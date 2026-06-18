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
    // after transcription OR discard. Guarded by [capturedLock] — appended on the reader
    // thread, swapped out by stop()/discard().
    //
    // OWNERSHIP RULE (ADR 0027): consumers never operate on this field directly — they
    // atomically SWAP it for a fresh list under the lock ([takeCaptured]) and work on the
    // private snapshot. This makes a delayed stop()/discard() from a previous take unable
    // to touch the buffers of a session started afterwards (the cancel→restart data-loss
    // race found in the 2026-06-10 review).
    private val capturedLock = Any()
    private var captured = ArrayList<ShortArray>()

    // While false, the reader thread drops (and zeroes nothing — it never copied) any PCM
    // it reads. Flipped under [capturedLock] so a chunk can never be appended after a
    // snapshot was taken: stopCapture() clears it BEFORE joining the reader, so even a
    // reader that outlives the 500 ms join cannot leak un-zeroed copies into the list.
    private var accepting = false

    private var readerThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var btRouter: BluetoothAudioRouter? = null

    // Serializes [releaseCaptureResources]: AudioRecord is not thread-safe and the
    // awaitClose teardown thread can race a concurrent stop()/discard() to it.
    private val teardownLock = Any()

    private val _rmsDb = MutableStateFlow(0f)
    override val rmsDb: Flow<Float> = _rmsDb.asStateFlow()

    // Flips to `true` on the first non-silent PCM read of the current session. The UI watches
    // this to leave the "Preparazione…" warm-up state and show "Listening…" — see ADR 0020 /
    // CaptureViewModel. Reset to `false` at the start of every new session.
    private val _audioReady = MutableStateFlow(false)
    override val audioReady: Flow<Boolean> = _audioReady.asStateFlow()

    // Milliseconds of PCM captured so far (samples / 16 kHz). Drives the long-note
    // advisory in the capture UI — the transcript-length threshold is dead in batch
    // mode because there is no live transcript. Reset at the start of every session.
    private val _capturedDurationMs = MutableStateFlow(0L)
    override val capturedDurationMs: Flow<Long> = _capturedDurationMs.asStateFlow()

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by the app before capture starts.
    override fun start(language: Language): Flow<TranscriptChunk> =
        callbackFlow {
            stopRequested.set(false)
            captureStopped.set(false)
            _audioReady.value = false
            _capturedDurationMs.value = 0L
            activeLanguageBcp47 = if (language == Language.Unknown) "auto" else language.bcp47
            // Defensive: any leftover chunks from a session that was torn down without
            // stop()/discard() are zeroed before the references are dropped (ADR 0002 —
            // PCM must never linger in the heap un-overwritten).
            zeroAndDrop(takeCaptured())
            synchronized(capturedLock) { accepting = true }

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
                        var totalSamples = 0L
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
                            // Copy: the read buffer is reused on the next iteration. The
                            // append is gated on [accepting] under the same lock the
                            // consumers use, so a read that completes after stopCapture()
                            // can never add a chunk behind a taken snapshot.
                            synchronized(capturedLock) {
                                if (accepting) captured.add(readBuffer.copyOf(read))
                            }
                            totalSamples += read
                            _capturedDurationMs.value = totalSamples * 1000L / SAMPLE_RATE
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
                // Stop the reader promptly (cheap, safe on Main)…
                stopRequested.set(true)
                synchronized(capturedLock) { accepting = false }
                // …but do the heavy teardown (thread join up to 500 ms, AudioRecord
                // release, BT route clear) on a short-lived background thread: the
                // collector context is typically Main and joining there janks the UI.
                //
                // The teardown operates on THIS session's captured locals (`thread`,
                // `record`, `router`), never on the shared fields — a delayed close
                // can therefore never release the resources of a session started
                // afterwards (ADR 0027). Buffer zeroing is NOT done here: the
                // legitimate stop() path still needs the data after the flow is
                // cancelled; the VM-owned discard()/stop() calls are the zeroing points.
                Thread { releaseCaptureResources(thread, record, router) }
                    .apply { name = "asr-teardown" }
                    .start()
                _rmsDb.value = 0f
            }
        }

    override suspend fun stop(): String =
        withContext(Dispatchers.Default) {
            stopCapture()

            // Atomic swap: from here on this coroutine is the only owner of [snapshot];
            // a session started concurrently gets a fresh list and can't be affected.
            val snapshot = takeCaptured()
            val pcm =
                run {
                    val total = snapshot.sumOf { it.size }
                    val out = ShortArray(total)
                    var offset = 0
                    for (chunk in snapshot) {
                        chunk.copyInto(out, offset)
                        offset += chunk.size
                    }
                    out
                }

            // Idempotency guard: a second stop() (or a stop after discard) finds an empty
            // snapshot — return immediately instead of loading the ASR model for nothing.
            if (pcm.isEmpty()) return@withContext ""

            try {
                transcriber.transcribe(pcm, SAMPLE_RATE, activeLanguageBcp47)
            } finally {
                // Privacy: overwrite the captured audio in RAM as soon as it has been
                // transcribed — also on the exception path.
                zeroAndDrop(snapshot)
                pcm.fill(0)
            }
        }

    override suspend fun discard() {
        withContext(Dispatchers.Default) {
            stopCapture()
            // No transcription: the user abandoned the take. Zero + drop immediately —
            // discarded audio must never be processed nor linger in the heap (ADR 0002).
            zeroAndDrop(takeCaptured())
        }
    }

    /**
     * Atomically swap the captured-chunk list for a fresh one and return the snapshot.
     * Also stops accepting new chunks, so the snapshot is complete and final.
     */
    private fun takeCaptured(): List<ShortArray> =
        synchronized(capturedLock) {
            accepting = false
            val snapshot = captured
            captured = ArrayList()
            snapshot
        }

    /** Overwrite every chunk with zeros, then drop the references. */
    private fun zeroAndDrop(chunks: List<ShortArray>) {
        chunks.forEach { it.fill(0) }
    }

    /** Idempotent: stops capture, joins the reader thread, releases the recorder + BT route. */
    private fun stopCapture() {
        if (!captureStopped.compareAndSet(false, true)) return
        stopRequested.set(true)
        // Stop accepting BEFORE joining: even if the reader outlives the join timeout,
        // any PCM it reads afterwards is dropped instead of copied into the list.
        synchronized(capturedLock) { accepting = false }
        val thread = readerThread.also { readerThread = null }
        val record = audioRecord.also { audioRecord = null }
        val router = btRouter.also { btRouter = null }
        releaseCaptureResources(thread, record, router)
    }

    /**
     * Releases one capture's OS-side resources. Serialized via [teardownLock] because two
     * paths can race to it for the same objects (the `awaitClose` background thread and a
     * concurrent `stop()`/`discard()`), and `AudioRecord` is not thread-safe. Every call
     * is `runCatching`-wrapped, so double-release of an already-dead recorder is a no-op.
     */
    private fun releaseCaptureResources(
        thread: Thread?,
        record: AudioRecord?,
        router: BluetoothAudioRouter?,
    ) {
        synchronized(teardownLock) {
            runCatching { thread?.join(THREAD_JOIN_MS) }
            runCatching { record?.stop() }
            runCatching { record?.release() }
            runCatching { router?.clear() }
        }
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
