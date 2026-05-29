package com.voicenotemd.core.inference.session

import com.voicenotemd.core.common.dispatchers.AppDispatchers
import com.voicenotemd.core.common.repository.ImportResult
import com.voicenotemd.core.common.repository.ModelImportCandidate
import com.voicenotemd.core.common.repository.OnDeviceModelRepository
import com.voicenotemd.core.common.repository.OnDeviceModelStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject

/**
 * Per-model validation rules, so the same [FileBasedOnDeviceModelRepository] can guard
 * both the Gemma `.litertlm` and the whisper `ggml-*.bin` imports. The checks are
 * deliberately coarse — file name + size — which catches the realistic user mistake
 * (picking the wrong, much smaller file) without trying to parse model-specific magic
 * bytes of formats we don't fully control.
 *
 * @param label human name of the model, for error copy ("the Gemma model").
 * @param expectedHint what a correct file looks like, for error copy (".litertlm file").
 * @param minBytes reject anything smaller than this — a real model is hundreds of MB.
 * @param nameMatches predicate over the SAF display name; `null`/unknown names pass
 *   (not every provider reports a name) so we never block on missing metadata.
 */
data class ModelValidationSpec(
    val label: String,
    val expectedHint: String,
    val minBytes: Long,
    val nameMatches: (String) -> Boolean,
)

/**
 * File-backed [OnDeviceModelRepository]. Writes the imported bytes to a sibling temp file
 * first and then `renameTo`s it onto the canonical path — so a partial write never leaves
 * a half-imported file that the loader would try to use.
 *
 * Status is tracked in memory (StateFlow) and seeded from the [ModelFileProvider] at
 * construction; subsequent writes via [importFrom] / [delete] update the flow themselves.
 * That's good enough for v1 — the only writers are this class.
 */
class FileBasedOnDeviceModelRepository
    @Inject
    constructor(
        private val modelFileProvider: ModelFileProvider,
        private val targetSelector: ImportTargetSelector,
        private val dispatchers: AppDispatchers,
        private val validation: ModelValidationSpec,
    ) : OnDeviceModelRepository {
        private val status = MutableStateFlow(currentStatus())

        override fun observeStatus(): Flow<OnDeviceModelStatus> = status.asStateFlow()

        override suspend fun importFrom(
            source: InputStream,
            candidate: ModelImportCandidate,
        ): ImportResult =
            withContext(dispatchers.io) {
                // Pre-copy validation on the SAF metadata — cheap, and avoids streaming a
                // multi-GB file we already know is wrong. Both checks no-op on missing data.
                preCopyRejection(candidate)?.let { return@withContext it }

                val target = targetSelector.targetFile()
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, "${target.name}.part")
                try {
                    tmp.outputStream().use { out -> source.copyTo(out, BUFFER_SIZE) }

                    // Post-copy size check: the declared size can be absent or lie, but the
                    // bytes on disk cannot. A too-small file is the wrong file — delete the
                    // temp and report it rather than renaming a dud onto the canonical path.
                    if (tmp.length() < validation.minBytes) {
                        val actualMb = tmp.length() / 1_000_000
                        val minMb = validation.minBytes / 1_000_000
                        tmp.delete()
                        return@withContext ImportResult.Failed(
                            reason =
                                "That file is only ${actualMb} MB — too small to be " +
                                    "${validation.label} (expected at least ${minMb} MB, " +
                                    "a ${validation.expectedHint}). Did you pick the right file?",
                        )
                    }

                    if (!tmp.renameTo(target)) {
                        // Cross-filesystem rename failures fall through to copy + delete.
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    status.value = OnDeviceModelStatus.Present
                    ImportResult.Success(sizeBytes = target.length())
                } catch (t: Throwable) {
                    tmp.delete()
                    ImportResult.Failed(reason = t.message ?: t::class.java.simpleName)
                }
            }

        /** Returns a [ImportResult.Failed] if the candidate's name/size obviously disqualify
         *  it, or `null` to proceed. Missing metadata always proceeds. */
        private fun preCopyRejection(candidate: ModelImportCandidate): ImportResult.Failed? {
            val name = candidate.displayName
            if (name != null && !validation.nameMatches(name)) {
                return ImportResult.Failed(
                    reason =
                        "\"$name\" doesn't look like ${validation.label} " +
                            "(expected a ${validation.expectedHint}). Did you pick the right file?",
                )
            }
            val declared = candidate.declaredSizeBytes
            if (declared != null && declared in 1 until validation.minBytes) {
                val declaredMb = declared / 1_000_000
                val minMb = validation.minBytes / 1_000_000
                return ImportResult.Failed(
                    reason =
                        "That file is only ${declaredMb} MB — too small to be " +
                            "${validation.label} (expected at least ${minMb} MB). " +
                            "Did you pick the right file?",
                )
            }
            return null
        }

        override suspend fun delete() =
            withContext(dispatchers.io) {
                targetSelector.targetFile().takeIf(File::exists)?.delete()
                status.value = currentStatus()
            }

        private fun currentStatus(): OnDeviceModelStatus =
            if (modelFileProvider.isAvailable()) {
                OnDeviceModelStatus.Present
            } else {
                OnDeviceModelStatus.Missing
            }

        private companion object {
            const val BUFFER_SIZE = 64 * 1024
        }
    }

/**
 * The destination for the SAF "Import model" copy. Pulled into a Hilt binding so unit tests
 * can target a temp directory without standing up a Context.
 */
fun interface ImportTargetSelector {
    fun targetFile(): File
}
