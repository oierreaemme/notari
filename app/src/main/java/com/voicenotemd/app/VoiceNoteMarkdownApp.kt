package com.voicenotemd.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.voicenotemd.core.inference.session.GemmaSession
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Process-wide entry point.
 *
 * No analytics SDK, no crash reporter, no remote config — by design. See
 * docs/decisions/0002-privacy-enforcement.md.
 */
@HiltAndroidApp
class VoiceNoteMarkdownApp : Application() {
    @Inject lateinit var gemmaSession: GemmaSession

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idleReleaseJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // Deterministic engine release on real backgrounding (ADR 0028).
        //
        // `onTrimMemory(TRIM_MEMORY_COMPLETE)` — the previous release trigger (ADR 0016
        // §3) — is documented as never called on API 34+, so on modern devices the
        // 1.5 GB engine survived in the background indefinitely. ProcessLifecycleOwner
        // gives the honest app-level signal: ON_STOP = the whole app left the
        // foreground (NOT fired for in-app navigation or configuration changes, so the
        // capture→detail→capture round trips that motivated ADR 0016 never schedule a
        // release). After IDLE_RELEASE_DELAY in the background we drop the engine; a
        // quick app switch (< the delay) cancels the timer and keeps it warm.
        //
        // release() is generation-safe (it defers the close if a native inference is
        // in flight) and the next warmUp()/generate() reloads lazily, so firing while
        // a background transcription→structuring pipeline is finishing costs at most
        // one reload — and the FGS keeps us foreground-ish during capture anyway.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    idleReleaseJob?.cancel()
                    idleReleaseJob =
                        appScope.launch {
                            delay(IDLE_RELEASE_DELAY_MS)
                            gemmaSession.release()
                        }
                }

                override fun onStart(owner: LifecycleOwner) {
                    idleReleaseJob?.cancel()
                    idleReleaseJob = null
                }
            },
        )
    }

    private companion object {
        /**
         * How long the app may sit fully backgrounded before the Gemma engine is
         * released. 5 minutes: long enough that an app switch / notification detour
         * keeps the engine warm (reload costs 15-30 s on the reference device),
         * short enough that we're not the process the OS kills first on 4 GB phones.
         */
        const val IDLE_RELEASE_DELAY_MS = 5 * 60 * 1000L
    }
}
