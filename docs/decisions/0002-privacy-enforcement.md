# ADR 0002 — Privacy enforcement (the cardinal rule)

- **Status:** Accepted (cannot be overridden by any later ADR)
- **Date:** 2026-05-09
- **Deciders:** Notari engineering

## Context

CLAUDE.md pillars 1, 2, and 6, plus the cardinal rule in section 14,
state that:

1. The app makes no network requests, ever.
2. Audio is held in RAM only and never persisted to disk.
3. The user must be able to verify both of the above without trusting us.

Marketing apps make the same promise. We make it true and prove it.

## Decision

We enforce privacy at four layers:

### Manifest

- The merged `AndroidManifest.xml` MUST NOT contain
  `android.permission.INTERNET`.
- It MUST NOT contain `ACCESS_NETWORK_STATE`,
  `CHANGE_NETWORK_STATE`, or any networking-adjacent permission.
- `android:allowBackup="false"` is set, plus a strict
  `data_extraction_rules.xml` that excludes everything from cloud
  backup and device transfer (notes contain intimate content).

### CI gate

A workflow step in `.github/workflows/ci.yml` runs:

```bash
./gradlew :app:processReleaseManifest
grep -q 'android.permission.INTERNET' app/build/intermediates/merged_manifests/release/AndroidManifest.xml \
  && { echo "INTERNET permission detected — failing build"; exit 1; } || exit 0
```

If a transitive AAR adds `INTERNET`, the build fails until we either
remove the dependency or add a `<uses-permission ... tools:node="remove">`
override and document why.

### Audio lifecycle

`:core:asr` exposes audio only as a `Flow<TranscriptChunk>` — never as
a byte buffer that callers could persist. Internally, the recording
buffer is a single `ShortArray` zeroed via `Arrays.fill(0)` once the
transcription session ends. There is no `File.createTempFile` call
anywhere in the module; a unit test grep enforces this.

### Inference assets

The Gemma `.task` file ships either bundled (release) or downloaded
once via `DownloadManager` with explicit user consent (debug
default — useful so APKs stay under 100MB during dev). At runtime
the model file is read-only; we never call `URLConnection`,
`OkHttpClient`, `Retrofit`, or anything similar.

## Alternatives considered

- **Trust ourselves.** No CI gate, just code review.
  Rejected — humans miss things; the cost of one mistaken
  `INTERNET` permission is the entire product proposition.
- **Use a network security config that blocks all hosts.** That
  defends only `INTERNET`-permission-enabled apps that try to
  connect — it is a softer guarantee. We want the harder one:
  the OS itself doesn't allow networking.
- **Allow opt-in cloud sync as a future feature.** Rejected for
  v1. If we add it later it will be a separate, explicitly-flagged,
  user-initiated path with clear UI consent — never silent.

## Consequences

- Every dependency we pull in has to be vetted for transitive
  network permissions. This rules out: Firebase Crashlytics, any
  GMS-flavored library, most analytics SDKs, OkHttp/Retrofit.
- Crash reporting falls back to local on-device logs surfaced in a
  user-visible debug screen.
- When a user reports "the app crashed", they can copy the local
  log and email it themselves — privacy intact.
