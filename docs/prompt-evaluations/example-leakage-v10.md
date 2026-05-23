# Prompt evaluation — few-shot example leakage (v10)

**Date:** 2026-05-22
**Change:** new active prompt `structure_note_v10.txt`; supersedes v9 (which never reached a device).

## Problem observed (real device, Pixel 6a)

The model emitted the CONTENT of the worked examples as if it were the user's
note — a Pillar 4 (no-hallucination) violation:

- A note titled **"Riunione Jira e fiori per Laura"** with body about a meeting
  with Marco / the Atlassian team / the Jira migration — verbatim from v8
  Example 10. The user never said any of this.
- A note **"Deploy, sync roadmap e bug fix"** mentioning **NTR-432** and
  **Andrea**/**Sarah** — verbatim from v8 Example 8.

(Note: those strings exist only in v8. Their appearance proves the device was
running a stale v8-packaged APK — the v9 source change had not been packaged.
Fixed operationally with a clean rebuild; see ADR 0017 follow-up. v10 fixes the
underlying leakage so it cannot recur regardless of which examples ship.)

## Root cause

Small INT4 models (E2B) attend to long, vivid, specific few-shot examples over
a short transcript placed far away at the end of the prompt. With 10 (v8) — or
even 6 (v9) — richly detailed examples full of names, projects, and ticket
numbers, the model copied an example instead of processing the transcript,
especially when the dictation was short or ambiguous. The deterministic
post-processing pipeline (ADR 0015) did not catch it: `TagValidator` accepts
generic broad-category tags, and nothing anchors the body/title to the
transcript.

## Change (v10)

1. **Fewer, shorter examples: 6 → 3.** Less material for the model to copy.
2. **Low-salience content.** Generic topics (call about a quote, shopping list,
   meeting + accountant); no ticket numbers, no memorable project names.
3. **Anti-copy guard before the transcript.** An explicit block states the
   examples are format-only, contain no user information, and must never be
   copied; short transcripts must yield short notes built only from the
   transcript's own words.
4. Example dates use a neutral January reference so they cannot be mistaken for
   "today".

## Evaluation to run on-device (model not available in CI)

1. **Leakage gone.** Dictate several short, unrelated notes. Confirm NO output
   mentions Marco, Atlassian, Jira, NTR-432, Sarah, Andrea, Laura, or any
   example content. This is the binary pass/fail for the regression.
2. **Short stays short.** Dictate one short sentence → expect a short note, not
   a padded multi-section one.
3. **Formatting preserved.** Confirm checkbox-vs-event, bullet lists, and
   multi-topic `##` headings still work (Examples A/B/C cover these; the
   post-processing pipeline backstops formatting).
4. **Faithfulness.** Dictate a note with a garbled fragment and a proper noun →
   typos fixed, garbled kept, proper noun preserved, nothing invented.

## Known caveat

Leakage probability is reduced, not provably zero — it is a small model. If a
leak recurs, add the failing transcript to `core/inference/src/test/resources/prompt-eval/`
and consider dropping to 2 examples or making the anti-copy guard even blunter.
