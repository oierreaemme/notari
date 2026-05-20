# 16. Separate engine load from inference timeout

Date: 2026-05-17
Status: Accepted

## Context

On 2026-05-17, real-device testing surfaced a reproducible failure
pattern: four consecutive Italian voice notes captured in a single
session, the first three structured cleanly, the fourth fell back to
plain text with the debug card showing `Pass 1 failed (timeout after
68900ms)`. The transcript was short (~278 characters), the model was
already loaded and had just served three other inferences successfully,
and no exception was thrown — just a clean timeout.

The 68900 ms number is the exact value of
`coldStartBudgetFor(transcript)` for a 278-character note under the
pre-existing constants:

    COLD_START_BASE_MS + transcript.length * PER_CHAR_BUDGET_MS
    = 55_000 + 278 * 50
    = 68_900 ms

That match-to-the-millisecond ruled out "model genuinely took too long
to think". The budget timer expired before generation could complete
because something other than inference was consuming the budget. Tracing
the path of the fourth note showed:

1. After the third save, the app navigated
   `CaptureScreen → NoteDetailScreen`. This is a brief background
   excursion of the capture screen — short, but enough to trip
   `ComponentCallbacks2.onTrimMemory(TRIM_MEMORY_BACKGROUND)` on
   memory-constrained devices.
2. `LiteRtLmGemmaSession.onTrimMemory` (per ADR 0009) released the
   1.5 GB engine on that signal.
3. The user tapped Back to return to capture. `LifecycleResumeEffect` in
   `CaptureRoute` fired `viewModel.warmUpIfNeeded()`, which kicked off
   `session.warmUp()` fire-and-forget.
4. Before warm-up completed, the user dictated and pressed stop. The
   structuring flow entered `Pass 1`, whose `withTimeoutOrNull` started
   counting from that moment.
5. `generate()` saw `engineRef.get() == null` and re-entered
   `ensureEngineLoaded()`. The CAS-based publish allowed this — but it
   also meant the in-flight warm-up coroutine and the generate coroutine
   were both inside `engineFactory()` simultaneously. Both attempted
   `Backend.GPU().initialize()`, the second one lost the GPU device,
   and the recovery branch fell back to `Backend.CPU()`. The two engine
   instances were then partially serialized through ML Drift kernel
   compilation contention, dragging the effective load time toward the
   high end of the empirical 15-30s range.
6. By the time inference would have started, the 68.9-second budget had
   already been consumed by engine load (and racing engine loads).
   Withthe budget exhausted, `withTimeoutOrNull` returned null, the
   structuring use case followed its contract and produced a plain-text
   fallback, and the user saw an unstructured note for content that
   would have structured perfectly given another 10-15 seconds.

The fundamental design flaw the incident exposes: a single budget was
governing two operations with wildly different time profiles. Engine
load is **variable** (15-30s on Pixel 6a, more if GPU init races or
thermal throttling kicks in). Inference on a warm engine is
**predictable** (5-15s for typical structured-note output). Conflating
them meant any slow load consumed the inference budget, and on a slow
device or contended GPU there was nothing the inference budget could
do to save itself.

## Decision

Three coordinated changes split engine load out of the inference
budget, eliminate the race that doubled load latency, and stop the
over-eager memory release that triggered cold reloads in the first
place.

### 1. Warm-up happens BEFORE the Pass 1 timeout starts

`StructureNoteUseCaseImpl.invoke()` now calls
`session.warmUp()` inside its own `withTimeoutOrNull(60_000)` block
before entering the timed Pass 1 flow:

```kotlin
runCatching {
    withTimeoutOrNull(ENGINE_LOAD_BUDGET_MS) { session.warmUp() }
}
val pass1Outcome = runCatching {
    withTimeoutOrNull(pass1Budget) {
        session.generate(basePrompt.render(cleaned, now, existingTags))
    }
}
```

The warm-up budget is generous (60s — first-ever load on a cold device
can take 25-30s on Pixel 6a CPU fallback, plus headroom for thermal
slowness). It only expires if the model file is corrupt or the device
is in real trouble; in that case `generate()` surfaces the actual
failure on the next line and we fall back to plain text the normal way.

Once warm-up returns, Pass 1's budget governs only prefill + decode on
a warm engine, which is predictable enough to size confidently.
`COLD_START_BASE_MS` drops from 55s to 15s — the 40s difference is
exactly the engine-load slack that now lives in `ENGINE_LOAD_BUDGET_MS`.

### 2. Engine load is serialized with a Mutex

