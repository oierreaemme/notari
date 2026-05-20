# 14. Orthographic cleanup: where "transform" stops being "invent"

Date: 2026-05-16
Status: Accepted

## Context

CLAUDE.md Pillar 4 is unambiguous: *"The model must only **transform** what
the user said. It must never invent dates, names, or facts not present in the
transcript."* The prompt enforced this with a single line:

> Only transform what is in the transcript. Never invent facts.

In production this turned out to be *over-applied*. A user dictating
half-asleep early-morning notes (typed input, fingers on phantom keys) ended
up with output that preserved every typo and ASR-style garbage token
literally:

| Input              | v5 output         | What the user wanted |
|--------------------|-------------------|----------------------|
| "svejato"          | "svejato"         | "svegliato" (typo)   |
| "sabia"            | "sabia"           | "sabbia" (typo)      |
| "integrale rno"    | "integrale rno"   | "intorno" (word break) |
| "arrabbia con i turisti" | "arrabbia con i turisti" | "arrabbiata con i turisti" (missing ending) |
| "hluba chicca"     | "hluba chicca"    | unchanged — irrecoverable garbage |
| "Carusi"           | "Carusi"          | "Carusi" — proper noun, preserve |

The first four are *transforms*, not inventions: the user clearly meant the
corrected version, the model is restoring a word it can identify with high
confidence. The fifth is *correctly preserved* — there's no certain
reconstruction, inventing one would be worse than leaving the mess. The
sixth is *correctly preserved* — it's a proper noun.

So the failure was not the user model of what to do, it was that the prompt
never said "this kind of correction is allowed". The model defaulted to the
safest reading of the no-invent rule.

## Decision

Add an explicit `Cleanup — REQUIRED orthographic corrections` section to the
prompt (now v6, `structure_note_v6.txt`) that:

1. **Permits and requires** fixing:
   - Clear orthographic typos (missing letters, wrong endings, accents).
   - Word-segmentation errors from dictation where the fragment is
     unambiguously the tail of a known word
     (*"integrale rno"* → *"intorno"*).
   - Missing punctuation that obscures clauses.
2. **Forbids** fixing:
   - Garbled fragments where no certain reconstruction exists
     (*"hluba chicca"*, *"oasi o tetta"* — left verbatim).
   - Proper nouns, even unusual ones (*"Carusi"*, *"Lakia"*, *"Remo reale"*).
   - Style or phrasing — clunky sentences keep their clunk.
3. **Explicit conflict resolution**: the "no invention" rule overrides the
   cleanup rule whenever they meet. If the model is in doubt, it keeps the
   original token.

A worked Example 7 in the prompt demonstrates all three behaviours on one
transcript: fixed typos, kept garbled fragment, preserved proper noun. The
example matters more than the rule text for a 2B-effective model — E2B
mimics the form of the examples more than it parses the rules
(observation reused from v4's framing flip; see ADR 0011 area).

## Alternatives considered

**Run a deterministic spell-checker before the model.** Rejected. A spell
checker doesn't know which corrections are appropriate — it would "fix"
proper nouns and made-up words, exactly the opposite of what we want. The
contextual judgment is precisely what the LLM is for.

**Two-pass: structure first, then a cleanup pass.** Adds latency for no
quality gain. The structuring pass is already producing prose — adding a
separate cleanup step would double the inference time and create new
seams between transforms. One prompt, one pass.

**Surface a "cleanup aggressiveness" slider in Settings.** Considered
deferring to the user. Rejected because it shifts a calibration job onto
the user that they can't reasonably make on a per-note basis. The right
default is the right default; if it stops working we revisit per-language
or per-input-modality.

## Consequences

- **Notes look like cleaner Italian/English** by default. Half-asleep
  typing and recognizer artefacts get smoothed where safe.
- **Proper nouns are explicitly safe.** Names like "Lakia", "Carusi",
  "Remo reale" — which an aggressive spell-corrector would mangle — are
  preserved because the prompt says so and Example 7 shows it.
- **Garbled sequences are still preserved verbatim.** Users will recognize
  their own garbage when they re-read; the model won't invent meaning to
  fill the gap. This protects the "no invention" pillar even when the
  cleanup rule is active.
- **Token cost**: v6 prompt is ~150 chars longer than v5 due to the
  Cleanup section and Example 7. Prefill overhead increases ~50ms per
  call on Pixel 6a CPU — negligible relative to the multi-second
  generation budget.
- **Rollback path**: `AssetPromptLoader.ACTIVE_PROMPT = "structure_note_v5.txt"`
  reverts to the previous calibration. All prior versions remain in
  `assets/prompts/`.
- **Eval coverage**: the prompt-eval fixture suite under
  `core/inference/src/test/resources/prompt-eval/` should grow a new
  fixture pair specifically for "typed half-asleep, mix of typos and
  garbage and proper nouns" — adding it the next time a real user note
  surfaces a regression.

## References

- `core/inference/src/main/assets/prompts/structure_note_v6.txt` — new
  prompt with Cleanup section + Example 7.
- `core/inference/src/main/java/com/voicenotemd/core/inference/prompt/AssetPromptLoader.kt` —
  `ACTIVE_PROMPT` flipped to v6.
- ADR 0005 — JSON output contract (still binding).
- ADR 0012 — Tag consistency (still applies; v6 keeps the `EXISTING TAGS` rule).
- CLAUDE.md Pillar 4 — "no hallucination of content" (this ADR refines the
  pillar's operational meaning without weakening it).
