package com.voicenotemd.core.asr.di

import android.content.Context
import com.voicenotemd.core.asr.BatchSpeechToTextSession
import com.voicenotemd.core.asr.BatchTranscriber
import com.voicenotemd.core.asr.SpeechToTextSession
import com.voicenotemd.core.asr.WhisperBatchTranscriber
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/**
 * Wires the on-device ASR path (ADR 0018): a BATCH session
 * ([BatchSpeechToTextSession] — capture-to-RAM, transcribe at stop) behind the
 * [SpeechToTextSession] seam, backed by the native whisper.cpp [WhisperBatchTranscriber].
 *
 * The session and the transcriber are the seam: swapping the transcriber (e.g. to a
 * Gemma-audio-native variant) is a one-line change with no impact on the capture flow.
 */
@Module
@InstallIn(SingletonComponent::class)
object AsrModule {
    @Provides
    fun provideBatchTranscriber(
        @ApplicationContext context: Context,
    ): BatchTranscriber = WhisperBatchTranscriber(context)

    /**
     * Factory-shaped (NOT singleton): each capture session is a fresh recorder + buffer.
     */
    @Provides
    fun provideSpeechToTextSession(
        @ApplicationContext context: Context,
        transcriber: BatchTranscriber,
    ): SpeechToTextSession = BatchSpeechToTextSession(context, transcriber)
}
