package com.voicenotemd.feature.onboarding

import com.google.common.truth.Truth.assertThat
import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.domain.UserSettings
import com.voicenotemd.core.common.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val settingsFlow = MutableStateFlow(UserSettings.Default)
    private var markedComplete = 0

    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository =
            object : SettingsRepository {
                override fun observe(): Flow<UserSettings> = settingsFlow

                override suspend fun setForcedLanguage(language: Language?) {
                    settingsFlow.value = settingsFlow.value.copy(forcedLanguage = language)
                }

                override suspend fun markOnboardingComplete() {
                    markedComplete++
                    settingsFlow.value = settingsFlow.value.copy(hasCompletedOnboarding = true)
                }

                override suspend fun setRequireBiometricUnlock(enabled: Boolean) {
                    settingsFlow.value = settingsFlow.value.copy(requireBiometricUnlock = enabled)
                }
            }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `should resolve to shouldShow=true when onboarding never completed`() =
        runTest {
            val vm = OnboardingViewModel(repository)
            advanceTimeBy(50)

            val state = vm.uiState.value
            assertThat(state.isResolved).isTrue()
            assertThat(state.shouldShow).isTrue()
            assertThat(state.isCompleted).isFalse()
        }

    @Test
    fun `should set isCompleted=true on init when prior launch finished onboarding`() =
        runTest {
            settingsFlow.value = UserSettings.Default.copy(hasCompletedOnboarding = true)
            val vm = OnboardingViewModel(repository)
            advanceTimeBy(50)

            val state = vm.uiState.value
            assertThat(state.isResolved).isTrue()
            assertThat(state.shouldShow).isFalse()
            assertThat(state.isCompleted).isTrue()
            // We did NOT call markOnboardingComplete — the user had already completed it
            // on a prior launch, the repository already knows.
            assertThat(markedComplete).isEqualTo(0)
        }

    @Test
    fun `should mark complete and set isCompleted=true on Finish`() =
        runTest {
            val vm = OnboardingViewModel(repository)
            advanceTimeBy(50)
            // Sanity check: not completed yet
            assertThat(vm.uiState.value.isCompleted).isFalse()

            vm.onIntent(OnboardingUiIntent.Finish)
            advanceTimeBy(50)

            val state = vm.uiState.value
            assertThat(state.isCompleted).isTrue()
            assertThat(state.shouldShow).isFalse()
            assertThat(markedComplete).isEqualTo(1)
        }

    @Test
    fun `should mark complete and set isCompleted=true on Skip`() =
        runTest {
            val vm = OnboardingViewModel(repository)
            advanceTimeBy(50)

            vm.onIntent(OnboardingUiIntent.Skip)
            advanceTimeBy(50)

            val state = vm.uiState.value
            assertThat(state.isCompleted).isTrue()
            assertThat(markedComplete).isEqualTo(1)
        }
}
