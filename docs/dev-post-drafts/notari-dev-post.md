# Notari — Voice notes that never leave your phone, structured by Gemma 4

*This is a submission for the [Gemma 4 Challenge: Build with Gemma 4](https://dev.to/challenges/google-gemma-2026-05-06).*

---

## The problem

For months I'd been looking for a better way to catch what goes through my
head — ideas, half-formed projects, notes to myself. I keep an Obsidian vault
for the things that survive, and I even built myself a small LLM-powered wiki
that I drive from the Gemini CLI. It works. But the more personal the note,
the more the arrangement nagged at me: every thought I typed or dictated was a
thought I was handing to someone else's server.

The closest thing I had to a real capture tool was my Pixel's Recorder app. It
transcribes as you speak, which felt like magic the first week. Then the
friction set in. The transcript comes out as one wall of text — no title, no
structure, nothing I can search by topic three weeks later — and it lives in my
Google account, synced off the phone, not in my own files. To get anything into
my Obsidian vault I had to replay the recording, copy the transcript out, and
reformat it by hand. Capturing was easy; everything *after* capturing was the
problem.

There's a quieter failure underneath that one: the notes I *didn't* take. The
thought I started to dictate late at night and then dropped, because I didn't
want a recording of myself thinking out loud sitting on a server I don't
control. Both failures share a root cause — the recording and the
*understanding* of the recording happen in different places, owned by different
parties, under different rules.

**Notari** is my attempt to put them back on one device. You speak — or type,
in the moments you can't speak. The phone transcribes locally, asks a small
language model running on the phone to give the transcript a title, tags,
parsed datetime references, and a clean Markdown body, and then forgets the
audio buffer before anything is written to disk. What you get is a Markdown
file you own — searchable, editable, and ready to drop straight into a vault
like Obsidian. Nothing leaves the phone unless you decide to export it.

## The approach

The shape of Notari falls naturally out of two non-negotiables: the audio
never reaches disk, and the structured output is produced on the same device
that did the recording. Everything else is downstream of those two
commitments. Network calls leave; data leaves; the easy paths leave. What
stays is a pipeline that fits in one process on one phone.

The pipeline has three stages. The first is **Android's built-in
SpeechRecognizer**, which transcribes the audio buffer in memory and never
writes the wav. Speech recognition on modern Android is on-device by default
on Pixel and most flagship hardware — the API was originally cloud-backed
and is now mostly local; we verified this experimentally by recording in
airplane mode and watching transcription still complete. The second stage is
**Gemma 4 E2B running via LiteRT-LM**, Google AI Edge's runtime for on-device
LLMs, with the model file loaded from app-private storage. The third stage
is a strict **JSON parser** that takes the model's response and turns it into
a typed `StructuredNote` ready to save to Room. There's a fallback at each
stage so the user never sees a broken state — if the JSON doesn't parse on
the first try, we retry with a stricter prompt; if it fails again, we save
the raw transcript as a plain-text note and surface a friendly notice. The
user always ends up with a note in their hand.

The choice of **Gemma 4 E2B specifically** (not E4B, not the 26B MoE, not
the 31B Dense) is the most opinionated decision in the project. E2B is the
edge variant — roughly 2 billion effective parameters thanks to per-layer
embeddings — and it's the only Gemma 4 variant whose memory and compute
profile lets us hold the engine warm in RAM on a mid-range device while the
operating system and the speech recognizer also breathe. E4B is the
plausible next step up; it produces noticeably richer descriptions in
domains like multimodal image understanding, but the structuring task we
need it for here is small, well-bounded, and lives or dies on instruction
following rather than reasoning depth. E2B clears that bar cleanly. The 26B
and 31B variants are server-class models — they exist for a different
deployment scenario, where the question is not "what fits on the phone" but
"how much GPU can the user's cloud provider afford." We are not in that
business.

Two architectural choices flow from picking the smallest model. The first is
**delivery via the Storage Access Framework, not a network download.** The
user obtains `gemma-4-e2b-it.litertlm` from Google AI Edge (where they accept
the Gemma license), and then explicitly imports it into Notari via Settings →
On-device model. There is no `DownloadManager` call. There is no URL hardcoded
into the app. The "zero network calls" claim becomes literally exhaustive —
verifiable by inspecting the merged `AndroidManifest.xml` and confirming that
`android.permission.INTERNET` is not present (a CI check enforces this on
every commit). The second is the **JSON contract between prompt and parser.**
The prompt instructs Gemma 4 to return one JSON object conforming to a fixed
schema — `{ language, title, tags, mentions, body_markdown }` — and nothing
else. The schema is encoded in code (Moshi adapters), mirrored in the prompt
as ground truth, and validated by the parser with lenient JSON handling plus
targeted sanitization for the most common artifacts.

What Notari **doesn't ask Gemma 4 to do** is just as important as what it
asks. The model never invents content. It does not add commentary. It does
not paraphrase meaning. It is allowed to remove false starts and filler words
("uh", "ehm", "this thing"), and to fix obvious transcription errors when
context makes the intent clear, but it stays conservative — when in doubt, the
original word survives. It is not allowed to suggest actions the user did not
mention. The system prompt enforces these as ABSOLUTE RULES, and a small
adversarial evaluation suite tests them with deliberately tricky transcripts.
A voice note app whose AI invents content is worse than useless — it would
quietly rewrite history. Treating the model as a transformer instead of an
oracle is the architectural decision that makes the output trustworthy.

## Demo

<!-- TODO: embed the 80s YouTube demo here before publishing. -->
<!-- Shows: a real Italian dictation structured on-device; airplane-mode
     proof that it works with no connectivity; a filesystem check confirming
     no audio file is created. -->

*[Demo video — embedded on publish]*

## Technical highlights

### The prompt is a contract, not a personality

The most-edited file in the repository is a 75-line plain-text asset:
`core/inference/src/main/assets/prompts/structure_note_v1.txt`. It is the
prompt that turns a raw transcript into a structured note, and it is written
as a contract between the model and the parser rather than as a "you are a
helpful assistant" preamble. The first few lines set the output discipline:

```
You are a strict structuring engine for personal voice notes.

You receive a raw speech-to-text transcript. You return ONE JSON object
and NOTHING ELSE. No prose. No preamble. No closing remarks. No code
fences. No explanations. JSON only.

ABSOLUTE RULES — non-negotiable:
1. You ONLY transform what is in the transcript. You NEVER invent dates,
   names, places, people, tasks, or facts that are not explicitly present.
2. You NEVER add commentary, opinions, summaries, or editorial framing.
3. You NEVER suggest actions the user did not mention.
…
```

Three short examples follow — one English, one Italian, one minimal — and
then the schema is repeated explicitly inside the prompt so the model sees the
same key order and types that the Kotlin Moshi adapter expects. The prompt is
versioned by filename (`structure_note_v1.txt`, `structure_note_v2.txt`, …) so
any meaningful change to the contract is a code-review event with a paired
evaluation suite, never a silent edit. Treating the prompt as a grammar rather
than as soft instruction is what makes a 2-billion-effective-parameter model
produce reliable structured output. With this framing, Gemma 4 E2B follows the
schema with surprising consistency; without it, the same model wanders into
prose and apologies within three transcripts.

### A JSON parser that expects messy real-world output

The model's output is almost always valid JSON, but "almost" is doing real
work in that sentence. Real edge LLM output occasionally arrives wrapped in a
markdown code fence, prefixed with a friendly "Sure, here is the JSON:",
suffixed with closing remarks, or — most commonly — containing raw newline
characters inside string values, which is invalid per RFC 8259. The parser
handles each of these explicitly:

```kotlin
internal fun sanitize(raw: String): String? {
    val noBom = raw.trim().removePrefix("﻿").trim()
    val noFence = noBom
        .removePrefix("```json").removePrefix("```")
        .removeSuffix("```")
        .trim()
    val noThoughts = stripReasoningTags(noFence)
    val firstBrace = noThoughts.indexOf('{')
    val lastBrace = noThoughts.lastIndexOf('}')
    if (firstBrace < 0 || lastBrace <= firstBrace) return null
    val jsonBlock = noThoughts.substring(firstBrace, lastBrace + 1)
    return escapeRawNewlinesInStrings(jsonBlock)
}
```

The order matters. Thought-tag stripping happens before the brace scan,
because a reasoning block can plausibly contain JSON-like text that would
otherwise fool the first-brace / last-brace heuristic. The raw-newline fixer
runs last, because it only makes sense once a JSON block has been isolated.
When Moshi still rejects the result, the use case retries once with a stricter
"RETURN JSON ONLY" prompt, and if that fails too, it saves the raw transcript
as a plain-text note with a friendly non-blocking notice — the user never
lands on an error screen.

### Multilingual native — same language in, same language out

The app supports voice notes in English, Italian, Spanish, French, German, and
Portuguese. The interesting choice here is what the app *doesn't* do: it does
not translate. Once the recognizer has produced a transcript, the structuring
prompt instructs the model to write `title`, `tags`, and `body_markdown` in that
same language, not in English. There is no "translate to canonical language for
storage" intermediate step — an Italian dictation produces an Italian Markdown
note, a Portuguese dictation a Portuguese one. This sounds obvious until you
realize that most LLM-based productivity tools default to English output
regardless of input language — a quiet indignity that becomes loud once you
notice it.

One honest caveat lives upstream of all this, at the speech layer, and it took a
real multilingual test to surface it: Android's `SpeechRecognizer` does **not**
reliably detect the spoken language — you tell it which language to expect.
Notari's "Auto" setting therefore means *use the phone's system language*, not
*figure out what I'm speaking*. On an English-locale phone, dictating Italian
under "Auto" comes back as garbled English, so a multilingual user pins Italian
first — one tap on the capture screen, where the chip now shows the effective
locale (e.g. "AUTO · EN") so the fallback is never a surprise. I'd rather be
straight about that than ship a magic-detection claim the platform can't honor.
Real spoken-language detection is a speech-path upgrade on the roadmap (see
*What's next*). Six languages is a v1 choice limited by what `SpeechRecognizer`
reliably handles on shipped Android devices; more follow as that path improves.

### When you can't speak: Silent Mic

A voice note app has one obvious blind spot — the moments you can't talk. On a
plane, on a train, in a meeting where saying your notes out loud would be
absurd — or at 3 a.m., when you wake up with a dream you want to pin down and
don't want to wake the person sleeping next to you. I hit those moments
constantly, so the capture screen has a second entrance: a keyboard icon that opens **Silent Mic**, a plain text field that
feeds straight into the same structuring pipeline. You type the messy thought
instead of speaking it, Gemma 4 gives it the same title-tags-datetime-Markdown
treatment, and the result is indistinguishable from a dictated note. It cost
almost nothing architecturally — the structuring step never cared whether the
transcript came from the microphone or the keyboard — and it means the app
still works in exactly the situations where a pure voice recorder goes mute.
The typed text never leaves the device either; the privacy promise covers both
doors.

### Markdown that round-trips into your vault

The whole point of structuring a note is that you can take it somewhere else.
Every note exports as a portable Markdown file with **YAML frontmatter** — the
metadata block Obsidian, Hugo, Jekyll, LogSeq and most static-site tools already
understand. A single renderer feeds both the single-note share sheet and the
batch "export selected notes to a ZIP," so a file you share and a file you
bulk-export are byte-for-byte identical. The frontmatter carries everything
Gemma 4 extracted, not just a flat string:

```yaml
---
title: "Riunione con Marco — progetto Atlas"
created: 2026-05-14T22:31:07Z
updated: 2026-05-14T22:33:50Z
language: it
tags: [riunione, progetto-atlas, onboarding]
mentions:
  - surface: "domani alle 15"
    iso: 2026-05-15T15:00:00Z
