# 15. LLM emits intent, deterministic code imposes form

Date: 2026-05-16
Status: Accepted

## Context

By prompt v6 we had a strong but imperfect structuring contract: Gemma 4 E2B
was producing JSON that mostly matched our schema and mostly preserved
meaning. But "mostly" is exactly the gap that makes a product feel flaky.
A representative sample of real-device traces from a single morning of
dogfooding turned up:

- **Inline checkboxes**: `Mi sono svegliato confuso. - [ ] Ricordarmi…`
  with the `- [ ]` glued to the prose with no newline. Pure formatting
  failure — the words are correct.
- **`stasera` resolved to yesterday**: Gemma emitted
  `iso_resolved: "2026-05-15T22:00:00Z"` for "stasera" on a transcript
  dictated on 2026-05-16. The model has no reliable wall-clock grounding
  even with `CURRENT TIMESTAMP` in the prompt.
- **Tag hallucination**: a note about a deploy + sync meeting came back
  with tag `rag`, which appeared nowhere in the transcript and was
  apparently summoned from a prior note's vocabulary.
- **Title trailing punctuation**: `"Riunione con Marco."` as a title.
  Reads poorly as a heading; Gemma over-mimicked the source sentence.
- **Event-as-checkbox / commitment-as-prose**: a riunione (event)
  became `- [ ]`; three "devo" commitments scattered in a paragraph all
  stayed in prose.

The instinct to fix all of these *in the prompt* is the wrong instinct.
Each iteration of "tell the model more explicitly" buys probabilistic
improvement at the cost of prompt tokens and inference latency, and never
reaches 100%. Worse, prompt-level fixes are fragile — a fix for one class
of failure can erode the model's behavior on another.

## Decision

Adopt a strict split:

**Gemma is responsible for *intent***: language detection, what to call the
title, what the tags should be, what's a commitment vs an event, how to
structure the body, what counts as a datetime mention. These are *semantic
judgments* that require comprehension.

**Deterministic Kotlin code is responsible for *form***: line breaks,
whitespace normalization, ISO datetime resolution for known relative
expressions, tag anchoring, title sanitization. These have a *single
correct answer* given the input — no judgment, no comprehension required,
no opportunity for hallucination.

Concretely, three pure-function post-processors live in
`:core:inference/.../normalize/`, each ~50–200 lines, with full unit test
coverage:

1. **`RelativeDateTimeResolver`** — multilingual table (en, it, es, fr,
   de, pt) of simple relative expressions ("stasera", "tonight",
   "ce soir", "esta noche", "heute abend", "esta noite") → exact
   `Instant` via `clock + ZoneId`. When the surface form is a known
   simple expression, our value overrides Gemma's `iso_resolved`. For
   compound expressions ("stasera tardi"), we abstain and trust Gemma.

2. **`MarkdownBodyFormatter`** — regex-based: every `- [ ]` and `- `
   starts on its own line; blank line between prose and block markers;
   triple-newlines collapsed to double; trailing whitespace stripped.
   Idempotent.

3. **`TagValidator`** — strips tags that have no anchor in either the
   transcript or the existing-tags corpus. A multi-part kebab tag is
   anchored if any of its ≥3-char parts appears in the transcript;
   a mono-part short tag requires literal presence.

These run inside `StructureNoteUseCaseImpl.buildStructuredNote()` after
Gemma's JSON is parsed, in this order: `resolveMention` (mentions) →
`TagValidator.validate` (tags) → `MarkdownBodyFormatter.format` (body) →
title trim+sanitize. Each step is a *subtractive or normalizing* transform
— none can add content. Pillar 4 ("no hallucination of content",
CLAUDE.md §3) is preserved through the whole pipeline.

The prompt (now v7) is correspondingly leaner: it no longer carries
formatting whitespace rules or surface-form precision rules, because
those are now enforced in code. It focuses on the genuinely semantic
calls: commitments vs events, tag reuse policy, meta-speech preservation.

## Alternatives considered

