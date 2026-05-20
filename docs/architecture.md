# Architecture overview

This document explains the runtime shape of Notari: how
the modules cooperate, the data that flows between them, and — most
importantly — the lifetime of the audio buffer.

For the rationale behind any specific choice, see the
[Architecture Decision Records](decisions/).

## Module graph

```
                         ┌────────────────────┐
                         │       :app         │
                         │  (Hilt root, nav)  │
                         └────────┬───────────┘
                                  │
        ┌─────────────────────────┼──────────────────────┐
        ▼                         ▼                      ▼
┌──────────────┐         ┌──────────────┐         ┌─────────────────┐
│ :feature:    │         │ :feature:    │         │ :feature:       │
│   capture    │         │   notes      │         │   noteDetail    │
└──────┬───────┘         └──────┬───────┘         └─────────┬───────┘
       │                        │                           │
       │                ┌───────┘                           │
       ▼                ▼                                   ▼
       └─────────► :core:design (theme, components) ◄───────┘

       ┌──────────────────────┬──────────────────────┐
       │                      │                      │
       ▼                      ▼                      ▼
┌──────────────┐      ┌──────────────┐      ┌──────────────────┐
│  :core:asr   │      │ :core:       │      │  :core:database  │
│ SpeechRecogn │      │  inference   │      │  Room + DAOs     │
│              │      │  Gemma 4 E2B │      │                  │
└──────────────┘      └──────────────┘      └──────────────────┘
       │                      │                      │
       └──────────────┬───────┴──────────────┬───────┘
                      ▼                      ▼
              ┌───────────────────────────────────┐
              │            :core:common           │
              │  Result, dispatchers, domain      │
              │  models (Note, Tag, Language...)  │
              └───────────────────────────────────┘
```

`:feature:settings` and `:core:datastore` are omitted for clarity but
follow the same shape: settings depends on `:core:datastore` and
`:core:common`.

## End-to-end flow: capture → save

```
User taps record
    │
    ▼
CaptureViewModel.onIntent(Record)
    │
    ▼
SpeechToTextSession.start(language)         ← :core:asr
    │   audio frames live ONLY in RAM
    ▼
Flow<TranscriptChunk>
    │
    ▼
User taps stop  →  CaptureViewModel.onIntent(Stop)
    │
    ▼
String transcript (in memory only)
    │
    ▼
StructureNoteUseCase(transcript)            ← :core:inference
    │
    ▼
GemmaSession.runStructuringPrompt(...)
    │
    ▼
Raw model response (String)
    │
    ▼
StructuredNoteParser.parse(raw)             ← :core:inference
    │
    ▼ (success)        ▼ (failure)
StructuredNote      retry once with stricter prompt
    │                   │
    │                   ▼ (failure)
    │                Note(structured = false, body = transcript)
    │                   │
    └─────────┬─────────┘
              ▼
NoteRepository.insert(note)                 ← :core:database (Room)
              │
              ▼
NavController.navigate(NoteDetail(noteId))
```

## Audio buffer lifetime — the privacy core

The audio buffer is the single most sensitive piece of state in this
application. v1 uses Android's `SpeechRecognizer` for transcription
(per ADR 0003), which means **the audio buffer is owned and managed by
the OS recognizer service**, not by our code. We never see the bytes
directly. The lifetime is:

```
start()                                                 stop()
   │                                                       │
   ▼                                                       ▼
SpeechRecognizer.createSpeechRecognizer(context)        recognizer
   ├─ OS allocates an internal audio buffer (we don't        .stopListening()
   │  hold any ref to it, can't read it, can't write it).    .destroy()
   ├─ Recognizer emits text chunks → our `callbackFlow`.       ↑
   ├─ We accumulate the text in `lastTranscript: String`.      │
   └─ The audio bytes stay scoped to the recognizer service. ─┘
                                                             │
                                                             ▼
                            OS reclaims the recognizer's audio buffer.
                            We zero `lastTranscript` to "" after read.
```

**Invariants enforced:**

1. `:core:asr` never instantiates `AudioRecord`, `MediaRecorder`,
   `MediaCodec`, `MediaMuxer`, `FileOutputStream`, or any class that
   could persist audio. A Detekt rule (`style.ForbiddenImport`) blocks
   the imports at compile-time, and a source-grep test
   (`NoAudioPersistenceTest`) catches fully-qualified usages that
   bypass an import statement.
2. We pass `RecognizerIntent.EXTRA_PREFER_OFFLINE = true` so the
   recognizer cannot silently fall back to a cloud transcription path.
3. We call `recognizer.destroy()` on stop AND on the callbackFlow's
   `awaitClose`. The OS releases its native audio resources promptly.
4. The CI privacy gate confirms `INTERNET` is not in the merged
   manifest, so even a misbehaving native library cannot exfiltrate
   the buffer. See `scripts/check-no-internet-permission.sh`.

