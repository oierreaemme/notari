# 21. Strip informational logs from the release build via R8

Date: 2026-05-28
Status: Accepted

## Context

The on-device spike work that landed in `feature/asr-whisper` left
behind a handful of `Log.i(TAG, …)` call sites in `:core:asr` — tags
`BatchSession`, `WhisperBatch`, `AsrBtRouter`, `AsrFallback`,
`VoskModel` — that are extremely useful during debug:

- `BatchSession` reports the AudioRecord cold-start timings: first
  PCM frame, first non-silent PCM frame, plus the ms offset from
  `startRecording()`. This is how ADR 0020 was validated on-device.
- `WhisperBatch` reports the model that was loaded, the sample count,
  the language pin, and the thread count used for inference.
- `AsrBtRouter` reports the routing decision (`BLUETOOTH` vs
  `phone-mic`) and the failure reason if any.

In a production APK these strings are noise: the user can't see
logcat, and shipping the string concatenation in the logging argument
list ("transcribing ${audio.size} samples lang=$language …") still
costs allocations on every recording even if nothing is being
collected, because `Log.i` is not a no-op.

We need the diagnostic surface to stay alive in `assembleDebug` (so
on-device troubleshooting keeps working) and disappear cleanly in
`assembleRelease`.

## Decision

Add a single `-assumenosideeffects` directive to
`app/proguard-rules.pro`:

```
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
```

R8 only runs on the release build (debug uses `minifyEnabled false`),
so the assumption only takes effect in release artifacts. The
"no side effects" promise allows R8 to remove the call sites
entirely — including, via dead-store elimination, the string
concatenation that built the log message in the first place. Net cost
in release: zero allocations, zero CPU, zero APK weight for the
removed code.

`Log.e` and `Log.w` are NOT stripped. A genuine production failure
(model fails to load, inference returns an empty string, whisper
exception, …) still leaves a logcat trace that a developer attached
via `adb` to a user device can read. This matches the CLAUDE.md
working agreement *"Never silence errors with empty try/catch"* —
the rule is about hiding errors, not chatty diagnostics.

## Consequences

**Positive**

- All five diagnostic tag groups stay verbatim in the source code
  and remain available in `debug` for on-device troubleshooting (the
  workflow we used to validate ADRs 0018 / 0020).
- Release APKs ship with zero diagnostic logging overhead — no
  string concatenation, no allocation, no logcat write.
- No conditional code, no `BuildConfig.DEBUG` checks scattered across
  the codebase, no risk of accidentally leaving a stray `if (DEBUG)`
  wrapper that masks a real issue.
- The decision is reversible per-build: drop a Log.e instead of Log.i
  to keep something in release.

**Neutral**

- A developer reading the source might wonder why so many `Log.i`
  calls exist if the app is "supposed to be production". This ADR is
  the answer; a one-line pointer in `app/proguard-rules.pro`
  comments points here as well.

**Negative**

- Forensic reproduction of a production bug becomes harder: a user
  who reports "the warm-up indicator stayed up for 3 seconds and then
  whisper produced nothing" can't paste `adb logcat | Select-String
  BatchSession` because release builds have none. They need to
  reproduce on a debug build, OR install `assembleDebug` from us.
  Accepted tradeoff — we're not at the support volume where this
  matters.

## Alternatives considered

**A `Logger` interface with a debug-only Hilt binding.**
Inject a real logger in debug, a no-op in release. Clean, but adds
plumbing for every log site and a per-call vtable lookup. The R8
rule achieves the same effect with zero code change and zero runtime
cost.

**Per-module `BuildConfig.DEBUG` checks.**
`buildFeatures.buildConfig = true` is disabled by default on AGP 8+;
turning it on in every module that logs is fine, but the result is
`if (BuildConfig.DEBUG) Log.i(...)` boilerplate that R8 also has to
strip. Same outcome, more code.

**Remove the logs entirely.**
Considered. Rejected because the diagnostic surface saved us multiple
sessions of on-device debugging (the AudioRecord warm-up timing in
particular was discovered exactly because of `BatchSession` logs).
Throwing them away in week one of "production" would be premature.

## Links

- `app/proguard-rules.pro` — the directive itself.
- ADR 0018 — Continuous-streaming ASR (whisper migration); source
  of the `BatchSession` / `WhisperBatch` / `AsrBtRouter` tags.
- ADR 0020 — `Phase.Preparing`, validated using these logs.