**More prompt iteration.** Considered and explicitly rejected. The cycle
"observe failure → strengthen prompt → measure → observe new failure" had
diminishing returns by v5. The marginal token costs (longer prompt =
slower prefill on a 1.5GB on-device model) were starting to bite, and
prompt edits were starting to *trade* failures: tightening the checkbox
rule loosened the no-smoothing rule.

**Train a small adapter / LoRA on top of Gemma.** Would require GPU
training infra and a labeled dataset, neither of which we have. The
deterministic code paths we're adding *are* exactly the constraints a
fine-tune would learn, but expressed as a few regexes and tables instead
of fine-tune weights. Same constraint, infinitely cheaper.

**Use Gemma function-calling to ask the model for an iso-resolved date
in a structured tool call format.** Interesting but the tool-call
overhead doubles the inference time, and we'd still need to validate the
returned ISO — at which point we might as well compute it ourselves for
the cases we can.

**Spell-check / grammar tools (LanguageTool, etc.) for orthographic
cleanup.** Out of scope. Already handled by Gemma in v6 (and v6 stays in
charge of orthographic correction because it requires context-sensitive
judgment that a deterministic spell-checker doesn't have — see ADR 0014).

## Consequences

- **Quality is measurably higher** without requiring the model to be
  more capable. The classes of failure documented above are now
  impossible-by-construction for the things we moved to code.
- **Prompt v7 is ~10% shorter than v6** even though it adds new
  semantic guidance (Example 8 and 9). Removing formatting/precision
  rules freed budget for the genuinely-needed semantic distinctions.
- **Latency is unchanged** on the inference path (Gemma still does the
  same prefill+decode); the deterministic post-processing is
  microseconds. We may have actually saved a few hundred ms on prefill
  due to the shorter prompt.
- **Test coverage is dramatically stronger**. Each post-processor is a
  pure function with deterministic outputs; the test suite is trivially
  exhaustive and doesn't require any LLM in the loop.
  - `RelativeDateTimeResolverTest`: 20+ cases across 6 languages.
  - `MarkdownBodyFormatterTest`: 13 cases covering the edge cases.
  - `TagValidatorTest`: 11 cases covering the anchor rules.
- **The architecture becomes a teaching artifact**. For the Gemma 4
  Challenge submission, this ADR makes a substantive point that goes
  beyond "I built an app with Gemma": *the right way to use a small
  on-device LLM is to constrain it to what only an LLM can do, and
  let deterministic code do everything else*. This is the same
  discipline used in production agentic systems at scale, just
  applied at a phone-and-1.5GB-model scale.
- **Rollback paths are surgical**: each post-processor can be disabled
  independently by skipping its call in `buildStructuredNote`. The
  prompt v6 stays in `assets/prompts/` and can be re-activated by
  flipping `AssetPromptLoader.ACTIVE_PROMPT`.
- **A known design limit**: weekday resolution for "next Monday" /
  "lunedì prossimo" collapses to "next occurrence of Monday" — we
  don't try to disambiguate "this week's Monday" vs "next week's
  Monday" in Italian/English everyday speech where the boundary is
  genuinely fuzzy. We'd rather be wrong by a few days than wrong by
  a week. Documented in `RelativeDateTimeResolver` kdoc.

## References

- `core/inference/src/main/java/com/voicenotemd/core/inference/normalize/RelativeDateTimeResolver.kt`
- `core/inference/src/main/java/com/voicenotemd/core/inference/normalize/MarkdownBodyFormatter.kt`
- `core/inference/src/main/java/com/voicenotemd/core/inference/normalize/TagValidator.kt`
- `core/inference/src/main/java/com/voicenotemd/core/inference/structure/StructureNoteUseCaseImpl.kt`
  — `buildStructuredNote()` wires the pipeline.
- `core/inference/src/main/assets/prompts/structure_note_v7.txt` — the
  leaner, semantically-focused prompt.
- ADR 0014 — orthographic cleanup (still in the prompt; complementary).
- CLAUDE.md §3 Pillar 4 — "no hallucination of content" (preserved).
