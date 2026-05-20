# DEV post — Section 2: The Approach

> Section 2 of the DEV post per CLAUDE.md §12: "the architecture, the role of
> Gemma 4 E2B, why E2B specifically (not 4B, not cloud), what the model does
> and doesn't do." Target length: 4–5 paragraphs.
>
> This is the section that earns the right to claim what the opening hook
> promised. The hook gets the reader to nod; this section convinces them the
> nodding is justified by real engineering, not marketing. Written in English
> per CLAUDE.md §12. Uses **Notari** for the product name throughout.

---

## The Approach

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
is a strict **JSON parser** that takes the model's response and turns it
into a typed `StructuredNote` ready to save to Room. There's a fallback at
each stage so the user never sees a broken state — if the JSON doesn't
parse on the first try, we retry with a stricter prompt; if it fails again,
we save the raw transcript as a plain-text note and surface a friendly
notice. The user always ends up with a note in their hand.

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

Two architectural choices flow from picking the smallest model. The first
is **delivery via the Storage Access Framework, not a network download.**
The user obtains `gemma-4-e2b-it.litertlm` from Google AI Edge (where they
accept the Gemma license), and then explicitly imports it into Notari via
Settings → On-device model. There is no `DownloadManager` call. There is no
URL hardcoded into the app. The "zero network calls" claim becomes
literally exhaustive — verifiable by inspecting the merged
`AndroidManifest.xml` and confirming that `android.permission.INTERNET` is
not present (a CI check enforces this on every commit). The second is the
**JSON contract between prompt and parser.** The prompt instructs Gemma 4
to return one JSON object conforming to a fixed schema — `{ language,
title, tags, mentions, body_markdown }` — and nothing else. The schema is
encoded in code (Moshi adapters), mirrored in the prompt as ground truth,
and validated by the parser with lenient JSON handling plus targeted
sanitization for the most common artifacts (BOM, code fences, prose
preamble, reasoning-trace tags emitted by Gemma 4's Thinking Mode). This
contract lets us treat the model as a deterministic transformer of
transcripts to notes; when the contract breaks, we degrade gracefully.

What Notari **doesn't ask Gemma 4 to do** is just as important as what it
asks. The model never invents content. It does not add commentary. It does
not paraphrase meaning. It is allowed to remove false starts and filler
words ("uh", "ehm", "this thing"), and to fix obvious transcription errors
when context makes the intent clear, but it stays conservative — when in
doubt, the original word survives. It is not allowed to suggest actions the
user did not mention. The system prompt enforces these as ABSOLUTE RULES,
and a small adversarial evaluation suite tests them with deliberately
tricky transcripts (fragments that invite the model to "complete" the
thought, vague time references that invite hallucinated dates, and so on).
A voice note app whose AI invents content is worse than useless — it would
quietly rewrite history. Treating the model as a transformer instead of an
oracle is the architectural decision that makes the output trustworthy.
