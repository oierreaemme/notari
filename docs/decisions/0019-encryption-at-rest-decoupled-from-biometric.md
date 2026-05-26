# 19. Encryption at rest, decoupled from the biometric lock

Date: 2026-05-26
Status: Proposed

## Context

An external review (Gemini, 2026-05-26, same engagement as the ADR 0017
review) flagged the biometric launch lock as "security theatre": the lock
(ADR 0013) is a UI gate only, while the Room database (`voice_note.db`) sits
in plaintext in the app's data directory. An attacker with filesystem access
(ADB backup, a rooted/lost device, forensic extraction) reads every note
regardless of the lock.

The diagnosis is accurate, with a caveat the review itself later accepted:
the privacy pillar (ADR 0002, CLAUDE.md §3) is about *no network
exfiltration* (the audio and data never leave the device) — it never
promised encryption at rest. ADR 0013 was explicit that the lock defends
*device-shared access* (shoulder-surfing), not forensic recovery, and
explicitly left database encryption as a future "separate hardening pass."
This ADR is that pass.

The real issue is one of honesty: an app that presents a biometric lock
*implies* at-rest protection it does not deliver. We close the gap with
actual cryptography.

Two designs were on the table:

- **(A) Couple encryption to biometric** (the review's detailed proposal): a
  Keystore AES key with `setUserAuthenticationRequired(true)`, unlocked via a
  `BiometricPrompt.CryptoObject`, used to decrypt the SQLCipher passphrase.
- **(B) Encrypt always, with a device-bound key; keep biometric as a separate
  optional UX gate.**

## Decision

**Adopt (B): the Room database is encrypted at rest unconditionally with
SQLCipher, using a random passphrase protected by a hardware-backed Android
Keystore AES key that does _not_ require user authentication. Encryption is
always on, regardless of whether the user enabled the biometric lock.**

The biometric lock (ADR 0013) is unchanged: it stays an opt-in, UX-only gate
in `MainActivity`. Crucially, **it is already UX-only today**, so this change
adds encryption *underneath* without touching the lock, the Hilt graph's
startup behaviour, or `MainActivity`. We update the Privacy screen copy so
the two concerns are described honestly and separately:

- *Always:* notes are encrypted at rest; the key never leaves secure hardware.
- *Optional:* a biometric screen lock, on top, for device-shared access.

### Why decouple (why not the review's auth-bound key)

1. **No catastrophic data loss.** An auth-bound key is permanently
   invalidated (`KeyPermanentlyInvalidatedException`) when the user adds or
   removes a fingerprint. With the DB passphrase wrapped by that key, an
   enrollment change would render every note unrecoverable. For a personal
   daily-notes tool that the author uses for real content, silent total loss
   on a fingerprint re-enroll is unacceptable. `setInvalidatedByBiometric-
   Enrollment(false)` mitigates but still couples crypto to a control that
   does not need to gate it.
2. **No lifecycle / Hilt ripple.** A device-bound key (no user-auth
   requirement) is usable whenever the app process runs on an unlocked
   device, so Room opens normally at startup. An auth-bound key would forbid
   opening the DB before a biometric prompt, forcing a lazy-post-unlock
   re-architecture of the database provider.
3. **Always protected.** Data is encrypted even for users who never turn on
   the lock — the strongest posture and the one that matches the product's
   promise. Coupling would leave the DB plaintext whenever the toggle is off.
4. **No toggle migrations.** With always-on encryption there is no
   plaintext↔encrypted migration each time the biometric toggle flips.

### Threat model and honest trade-off

A key without a user-auth requirement is usable by the app process whenever
the device is unlocked. This design therefore protects against **offline
extraction** — pulling `voice_note.db`, ADB/auto-backup exfiltration, a
lost/stolen device with a locked screen, forensic imaging — because the
hardware-backed key never leaves the TEE/StrongBox and the ciphertext is
useless without it. It does **not** defend against an attacker who achieves
code execution *as the app* on an unlocked device. That is the proportionate
threat model for a personal notes app and is a material, real improvement
over today's plaintext database. We request StrongBox where available and
fall back to TEE-backed Keystore otherwise.

## Alternatives considered

- **(A) Biometric-coupled / auth-bound key.** Rejected for the data-loss and
  lifecycle reasons above. Stronger on paper against a very narrow threat
  (live device, screen unlocked, attacker present) at the cost of a real
  risk of destroying the user's own data. Wrong trade for this product.
- **EncryptedSharedPreferences / Jetpack Security for "the data".** Does not
  encrypt the SQLite database itself; it only protects key-value prefs.
  SQLCipher is the right tool for the DB. (Jetpack Security's crypto is still
  used conceptually for wrapping the passphrase.)
- **No encryption, just honest Privacy copy.** Rejected: with the device-bound
  design the cost is low and the protection is genuine, so closing the gap is
  worth it rather than merely disclaiming it.
- **Encrypt DataStore (settings) too.** Not needed: settings hold only
  non-sensitive prefs (theme, language pin, `require_biometric_unlock`). We
  confirm nothing sensitive lands there and leave it unencrypted. The wrapped
  DB passphrase is stored as ciphertext only.

## Implementation notes

- **Dependency:** SQLCipher for Android via Room's `SupportFactory`. Verify
  the current artifact/version against `gradle/libs.versions.toml` (the newer
  `net.zetetic:sqlcipher-android` line vs the legacy `android-database-
  sqlcipher`) and that it stays compatible with the no-INTERNET CI check
  (ADR 0007) — SQLCipher is fully offline native code, no network.
- **Passphrase provider** (`:core:database`): get-or-create a hardware-backed
  AES key in the Keystore (alias, `StrongBox` preferred, no auth requirement);
  get-or-create a strong random DB passphrase on first run; store it wrapped
  (AES-GCM + IV) in DataStore; unwrap on DB open and hand the bytes to
  `SupportFactory`. Zero the plaintext passphrase array after use.
- **minSdk 28 safe:** because the key is *not* auth-bound, none of the
  API-30+ auth-parameter calls are needed; key generation stays on the
  28-compatible path.
- **Existing-data migration:** installs already have a plaintext
  `voice_note.db`. On first run after this ships, migrate it to encrypted via
  SQLCipher's `sqlcipher_export()` (open plaintext → `ATTACH` encrypted →
  export → swap), guarded so it runs exactly once and preserves all notes.
  A failed/interrupted migration must not lose the plaintext source until the
  encrypted copy is verified.
