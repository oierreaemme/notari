# ADR 0006 — MVI state shape for feature ViewModels

- **Status:** Accepted
- **Date:** 2026-05-09
- **Deciders:** Notari engineering

## Context

CLAUDE.md section 5 mandates a single state-management shape for every
feature ViewModel. We codify it here so future contributors don't
invent variants.

## Decision

Every feature ViewModel exposes exactly:

```kotlin
val uiState: StateFlow<XxxUiState>
val uiEvents: SharedFlow<XxxUiEvent>
fun onIntent(intent: XxxUiIntent)
```

- **`UiState`** is an immutable data class. It contains everything the
  screen needs to render. No `LoadingState` enum scattered in fields —
  if the screen has multiple loading regions, we model them as a sealed
  class on the affected sub-state.
- **`UiEvent`** is a sealed interface for one-shot side effects:
  navigation, snackbars, system intents (share sheet). Replay is 0 —
  these are events, not state.
- **`UiIntent`** is a sealed interface that enumerates every action the
  screen can ask the ViewModel to perform. The ViewModel's `onIntent`
  is the only public mutation entry point.

Naming: `CaptureUiState`, `CaptureUiEvent`, `CaptureUiIntent`,
`CaptureViewModel`. No "Action", "Effect", "Reducer" suffix variants.

The ViewModel is annotated with `@HiltViewModel` and accepts
`UseCase` interfaces (defined in `:core:common` or per-feature `domain/`)
plus `AppDispatchers`. It does not accept Android `Context`, repository
implementations, or DAOs directly.

## Alternatives considered

- **Compose State Holder pattern (state + callbacks, no ViewModel).**
  Rejected because we need scoped coroutine work and process-death
  survival. ViewModels give us both for free.
- **Redux-style single global store.** Overkill for a 4-feature app
  and creates coupling between unrelated screens. Rejected.
- **MVVM with mutable observables.** Rejected — non-determinism we
  don't need, and the testing story is worse than `StateFlow` +
  Turbine.

## Consequences

- Reviewing a ViewModel diff is mechanical: which intents changed,
  which states changed, which events emit. No "where does this side
  effect happen?" archaeology.
- Screenshot tests can pin one-shot rendering by passing a fixed
  `UiState`; we never need to drive a real ViewModel from a screenshot
  test.
- Cross-screen state sharing happens via repositories with their own
  `Flow`s — never via a shared ViewModel.
