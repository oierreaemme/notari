package com.voicenotemd.feature.settings

import com.google.common.truth.Truth.assertThat
import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.domain.Note
import com.voicenotemd.core.common.domain.Tag
import com.voicenotemd.core.common.domain.UserSettings
import com.voicenotemd.core.common.repository.ImportResult
import com.voicenotemd.core.common.repository.NoteRepository
import com.voicenotemd.core.common.repository.OnDeviceModelRepository
import com.voicenotemd.core.common.repository.OnDeviceModelStatus
import com.voicenotemd.core.common.repository.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val settingsFlow = MutableStateFlow(UserSettings.Default)
    private val modelStatusFlow = MutableStateFlow(OnDeviceModelStatus.Missing)
    private var deleteAllCalls = 0
    private val languageWrites = mutableListOf<Language?>()
    private var importedBytes: ByteArray? = null
    private var importResultStub: ImportResult = ImportResult.Success(sizeBytes = 0L)
    private var modelDeleted = 0
    private val biometricWrites = mutableListOf<Boolean>()

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var noteRepository: NoteRepository
    private lateinit var modelRepository: OnDeviceModelRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        settingsRepository =
            object : SettingsRepository {
                override fun observe(): Flow<UserSettings> = settingsFlow

                override suspend fun setForcedLanguage(language: Language?) {
                    languageWrites += language
                    settingsFlow.value = settingsFlow.value.copy(forcedLanguage = language)
                }

                override suspend fun markOnboardingComplete() {
                    settingsFlow.value = settingsFlow.value.copy(hasCompletedOnboarding = true)
                }

                override suspend fun setRequireBiometricUnlock(enabled: Boolean) {
                    biometricWrites += enabled
                    settingsFlow.value = settingsFlow.value.copy(requireBiometricUnlock = enabled)
                }
            }
        noteRepository =
            object : NoteRepository {
                override fun observeAll(): Flow<List<Note>> = flowOf(emptyList())

                override fun observe(id: String): Flow<Note?> = flowOf(null)

                override fun observeByTag(tag: Tag): Flow<List<Note>> = flowOf(emptyList())

                override fun observeAllTags(): Flow<List<Tag>> = flowOf(emptyList())

                override suspend fun insert(note: Note) = Unit

                override suspend fun update(note: Note) = Unit

                override suspend fun delete(id: String) = Unit

                override suspend fun deleteAll() {
                    deleteAllCalls++
                }
            }
        modelRepository =
            object : OnDeviceModelRepository {
                override fun observeStatus(): Flow<OnDeviceModelStatus> = modelStatusFlow

                override suspend fun importFrom(source: InputStream): ImportResult {
                    importedBytes = source.readBytes()
                    if (importResultStub is ImportResult.Success) {
                        modelStatusFlow.value = OnDeviceModelStatus.Present
                    }
                    return importResultStub
                }

                override suspend fun delete() {
                    modelDeleted++
                    modelStatusFlow.value = OnDeviceModelStatus.Missing
                }
            }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `should reflect repository state on init`() =
        runTest {
            settingsFlow.value = UserSettings.Default.copy(forcedLanguage = Language.German)
            val vm = newViewModel()
            advanceTimeBy(50)

            assertThat(vm.uiState.value.isLoading).isFalse()
            assertThat(vm.uiState.value.forcedLanguage).isEqualTo(Language.German)
        }

    @Test
    fun `should write through to settings repository`() =
        runTest {
            val vm = newViewModel()
            advanceTimeBy(50)

            vm.onIntent(SettingsUiIntent.SetForcedLanguage(Language.French))

            assertThat(languageWrites).containsExactly(Language.French)
        }

    @Test
    fun `should map Unknown to null when persisting`() =
        runTest {
            val vm = newViewModel()
            advanceTimeBy(50)

            vm.onIntent(SettingsUiIntent.SetForcedLanguage(Language.Unknown))

            assertThat(languageWrites).containsExactly(null as Language?)
        }

    @Test
    fun `should require explicit confirmation before deleting all`() =
        runTest {
            val vm = newViewModel()
            advanceTimeBy(50)

            vm.onIntent(SettingsUiIntent.RequestDeleteAll)
            assertThat(vm.uiState.value.showDeleteAllConfirm).isTrue()
            assertThat(deleteAllCalls).isEqualTo(0)

            vm.onIntent(SettingsUiIntent.ConfirmDeleteAll)

            assertThat(deleteAllCalls).isEqualTo(1)
            assertThat(vm.uiState.value.showDeleteAllConfirm).isFalse()
            assertThat(vm.uiState.value.notesDeleted).isTrue()
        }

    @Test
    fun `should clear deletion banner when acknowledged`() =
        runTest {
            val vm = newViewModel()
            advanceTimeBy(50)
            vm.onIntent(SettingsUiIntent.RequestDeleteAll)
            vm.onIntent(SettingsUiIntent.ConfirmDeleteAll)

            vm.onIntent(SettingsUiIntent.AcknowledgeDeletion)

            assertThat(vm.uiState.value.notesDeleted).isFalse()
        }

    @Test
    fun `should drop confirmation when dismissed without confirming`() =
        runTest {
            val vm = newViewModel()
            advanceTimeBy(50)
            vm.onIntent(SettingsUiIntent.RequestDeleteAll)

            vm.onIntent(SettingsUiIntent.DismissDeleteAll)

            assertThat(vm.uiState.value.showDeleteAllConfirm).isFalse()
            assertThat(deleteAllCalls).isEqualTo(0)
        }

    @Test
    fun `should expose model status from repository`() =
        runTest {
            modelStatusFlow.value = OnDeviceModelStatus.Present
            val vm = newViewModel()
            advanceTimeBy(50)

            assertThat(vm.uiState.value.modelStatus).isEqualTo(OnDeviceModelStatus.Present)
        }

    @Test
    fun `should stream bytes from picker into model repository on import`() =
        runTest {
            val vm = newViewModel()
            advanceTimeBy(50)
            val payload = byteArrayOf(1, 2, 3, 4, 5)

            vm.importModelFromStream { ByteArrayInputStream(payload) }
            advanceTimeBy(50)

            assertThat(importedBytes).isEqualTo(payload)
            assertThat(vm.uiState.value.isImportingModel).isFalse()
            assertThat(vm.uiState.value.modelStatus).isEqualTo(OnDeviceModelStatus.Present)
        }

    @Test
    fun `should surface a friendly error when picker returns null`() =
        runTest {
            val vm = newViewModel()
            advanceTimeBy(50)

            vm.importModelFromStream { null }
            advanceTimeBy(50)

            assertThat(vm.uiState.value.lastImportError).isNotNull()
            assertThat(vm.uiState.value.isImportingModel).isFalse()
            assertThat(importedBytes).isNull()
        }

    @Test
    fun `should record reason when repository import fails`() =
        runTest {
            importResultStub = ImportResult.Failed(reason = "disk full")
            val vm = newViewModel()
            advanceTimeBy(50)

            vm.importModelFromStream { ByteArrayInputStream(ByteArray(8)) }
            advanceTimeBy(50)

            assertThat(vm.uiState.value.lastImportError).isEqualTo("disk full")
        }

    @Test
    fun `should ignore concurrent import requests while one is in flight`() =
        runTest {
            val vm = newViewModel()
            advanceTimeBy(50)

            // With UnconfinedTestDispatcher the first import would complete synchronously
            // before the second call has a chance to be dropped. We pause the first import
            // inside its openStream lambda (which is `suspend () -> InputStream?`) so the
            // ViewModel's `isImportingModel = true` state is observable to the second call.
            val gate = CompletableDeferred<Unit>()
            var firstStreamCalls = 0
            vm.importModelFromStream {
                firstStreamCalls++
                gate.await()
                ByteArrayInputStream(ByteArray(8))
            }
            // At this point the first import coroutine is suspended at `gate.await()` and
            // isImportingModel is still true. The second call must be dropped.
            var secondStreamCalls = 0
            vm.importModelFromStream {
                secondStreamCalls++
                ByteArrayInputStream(ByteArray(8))
            }

            assertThat(firstStreamCalls).isEqualTo(1)
            assertThat(secondStreamCalls).isEqualTo(0)

            // Cleanly resume the first import so any background plumbing settles.
            gate.complete(Unit)
            advanceTimeBy(50)
        }

    @Test
    fun `should mirror biometric flag from settings repository`() =
        runTest {
            settingsFlow.value = UserSettings.Default.copy(requireBiometricUnlock = true)
            val vm = newViewModel()
            advanceTimeBy(50)

            assertThat(vm.uiState.value.requireBiometricUnlock).isTrue()
        }

    @Test
    fun `should persist biometric toggle through repository`() =
        runTest {
            val vm = newViewModel()
            advanceTimeBy(50)
            vm.onBiometricAvailability(available = true)

            vm.onIntent(SettingsUiIntent.SetRequireBiometricUnlock(true))
            advanceTimeBy(50)

            assertThat(biometricWrites).containsExactly(true)
            assertThat(vm.uiState.value.requireBiometricUnlock).isTrue()
        }

    @Test
    fun `should refuse to enable biometric when device cannot authenticate`() =
        runTest {
            val vm = newViewModel()
            advanceTimeBy(50)
            vm.onBiometricAvailability(available = false)

            vm.onIntent(SettingsUiIntent.SetRequireBiometricUnlock(true))
            advanceTimeBy(50)

            // No write, no UI state flip — protects the user from locking themselves out.
            assertThat(biometricWrites).isEmpty()
            assertThat(vm.uiState.value.requireBiometricUnlock).isFalse()
            assertThat(vm.uiState.value.biometricUnavailable).isTrue()
        }

    @Test
    fun `should force biometric off when device reports unavailable after it was on`() =
        runTest {
            settingsFlow.value = UserSettings.Default.copy(requireBiometricUnlock = true)
            val vm = newViewModel()
            advanceTimeBy(50)

            vm.onBiometricAvailability(available = false)
            advanceTimeBy(50)

            // User had enrolled biometrics → enabled the toggle → later revoked enrollment.
            // We auto-disable to prevent a lock-out at next launch.
            assertThat(biometricWrites).containsExactly(false)
        }

    @Test
    fun `should delete model and revert status on DeleteModel intent`() =
        runTest {
            modelStatusFlow.value = OnDeviceModelStatus.Present
            val vm = newViewModel()
            advanceTimeBy(50)

            vm.onIntent(SettingsUiIntent.DeleteModel)
            advanceTimeBy(50)

            assertThat(modelDeleted).isEqualTo(1)
            assertThat(vm.uiState.value.modelStatus).isEqualTo(OnDeviceModelStatus.Missing)
        }

    private fun newViewModel(): SettingsViewModel =
        SettingsViewModel(
            settingsRepository = settingsRepository,
            noteRepository = noteRepository,
            modelRepository = modelRepository,
        )
}
