# Prompt evaluation — language lock on pinned dictation language

**Date:** 2026-05-22
**Change:** new `LanguageScopedPromptTemplate` decorator (in `core/inference/.../prompt/PromptTemplate.kt`), applied by `StructureNoteUseCaseImpl` only when `forceLanguage != null`.

## Problem observed (real device, Pixel 6a, CPU)

An **English** dictation ("Note to self: the privacy promise is the product") produced:

- a **mixed-language title**: "Privacy promise è il prodotto" (EN + IT)
- **Italian tags**: `#idea`, `#personale`

The user's prior notes were Italian, so their mental model was "the model went Italian," but the real cause is upstream:

1. **`forceLanguage` never reached the prompt.** It only set the stored `Language` enum and the ASR recognizer locale. The structuring prompt always ran in "detect the language" mode, so pinning a language did **not** constrain the model's output language at all.
2. Gemma 4 E2B is small and, on short notes, does not reliably hold output-language consistency — the multilingual few-shot examples in `structure_note_v1.txt` (which include an Italian example) can bleed into the output.
3. `TagValidator` trusts any mono-part tag ≥4 chars ("lavoro", "personale", "idea") as a semantic abstraction, so the wrong-language tags survived validation.

(Separately noted, NOT fixed here: `{{EXISTING_TAGS}}` and `{{NOW_ISO}}` markers are absent from `structure_note_v1.txt`, so the ADR 0012 tag-reuse nudge and Gemma-side date anchoring are currently inert. Re-enabling them risks re-introducing cross-language tag contamination and needs its own eval — deferred to post-submission.)

## Change

When the user has **pinned** a language, the base prompt is wrapped with a blunt directive prepended before the existing prompt:

> LANGUAGE LOCK — the user selected "&lt;bcp47&gt;" as the dictation language. Write the "title", every entry in "tags", "body_markdown", and each date "surface_form" ALL in "&lt;bcp47&gt;". Do NOT mix languages and do NOT borrow the language of the examples below. The "language" field MUST be "&lt;bcp47&gt;".

Implementation notes:
- The base prompt asset (`structure_note_v1.txt`) is **unchanged** — the directive is composed in code, the same pattern already used by `StricterPromptTemplate`. So the evaluated v1 baseline is untouched for the auto (no-pin) case.
- With **no pin**, the decorator is not applied → behaviour is byte-identical to before, so the existing auto-detection path carries no regression risk.

## Evaluation to run on-device (cannot run the model in CI / off-device)

For each language pinned, dictate a short note in that language and a short note in a *different* language, and confirm `title`, `tags`, `body_markdown`, `language` all come back in the **pinned** language:

- Pin EN, dictate the English privacy note → expect English title + English tags (regression case).
- Pin IT, dictate the Italian Atlas note → expect Italian throughout.
- Pin EN, dictate Italian on purpose → expect the recognizer/model behaviour to favour EN (acceptable: the user asked for EN).

## Known caveat

E2B may still occasionally slip even with the lock — it's a small model and the directive is a strong nudge, not a hard constraint. This eval is qualitative; expand the on-device pinned-language cases in the living suite if a slip is seen.
