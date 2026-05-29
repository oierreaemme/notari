package com.voicenotemd.core.asr

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
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
            // The best partial of the CURRENT segment. SpeechRecognizer only delivers a
            // committed `onResults` when it cleanly detects end-of-speech; on a NO_MATCH,
            // timeout, or transient error (BUSY/NETWORK/SERVER) it ends the segment WITHOUT
            // an `onResults`, and the words it had already recognized would be lost. We keep
            // the latest partial here and flush it before restarting so no speech is dropped.
            var currentPartial = ""

            // Append [currentPartial] to the running transcript and emit it, then clear it.
            // Called on any segment end that did NOT go through `onResults` (which commits
            // and clears `currentPartial` itself, so this is a no-op in the normal path).
            fun commitPendingPartial() {
                val pending = currentPartial.trim()
                if (pending.isEmpty()) return
                accumulatedTranscript =
                    if (accumulatedTranscript.isEmpty()) pending else "$accumulatedTranscript $pending"
                lastTranscript = accumulatedTranscript
                currentPartial = ""
                trySend(TranscriptChunk(accumulatedTranscript, isFinal = true, detectedLanguage = language))
            }

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
                            // Remember this segment's partial so it can be salvaged if the
                            // segment ends without a committed `onResults`.
                            currentPartial = best
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
                            // This segment committed cleanly — nothing left to salvage.
                            currentPartial = ""
                        } else {
                            // Empty final but we may have buffered a partial for this segment
                            // (some OEMs deliver text only via onPartialResults). Don't drop it.
                            commitPendingPartial()
                        }
                        // Continuous-listen mode: SpeechRecognizer fires `onResults` whenever it
                        // *thinks* the user has paused (which is overeager in real-world dictation,
                        // especially in Italian/Spanish where natural pauses are common). Auto-
                        // closing on `onResults` was making the recording terminate mid-sentence.
                        // Instead, we restart `startListening` to capture the next utterance segment.
                        // The user terminates the recording explicitly via the stop button — that
                        // sets [stopRequested] true, and the handler below skips the restart.
                        scheduleRestartIfRunning(intent, RESTART_DELAY_MS)
                    }

                    override fun onError(error: Int) {
                        when (error) {
                            SpeechRecognizer.ERROR_CLIENT -> {
                                // Fired when we call stopListening()/destroy() ourselves. Ignore.
                            }
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            -> {
                                // User is thinking / pausing. Salvage whatever this segment
                                // recognized, then restart so the recording tolerates long
                                // natural gaps without dropping the words spoken so far.
                                commitPendingPartial()
                                scheduleRestartIfRunning(intent, RESTART_DELAY_MS)
                            }
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                            SpeechRecognizer.ERROR_SERVER,
                            -> {
                                // Transient: the recognizer service was still tearing down the
                                // previous segment when we asked it to restart (the "stops then
                                // restarts" symptom). Do NOT close the recording. Salvage the
                                // partial and retry after a longer backoff; the restart helper
                                // calls cancel() first to reset the service state.
                                commitPendingPartial()
                                scheduleRestartIfRunning(intent, BUSY_RESTART_DELAY_MS)
                            }
                            else -> {
                                // Genuinely fatal (ERROR_AUDIO, ERROR_INSUFFICIENT_PERMISSIONS,
                                // ERROR_LANGUAGE_*). Salvage anything captured, then close the
                                // flow so the ViewModel sees natural completion.
                                commitPendingPartial()
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
                // Teardown failures are non-fatal (the flow is closing regardless) but must
                // not be silenced — CLAUDE.md §15. Log.w survives R8 in release (ADR 0021
                // only strips v/d/i), so a recognizer that misbehaves on teardown is visible
                // in logcat without affecting the user.
                try {
                    recognizer.stopListening()
                } catch (e: Exception) {
                    Log.w(TAG, "stopListening() failed during flow teardown", e)
                }
                try {
                    recognizer.destroy()
                } catch (e: Exception) {
                    Log.w(TAG, "destroy() failed during flow teardown", e)
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
    private fun scheduleRestartIfRunning(
        intent: android.content.Intent,
        delayMs: Long,
    ) {
        if (stopRequested.get()) return
        mainHandler.postDelayed({
            if (stopRequested.get()) return@postDelayed
            val r = this@AndroidSpeechToTextSession.recognizer ?: return@postDelayed
            try {
                // cancel() first resets the recognizer service to a clean idle state. Without
                // it, a startListening() that lands while the previous segment is still tearing
                // down throws/reports ERROR_RECOGNIZER_BUSY, which previously killed the session.
                r.cancel()
                r.startListening(intent)
            } catch (e: Exception) {
                // A failed restart means this segment is lost; the next error/result callback
                // will schedule another attempt. Surface it so a recognizer stuck in a restart
                // loop is diagnosable rather than silently dropping audio.
                Log.w(TAG, "recognizer restart (cancel + startListening) failed", e)
            }
        }, delayMs)
    }

    private companion object {
        const val TAG = "AsrFallback"

        // Normal gap between utterance segments — the empirical minimum that keeps the
        // platform recognizer happy when restarting back-to-back.
        const val RESTART_DELAY_MS = 50L

        // Longer backoff after a transient error (BUSY/NETWORK/SERVER) so the recognizer
        // service has time to finish releasing the previous session before we reuse it.
        const val BUSY_RESTART_DELAY_MS = 300L
    }

    override suspend fun stop(): String {
        stopRequested.set(true)
        mainHandler.removeCallbacksAndMessages(null)
        _rmsDb.value = 0f
        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "stopListening() failed in stop()", e)
        }
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy() failed in stop()", e)
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