`LiteRtLmGemmaSession.ensureEngineLoaded()` becomes `suspend` and gates
its expensive section behind `engineLoadMutex.withLock`:

```kotlin
private suspend fun ensureEngineLoaded(): Engine {
    engineRef.get()?.let { return it }   // fast path, no lock
    return engineLoadMutex.withLock {
        engineRef.get()?.let { return@withLock it }   // re-check under lock
        val file = modelFileProvider.fileOrNull() ?: throw GemmaUnavailableException(...)
        val newEngine = engineFactory(file)
        engineRef.set(newEngine)
        newEngine
    }
}
```

Concurrent callers (`warmUp()` from `LifecycleResumeEffect` plus
`generate()` from `structure()`) now await the single load instead of
racing it. The AtomicReference is kept on top of the mutex for the
fast path so an already-published engine returns without acquiring the
lock at all.

The previous CAS-then-close pattern is removed: it was correct in
terms of memory but did not prevent the two engineFactory invocations
from running in parallel, which was the actual cost.

### 3. `onTrimMemory` only releases on `TRIM_MEMORY_COMPLETE`

The threshold in `LiteRtLmGemmaSession.onTrimMemory` moves from
`TRIM_MEMORY_BACKGROUND` to `TRIM_MEMORY_COMPLETE`. The former fires
whenever the app is backgrounded — including the brief
`Capture → NoteDetail → Capture` round trips that punctuate normal
use. `TRIM_MEMORY_COMPLETE` is the "system is actively killing
background processes to reclaim RAM" signal; at that level dropping
1.5 GB is the right thing to do. Below that, we keep the engine and
accept the memory cost in exchange for inference latency that doesn't
collapse mid-session.

This change does mean the app is a less polite background citizen on
memory-constrained devices — but only outside the
`TRIM_MEMORY_RUNNING_CRITICAL` / `TRIM_MEMORY_COMPLETE` window, where
the OS is not actually starving. ADR 0009 has been amended to point
to this ADR.

## Alternatives considered

- **Increase the Pass 1 budget further** (e.g. 90s base). This was the
  cheap fix and was rejected. It papers over a structural problem,
  worsens user-facing latency on every call to "protect" against a
  rare slow-load tail, and would have hit MAX_PASS_BUDGET_MS for any
  notable transcript length. The split addresses the root cause.
- **Keep the engine in a foreground service**. Heavy compliance lift,
  requires a persistent notification, and is overkill: under normal
  use the engine just needs to survive a 1-second app switch, not
  weeks of background life. Re-evaluated if real-device usage shows
  TRIM_MEMORY_COMPLETE firing during active sessions.
- **Pre-warm at `Application.onCreate()`**. Considered, rejected: pays
  the 1.5 GB cost for every cold launch even when the user never
  records (settings-only sessions, privacy-info-only visits). The
  per-screen `warmUpIfNeeded()` hook already covers the realistic
  cases without that overhead.

## Consequences and trade-offs

- The user-visible result on the incident transcript: structuring
  completes in ~25-35s (warm engine + inference) instead of timing out
  at 68.9s.
- The "Last model response (debug)" card will rarely show
  `Pass 1 failed (timeout after Xms)` anymore; when it does, the value
  will be small enough that the cause is clearly inference-side, not
  load-side.
- Memory footprint of a backgrounded app is higher: ~1.5 GB stays
  resident across short background trips. On 4 GB-RAM devices this
  raises the marginal probability of process death from a sustained
  OOM event, but does not change behavior under genuine memory
  pressure (TRIM_MEMORY_COMPLETE still releases).
- The serialized load eliminates the spurious GPU→CPU fallback that
  was doubling load times mid-session. On Adreno-class GPUs this is
  the dominant latency win.
- The race-then-close path in `ensureEngineLoaded` is gone. Anyone
  reading the file after this ADR should see one engineFactory call
  per logical load event, not two.

## Follow-ups

- Add a startup-time A/B counter (local-only, never reported) that
  tracks the empirical distribution of `engine.warmUp()` durations on
  real devices, so future budget tuning is data-driven instead of
  anecdotal.
- Consider releasing the engine on a 5-minute idle timer when the
  capture screen is not in resumed state — covers the "user left
  capture and went deep into notes" case where holding 1.5 GB is
  wasted.

## Amendment — 2026-05-17 evening: backend-aware budgets