structured: true
---

# Riunione con Marco — progetto Atlas
…
```

The `mentions` block is the part I'm most pleased with: it keeps both the phrase
the user actually said ("domani alle 15") and the resolved ISO timestamp the
model inferred, side by side — so the date Gemma understood survives into the
file instead of being flattened away. Drop the note into an Obsidian vault and
the tags become vault tags, the dates become queryable properties, and the note
stops being trapped inside one app. A tool that ignores frontmatter still
renders the document correctly, thanks to the `# Title` heading and the body
below the block. This is what closed the loop on the problem I started with:
getting a structured note out of the recorder and into the place where I
actually keep my knowledge.

### A note you can find — and keep adding to

Capturing was never the hard part of the original problem; finding the note
three weeks later was. So the notes list is built for retrieval: a debounced
search across titles and bodies, and a tag-chip filter that narrows to a single
topic — both running entirely over the local Room database, so no search index
ever leaves the device. And because a thought is rarely finished in one sitting,
any note can be extended by voice: open it, tap the mic, dictate the follow-up,
and the new dictation is structured on its own and appended to the existing
note, with the two tag sets merged. Select several notes and you can export the
whole set at once as a ZIP of Markdown files. Capture, find, extend, take it
with you — the loop a flat wall of recorder transcripts could never close.

