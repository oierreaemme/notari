# Notari — voice notes that never leave your phone, structured by Gemma 4

> *Built for the [Google Gemma 4 Challenge](https://dev.to/) — "Build With Gemma 4" track.*
>
> **Note:** this is the long-form working draft. The version actually published to dev.to (structured on the official challenge template) is [`dev-post-submission.md`](dev-post-submission.md).

---

## The problem

I keep voice memos. Meeting decisions. Half-formed ideas at 11pm. Reminders I'll forget by the time I'm home. The two app categories that should solve this don't:

- **System dictation** gives me a raw transcript with no structure. I never re-read it. It rots in a folder.
- **Cloud "AI voice notes"** structure beautifully, but they upload my audio. Meeting decisions. Personal reflections. To a server I don't control, against a privacy policy that changes.

So I built the third option: an Android app that records, transcribes, and structures voice notes **entirely on-device**, where "on-device" means the audio is held in RAM, never written to disk, and the network permission isn't even requested by the app.

Voice notes are the kind of content where privacy isn't a marketing veneer — it's a precondition for using the tool at all. If the app feels like it might leak, I won't dictate the thing that matters most. So the privacy guarantee had to be load-bearing, not optional.

## The approach

The pipeline is short on purpose:

```
Mic ─▶ Android SpeechRecognizer ─▶ Gemma 4 E2B (LiteRT-LM) ─▶ JSON ─▶ Room
```

1. **Capture.** `SpeechRecognizer` runs in continuous-listen mode so the user can pause naturally without the recognizer giving up. The OS owns the audio buffer; we only see the text Flow.
2. **Structure.** The transcript is fed to **Gemma 4 E2B** (Effective-2B, INT4-quantized, ~1.5 GB on disk) running locally via Google AI Edge's **LiteRT-LM** runtime. The prompt is engineered to return a single JSON object — title, tags, dated `mentions[]`, a Markdown body — and nothing else.
3. **Parse + persist.** A lenient Moshi parser tolerates trailing commas and unquoted keys. On parse failure, we retry once with a stricter prompt; on a second failure, the transcript is saved as plain text so the user never loses their content. Notes land in Room with cascading FK deletes.

No step touches the network. The `INTERNET` permission isn't declared in the merged manifest, and the CI gate fails the build if anyone ever adds it.

### Why Gemma 4 E2B specifically?

Three reasons made E2B the only sensible choice for this app:

- **It fits.** ~1.5 GB on disk in INT4. Loads inside a 4 GB-RAM phone's budget alongside the rest of the app. The Gemma 4 4B variant exceeded what I was willing to ship in an APK for a "personal capture tool".
- **It's strong enough for structured generation.** The task here isn't open-ended reasoning — it's "given this transcript, return a fixed-schema JSON object". E2B does this reliably across six languages once the prompt is tuned for it (more on that below).
- **LiteRT-LM ships a maintained Kotlin binding.** `com.google.ai.edge.litertlm:litertlm-android` reads `.litertlm` files directly, supports GPU and CPU backends, and exposes the `Engine` / `Session` / `LlmInference` API surface the rest of the app can be built around.

The app is opinionated: it transforms the transcript faithfully, never paraphrases meaning, and never invents dates, names, or facts. That guarantee is enforced by the prompt and verified by adversarial fixtures in `core/inference/src/test/resources/prompt-eval/`.

## Demo

{% embed https://www.youtube.com/watch?v=3U477zIH7FA %}

*(If the embed doesn't render in the dev.to editor, paste the bare URL on its own line — dev.to auto-embeds YouTube.)*

## Technical highlights

### The JSON-first contract

The model is asked for one thing: a JSON object matching a fixed schema. No prose, no markdown fences, no "Sure! Here's the structured note:" preamble.

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

The prompt is versioned in `core/inference/src/main/assets/prompts/structure_note_vN.txt`. The active version is referenced from `AssetPromptLoader.ACTIVE_PROMPT` — every change to the prompt is a versioned, file-based change with a corresponding ADR. The active version is **v10**, evolved from v1 through ten rounds of real-corpus testing:

- **v2** condensed the few-shot examples after E2B started over-mimicking long examples.
- **v3** added a `CURRENT TIMESTAMP` block so the model could resolve "tomorrow at 3pm" to a real ISO instant.
- **v4** fixed four E2B-specific failure modes: confusing `mentions[]` with named entities, dropping `- [ ]` checkboxes for spoken commitments, collapsing enumerations into prose, and never using `##` headings on multi-topic notes. The fix in every case was changing the framing from "you may" to "REQUIRED".
- **v5** added an `EXISTING TAGS` section populated at render time with the user's current tag corpus, plus a reuse rule with a worked example. Across several sessions on the same topic, Gemma was coining near-synonyms (`app`, `app-development`, `dev`); v5 nudges it to reuse what's already there.
- **v6** added orthographic cleanup rules (fix false starts and obvious mis-hearings without changing meaning).
- **v7** slimmed the prompt back down — removed verbose formatting-whitespace rules that were eating the cold-start prefill budget.
- **v8** added a FINAL CHECKLIST before generation and a headings-preserve-prose rule, then had to be trimmed again when the extra ~1000 characters pushed cold-start over budget on a Pixel 6a.
- **v10** fixed the most important bug of the project (Pillar 4): E2B was occasionally emitting the *content* of the worked examples as if it were the user's note. The fix cut the examples from ten to three short, low-salience ones, replaced specific names and ticket numbers with bland placeholders, and added a blunt anti-copy guard right before the transcript. v10 also moved the language lock from the bare BCP-47 code to the language name ("English"), which stopped mixed-language titles and tags. (v9 was an unshipped intermediate.)

### Robust parsing — the model will be sloppy

Even with a strict prompt, real E2B output has variance: trailing commas, occasional Markdown fences, an extra explanation appended after the closing brace. The parser:

- Strips ` ```json ` fences if present.
- Trims everything before the first `{` and after the last balanced `}`.
- Hands the cleaned slice to Moshi configured as **lenient**.

If that still fails, we retry once with a stricter `RETURN JSON ONLY. NO OTHER TEXT.` preamble. If *that* fails, we fall back to saving the raw transcript as a plain-text note. The user always keeps their content — the worst case is "structured" turns from `true` to `false` and the note shows up un-formatted in the list. The raw model response is captured in `StructuringResult.lastRawResponse` so a debug card can show what came back.

### Audio non-persistence — the privacy backbone

The most important thing this app does is *not* write audio to disk. Ever.

`SpeechRecognizer` is started in continuous-listen mode. It owns the buffer; the app only ever sees a `Flow<TranscriptChunk>` of strings. When the user stops, we cancel the session — `awaitClose` calls `recognizer.destroy()`, releases the OS-side audio resources, and the buffer goes with it. There is no `.wav`, `.m4a`, `.aac`, or `.tmp` file in the app's data directory at any point during the recording lifecycle. The bash check is one line:

```
adb shell run-as com.voicenotemd find /data/data/com.voicenotemd -type f \
  \( -name '*.wav' -o -name '*.m4a' -o -name '*.aac' -o -name '*.tmp' \) 2>/dev/null
```

It should return nothing — at any time.

### Backend probing — GPU first, CPU fallback

LiteRT-LM 0.11 supports both `Backend.GPU()` and `Backend.CPU()` for Gemma 4 E2B. On paper, GPU is 2-4× faster on decode. In practice, GPU initialization fails on some devices (Pixel 6a's Mali-G78, in my testing) and the runtime quietly errors out at first generation.

The session factory probes:

```kotlin
runCatching { engineFactory(Backend.GPU()) }
    .recoverCatching { engineFactory(Backend.CPU()) }
    .getOrThrow()
```

The active backend is logged to `adb logcat -s VoiceNoteGemma` so I can tell at a glance which path a device landed on. On the reference Pixel 6a (CPU fallback) a 1000-character note structures in 50-60s; on a device that gets the GPU path it drops to 15-25s. See [ADR 0011](docs/decisions/0011-backend-probing-and-mtp.md).

### MTP speculative decoding

If the Gemma model file includes the **Multi-Token Prediction** drafter heads (the Hugging Face re-publication from May 2026 onward does), enabling `ExperimentalFlags.enableSpeculativeDecoding = true` before engine creation gives a ~25% decode speedup on CPU and 2-3× on GPU. Wrapped in `runCatching` so an API rename in a patch release degrades to non-speculative decode rather than crashing the build.

### Engine lifecycle — keeping 1.5 GB livable

The Gemma engine is roughly 1.5 GB resident. On a 4 GB-RAM device that's most of the budget after the OS takes its share, so leaving it loaded after the user finishes structuring is a recipe for a process kill.

`LiteRtLmGemmaSession` implements `ComponentCallbacks2` and releases the engine on `onTrimMemory(level >= TRIM_MEMORY_BACKGROUND)`. The next call reloads lazily. To hide the cold-start latency, `GemmaSession.warmUp()` is fire-and-forget called from `CaptureViewModel.init` — by the time the user has tapped the mic and started talking, the engine is already loading in the background.

There's a CAS-protected init race: if two callers reach `ensureEngineLoaded()` simultaneously, the loser explicitly `close()`s the redundant 1.5 GB allocation. (Not theoretical — caught it in a flaky integration test where warm-up and first-generate raced.)

### Interoperability — your notes are not hostages

Most note apps store content in a proprietary database that you can only get out via the app's own export button (and only as long as the app is still maintained). Notari does the opposite: every note is, by construction, a portable Markdown file with YAML frontmatter.

The share sheet emits the exact same `.md` shape that the ZIP backup does — one source of truth, `Note.toMarkdownWithFrontmatter()` in `:core:common`. Drop the file into an Obsidian vault, a Logseq graph, a Foam workspace, a static site generator, or just a folder synced via your preferred file backup: the structure carries with it. The `tags:` list in the frontmatter shows up as tags in Obsidian. The `mentions:` block with resolved ISO instants is parseable by any timeline plugin. The body is plain Markdown — checkboxes, headings, bold, all of it.

This matters for the privacy story in a subtle way: a user who decides to stop using the app should be able to take their data and leave, without losing any structure. The privacy promise isn't just *"we don't send your data"*, it's *"your data was always yours"*. Markdown was chosen on day one for this reason, before any of the inference work started.

### Multilingual handling

The prompt detects the input language and produces `title`, `tags`, `body_markdown`, `surface_form` in that language. Datetimes are resolved against the device's timezone so *"domani alle 15"*, *"tomorrow at 3pm"*, *"mañana a las 3"* all produce real ISO instants in the JSON, and the `LocalDate.parse` / `OffsetDateTime.parse` / `Instant.parse` cascade in the use case handles every shape the model emits in practice. The UI is English-only in v1 — translating the UI is a roadmap item, not a competition deliverable.

## What I learned about Gemma 4 E2B

**Framing matters more than I expected.** Going from "use checkboxes for tasks" to "REQUIRED: every `devo / I need to / must` is a `- [ ]` checkbox" was the single largest quality jump. E2B respects directives much more reliably than permissions. The same framing flip applied to bullet lists ("REQUIRED: every enumeration") and headings ("REQUIRED: every topic switch").

**Few-shot examples are tokens, not magic.** The first prompt version had eight examples covering every edge case. E2B over-mimicked their length and style. Cutting to three carefully chosen ones — pure prose, bulleted list, multi-topic with headings — produced cleaner output across the board. The token budget freed up paid for the `CURRENT TIMESTAMP` block.

**Schema is the strongest hint.** Telling the model "return JSON with these five keys" via an inline schema block plus three worked examples beats every "be sure to return valid JSON" instruction. The reason a strict-prompt retry pass works at all is that it's not asking for new content — it's just restating the existing schema with louder caps locks.

**The model can do temporal reasoning if you give it the time.** Without `CURRENT TIMESTAMP: 2026-05-15T10:00:00+02:00` in the prompt, every relative date came back as `null`. With it, ~95% of `"tomorrow at 3pm"` / `"domani alle 15"` / `"venerdì prossimo"` resolve correctly across the six supported languages.

**It can't be a fact source.** Anything that requires recall — "the dentist I always go to", "the project I told you about last week" — is hallucination territory. I make this explicit in the prompt and verify it in adversarial fixtures. The app's contract is *transform*, never *augment*.

**Latency is real but tameable.** On the Pixel 6a (Tensor G1, CPU fallback) a 1000-character note structures in ~60s. That sounds long until you remember: the user *just spent 60 seconds dictating it*. Pre-warming the engine while the user is reading the capture screen, and showing a progress affordance while structuring runs, turns the perceived latency into "I see something happening" rather than "is this thing frozen?". On devices that get the GPU path it's ~15-25s and the experience is properly snappy.

## What's next

Three upgrades on the roadmap that I deliberately cut from v1 to ship within the competition window:

- **Gemma audio-native ASR.** Replace `SpeechRecognizer` with Gemma 4 E2B's multimodal audio input so transcription, language detection, and structuring all happen in a single forward pass through the same model. This collapses the pipeline and removes a class of "the recognizer dropped the last 200ms" edge cases.
- **Tool calls for calendar.** With Gemma function-calling, a note containing a resolved `mentions[]` could optionally surface a "Add to calendar" affordance that fires an `Intent.ACTION_INSERT` against the system calendar provider. Still no network; still on-device; just a tighter loop from voice to plan.
- **Ask your past notes — local RAG.** With a small on-device embedding model (an MTEB-class encoder in INT8 weighs in well under 200 MB) the notes corpus becomes searchable by meaning, not just keywords. *"What did I write about the Lighthouse project last week?"* → Gemma retrieves the relevant note bodies, summarizes, cites the source titles. The same privacy contract holds: embeddings live in Room next to the notes, queries never leave the device. The reason this is roadmap and not v1 is that retrieval-augmented generation needs careful citation handling to keep the "transform, don't augment" promise of the current product — Gemma must answer *from* the notes, not *about* them. Worth doing right; not worth rushing.

## Try it

- **Code:** https://github.com/oierreaemme/notari
- **Video:** https://www.youtube.com/watch?v=3U477zIH7FA
- **APK:** https://github.com/oierreaemme/notari/releases/download/v1.0.0/notari-v1.0.0.apk *(confirm this matches the release tag + asset filename you upload)*
- **Architecture, ADRs, prompts:** see `docs/` in the repo
- **License:** Apache 2.0

The privacy promise of this app is verifiable. Run it in airplane mode. Inspect the manifest. Sniff the network. Nothing leaves the device — that's the whole point.

---

*Notari was built solo across the two weeks of the Gemma 4 Challenge. Total commits: ~50. Total LOC: ~6k Kotlin. The model file is downloaded once, manually, from Google AI — no analytics, no telemetry, no surprises. The name takes its cue from the Latin* notarius *— the historically trusted recorder of spoken statements. That, in two syllables, is the product.*
