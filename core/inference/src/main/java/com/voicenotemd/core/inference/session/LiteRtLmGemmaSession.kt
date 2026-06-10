@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package com.voicenotemd.core.inference.session

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import com.voicenotemd.core.common.dispatchers.AppDispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Result of a successful engine build. The factory returns this (rather than just
 * `Engine`) so the session can record which backend the engine ended up on — that
 * choice matters for downstream budget sizing (see ADR 0016 follow-up: CPU is
 * roughly 3-4× slower per token than GPU on the same prompt, so the structuring
 * use case picks different per-char budgets once it knows the path).
 */
data class EngineLoadResult(val engine: Engine, val backend: InferenceBackend)

/**
 * Google AI Edge LiteRT-LM-backed [GemmaSession]. Loads Gemma 4 E2B (`.litertlm`,
 * INT4 quantized) from the configured model file and exposes a single-shot [generate]
 * entry point.
 *
 * **Why LiteRT-LM and not MediaPipe Tasks GenAI?** Gemma 4 E2B is distributed in the
 * `.litertlm` format. The MediaPipe Tasks `.task` bundle path is the older runtime and
 * doesn't load `.litertlm` files. See ADR 0008 for the full reasoning.
 *
 * **Lifecycle:**
 * - The expensive engine ([Engine]) is created lazily on the first call. We don't
 *   load it eagerly because it allocates ~1.5 GB and the user might never need it
 *   (e.g. they bounced off the privacy info screen).
 * - [generate] runs on [AppDispatchers.default] so the inference can use a CPU-bound
 *   pool without starving the main thread.
 * - [release] tears the engine down — call from `Application.onTerminate` or when the
 *   process is going to background under memory pressure.
 *
 * **Privacy invariants** (see ADR 0002):
 * - This class makes no network calls. The model is loaded from a local file.
 * - The only string we send to the model is the rendered prompt, which is in turn
 *   composed by the use-case layer from a transcript that lived only in RAM.
 *
 * **API surface caveat:** the LiteRT-LM Kotlin/Java bindings (`com.google.ai.edge
 * .litertlm:litertlm`) are still being stabilized. The exact `Engine` constructor
 * signature may shift between minor releases — if a build fails on this file after a
 * dependency bump, the only required change is in [createEngine] / [runGenerate];
 * the rest of the app talks to [GemmaSession] which is stable.
 */
