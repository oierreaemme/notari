package com.voicenotemd.feature.settings

import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.repository.OnDeviceModelStatus

/** The two on-device models the user imports via SAF. See ADR 0008 / 0018 / 0022. */
enum class ManagedModel {
    /** Gemma 4 E2B `.litertlm` — note structuring. */
    Gemma,

    /** whisper.cpp `ggml-*.bin` — voice transcription. */
    Whisper,
}

/** Per-model import/status slice, so the screen renders one section per [ManagedModel]. */
data class ModelSectionState(
    val status: OnDeviceModelStatus = OnDeviceModelStatus.Missing,
    val isImporting: Boolean = false,
    val lastImportError: String? = null,
)

data class SettingsUiState(
    val isLoading: Boolean = true,
    val forcedLanguage: Language? = null,
    val showDeleteAllConfirm: Boolean = false,
    val notesDeleted: Boolean = false,
    val gemma: ModelSectionState = ModelSectionState(),
    val whisper: ModelSectionState = ModelSectionState(),
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
) {
    /** Convenience accessor for the section matching [model]. */
    fun section(model: ManagedModel): ModelSectionState =
        when (model) {
            ManagedModel.Gemma -> gemma
            ManagedModel.Whisper -> whisper
        }
}

sealed interface SettingsUiIntent {
    data class SetForcedLanguage(val language: Language?) : SettingsUiIntent

    data object RequestDeleteAll : SettingsUiIntent

    data object ConfirmDeleteAll : SettingsUiIntent

    data object DismissDeleteAll : SettingsUiIntent

    data object AcknowledgeDeletion : SettingsUiIntent

    data class DeleteModel(val model: ManagedModel) : SettingsUiIntent

    data class DismissImportError(val model: ManagedModel) : SettingsUiIntent

    data class SetRequireBiometricUnlock(val enabled: Boolean) : SettingsUiIntent
}
