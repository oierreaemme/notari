package com.voicenotemd.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.voicenotemd.app.navigation.VoiceNoteNavHost
import com.voicenotemd.core.common.repository.SettingsRepository
import com.voicenotemd.core.design.theme.VoiceNoteMarkdownTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Hosts the entire Compose tree.
 *
 * Extends [FragmentActivity] (not `ComponentActivity`) because `androidx.biometric`'s
 * BiometricPrompt requires a `FragmentActivity` host. The Compose interop is identical
 * — `setContent` is available on any ComponentActivity ancestor, which FragmentActivity
 * is, and edge-to-edge / splash screen / Hilt all keep working.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen MUST run before super.onCreate so it can swap the cold-start
        // window theme out for the SplashScreen-managed one. After this returns the theme
        // declared by `postSplashScreenTheme` (Theme.VoiceNoteMarkdown) is in effect.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // `remember` so the mapped Flow is created once, not rebuilt on every
            // recomposition. Without it, the downstream collectAsState() resets each
            // recomposition (lint: FlowOperatorInvokedInComposition). `settingsRepository`
            // is a stable injected singleton, so no remember key is needed.
            val lockRequiredFlow =
                remember { settingsRepository.observe().map { it.requireBiometricUnlock } }
            VoiceNoteMarkdownAppContent(
                lockRequiredFlow = lockRequiredFlow,
                showPrompt = ::showBiometricPrompt,
            )
        }
    }

    /**
     * Surface the system BiometricPrompt. We require BIOMETRIC_STRONG (Class 3) — the same
     * threshold used to gate the toggle in Settings — so the prompt won't accept weaker
     * authenticators that the user might not realize they enrolled.
     *
     * The `negativeButton` reads "Quit" because we deliberately do not provide a device-PIN
     * fallback: this gate is opt-in, and a user who chose biometric protection probably did
     * so to keep the notes off-limits even when the phone is unlocked. They can disable the
     * setting any time they like — but not from inside the locked app.
     */
    private fun showBiometricPrompt(onSuccess: () -> Unit) {
        val canAuth =
            BiometricManager.from(this)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // Defensive: the toggle should have been forced off by SettingsViewModel when
            // the device can't auth. If we somehow get here anyway, fail open so the user
            // isn't locked out forever. ADR 0013 calls this out explicitly.
            onSuccess()
            return
        }
        val prompt =
            BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onSuccess()
                    }
                    // We deliberately do nothing on error/failure — the gate stays in place
                    // and the user can retry via the "Try again" button.
                },
            )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.app_biometric_unlock_title))
                .setSubtitle(getString(R.string.app_biometric_unlock_subtitle))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(getString(R.string.app_biometric_quit))
                .build(),
        )
    }
}

@Composable
private fun VoiceNoteMarkdownAppContent(
    lockRequiredFlow: kotlinx.coroutines.flow.Flow<Boolean>,
    showPrompt: (onSuccess: () -> Unit) -> Unit,
) {
    VoiceNoteMarkdownTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            // `null` while we're loading the flag from DataStore. We refuse to render
            // anything during this brief window — better a blank surface for ~50ms than
            // a flash of notes followed by a lock prompt.
            val lockRequired by lockRequiredFlow.collectAsState(initial = null)
            var unlocked by remember { mutableStateOf(false) }
            val lifecycleOwner = LocalLifecycleOwner.current

            // Re-lock whenever the app leaves the foreground, and (re-)prompt when it comes
            // back. This makes an enabled biometric gate protect the notes after the app has
            // merely been backgrounded — not only on a cold start (ADR 0013: notes stay
            // off-limits even on an unlocked device). No-op while the gate is off.
            //
            // ON_STOP fires when the app is no longer visible (home, recents, another app, a
            // SAF picker, the share sheet). ON_START fires when it becomes visible again;
            // addObserver also replays the current state to a freshly-added observer, so the
            // first ON_START drives the initial prompt too — no separate launch needed. The
            // system BiometricPrompt is a dialog fragment that does NOT stop the activity, so
            // it cannot re-trigger itself.
            DisposableEffect(lifecycleOwner, lockRequired) {
                val observer =
                    LifecycleEventObserver { _, event ->
                        if (lockRequired == true) {
                            when (event) {
                                Lifecycle.Event.ON_STOP -> unlocked = false
                                Lifecycle.Event.ON_START -> if (!unlocked) showPrompt { unlocked = true }
                                else -> Unit
                            }
                        }
                    }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            when {
                lockRequired == null -> Unit // splash window covers this
                lockRequired == false -> VoiceNoteNavHost() // gate disabled
                unlocked -> VoiceNoteNavHost()
                else -> LockedGate(onRetry = { showPrompt { unlocked = true } })
            }
        }
    }
}

/**
 * The screen shown while the BiometricPrompt is up, and again if the user dismisses it
 * without authenticating. Intentionally minimal — the system prompt is the actual UI;
 * this surface is just what's behind it.
 */
@Composable
private fun LockedGate(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.app_locked_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.app_locked_subtitle),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRetry) { Text(stringResource(R.string.app_btn_try_again)) }
        }
    }
}
