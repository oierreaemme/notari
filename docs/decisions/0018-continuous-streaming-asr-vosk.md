# 18. Continuous-streaming ASR: replace SpeechRecognizer with Vosk

Date: 2026-05-26
Status: Proposed (supersedes the v2 direction of ADR 0003)

## Context

Real-world use on the Pixel 6a (Mario, dictating personal notes — often
in the car, hands-free and eyes-off-screen) surfaced a defect that ADR
0003 did not weigh: **long dictations lose words.**

The cause is structural, not a bug in `AndroidSpeechToTextSession`.
Android's `SpeechRecognizer` is an IPC request/response service that
*owns the microphone itself* and operates in discrete utterance segments.
It was designed for short voice commands and search queries, not
continuous transcription. It ends a segment whenever it *thinks* the
speaker paused (`onResults`) or on `ERROR_NO_MATCH` / `ERROR_SPEECH_TIMEOUT`.
Our session wrapper already does everything possible to paper over this —
it salvages each segment's partial before restarting (`commitPendingPartial`),
calls `cancel()` before `startListening()` to avoid `ERROR_RECOGNIZER_BUSY`,
guards restarts with an `AtomicBoolean`, and backs off on transient errors.
Despite all of it, **two failures remain irreducible on this API:**

1. **The restart gap.** Between the end of one segment and the moment the
   recognizer is actually listening again (the 50 ms / 300 ms restart delay
   *plus* the service's own re-init latency), it is the OS that stops
   capturing audio. Any speech in that window is gone — no partial-salvage
   can recover audio that was never captured. This is what eats words in a
   long, continuous dictation.
2. **The audible earcons.** `SpeechRecognizer` plays system start/stop
   sounds at each segment boundary. In continuous mode the user hears them
   at every restart, and the only mitigation (muting system streams) is an
   OEM-variant hack. The user reports having to keep system volume at zero.

For a hands-free, eyes-free scenario (driving) a visual "listening paused"
indicator is no help. The user's requirement is explicit and reasonable:
**dictate long notes reliably, without watching the screen.** `SpeechRecognizer`
cannot meet it. Notably, Google's own continuous dictation (Recorder,
Gboard voice typing) does **not** use the public `SpeechRecognizer` API,
for exactly this reason.

Two facts also change the calculus that ADR 0003 was decided under:

- **The deadline driver is gone.** ADR 0003 chose `SpeechRecognizer` partly
  to de-risk the May 24 submission. That window has closed (today is
  2026-05-26). The optimisation target is now Notari as a tool its author
  uses daily, not demo optics under deadline.
- **The device is CPU-bound on the LLM.** On the Pixel 6a, LiteRT-LM's GPU
  init fails and Gemma E2B runs on CPU (ADR 0011, ADR 0016, ADR 0017). The
  structuring step already saturates the cores. Any ASR engine we add must
  *not* contend with Gemma for CPU — which rules out the heavy options
  (whisper.cpp, Gemma-audio) as the everyday capture path.

## Decision

**Replace `SpeechRecognizer` with a continuous-capture + streaming-decode
pipeline, backed by Vosk, as the production ASR for Notari.**

The architectural principle: **own the microphone continuously and decode
in a stream.** A single `AudioRecord` session runs for the whole dictation
and writes PCM frames into an in-memory ring buffer; a streaming recogniser
consumes those frames without interruption. Because capture never stops,
the restart gap — the thing losing words today — cannot occur. There are no
segments, no earcons, and no need for any "still listening" cue.

The swap is contained by the existing seam. `:core:asr` already exposes the
`SpeechToTextSession` interface (one `Flow<TranscriptChunk>` + `rmsDb` +
`stop()`), and `FakeSpeechToTextSession` proves the seam is real. We add a
`VoskSpeechToTextSession` implementing the same interface; `CaptureViewModel`
and the capture screen are untouched. `AndroidSpeechToTextSession` stays in
the tree as a fallback for devices where the Vosk model is unavailable.

**Why Vosk specifically, for this case:**

- **It is built for this.** Vosk does true, unbounded, real-time streaming
  dictation. whisper.cpp is window-based (~30 s) and Gemma-audio is
  block-based; both reintroduce latency and complexity precisely in the
  long, continuous, hands-free scenario we are trying to fix.
- **It does not fight Gemma for the CPU.** Vosk's small acoustic models run
  in real time on modest CPU. On a Pixel 6a already running Gemma on CPU,
  this is the deciding factor: whisper.cpp or a second Gemma pass in
  continuous capture would thrash the cores and trigger the throttling we
  fought in ADR 0016 / 0017.
- **Offline, multilingual, low integration risk.** Per-language models cover
  all six v1 languages (en, it, es, fr, de, pt); the Android library handles
  mic capture and streaming; it slots behind the existing interface.

**Privacy (Pillar 2) is preserved — and strengthened.** Owning the
`AudioRecord` buffer in RAM, overwriting it after decode, never touching
disk, satisfies the no-audio-persistence rule (ADR 0002). It is in fact
*more* auditable than `SpeechRecognizer`, where the audio buffer is owned by
a system service we cannot inspect. No new permission is added beyond
`RECORD_AUDIO`; still no `INTERNET`.

## Alternatives considered

