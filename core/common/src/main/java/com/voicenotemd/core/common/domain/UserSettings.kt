package com.voicenotemd.core.common.domain

/**
 * Snapshot of the user-tunable preferences. Kept tiny on purpose — every additional
 * setting is a future migration step.
 *
 * @param forcedLanguage when non-null, the recognizer + Gemma are pinned to this
 *   language regardless of auto-detection. `null` means "let the device decide".
 * @param hasCompletedOnboarding `false` until the user has seen the 3-screen welcome
 *   (per CLAUDE.md, the onboarding caps out at 3 screens, no carousel of 8 features).
 * @param requireBiometricUnlock when `true`, the app shows a system biometric prompt
 *   on every cold launch (and on resume after being backgrounded) before revealing
 *   any notes. Off by default — privacy stays a feature the user opts *into*, not a
 *   wall in front of them on day one. See ADR 0013.
 */
data class UserSettings(
    val forcedLanguage: Language?,
    val hasCompletedOnboarding: Boolean,
    val requireBiometricUnlock: Boolean,
) {
    companion object {
        /** Defaults applied when the DataStore file is empty (first launch). */
        val Default: UserSettings =
            UserSettings(
                forcedLanguage = null,
                hasCompletedOnboarding = false,
                requireBiometricUnlock = false,
            )
    }
}
