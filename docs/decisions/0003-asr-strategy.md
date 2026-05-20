# ADR 0003 — ASR strategy: SpeechRecognizer for v1, Gemma audio for v2

- **Status:** Accepted
- **Date:** 2026-05-09
- **Deciders:** Notari engineering

## Context

We need on-device speech-to-text in six languages (en, it, es, fr, de,
pt). Two realistic options exist:

1. **Android `SpeechRecognizer`** — built into the OS, free, on-device
   on most modern devices (Pixel, recent Samsung), broad language
   coverage, predictable streaming results. The "free" word is
   important: no model size in our APK.
2. **Gemma 4 E2B audio-native input** — promising, lets us drop a whole
   library, gives us cross-language consistency, but is novel territory
   and adds risk on the timeline (CLAUDE.md section 12, May 24
   deadline).

## Decision

**v1 ships with `SpeechRecognizer`.** The `:core:asr` module wraps it
behind a `SpeechToTextSession` interface with a single
`Flow<TranscriptChunk>` API. This interface is the *seam* — we can
swap implementations later without touching the capture screen.

**v2 (post-submission) replaces it with a Gemma-audio implementation.**
Same interface, different backing: feed PCM frames to Gemma, get
transcript chunks back.

This means: the user never sees the difference except in transcription
quality and language coverage breadth. The privacy story is the same
either way (both fully on-device).

## Alternatives considered

- **Skip SpeechRecognizer, go straight to Gemma audio.** Rejected for
  v1 because:
  - It's harder to test on a real device under deadline pressure.
  - SpeechRecognizer's per-device on-device support varies; we want a
    fast happy path.
  - The Gemma audio surface area is younger and we'd be debugging it
    in the same week we'd be polishing UX.
- **Whisper.cpp via JNI.** Battery cost, model size, language coverage
  worse than SpeechRecognizer for our six languages. Rejected.
- **Vosk.** Solid library, but model size per language is non-trivial
  and the API is heavier than SpeechRecognizer. Rejected for v1.

## Consequences

- `:core:asr` has an interface and one implementation. The Gemma audio
  implementation is tracked in the roadmap, not the v1 milestone.
- We accept that on devices where SpeechRecognizer requires a network
  fallback (older OEM ROMs), the user will see an empty transcript
  rather than us silently sending audio. The capture screen makes this
  explicit: "On-device transcription unavailable — please update your
  Android Speech Services or pick another device."
- The submission video MUST showcase airplane-mode operation to prove
  the on-device claim — the empty-transcript fallback is the proof
  that we don't cheat.
