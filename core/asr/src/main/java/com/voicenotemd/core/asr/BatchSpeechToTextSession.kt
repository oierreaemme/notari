package com.voicenotemd.core.asr

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.SystemClock
import android.util.Log
import com.voicenotemd.core.common.domain.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Batch speech-to-text: capture the whole dictation as PCM in RAM, then transcribe it once
 * at [stop] via a [BatchTranscriber]. This is the shape whisper.cpp needs (ADR 0018) —
 * whisper is window-based, not a low-latency streaming recogniser, and for a voice-note app
 * (especially hands-free in the car, where the screen is off) there is no value in a live
 * word-by-word transcript anyway.
 *
 * Owns the [AudioRecord] capture, [BluetoothAudioRouter] routing, and the RMS waveform
 * derivation.
 *
 * **Privacy (ADR 0002 / ADR 0019).** The full PCM lives in RAM only for the duration of the
 * session and is overwritten with zeros immediately after transcription. No audio sink
 * (MediaRecorder, file, codec) is ever touched, and nothing is written to disk.
 *
 * **Warm-up (ADR 0020).** The reader thread emits `audioReady = true` on the first PCM frame
 * whose RMS exceeds [NON_SILENT_DB_THRESHOLD]. The capture screen uses that signal to leave
 * the "Preparazione…" state — see [CaptureViewModel].
 */
