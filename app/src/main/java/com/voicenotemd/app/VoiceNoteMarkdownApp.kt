package com.voicenotemd.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Process-wide entry point.
 *
 * No analytics SDK, no crash reporter, no remote config — by design. See
 * docs/decisions/0002-privacy-enforcement.md.
 */
@HiltAndroidApp
class VoiceNoteMarkdownApp : Application()
