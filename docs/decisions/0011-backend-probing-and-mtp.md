# 11. Backend probing (GPU → CPU) + MTP speculative decoding

Date: 2026-05-14
Status: Accepted

## Context

Initial implementation (per ADR 0008) hardcoded `Backend.CPU()` in
`LiteRtLmGemmaSession`. This was the safe default while we verified that the
LiteRT-LM Kotlin API was correctly wired end-to-end. Once the pipeline was
working we measured real latency on the reference device (Pixel 6a, Tensor G1):

| Transcript length | Time on `Backend.CPU()` |
|---|---|
| 200 chars (short note) | 15-20 seconds |
| 500 chars (medium) | 25-30 seconds |
| 1000 chars (long) | 50-60 seconds |
| 2000 chars (very long) | 95-100 seconds |

Most of this is **prefill** — Gemma 4 E2B in INT4 on the Tensor G1 CPU runs at
~30-50 tokens/second of prefill. A 1000-char transcript plus the ~3000-char v4
prompt is ~1000 input tokens, ~30 seconds of prefill before generation begins.

This was usable but not snappy enough to make the demo feel responsive. Two
LiteRT-LM features we hadn't enabled were potentially significant:

1. **`Backend.GPU()`**. Google's published benchmarks for Gemma 4 E2B on
   `litertlm-android` show 2-4× decode speedup on Mali GPUs. If it works on the
   user's device, that drops 1000-char-note structuring to ~15-25 seconds.

2. **Multi-Token Prediction (MTP) speculative decoding**. The `gemma-4-E2B-it-litert-lm`
   file on Hugging Face was re-published 2026-05-05 with MTP drafter heads
   embedded. Enabling them via `ExperimentalFlags.enableSpeculativeDecoding = true`
   gives an additional 2-3× decode speedup on GPU and ~25% on CPU.

Both have failure modes:
- GPU init can fail at engine creation time on some OEM driver / kernel combos.
  See LiteRT-LM Issues #1860 (Pixel 8 Pro silent failure) and #2114 (Galaxy S26
  Exynos Clspv error mixing global/constant address spaces). Pixel 6a (Mali-G78)
  also hits this on LiteRT-LM 0.11 — `Failed to create engine: INTERNAL: ERROR:
  [llm_litert_compiled_model_executor.cc:1928]`.
- MTP requires the `@ExperimentalApi` annotation on the LiteRT-LM Kotlin
  bindings; the field/setter signature can shift between patch releases.

## Decision

Probe both at engine creation time, with graceful runtime fallback for each
independently. Concretely, in `LiteRtLmGemmaSession.engineFactory`:

```kotlin
// 1. Enable MTP speculative decoding (wrapped — flag is experimental).
runCatching {
    ExperimentalFlags.enableSpeculativeDecoding = true
}.onFailure { Log.w(TAG, "MTP unavailable; engine will run without it", it) }

// 2. Try GPU; fall back to CPU on failure (driver mismatch, kernel compile fail).
runCatching {
    Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.GPU()))
        .also { it.initialize() }
}.recoverCatching {
    Log.w(TAG, "Backend.GPU init failed; falling back to Backend.CPU", it)
    Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.CPU()))
        .also { it.initialize() }
}.getOrThrow()
```

The whole file is annotated `@file:OptIn(ExperimentalApi::class)` so the
`ExperimentalFlags` reference doesn't require per-callsite opt-in.

Logging is deliberate: every engine init writes one line to logcat under tag
`VoiceNoteGemma` saying which backend it landed on (`Engine initialized on
Backend.GPU` vs `Engine initialized on Backend.CPU (GPU unavailable)`) and
whether MTP was enabled (`MTP speculative decoding enabled` vs `Could not enable
MTP speculative decoding (...)`). Running `adb logcat -s VoiceNoteGemma` tells
you in one line what the active inference path is on this device.

## Consequences

- **On devices where GPU init succeeds**, structuring drops from ~50-60s to
  ~15-25s for the same 1000-char input. The latency story for the demo becomes
  significantly stronger.

- **On devices where GPU fails** (including Pixel 6a as of LiteRT-LM 0.11), the
  app silently falls back to CPU. The user never sees a crash; the latency is
  the prior baseline.

- **MTP gives a modest CPU speedup** (~25%) on the fallback path. A 60s long
  note becomes ~45-48s. Not a game-changer but free.

- **Privacy invariant preserved**: GPU inference still runs entirely on-device.
  The pillar holds regardless of which backend wins the probe.

- **Honest UX**: the `StructuringPane` shows an elapsed-time counter and a
  per-transcript-length estimate, so the user understands that long notes take
  longer and short notes are quick. The estimate is a separate heuristic
  (~15s + 40ms per char, capped at 150s) and assumes CPU; it intentionally
  overstates slightly on GPU so the spinner feels like *almost done* instead of
  *we still have a long way to go*.

- **Pass timeout budget** scales with transcript length to match the linear
  prefill cost. Cold-start (first call after process boot): 30s + 50ms × len,
  capped at 150s. Warm: 8s + 50ms × len, capped at 150s. See ADR 0009 for the
  full timing model.

- **Pre-warming** in `CaptureViewModel.init` kicks off `session.warmUp()` in
  a fire-and-forget coroutine. By the time the user has tapped the mic and
  finished dictating, the engine is loaded (warm path), eliminating the
  ~10-15s cold-load tax from the user's perceived first-structuring time.

## Alternatives considered

- **Hardcode `Backend.GPU()` and crash on incompatible devices.** Rejected
  because the install base for an Android app spans many GPUs and driver
  versions; failing-closed makes the app unusable on a meaningful fraction
  of devices through no fault of the user.

- **Ship a device allowlist for GPU.** Rejected because the allowlist would
  drift behind reality (new devices, OEM driver updates) faster than we can
  maintain it. Runtime probing is the only correct strategy.

- **Skip MTP for v1.** Rejected because the cost is ~20 lines of code wrapped
  in two layers of `runCatching`, and even a 25% CPU speedup is worth
  ~10-15 seconds off the typical structuring time.

- **Use Qualcomm QNN / NPU paths** (Pixel 9 / Snapdragon 8 Gen 3+). Out of
  scope for v1 — requires a separate model build with NPU-compatible
  quantization, plus runtime detection logic. Tracked as a v2 feature in the
  DEV-post "What's next" section.

## Verification

Engine selection is visible via:

```bash
adb logcat -s VoiceNoteGemma
```

Reference run on Pixel 6a (Tensor G1, Mali-G78):

```
D VoiceNoteGemma: MTP speculative decoding enabled
W VoiceNoteGemma: Backend.GPU init failed (Failed to create engine: INTERNAL: ...)
D VoiceNoteGemma: Engine initialized on Backend.CPU (GPU unavailable)
D VoiceNoteGemma: Gemma response (448 chars): {...}
```

Cold path: ~33s for ~250-char input → 448-char output (test 1, Riflessione).
Same test before MTP was enabled: ~45-50s. Speedup confirmed.