class LiteRtLmGemmaSession(
    private val context: Context,
    private val dispatchers: AppDispatchers,
    private val modelFileProvider: ModelFileProvider,
    private val engineFactory: (File) -> EngineLoadResult = { file ->
        // Try to enable Multi-Token Prediction speculative decoding before creating
        // the engine. MTP uses speculative drafter heads embedded in the .litertlm
        // file to predict multiple tokens per forward pass, giving 2-3× decode
        // speedup on GPU and 20-30% on CPU per Google's benchmarks. Requires:
        //  - litertlm-android 0.11+ (exposes ExperimentalFlags)
        //  - Model file re-downloaded from Hugging Face after 2026-05-05 (when the
        //    litert-community/gemma-4-E2B-it-litert-lm card was re-published with
        //    MTP heads embedded)
        // The set is wrapped in runCatching because ExperimentalFlags is an
        // @ExperimentalApi: even with @OptIn at the file level, the field/setter
        // signature can shift in patch releases. On failure we silently continue
        // without speculative decoding — the engine still works, just slower.
        runCatching {
            ExperimentalFlags.enableSpeculativeDecoding = true
            Log.d("VoiceNoteGemma", "MTP speculative decoding enabled")
        }.onFailure {
            Log.w(
                "VoiceNoteGemma",
                "Could not enable MTP speculative decoding (${it.message}); " +
                    "engine will run without it",
            )
        }

        // Try GPU first; fall back to CPU if GPU init fails. On Pixel 6a (Adreno 619)
        // and similar mid-tier GPUs, Backend.GPU gives 2-4× decode speedup (per Google's
        // LiteRT-LM benchmarks) which brings ~1000-char-note structuring from 50-60s on
        // CPU down to 15-25s. Known GPU init failure modes exist on some OEM devices
        // (see LiteRT-LM Issues #1860 Pixel 8 Pro, #2114 Galaxy S26 Exynos) — the
        // try/recover keeps us robust: if the GPU driver refuses to compile the
        // ML Drift kernels, we silently fall back to CPU and the app still works.
        runCatching {
            val gpuConfig =
                EngineConfig(
                    modelPath = file.absolutePath,
                    backend = Backend.GPU(),
                )
            val engine = Engine(gpuConfig)
            engine.initialize()
            Log.d("VoiceNoteGemma", "Engine initialized on Backend.GPU")
            EngineLoadResult(engine, InferenceBackend.GPU)
        }.recoverCatching { gpuError ->
            Log.w(
                "VoiceNoteGemma",
                "Backend.GPU init failed (${gpuError.message}); falling back to Backend.CPU",
                gpuError,
            )
            val cpuConfig =
                EngineConfig(
                    modelPath = file.absolutePath,
                    backend = Backend.CPU(),
                )
            val engine = Engine(cpuConfig)
            engine.initialize()
            Log.d("VoiceNoteGemma", "Engine initialized on Backend.CPU (GPU unavailable)")
            EngineLoadResult(engine, InferenceBackend.CPU)
        }.getOrThrow()
    },
) : GemmaSession, ComponentCallbacks2 {
    private val engineRef = AtomicReference<Engine?>(null)

    /**
     * Which backend the currently-loaded engine is on. Written under
     * [engineLoadMutex] alongside [engineRef], read lock-free by [backend].
     * `@Volatile` so a writer's update is visible to the next reader.
     */
    @Volatile
    private var loadedBackend: InferenceBackend = InferenceBackend.UNKNOWN

    /**
     * Serializes engine creation. Without this, two concurrent callers
     * (typically `warmUp()` fired from `LifecycleResumeEffect` and `generate()`
     * fired the moment the user hits stop) could each enter `engineFactory()`
     * and try to GPU-init the model at the same time. On real devices that
     * race caused one of the two to fall back to `Backend.CPU` mid-session,
     * pushing inference latency from ~20s to ~60s — exactly the 2026-05-17
     * incident pattern. With this mutex, the second caller awaits the first
     * one's published engine instead of building its own.
     *
     * AtomicReference is kept on top of the mutex for the fast path: an
     * already-published engine returns without acquiring the lock at all.
     */
    private val engineLoadMutex = Mutex()

    /**
     * Single-flights the actual inference so two native generations never run at once.
     *
     * Why this matters: `Conversation.sendMessage()` is a synchronous, blocking native
     * (C++) call. When the structuring use-case's `withTimeoutOrNull` fires, it cancels
     * the *Kotlin* coroutine, but the native thread keeps running to completion — Kotlin
     * cancellation does not propagate into LiteRT-LM. Before this guard, starting a second
     * capture while a timed-out inference was still spinning launched a SECOND native
     * inference concurrently; on the CPU fallback path the two saturated the cores,
     * causing thermal throttling and making *every* subsequent note time out too (the
     * recurring contention pattern, ADR 0016/0017). With this mutex the next generation
     * waits for the in-flight one to finish instead of piling on, so the device only ever
     * runs one inference at a time. A waiting caller is still cancellable while suspended,
     * so a use-case timeout on the waiter falls through to the plain-text fallback cleanly.
     */
    private val generationMutex = Mutex()

    // Singleton — unregisterComponentCallbacks non necessario, viviamo per la vita del processo
    init {
        context.applicationContext.registerComponentCallbacks(this)
    }

    override fun isReady(): Boolean = engineRef.get() != null || modelFileProvider.isAvailable()

    /**
     * Set by [release] when the engine must be torn down but a native generation is in
     * flight (the generation holds [generationMutex]). The generation's `finally` honors
     * the request once the native call returns — closing the engine *under* a running
     * `sendMessage()` is a native crash (the C++ side has no idea the Java peer died).
     */
    @Volatile
    private var closeRequested = false

    override suspend fun generate(prompt: String): String =
        withContext(dispatchers.default) {
            val engine = engineRef.get() ?: ensureEngineLoaded()
            // Serialize the native inference itself. Engine loading above is intentionally
            // outside this lock (it has its own engineLoadMutex), so a background warm-up
            // can still proceed while a generation is in flight.
            generationMutex.withLock {
                try {
                    runGenerate(engine, prompt)
                } finally {
                    // A release() (onTrimMemory / onTerminate) that arrived mid-generation
                    // deferred the close to us — do it now that the native call is done.
                    // Runs even when the calling coroutine was cancelled by a pass timeout
                    // (this generation is then a "zombie": the finally is its last word).
                    if (closeRequested) closeEngineNow()
                }
            }
        }

    /**
     * Pre-load the engine on a background dispatcher. Idempotent. Swallows
     * `GemmaUnavailableException` (no model file yet) — the capture flow will surface
     * that the normal way via `generate()` later.
     */
    override suspend fun warmUp() {
        if (engineRef.get() != null) return
        withContext(dispatchers.default) {
            try {
                ensureEngineLoaded()
            } catch (_: GemmaUnavailableException) {
                // No model imported yet — silent. The capture flow will hit the same
                // exception and surface the plain-text fallback at use time.
            }
        }
    }

    private suspend fun ensureEngineLoaded(): Engine {
        // Fast path: another caller already published one.
        engineRef.get()?.let { return it }

        return engineLoadMutex.withLock {
            // Re-check under the lock: we may have queued behind the first
            // loader, which has now published. Avoids building a second engine
            // just to throw it away.
            engineRef.get()?.let { return@withLock it }

            val file: File =
                modelFileProvider.fileOrNull()
                    ?: throw GemmaUnavailableException("Model file is not yet available on disk.")

            val loaded = engineFactory(file)
            // Publish backend FIRST so any reader that sees a non-null engineRef
            // also sees the correct backend (writes to engineRef happen-before
            // the volatile assignment ordering ensures this on JVM/ART).
            loadedBackend = loaded.backend
            engineRef.set(loaded.engine)
            loaded.engine
        }
    }

    override fun backend(): InferenceBackend = loadedBackend

    /**
     * Single-shot prompt → response. The structuring use-case never streams; it consumes
     * the full JSON response in one go before parsing. If we later add a streaming UI we
     * can switch to `engine.generateStreaming(...)` without touching the use-case layer.
     */
    private fun runGenerate(
        engine: Engine,
        prompt: String,
    ): String {
        val samplerConfig =
            SamplerConfig(
                topK = DEFAULT_TOP_K,
                topP = 1.0,
                temperature = DEFAULT_TEMPERATURE.toDouble(),
            )
        val conversation =
            engine.createConversation(
                ConversationConfig(
                    samplerConfig = samplerConfig,
                ),
            )
        return try {
            val response = conversation.sendMessage(prompt)
            val text =
                response.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
            // Logcat trace for the structuring pipeline. Strictly local — Logcat is on-device,
            // never leaves the phone. Lets `adb logcat -s VoiceNoteGemma` show what the model
            // is actually emitting when the parser falls back. The note body in the trace is
            // the same bytes that the on-screen "Last model response (debug)" card shows.
            //
            // Gated behind LOG_MODEL_RESPONSE (default OFF) because in debug builds — the
            // builds used daily on the reference device — the full note content would
            // otherwise sit in the logcat ring buffer (release strips Log.d via R8, ADR
            // 0021, so this only matters for debug). Flip the constant locally when
            // diagnosing parser fallbacks; the length-only line below stays as the
            // always-on breadcrumb.
            if (LOG_MODEL_RESPONSE) {
                Log.d(TAG, "Gemma response (${text.length} chars): $text")
            } else {
                Log.d(TAG, "Gemma response received (${text.length} chars)")
            }
            text
        } finally {
            conversation.close()
        }
    }

    /**
     * Release the engine. Safe to call at any moment: if a native generation is in
     * flight we must NOT close the engine under it (SIGSEGV risk — `Engine.close()`
     * concurrent with a blocking `sendMessage()` on another thread). In that case we
     * flag [closeRequested] and the generation's `finally` performs the close as soon
     * as the native call returns. When no generation is running, the close is immediate.
     */
    override fun release() {
        if (generationMutex.tryLock()) {
            try {
                closeEngineNow()
            } finally {
                generationMutex.unlock()
            }
        } else {
            closeRequested = true
        }
    }

    private fun closeEngineNow() {
        closeRequested = false
        engineRef.getAndSet(null)?.close()
        // Reset backend so the next reader sees UNKNOWN until a fresh load
        // re-publishes a real value. Otherwise a stale backend reading could
        // tell the use case "GPU" while the engine is actually unloaded —
        // safer to admit ignorance.
        loadedBackend = InferenceBackend.UNKNOWN
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        // Only release on truly critical pressure. The previous threshold
        // (`TRIM_MEMORY_BACKGROUND`) fires whenever the app is backgrounded —
        // which on real-device traces was every save→note-detail→back-to-capture
        // round trip (~1s of "background" time). Each release cost a 15-30s
        // reload on the next dictation and was the dominant cause of the
        // 2026-05-17 timeout incident. `TRIM_MEMORY_COMPLETE` is the
        // "system is killing background processes" signal — at that level the
        // OS is genuinely under pressure and dumping our 1.5 GB engine is the
        // right thing to do. Anything below that, we hold on to the engine
        // and accept the memory cost.
        //
        // We keep `release()` callable directly so the Application can still
        // unload the engine deterministically on `onTerminate()` if needed.
        //
        // KNOWN FOLLOW-UP: `TRIM_MEMORY_COMPLETE` is deprecated as of Android 14
        // (API 34) and is documented as "never called" on that API and above.
        // The replacement path is `ProcessLifecycleOwner` for true app-state
        // backgrounding plus `TRIM_MEMORY_RUNNING_CRITICAL` for foreground
        // memory pressure. On API 34+ today this method becomes effectively a
        // no-op, which means the engine survives across longer background
        // windows than the original design intended — acceptable for v1
        // (slightly less polite citizen, no functional break). Tracked in
        // ADR 0016 follow-ups.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            release()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}

    // ComponentCallbacks#onLowMemory is itself deprecated in favor of onTrimMemory;
    // we still have to override it because the interface contract requires it. No
    // logic here — onTrimMemory above is the live path.
    @Deprecated("Implemented only to satisfy ComponentCallbacks; logic lives in onTrimMemory.")
    override fun onLowMemory() {}

    private companion object {
        const val TAG = "VoiceNoteGemma"
        const val DEFAULT_TOP_K = 40
        const val DEFAULT_TEMPERATURE = 0.2f

        /**
         * When true, [runGenerate] logs the FULL model response body (note content!) at
         * Log.d. Privacy default is false so daily-driver debug builds don't keep note
         * text in the logcat ring buffer (review 2026-06-10 #7). Flip locally only while
         * diagnosing parser fallbacks; never commit `true`.
         */
        const val LOG_MODEL_RESPONSE = false
    }
}

/**
 * Locates the on-disk model file. Production binding lives in `:app/AppModule.kt` and
 * checks two locations in order (see CHANGELOG / ADR 0008):
 *
 * 1. `filesDir/models/gemma-4-e2b-it.litertlm` — where the SAF "Import model" flow
 *    drops the file. App-private, never world-readable.
 * 2. `getExternalFilesDir("models")/gemma-4-e2b-it.litertlm` — where `adb push`
 *    drops the file during local development.
 */
interface ModelFileProvider {
    fun isAvailable(): Boolean

    fun fileOrNull(): File?
}
