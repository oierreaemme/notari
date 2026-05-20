package com.voicenotemd.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voicenotemd.core.common.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Decides whether the user needs the welcome flow and writes the "completed" flag once
 * they finish (or skip) it. The actual page content lives in [OnboardingPages].
 *
 * The VM resolves the visibility decision exactly once, on the first emission from
 * [SettingsRepository.observe]; subsequent emissions are ignored to avoid the screen
 * blinking when settings change while the user is mid-onboarding.
 */
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(OnboardingUiState())
        val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                val firstSettings = settingsRepository.observe().first()
                _uiState.update {
                    it.copy(
                        isResolved = true,
                        shouldShow = !firstSettings.hasCompletedOnboarding,
                        isCompleted = firstSettings.hasCompletedOnboarding,
                    )
                }
            }
        }

        fun onIntent(intent: OnboardingUiIntent) {
            when (intent) {
                OnboardingUiIntent.Skip, OnboardingUiIntent.Finish -> complete()
            }
        }

        private fun complete() {
            viewModelScope.launch {
                settingsRepository.markOnboardingComplete()
                _uiState.update { it.copy(shouldShow = false, isCompleted = true) }
            }
        }
    }
