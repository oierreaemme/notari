# Prompt evaluation — EN checkbox gap (v11)

**Date:** 2026-05-30
**Change:** new active prompt `structure_note_v11.txt`; supersedes v10.

## Problem observed

In v10, Example B was an English shopping-list note (`"For tomorrow: bread, milk, eggs."`),
which teaches EN + enumeration → plain bullet list. This left a gap: there was no
English example demonstrating that `"I need to / I should / I must"` → `- [ ]` checkbox.

On a small INT4 model the worked examples are the primary signal for output format.
Because the only checkbox examples were in Italian (Example A and C), the model
occasionally produced plain `-` bullets for English commitment notes — especially
short ones where the rules text is out-weighed by the example prior.

## Change (v11)

Replaced Example B with an English commitment note:

```
INPUT: "Quick note: I need to schedule a follow-up with the dentist and send the report to Tom."
OUTPUT: {"language":"en","title":"Dentist follow-up and report for Tom","tags":["personal","work"],
         "mentions":[],"body_markdown":"- [ ] Schedule a follow-up with the dentist\n- [ ] Send the report to **Tom**"}
```

Benefits:
- **EN + `- [ ]` checkbox** — fills the most common missing pattern.
- **Bold entity** (`**Tom**`) — first example demonstrating the `**bold** key entities sparingly` rule.
- **Empty mentions** — shows that not every note has datetime references.
- **Two-tag output** — reinforces the 2–4 tag range at the lower end.
- Low salience: generic topic (dentist, report), no memorable names/ticket numbers that could leak.

The enumeration → plain bullet rule is now covered by text only (`"A LIST the user
enumerated ... is a BULLET LIST - (not checkbox)"`). This is sufficient; the rule is
unambiguous and the removal frees an example slot for the higher-value pattern.

## Companion fix

`core/inference/src/test/resources/prompt-eval/en/reminder-call.expected.json`:
- `body_markdown`: plain `- ` bullets → `- [ ]` checkboxes (transcript says "I need to call",
  "make sure to bring up", "ask her" — all commitments).
- `tags`: `["call","sarah","proposal"]` → `["work","call"]` (broad categories per the tags rule;
  proper nouns are not broad-category tags).

## Evaluation to run on-device

1. **EN checkbox regression.** Dictate: *"I need to finish the report and send it to the team
   before Friday."* → expect `- [ ]` for each commitment, NOT plain `- `.
2. **Bold entity.** Same note → expect a key person or entity in `**bold**` if one is named.
3. **IT not regressed.** Dictate the Italian multi-topic note ("Due cose…") → still `##`
   headings + `- [ ]` checkboxes under each, no EN influence.
4. **Short EN note.** Dictate one sentence with no commitment marker → expect prose paragraph,
   no checkboxes, no padding.
5. **Leakage check.** No mention of "dentist", "Tom", "report" or any example content in
   the output of unrelated notes.

## Known caveat

The enumeration → plain bullet pattern is now example-free. On E2B, a pure enumeration
("bread, milk, eggs") may still produce correct bullets (the rules text is explicit), but
if a regression is seen, add a second EN example or restore an enumeration example as Example D
(would require revisiting the 3-example cap in inference-rules.md).
