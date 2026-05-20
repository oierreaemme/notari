# ADR 0001 — Module structure and Clean Architecture layering

- **Status:** Accepted
- **Date:** 2026-05-09
- **Deciders:** Notari engineering

## Context

The CLAUDE.md contract specifies Clean Architecture (presentation /
domain / data) with strict layering, MVI in the presentation layer, and
modularity for build speed and ownership. We need to commit to a module
graph before writing code so that it survives the project's lifetime
without churn.

## Decision

We adopt a multi-module Gradle layout:

```
:app                — entry point + navigation graph + Hilt root
:core:common        — domain primitives, Result type, dispatcher bundle
:core:design        — Material 3 theme, motion, shared composables
:core:database      — Room database, DAOs, entities (data layer)
:core:datastore     — DataStore preferences (data layer)
:core:inference     — Gemma 4 E2B + MediaPipe (data layer)
:core:asr           — SpeechRecognizer wrapper (data layer)
:feature:capture    — recording + structuring screen (presentation)
:feature:notes      — list (presentation)
:feature:noteDetail — single-note view (presentation)
:feature:settings   — settings + privacy info (presentation)
```

Layering rules:

- Presentation depends only on **domain** (re-exposed via `:core:common`
  and use-case interfaces).
- Domain depends on **nothing**. It has no Android-specific imports.
- Data layer (every `:core:*` except `:common` and `:design`) implements
  domain interfaces and depends only on **domain**.
- No layer skipping. Features never reach into a `:core:*` data module
  directly — they go through use-case interfaces injected via Hilt.
- Models cross layer boundaries only as domain models. Each data module
  owns the mapping from its native shape (Room entity, JSON, etc.) to
  the domain model.

We use a `:build-logic` included build with convention plugins
(`voicenotemd.android.application`, `…library`, `…feature`, `…compose`,
`…hilt`, `voicenotemd.jvm.library`) so module build files stay short
and consistent.

## Alternatives considered

- **Single-module project.** Faster to set up; loses per-feature
  ownership and parallel compilation. Rejected: even at v1 we have 4
  features and 6 cores, and the build benefits are real.
- **Gradle composite build with separate repos for `:core:inference`.**
  Tempting because that module is the heaviest. Rejected as premature
  — the inference module is small in code, large only because of the
  bundled `.task` model, which is not a build-time concern.
- **No `:build-logic`, just copy-paste in each module.** Rejected.
  Convention plugins pay for themselves the moment we touch any version
  in `libs.versions.toml`.

## Consequences

- Adding a new feature is mechanical: create the module, apply
  `voicenotemd.android.feature`, list the `:core:*` data modules it
  needs, write a Route + ViewModel + use cases.
- Refactoring a use case forces us to think about which module owns it
  — this is desirable.
- Initial setup is heavier; we accept this in exchange for long-term
  cleanliness.
