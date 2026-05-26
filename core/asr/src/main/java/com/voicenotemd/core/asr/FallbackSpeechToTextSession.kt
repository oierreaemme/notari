package com.voicenotemd.core.asr

import android.util.Log
import com.voicenotemd.core.common.domain.Language
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * Selects the Vosk continuous-streaming session when a model is available for the
 * dictation language, and falls back to [AndroidSpeechToTextSession] otherwise.
 *
 * This keeps the app fully working on devices that do not yet have a Vosk model on
 * disk (model delivery is tracked separately — ADR 0008 / ADR 0018), while routing
 * to the gap-free Vosk path wherever a model exists. The choice is made per
 * [start] call, because model availability is per-language.
 */
class FallbackSpeechToTextSession(
    private val primaryFactory: () -> SpeechToTextSession,
    private val fallbackFactory: () -> SpeechToTextSession,
    private val modelProvider: VoskModelProvider,
) : SpeechToTextSession {
    private val activeDelegate = MutableStateFlow<SpeechToTextSession?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val rmsDb: Flow<Float> =
        activeDelegate.flatMapLatest { it?.rmsDb ?: flowOf(0f) }

    override fun start(language: Language): Flow<TranscriptChunk> {
        val hasModel = modelProvider.hasModel(language)
        val delegate = if (hasModel) primaryFactory() else fallbackFactory()
        // Diagnostic (spike): which engine was chosen and why. No transcript/audio content
        // is logged — only the language and model-presence flag. Safe under the privacy guard.
        Log.i(
            TAG,
            "ASR engine=${if (hasModel) "VOSK" else "ANDROID(fallback)"} " +
                "language=${language.name} (${language.bcp47}) hasModel=$hasModel",
        )
        activeDelegate.value = delegate
        return delegate.start(language)
    }

    override suspend fun stop(): String {
        val delegate = activeDelegate.value
        val result = delegate?.stop().orEmpty()
        activeDelegate.value = null
        return result
    }

    private companion object {
        const val TAG = "AsrFallback"
    }
}
