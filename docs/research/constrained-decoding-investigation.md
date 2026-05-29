# Investigation: constrained decoding in LiteRT-LM 0.11.0

Date: 2026-05-29
Status: Research note (no decision taken)

## Question

Can we use LiteRT-LM's constrained decoding to guarantee Gemma emits valid JSON,
eliminating the plain-text fallbacks caused by malformed JSON (ADR 0005)?

## What the artifact actually exposes

Inspected `com.google.ai.edge.litertlm:litertlm-android:0.11.0` (the version this app
ships). Relevant findings from the decompiled API surface:

- **The flag exists.** `ExperimentalFlags` (the same singleton we already use for
  `enableSpeculativeDecoding`) has:
  ```
  private static boolean enableConversationConstrainedDecoding;
  public final boolean getEnableConversationConstrainedDecoding();
  public final void setEnableConversationConstrainedDecoding(boolean);
  ```
  So turning it on is one line, like the MTP flag in `LiteRtLmGemmaSession.engineFactory`.

- **It is tied to the Conversation + tools path, not a generic "response JSON schema".**
  The constraint is named `enableConversation…`, and the schema-bearing types are all on
  the tool-calling side: `ConversationConfig` carries `tools: List<ToolProvider>` and
  `automaticToolCalling: Boolean`, and the artifact ships `OpenApiTool`, `ReflectionTool`,
  `Tool`, `ToolManager`, `ToolParam`. There is **no** `responseFormat` / `jsonSchema`
  field on `SessionConfig`/`ConversationConfig`/`SamplerConfig` (those only hold
  `samplerConfig` = topK/topP/temperature/seed).

  In other words: constrained decoding here means "constrain the output to a **tool
  call** whose arguments match the tool's OpenAPI schema", not "constrain a free-form
  completion to an arbitrary JSON schema".

## What adopting it would require

Today the structuring path is: render prompt → `Conversation`/generate → get free text →
strip Thinking Mode (ADR 0010) → parse JSON (`StructuredNoteParser`) → retry with a
stricter prompt on failure (ADR 0005). To use constrained decoding we'd have to:

1. Define the structured note as an **`OpenApiTool`** (title, bodyMarkdown, tags[],
   mentions[], languageBcp47) and register it on the `ConversationConfig`.
2. Switch the generate call to the **automatic tool-calling** flow and read the tool-call
   arguments instead of parsing a text completion.
3. Set `ExperimentalFlags.enableConversationConstrainedDecoding = true`.
4. Re-evaluate the Thinking-Mode strip, the stricter-prompt retry, and the prompt
   template itself — several of them exist precisely to coax valid JSON out of a free-form
   completion and may become redundant (or interfere).

That is a **real re-architecture of `:core:inference`**, not a flag flip.

## Trade-offs

**Upside**
- Syntactically valid output by construction → removes the "malformed JSON twice → plain
  text" class of fallbacks (one of the two fallback causes; the other is timeout, which
  this does *not* fix).
- Likely CPU-compatible (constrained decoding is grammar/mask-based at sampling time, not
  a GPU-only feature like LoRA) — but unverified on the Pixel 6a.

**Risk / cost**
- `@ExperimentalApi`: the flag and tool API can shift between patch releases (we already
  wrap the MTP flag in `runCatching` for this reason).
- Re-architecture cost + regression surface (Thinking Mode, retry, prompt v10, the 6-language
  behaviour all interact).
- Constrained decoding guarantees **structure, not semantics** — the values can still be
  wrong/low-quality; it doesn't make Gemma "format better", only "emit parseable JSON".
- Possible decode-latency overhead from the constraint mask (unmeasured on CPU).

## Recommendation

**Promising but not urgent; prototype before adopting.** The on-demand "Structure with AI"
retry (shipped 2026-05-29) already addresses the *user-visible* problem — a note that
didn't format — and the dominant real-world failure is **timeout**, which constrained
decoding does not solve. Constrained decoding only helps the *parse-failure* slice.

Suggested path if/when we pursue it:
1. Spike behind a flag: define the `OpenApiTool` schema, enable the flag, run the existing
   `docs/prompt-evaluations` transcripts on the Pixel 6a.
2. Measure: parse-success rate (expect ~100%) **and** decode latency vs today, on CPU.
3. Only then decide whether to replace the text-parse path — it would warrant its own ADR.

## References

- Artifact inspected: `litertlm-android-0.11.0.aar` (`ExperimentalFlags`,
  `ConversationConfig`, `OpenApiTool`, `Tool*`).
- [Blazing fast on-device GenAI with LiteRT-LM — Google Developers Blog](https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/)
- [LiteRT-LM Kotlin API — DeepWiki](https://deepwiki.com/google-ai-edge/LiteRT-LM/4.6-kotlin-and-android-api)
- ADR 0005 (JSON output contract + plain-text fallback), ADR 0010 (Thinking Mode strip),
  ADR 0011/0016/0017 (CPU-bound inference on the Pixel 6a).
