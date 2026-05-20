package com.voicenotemd.core.asr

import com.voicenotemd.core.common.domain.Language
import kotlinx.coroutines.flow.Flow

/**
 * On-device speech-to-text session.
 *
 * v1 implementation: [AndroidSpeechToTextSession] wrapping [android.speech.SpeechRecognizer].
 * v2 (post-submission): a Gemma-audio-native variant. Both will satisfy this exact contract;
 * the seam is intentional (see ADR 0003).
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
     * A real-time stream of the audio RMS (Root Mean Square) level in decibels.
     * Exposed independently so UI can animate waveforms during [start].
     * Resets to 0.0f when idle.
     */
    val rmsDb: Flow<Float>
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
