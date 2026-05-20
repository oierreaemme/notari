package com.voicenotemd.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.domain.UserSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PreferencesSettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataStoreFile: File
    private lateinit var repository: PreferencesSettingsRepository

    // We use UnconfinedTestDispatcher so DataStore reads/writes complete immediately
    // (no scheduler advancement needed). Both the DataStore's internal scope and
    // runTest below use the same dispatcher instance so they coordinate.
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        dataStoreFile = tempFolder.newFile("settings.preferences_pb")
        // The factory rejects empty files — start clean by deleting and letting it recreate.
        dataStoreFile.delete()
        repository =
            PreferencesSettingsRepository(
                dataStore =
                    PreferenceDataStoreFactory.create(
                        scope = testScope,
                        produceFile = { dataStoreFile },
                    ),
            )
    }

    @After
    fun tearDown() {
        if (dataStoreFile.exists()) dataStoreFile.delete()
    }

    @Test
    fun `should emit defaults when datastore is empty`() =
        runTest(testDispatcher) {
            repository.observe().test {
                assertThat(awaitItem()).isEqualTo(UserSettings.Default)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `should persist forced language across writes`() =
        runTest(testDispatcher) {
            repository.setForcedLanguage(Language.Italian)

            repository.observe().test {
                val settings = awaitItem()
                assertThat(settings.forcedLanguage).isEqualTo(Language.Italian)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `should clear forced language when set to null`() =
        runTest(testDispatcher) {
            repository.setForcedLanguage(Language.Spanish)
            repository.setForcedLanguage(null)

            repository.observe().test {
                assertThat(awaitItem().forcedLanguage).isNull()
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `should treat Unknown language as cleared`() =
        runTest(testDispatcher) {
            repository.setForcedLanguage(Language.French)
            repository.setForcedLanguage(Language.Unknown)

            repository.observe().test {
                assertThat(awaitItem().forcedLanguage).isNull()
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `should mark onboarding complete idempotently`() =
        runTest(testDispatcher) {
            repository.markOnboardingComplete()
            repository.markOnboardingComplete()

            repository.observe().test {
                assertThat(awaitItem().hasCompletedOnboarding).isTrue()
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `should persist biometric unlock toggle across writes`() =
        runTest(testDispatcher) {
            repository.setRequireBiometricUnlock(true)

            repository.observe().test {
                assertThat(awaitItem().requireBiometricUnlock).isTrue()
                cancelAndConsumeRemainingEvents()
            }

            repository.setRequireBiometricUnlock(false)

            repository.observe().test {
                assertThat(awaitItem().requireBiometricUnlock).isFalse()
                cancelAndConsumeRemainingEvents()
            }
        }
}
