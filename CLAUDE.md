# Notari - Voice Note Markdown

## Tech Stack & Architecture
- Kotlin 2.0+, Jetpack Compose (Material 3), Room, Hilt, Coroutines/Flow.
- MVI Architecture: Presentation -> Domain -> Data. No layer skipping.
- Strict constraints: No RxJava, no LiveData, no legacy findViewById, no XML layouts (except launcher).

## Cardinal Rules (Non-Negotiable)
1. ZERO NETWORK CALLS: The app makes NO network requests. Never request INTERNET permission.
2. ZERO AUDIO PERSISTENCE: Audio is memory-only. Buffer MUST be overwritten immediately after transcription. No audio files on disk.
3. DETERMINISTIC OUTPUT: Gemma must return strict JSON.

## Progressive Context (Read before working on specific domains)
- For project goals, DEV Challenge deliverables, and human/AI roles: Read `docs/rules/PRD.md`
- For MVI, architecture rules, TDD, and CI/CD workflow: Read `docs/rules/architecture-and-workflow.md`
- For UX/UI design, Compose rules and motion: Read `docs/rules/ui-guidelines.md`
- For Gemma Prompting, JSON parsing, and LLM Inference rules: Read `docs/rules/inference-rules.md`
- For past architectural decisions and history: Read ADRs in `docs/decisions/`

## Project Documentation
- Test evaluations for Gemma prompts are in `docs/prompt-evaluations/`
- Drafts for the DEV post and video script are in `docs/dev-post-drafts/`