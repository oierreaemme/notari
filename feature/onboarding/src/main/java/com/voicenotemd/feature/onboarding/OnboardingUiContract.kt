package com.voicenotemd.feature.onboarding

/**
 * Three-screen onboarding (per CLAUDE.md section 8):
 *  1. Speak.
 *  2. We make it Markdown.
 *  3. Audio never leaves your phone.
 *
 * No carousel of 8 features, no persuasion deck — just the three things the user must
 * understand before using the app for the first time.
 */
data class OnboardingUiState(
    val isResolved: Boolean = false,
    val shouldShow: Boolean = false,
    val isCompleted: Boolean = false,
)

sealed interface OnboardingUiIntent {
    data object Skip : OnboardingUiIntent

    data object Finish : OnboardingUiIntent
}

/**
 * Static page content. Lives outside the VM so it can be unit-tested directly and so the
 * Compose layer can render it without instantiating the VM in @Preview.
 */
data class OnboardingPage(
    val headline: String,
    val body: String,
)

val OnboardingPages: List<OnboardingPage> =
    listOf(
        OnboardingPage(
            headline = "Speak.",
            body = "Tap the mic and dictate naturally. We'll handle the rest.",
        ),
        OnboardingPage(
            headline = "We make it Markdown.",
            body =
                "Gemma 4 turns your voice into a clean, structured note — title, " +
                    "tags, dates, body. All on your phone.",
        ),
        OnboardingPage(
            headline = "Audio never leaves your phone.",
            body =
                "No INTERNET permission. No cloud. The recording buffer is held " +
                    "in RAM and erased the moment it's transcribed.",
        ),
    )