### Audio non-persistence — verifiable, not asserted

The privacy story is the product, so the engineering needs to back it
literally. Audio captured by `MediaRecorder` is held in memory only, streamed
to the speech recognizer as it is captured, and the buffer is overwritten the
moment the transcript exists. No `.wav`, `.m4a`, `.aac`, or any other audio
file is ever written to disk — not even to cache. You can verify this
directly: start a recording, then `adb shell run-as com.voicenotemd.app find .
-name '*.wav' -o -name '*.m4a'` returns nothing, during recording and after.
On the network side, the merged `AndroidManifest.xml` carries no `INTERNET`
permission, and a CI gate parses the merged manifest of every commit to
enforce that no transitive library quietly reintroduces it. Privacy claims
that depend on developer discipline rot; privacy claims that fail the build at
PR time hold.

Privacy doesn't end at the network boundary, either. Settings can require a
fingerprint or face unlock every time the app opens, so your notes stay sealed
even when the phone itself is unlocked and sitting in someone else's hands.

### LiteRT-LM integration: lazy load, GPU-first, thermally aware

The inference engine is wrapped in `LiteRtLmGemmaSession`, which exposes a
tiny suspend-fun surface (`generate`, `warmUp`, `isReady`) and hides all of
the load-time complexity. The engine is created lazily on first call rather
than at app startup, because a 1.5 GB `.litertlm` file allocates real RAM and
the user might bounce off the privacy info screen without ever needing
inference. When generation does fire, the factory tries `Backend.GPU` first —
on a Pixel 7 this typically brings a 30-second note from ~60 seconds of CPU
inference down to ~20 seconds — and silently falls back to `Backend.CPU` if
GPU initialization fails. Multi-Token Prediction speculative decoding is
enabled when available, giving another 2-3× decode speedup on GPU. A mutex
around engine creation prevents two concurrent callers from each trying to
GPU-init the model in parallel, a race that once turned a 20-second inference
into a 60-second timeout in the wild. The engine is released only on genuine
system memory pressure, not on every backgrounding, so a save-and-go round
trip doesn't pay a cold-reload penalty.

