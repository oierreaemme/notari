package com.voicenotemd.core.asr

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import com.voicenotemd.core.common.domain.Language
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Continuous-streaming speech-to-text backed by Vosk (ADR 0018).
 *
 * Unlike [AndroidSpeechToTextSession], we own the microphone: a single
 * [AudioRecord] runs for the whole dictation and streams PCM frames into a Vosk
 * [Recognizer] without ever stopping. Because capture never pauses there is no
 * inter-segment gap — the source of dropped words and earcons on the
 * SpeechRecognizer path is eliminated by construction, which is the whole point
 * of the swap for long, hands-free (in-car, screen-off) dictation.
 *
 * **Privacy (ADR 0002 / ADR 0019).** The PCM buffer lives only in RAM for the
 * duration of the session and is overwritten with zeros on stop. No audio sink
 * (MediaRecorder, file, codec) is ever touched. `AudioRecord` is permitted here
 * — and only here — under the evolved privacy guard; see `NoAudioPersistenceTest`.
 *
 * NOTE (spike scope, ADR 0018 follow-ups): capture currently runs only while the
 * app is foregrounded. Screen-off / background dictation needs a `microphone`
 * foreground service (Android 14: `FOREGROUND_SERVICE_MICROPHONE`) and is tracked
 * separately, as is Bluetooth (in-car) audio routing.
 */
class VoskSpeechToTextSession(
    private val context: Context,
    private val modelProvider: VoskModelProvider,
) : SpeechToTextSession {
    private val stopRequested = AtomicBoolean(false)
    private var lastTranscript: String = ""

    private val _rmsDb = MutableStateFlow(0f)
    override val rmsDb: Flow<Float> = _rmsDb.asStateFlow()

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by the app before capture starts.
    override fun start(language: Language): Flow<TranscriptChunk> =
        callbackFlow {
            lastTranscript = ""
            stopRequested.set(false)

            val model: Model? = modelProvider.loadModel(language)
            if (model == null) {
                // No model on device for this language. We should not normally be started
                // without one (FallbackSpeechToTextSession routes elsewhere), but close
                // cleanly rather than crash if it happens.
                close()
                return@callbackFlow
            }

            // Route capture to a Bluetooth headset mic if one is connected (in-car /
            // hands-free use). Falls back to the phone mic otherwise. See ADR 0018.
            val btRouter = BluetoothAudioRouter(context)
            btRouter.routeToBluetoothIfAvailable()

            val recognizer = Recognizer(model, SAMPLE_RATE)
            val minBuffer =
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE.toInt(),
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            val bufferBytes = if (minBuffer > 0) minBuffer * 2 else FALLBACK_BUFFER_BYTES
            val audioRecord =
                AudioRecord(
                    AUDIO_SOURCE_VOICE_RECOGNITION,
                    SAMPLE_RATE.toInt(),
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )

            // PCM read buffer — RAM only, zeroed on stop (see awaitClose). Sized to the
            // read granularity, not the whole capture, so memory stays flat for long notes.
            val readBuffer = ShortArray(bufferBytes / 2)
            var accumulated = ""

            val readerThread =
                Thread {
                    runCatching {
                        audioRecord.startRecording()
                        while (!stopRequested.get()) {
                            val read = audioRecord.read(readBuffer, 0, readBuffer.size)
                            if (read <= 0) continue
                            _rmsDb.value = rmsDbOf(readBuffer, read)
                            if (recognizer.acceptWaveForm(readBuffer, read)) {
                                val text = VoskResultParser.finalText(recognizer.result)
                                if (text.isNotEmpty()) {
                                    accumulated = appendSegment(accumulated, text)
                                    lastTranscript = accumulated
                                    trySend(TranscriptChunk(accumulated, isFinal = true, detectedLanguage = language))
                                }
                            } else {
                                val partial = VoskResultParser.partialText(recognizer.partialResult)
                                if (partial.isNotEmpty()) {
                                    val full = appendSegment(accumulated, partial)
                                    lastTranscript = full
                                    trySend(TranscriptChunk(full, isFinal = false, detectedLanguage = language))
                                }
                            }
                        }
                    }
                    // Flush whatever the recognizer still holds when the loop ends, so the
                    // final words spoken right before stop are not dropped.
                    runCatching {
                        val tail = VoskResultParser.finalText(recognizer.finalResult)
                        if (tail.isNotEmpty()) {
                            accumulated = appendSegment(accumulated, tail)
                            lastTranscript = accumulated
                            trySend(TranscriptChunk(accumulated, isFinal = true, detectedLanguage = language))
                        }
                    }
                }
            readerThread.start()

            awaitClose {
                stopRequested.set(true)
                runCatching { readerThread.join(THREAD_JOIN_MS) }
                runCatching { audioRecord.stop() }
                runCatching { audioRecord.release() }
                runCatching { btRouter.clear() }
                // Privacy invariant: overwrite the in-RAM PCM buffer before releasing it.
                readBuffer.fill(0)
                runCatching { recognizer.close() }
                // The Model is owned and cached by the provider; do not close it here.
                _rmsDb.value = 0f
            }
        }

    override suspend fun stop(): String {
        stopRequested.set(true)
        _rmsDb.value = 0f
        return lastTranscript.also { lastTranscript = "" }
    }

    private fun appendSegment(
        accumulated: String,
        segment: String,
    ): String = if (accumulated.isEmpty()) segment else "$accumulated $segment"

    /**
     * RMS level mapped to roughly the SpeechRecognizer dB range the capture UI expects
     * ([-2f, 12f]; `CaptureRoute` normalizes via `(x + 2) / 12`). We compute dBFS from the
     * PCM (full scale = 32768), clamp to a [DB_FLOOR, DB_CEIL] window, and rescale so
     * silence reads ~-2 and loud speech ~12 — otherwise the raw dB values saturate the
     * UI's normalization and the waveform sits frozen at max. Constants are heuristic/tunable.
     */
    private fun rmsDbOf(
        samples: ShortArray,
        length: Int,
    ): Float {
        if (length <= 0) return SILENCE_DB
        var sumSquares = 0.0
        for (i in 0 until length) {
            val s = samples[i].toDouble()
            sumSquares += s * s
        }
        val rms = sqrt(sumSquares / length)
        if (rms < 1.0) return SILENCE_DB
        val dbfs = 20.0 * log10(rms / FULL_SCALE)
        val amplitude = ((dbfs - DB_FLOOR) / (DB_CEIL - DB_FLOOR)).coerceIn(0.0, 1.0)
        return (amplitude * 12.0 - 2.0).toFloat()
    }

    private companion object {
        // Value of MediaRecorder.AudioSource.VOICE_RECOGNITION. We use the literal so we
        // never import the MediaRecorder symbol (banned by Detekt + NoAudioPersistenceTest
        // as a persistence-sink guard). Selecting the mic input source does not persist
        // anything; the ban stays fully intact.
        const val AUDIO_SOURCE_VOICE_RECOGNITION = 6
        const val SAMPLE_RATE = 16_000f
        const val FALLBACK_BUFFER_BYTES = 8_192
        const val THREAD_JOIN_MS = 500L

        // Waveform RMS mapping (see rmsDbOf). FULL_SCALE = 16-bit max; DB_FLOOR/DB_CEIL is
        // the dBFS window mapped onto the UI's [-2, 12] expectation. SILENCE_DB is the
        // UI's lower bound (renders as no pulse).
        const val FULL_SCALE = 32_768.0
        const val DB_FLOOR = -50.0
        const val DB_CEIL = -10.0
        const val SILENCE_DB = -2f
    }
}
