package com.voicenotemd.core.common.repository

import kotlinx.coroutines.flow.Flow
import java.io.InputStream

/**
 * Visibility + lifecycle for the on-device LLM model file (`gemma-4-e2b-it.litertlm`).
 *
 * The interface is deliberately narrow: feature code only ever needs to know whether the
 * model is present and to hand over a fresh import. The actual file paths, atomic-rename
 * dance, and integrity checks live in the implementation (`:core:inference`).
 */
interface OnDeviceModelRepository {
    /** Cold flow that emits the current status and every subsequent change. */
    fun observeStatus(): Flow<OnDeviceModelStatus>

    /**
     * Copy the bytes from [source] into the app-private model directory, atomically
     * replacing any prior file. The caller is responsible for closing [source].
     *
     * Streamed so we never hold the full ~1.5 GB blob in memory.
     */
    suspend fun importFrom(source: InputStream): ImportResult

    /** Permanently delete the on-device model. After this returns, status is `Missing`. */
    suspend fun delete()
}

enum class OnDeviceModelStatus {
    /** No model file has been imported yet — the app falls back to plain-text capture. */
    Missing,

    /** Model file is present and ready for inference. */
    Present,
}

sealed interface ImportResult {
    data class Success(val sizeBytes: Long) : ImportResult

    data class Failed(val reason: String) : ImportResult
}
