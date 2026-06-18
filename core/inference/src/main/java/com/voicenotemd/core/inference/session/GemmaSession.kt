package com.voicenotemd.core.inference.session

/**
 * Thin abstraction over the on-device LLM session.
 *
 * The concrete MediaPipe-backed implementation lives in this same package; tests substitute
 * it with [FakeGemmaSession] so the structuring logic can be exercised without loading the
 * real model.
 */
interface GemmaSession {
    /**
     * Send [prompt] to the model, return the full response text once generation completes.
     *
     * Implementations MUST NOT throw on routine model failures (OOM, truncation) — they
     * should return whatever was produced (possibly empty), and let the caller decide on
     * retry/fallback. This keeps the use-case layer's error handling exhaustive.
     *
     * @throws GemmaUnavailableException if the model has not been loaded yet, or the
     *         download is incomplete. The caller is expected to surface a "preparing"
     *         state, not a crash.
     */
    suspend fun generate(prompt: String): String

    /**
     * @return `true` once the model is loaded and ready to serve [generate] calls.
     */
    fun isReady(): Boolean

    /**
     * Eagerly load the engine if it isn't already in memory. Idempotent: a no-op when
     * the engine is already warm (or no model file exists yet).
     *
     * Designed to be fire-and-forget from feature ViewModels' init blocks — the user
     * lands on the capture screen, this kicks off the ~1.5 GB engine load on a background
     * thread, and by the time they finish dictating the first call to [generate] hits a
     * warm engine instead of paying the cold-start latency. Suspends until the load
     * completes (or fails); callers typically don't await it.
     */
    suspend fun warmUp() {
        // Default implementation: a single throwaway call to the readiness check is a
        // safe no-op for stub/fake sessions used in tests. The real LiteRT-LM session
        // overrides this with an actual load.
        @Suppress("UNUSED_EXPRESSION")
        isReady()
    }

    /**
     * The backend the engine is currently running on. Returns [InferenceBackend.UNKNOWN]
     * before the engine has been loaded (or for stub sessions that don't track this).
     *
     * The structuring use case reads this AFTER [warmUp] to size per-pass timeouts:
     * CPU inference for a typical structured-note response is empirically 3-4× slower
     * than GPU (real-device 2026-05-17: 470-char note → 78s on Pixel CPU fallback vs
     * the expected ~20s on GPU). A fixed per-char budget cannot cover both paths; the
     * use case picks the right formula once it knows which path is live.
     */
    fun backend(): InferenceBackend = InferenceBackend.UNKNOWN

    /**
     * Release the loaded engine and its memory (~1.5 GB for Gemma 4 E2B). Idempotent;
     * safe to call when nothing is loaded. The next [generate]/[warmUp] reloads lazily.
     *
     * Exposed on the interface so process-lifecycle code (ADR 0028: the idle-release
     * observer in the Application) can unload deterministically without knowing the
     * concrete implementation. `onTrimMemory(TRIM_MEMORY_COMPLETE)` is documented as
     * never called on API 34+, so this is the primary memory-release path there.
     *
     * Default: no-op, for stub sessions that hold no engine.
     */
    fun release() {}
}

/**
 * Which compute path the on-device engine is using.
 *
 * `UNKNOWN` covers two distinct states: the engine has not been loaded yet (so we can't
 * report), and the implementation does not track backend at all (stubs, tests). Callers
 * sizing budgets should treat UNKNOWN as "assume the worst" (i.e. CPU-like budgets)
 * because falsely treating an unknown engine as fast risks the same timeout class that
 * triggered the 2026-05-17 incident.
 */
enum class InferenceBackend { GPU, CPU, UNKNOWN }

class GemmaUnavailableException(reason: String) : RuntimeException(reason)
