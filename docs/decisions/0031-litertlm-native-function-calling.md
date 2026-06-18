# 31. LiteRT-LM native structured output for the structuring step

Date: 2026-06-18
Status: Proposed (spike required before Accepted)

## Context

ADR 0005 froze the structuring contract as a **prompt-engineered JSON
envelope** parsed with Moshi (lenient) behind a sanitize → retry →
plain-text-fallback ladder. At the time we rejected Gemma's function-calling
/ tool-use mode for v1: *"adds dependency on a feature that is younger than
the model family itself, and Tasks GenAI's exposure to that surface is
inconsistent. Rejected for v1; revisit for v2."*

Public LiteRT-LM examples and the SDK reference now indicate that the
runtime exposes **two** native structured-output mechanisms on our stack
(LiteRT-LM `0.12.0`, Gemma 4 E2B), neither of which requires leaving the
runtime. This ADR revisits the "revisit for v2" note.

**Lever A — constrained decoding via `setResponseSchema` (preferred).**
The engine can enforce a JSON schema on the output directly:

```kotlin
val options = LlmInferenceOptions.builder()
    .setModelPath(modelPath)
    .setResponseSchema(schema)   // engine returns schema-conforming JSON
    .setMaxTokens(300)           // constrained JSON is short — keep KV cache tight
    .build()
```

Instead of parsing free text and hoping the model follows format
instructions, the schema is declared and the engine returns conforming JSON.
This constrains the decoder rather than parsing after the fact, and it is the
closest match to what Pillar 3 wants: one frozen schema, engine-enforced,
minimal app code. It is **single-pass and emits no tool-call wrapper**, which
fits Notari's "structure one transcript, nothing acts" shape.

**Lever B — native function calling (`tools` + `automaticToolCalling`).**
Tool use is a first-class `ConversationConfig` field:

```kotlin
val conversation = engine.createConversation(
    ConversationConfig(
        tools = tools,
        automaticToolCalling = false,   // app intercepts every call before it runs
    )
)
```

`automaticToolCalling = false` hands each emitted call back to app code for
validation before execution. This works for typed output, but for our use it
wraps the note in an `emit_structured_note(...)` tool-call envelope we would
only unwrap again — more moving parts than Lever A, since we never dispatch
tools that *act*.

**Decoder-grammar alternative (out of scope).** A GBNF grammar gives the same
shape guarantee at the decoder level, but it is a `llama.cpp` / `llama.rn`
feature, not a LiteRT-LM one, and adopting it would mean replacing the
runtime. Levers A and B make that migration unnecessary if either works on
our shipped artifact.

**Why this matters:** if LiteRT-LM enforces the schema at the SDK layer,
most of ADR 0005's sanitize/retry surface and the reasoning-trace stripping
amendment become dead weight, and Pillar 3 (deterministic output) gets
stronger for free without leaving our runtime.

## Decision (proposed)

Pursue **Lever A (`setResponseSchema`) as the primary candidate**, with
Lever B (function calling) as the fallback native path. Validate with a
**time-boxed spike** in `:core:inference` against the LiteRT-LM version we
currently ship, before committing. The spike answers five questions, in
order:

1. **Surface exists?** Does our LiteRT-LM version expose `setResponseSchema`
   on `LlmInferenceOptions` for the `gemma-4-E2B-it` `.litert-lm` asset we
   ship — on **both** the GPU and CPU backends (ADR 0011 probing must keep
   working on either path)? If not, check Lever B (`tools` /
   `automaticToolCalling` on `ConversationConfig`) before falling back.
2. **Constrains or just biases?** Does the schema *constrain sampling* (the
   real win — invalid JSON becomes unrepresentable) or only nudge the
   model? Measure JSON-validity rate on the existing `prompt-eval/` corpus
   with the schema on vs off.
3. **Schema expressiveness.** Can the schema express the full v1 contract —
   `language`, `title`, `tags[]`, `mentions[]` (array of objects with
   `surface_form` + nullable `iso_resolved`), `body_markdown` — including
   the nested `mentions` objects and a free-form Markdown string field?
   If the schema can't carry nested objects, the validator still earns its
   keep and Lever A degrades to a partial win.
