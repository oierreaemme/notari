package com.voicenotemd.core.asr

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.voicenotemd.core.common.domain.Language
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1 implementation backed by Android's built-in [SpeechRecognizer].
 *
 * Note carefully: SpeechRecognizer hides the audio buffer from us — it's owned and
 * scoped to the recognizer service. We do not allocate a byte/short buffer ourselves.
 * That means the privacy invariants from ADR 0002 reduce to:
 *
 * 1. Use `RecognizerIntent.EXTRA_PREFER_OFFLINE = true` so the recognizer does not silently
 *    fall back to a cloud transcription path.
 * 2. Never hold on to the recognizer past [stop]; we [destroy] it explicitly so the OS
 *    releases its native audio resources promptly.
 * 3. Never write the recognizer's intermediate results to disk (we don't, all flows in
 *    this class go to memory only).
 */
class AndroidSpeechToTextSession(
    private val context: Context,
) : SpeechToTextSession {
    private var recognizer: SpeechRecognizer? = null
    private var lastTranscript: String = ""

    /**
     * Flipped to true when [stop] is called (or the caller cancels the flow via
     * [awaitClose]). The continuous-listen handlers check this before scheduling
     * a `startListening` restart — so a user-initiated stop is honoured immediately,
     * no race against pending Handler callbacks.
     */
    private val stopRequested = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _rmsDb = MutableStateFlow(0f)
    override val rmsDb: Flow<Float> = _rmsDb.asStateFlow()

    override fun start(language: Language): Flow<TranscriptChunk> =
        callbackFlow {
            lastTranscript = ""
            stopRequested.set(false)
            val recognizer =
                SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
                    .also { this@AndroidSpeechToTextSession.recognizer = it }

            var accumulatedTranscript = ""

            val intent =
                android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    // Keep extra timeouts as hints, even if some OS versions ignore them
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
                    if (language != Language.Unknown) {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.recognizerLocale)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.recognizerLocale)
                    }
                }

            val listener =
                object : RecognitionListener {
                    override fun onPartialResults(partialResults: Bundle?) {
                        val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        val best = list.firstOrNull().orEmpty()
                        if (best.isNotEmpty()) {
                            val fullText = if (accumulatedTranscript.isEmpty()) best else "$accumulatedTranscript $best"
                            lastTranscript = fullText
                            trySend(TranscriptChunk(fullText, isFinal = false, detectedLanguage = language))
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        val best = list.firstOrNull().orEmpty()
                        if (best.isNotEmpty()) {
                            accumulatedTranscript =
                                if (accumulatedTranscript.isEmpty()) {
                                    best
                                } else {
                                    "$accumulatedTranscript $best"
                                }
                            lastTranscript = accumulatedTranscript
                            trySend(TranscriptChunk(accumulatedTranscript, isFinal = true, detectedLanguage = language))
                        }
                        // Continuous-listen mode: SpeechRecognizer fires `onResults` whenever it
                        // *thinks* the user has paused (which is overeager in real-world dictation,
                        // especially in Italian/Spanish where natural pauses are common). Auto-
                        // closing on `onResults` was making the recording terminate mid-sentence.
                        // Instead, we restart `startListening` to capture the next utterance segment.
                        // The user terminates the recording explicitly via the stop button — that
                        // sets [stopRequested] true, and the handler below skips the restart.
                        scheduleRestartIfRunning(intent)
                    }

                    override fun onError(error: Int) {
                        when (error) {
                            SpeechRecognizer.ERROR_CLIENT -> {
                                // Fired when we call stopListening()/destroy() ourselves. Ignore.
                            }
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            -> {
                                // User is thinking / pausing. Restart silently so the recording
                                // tolerates long natural gaps.
                                scheduleRestartIfRunning(intent)
                            }
                            else -> {
                                // Fatal error (mic unavailable, audio path broken, etc.). Close the
                                // flow so the ViewModel sees natural completion and falls back to
                                // "Nothing was captured" UX.
                                close()
                            }
                        }
                    }

                    override fun onReadyForSpeech(params: Bundle?) = Unit

                    override fun onBeginningOfSpeech() = Unit

                    override fun onRmsChanged(rmsdB: Float) {
                        _rmsDb.value = rmsdB
                    }

                    override fun onBufferReceived(buffer: ByteArray?) = Unit

                    override fun onEndOfSpeech() = Unit

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?,
                    ) = Unit
                }

            recognizer.setRecognitionListener(listener)
            recognizer.startListening(intent)

            awaitClose {
                stopRequested.set(true)
                mainHandler.removeCallbacksAndMessages(null)
                _rmsDb.value = 0f
                try {
                    recognizer.stopListening()
                } catch (_: Exception) {
                }
                try {
                    recognizer.destroy()
                } catch (_: Exception) {
                }
                this@AndroidSpeechToTextSession.recognizer = null
            }
        }

    /**
     * Restart `startListening` on the main thread after a tiny delay, but ONLY if the
     * user hasn't requested stop in the meantime. The 50ms delay is the empirical
     * minimum that keeps the platform recognizer service happy between sessions —
     * calling restart synchronously from inside a callback occasionally throws
     * IllegalStateException on some OEMs.
     */
    private fun scheduleRestartIfRunning(intent: android.content.Intent) {
        if (stopRequested.get()) return
        mainHandler.postDelayed({
            if (stopRequested.get()) return@postDelayed
            val r = this@AndroidSpeechToTextSession.recognizer ?: return@postDelayed
            try {
                r.startListening(intent)
            } catch (_: Exception) {
            }
        }, 50L)
    }

    override suspend fun stop(): String {
        stopRequested.set(true)
        mainHandler.removeCallbacksAndMessages(null)
        _rmsDb.value = 0f
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        // The current SpeechRecognizer impl does NOT expose a buffer for us to zero. The
        // OS holds it; destroying the recognizer is the strongest deletion signal we have.
        return lastTranscript.also { lastTranscript = "" }
    }
}

/**
 * Test-only helper: replays a fixed transcript chunk-by-chunk without touching the
 * platform recognizer. Used by feature tests and prompt-eval suites.
 */
class FakeSpeechToTextSession(private val script: List<TranscriptChunk>) : SpeechToTextSession {
    override val rmsDb: Flow<Float> = kotlinx.coroutines.flow.flowOf(0f)

    override fun start(language: Language): Flow<TranscriptChunk> =
        kotlinx.coroutines.flow.flow {
            script.forEach { emit(it) }
        }

    override suspend fun stop(): String = script.lastOrNull { it.isFinal }?.text.orEmpty()
}
