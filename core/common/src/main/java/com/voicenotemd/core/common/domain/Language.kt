package com.voicenotemd.core.common.domain

/**
 * The languages we officially support at v1. They are reliably handled by both
 * Android SpeechRecognizer and Gemma 4 E2B (per CLAUDE.md pillar 5).
 *
 * Two BCP-47 representations:
 * - [bcp47] is the **primary** tag ("en", "it", …). This is what we exchange with
 *   Gemma in the prompt's `language` field, and what the JSON parser reads back.
 * - [recognizerLocale] is the **locale-tagged** form ("en-US", "it-IT", …) we hand
 *   to Android `SpeechRecognizer` via `RecognizerIntent.EXTRA_LANGUAGE`. Many
 *   recognizer implementations only honor the locale-tagged form when offline.
 *
 * Inside the app we use this enum so we get exhaustive `when` checks.
 */
enum class Language(val bcp47: String, val recognizerLocale: String) {
    English("en", "en-US"),
    Italian("it", "it-IT"),
    Spanish("es", "es-ES"),
    French("fr", "fr-FR"),
    German("de", "de-DE"),
    Portuguese("pt", "pt-PT"),

    /**
     * The user dictated something we couldn't reliably classify, OR the locale fell
     * outside the supported set. We keep the note but stamp it as Unknown so we don't
     * pretend to have detected something we didn't.
     */
    Unknown("und", "und"),
    ;

    companion object {
        /**
         * Best-effort mapping from a BCP-47 tag (or the prefix of one) to a [Language].
         * Returns [Unknown] for unsupported locales. Robust to casing and to country
         * suffixes — e.g. "EN-US", "en_GB", "en-Latn-GB" all collapse to [English].
         */
        fun fromBcp47(tag: String?): Language {
            if (tag.isNullOrBlank()) return Unknown
            val primary =
                tag.trim()
                    .substringBefore('-')
                    .substringBefore('_')
                    .lowercase()
            return entries.firstOrNull { it.bcp47 == primary } ?: Unknown
        }
    }
}
