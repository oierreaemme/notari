# Notari — Voice notes that never leave your phone, structured by Gemma 4

*This is a submission for the [Gemma 4 Challenge: Build with Gemma 4](https://dev.to/challenges/google-gemma-2026-05-06).*

---

## The problem

A bit over a year ago I tried to dictate a half-formed product idea on my
way to a meeting. I knew I would forget the specifics by the time I got to
a keyboard, so I tapped the microphone, talked for two minutes, and put the
phone away.

When I went looking for it three weeks later, I scrolled through eighty
audio files named `recording_2025-04-17_09-42-18.m4a`. I had a vague memory
of the day. I did not have a vague memory of the time. I played nine of
them back at 1.5× speed before giving up.

This is the part of voice-note apps nobody markets. Capturing a voice note
is easy — every phone has had a microphone button for fifteen years.
**Finding** a voice note three weeks later, when you only half-remember when
you said it and what you said, is where the entire premise quietly fails.

And there's a second failure underneath the first: the voice notes you
*didn't* record. The thought you started to dictate at 11 PM and put away
half-pressed because you didn't want a recording of yourself reasoning out
loud sitting in someone else's cloud. Both failures have the same root
cause: the audio capture and the audio understanding live in different
places, owned by different parties, on different rules.

**Notari** is what happens when you put them back together on a single
device. You speak. The phone transcribes locally, asks a small language
model running on the phone to give the transcript a title, tags, parsed
datetime references, and a clean Markdown body, and then forgets the audio
buffer before anything is written to disk. The note becomes a file you can
search, open, and export — not an audio blob you have to scrub through.

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

### Multilingual native, by detection rather than translation

The app supports voice notes in English, Italian, Spanish, French, German,
and Portuguese. The interesting choice here is what the app *doesn't* do: it
does not translate. The Android `SpeechRecognizer` returns a transcript in the
user's dictated language, and the structuring prompt instructs the model to
write `title`, `tags`, and `body_markdown` in that same language, not in
English. There is no "translate to canonical language for storage"
intermediate step. An Italian dictation produces an Italian Markdown note; a
Portuguese dictation produces a Portuguese Markdown note. This sounds obvious
until you realize that most LLM-based productivity tools default to English
output regardless of input language — a quiet indignity that becomes loud once
you notice it. Six languages is a v1 choice limited by what `SpeechRecognizer`
reliably handles on shipped Android devices; more will follow as the speech
path is upgraded.

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
makes the output trustworthy. The prompt does most of the work — an ABSOLUTE
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
everything from the capture screen upward stays the same.

Two smaller items round out the v2 roadmap. **Function calling** on edge Gemma 4
would let the structuring step return typed JSON via constrained sampling rather
than prompt-engineered string output, removing most of the sanitization layer
and unlocking calendar integration without leaving the device. And **a small
bug in relative date resolution** surfaced during real-device testing: a meeting
transcribed without an explicit date but with a specific time anchored to a past
date instead of the next future occurrence — the resolver needs a "future bias"
rule for ambiguous time-only references. Both are in the public issue tracker;
neither blocks the v1 promise of *"speak, get a clean Markdown note, audio never
leaves your phone."*

## Try it

- **Code** — `github.com/<USER>/notari` *(repo made public on submission day)*
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
