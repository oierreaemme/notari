package com.voicenotemd.core.inference.session

import com.voicenotemd.core.common.dispatchers.AppDispatchers
import com.voicenotemd.core.common.repository.ImportResult
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
    ) : OnDeviceModelRepository {
        private val status = MutableStateFlow(currentStatus())

        override fun observeStatus(): Flow<OnDeviceModelStatus> = status.asStateFlow()

        override suspend fun importFrom(source: InputStream): ImportResult =
            withContext(dispatchers.io) {
                val target = targetSelector.targetFile()
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, "${target.name}.part")
                try {
                    tmp.outputStream().use { out -> source.copyTo(out, BUFFER_SIZE) }
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
