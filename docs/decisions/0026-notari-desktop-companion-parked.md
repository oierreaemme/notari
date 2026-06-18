# 26. Notari desktop companion for heavy processing: idea, parked

Date: 2026-06-05
Status: Parked (idea — revisit only if it becomes a real need)

## Context

Follow-on from ADR 0025 (diarization). The heavy, multi-speaker use case
(meetings, interviews) is a poor fit for the phone — RAM, CPU contention with
Gemma, small models by necessity. It is a natural fit for the desktop, where
those constraints largely disappear: bigger whisper models (medium/large), a
real diarization pipeline (pyannote / whisperX / sherpa-onnx), a larger local
LLM, and long recordings processed without thrashing.

This note captures the idea so it is not re-derived later.

## Decision

**Park it as an idea. Do not start now.**

Framing, if ever built: a desktop **companion**, not a port. Notari mobile
keeps its job (capture-in-the-moment, one voice, on the go — irreplaceable and
not to be diluted). The desktop tool does the opposite job: sit down, import a
long recording, diarize, transcribe with large models, edit calmly, export to
the vault. Complementary products, different moments — not substitutes.

## Notes for if/when revisited

- **Not a conversion.** The Android app is Kotlin/Compose/MediaPipe-Android;
  desktop is a separate codebase. But a lot ports: whisper.cpp (C++,
  cross-platform, fast on desktop), the structuring logic and JSON contract,
  the Markdown+YAML export format, the Obsidian integration, and the reasoning
  in these ADRs.
- **Natural stack: Kotlin Multiplatform / Compose Multiplatform.** The MVI
  domain layer is already Kotlin; KMP could share domain (and some UI) between
  Android and desktop (JVM target) rather than starting from zero. More
  sensible here than Flutter or Electron given the existing investment.
- **Privacy holds, easily.** Local models, no cloud — the no-network principle
  (Pillar 2) is if anything simpler to honor on desktop.
- **Start lean if at all.** Not a rewrite: a small companion that does only the
  heavy work — import audio → diarize + transcribe with large models →
  structure → export to vault — reusing whisper.cpp and a local LLM. A tool,
  not a product. Let it grow only if it proves useful in real use.

## Probable form: an Obsidian plugin, not a standalone app

The desktop companion most likely should *be* an Obsidian plugin rather than a
separate native app. Obsidian is already an Electron desktop app the author
lives in daily, so a plugin gives a cross-platform desktop UI **and**
distribution (community plugins) for free, and it lives exactly where the user
already is — leaner than building a standalone app.

Shape: two pieces. Obsidian plugins are JS/TS in an Electron sandbox and can't
comfortably run heavy ML in-process, so the engine (whisper + a local LLM) runs
as a **local sidecar service** — e.g. Ollama's localhost API for the LLM, a
whisper server for transcription. The plugin is just the UI inside Obsidian: a
record/import button, send audio to localhost, receive structured Markdown,
write the note straight into the vault with the user's templates, tags,
frontmatter, in the right folder. localhost only, no cloud — the no-network
principle (Pillar 2) holds.

Why it matters: it **closes the loop**. Today Notari produces Obsidian-targeted
Markdown that is then exported/imported; the plugin removes that step — the note
is born inside the vault. Of the parked expansion ideas this is the one most
likely to be the author's *own* itch (heavy Obsidian user; the whole reason
Notari exists), versus diarization / "desktop for meetings", which trace to
external interest. So if any of these is revived, this is the probable one.

## The honest blocker (not technical)

Feasibility is not the question; **finishing** is. This is a solo project with
a track record of half-done prototypes. Before committing real time, two
honest checks:

1. Is it the author's own need, or someone else's? Notari exists because the
   author needed it; meeting transcription is currently external interest (one
   LinkedIn comment, June 2026), not a validated personal itch.
2. Is it imagined as a real project, or another prototype?

If the answer to (1) is "not really mine," leaving it here as a recorded idea
is the right outcome. Linked from [ADR 0025](0025-speaker-diarization-parked.md).
