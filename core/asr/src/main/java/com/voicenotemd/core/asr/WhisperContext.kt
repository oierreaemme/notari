package com.voicenotemd.core.asr

/**
 * Thin Kotlin wrapper over the whisper.cpp JNI bridge (`libwhisper_jni`). One instance owns
 * one native `whisper_context`. Not thread-safe — callers serialise access (we run one
 * transcription at a time). See ADR 0018 phase 2.
 */
class WhisperContext private constructor(
    private var nativePtr: Long,
) {
    /**
     * Transcribe [audio] (32-bit float PCM, mono, 16 kHz, normalised to [-1, 1]).
     * [languageBcp47] is "it"/"en"/… to pin the language, or "auto" to let whisper detect.
     */
    fun transcribe(
        audio: FloatArray,
        languageBcp47: String,
        threads: Int,
    ): String {
        if (nativePtr == 0L) return ""
        return nativeTranscribe(nativePtr, audio, threads, languageBcp47)
    }

    /** Free the native context. Idempotent. */
    fun release() {
        if (nativePtr != 0L) {
            nativeFree(nativePtr)
            nativePtr = 0L
        }
    }

    private external fun nativeTranscribe(
        ptr: Long,
        audio: FloatArray,
        threads: Int,
        language: String,
    ): String

    private external fun nativeFree(ptr: Long)

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }

        /** Load a ggml model from [path]; returns null if native init fails. */
        fun fromFile(path: String): WhisperContext? {
            val ptr = nativeInitFromFile(path)
            return if (ptr != 0L) WhisperContext(ptr) else null
        }

        @JvmStatic
        private external fun nativeInitFromFile(path: String): Long
    }
}
