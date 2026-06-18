# MTP-on-CPU spike — does speculative decoding help on the CPU backend?

Date: 2026-06-18
Status: Protocol — awaiting on-device numbers
Owner: Mario (on-device, Pixel 6a)

## Question

Today `LiteRtLmGemmaSession.engineFactory` sets
`ExperimentalFlags.enableSpeculativeDecoding = true` **unconditionally**, before
the backend is even chosen, so MTP stays on for the CPU fallback. Two signals
say that may be wrong on CPU:

1. A comparable on-device write-up reports MTP is **net-negative on CPU**: the
   drafter and target models run *sequentially* there (no parallel verify, which
   is what gives the GPU win), so the extra drafter pass is pure overhead.
2. Our own `StructureNoteUseCaseImpl` budget comment already says
   *"no MTP / MTP not engaging effectively on this device"* — i.e. the CPU
   latency model was fit assuming MTP does nothing.

Yet `CHANGELOG.md` claims *"~25% decode speedup on CPU"*. That claim is
unverified and contradicts both signals. This spike settles it with numbers.

**Hypothesis:** on the CPU backend, MTP-off is **equal to or faster than**
MTP-on. If confirmed, gate `enableSpeculativeDecoding` to the GPU path only.

## What to measure

Per structuring call, the engine now logs (length-only, privacy-safe):

```
Gemma generate: <elapsedMs>ms · backend=CPU · in=<promptChars>ch · out=<outChars>ch
```

`elapsedMs` = prefill + decode. Prefill depends on `in` (the ~8 KB static prompt
+ transcript, roughly constant across runs of the same probe). MTP only affects
**decode**, so the fair metric is **ms per output char** at a similar `out`:

```
ms_per_out_char = elapsedMs / out
```

Compare the mean `ms_per_out_char` between MTP-on and MTP-off on CPU.

## Conditions (2 required, 1 optional control)

| Run | Backend | MTP | Build flips |
|-----|---------|-----|-------------|
| **A** | CPU | **on**  | force CPU (below); leave MTP flag `= true` |
| **B** | CPU | **off** | force CPU (below); MTP flag `= false` |
| C (control) | GPU | on | no flips (default) — sanity-check MTP *does* help on GPU |

### Build flips (revert after the spike)

In `core/inference/.../session/LiteRtLmGemmaSession.kt`:

- **MTP off (run B):** change the line
  `ExperimentalFlags.enableSpeculativeDecoding = true` → `= false`.
- **Force CPU (runs A and B):** add as the **first line inside the GPU
  `runCatching { ... }` block** (just before `val gpuConfig = …`):
  ```kotlin
  error("spike: forcing CPU backend")
  ```
  This makes the GPU attempt throw, so `recoverCatching` builds the CPU engine;
  the timing line will then read `backend=CPU`.

Each run is a separate **clean** build + install:
```
./gradlew clean :app:installDebug
```

## Corpus & procedure

Use the existing probe script `docs/prompt-evaluations/v12-test-script.md`
(10 dictation probes; P1–P5 are the mandatory core). For each run A/B (and C):

1. Clean-build + install with that run's flips.
2. Confirm the engine landed on the intended backend — the first structuring of
   the session logs `Engine initialized on Backend.CPU` (or `.GPU`).
3. Dictate the same probe set, **2–3 passes** each, to average out thermal noise.
   Keep the phone off-charger and let it cool between runs so neither condition
   is throttled more than the other.
4. Capture the timing lines (PowerShell):
   ```
   adb logcat -c
   adb logcat -s VoiceNoteGemma | Select-String -Pattern "Gemma generate"
   ```
   Paste them into the results table below (or save to a file and we parse it).

## Decision rule (pre-agreed, before seeing numbers)

Let `Δ = (B_on_cpu − A_off_cpu) / A_off_cpu` on mean `ms_per_out_char`
(positive Δ = MTP-on is slower):

- **Δ ≥ −5%** (off is faster, or within noise) → **MTP gives no CPU benefit.**
  Apply the fix: gate `enableSpeculativeDecoding` to GPU-only. Correct the
  CHANGELOG (drop the "+25% CPU" claim).
- **Δ ≤ −15%** (on is clearly faster) → keep MTP on for CPU; the external
  "net-negative on CPU" claim doesn't generalize to our model/device. Replace the
  CHANGELOG's vague "~25%" with the measured number.
- **−15% < Δ < −5%** → marginal; keep on, but record the real figure and revisit
  if CPU latency work (R4) reopens it.

Run C must show MTP clearly helping on GPU; if it doesn't, the model file may
lack MTP drafter heads (re-check it's the post-2026-05-05 Hugging Face copy) and
the CPU result is moot until that's fixed.

## The fix, if CPU is confirmed no-benefit

Move the flag out of the unconditional preamble and set it per backend:

```kotlin
// GPU success path:
ExperimentalFlags.enableSpeculativeDecoding = true   // helps on GPU (parallel verify)
// CPU recover path, before building the CPU engine:
ExperimentalFlags.enableSpeculativeDecoding = false  // sequential on CPU → net overhead
```

Wrap each set in the existing `runCatching` pattern. Then re-run A/B once to
confirm the gated build matches the best measured condition. This becomes ADR
material only if it changes the budget model in `StructureNoteUseCaseImpl`.

## Results (fill in)

| Run | Backend | MTP | probe | pass | elapsedMs | out ch | ms/out-char |
|-----|---------|-----|-------|------|-----------|--------|-------------|
| A | CPU | on  | P1 | 1 |  |  |  |
| B | CPU | off | P1 | 1 |  |  |  |
| … |  |  |  |  |  |  |  |

**Means:** A (CPU/on) = … · B (CPU/off) = … · Δ = … → decision: …
