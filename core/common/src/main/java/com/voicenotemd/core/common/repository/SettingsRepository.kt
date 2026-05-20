package com.voicenotemd.core.common.repository

import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.domain.UserSettings
import kotlinx.coroutines.flow.Flow

/**
 * Single point of access to the user's tunable settings.
 *
 * Implemented in :core:datastore on top of Preferences DataStore. The contract is held
 * here in the domain layer so feature ViewModels can depend on it without pulling in
 * androidx.datastore — keeping the layering rules from CLAUDE.md section 5 honest.
 */
interface SettingsRepository {
    /**
     * Cold flow that emits the current settings + every subsequent change.
     */
    fun observe(): Flow<UserSettings>

    /**
     * Pin the dictation language. Pass `null` to revert to auto-detection.
     */
    suspend fun setForcedLanguage(language: Language?)

    /**
     * Mark the onboarding flow as complete. Idempotent — once true, subsequent calls are
     * harmless no-ops.
     */
    suspend fun markOnboardingComplete()

    /**
     * Enable or disable the system biometric unlock gate on app launch and resume.
     * The MainActivity reads the resulting flag from [observe] and shows a
     * BiometricPrompt before unblocking the UI. See ADR 0013.
     */
    suspend fun setRequireBiometricUnlock(enabled: Boolean)
}
