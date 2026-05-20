# 0008. LiteRT-LM runtime + SAF-based model delivery

- **Status:** Accepted (supersedes runtime + delivery sections of ADR 0004)
- **Date:** 2026-05-10

## Context

Two interlocking facts forced a revisit of ADR 0004:

1. **Format reality.** Google distributes Gemma 4 E2B as
   `gemma-4-E2B-it.litertlm` — the LiteRT-LM bundle format. The
   `com.google.mediapipe:tasks-genai` runtime we originally chose only
   loads MediaPipe `.task` bundles. The two formats are **not
   interchangeable**; there is no public `.task` build of E2B.

2. **Delivery story.** ADR 0004's plan (debug → `DownloadManager`,
   release → Play Asset Delivery) carries real friction:
   - The Gemma weights are gated by Google's terms of use; we cannot
     host a public, no-auth mirror without violating those terms.
   - A 1.5 GB cold-start download is a hostile first-run experience.
   - "Zero network calls" becomes an asterisk ("…except the one-time
     model download"), which weakens the privacy story we lead with.

## Decision

### Runtime: switch to LiteRT-LM

Replace `com.google.mediapipe:tasks-genai` with
`com.google.ai.edge.litertlm:litertlm`. This is the runtime Google AI
Edge is converging on for on-device LLMs and the only one that loads
`.litertlm` directly.

The replacement is local to `:core:inference`. The
[`GemmaSession`][session] interface — which is what every other module
talks to — does not change. The new
[`LiteRtLmGemmaSession`][impl] is a drop-in for the deleted
`MediaPipeGemmaSession`.

[session]: ../../core/inference/src/main/java/com/voicenotemd/core/inference/session/GemmaSession.kt
[impl]: ../../core/inference/src/main/java/com/voicenotemd/core/inference/session/LiteRtLmGemmaSession.kt

### Delivery: Storage Access Framework, not DownloadManager

The model arrives on the device the same way every other large file does:
the user downloads `gemma-4-E2B-it.litertlm` from Google AI (where they
accept the Gemma terms), and then **explicitly imports it into the app**
via the Storage Access Framework picker exposed in **Settings → On-device
model**.

Implementation:

- `OnDeviceModelRepository` (interface in `:core:common`) exposes
  `observeStatus(): Flow<OnDeviceModelStatus>` and
  `suspend fun importFrom(InputStream): ImportResult`.
- The file-backed implementation lives in `:core:inference`
  (`FileBasedOnDeviceModelRepository`). Imports stream the bytes to a
  `.part` file and atomic-rename onto the canonical path so a partial
  write never leaves a half-imported file the loader would try to use.
- The canonical path is
  `filesDir/models/gemma-4-e2b-it.litertlm` — app-private, never
  world-readable, removed cleanly on uninstall.
- For developer dogfooding, `ModelFileProvider` also resolves
  `getExternalFilesDir("models")/gemma-4-e2b-it.litertlm`, so an
  `adb push` is the fastest path during local iteration.

### Trade-offs we are accepting

- **First-run UX cost.** The user has to know to grab the model from
  Google AI before the structured-note feature works. Mitigation:
  the capture flow falls back gracefully to plain-text saves (per
  ADR 0005), and the Settings screen always surfaces a clear "Import
  model" entry with the file name we expect.
- **No auto-update.** When Google releases a newer Gemma E2B build the
  user re-imports manually. Acceptable for v1; we revisit if/when a
  signed model-update channel becomes available without violating the
  zero-network promise.

## Consequences

- The privacy story sharpens: "the app never makes a network call,
  including for the model" is now literally true. The verification
  steps in the README become provably exhaustive.
- ADR 0004's runtime-and-delivery sections are superseded by this ADR.
  The "bundle vs download" framing it explored is no longer relevant —
  we do neither.
- Build dependencies shrink: `com.google.mediapipe:tasks-genai` and
  `com.google.mediapipe:tasks-text` are removed; only
  `com.google.ai.edge.litertlm:litertlm` remains for inference. This
  also removes the need for the manifest scrubbing that ADR 0007
  introduced for MediaPipe's transitive datatransport components — but
  the scrubbing is left in place (defence in depth).
- Test coverage extends to import I/O via
  `FileBasedOnDeviceModelRepositoryTest` (atomic write, mid-stream
  failure → no partial file, delete reverts status).
