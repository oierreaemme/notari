# ADR 0004 — Gemma 4 E2B packaging and runtime delivery

- **Status:** Superseded by ADR 0008 (2026-05-10) for both the runtime
  and the delivery sections. Kept here for historical context.
- **Date:** 2026-05-09
- **Deciders:** Notari engineering

## Context

Gemma 4 E2B (INT4 quantized, MediaPipe `.task` format) weighs roughly
1 GB. Two paths to get it onto the device:

1. **Bundle in the APK / AAB**, using Play Asset Delivery for the
   release variant.
2. **Download once on first launch** via `DownloadManager` with explicit
   user consent.

Path 1 needs a Play Store distribution; path 2 needs network access for
the download itself, which is the only network event in the whole app.

## Decision

We support **both** behind a build flag, with sensible defaults:

- **Debug builds**: download once on first launch via the system
  `DownloadManager`. The download URL is shown to the user before
  they confirm. The download itself happens in `DownloadManager` —
  our process never opens a socket. After the download completes the
  file is moved into app-private storage with read-only permissions.

- **Release builds (Play Store)**: bundled via Play Asset Delivery
  with `install-time` delivery. The APK does NOT request `INTERNET`.
  Users who sideload from GitHub will get a debug-flavored release that
  uses the download path; this trade-off is documented in the README.

A `BuildConfig`-style flag (`MODEL_DELIVERY = "bundled" | "download"`)
selects the loader. The capture screen consults
`ModelAvailability.observe()` and shows a clear "Preparing — downloading
the local model (~1 GB, one time)" state when needed.

## Alternatives considered

- **Always bundle, always.** Rejected — APKs over 150 MB are painful to
  sideload and fail on Play's APK-size limit without bundles.
- **Always download.** Rejected — Play Store users would see an
  unexpected 1 GB download on first launch, terrible first impression.
- **Use a smaller model (Gemma 4 nano, hypothetical).** No such model
  variant publicly available at v1 timeline. We accept E2B's size.

## Consequences

- The release build's manifest still has zero `INTERNET` permission
  (Play Asset Delivery handles delivery at install time, not via app
  code).
- The debug build's `DownloadManager` call is the ONLY moment in the
  app where the device touches the network on our behalf. After the
  download completes, even the debug build operates fully offline. The
  privacy verification instructions in the README mention this caveat.
- We must provide an opt-out: a user who has the `.task` file from
  another source can drop it into a known directory and we use that
  copy without downloading.
