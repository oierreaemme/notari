package com.voicenotemd.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.repository.GemmaModel
import com.voicenotemd.core.common.repository.ImportResult
import com.voicenotemd.core.common.repository.ModelImportCandidate
import com.voicenotemd.core.common.repository.NoteRepository
import com.voicenotemd.core.common.repository.OnDeviceModelRepository
import com.voicenotemd.core.common.repository.SettingsRepository
import com.voicenotemd.core.common.repository.WhisperModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import javax.inject.Inject

/**
 * Settings is intentionally tiny — the surface is the privacy guarantee, not feature toggles.
 *
 * The destructive "delete all notes" path is gated by a confirmation flag in [SettingsUiState];
 * this is by design, not a UX preference: per CLAUDE.md, dialogs are reserved exclusively for
 * destructive actions and we want every callsite to go through the same confirm step.
 *
 * Model import: the screen owns the SAF launcher (which gives back a `Uri`) and resolves the
 * `Uri` into an [InputStream] via [ContentResolver]. The VM exposes a single suspend entry
 * point [importModelFromStream] that streams the bytes to disk via [OnDeviceModelRepository].
 * The Android `Uri` type never crosses into the VM — keeping presentation/domain layering
 * honest per CLAUDE.md section 5.
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val noteRepository: NoteRepository,
        @GemmaModel private val gemmaRepository: OnDeviceModelRepository,
        @WhisperModel private val whisperRepository: OnDeviceModelRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SettingsUiState())
        val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

        private fun repositoryFor(model: ManagedModel): OnDeviceModelRepository =
            when (model) {
                ManagedModel.Gemma -> gemmaRepository
                ManagedModel.Whisper -> whisperRepository
            }

        /** Applies [transform] to the [ModelSectionState] for [model], leaving the other intact. */
        private fun updateSection(
            model: ManagedModel,
            transform: (ModelSectionState) -> ModelSectionState,
        ) {
            _uiState.update {
                when (model) {
                    ManagedModel.Gemma -> it.copy(gemma = transform(it.gemma))
                    ManagedModel.Whisper -> it.copy(whisper = transform(it.whisper))
                }
            }
        }

        init {
            viewModelScope.launch {
                settingsRepository.observe().collect { settings ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            forcedLanguage = settings.forcedLanguage,
                            requireBiometricUnlock = settings.requireBiometricUnlock,
                        )
                    }
                }
            }
            ManagedModel.entries.forEach { model ->
                viewModelScope.launch {
                    repositoryFor(model).observeStatus().collect { status ->
                        updateSection(model) { it.copy(status = status) }
                    }
                }
            }
        }

        /**
         * Called by the Composable layer with the platform-side biometric availability check
         * (we keep `androidx.biometric` out of the VM's compile classpath so the VM remains
         * a pure unit-testable JVM target). When the device cannot enroll a strong biometric
         * we surface this in the UI and force the persisted setting off — otherwise the user
         * could end up locked out by enabling the toggle and then revoking their fingerprint.
         */
        fun onBiometricAvailability(available: Boolean) {
            _uiState.update { it.copy(biometricUnavailable = !available) }
            if (!available && _uiState.value.requireBiometricUnlock) {
                viewModelScope.launch { settingsRepository.setRequireBiometricUnlock(false) }
            }
        }

        fun onIntent(intent: SettingsUiIntent) {
            when (intent) {
                is SettingsUiIntent.SetForcedLanguage -> setLanguage(intent.language)
                SettingsUiIntent.RequestDeleteAll ->
                    _uiState.update { it.copy(showDeleteAllConfirm = true) }
                SettingsUiIntent.DismissDeleteAll ->
                    _uiState.update { it.copy(showDeleteAllConfirm = false) }
                SettingsUiIntent.ConfirmDeleteAll -> deleteAll()
                SettingsUiIntent.AcknowledgeDeletion ->
                    _uiState.update { it.copy(notesDeleted = false) }
                is SettingsUiIntent.DeleteModel -> deleteModel(intent.model)
                is SettingsUiIntent.DismissImportError ->
                    updateSection(intent.model) { it.copy(lastImportError = null) }
                is SettingsUiIntent.SetRequireBiometricUnlock -> setBiometricLock(intent.enabled)
            }
        }

        private fun setBiometricLock(enabled: Boolean) {
            // Guard: if the platform reports biometrics unavailable, ignore the request.
            // This prevents a race where the user toggles on before [onBiometricAvailability]
            // has run, then can't get past the launch gate because there's nothing to verify.
            if (enabled && _uiState.value.biometricUnavailable) return
            viewModelScope.launch {
                settingsRepository.setRequireBiometricUnlock(enabled)
            }
        }

        /**
         * Stream the chosen `.litertlm` file into app-private storage. Called from the
         * Composable layer after the SAF picker resolves; the [openStream] lambda lets the
         * VM consume the bytes without ever touching an Android `Uri` itself.
         *
         * @param openStream factory that opens the input stream. Returning `null` means the
         *   resolver couldn't open the document — we surface a friendly error.
         */
        fun importModelFromStream(
            model: ManagedModel,
            candidate: ModelImportCandidate = ModelImportCandidate(null, null),
            openStream: suspend () -> InputStream?,
        ) {
            if (_uiState.value.section(model).isImporting) return
            updateSection(model) { it.copy(isImporting = true, lastImportError = null) }
            viewModelScope.launch {
                val stream = openStream()
                if (stream == null) {
                    updateSection(model) {
                        it.copy(isImporting = false, lastImportError = "Couldn't read the chosen file.")
                    }
                    return@launch
                }
                val result = stream.use { repositoryFor(model).importFrom(it, candidate) }
                updateSection(model) {
                    when (result) {
                        is ImportResult.Success -> it.copy(isImporting = false, lastImportError = null)
                        is ImportResult.Failed -> it.copy(isImporting = false, lastImportError = result.reason)
                    }
                }
            }
        }

        private fun setLanguage(language: Language?) {
            viewModelScope.launch {
                val sanitized = language?.takeIf { it != Language.Unknown }
                settingsRepository.setForcedLanguage(sanitized)
            }
        }

        private fun deleteAll() {
            viewModelScope.launch {
                noteRepository.deleteAll()
                _uiState.update {
                    it.copy(showDeleteAllConfirm = false, notesDeleted = true)
                }
            }
        }

        private fun deleteModel(model: ManagedModel) {
            viewModelScope.launch {
                repositoryFor(model).delete()
            }
        }
    }

/**
 * The languages we let the user pin from the settings screen. Mirrors the v1 supported set
 * declared in CLAUDE.md pillar 5.
 */
val PinnableLanguages: List<Language> =
    listOf(
        Language.English,
        Language.Italian,
        Language.Spanish,
        Language.French,
        Language.German,
        Language.Portuguese,
    )
