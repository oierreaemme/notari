package com.voicenotemd.core.asr

import com.voicenotemd.core.common.domain.Language
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * On-device speech-to-text session.
 *
 * Current implementation: [BatchSpeechToTextSession] — capture-to-RAM, transcribe at [stop]
 * via a [BatchTranscriber] (whisper.cpp), the shape whisper needs (ADR 0018). A future
 * Gemma-audio-native variant would satisfy this same contract; the seam is intentional
 * (see ADR 0003).
 *
 * **Audio buffer lifetime — non-negotiable, see ADR 0002:**
 *
 * - Audio frames are held only in RAM, ONLY for the duration of an active session.
 * - At [stop], the implementation MUST overwrite any internal byte buffer with zeros
 *   before releasing the reference. This is enforced by an in-module unit test.
 * - Implementations MUST NOT instantiate `MediaRecorder`, `File`, `FileOutputStream`,
 *   or any class that could persist audio to disk. A `forbiddenImports` Detekt rule
 *   plus a source-set grep test enforces this.
 * - The transcript flow is the ONLY observable output of the session.
 */
interface SpeechToTextSession {
    /**
     * Begin a new recording session. Hot-collected: a single subscriber receives chunks
     * as the recognizer emits them. The session ends when the caller invokes [stop] or
     * the upstream collector cancels.
     *
     * @param language the dictation language. If [Language.Unknown] the implementation
     *   uses the device default and tags chunks with whatever the recognizer reports.
     */
    fun start(language: Language): Flow<TranscriptChunk>

    /**
     * Stop recording. Idempotent: calling [stop] when no session is active is a no-op.
     *
     * Returns the final aggregated transcript. After this returns, the audio buffer has
     * been zeroed and dereferenced.
     */
    suspend fun stop(): String

    /**
     * Abandon the in-progress recording WITHOUT transcribing. Idempotent.
     *
     * Contract (ADR 0002 / ADR 0027): the implementation MUST stop capture, zero every
     * internal audio buffer, and drop the references before returning. This is the path
     * for user-initiated cancels and ViewModel teardown — unlike [stop] it never feeds
     * the captured PCM to the ASR engine, so discarded audio is never processed.
     *
     * Default: no-op, for implementations that hold no audio between chunks.
     */
    suspend fun discard() {}

    /**
     * A real-time stream of the audio RMS (Root Mean Square) level in decibels.
     * Exposed independently so UI can animate waveforms during [start].
     * Resets to 0.0f when idle.
     */
    val rmsDb: Flow<Float>

    /**
     * Emits `true` the moment the underlying audio path is producing usable PCM (i.e. the
     * first non-silent frame has been read), and stays `true` for the rest of the session.
     *
     * The capture screen uses this to differentiate a "Preparazione…" warm-up state from
     * the real "Listening…" state — on a Pixel 6a, AudioRecord needs ~700–1000 ms after
     * [start] before its AGC and audio pipeline have stabilised, and any speech inside
     * that window comes out as silence or unintelligible noise. Without this signal,
     * users who speak immediately after tapping the mic lose the first one or two words.
     *
     * **Default contract:** emit `true` immediately. Lightweight or stub implementations
     * (e.g. the Android `SpeechRecognizer` wrapper) can ignore this concern — only the
     * batch capture path needs the warm-up grace period.
     */
    val audioReady: Flow<Boolean>
        get() = flowOf(true)

    /**
     * Milliseconds of audio captured so far in the current session. Resets to 0 at
     * [start]. With batch ASR there is no live transcript, so duration is the only
     * honest "how long is this note going to be" signal the UI can show advisories on
     * (the transcript-length threshold went dead with the whisper migration — ADR 0018).
     *
     * Default: constant 0 for implementations that don't track it.
     */
    val capturedDurationMs: Flow<Long>
        get() = flowOf(0L)
}

/**
 * One chunk of partial or final transcript.
 *
 * - `text` is the full recognized text up to this point (recognizer-defined; usually
 *   accumulates across partials, then resets to the final).
 * - `isFinal = true` marks the recognizer's terminal result for the current utterance.
 * - `detectedLanguage` may be `null` while the recognizer hasn't decided yet.
 */
data class TranscriptChunk(
    val text: String,
    val isFinal: Boolean,
    val detectedLanguage: Language? = null,
)