- **`MainActivity` / biometric: untouched.** ADR 0013 stands as-is.
- **Backup:** the encrypted DB is already excluded from auto-backup (CLAUDE.md
  edge case); even a leaked backup is ciphertext with an un-restorable key.
- **Tests (data layer = TDD, CLAUDE.md §7):** passphrase provider round-trip;
  open/read/write through the encrypted DB (Robolectric/instrumented);
  migration test proving plaintext→encrypted preserves every note;
  `NoAudioPersistenceTest` unaffected. Coverage threshold on `:core:database`
  must hold.

## Consequences and trade-offs

- The privacy promise becomes a cryptographic guarantee at rest, not a
  perception. The biometric lock is now described honestly as a screen gate.
- Per-operation crypto overhead on DB I/O. Negligible for note CRUD even on
  the Pixel 6a reference device; to be confirmed, not optimised pre-emptively.
- `:core:database` gains native code (SQLCipher) and a Keystore dependency.
  App size grows modestly; acceptable against the existing >1 GB LLM bundle.
- ADR 0013's "Alternatives considered → encrypt the DB" is now realised, with
  a deliberate divergence: device-bound, always-on, **not** biometric-coupled.

## Follow-ups

- Implement and validate on the Pixel 6a: confirm the key lands in TEE/
  StrongBox, the one-time migration preserves existing notes, and there is no
  perceptible I/O regression. **Flip this ADR to Accepted on merge.**
- Update CLAUDE.md §3/§6 privacy wording and the Settings → Privacy copy to
  state "encrypted at rest, always" distinctly from the optional lock.
- Amend ADR 0013 with a forward link (done alongside this ADR).
- Unrelated but adjacent (tracked separately): add logging to the empty
  `catch (_: Exception) {}` blocks in `AndroidSpeechToTextSession` per the
  review's point A and CLAUDE.md §15.