4. **Cost?** Prefill/decode latency delta on the reference Pixel vs prompt
   v12, cold and warm, GPU and CPU. A schema that adds tokens to every
   prefill fights ADR 0023's 8 s quick-wait budget; `setMaxTokens` stays
   tight because constrained output is short.
5. **Determinism under our sampler.** Re-confirm behavior at our low
   temperature and with the reasoning-trace tags from ADR 0005's
   amendment — does the constrained path suppress in-band `<thought>`
   noise, or do we still need the strip pass?

Then choose:

- **If Lever A exists and constrains (expected best case):** adopt
  `setResponseSchema(StructuredNoteJson)` as the **primary** structuring
  path. The existing Moshi mapping becomes a **validator**, not a rescuer.
  Keep ADR 0005's plain-text fallback as the last resort (model OOM /
  truncation still exist). Flip this ADR to Accepted and amend ADR 0005.
- **If Lever A is absent/weak but Lever B (function calling) constrains:**
  adopt the single-tool `emit_structured_note(schema = StructuredNoteJson)`
  with `automaticToolCalling = false`; intercept callback validates, then
  unwraps. Accept the extra envelope as the cost of a working guarantee.
- **If a lever exists but only biases (doesn't constrain):** adopt it only
  if it measurably lifts validity at no latency cost; otherwise keep prompt
  v12 + parser and record the negative result here.
- **If neither lever is available on our version:** stay on ADR 0005. Do
  **not** bump LiteRT-LM solely for this without its own ADR (engine
  lifecycle / budget regressions per ADR 0016/0017 are a real cost).
  Re-evaluate at the next planned SDK bump.

The `llama.cpp`/GBNF migration is **out of scope** here and stays parked: it
trades our entire runtime for a grammar guarantee, and the two native levers
above are the cheaper test of the same hypothesis.

## Consequences

- Best case: ADR 0005's retry step and most of the sanitizer become
  belt-and-suspenders, and Pillar 3 is enforced at the sampling layer. Net
  simplification of `:core:inference`.
- The structuring code path becomes coupled to a specific LiteRT-LM minimum
  version — must be pinned and tested on both backends, and noted next to
  the `uses-native-library` GPU requirement (ADR 0030).
- Privacy posture is unchanged: constrained decoding and tool calling are
  both local inference; nothing in this design performs I/O or touches the
  network. The no-INTERNET CI gate is unaffected.
- If the spike is negative, the cost was one spike and we have a documented,
  dated answer to the "native structured output on LiteRT-LM?" question
  instead of an open investigation.

## Alternatives considered

- **Keep ADR 0005 as-is (status quo).** Zero risk, but leaves a native
  reliability lever on the table and keeps carrying the retry/strip
  machinery.
- **Function calling instead of `setResponseSchema` (Lever B as primary).**
  A working guarantee, but wraps the note in a tool-call envelope we only
  unwrap again — more moving parts than constrained decoding for a
  single-pass, non-agentic task. Kept as the fallback native path, not the
  first choice. (Multi-turn tool dispatch is rejected outright: nothing
  Notari emits *acts*.)
- **Migrate to `llama.cpp` + GBNF grammar.** Strongest shape guarantee, but
  replaces the runtime, forfeits LiteRT-LM's MTP speculative decoding
  (ADR 0011) and the GPU work of ADR 0030. Rejected as disproportionate
  until both native levers are proven insufficient.

## References

- ADR 0005 (JSON output contract — this ADR revisits its "revisit for v2"
  note on native structured output), ADR 0011 (backend probing + MTP),
  ADR 0015 (LLM emits intent, code imposes form), ADR 0023 (async
  structuring budget), ADR 0030 (GPU `uses-native-library`).
- LiteRT-LM Kotlin API reference — verify `setResponseSchema` and `tools` /
  `automaticToolCalling` support for the shipped version:
  github.com/google-ai-edge/LiteRT-LM/tree/main/docs/api/kotlin
- Public LiteRT-LM `0.12.0` example using `setResponseSchema` constrained
  decoding on Android (Lever A):
  dev.to/samdude/gemma-4-on-android-tricks-for-faster-on-device-inference-3kj5
- Public LiteRT-LM example using `ConversationConfig(tools=…,
  automaticToolCalling=false)` (Lever B):
  dev.to/asimie/genie-building-a-privacy-first-autonomous-agent-that-controls-your-phone-entirely-offline-4da2
