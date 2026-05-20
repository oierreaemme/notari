# DEV post — Section 4: Technical Highlights

> Section 4 per CLAUDE.md §12: "the prompt design, the JSON-first contract,
> the multilingual handling, the audio non-persistence, LiteRT-LM
> integration, key snippets of code." Target length: 5–6 paragraphs with
> at least one short code snippet to anchor the technical claims.
>
> Goal of the section: show that the architectural commitments described
> in Section 2 are backed by concrete, inspectable engineering — not
> aspirational architecture diagrams. Anyone reading this section should
> be able to fork the repo and see the code that does what the paragraphs
> claim.

---

## Technical Highlights

### The prompt is a contract, not a personality

The most-edited file in the repository is a 75-line plain-text asset:
`core/inference/src/main/assets/prompts/structure_note_v1.txt`. It is the
prompt that turns a raw transcript into a structured note, and it is
written as a contract between the model and the parser rather than as a
"you are a helpful assistant" preamble. The first few lines set the
output discipline:

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
then the schema is repeated explicitly inside the prompt so the model sees
the same key order and types that the Kotlin Moshi adapter expects. The
prompt is versioned by filename (`structure_note_v1.txt`,
`structure_note_v2.txt`, …) so any meaningful change to the contract is
a code-review event with a paired evaluation suite, never a silent edit.
Treating the prompt as a grammar rather than as soft instruction is what
makes a 2-billion-effective-parameter model produce reliable structured
output. With this framing, Gemma 4 E2B follows the schema with surprising
consistency; without it, the same model wanders into prose and apologies
within three transcripts.

### A JSON parser that expects messy real-world output

The model's output is almost always valid JSON, but "almost" is doing real
work in that sentence. Real edge LLM output occasionally arrives wrapped
in a markdown code fence, prefixed with a friendly "Sure, here is the
JSON:", suffixed with closing remarks, or — most commonly — containing
raw newline characters inside string values, which is invalid per
RFC 8259. The parser in `core/inference/.../schema/StructuredNoteParser.kt`
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
otherwise fool the first-brace / last-brace heuristic. The raw-newline
fixer runs last, because it only makes sense once a JSON block has been
isolated. When Moshi still rejects the result, the use case retries once
with a stricter "RETURN JSON ONLY" prompt, and if that fails too, it
saves the raw transcript as a plain-text note with a friendly non-blocking
notice — the user never lands on an error screen.

### Multilingual native, by detection rather than translation

The app supports voice notes in English, Italian, Spanish, French, German,
and Portuguese. The interesting choice here is what the app *doesn't* do:
it does not translate. The Android `SpeechRecognizer` returns a transcript
in the user's dictated language, and the structuring prompt instructs the
model to write `title`, `tags`, and `body_markdown` in that same language,
not in English. There is no "translate to canonical language for storage"
intermediate step. An Italian dictation produces an Italian Markdown note;
a Portuguese dictation produces a Portuguese Markdown note. The schema
carries a `language` field as a BCP-47 tag so callers can render notes
appropriately, but the content itself is the user's words, cleaned. This
sounds obvious until you realize that most LLM-based productivity tools
default to English output regardless of input language — a quiet
indignity that becomes loud once you notice it. Six languages is a v1
choice limited by what `SpeechRecognizer` reliably handles on shipped
Android devices; more will follow as the speech path is upgraded.

### Audio non-persistence — verifiable, not asserted

The privacy story is the product, so the engineering needs to back it
literally. Audio captured by `MediaRecorder` is held in memory only,
streamed to the speech recognizer as it is captured, and the buffer is
overwritten the moment the transcript exists. No `.wav`, `.m4a`, `.aac`,
or any other audio file is ever written to disk — not even to cache. You
can verify this directly: start a recording, then `adb shell run-as
com.voicenotemd.app find . -name '*.wav' -o -name '*.m4a'` returns
nothing, during recording and after. On the network side, the merged
`AndroidManifest.xml` carries no `INTERNET` permission, and a CI gate
parses the merged manifest of every commit to enforce that no transitive
library quietly reintroduces it. The two pillars are written into the
build: a manifest scrubber strips
`com.google.android.datatransport.*` services that ride along with some
ML libraries, and a shell script in `scripts/check-no-internet-
permission.sh` fails the build if `android.permission.INTERNET` shows up
anywhere in the merged output. Privacy claims that depend on developer
discipline rot; privacy claims that fail the build at PR time hold.

### LiteRT-LM integration: lazy load, GPU-first, thermally aware

The inference engine is wrapped in `LiteRtLmGemmaSession`, which exposes
a tiny suspend-fun surface (`generate`, `warmUp`, `isReady`) and hides
all of the load-time complexity. The engine is created lazily on first
call rather than at app startup, because a 1.5 GB `.litertlm` file
allocates real RAM and the user might bounce off the privacy info screen
without ever needing inference. When generation does fire, the factory
tries `Backend.GPU` first — on a Pixel 7 this typically brings a
30-second note from ~60 seconds of CPU inference down to ~20 seconds —
and silently falls back to `Backend.CPU` if GPU initialization fails
(some OEM device + driver combinations refuse to compile the ML Drift
kernels, and we want the app to keep working rather than fail loud).
Multi-Token Prediction speculative decoding is enabled when available,
giving another 2-3× decode speedup on GPU. A mutex around engine
creation prevents two concurrent callers — typically a `warmUp()` from
the capture screen and a `generate()` from the just-stopped recording —
from each trying to GPU-init the model in parallel, a race that caused
one of them to fall back to CPU mid-session and turned a 20-second
inference into 60-second timeouts in the wild. The `ComponentCallbacks2`
hook releases the engine only on `TRIM_MEMORY_COMPLETE`, the genuine
"system is killing background processes" signal — earlier thresholds
released too aggressively and made every save-and-go round trip pay
a 15-second cold-reload penalty.

### Storage Access Framework, not DownloadManager

The model arrives on the device via explicit user import: the user
downloads `gemma-4-e2b-it.litertlm` from Google AI Edge (where they
accept the Gemma license), then imports it through Settings → On-device
model, which opens the standard system file picker. The file lands in
`filesDir/models/gemma-4-e2b-it.litertlm`, app-private and removed on
uninstall. There is no `DownloadManager` call, no hardcoded mirror URL,
no telemetry pinging back that the import succeeded. The "zero network
calls" claim is therefore literally exhaustive — including for model
delivery — and the README's verification instructions point to an
`adb` command anyone can run to confirm. Imports stream the bytes to a
`.part` file and atomic-rename onto the canonical path on completion,
so a partial write never leaves a half-imported model that the loader
would otherwise try to use.
