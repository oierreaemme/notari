package com.voicenotemd.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.domain.UserSettings
import com.voicenotemd.core.common.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Preferences-DataStore-backed implementation of [SettingsRepository].
 *
 * The DataStore reference is injected so unit tests can spin up a temporary file-backed
 * store via `androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(...)`
 * without dragging in Hilt. Production wiring is done in [DataStoreModule].
 */
class PreferencesSettingsRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : SettingsRepository {
        override fun observe(): Flow<UserSettings> =
            dataStore.data.map { prefs ->
                UserSettings(
                    forcedLanguage =
                        prefs[Keys.ForcedLanguage]
                            ?.let(Language.Companion::fromBcp47)
                            ?.takeIf { it != Language.Unknown },
                    hasCompletedOnboarding = prefs[Keys.HasCompletedOnboarding] ?: false,
                    requireBiometricUnlock = prefs[Keys.RequireBiometricUnlock] ?: false,
                )
            }

        override suspend fun setForcedLanguage(language: Language?) {
            dataStore.edit { prefs ->
                if (language == null || language == Language.Unknown) {
                    prefs.remove(Keys.ForcedLanguage)
                } else {
                    prefs[Keys.ForcedLanguage] = language.bcp47
                }
            }
        }

        override suspend fun markOnboardingComplete() {
            dataStore.edit { prefs ->
                prefs[Keys.HasCompletedOnboarding] = true
            }
        }

        override suspend fun setRequireBiometricUnlock(enabled: Boolean) {
            dataStore.edit { prefs ->
                prefs[Keys.RequireBiometricUnlock] = enabled
            }
        }

        /**
         * Preference keys are kept private to this file. Keep the strings stable; renaming
         * them is a migration. They never appear in any user-visible surface.
         */
        private object Keys {
            val ForcedLanguage = stringPreferencesKey("forced_language_bcp47")
            val HasCompletedOnboarding = booleanPreferencesKey("has_completed_onboarding")
            val RequireBiometricUnlock = booleanPreferencesKey("require_biometric_unlock")
        }
    }
