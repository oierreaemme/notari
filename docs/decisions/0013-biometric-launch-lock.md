# 13. Optional biometric launch lock

Date: 2026-05-15
Status: Accepted

> **Update (2026-05-26):** The "encrypt the Room database" hardening pass
> mentioned under *Alternatives considered* is now decided in
> [ADR 0019](0019-encryption-at-rest-decoupled-from-biometric.md) — with a
> deliberate divergence: encryption is **always on and bound to a device-bound
> Keystore key**, not coupled to biometric auth. This lock stays exactly as
> described here: opt-in, UX-only, defending device-shared access. At-rest
> protection is now owned by ADR 0019, not by this lock.

> **Update (2026-06-10): the gate is now an overlay, not a NavHost
> replacement.** The original implementation swapped `VoiceNoteNavHost()` out
> of composition while locked. Combined with the re-lock-on-ON_STOP behavior
> (2026-05-31), engaging the gate destroyed the NavController and every
> back-stack entry's ViewModel. Two real-device bugs followed: screen-off
> mid-dictation discarded the recording in progress (CaptureViewModel
> cleared), and the ZIP export's SAF picker — which stops the activity —
> destroyed the ActivityResult callback, leaving a created-but-empty .zip.
> The NavHost is now always composed; `LockedGate` sits on top as an opaque,
> pointer-swallowing overlay. The threat model is unchanged (screen gate;
> FLAG_SECURE covers Recents/screenshots; at-rest = ADR 0019), but in-flight
> work now survives the lock engaging.

## Context

Notes contain meeting decisions, half-formed ideas, personal reflections — the
kind of content the user *intends* to keep private (see CLAUDE.md pillar 6).
A phone that's already unlocked, handed to a friend or sitting on a desk at
work, exposes the entire notes list to anyone who taps the app icon.

The privacy story so far covers data *in transit* (no network) and data *at
rest in the audio buffer* (never written to disk). It does **not** cover
*device-shared access*: anyone holding an unlocked phone is, today, fully
trusted by the app.

We want to close that hole without:
- Requiring a per-note password (annoying, abandoned by users).
- Building a custom PIN screen that we'd then have to secure ourselves.
- Making the app feel paranoid on day one (most users don't need this).

## Decision

Add an **opt-in** biometric unlock gate that:

1. Lives behind a toggle in Settings → Security. **Off by default.** The
   privacy promise of the app is real even when this toggle is off; the
   toggle adds *device-shared-access* protection on top.
2. Uses `androidx.biometric` (Class 3 / `BIOMETRIC_STRONG` only). Class 2
   and credential fallback are intentionally not enabled — anyone with
   knowledge of the device PIN should not get past the app's gate. If the
   user wants device-PIN-equivalent protection they have the OS-level
   lock-on-app-open feature in Android Settings; this is a stronger gate.
3. Fires the BiometricPrompt on every cold launch and on resume from
   background, whenever the toggle is on. State is reset in MainActivity
   via the `unlocked` flag, which is local to the activity scope.
4. Reads the toggle from `SettingsRepository.observe()` so the existing
   DataStore flow is the source of truth and a settings change is reflected
   immediately. The `lockRequired` flag is `null` while the first emission
   is in flight — during that window we render nothing (the splash window
   covers it visually) to prevent a flash of the notes list before the
   prompt appears.
5. **Self-disables when the device cannot enroll BIOMETRIC_STRONG.** This
   matters because a user could enable the toggle, then go to Android
   Settings and remove their fingerprint — at which point we'd hard-lock
   them out of their own notes with no recovery path. `SettingsViewModel`
   listens for `onBiometricAvailability(false)` and writes
   `setRequireBiometricUnlock(false)` so the next launch isn't gated.
6. **Fails open in the activity** if BiometricManager reports unavailable
   at gate time. This is the safety net for the race where DataStore says
   "locked = true" but the hardware says "can't authenticate". The Settings
   guard above should have caught it, but if it didn't, we prefer the user
   sees their notes over being locked out forever.

## Alternatives considered

**Encrypt the Room database with a biometric-protected key.** This is the
"correct" defence in depth — even a rooted attacker who reads the SQLite
file gets ciphertext. Rejected for v1 on two grounds: (a) `SQLCipher` is
heavy and pulls native code we don't currently link, (b) the threat model
we're actually defending is *shoulder access*, not forensic recovery. A
biometric gate on the activity is the proportionate response. Database
encryption is on the roadmap as a separate hardening pass; ADR 0013 does
not preclude it.

**Per-note lock.** Considered and rejected — adds friction without solving
the threat. If a casual observer is in the app at all, they can already
see *which notes exist*, *what their titles are*, and *what tags they
have*. Locking the body of individual notes leaks metadata while making
the app annoying to use.

**Always-on lock, no toggle.** Rejected because it makes first-launch
hostile to users who don't have the threat model. Privacy as a feature is
real because it's *trustworthy*, not because it's *imposing*.

## Consequences

- **MainActivity now extends `FragmentActivity`** (was `ComponentActivity`)
  because BiometricPrompt requires a FragmentActivity host. Splash screen,
  Hilt `@AndroidEntryPoint`, edge-to-edge, and Compose `setContent` all
  continue to work — FragmentActivity is a direct subclass of
  ComponentActivity, so the upgrade is transparent.
- **No new permission in the manifest.** `androidx.biometric` operates
  entirely via the framework's existing biometric capability and does not
  request a runtime permission on minSdk 28+. The "no INTERNET permission"
  CI check (ADR 0007) is unaffected.
- **`UserSettings.requireBiometricUnlock`** is a new persisted boolean.
  Default `false` so upgraded installs from a v0 → v1 transition see no
  behaviour change. The DataStore key
  (`require_biometric_unlock`) is brand-new; the existing keys remain
  unchanged, no migration step required.
- **Test impact** is contained: `PreferencesSettingsRepositoryTest`,
  `SettingsViewModelTest`, and the in-memory `SettingsRepository` fakes
  in `OnboardingViewModelTest` all gain the new method, with one new test
  per side: persistence round-trip, "respects available", "refuses when
  unavailable", and "auto-disables when device loses enrollment".
- **The lock is not a substitute for the device lock screen.** We document
  this explicitly in Settings → Security: a passing PIN/biometric at the
  Android level is required to reach the launcher icon in the first place;
  our gate is *in addition*, for the case where the device is already
  unlocked.

## References

- `core/common/src/main/java/com/voicenotemd/core/common/domain/UserSettings.kt` —
  the new persisted field.
- `core/datastore/src/main/java/com/voicenotemd/core/datastore/PreferencesSettingsRepository.kt` —
  the new `RequireBiometricUnlock` key + `setRequireBiometricUnlock`.
- `feature/settings/src/main/java/com/voicenotemd/feature/settings/SettingsRoute.kt` —
  the new Security section + `BiometricManager.canAuthenticate` probe.
- `feature/settings/src/main/java/com/voicenotemd/feature/settings/SettingsViewModel.kt` —
  the availability gate that auto-disables when biometrics go away.
- `app/src/main/java/com/voicenotemd/app/MainActivity.kt` — the
  FragmentActivity upgrade and the `LockedGate` Composable.
