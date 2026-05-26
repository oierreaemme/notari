package com.voicenotemd.core.asr.di

import android.content.Context
import com.voicenotemd.core.asr.AndroidSpeechToTextSession
import com.voicenotemd.core.asr.FallbackSpeechToTextSession
import com.voicenotemd.core.asr.FileVoskModelProvider
import com.voicenotemd.core.asr.SpeechToTextSession
import com.voicenotemd.core.asr.VoskModelProvider
import com.voicenotemd.core.asr.VoskSpeechToTextSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AsrModule {
    /** Singleton: caches the loaded Vosk model across capture sessions. */
    @Provides
    @Singleton
    fun provideVoskModelProvider(
        @ApplicationContext context: Context,
    ): VoskModelProvider = FileVoskModelProvider(context)

    /**
     * Factory-shaped (NOT singleton): each capture session needs a fresh recognizer
     * because the underlying engines keep per-utterance state and are torn down on stop().
     *
     * Routes to the Vosk continuous-streaming session when a model exists for the
     * dictation language, otherwise to the SpeechRecognizer-backed fallback so devices
     * without a model keep working. See ADR 0018.
     */
    @Provides
    fun provideSpeechToTextSession(
        @ApplicationContext context: Context,
        modelProvider: VoskModelProvider,
    ): SpeechToTextSession =
        FallbackSpeechToTextSession(
            primaryFactory = { VoskSpeechToTextSession(context, modelProvider) },
            fallbackFactory = { AndroidSpeechToTextSession(context) },
            modelProvider = modelProvider,
        )
}