## What I learned about Gemma 4 E2B

Three observations from the on-device runs are worth sharing with anyone
considering Gemma 4 E2B for a similar problem. They are not the kind of
takeaways you get from a benchmark; they only show up once you have a real
prompt, a real model file, a real device, and an honest evaluation eye.

**E2B respects the spirit of "transform, don't invent" if the prompt is
strict.** This was the open question I cared about most. In testing the
structuring on a real dictation that ended with *"…wait, there was something
else I wanted to write, something about sensors, but I don't remember. I'll
note it later"*, the model kept that exact uncertainty in the body, without
trying to fill in what "something about sensors" might have been. It also
preserved transcription errors that came out garbled from speech-to-text
without rewriting them into something plausible. That conservatism is what
makes the output trustworthy — and it has a cost worth being honest about. When
speech-to-text mangles a word, the model leaves the mangled word in place
rather than guessing what you meant, so a note can occasionally read a little
oddly. That's a deliberate trade: I'd rather take an odd word I can fix in two
taps than a confident rewrite I'd never think to check, which is exactly why
every note stays fully editable before and after saving. The prompt does most
of the work — an ABSOLUTE
RULES section, clear examples, an explicit "when in doubt, keep the original
word" clause — and E2B follows it reliably enough that I have not needed a
hallucination-recovery path on top.

**The famous "Thinking Mode" isn't accessible from prompt instruction on this
stack.** This surprised me, because the broader Gemma 4 community talks about
the reasoning trace as one of the model family's defining features. I ran a
controlled spike: modified the structuring prompt to explicitly request
reasoning inside `<thought>` tags before the JSON, rebuilt, reinstalled, and
processed three real voice notes on a Pixel. Three out of three, the model
emitted the JSON directly with no reasoning trace at all. I cannot tell from
outside whether the LiteRT-LM runtime strips the reasoning tokens, whether the
INT4-quantized E2B variant simply doesn't surface them, or whether the dense
JSON-only prompt context overrode the late-added instruction. The practical
conclusion is the same: if you're building on edge Gemma 4 via LiteRT-LM on
Android today, you don't get the "show the user the model's reasoning" UX for
free.

**JSON output is reliable in shape, occasionally non-pristine in punctuation.**
Across the dictations I tested in Italian, English, and Spanish, every single
response was parseable into the schema without ever falling through to the
plain-text fallback. The shape is rock-solid. What varies, run to run, is
presentational detail: sometimes compact single-line JSON, sometimes
pretty-printed; tags occasionally with a literal newline inside a string
(technically invalid JSON); the body markdown alternating between task-list and
prose for similar transcripts. None of it is dangerous, and a small
sanitization layer absorbs it. If you build on E2B and care about structured
output, plan on a sanitization layer between the model and a strict parser —
you'll need it occasionally, and adding it once beats living with intermittent
parse failures.

