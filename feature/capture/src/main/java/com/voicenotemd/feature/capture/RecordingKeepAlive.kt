package com.voicenotemd.feature.capture

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the capture pipeline alive while audio is being captured/transcribed —
 * concretely, the microphone foreground service ([RecordingForegroundService]).
 *
 * Why an abstraction: the keep-alive used to be driven by a `LaunchedEffect` in
 * `CaptureRoute`, which only works while the composable is in composition. If the user
 * navigated away mid-recording (capture keeps running by design — hands-free), the
 * effect was disposed and a capture that ended off-screen left the service (and its
 * "recording" notification) running until the user came back (review 2026-06-10 #10).
 * The ViewModel owns the phase state machine, so it owns the keep-alive too; this
 * interface keeps the VM free of Android service plumbing and unit-testable.
 */
interface RecordingKeepAlive {
    /** Idempotent. Called when capture becomes active (Preparing/Recording/Transcribing). */
    fun start()

    /** Idempotent. Called when capture ends, whatever the path (save, cancel, error, VM death). */
    fun stop()
}

/** Production binding: the microphone foreground service. */
class ServiceRecordingKeepAlive
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : RecordingKeepAlive {
        override fun start() = RecordingForegroundService.start(context)

        override fun stop() = RecordingForegroundService.stop(context)
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RecordingKeepAliveModule {
    @Binds
    @Singleton
    abstract fun bindRecordingKeepAlive(impl: ServiceRecordingKeepAlive): RecordingKeepAlive
}
