---
title: Notari — Voice notes that never leave your phone, structured by Gemma 4
published: false
tags: devchallenge, gemmachallenge, gemma
---

*This is a submission for the [Gemma 4 Challenge: Build with Gemma 4](https://dev.to/challenges/google-gemma-2026-05-06)*

---

## What I Built

For months I'd been looking for a better way to catch what goes through my head — ideas, half-formed projects, notes to myself. I keep an Obsidian vault for the things that survive, and I even built myself a small LLM-powered wiki that I drive from the Gemini CLI. It works. But the more personal the note, the more the arrangement nagged at me: every thought I typed or dictated was a thought I was handing to someone else's server.

The closest thing I had to a real capture tool was my Pixel's Recorder app. It transcribes as you speak, which felt like magic the first week. Then the friction set in. The transcript comes out as one wall of text — no title, no structure, nothing I can search by topic three weeks later — and it lives in my Google account, synced off the phone, not in my own files. To get anything into my Obsidian vault I had to replay the recording, copy the transcript out, and reformat it by hand. And then there were the notes I *didn't* take: the thought I started to dictate late at night and then dropped, because I didn't want a recording of myself thinking out loud sitting on a server I don't control.

**Notari** is my attempt to fix that. You speak — or type, in the moments you can't speak. The phone transcribes locally, a small language model running on the phone gives the transcript a title, tags, parsed datetime references, and a clean Markdown body, and then the audio buffer is overwritten before anything is written to disk. What you get is a Markdown file you own — searchable, editable, ready to drop straight into Obsidian or any Markdown-based workflow. Nothing leaves the phone unless you decide to export it.

The privacy claim is not a marketing line. The app carries no `INTERNET` permission. A CI check parses the merged `AndroidManifest.xml` on every commit to enforce that no transitive library quietly reintroduces it. You can verify audio non-persistence yourself during a recording session:

```
adb shell run-as com.voicenotemd.app find . -name '*.wav' -o -name '*.m4a'
```

It returns nothing — during recording and after.

**Key features:**
- Voice capture → structured Markdown note, fully on-device
- Zero network calls, ever — verifiable, not asserted
- Audio buffer cleared immediately after transcription; never written to disk
- Multilingual: English, Italian, Spanish, French, German, Portuguese — same language in, same language out
- Silent Mic: type instead of speaking; same structuring pipeline, same privacy guarantee
- YAML frontmatter export compatible with Obsidian, Hugo, Logseq, Jekyll
- Notes extend by voice: dictate a follow-up, it appends structured to the existing note
- Biometric lock: fingerprint or face unlock on every app open

---

## Demo

<!-- 
  BEFORE PUBLISHING: replace the URL below with the real YouTube link.
  DEV.to Liquid tag format: {% embed https://youtu.be/YOUR_VIDEO_ID %}
-->

{% embed https://youtu.be/PLACEHOLDER %}

*The demo shows: a real English dictation structured on-device (with real latency, not faked); airplane-mode proof that it works with zero connectivity; Italian dictation producing an Italian note, no translation; the export flow.*

---

## Code

**Repository:** [github.com/oierreaemme/notari](https://github.com/oierreaemme/notari)

The repo is structured as a multi-module Android project. The modules most relevant to the Gemma 4 integration are:

- `:core:inference` — LiteRT-LM engine wrapper, prompt asset, JSON sanitizer, retry logic
- `:core:asr` — Android SpeechRecognizer wrapper, language-pin management
- `:feature:capture` — recording screen, MVI state machine, Silent Mic

The `docs/decisions/` folder holds 16 Architecture Decision Records written during development. ADR 0002 covers the privacy enforcement architecture; ADR 0005 covers the JSON output contract; ADR 0008 explains why the model is delivered via the Storage Access Framework instead of a `DownloadManager` call; ADR 0016 covers the engine lifecycle and the load-vs-inference mutex that prevents OOM on low-RAM devices.

**APK:** available on the GitHub Releases page. SHA-256 published alongside so you can verify the installed file matches the source.

---

## How I Used Gemma 4

### The choice: E2B, not E4B, not the larger variants

Gemma 4 E2B is the edge variant — roughly 2 billion effective parameters via per-layer embeddings — and it is the only Gemma 4 model whose memory and compute profile lets us hold the engine warm in RAM on a mid-range device (I tested on a Pixel 6a, 6 GB RAM, no GPU backend available for this stack) while the OS and the speech recognizer also breathe. E4B is the plausible next step up; it produces richer output on complex multimodal tasks, but the structuring work here — extract a JSON from a short personal transcript — is small, well-bounded, and lives or dies on instruction-following rather than reasoning depth. E2B clears that bar cleanly. The 26B MoE and 31B Dense variants are server-class models; they don't belong in this conversation.

The practical consequence of this choice: the INT4-quantized E2B `.litertlm` file is roughly 1.5 GB, which sits comfortably within 4 GB device RAM budgets. On a Pixel 6a (CPU only), a short note structures in ~40–50 seconds. On a GPU-capable device the engine tries `Backend.GPU` first and typically halves that. Neither is instant — and I show the real latency in the demo rather than cutting around it, because honesty about what "on-device" costs is more useful to the community than a deceptive fast cut.

### The structuring pipeline

Android's `SpeechRecognizer` transcribes the audio buffer in memory and hands a text transcript to the inference engine. Gemma 4 E2B receives the transcript through LiteRT-LM (Google AI Edge's runtime for on-device LLMs) and is instructed to return exactly one JSON object conforming to a fixed schema:

```json
{
  "language": "it",
  "title": "Riunione con Marco — progetto Atlas",
  "tags": ["riunione", "progetto-atlas", "onboarding"],
  "mentions": [
    { "surface_form": "domani alle 15", "iso_resolved": "2026-05-15T15:00:00Z" }
  ],
  "body_markdown": "## Riunione con Marco\n\nDomani alle 15, riunione con **Marco** sul progetto Atlas.\n\n- [ ] Preparare le slide della parte di onboarding"
}
```

A typed Moshi adapter parses that into a `StructuredNote`. A sanitization layer handles the real-world imperfections — markdown code fences, stray reasoning tags, raw newlines inside string values — before the strict parser runs. If parsing still fails, the use case retries once with a tighter "RETURN JSON ONLY" prompt prefix. If the second attempt also fails, it saves the raw transcript as a plain-text note with a non-blocking notice. The user always ends up with a note; they never land on an error screen.

### The prompt is a contract, not a personality

The prompt lives in `core/inference/src/main/assets/prompts/`. It is versioned by filename so any meaningful change to the output contract is a code-review event with a paired evaluation run, not a silent edit. The first lines set the discipline:

```
You convert a voice-note transcript into ONE JSON object.
Output JSON ONLY — no prose, no markdown fences, no explanation.

Rules:
- Only transform what is in the transcript. Never invent facts, names, dates, places, events.
- Remove fillers/repetitions; preserve meaning.
- Detect the language; write title, tags, body, surface_form in THAT language. Do not translate.
- The title field IS the heading — never put `# Title` at the top of body_markdown.
```

Three few-shot examples follow — one English, one Italian, one minimal — chosen to teach format, not content. The examples are explicitly labelled "FORMAT ONLY" and the prompt ends with: *"NEVER copy their words, names, dates, tasks, or topics into your output. Structure ONLY the text between the triple quotes."* This is the anti-hallucination architecture: a tight schema, explicit prohibition, a FINAL CHECKLIST the model runs before emitting, and a parser that validates the result. Trusting the model blindly and hoping it behaves is the failure mode; designing the system so misbehavior has nowhere to go is the engineering.

### What Gemma 4 E2B actually does well — and where it needs help

Three observations from the on-device evaluation runs that won't show up in a benchmark:

**It respects "transform, don't invent" when the prompt is strict.** A test transcript that ended with *"…wait, there was something else I wanted to write, something about sensors, but I don't remember. I'll note it later"* came back with that exact uncertainty preserved in the body, not filled in with plausible sensor content. That conservatism is what makes the output trustworthy — and it costs something honest: when speech-to-text mangles a word, the model leaves the mangled word in place rather than guessing. That's a deliberate trade: a wrong word you can fix beats a confident rewrite you'd never think to check.

**Thinking Mode isn't accessible from prompt instruction on this stack.** I ran a controlled spike: modified the prompt to explicitly request reasoning inside `<thought>` tags, rebuilt, reinstalled, processed three voice notes. Three out of three, the model emitted JSON directly with no reasoning trace. The LiteRT-LM runtime, the INT4-quantized E2B variant, or the dense JSON-only prompt context — one of them suppresses it. If you're building on edge Gemma 4 via LiteRT-LM on Android today, plan accordingly.

**JSON output is reliable in shape, occasionally non-pristine in punctuation.** Across all tested transcripts in Italian, English, and Spanish, every response parsed into the schema without ever falling through to the plain-text fallback. The shape is rock-solid. What varies: compact vs. pretty-printed; occasionally a literal newline inside a string value. A small sanitization layer absorbs it. If you build on E2B and care about structured output, build that sanitization layer — you'll need it occasionally, and adding it once beats living with intermittent parse failures.

The mental adjustment that colored everything: E2B is not a shrunken GPT. The whole game is finding the narrow thing it does genuinely well — here, disciplined structure-extraction from a short transcript — building the product tightly around that, and designing so its weak spots never get a chance to show. Once I stopped asking it to be clever and started asking it to be reliable, it stopped feeling like a compromise and started feeling like the right tool.

---

## What's next

The most consequential next step is replacing Android's `SpeechRecognizer` with Gemma 4's audio-native input: voice → Gemma 4 (multimodal) → structured note in a single forward pass, folding in real spoken-language detection — the thing `SpeechRecognizer` can't do today. The rewrite is local to `:core:asr`; everything from the capture screen upward stays the same. The other item on the roadmap is function calling on edge Gemma 4, which would let the structuring step return typed JSON via constrained sampling rather than prompt-engineered string output, removing the sanitization layer entirely.

---

## A personal note

I'm not a professional Android developer. I had one Gemma app behind me and a lot of help — from AI coding assistants and from documentation I read three times over. For twenty days the loudest feeling wasn't excitement, it was the fear that I'd spend all of them and end up with something that wouldn't land. The morning the whole test suite finally went green, after hours of it staying stubbornly red, was the closest this project came to a celebration. I'm leaving that here because "built with Gemma 4" should include the honest version of who built it and how.

---

*Built for the [Google Gemma 4 Challenge: Build with Gemma 4](https://dev.to/challenges/google-gemma-2026-05-06) · May 2026 · Apache 2.0*
