package com.voicenotemd.core.asr.di

import android.content.Context
import com.voicenotemd.core.asr.BatchSpeechToTextSession
import com.voicenotemd.core.asr.BatchTranscriber
import com.voicenotemd.core.asr.FakeBatchTranscriber
import com.voicenotemd.core.asr.SpeechToTextSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/**
 * Phase 1 of the whisper migration (ADR 0018): the app uses a BATCH session
 * (capture-to-RAM, transcribe at stop) behind the same [SpeechToTextSession] seam. The
 * transcriber is a placeholder ([FakeBatchTranscriber]) so the record → transcribe →
 * structure flow can be validated before the native whisper.cpp engine arrives.
 *
 * Phase 2 swaps [provideBatchTranscriber] to return a whisper.cpp-backed transcriber — a
 * one-line change, with no impact on the session or the capture flow.
 *
 * The Vosk streaming path (`VoskSpeechToTextSession` / `FallbackSpeechToTextSession`,
 * `FileVoskModelProvider`) is kept in the module for reference and is simply not wired
 * while we validate the batch flow.
 */
@Module
@InstallIn(SingletonComponent::class)
object AsrModule {
    @Provides
    fun provideBatchTranscriber(): BatchTranscriber = FakeBatchTranscriber()

    /**
     * Factory-shaped (NOT singleton): each capture session is a fresh recorder + buffer.
     */
    @Provides
    fun provideSpeechToTextSession(
        @ApplicationContext context: Context,
        transcriber: BatchTranscriber,
    ): SpeechToTextSession = BatchSpeechToTextSession(context, transcriber)
}
