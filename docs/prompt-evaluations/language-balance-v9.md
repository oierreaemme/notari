# Prompt evaluation — language balance and prefill pruning (v9)

**Date:** 2026-05-22
**Change:** new active prompt `structure_note_v9.txt` (was `v8`), set via `AssetPromptLoader.ACTIVE_PROMPT`. Companion code changes in ADR 0017.

## Problem observed (real device, Pixel 6a, CPU)

Even after the language-lock decorator (see `language-lock-pinned.md`), an
**English** dictation still came back partly Italian (mixed title, Italian
tags) on the latest build, and short notes sometimes timed out.

Two upstream causes, beyond the lock:

1. **Few-shot imbalance.** `structure_note_v8.txt` carried 10 worked
   examples — **9 Italian, 1 English** (verified: `OUTPUT:` count 10,
   `"language":"en"` count 1). On an autoregressive INT4 model this is a
   strong Italian prior that the lock alone did not fully overcome on short
   English notes.
2. **Cross-language tag reuse.** `{{EXISTING_TAGS}}` (now live again in v8,
   unlike the inert v1 noted earlier) plus the rule "REUSE that exact tag
   string" fed Italian tags into English notes; one Italian token in `tags`
   then dragged the rest of the JSON Italian.

(Prefill cost: v8 was 11148 bytes. On the CPU fallback path the static
prompt re-prefills cold on every `Conversation` — the dominant latency
term — so prompt size feeds directly into the timeout problem.)

## Change

`structure_note_v9.txt`:

- **Examples cut 10 → 6, rebalanced 3 EN / 3 IT** (verified counts). Each
  example still teaches one distinct rule:
  1. EN — pure reflection, prose only
  2. IT — shopping list, bullets not checkboxes
  3. EN — event in prose + commitments as checkboxes, single topic
  4. IT — orthographic cleanup, garbled fragments + proper nouns kept
  5. IT — meta-speech preserved, not smoothed
  6. EN — multi-topic `##` headings + reuse of an existing tag
- **New tag-language rule:** "Tags MUST be in the SAME language as the note.
  Do NOT reuse an EXISTING TAG that is in a different language; coin a fresh
  one in the note's language." This is the in-prompt guard for the auto
  (no-pin) case, complementing the code-side per-language tag filter.
- All rule sections (cleanup, checkbox-vs-prose, headings, mentions, tags,
  body, schema, final checklist) carried over from v8 unchanged in intent.

Result: **8787 bytes (−21% vs v8)**, lower CPU prefill, balanced language
prior.

## Evaluation to run on-device (model cannot run in CI / off-device)

1. **Language (regression case).** Pin EN, dictate the English privacy note
   ("Note to self: the privacy promise is the product") → expect English
   title, English tags, English body, `language":"en`. Repeat with an
   Italian history present (the original failing condition).
2. **Auto-detect.** No pin, dictate English → expect English output even
   though the corpus is Italian (tests the new tag-language prompt rule).
3. **Italian quality not regressed.** Dictate the multi-topic Italian
   "riunione Jira + fiori" note → expect `##` headings with event prose
   before checkboxes (the behaviour the removed v8 Example 10 taught; now
   carried by the EN Example 6 + the Headings rule).
4. **Cleanup not regressed.** Dictate the half-asleep Italian "due sogni"
   note → expect typos fixed, "hluba chicca" kept, "Carusi" bolded.
5. **Latency.** Time a short (~250-char) note on the CPU device → should be
   faster than v8 by roughly the prefill saved on ~2.3 KB.

## Known caveat

E2B can still slip on very short notes — the rebalanced examples and the
tag-language rule are strong nudges, not hard constraints. If a slip is
seen, add the failing transcript to the living eval fixtures under
`core/inference/src/test/resources/prompt-eval/` with the JSON we wish the
model had produced. The deterministic post-processing pipeline (ADR 0015)
still enforces formatting regardless of model output.
