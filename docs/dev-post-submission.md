---
title: Notari — voice notes that never leave your phone, structured by Gemma 4
published: false
tags: devchallenge, gemmachallenge, gemma
cover_image:
---

*This is a submission for the [Gemma 4 Challenge: Build with Gemma 4](https://dev.to/challenges/google-gemma-2026-05-06)*

## What I Built

**Notari** is an Android app that records a voice note, transcribes it, and turns it into a clean, structured Markdown note — **entirely on-device**. The audio is held in RAM, never written to disk, and the app doesn't even request the `INTERNET` permission.

I keep voice memos: meeting decisions, half-formed ideas at 11pm, reminders I'll forget by the time I'm home. The two app categories that should solve this don't:

- **System dictation** gives me a raw transcript with no structure. I never re-read it. It rots in a folder.
- **Cloud "AI voice notes"** structure beautifully, but they upload my audio — meeting decisions, personal reflections — to a server I don't control, against a privacy policy that can change.

So I built the third option. Voice notes are the kind of content where privacy isn't a marketing veneer — it's a precondition for using the tool at all. If the app feels like it might leak, I won't dictate the thing that matters most. So the privacy guarantee had to be load-bearing, not optional.

The pipeline is short on purpose:

```
Mic ─▶ Android SpeechRecognizer ─▶ Gemma 4 E2B (LiteRT-LM) ─▶ JSON ─▶ Room
```

1. **Capture.** `SpeechRecognizer` runs in continuous-listen mode so the user can pause naturally without the recognizer giving up. The OS owns the audio buffer; the app only ever sees the text Flow.
2. **Structure.** The transcript is fed to Gemma 4 E2B running locally via Google AI Edge's LiteRT-LM runtime. The prompt is engineered to return a single JSON object — title, tags, dated `mentions[]`, a Markdown body — and nothing else.
3. **Parse + persist.** A lenient Moshi parser tolerates trailing commas and unquoted keys. On parse failure we retry once with a stricter prompt; on a second failure the transcript is saved as plain text so the user never loses content. Notes land in Room as portable Markdown.

No step touches the network. The `INTERNET` permission isn't declared in the merged manifest, and a CI gate fails the build if anyone ever adds it.

## Demo

{% embed https://www.youtube.com/watch?v=3U477zIH7FA %}

## Code

- **Repository:** https://github.com/oierreaemme/notari
- **APK (v1.0.0):** https://github.com/oierreaemme/notari/releases/download/v1.0.0/notari-v1.0.0.apk
- **Architecture, ADRs, prompt evaluations:** see `docs/` in the repo
- **License:** Apache 2.0

The privacy promise is verifiable. Run it in airplane mode. Inspect the manifest. Sniff the network. Nothing leaves the device — that's the whole point.

## How I Used Gemma 4

I chose **Gemma 4 E2B** (Effective-2B, INT4-quantized, ~1.5 GB on disk) running locally via LiteRT-LM. Three reasons made E2B the right fit — not E4B, not a cloud model:

- **It fits.** ~1.5 GB in INT4 loads inside a 4 GB-RAM phone's budget alongside the rest of the app. The larger Gemma 4 variant exceeded what I was willing to ship in an APK for a personal capture tool.
- **It's strong enough for structured generation.** The task isn't open-ended reasoning — it's "given this transcript, return a fixed-schema JSON object". E2B does this reliably across six languages once the prompt is tuned for it.
- **LiteRT-LM ships a maintained Kotlin binding.** `com.google.ai.edge.litertlm:litertlm-android` reads `.litertlm` files directly, supports GPU and CPU backends, and exposes the `Engine` / `Session` API the rest of the app is built around.

The app is opinionated: it transforms the transcript faithfully, never paraphrases meaning, and never invents dates, names, or facts. That guarantee is enforced by the prompt and verified by adversarial fixtures in `core/inference/src/test/resources/prompt-eval/`.

### The JSON-first contract

The model is asked for one thing: a JSON object matching a fixed schema. No prose, no Markdown fences, no "Sure! Here's the structured note:" preamble.

```json
{
  "language": "<bcp47>",
  "title": "<short, no trailing punctuation>",
  "tags": ["<lowercase-kebab>"],
  "mentions": [
    { "surface_form": "<datetime span>", "iso_resolved": "<ISO-8601 or null>" }
  ],
  "body_markdown": "<Markdown>"
}
```

The prompt is versioned in `core/inference/src/main/assets/prompts/structure_note_vN.txt` and referenced from `AssetPromptLoader.ACTIVE_PROMPT`. Every change is a versioned, file-based change with a corresponding ADR. The active version is **v10**, evolved through ten rounds of real-corpus testing — and the evolution itself is most of what I learned about the model:

- **v2** condensed the few-shot examples after E2B started over-mimicking long examples.
- **v3** added a `CURRENT TIMESTAMP` block so the model could resolve "tomorrow at 3pm" to a real ISO instant.
- **v4** fixed four E2B-specific failure modes (confusing `mentions[]` with named entities, dropping checkboxes for spoken commitments, collapsing enumerations into prose, never using headings on multi-topic notes). The fix in every case was changing the framing from "you may" to "REQUIRED".
- **v6** added orthographic cleanup rules (fix false starts and obvious mis-hearings without changing meaning).
- **v7** slimmed the prompt back down — removed verbose formatting-whitespace rules that were eating the cold-start prefill budget.
- **v8** added a FINAL CHECKLIST before generation and a headings-preserve-prose rule, then had to be trimmed again when the extra ~1000 characters pushed cold-start over budget on a Pixel 6a.
- **v10** fixed the most important bug of the whole project (Pillar 4): E2B was occasionally emitting the *content* of the worked examples as if it were the user's note. The fix cut the examples from ten to three short, low-salience ones, replaced specific names and ticket numbers with bland placeholders, and added a blunt anti-copy guard right before the transcript. v10 also moved the language lock from the bare BCP-47 code to the language name ("English"), which stopped mixed-language titles and tags.

### Robust parsing — the model will be sloppy

Even with a strict prompt, real E2B output has variance: trailing commas, occasional Markdown fences, an extra explanation after the closing brace. The parser strips any leading or trailing Markdown code fences, trims everything before the first `{` and after the last balanced `}`, and hands the cleaned slice to Moshi configured as **lenient**. If that fails, we retry once with a stricter `RETURN JSON ONLY. NO OTHER TEXT.` preamble; if *that* fails, we fall back to saving the raw transcript as a plain-text note. The user always keeps their content.

### Audio non-persistence — the privacy backbone

The most important thing this app does is *not* write audio to disk. Ever. `SpeechRecognizer` owns the buffer; the app only ever sees a `Flow<TranscriptChunk>` of strings. When the user stops, `awaitClose` calls `recognizer.destroy()` and the buffer goes with it. There is no `.wav`, `.m4a`, `.aac`, or `.tmp` file in the app's data directory at any point. The check is one line:

```
adb shell run-as com.voicenotemd.debug find /data/data/com.voicenotemd.debug -type f
```

The output lists the Room database, the DataStore settings, and the model files — and nothing audio. I verified this live during, before, and after a recording.

### Backend probing — GPU first, CPU fallback

LiteRT-LM supports both `Backend.GPU()` and `Backend.CPU()`. GPU is faster on decode, but GPU init fails on some devices (the Pixel 6a's Mali-G78 in my testing). The session factory probes GPU and recovers to CPU:

```kotlin
runCatching { engineFactory(Backend.GPU()) }
    .recoverCatching { engineFactory(Backend.CPU()) }
    .getOrThrow()
```

On the reference Pixel 6a (CPU fallback) a 1000-character note structures in ~50-60s; on a device that gets the GPU path it's ~15-25s.

### Engine lifecycle — keeping 1.5 GB livable

The engine is ~1.5 GB resident — most of a 4 GB device's budget. `LiteRtLmGemmaSession` implements `ComponentCallbacks2` and releases the engine on `onTrimMemory(TRIM_MEMORY_BACKGROUND)`, reloading lazily. To hide cold-start, `warmUp()` is fire-and-forget from `CaptureViewModel.init` — by the time the user has tapped the mic and started talking, the engine is already loading.

### Multilingual handling

The prompt detects the input language and produces the title, tags, body, and datetime surface forms in that language. Datetimes resolve against the device timezone, so *"domani alle 15"*, *"tomorrow at 3pm"*, and *"mañana a las 3"* all produce real ISO instants. Supported at v1: English, Italian, Spanish, French, German, Portuguese. The UI is English-only in v1 — UI localization is a roadmap item.

### Interoperability — your notes are not hostages

Every note is, by construction, a portable Markdown file with YAML frontmatter (`Note.toMarkdownWithFrontmatter()`). Drop it into an Obsidian vault, a Logseq graph, or any folder you sync — tags, resolved datetimes, headings, and checkboxes all carry with it. The privacy promise isn't just "we don't send your data", it's "your data was always yours".

## What I learned about Gemma 4 E2B

**Framing matters more than I expected.** Going from "use checkboxes for tasks" to "REQUIRED: every *I need to / must* is a `- [ ]` checkbox" was the single largest quality jump. E2B respects directives far more reliably than permissions.

**Few-shot examples are tokens, not magic — and they can leak.** Early prompts had eight to ten examples; E2B over-mimicked their length *and*, worse, sometimes copied their content into the user's note. Cutting to three short, low-salience examples fixed both the bloat and the leakage. This was the scariest bug of the project precisely because it violated the core "transform, don't invent" promise.

**Schema is the strongest hint.** An inline schema block plus three worked examples beats every "be sure to return valid JSON" instruction. The strict-retry pass works because it isn't asking for new content — just restating the schema with louder caps locks.

**It can do temporal reasoning if you give it the time.** Without `CURRENT TIMESTAMP` in the prompt, every relative date came back `null`. With it, ~95% of relative dates resolve correctly across the six languages.

**It can't be a fact source.** Anything that requires recall — "the dentist I always go to" — is hallucination territory. The contract is *transform*, never *augment*, and I verify it with adversarial fixtures.

**Latency is real but tameable.** ~60s on a Pixel 6a (CPU) sounds long until you remember the user *just spent 60 seconds dictating*. Pre-warming the engine and showing a clear progress affordance turns it into "I see something happening" rather than "is this frozen?". On the GPU path it's ~15-25s.

## What's next

Three upgrades I deliberately cut from v1 to ship within the competition window:

- **Gemma audio-native ASR.** Replace `SpeechRecognizer` with Gemma 4 E2B's multimodal audio input so transcription, language detection, and structuring all happen in one forward pass.
- **Tool calls for calendar.** With Gemma function-calling, a resolved `mentions[]` could surface an on-device "Add to calendar" affordance via `Intent.ACTION_INSERT`. Still no network.
- **Ask your past notes — local RAG.** A small on-device embedding model (INT8, well under 200 MB) would make the corpus searchable by meaning. Embeddings live in Room next to the notes; queries never leave the device. This is roadmap and not v1 because RAG needs careful citation handling to keep the "transform, don't augment" promise.

---

*Notari was built solo across the two weeks of the Gemma 4 Challenge. The model file is downloaded once, manually, from Google AI — no analytics, no telemetry, no surprises. The name takes its cue from the Latin* notarius *— the historically trusted recorder of spoken statements. That, in two syllables, is the product.*
