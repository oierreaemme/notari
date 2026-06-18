# 31. LiteRT-LM native structured output for the structuring step

Date: 2026-06-18
Status: Proposed (spike required before Accepted)

## Context

ADR 0005 froze the structuring contract as a **prompt-engineered JSON
envelope** parsed with Moshi (lenient) behind a sanitize → retry →
plain-text-fallback ladder, and rejected Gemma's function-calling / tool-use
mode for v1 (*"revisit for v2"*). This ADR revisits that note and turns the
earlier research note `docs/research/constrained-decoding-investigation.md`
(2026-05-29) into a decision. That note inspected the artifact we actually
ship and its findings remain authoritative; this ADR does not contradict
them, it acts on them.

**What our shipped runtime (LiteRT-LM `0.11.0`) actually exposes.** A
decompile of `litertlm-android:0.11.0` (research note, 2026-05-29) found:

- `ExperimentalFlags.enableConversationConstrainedDecoding` — a one-line
  flag, same singleton as the MTP flag in
  `LiteRtLmGemmaSession.engineFactory`.
- Constrained decoding here is **tied to the tool-calling path**: it
  constrains output to a *tool call* whose arguments match an `OpenApiTool`
  schema. `ConversationConfig` carries `tools` + `automaticToolCalling`; the
  artifact ships `OpenApiTool` / `Tool` / `ToolManager` / `ToolParam`.
- There is **no** generic `responseFormat` / `jsonSchema` / `setResponseSchema`
  on `SessionConfig` / `ConversationConfig` / `SamplerConfig`. A free-form
  completion cannot be constrained to an arbitrary JSON schema on `0.11.0`.

So on the version we ship, the **only** native structured-output lever is the
tool path:

```kotlin
// 0.11.0 — constrain output to an OpenApiTool's argument schema
ExperimentalFlags.enableConversationConstrainedDecoding = true   // @ExperimentalApi
val conversation = engine.createConversation(
    ConversationConfig(
        tools = listOf(structuredNoteTool),  // OpenApiTool: title, bodyMarkdown, tags[], mentions[], languageBcp47
        automaticToolCalling = false,          // app intercepts/validates before use
    )
)
```

A public LiteRT-LM example (Genie) confirms this
`ConversationConfig(tools=…, automaticToolCalling=false)` surface in
production use.

