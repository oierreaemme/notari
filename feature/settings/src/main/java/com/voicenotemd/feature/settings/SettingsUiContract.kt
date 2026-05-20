package com.voicenotemd.feature.settings

import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.repository.OnDeviceModelStatus

data class SettingsUiState(
    val isLoading: Boolean = true,
    val forcedLanguage: Language? = null,
    val showDeleteAllConfirm: Boolean = false,
    val notesDeleted: Boolean = false,
    val modelStatus: OnDeviceModelStatus = OnDeviceModelStatus.Missing,
    val isImportingModel: Boolean = false,
    val lastImportError: String? = null,
    /**
     * Mirrors `UserSettings.requireBiometricUnlock`. When `true`, MainActivity gates
     * the UI behind a BiometricPrompt on launch and on resume. See ADR 0013.
     */
    val requireBiometricUnlock: Boolean = false,
    /**
     * Set to `true` when the device cannot enroll any strong biometric (no fingerprint,
     * no face unlock, or hardware missing). The toggle disables itself and the row
     * shows a one-line explanation.
     */
    val biometricUnavailable: Boolean = false,
)

sealed interface SettingsUiIntent {
    data class SetForcedLanguage(val language: Language?) : SettingsUiIntent

    data object RequestDeleteAll : SettingsUiIntent

    data object ConfirmDeleteAll : SettingsUiIntent

    data object DismissDeleteAll : SettingsUiIntent

    data object AcknowledgeDeletion : SettingsUiIntent

    data object DeleteModel : SettingsUiIntent

    data object DismissImportError : SettingsUiIntent

    data class SetRequireBiometricUnlock(val enabled: Boolean) : SettingsUiIntent
}