There's a mental adjustment that has to happen first, and it's worth naming
because it colored everything else. We've all gotten used to what the cloud
giants — Google, Anthropic, OpenAI — let us do, and the first time you hold a
model entirely on your own phone, with no account and no network, the instinct
is to expect one of *those*, just smaller. It isn't. E2B is a small model, and
treating it like a shrunken GPT is the fastest route to disappointment. The
whole game is the opposite: find the narrow thing it does genuinely well — here,
disciplined structure-extraction from a transcript — build the product tightly
around that, and design so its weak spots never get the chance to show. Once I
stopped asking it to be clever and started asking it to be reliable, it stopped
feeling like a compromise and started feeling like the right tool. Having a
capable LLM in your pocket, fully private, with no cloud behind it, is genuinely
a small marvel — as long as you meet it on its own terms.

The recommendation, if you're starting from here: lean on a strict schema in
the prompt and a forgiving parser in the code, not the other way around. E2B
follows a firm, opinionated, example-rich prompt with surprising consistency,
and the few output quirks it produces are exactly the kind of thing a
sanitization step handles cheaply. Treat the model as a small, fast,
opinionated colleague who needs clear briefing and gentle proofreading, not as
an oracle. That mental model is what makes E2B feel like the right tool instead
of a compromise.

## What's next

The most consequential next step is **replacing Android's `SpeechRecognizer`
with Gemma 4's audio-native input**. Today the pipeline is voice →
SpeechRecognizer (text) → Gemma 4 (text) → structured note; tomorrow it can
collapse to voice → Gemma 4 (multimodal) → structured note in a single forward
pass. The model weights are already designed for that — Gemma 4 E2B and E4B are
natively multimodal — but public LiteRT-LM inference of the audio variant on
Android is not yet shipped. Once it is, the rewrite is local to `:core:asr` and
everything from the capture screen upward stays the same. It also folds in real
spoken-language detection — the thing Android's `SpeechRecognizer` can't do
today, which is why "Auto" currently falls back to the phone's system language —
because a multimodal model can infer the language straight from the audio.

One item rounds out the v2 roadmap: **function calling** on edge Gemma 4 would
let the structuring step return typed JSON via constrained sampling rather than
prompt-engineered string output, removing most of the sanitization layer and
unlocking calendar integration without leaving the device. It's tracked in the
public issue tracker and doesn't block the v1 promise of *"speak, get a clean
Markdown note, audio never leaves your phone."*

One real-device finding that *did* make it into v1: Gemma occasionally anchored
a bare time like "at 3:30" to a past date instead of the next future occurrence.
A conservative future-bias guard now rolls such ambiguous mentions forward — but
only when the gap is small and the user used no explicit past reference
("yesterday", "last Friday"), so genuine historical references are left alone.
That class of bug is invisible until you dictate real notes on a real phone and
read the timestamps back — which is why the evaluation loop runs on-device, not
only in unit tests.

## A personal note

A personal note, since this is a personal project: I'm not a professional
Android developer. I had one Gemma app behind me and a lot of help — from AI
coding assistants and from documentation I read three times over. For twenty
days the loudest feeling wasn't excitement, it was the fear that I'd spend all
of them and end up with a submission that wouldn't land, or with something
short of what I'd pictured. The morning the whole test suite finally went green,
after hours of it staying stubbornly red, was the closest this project came to a
celebration. I'm leaving that here because "built with Gemma 4" should include
the honest version of who built it and how.

## Try it

- **Code** — `github.com/oierreaemme/notari` *(repo made public on submission day)*
- **APK** — direct download on the GitHub Releases page; SHA-256 published
  alongside so you can verify the file you installed is the one the source
  produced.
- **Demo video** — the 80-second walkthrough embedded above: a real Italian
  dictation structured on-device, a verification in airplane mode, and a
  filesystem check confirming no audio file is created.
- **Architecture & decisions** — `docs/decisions/` holds the 16+ ADRs written
  during development. ADR 0008 explains why the model is delivered via the
  Storage Access Framework instead of `DownloadManager`; ADR 0002 explains the
  privacy enforcement.

Notari is a research project as much as a product — every architectural choice
that went in is documented in the open, so if you disagree with one, you can
read why. Pull requests, issues, and honest criticism are all welcome.

---

*Built for the [Google Gemma 4 Challenge: Build with Gemma 4](https://dev.to/challenges/google-gemma-2026-05-06) on dev.to · May 2026 · Apache 2.0 license*