**The generic `setResponseSchema` path is NOT available to us today.** A
separate public example (samdude) shows
`LlmInferenceOptions.builder().setResponseSchema(schema)` — but that is on
LiteRT-LM **`0.12.0`** and uses the MediaPipe-style `LlmInferenceOptions`
surface, not the `ConversationConfig` API we use. We deliberately deferred
the `0.11 → 0.13` bump (session recap 2026-06-10: *"no relevant fix
documented, API risk unjustified"*). A generic response-schema lever is
therefore a **future option gated on a version bump**, not something we can
adopt now.

**Why this is not urgent.** Per the 2026-05-29 research note, constrained
decoding only removes the *malformed-JSON* slice of ADR 0005's fallbacks; the
**dominant real-world failure is timeout**, which it does not fix. The
on-demand "Structure with AI" retry (shipped 2026-05-29) already covers the
user-visible problem. Constrained decoding also guarantees structure, not
semantics, and the flag is `@ExperimentalApi` (can shift between patch
releases — we already wrap the MTP flag in `runCatching`).

## Decision (proposed)

Keep ADR 0005's prompt + parser + plain-text fallback as the **shipping
path**. Treat native constrained decoding as a **prototype-before-adopt**
item (consistent with the 2026-05-29 research note's recommendation), and
only via the tool path that exists on `0.11.0`. Spike, time-boxed, in
`:core:inference`:

1. **Re-architecture cost & schema fit.** Define the structured note as an
   `OpenApiTool` and switch the generate call to read tool-call arguments
   instead of parsing a text completion. Can the `OpenApiTool` schema
   express the full v1 contract — in particular `mentions[]` as an array of
   objects (`surface_form` + nullable `iso_resolved`) and a free-form
   `body_markdown` string?
2. **Does the flag actually constrain on our artifact?** Set
   `enableConversationConstrainedDecoding = true` (wrapped in `runCatching`
   for the `@ExperimentalApi` risk); measure parse-success rate on the
   existing `prompt-eval/` corpus on **both** GPU and CPU backends (Pixel 6a).
3. **Latency.** Decode-latency delta from the constraint vs prompt v14, cold
   and warm, GPU and CPU (unmeasured on CPU per the research note); must
   respect ADR 0023's 8 s quick-wait budget.
4. **Redundancy check.** Does the constrained path let us drop the
   Thinking-Mode strip (ADR 0010) and the stricter-prompt retry (ADR 0005),
   or do they interfere?

Then choose:

- **If the tool-path constraint works and the re-architecture is contained:**
  adopt it as the primary structuring path; the existing Moshi mapping
  becomes a **validator** over the tool-call arguments; keep the plain-text
  fallback for the timeout / OOM slice it does not solve. Flip to Accepted,
  amend ADR 0005, supersede the research note.
- **If it only partially helps or the re-architecture is heavy:** stay on
  ADR 0005 (it already handles the user-visible case) and record the measured
  numbers here. This is the likely outcome.
- **Revisit `setResponseSchema` only if/when we bump to ≥ `0.12.0`** for
  independent reasons — it would be simpler than the tool path (single-pass,
  no tool envelope), but it does not justify a version bump on its own.

The `llama.cpp` / GBNF migration stays out of scope / parked: it replaces the
runtime and forfeits MTP (ADR 0011) and the GPU work (ADR 0030).

## Consequences

- No change to the shipping path now; we have a dated, version-accurate
  decision instead of an open question, plus a concrete spike for if/when we
  pursue it.
- Any adoption couples `:core:inference` to the `@ExperimentalApi` tool
  surface of a specific LiteRT-LM version — pin and test on both backends,
  wrap in `runCatching`.
- Privacy posture unchanged: the `OpenApiTool` here carries data only; no
  tool performs I/O or touches the network. The no-INTERNET CI gate is
  unaffected.

## Alternatives considered

- **Keep ADR 0005 as-is (status quo).** The current default and likely
  outcome: it already addresses the user-visible parse-failure case via the
  on-demand retry, and timeout (the dominant failure) is outside constrained
  decoding's reach.
- **Generic `setResponseSchema` (from `0.12.0`+).** Cleaner than the tool
  path (single-pass, no envelope) but absent on `0.11.0` and gated on a
  deferred version bump. Future option, not now.
- **Migrate to `llama.cpp` + GBNF grammar.** Strongest guarantee,
  disproportionate cost (runtime replacement, loses MTP + the GPU work).
  Rejected.

## References

- ADR 0005 (JSON contract + plain-text fallback — this ADR revisits its
  "revisit for v2" note), ADR 0010 (Thinking-Mode strip), ADR 0011 (backend
  probing + MTP), ADR 0015 (LLM emits intent, code imposes form), ADR 0023
  (async structuring budget), ADR 0030 (GPU `uses-native-library`).
- `docs/research/constrained-decoding-investigation.md` (2026-05-29) —
  decompile of the shipped `0.11.0` artifact; authoritative on what the
  runtime exposes. This ADR turns that research into a decision.
- Public LiteRT-LM example using `ConversationConfig(tools=…,
  automaticToolCalling=false)`:
  dev.to/asimie/genie-building-a-privacy-first-autonomous-agent-that-controls-your-phone-entirely-offline-4da2
- Public LiteRT-LM `0.12.0` example using `setResponseSchema` (different API
  surface; not on our shipped version):
  dev.to/samdude/gemma-4-on-android-tricks-for-faster-on-device-inference-3kj5
- LiteRT-LM Kotlin API reference:
  github.com/google-ai-edge/LiteRT-LM/tree/main/docs/api/kotlin