- **Keep `SpeechRecognizer` and mitigate (mute streams + visual cue).**
  Rejected as the primary fix. It addresses the earcons but cannot close the
  capture gap, and the visual cue is useless eyes-free. It does not meet the
  requirement. (We may still apply earcon muting to the fallback path.)
- **whisper.cpp via JNI.** Best raw accuracy, especially for accented/noisy
  (in-car) audio. Rejected as the *v-next* engine because: it is window-based
  (not true streaming), needs VAD + sliding-window plumbing, and its CPU/
  battery cost would contend with Gemma on the reference device. **Kept on
  the roadmap as an accuracy upgrade behind the same interface**, to revisit
  if Vosk's in-car accuracy proves insufficient.
- **Gemma 4 E2B audio-native (the v2 plan from ADR 0003).** Reconsidered and
  deprioritised. Gemma already does the high-value work (structuring); using
  it for ASR too would be slower and more fragile on the CPU-bound device,
  and the LiteRT-LM build we ship has known gaps (e.g. no inference cancel
  token, ADR 0017). Block-based, not a low-latency streaming dictation
  engine. Parked as a longer-term (v3) exploration, not the capture path.
- **ADR 0003's rejection of Vosk reversed.** ADR 0003 rejected Vosk on
  "model size per language is non-trivial and the API is heavier than
  SpeechRecognizer." Per-language model size is real but acceptable for an
  app already accepting a >1 GB bundle for the LLM (CLAUDE.md §11), and the
  per-language constraint maps cleanly onto the language model we already
  have: the language pin (and, in Auto, the device locale per ADR 0017)
  selects which Vosk model to load. The "heavier API" cost is dwarfed by the
  value of actually meeting the long-dictation requirement.

## Consequences and trade-offs

- **The restart gap and earcons are eliminated by construction** — the core
  user complaint is resolved, not patched.
- **Per-language model management.** Vosk loads one model per language, so
  switching language means switching model. We tie model selection to the
  existing language pin / device-locale resolution; no mid-utterance
  cross-language auto-detect (which the v1 ASR never truly supported anyway).
- **Accuracy.** Vosk's accuracy is solid but below whisper's, and in-car
  noise will test it. We accept this for v-next, mitigate with VAD, and hold
  whisper.cpp as the upgrade path behind the same interface.
- **App size / model delivery.** Per-language models add to delivery weight.
  This interacts with the SAF-vs-Play-Asset-Delivery question (ADR 0008,
  CLAUDE.md §11) and should be settled alongside it.
- **`RECORD_AUDIO` is now used directly** by our own capture layer rather
  than implicitly via the recogniser service. The privacy story improves;
  the permission set does not grow.
- **Fallback retained.** `AndroidSpeechToTextSession` remains for devices
  without a usable Vosk model, so we never regress to "no transcription at
  all."

## Follow-ups

- Build a `VoskSpeechToTextSession` spike behind `SpeechToTextSession` and
  validate on the Pixel 6a: long (5+ min) continuous dictation with pauses,
  in-car noise, all six languages. **Flip this ADR to Accepted once the spike
  validates on-device.**
- Decide model delivery (bundle vs download) jointly with ADR 0008; quantify
  per-language model size against the existing LLM bundle budget.
- Re-test the privacy invariant under the new capture path: confirm no audio
  artefact reaches disk/cache during and after a recording (extend
  `NoAudioPersistenceTest`).
- If in-car accuracy is insufficient, open an ADR for the whisper.cpp upgrade
  behind the same interface.
- Update ADR 0003's status annotation and CLAUDE.md §4 (ASR v2 line) to point
  to this decision.

## Amendment — 2026-05-27: validated on-device; engine pivots to whisper.cpp (batch)

The plan above (continuous capture + **Vosk streaming**) was built and validated on the
Pixel 6a. The in-car *plumbing* all works: continuous `AudioRecord` capture, Bluetooth mic
routing, and a `microphone` foreground service for screen-off recording. But Vosk's accuracy
was the dealbreaker this ADR's "Alternatives considered" anticipated — the small Italian
model garbles English / code-switching and degrades on imperfect speech, fatal for tech notes.

Per this ADR's own fallback clause, we pivoted to **whisper.cpp behind the same
`SpeechToTextSession` seam, as a batch transcriber** (capture PCM to RAM, transcribe once at
stop). whisper is window-based, and hands-free in the car there is no value in a live
transcript anyway. **Validated on-device 2026-05-27** with multilingual `ggml-base.bin`:
Italian faithful, English brand/jargon (e.g. "Ableton") correct — night-and-day better than
Vosk. (One miss observed: "synth" → "sint".)

Build lesson: AGP compiles the native debug variant unoptimized, and whisper.cpp is ~20×
slower without `-O3`, so the CMakeLists forces `-O3` into the debug flags.

Trade-offs accepted: batch adds a transcription wait after stop (Vosk transcribed for free
during recording), so end-to-end is somewhat slower; mitigated by loading/freeing the model
around the call and, if needed, dropping base → tiny. The Vosk path stays in the tree,
unwired, for reference.

**Remaining before this flips to Accepted:** a real in-car test with Bluetooth + whisper
together; model delivery (shared with ADR 0008); a dedicated "Transcribing…" UI phase; and
removing the spike diagnostic logs + restoring release ABIs. A dedicated ADR 0020 may later
formalise "whisper.cpp batch" as the engine of record.
