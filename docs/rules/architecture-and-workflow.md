# Architecture and Workflow Rules - Notari

## Technical Stack
* **Language:** Kotlin 2.0+ (Java only when interfacing with required libraries).
* **Build system:** Gradle with Kotlin DSL, version catalogs.
* **Min SDK:** 28 (Android 9).
* **UI:** Jetpack Compose with Material 3 Expressive, Compose Navigation.
* **Architecture:** MVI (Model-View-Intent) with Clean Architecture layering.
* **DI:** Hilt.
* **Async:** Kotlin Coroutines + Flow.
* **Persistence:** Room database; DataStore Preferences.
* **Inference:** MediaPipe LLM Inference with Gemma 4 E2B (.task format, INT4 quantized).
* **Testing:** JUnit 5, Turbine, Compose UI testing, Roborazzi, Mockk.
* **Strict constraints:** NO RxJava, NO LiveData, NO legacy findViewById, NO XML layouts (except launcher icon).

## Architecture & Layering Rules
* **Presentation layer** depends only on domain.
* **Domain layer** depends on nothing.
* **Data layer** implements domain interfaces. Depends only on domain.
* No layer skipping. UI never imports from data.
* Models cross layer boundaries only as domain models.
* **State management:** Each feature ViewModel exposes a single `StateFlow<UiState>`, a `SharedFlow<UiEvent>`, and a `fun onIntent(intent: UiIntent)`. No variations.

## Test-Driven Development (TDD) Discipline
* **Domain layer & Data layer:** TDD is mandatory.
* **State logic in ViewModels:** TDD mandatory for UiState transitions.
* **JSON parsing:** TDD for all schema parser failure modes.
* **No TDD for:** Compose UI (use Roborazzi screenshot tests instead), Gemma prompt (use evaluation suites), DI wiring.
* **Coverage target:** 80% line coverage on domain and data layers. CI fails if below.

## Development Workflow & CI
* `main` is always green and releasable. Feature branches `feature/<short-name>`.
* Every PR with architectural choices needs an ADR in `docs/decisions/`.
* Commit messages: Conventional Commits format.
* **CI pipeline:** Lint -> Unit tests -> Compose UI tests -> Screenshot tests -> Build debug APK -> Verify no INTERNET permission.
* **Self-review:** Claude must verify pillar compliance, coverage, and code readability before completing tasks.

## Working Agreements
* Be concise in code comments. Comment why, not what.
* Never silence errors with empty catch blocks.
* Prefer explicitness over cleverness.
* Refactor mercilessly when adding a feature reveals a bad abstraction.