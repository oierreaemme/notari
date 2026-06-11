# 25. Speaker diarization: considered, parked

Date: 2026-06-05
Status: Parked (deferred — revisit only under the conditions below)

## Context

Diarization ("who spoke when") came up twice: as a passing idea of our own,
and as explicit external interest (a comment on the Notari LinkedIn post,
June 2026, asking for it — and for iOS). It was never previously discussed or
rejected on record, so this note exists to capture the reasoning instead of
re-deriving it next time.

Notari today is built for **single-speaker** personal notes (the author
dictating). Diarization is the multi-speaker case: meetings, interviews — the
transcript attributed to Speaker 1 / Speaker 2.

## Decision

**Park it. Not in scope now. Notari stays single-speaker.**

## Why parked, not rejected

It is technically feasible on-device, on **both Android and iOS**, but it is a
real chunk of work, not a flag:

- **whisper.cpp does not diarize.** It produces words, not speaker labels.
  Diarization is a separate pipeline: VAD → speaker embeddings (a neural net) →
  clustering of those embeddings into speakers.
- **Realistic on-device path:** something like `sherpa-onnx` (a segmentation
  model + a speaker-embedding model via ONNX Runtime). Core is C/C++, like
  whisper.cpp, so it ports across Android and iOS and could sit behind the
  existing `SpeechToTextSession`-style seam.
- **Cost on our reference device.** Extra models mean more RAM and CPU. The
  Pixel 6a is already CPU-bound on Gemma (ADR 0016, 0017); a second ML stage in
  the capture path would contend for the same cores.
- **Accuracy ceiling.** Diarization degrades hard on overlapping speech,
  far-field mic, and narrow-band Bluetooth HFP audio — the same channel
  limitation already documented for whisper itself (ADR 0018, amendment
  2026-05-29). On "dirty" phone audio the speaker labels would often be wrong.
- **Scope.** Single-speaker notes and multi-speaker meeting transcription are
  different product surfaces. Adding diarization is a product expansion, not a
  feature toggle.

Privacy is **not** the blocker: a diarization pipeline would run fully
on-device, no new network, so it does not touch Pillar 2 or the no-`INTERNET`
rule.

## Revisit when

All three hold:

1. A deliberate product decision to support meetings/interviews (not just
   "someone asked").
2. A reference device with headroom — a working GPU path or a newer SoC — so
   the diarization stage does not thrash against Gemma.
3. We accept the accuracy ceiling on phone-grade and Bluetooth audio, or add
   PCM pre-processing to mitigate it.

## Implementation sketch (if revisited)

Keep whisper for the words. Add a diarization stage behind the ASR seam that
emits speaker-labelled segments, then pass the labels into the structuring
prompt so Gemma can attribute the Markdown by speaker. No change to the
privacy invariants.