The morning fix above eliminated cold-load contamination of the Pass 1
budget. Real-device testing the same evening surfaced a second class
of timeout on the same incident transcript: a 470-char Italian "sogno"
note timed out at exactly the new Pass 1 budget value
(`15_000 + 470*50 = 38_500ms`, debug card showed 38450ms). Logcat
confirmed the model produced a valid JSON response — but ~78 seconds
after engine init, well past the 38.5s Pass 1 budget. A second attempt
on the same device produced an identically valid response 77 seconds
later. Both inferences succeeded; the Kotlin coroutine cancelled them
mid-flight and the use case fell back to plain text.

Root cause: on the user's device, `Backend.GPU()` initialization fails
with an internal LiteRT-LM error
(`llm_litert_compiled_model_executor.cc:1928`), and the session correctly
falls back to `Backend.CPU()`. The morning fix's per-char budget
(`PER_CHAR_BUDGET_MS = 50`) was tuned to a warm GPU engine: ~3-5s
baseline prefill + ~30ms per transcript char + 3-8s decode = ~22s for a
470-char note. On the CPU fallback path, the actual cost is
~10-15s baseline prefill + ~150-180ms per transcript char (the ~8KB
static prompt re-prefills cold on every `Conversation` because there is
no KV cache reuse across calls) + 8-15s decode = ~78s for the same
input. A single per-char constant cannot cover both paths.

### Decision

The budget formula becomes backend-aware on BOTH terms (baseline AND
per-char). `GemmaSession` exposes a new `backend()` method returning
`InferenceBackend { GPU | CPU | UNKNOWN }`; `LiteRtLmGemmaSession`
records which `Backend.GPU()` / `Backend.CPU()` path `engineFactory()`
resolved to and reports it. The structuring use case reads
`session.backend()` after `warmUp()` and picks the budget shape:

| Backend | Pass 1 baseline | Pass 2 baseline | Per-transcript-char |
|---------|-----------------|-----------------|---------------------|
| GPU     | 15 s            | 8 s             | 30 ms               |
| CPU     | 60 s            | 50 s            | 150 ms              |
| UNKNOWN | 60 s            | 50 s            | 150 ms (treated as CPU) |

The split between baseline and per-char matters: on CPU the dominant
cost is the **static-prompt prefill** (~8 KB / ~2000 tokens, ~46 s
empirically), which is independent of user-transcript length and is
paid on every `Conversation` because LiteRT-LM does not reuse KV cache
across calls. Modelling this as per-char alone undershoots short notes
(the original morning fix sized a 278-char note at 56.7 s, while the
empirical need is ~65 s — exactly the 2026-05-17 evening incident).

`MAX_PASS_BUDGET_MS` is raised from 150s to 250s so CPU-path long-note
inference (1500-2000 char range) fits inside the cap instead of being
clipped mid-decode. Past 250s the experience is so degraded that
aborting is the right call regardless of backend.

Empirical fit on the Pixel test device (CPU fallback):
`T(L) ≈ 46s + 0.068s × L_chars`. Two data points fit: 470-char→78s and
278-char→65s (estimated; 56.7s budget did NOT cover it).

Concrete budgets after this amendment:

```
                  GPU                         CPU
  200-char   P1  21s / P2  14s        P1  90s / P2  80s
  278-char   P1  23s / P2  16s        P1 102s / P2  92s   ← incident note
  470-char   P1  29s / P2  22s        P1 131s / P2 121s   ← was 78s empirically
 1000-char   P1  45s / P2  38s        P1 210s / P2 200s
 2000-char   P1  75s / P2  68s        P1 250s (capped) / same
```

The cost of the change: CPU users wait longer to see "Pass 1 failed"
when something is genuinely wrong with the model (e.g. corrupt file
producing endless garbage). On balance this is the right trade — a
spurious fallback on content that would have structured correctly is
the worst possible outcome, far worse than waiting an extra minute
before surfacing a real failure.

### Consequence: UX surface area

CPU-path users will see structuring times of 60-90s for typical notes.
The existing "Xs elapsed" counter on the structuring pane keeps them
informed; the existing "Long note — structuring may take a bit longer"
advisory above the recording UI applies at the same 2000-char
threshold. Future work: detect CPU fallback at session start and show a
discreet one-time advisory ("Notari is running on CPU on this device —
structuring will be slower than on GPU-enabled phones"), so the user
isn't left guessing why the same app feels different on different
hardware.

### Tests

`OrderTrackingSession` in `StructureNoteUseCaseImplTest` now also
records calls to `backend()` and pins the ordering
`warmUp → backend → generate` so a refactor cannot accidentally read
backend before the engine is loaded (which would always read UNKNOWN
and forfeit the GPU optimization on that path).
