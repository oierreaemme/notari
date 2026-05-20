# 9. Engine Lifecycle

Date: 2026-05-11
Status: Accepted (partially superseded by [ADR 0016](0016-engine-load-inference-split.md) on 2026-05-17 — see Amendments)

## Context
The LiteRT-LM Engine powering Gemma 4 E2B is an expensive native dependency. When instantiated, it allocates ~1.5 GB of RAM. The app runs on memory-constrained mobile devices (target: 4 GB RAM). Leaving this engine allocated permanently while the app sits in the background causes severe memory pressure on the OS, increasing the probability of our process being killed without warning (OOM). However, eager reallocation on every UI hide/show would destroy the user experience (reloading the weights takes time).

## Decision
We tie the `LiteRtLmGemmaSession` native lifecycle to the Android OS memory pressure signals via `ComponentCallbacks2.onTrimMemory`.

We will release the engine explicitly when `level >= TRIM_MEMORY_BACKGROUND`. 
We **do not** release the engine on `TRIM_MEMORY_UI_HIDDEN`, because entering the Recents screen or switching briefly to a browser should not evict the 1.5 GB model if the OS isn't actually starving for RAM.

Additionally, to prevent memory leaks during concurrent initializations, `ensureEngineLoaded()` uses a compare-and-set (CAS) approach and immediately calls `close()` on the redundant engine if it loses the race.

## Consequences and Trade-offs
- The app becomes a good citizen in the Android ecosystem.
- Returning to the app after a long background session under memory pressure will incur a "cold start" inference penalty (reloading the engine), which is an acceptable trade-off to prevent random OOM crashes.
- **MaxTokens Limitation**: As of `litertlm-android:0.11.0` (bytecode verified on 2026-05-11), the API does not expose a `maxTokens` configuration for generation. We rely on the intrinsic context limit of the `.litertlm` model. To prevent runaway generation from blocking the UI indefinitely and consuming concurrent resources, we wrap the inference calls in `StructureNoteUseCaseImpl` with two defensive timeouts (`withTimeoutOrNull`):
  - **Pass 1 (cold-start budget): 30 seconds.** The first `generate()` call after process boot has to mmap/load ~1.5 GB of weights and run `engine.initialize()` before generation begins. Empirically: Pixel 6a (Tensor G1) takes 10–15s for this, Pixel 7+ takes 4–8s. Giving 30s prevents the user's first dictation from falling back to plain text for purely mechanical reasons.
  - **Pass 2 (warm inference budget): 10 seconds.** If Pass 1 returned with parse failure, Pass 2 runs the stricter prompt against a now-warm engine — pure inference, no load.

  If Pass 1 times out (rare, only on extreme cold-start), we short-circuit directly to the `plainTextFallback` without attempting Pass 2 — that protects against concurrent `Conversation` access on the still-busy engine (the previous Pass 1 native call may still be running in C++ even after the Kotlin coroutine cancels). Worst-case structuring latency is therefore 30s (cold) or ~40s (cold + warm retry on parse failure). Future iterations should migrate to `engine.generateStreaming(...)` with manual token counting when the API stabilizes, OR pre-warm the engine at app launch via `ProcessLifecycleOwner` to make Pass 1 always a warm call.

## Amendments (2026-05-17)

The original numbers in this ADR (Pass 1 cold-start budget = 30s, then bumped to 55s through v6/v7/v8 prompt growth) and the `TRIM_MEMORY_BACKGROUND` release threshold were both revised by [ADR 0016](0016-engine-load-inference-split.md) after a real-device incident in which a 278-character note timed out at exactly the conflated cold budget. ADR 0016 splits engine load out of the inference timeout, serializes concurrent loads with a `Mutex` (replacing the CAS-then-close pattern described above), and raises the trim threshold to `TRIM_MEMORY_COMPLETE`. The CAS-then-close detail and the cold/warm budget numbers in this ADR are therefore historical; read ADR 0016 for the current behavior.
