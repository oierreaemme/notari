package com.voicenotemd.core.asr.di

import android.content.Context
import com.voicenotemd.core.asr.AndroidSpeechToTextSession
import com.voicenotemd.core.asr.SpeechToTextSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AsrModule {
    /**
     * Important: this is *factory*-shaped, NOT singleton. Each capture session needs a
     * fresh recognizer because [SpeechRecognizer] keeps internal state per utterance and
     * we explicitly destroy it on stop().
     */
    @Provides
    fun provideSpeechToTextSession(
        @ApplicationContext context: Context,
    ): SpeechToTextSession = AndroidSpeechToTextSession(context)
}
