# 28. Process-lifecycle engine release, VM-owned keep-alive, recording duration cap

Date: 2026-06-10
Status: Accepted

## Context

Second batch of the 2026-06-10 review (docs/reviews/2026-06-10-security-
performance-review.md, items #7, #10, #11, #12, #13, #14). Three of these are
behavioural decisions worth recording; the rest are mechanical hardening noted
at the end.

## Decisions

### 1. Engine release via ProcessLifecycleOwner + 5-minute idle timer (#11)

ADR 0016 §3 moved the engine release trigger to
`onTrimMemory(TRIM_MEMORY_COMPLETE)` — which Android documents as **never
called on API 34+**. On modern devices the 1.5 GB engine therefore survived in
the background indefinitely; the polite-citizen behaviour ADR 0016 intended
had silently become "never release".

The Application now observes `ProcessLifecycleOwner`: on `ON_STOP` (the whole
app left the foreground — NOT fired for in-app navigation or config changes,
so the capture→detail→capture round trips that caused the 2026-05-17 incident
never schedule a release) it starts a 5-minute timer; `ON_START` cancels it;
expiry calls `GemmaSession.release()`.

- `release()` moved onto the `GemmaSession` **interface** (default no-op) so
  the app module doesn't need the concrete LiteRT-LM type. It is
  generation-safe per the review-#4 fix: if a native inference is in flight it
  defers the close to the generation's `finally`.
- 5 minutes balances reload cost (15-30 s on the Pixel 6a) against being an
  OOM-kill magnet on 4 GB devices. A screen-off hands-free dictation longer
  than 5 min may release the engine mid-capture; the structuring flow's
  `warmUp()` budget (ADR 0016) absorbs the reload, costing latency, not data.
- `onTrimMemory(TRIM_MEMORY_COMPLETE)` stays as belt-and-braces for API < 34.

### 2. Capture keep-alive owned by the ViewModel, not the composition (#10)

The microphone FGS used to be started/stopped by a `LaunchedEffect` in
`CaptureRoute` — alive only while the composable is composed. Capture
legitimately outlives the composition (hands-free with the user browsing
notes), so a capture that ended off-screen leaked the service + its
notification until the user returned.

A `RecordingKeepAlive` interface (feature-internal, Hilt-bound to
`ServiceRecordingKeepAlive` wrapping the FGS) is now driven by the VM:
`uiState.map { phase.isCaptureActive }.distinctUntilChanged()` →
start/stop. `Phase.isCaptureActive` = Preparing ∨ Recording ∨ Transcribing.
`onCleared()` stops it unconditionally. The route keeps only the
POST_NOTIFICATIONS request (a UI concern). The abstraction exists so the VM
stays free of Android service plumbing and unit-testable.

### 3. Hard recording cap: 15 minutes with auto-stop (#12)

PCM accumulates at ~1.9 MB/min and transcription transiently needs ~4× that
(chunks + concatenated copy + whisper's float image). Unbounded screen-off
dictation was an OOM waiting to happen — and an OOM loses *everything*
captured. At 15 minutes the VM auto-stops, tells the user why
("Maximum recording length reached — transcribing what was captured."), and
runs the normal transcribe→structure pipeline. Nothing is lost; the peak
(~115 MB) stays safe next to the LLM engine. Driven by the
`capturedDurationMs` flow added for the duration-based long-note advisory
(ADR 0027 batch).

## Mechanical hardening in the same batch

- **#7** — `LiteRtLmGemmaSession` no longer logs the full model response in
  debug builds by default (`LOG_MODEL_RESPONSE = false`, length-only
  breadcrumb kept). Release was already stripped (ADR 0021); this protects
  the daily-driver debug builds' logcat ring buffer.
- **#14** — ProGuard: the blanket `-keep` for `com.google.mediapipe.**` and
  `com.google.android.gms.**` (no longer direct dependencies, ADR 0008)
  became `-dontwarn`. Smaller APK, R8 free to shrink.
- **#13** — The capture VM no longer snapshots every `Note` to build the
  EXISTING_TAGS corpus: new `NoteDao.observeTagValuesWithLanguage()` join
  query → `NoteRepository.observeTagCorpus(): Flow<List<TagUsage>>` (default
  derived from `observeAll()` so fakes keep compiling).

## Explicitly NOT done

- **#15 (cache `isAvailable()` I/O)** — wontfix for now: the whisper
  provider's `listFiles()` runs only at repository seeding and import/delete,
  not in a hot path, and caching would break the adb-push dev flow (a pushed
  model must be picked up without restarting the app).

## Tests

- `CaptureViewModelTest`: keep-alive follows the phase machine (one start per
  take, stop on cancel); duration cap auto-stops, transcribes and structures.
- `NoteRepositoryImplTest`: `observeTagCorpus` emits distinct (tag, language)
  pairs from the join query.
- The idle-release observer is Application-level glue over framework
  lifecycle; validated on-device (background the app > 5 min → next capture
  logs a fresh engine load).
