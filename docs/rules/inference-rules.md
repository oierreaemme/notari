# Gemma Inference & Prompt Rules - Notari

## The Gemma Prompt Principles
* The prompt lives in `core/inference/src/main/assets/prompts/structure_note_v1.txt`. Changes are versioned and evaluated.
* **Constrain output schema rigidly:** The model returns JSON, nothing else. No prose, no preamble, no closing remarks.
* **Use few-shot examples sparingly:** 2-3 examples maximum.
* **Explicit anti-hallucination clauses:** "Do not invent. Do not add. Only transform what is present."
* **Locale-aware:** Model must detect input language and respond in the same language. Datetime parsing must handle localized formats ("domani alle 15", "tomorrow at 3pm").
* **Fault tolerance:** Parse with lenient JSON parser (Moshi). If parsing fails, retry once with a stricter preamble ("JSON ONLY"). If retry fails, fallback to plain text storage.

## Prompt Evaluation
* Maintain a living test suite in `core/inference/src/test/resources/prompt-eval/` with 20+ real transcripts in 6 languages.
* Qualitative checks: JSON validity, Schema conformance, No hallucinated facts/dates, Reasonable title/tags.

## Inference & ASR Edge Cases to Handle
* Gemma model not yet loaded: show "preparing" state, queue the transcript.
* Gemma OOMs: catch, show friendly message, fall back to raw transcript.
* Malformed JSON: retry once stricter, then fall back to plain text.
* Unparseable dates: keep as text, do not crash.
* Transcript exceeds context window: truncate intelligently (keep beginning + end) and warn user.
* Background noise (ASR returns nothing): do not crash.