# ADR 0005 — JSON output contract for the structuring step

- **Status:** Accepted
- **Date:** 2026-05-09
- **Deciders:** Notari engineering

## Context

Gemma's structuring output is the contract between the model and the
app. CLAUDE.md pillar 3 requires deterministic, schema-conforming
output, with a documented fallback when the model misbehaves.

We have to decide:

1. The exact schema.
2. The retry / fallback behavior on parse failure.
3. The JSON parser configuration (lenient vs strict).
4. How prompt evolution is versioned.

## Decision

### Schema (frozen for v1)

```json
{
  "language": "<bcp-47 primary tag>",
  "title": "<≤ 60 chars>",
  "tags": ["<lowercase-kebab>", "..."],
  "mentions": [
    { "surface_form": "<verbatim>", "iso_resolved": "<ISO-8601 | null>" }
  ],
  "body_markdown": "<Markdown body, no top-level # heading>"
}
```

This schema is encoded in `core/inference/.../schema/StructuredNoteJson.kt`
(Moshi codegen) and mirrored in the prompt at
`core/inference/src/main/assets/prompts/structure_note_v1.txt`. Any
change to the schema is a *breaking* change requiring `_v2`.

### Retry / fallback

```
1. Send prompt v1, get response R₁.
2. Sanitize R₁ (strip code fences, locate {...}). Parse with Moshi (lenient).
   ✔ on success → produce StructuredNote, save Note(structured = true).
   ✗ on failure → step 3.
3. Send a stricter prompt: "RETURN JSON ONLY. NO OTHER TEXT.
   The previous response could not be parsed. Output the structured note for the
   following transcript:" + transcript. Parse R₂.
   ✔ on success → produce StructuredNote, save Note(structured = true).
   ✗ on failure → step 4.
4. Save Note(structured = false), title = first 60 chars of transcript,
   bodyMarkdown = transcript verbatim. Show non-blocking notice:
   "Could not auto-structure this note — saved as plain text."
```

The user is never blocked.

### Parser configuration

- Moshi with `KotlinJsonAdapterFactory`.
- `adapter.lenient()` enabled (tolerates trailing commas, unquoted
  keys in some cases).
- Pre-parse sanitization strips the most common forms of model
  garbage (BOM, ` ```json ... ``` ` fences, prose preamble,
  in-band reasoning-trace tags — see "Reasoning-trace tag stripping"
  amendment below).
- The parser has unit tests for: clean input, fenced input, prose
  preamble, missing required field, truncated JSON, empty mentions
  filtered out, long titles truncated, and reasoning-trace tags in
  five positional / casing variants.

### Reasoning-trace tag stripping (amendment 2026-05-18)

Three independent confirmations from competitor analysis of dev.to
Gemma 4 Challenge submissions (DiagramFlowAI's `flutter_gemma`
`ThinkingResponse` stream, HumanLayer's explicit `<thought>` tag
strip-before-parse, and an AI Studio observation reported by Vinod
Kumar Jaipal) converge on the conclusion that **Gemma 4 surfaces its
"Thinking Mode" reasoning chain as in-band text tags** rather than
through a dedicated SDK channel. Our current prompt v1 instructs
JSON-only output and the default sampler (temperature 0.2) discourages
exploratory reasoning, so we do not expect these tags in steady state
— but a future prompt revision may explicitly enable Thinking Mode for
the reliability gains it brings on harder transcripts, and the model
may volunteer reasoning on edge inputs even without an explicit
instruction.

The parser now strips `<thought>...</thought>` blocks (and the
variants `<think>...</think>` and `<thinking>...</thinking>` that
appear in related model families) **before** the first-brace /
last-brace JSON-block scan. Doing it before the brace scan is load-
bearing: a reasoning block can plausibly contain JSON-like text
("I'll return `{"title":"fake"}` but actually...") that would
otherwise fool the heuristic into clipping the wrong region.

Implementation choices documented inline in `StructuredNoteParser
.stripReasoningTags`: three independent regexes (one per supported
tag literal) rather than a single alternation + backreference, to
avoid relying on JVM regex's documented-ambiguously case-insensitive
backreference behavior and to make mismatched-pair cases
(`<thought>...</think>`) unambiguously not-stripped. Each regex is
`IGNORE_CASE` + `DOT_MATCHES_ALL` + non-greedy.

Truncated reasoning traces (opening tag, no closing tag — would occur
if the model hits its token budget mid-reasoning) are deliberately
left in place. In that scenario there is no JSON output at all, so
the brace scan correctly returns `null` and the use-case layer falls
through to plain-text storage per the fallback path above.

### Prompt evolution

- File-based versioning: `structure_note_v1.txt`, `structure_note_v2.txt`,
  ...
- Each new version requires a re-run of the prompt evaluation suite
  (`core/inference/src/test/resources/prompt-eval/`) and a written
  evaluation note in `docs/prompt-evaluations/`.
- The active prompt is selected by a constant; we never silently swap
  prompts under users.

## Alternatives considered

- **Use Gemma's function-calling / tool-use mode.** Cleaner contract,
  but adds dependency on a feature that is younger than the model
  family itself, and Tasks GenAI's exposure to that surface is
  inconsistent. Rejected for v1; revisit for v2.
- **YAML output instead of JSON.** Easier for the model, harder for
  parsers, and most adversarial inputs we see exploit YAML's
  ambiguity. Rejected.
- **Stricter JSON parsing (no `lenient()`).** Rejected: model output is
  never quite RFC 8259 perfect, and lenient parsing only relaxes the
  "extra commas" rule. We don't lose meaningful safety.

## Consequences

- The prompt and the parser are co-located conceptually: changing one
  without the other is a code-review red flag.
- The fallback path means we can ship to a low-end device where Gemma
  occasionally OOMs and produces truncated output — the user still
  gets their note, just not structured.
- The user-visible "Could not auto-structure" message must be friendly
  (per CLAUDE.md UX principles) and never shame the device or the
  model.
