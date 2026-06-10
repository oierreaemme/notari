# 27. Audio buffer ownership: atomic snapshot-swap and a transcription-free discard path

Date: 2026-06-10
Status: Accepted

## Context

The 2026-06-10 security/performance review (docs/reviews/2026-06-10-security-
performance-review.md) found that the batch capture pipeline violated cardinal
rule 2 (zero audio persistence — *in spirit*: PCM must not linger in RAM
un-overwritten) on several paths, and contained a data-loss race:

1. **Cancel→restart race (data loss).** `cancelRecording()` reset the UI to
   Idle immediately and launched `session.stop()` fire-and-forget. `stop()`
   ran the whisper transcription on the *discarded* audio (seconds of CPU)
   and only afterwards zeroed and cleared `captured` — a **shared field** of
   the session singleton. A user who cancelled and quickly re-started
   dictation had the new take's chunks zeroed and cleared mid-recording by
   the delayed teardown of the previous take. The new note came out empty or
   silent-truncated.
2. **Un-zeroed PCM on abandonment.** `awaitClose → stopCapture()` did not
   zero `captured`; `onCleared()` only cancelled the job. A ViewModel death
   mid-recording left the full dictation un-overwritten in the heap. `start()`
   cleared the list without zeroing the chunks first.
3. **Un-zeroed float copy.** `WhisperBatchTranscriber` built a `FloatArray`
   image of the entire dictation and never overwrote it.
4. **Discarded audio was processed.** The cancel path fed the abandoned take
   to whisper anyway — wasted CPU/battery and unnecessary processing of
   private audio the user asked to throw away.
5. **Main-thread teardown.** `awaitClose` ran `Thread.join(500ms)` +
   `AudioRecord.release()` + BT route clear on the collector context (Main):
   up to ~500 ms of jank on every stop/cancel.

## Decision

### 1. Buffer ownership rule: consumers swap, never share

`captured` is now `private var`; the only way to consume it is
`takeCaptured()`, which — under `capturedLock` — stops accepting new chunks,
swaps the field for a fresh list, and returns the old one as a **privately
owned snapshot**. `stop()` and `discard()` operate exclusively on their
snapshot. A delayed teardown of take N can no longer touch take N+1's data,
whatever the interleaving.

The reader thread's append is gated on an `accepting` flag flipped under the
same lock *before* the reader join, so a reader that outlives the 500 ms join
cannot leak un-zeroed copies behind a snapshot.

### 2. `discard()`: the transcription-free teardown

`SpeechToTextSession` gains `suspend fun discard()`: stop capture, zero every
chunk, drop the references — **never** call the transcriber. It is the
mandatory path for user cancels (`cancelRecording`) and VM teardown
(`onCleared`, on a `NonCancellable` scope since `viewModelScope` is already
cancelled there). `stop()` keeps its contract (transcribe, then zero — now
also on the exception path, via `finally`).

`CaptureViewModel` keeps the in-flight discard as `teardownJob` and
`startRecording()` joins it before collecting a new session, serializing
teardown→restart at the owner level. The join is bounded by the reader-thread
join (~500 ms), not by whisper, because `discard()` doesn't transcribe.

### 3. Zeroing on every path

`start()` zeroes leftover chunks before dropping them (defensive);
`stop()` zeroes snapshot + concatenated PCM in `finally`;
`discard()` zeroes immediately; `WhisperBatchTranscriber` zeroes its float
copy in `finally`. The `NoAudioPersistenceTest` source-scan guard (`.fill(0)`
in every AudioRecord-touching file) still holds.

### 4. Teardown off the main thread, on session-local references

`awaitClose` now only sets the stop flags synchronously and hands the heavy
teardown (join, `AudioRecord.stop/release`, BT clear) to a short-lived
background thread operating on the **locals** of that `start()` invocation —
not on the shared fields, which a subsequent `start()` may have already
replaced. `releaseCaptureResources()` is serialized via `teardownLock`
because `AudioRecord` is not thread-safe and two paths (awaitClose thread,
concurrent `stop()`/`discard()`) can race to release the same recorder.

## Consequences

- Cancel is now cheap: no whisper pass on abandoned audio (was: seconds of
  4-thread CPU burn per cancel).
- The cancel→restart data-loss race is closed both at the session level
  (snapshot ownership) and at the VM level (teardown join).
- PCM is overwritten on **every** exit path: save, cancel, VM death, flow
  cancellation followed by stop, exceptions during transcription.
- Stop/cancel no longer jank the UI thread.
- `SpeechToTextSession` implementations that hold no audio get `discard()`
  as a default no-op; the interface change is source-compatible for them.

## Tests

- `CaptureViewModelTest`: cancel calls `discard()` and never `stop()`;
  restart after cancel awaits the in-flight discard before `start()`.
- `StaticPromptTemplateTest` (same review, separate fix): markers inside the
  transcript are not expanded — transcript is substituted last.
- The session's swap/zero internals need an instrumented test (AudioRecord);
  tracked with the existing instrumented-coverage follow-up of ADR 0018.
