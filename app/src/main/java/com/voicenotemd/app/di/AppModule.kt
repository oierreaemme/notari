package com.voicenotemd.app.di

import android.content.Context
import com.voicenotemd.core.common.di.defaultAppDispatchers
import com.voicenotemd.core.common.dispatchers.AppDispatchers
import com.voicenotemd.core.common.domain.Note
import com.voicenotemd.core.common.repository.NoteRepository
import com.voicenotemd.core.common.repository.OnDeviceModelRepository
import com.voicenotemd.core.common.usecase.SaveNoteUseCase
import com.voicenotemd.core.inference.session.FileBasedOnDeviceModelRepository
import com.voicenotemd.core.inference.session.ImportTargetSelector
import com.voicenotemd.core.inference.session.ModelFileProvider
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
    fun provideOnDeviceModelRepository(
        modelFileProvider: ModelFileProvider,
        importTargetSelector: ImportTargetSelector,
        dispatchers: AppDispatchers,
    ): OnDeviceModelRepository =
        FileBasedOnDeviceModelRepository(
            modelFileProvider = modelFileProvider,
            targetSelector = importTargetSelector,
            dispatchers = dispatchers,
        )

    @Provides
    @Singleton
    fun provideSaveNoteUseCase(repository: NoteRepository): SaveNoteUseCase =
        object : SaveNoteUseCase {
            override suspend fun invoke(note: Note) = repository.insert(note)
        }
}