Note carefully what we **do not** claim: we do not zero a ShortArray
because we don't have one to zero. The OS holds the audio. Destroying
the recognizer is the strongest deletion signal we have. The day v2
moves to Gemma 4 E2B audio-native input (per ADR 0003) the buffer will
be ours to manage — and at that point we'll allocate a `DirectByteBuffer`
specifically so we *can* zeroize it post-inference.

## On-device temporal reasoning

The structuring prompt (`structure_note_v4.txt`) embeds the current
wall-clock time when it renders:

```
CURRENT TIMESTAMP: 2026-05-14T16:42:00+02:00
USER TIMEZONE: Europe/Rome
```

This is supplied at call time by `StructureNoteUseCaseImpl` via the
injected `Clock`. The model anchors relative datetime references in the
transcript ("domani alle 15", "venerdì prossimo", "tomorrow at 3pm",
"in due ore") to absolute ISO-8601 timestamps in the `mentions` array
of its JSON output. Vague references ("una di queste sere", "soon")
stay `null` — the prompt's anti-hallucination clause makes this
explicit: "never invent".

Downstream, `StructureNoteUseCaseImpl.tryParseInstant` accepts three
formats Gemma can emit and parses each correctly into a `java.time.Instant`:

- ISO instant with Z: `2026-05-14T13:30:00Z` → `Instant.parse`
- Offset datetime: `2026-05-14T15:00:00+02:00` → `OffsetDateTime.parse`
- Date only: `2026-05-14` → `LocalDate.atStartOfDay(systemDefault())`

See ADR 0010 for the full decision and the prompt history that led to v4.

## Inference engine lifecycle

`LiteRtLmGemmaSession` (in `:core:inference`) wraps the LiteRT-LM
runtime with:

- **Lazy load** via an `AtomicReference<Engine?>` + CAS-protected
  initialization. The ~1.5 GB engine is allocated only when a `generate`
  call is made (or when `warmUp` is called from a feature ViewModel's
  init block to hide the cost behind UI latency).
- **Backend probing**, in priority order:
  1. `Backend.GPU()` — fastest. May fail on some OEM driver / kernel
     combos (e.g. Pixel 6a's Mali-G78 with LiteRT-LM 0.11 — known issue).
  2. `Backend.CPU()` — universal fallback.
  Each probe is wrapped in `runCatching`; logs which backend the engine
  actually came up on. See ADR 0011.
- **MTP speculative decoding** via `ExperimentalFlags.enableSpeculativeDecoding = true`
  before each engine creation. Wrapped in its own `runCatching` so a
  patch-release rename or removal of the flag does not break the build.
  Requires the model file dated 2026-05-05 or later (has MTP drafter
  heads embedded). ~25% decode speedup on CPU, ~2-3× on GPU per Google's
  benchmarks.
- **Memory pressure release** via `ComponentCallbacks2.onTrimMemory`.
  At `TRIM_MEMORY_BACKGROUND` or worse, we close the engine and null
  the `AtomicReference`. The next `generate` call lazily reloads. See
  ADR 0009.
- **Single-pass JSON parsing** with `Moshi.lenient()`. On parse failure
  we retry once with the `StricterPromptTemplate` (Pass 2). On a second
  failure or a Pass-1 timeout, we save the transcript verbatim as a
  plain-text note (`structured = false`) — per ADR 0005, the user is
  never blocked by a model failure.
- **Transcript-scaled timeouts**. `STRUCTURING_TIMEOUT_MS` no longer
  exists as a constant; the budget is now
  `coldStartBudgetFor(transcript)` and `warmBudgetFor(transcript)`,
  linear in transcript length (30s + 50ms/char for cold, 8s + 50ms/char
  for warm, capped at 150s). See ADR 0009.

## Layering rules — recap

- `:feature:*` depends on `:core:common` + `:core:design`. It MAY
  depend on a single `:core:*` data module (e.g. `:feature:capture`
  needs `:core:asr` and `:core:inference`). It NEVER depends on
  another `:feature:*`.
- `:core:database`, `:core:inference`, `:core:asr`, `:core:datastore`
  depend ONLY on `:core:common`. They expose Hilt-bound interfaces.
- `:core:common` has no Android dependencies and is buildable as a
  pure JVM module (today it's an Android library only because it's
  cheaper to keep AGP-only tests in one place; it can be split out
  if we need pure-JVM tests).

## State management

See [ADR 0006](decisions/0006-mvi-state-shape.md). Every feature
ViewModel exposes:

```kotlin
val uiState: StateFlow<XxxUiState>
val uiEvents: SharedFlow<XxxUiEvent>
fun onIntent(intent: XxxUiIntent)
```

No exceptions, no variants.

## Testing strategy

- **Domain + data layers**: TDD, JUnit 5 + Truth + Mockk + Turbine.
  Coverage gate at >80% line coverage on these layers.
- **Compose UI**: Roborazzi screenshot tests for visual regression,
  Compose UI tests for interaction. Not TDD — we build first, snapshot
  second.
- **Prompt evaluation**: a separate suite under
  `core/inference/src/test/resources/prompt-eval/` with real
  transcripts in 6 languages and structural assertions on the parsed
  output. Run on every prompt change.
