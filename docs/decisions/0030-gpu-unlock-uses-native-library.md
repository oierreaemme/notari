# 30. GPU backend unlocked: the "LiteRT-LM GPU-init bug" was a missing uses-native-library declaration

Date: 2026-06-10
Status: Accepted (validated on-device same day)

## Context

Since the 2026-05-17 incidents, the project operated on the premise that
`Backend.GPU()` initialization fails on the reference Pixel because of an
internal LiteRT-LM error (`llm_litert_compiled_model_executor.cc:1928`,
attributed to the same family as upstream issues #1860/#2114). That premise
shaped a lot of engineering:

- the backend-aware budget model (ADR 0016 amendment): CPU budgets up to 250 s,
  60 s Pass 1 baseline, 150 ms/char;
- the CPU-fallback UX work (elapsed counter, long-note advisory, the one-time
  CPU advisory of ADR 0029);
- ultimately the 8 s-then-background structuring (ADR 0023) — motivated by
  CPU-path latencies of 60-90 s per note.

While investigating the remaining speed levers, upstream documentation and
issue #1860 surfaced the actual requirement: **on API 31+, an app may only
`dlopen` vendor native libraries it explicitly declares** via
`<uses-native-library>`. Notari's manifest declared none, so the loader
refused `libOpenCL.so`, ML Drift's OpenCL probe failed inside engine init, and
the session's try/recover correctly fell back to CPU — making a packaging
omission look exactly like a runtime bug. The misattribution was easy: the
failure message comes from deep inside the native executor, and the same
symptom does occur as a genuine runtime issue on other SoCs (Tensor G3 has no
OpenCL at all; Exynos/Xclipse fails in Clspv — #2114).

## Decision

Declare the two libraries in the app manifest, optional so devices without
them still install:

```xml
<uses-native-library android:name="libvndksupport.so" android:required="false" />
<uses-native-library android:name="libOpenCL.so" android:required="false" />
```

No privacy impact: the declaration only permits loading an on-device GPU
driver library; it grants no permission and opens no I/O channel. The
no-INTERNET CI gate is unaffected.

## Validation — 2026-06-10, on-device

Clean install on the reference Pixel; logcat (`adb logcat -s VoiceNoteGemma`):

```
13:58:57.249  D VoiceNoteGemma: Engine initialized on Backend.GPU
13:59:13.945  D VoiceNoteGemma: Gemma response received (111 chars)
14:00:51.786  D VoiceNoteGemma: Gemma response received (240 chars)
```

First note structured ~16 s after engine init (includes first-note prefill);
subsequent notes well inside the warm-GPU envelope. User-reported experience:
"tutto molto più veloce". The length-only log lines also confirm the
LOG_MODEL_RESPONSE privacy gate is active in the daily build.

## What this does NOT change

- **CPU budgets stay** (ADR 0016 amendment): UNKNOWN/CPU paths are still real
  on devices genuinely without OpenCL (Tensor G3) or with broken CL compilers
  (Exynos #2114). The budget model now simply applies to the devices it was
  meant for instead of to every Pixel.
- **The CPU advisory stays** (ADR 0029 §1) — same reasoning.
- **ADR 0023's async structuring stays**: it was motivated by CPU latency but
  is the right architecture regardless; on GPU most notes now resolve inside
  the 8 s quick wait, so the synchronous review flow is back to being the
  common case, with the background path as the safety net.
- **Prompt v12 (prefill slim) stays**: GPU makes prefill cheap, but the slim
  prompt still helps cold capture and CPU-only devices.

## Lesson for the record

When a native-stack init failure on Android "matches a known upstream bug",
check the packaging requirements first: `uses-native-library` (API 31+),
extraction/page-size flags, and ABI filters can all reproduce "runtime bug"
symptoms. A 30-minute documentation pass would have saved three weeks of
budget engineering calibrated on a misdiagnosis — though that engineering
(backend-aware budgets, async structuring) turned out to be worth keeping on
its own merits.

## References

- LiteRT-LM Kotlin getting started (uses-native-library requirement):
  github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md
- Upstream issue #1860 (Backend.GPU on Pixel, requirement confirmed in thread)
- ADR 0016 (amended with a forward link to this ADR), ADR 0023, ADR 0029
