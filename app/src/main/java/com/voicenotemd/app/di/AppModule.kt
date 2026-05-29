package com.voicenotemd.app.di

import android.content.Context
import com.voicenotemd.core.asr.WhisperModelLocation
import com.voicenotemd.core.common.di.defaultAppDispatchers
import com.voicenotemd.core.common.dispatchers.AppDispatchers
import com.voicenotemd.core.common.domain.Note
import com.voicenotemd.core.common.repository.GemmaModel
import com.voicenotemd.core.common.repository.NoteRepository
import com.voicenotemd.core.common.repository.OnDeviceModelRepository
import com.voicenotemd.core.common.repository.WhisperModel
import com.voicenotemd.core.common.usecase.SaveNoteUseCase
import com.voicenotemd.core.inference.session.FileBasedOnDeviceModelRepository
import com.voicenotemd.core.inference.session.ImportTargetSelector
import com.voicenotemd.core.inference.session.ModelFileProvider
import com.voicenotemd.core.inference.session.ModelValidationSpec
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideAppDispatchers(): AppDispatchers = defaultAppDispatchers()

    /**
     * v1 model file provider. Looks for `gemma-4-e2b-it.litertlm` in two locations,
     * in priority order:
     *
     *  1. **App-private internal storage** — `filesDir/models/gemma-4-e2b-it.litertlm`.
     *     This is where the SAF "Import model" flow copies the file the user picks.
     *     Truly private; never world-readable.
     *  2. **App-scoped external storage** — `getExternalFilesDir("models")/<file>`.
     *     This is the convenience path for local development:
     *     `adb push gemma-4-E2B-it.litertlm
     *      /sdcard/Android/data/com.voicenotemd.debug/files/models/`
     *
     * If neither is present, [fileOrNull] returns null and `LiteRtLmGemmaSession` throws
     * `GemmaUnavailableException` on first use; the use-case layer catches that and falls
     * back to plain-text storage (per ADR 0005). The Settings screen surfaces an
     * explicit "Import model" entry so the user has a deterministic way out.
     */
    @Provides
    @Singleton
    fun provideModelFileProvider(
        @ApplicationContext context: Context,
    ): ModelFileProvider =
        object : ModelFileProvider {
            private val internalFile: File =
                File(context.filesDir, "models/$MODEL_FILE_NAME")
            private val externalFile: File =
                File(context.getExternalFilesDir("models"), MODEL_FILE_NAME)

            private fun resolved(): File? =
                when {
                    internalFile.exists() && internalFile.length() > 0L -> internalFile
                    externalFile.exists() && externalFile.length() > 0L -> externalFile
                    else -> null
                }

            override fun isAvailable(): Boolean = resolved() != null

            override fun fileOrNull(): File? = resolved()
        }

    private const val MODEL_FILE_NAME = "gemma-4-e2b-it.litertlm"

    /**
     * SAF imports always go into app-private internal storage — the file is never
     * world-readable on any API level, and uninstalling the app removes it cleanly.
     */
    @Provides
    @Singleton
    fun provideImportTargetSelector(
        @ApplicationContext context: Context,
    ): ImportTargetSelector =
        ImportTargetSelector {
            File(context.filesDir, "models/$MODEL_FILE_NAME")
        }

    @Provides
    @Singleton
    @GemmaModel
    fun provideGemmaModelRepository(
        modelFileProvider: ModelFileProvider,
        importTargetSelector: ImportTargetSelector,
        dispatchers: AppDispatchers,
    ): OnDeviceModelRepository =
        FileBasedOnDeviceModelRepository(
            modelFileProvider = modelFileProvider,
            targetSelector = importTargetSelector,
            dispatchers = dispatchers,
            validation = GEMMA_VALIDATION,
        )

    /**
     * The whisper.cpp transcription model. Shares the generic
     * [FileBasedOnDeviceModelRepository] with Gemma; only the location and validation
     * differ. The provider/target are built inline (not separate @Provides) so they never
     * collide with the Gemma [ModelFileProvider]/[ImportTargetSelector] bindings. Paths come
     * from [WhisperModelLocation] — the same constants the transcriber reads from. ADR 0022.
     */
    @Provides
    @Singleton
    @WhisperModel
    fun provideWhisperModelRepository(
        @ApplicationContext context: Context,
        dispatchers: AppDispatchers,
    ): OnDeviceModelRepository {
        val internalDir = File(context.filesDir, WhisperModelLocation.SUBDIR)
        val externalDir = File(context.getExternalFilesDir(null), WhisperModelLocation.SUBDIR)
        val provider =
            object : ModelFileProvider {
                // Present if either whisper dir holds a non-empty *.bin (a SAF import or an
                // adb-pushed dev model). Mirrors WhisperBatchTranscriber's resolution intent.
                private fun anyModel(): File? =
                    listOf(internalDir, externalDir)
                        .flatMap { it.listFiles()?.toList().orEmpty() }
                        .firstOrNull { it.isFile && it.length() > 0L && it.name.endsWith(".bin", true) }

                override fun isAvailable(): Boolean = anyModel() != null

                override fun fileOrNull(): File? = anyModel()
            }
        val target = ImportTargetSelector { File(internalDir, WhisperModelLocation.IMPORTED_FILE_NAME) }
        return FileBasedOnDeviceModelRepository(
            modelFileProvider = provider,
            targetSelector = target,
            dispatchers = dispatchers,
            validation = WHISPER_VALIDATION,
        )
    }

    /**
     * Gemma 4 E2B INT4 is well over 1 GB; 200 MB is a safe floor that rejects any
     * wrong/smaller pick while leaving headroom for future quantizations. The file the
     * user downloads from Google AI ends in `.litertlm`.
     */
    private val GEMMA_VALIDATION =
        ModelValidationSpec(
            label = "the Gemma model",
            expectedHint = ".litertlm file",
            minBytes = 200_000_000,
            nameMatches = { it.endsWith(".litertlm", ignoreCase = true) },
        )

    /**
     * whisper ggml models range from ~30 MB (tiny, quantized) to ~500 MB (small, f16); a
     * 10 MB floor rejects obviously-wrong picks without excluding the smallest real model.
     * The ggerganov releases are named `ggml-*.bin`.
     */
    private val WHISPER_VALIDATION =
        ModelValidationSpec(
            label = "a whisper model",
            expectedHint = "ggml-*.bin file",
            minBytes = 10_000_000,
            nameMatches = { it.endsWith(".bin", ignoreCase = true) },
        )

    @Provides
    @Singleton
    fun provideSaveNoteUseCase(repository: NoteRepository): SaveNoteUseCase =
        object : SaveNoteUseCase {
            override suspend fun invoke(note: Note) = repository.insert(note)
        }
}
