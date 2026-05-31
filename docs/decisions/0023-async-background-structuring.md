# 0023. Structure notes asynchronously, off the capture critical path

- **Status:** Proposed
- **Date:** 2026-05-31

## Context

Today the capture flow is strictly sequential and **blocking**:

```
Recording → stop() → Transcribing (whisper.cpp) → Structuring (Gemma) → Reviewing → Save
```

The user stares at the `Structuring` pane until Gemma returns. On a warm GPU
that is ~15–25 s; on the CPU fallback path it is far worse — `StructureNoteUseCaseImpl`
sizes per-pass budgets up to `MAX_PASS_BUDGET_MS = 250_000` (250 s), and the
2026-05-17 incident notes describe real 78 s structuring of a 470-char note on a
Pixel CPU. For an app whose pitch is "capture a quick thought", a multi-minute
blocking wait is the single worst UX property we ship, and the timeout it forces
is the dominant cause of plain-text fallbacks in the field.

The transcript itself is cheap and is already in hand the moment whisper
finishes. Nothing about *persisting the note* needs Gemma — structuring only
upgrades a note that is already complete and readable as plain text.

## Decision

**Decouple structuring from capture. Persist the transcript immediately; run
Gemma in the background; update the note in place when it finishes.**

New flow:

```
Recording → stop() → Transcribing → SAVE (plain note, structured=false) → user is free
                                          └── background: Gemma structures → UPDATE note in place
```

1. On `stop()`, after whisper returns, **save the note immediately** as a
   plain-text note (`structured = false`) and let the user leave the capture
   screen (navigate to the note, or back to capture for the next thought).
2. Kick off structuring as a **background job** that survives navigation. The
   existing microphone foreground service is the wrong vehicle (it implies
   recording); use either a short-lived `dataSync`/no-type foreground service or
   a `WorkManager` `CoroutineWorker`. The job loads/uses the already-resident
   Gemma engine via the same `GemmaSession`/`generationMutex` single-flight.
3. When structuring succeeds, **update the existing note** (title, tags,
   mentions, body, `structured = true`) by id — the same merge `NoteDetailViewModel.handleRestructure`
   already performs. On failure, the note simply stays plain text; the existing
   on-demand "Structure with AI" banner is the manual retry.
4. Surface completion non-intrusively: a subtle in-app signal (the note's row
   updates live via the Room flow) plus, if the app is backgrounded, a low-
   priority notification ("Note structured").

The blocking `Structuring` pane and the `Reviewing` step become **optional**.
Editing already lives in note detail, so a pre-save review is no longer the only
chance to fix the output.

## Alternatives considered

- **Status quo (synchronous).** Simplest, but keeps the multi-minute blocking
  wait and the timeout-driven fallbacks. Rejected.
- **Synchronous with a hard, short timeout + silent background upgrade.** Wait
  e.g. 8 s; if Gemma hasn't returned, save plain and continue structuring in the
  background. A reasonable middle step, and a low-risk first increment toward
  the full async design.
- **Keep a mandatory review, but make it non-blocking.** Save plain immediately,
  then show a "review structured version?" banner when ready. More UI work for
  marginal benefit over editing-in-detail.

## Consequences

- **Pro:** the worst latency leaves the user's critical path entirely; capture
  feels instant; timeout-driven fallbacks stop being user-visible.
- **Con / new complexity:**
  - A note now exists *before* it is structured — every consumer must already
    tolerate `structured = false` (it does).
  - **Concurrent-edit conflict:** the user can open and edit the plain note
    while the background job is still running, then the job overwrites it. Need
    a rule — e.g. only apply the structured result if the note's body is
    unchanged since capture, otherwise drop it (the user already curated it).
  - Background-job lifecycle, process death, and engine eviction
    (`onTrimMemory`) must be handled; `WorkManager` gives retries/constraints
    for free but adds a dependency and its own scheduling latency.
  - The `Reviewing` phase and `StructuringPane` shrink or disappear — UI and
    `CaptureViewModel` state machine simplify on the happy path but need a
    migration.
- **Privacy:** unchanged. No new permission, no network; structuring still runs
  entirely on-device.

## Rollout

Ship the **8 s-then-background** middle step first (small, reversible), measure,
then move to fully async if the data supports it.