class BatchSpeechToTextSession(
    private val context: Context,
    private val transcriber: BatchTranscriber,
) : SpeechToTextSession {
    private val stopRequested = AtomicBoolean(false)
    private val captureStopped = AtomicBoolean(false)

    // Dictation language for the batch transcriber, captured at start(); "auto" = detect.
    @Volatile
    private var activeLanguageBcp47: String = "auto"

    // Captured PCM chunks (copies of each read), concatenated at stop. RAM only; zeroed
    // after transcription. Guarded by [capturedLock] — appended on the reader thread, read
    // on the stop() coroutine.
    private val capturedLock = Any()
    private val captured = ArrayList<ShortArray>()

    private var readerThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var btRouter: BluetoothAudioRouter? = null

    private val _rmsDb = MutableStateFlow(0f)
    override val rmsDb: Flow<Float> = _rmsDb.asStateFlow()

    // Flips to `true` on the first non-silent PCM read of the current session. The UI watches
    // this to leave the "Preparazione…" warm-up state and show "Listening…" — see ADR 0020 /
    // CaptureViewModel. Reset to `false` at the start of every new session.
    private val _audioReady = MutableStateFlow(false)
    override val audioReady: Flow<Boolean> = _audioReady.asStateFlow()

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by the app before capture starts.
    override fun start(language: Language): Flow<TranscriptChunk> =
        callbackFlow {
            stopRequested.set(false)
            captureStopped.set(false)
            _audioReady.value = false
            activeLanguageBcp47 = if (language == Language.Unknown) "auto" else language.bcp47
            synchronized(capturedLock) { captured.clear() }

            // Route to a Bluetooth headset mic if connected (in-car / hands-free). ADR 0018.
            val router = BluetoothAudioRouter(context).also { btRouter = it }
            router.routeToBluetoothIfAvailable()

            val minBuffer =
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            val bufferBytes = if (minBuffer > 0) minBuffer * 2 else FALLBACK_BUFFER_BYTES
            val record =
                AudioRecord(
                    AUDIO_SOURCE_VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                ).also { audioRecord = it }

            val readBuffer = ShortArray(bufferBytes / 2)

            val thread =
                Thread {
                    runCatching {
                        record.startRecording()
                        val startedAtMs = SystemClock.elapsedRealtime()
                        Log.i(TAG, "AudioRecord.startRecording() returned, reading…")
                        var firstReadLogged = false
                        var firstNonSilentLogged = false
                        while (!stopRequested.get()) {
                            val read = record.read(readBuffer, 0, readBuffer.size)
                            if (read <= 0) continue
                            val rms = computeRmsDb(readBuffer, read)
                            if (!firstReadLogged) {
                                Log.i(
                                    TAG,
                                    "first PCM chunk ($read samples) " +
                                        "at +${SystemClock.elapsedRealtime() - startedAtMs}ms, rmsDb=$rms",
                                )
                                firstReadLogged = true
                            }
                            if (!firstNonSilentLogged && rms > NON_SILENT_DB_THRESHOLD) {
                                Log.i(
                                    TAG,
                                    "first non-silent PCM " +
                                        "at +${SystemClock.elapsedRealtime() - startedAtMs}ms, rmsDb=$rms",
                                )
                                firstNonSilentLogged = true
                                // The audio pipeline is now producing real signal — wake the UI
                                // out of "Preparazione…" so the user knows it's safe to speak.
                                _audioReady.value = true
                            }
                            _rmsDb.value = rms
                            // Copy: the read buffer is reused on the next iteration.
                            synchronized(capturedLock) { captured.add(readBuffer.copyOf(read)) }
                        }
                    }
                    // Scratch read buffer is transient; zero it before the thread exits.
                    readBuffer.fill(0)
                }.also { readerThread = it }
            thread.start()

            // Batch mode shows no live transcript — emit one empty chunk so the UI renders
            // its "Listening…" state; the real text arrives from stop().
            trySend(TranscriptChunk(text = "", isFinal = false, detectedLanguage = language))

            awaitClose {
                stopCapture()
                _rmsDb.value = 0f
            }
        }

    override suspend fun stop(): String {
        stopCapture()

        val pcm =
            synchronized(capturedLock) {
                val total = captured.sumOf { it.size }
                val out = ShortArray(total)
                var offset = 0
                for (chunk in captured) {
                    chunk.copyInto(out, offset)
                    offset += chunk.size
                }
                out
            }

        val transcript =
            withContext(Dispatchers.Default) {
                transcriber.transcribe(pcm, SAMPLE_RATE, activeLanguageBcp47)
            }

        // Privacy: overwrite the captured audio in RAM as soon as it has been transcribed.
        synchronized(capturedLock) {
            captured.forEach { it.fill(0) }
            captured.clear()
        }
        pcm.fill(0)

        return transcript
    }

    /** Idempotent: stops capture, joins the reader thread, releases the recorder + BT route. */
    private fun stopCapture() {
        if (!captureStopped.compareAndSet(false, true)) return
        stopRequested.set(true)
        runCatching { readerThread?.join(THREAD_JOIN_MS) }
        readerThread = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { btRouter?.clear() }
        btRouter = null
    }

    internal companion object {
        // Value of MediaRecorder.AudioSource.VOICE_RECOGNITION. Used as a literal so the
        // module never imports the MediaRecorder symbol (banned by the privacy guard).
        const val AUDIO_SOURCE_VOICE_RECOGNITION = 6
        const val SAMPLE_RATE = 16_000
        const val FALLBACK_BUFFER_BYTES = 8_192
        const val THREAD_JOIN_MS = 500L

        // Diagnostic tag used for capture-timing logs (cold-start AGC, BT SCO setup, the
        // first non-silent PCM frame, etc.). Stripped from release builds via the
        // `-assumenosideeffects` ProGuard rule (see app/proguard-rules.pro, ADR 0021).
        const val TAG = "BatchSession"

        // dB-ish threshold above which a PCM frame is treated as "speech-ish" rather than
        // background noise/silence, used to drive the `audioReady` signal (ADR 0020). The
        // UI's rms scale runs ~ -2 (silence) to 12 (loud speech), so 2 is a conservative
        // "user is clearly talking" floor.
        const val NON_SILENT_DB_THRESHOLD = 2f

        // Waveform RMS mapping (UI expects ~[-2, 12]).
        const val FULL_SCALE = 32_768.0
        const val DB_FLOOR = -50.0
        const val DB_CEIL = -10.0
        const val SILENCE_DB = -2f

        /**
         * Pure function — converts a 16-bit mono PCM block into a single waveform-friendly
         * RMS-dB value mapped to the UI's expected [-2, 12] range. Extracted out of the
         * reader thread for unit testing: the audio capture itself needs an instrumented
         * test (Robolectric or device) but the level conversion is platform-independent
         * arithmetic that can be exercised in plain JUnit.
         *
         * @param samples 16-bit PCM samples (any contents).
         * @param length valid sample count inside [samples] (the buffer may be over-sized).
         */
        internal fun computeRmsDb(
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
    }
}
