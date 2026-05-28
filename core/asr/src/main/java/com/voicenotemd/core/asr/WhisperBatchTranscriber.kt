package com.voicenotemd.core.asr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * whisper.cpp-backed batch transcriber (ADR 0018 phase 2). Loads a ggml model from device
 * storage, transcribes the whole PCM buffer in one shot, and frees the model immediately
 * after so its ~150 MB does not sit in RAM while Gemma structures the note.
 *
 * Model location: `<files>/whisper/ggml-base.bin`. The external files dir is checked first
 * (so the model can be `adb push`ed during the spike), then internal. Real model delivery
 * is the open follow-up shared with ADR 0008.
 */
class WhisperBatchTranscriber(
    private val context: Context,
) : BatchTranscriber {
    override suspend fun transcribe(
        pcm: ShortArray,
        sampleRate: Int,
        languageBcp47: String,
    ): String =
        withContext(Dispatchers.Default) {
            val modelPath = resolveModelPath()
            if (modelPath == null) {
                Log.e(TAG, "no whisper model found under any of: ${candidateDirs().joinToString()}")
                return@withContext ""
            }
            val whisper = WhisperContext.fromFile(modelPath)
            if (whisper == null) {
                Log.e(TAG, "whisper failed to load model at $modelPath")
                return@withContext ""
            }
            try {
                // 16-bit PCM → normalised float, as whisper expects.
                val audio = FloatArray(pcm.size) { pcm[it] / PCM_FULL_SCALE }
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                val language = languageBcp47.ifBlank { "auto" }
                Log.i(TAG, "transcribing ${audio.size} samples lang=$language threads=$threads")
                whisper.transcribe(audio, language, threads).trim()
            } finally {
                whisper.release()
            }
        }

    /**
     * Picks the best available model on device, preferring quantized variants because the
     * quality loss vs the f16 originals is sub-percentage and the size win is 2-3×: the
     * `small-q5_1` ~180 MB ≈ `small` 466 MB in accuracy. Order:
     *  1. `ggml-small-q5_1.bin`  — best quality/size compromise; recommended default.
     *  2. `ggml-small.bin`       — f16 small, full size.
     *  3. `ggml-base-q5_1.bin`   — base quantized.
     *  4. `ggml-base.bin`        — f16 base.
     *  5. `ggml-tiny.bin`        — fastest, least accurate.
     * The user just `adb push`es whichever they want and we pick it up — no rebuild needed.
     */
    private fun resolveModelPath(): String? {
        for (file in MODEL_FILES_BY_PREFERENCE) {
            val found = candidateDirs().map { File(it, file) }.firstOrNull { it.isFile }
            if (found != null) {
                Log.i(TAG, "loaded model: ${found.name}")
                return found.absolutePath
            }
        }
        return null
    }

    private fun candidateDirs(): List<File> =
        listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
            .map { File(it, MODEL_SUBDIR) }

    private companion object {
        const val TAG = "WhisperBatch"
        const val MODEL_SUBDIR = "whisper"
        val MODEL_FILES_BY_PREFERENCE =
            listOf(
                "ggml-small-q5_1.bin",
                "ggml-small.bin",
                "ggml-base-q5_1.bin",
                "ggml-base.bin",
                "ggml-tiny.bin",
            )
        const val PCM_FULL_SCALE = 32_768f
    }
}
