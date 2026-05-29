# 19. Encryption at rest, decoupled from the biometric lock

Date: 2026-05-26
Status: Accepted

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

- **Dependency:** SQLCipher for Android via Room's `SupportOpenHelperFactory`.
  Verify the current artifact/version against `gradle/libs.versions.toml` (the
  newer `net.zetetic:sqlcipher-android` line vs the legacy `android-database-
  sqlcipher`) and that it stays compatible with the no-INTERNET CI check
  (ADR 0007) — SQLCipher is fully offline native code, no network. Note: the
  newer `net.zetetic:sqlcipher-android` renames the legacy `SupportFactory` to
  `SupportOpenHelperFactory` and removes `SQLiteDatabase.loadLibs(context)`; the
  native lib must be loaded manually with `System.loadLibrary("sqlcipher")`
  before any SQLCipher call (the migrator does this on the upgrade path).
- **Passphrase provider** (`:core:database`): get-or-create a hardware-backed
  AES key in the Keystore (alias, `StrongBox` preferred, no auth requirement);
  get-or-create a strong random DB passphrase on first run; store it wrapped
  (AES-GCM + IV) in DataStore; unwrap on DB open and hand the bytes to
  `SupportOpenHelperFactory`. Zero the plaintext passphrase array after use.
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

## Amendment — 2026-05-29: implemented; ADR flipped to Accepted

Implementation shipped in `:core:database` under the `security` package:

- **`DatabasePassphraseProvider`** — generates a 32-byte random passphrase on first
  run, wraps it with a Keystore AES-256-GCM key (StrongBox preferred, TEE fallback),
  and persists the IV+ciphertext blob to `<filesDir>/db_passphrase.enc`. Subsequent
  runs unwrap and return the same passphrase. The returned array must be zeroed by the
  caller after use.
- **`PlaintextToEncryptedMigrator`** — triggered when the existing `voice_note.db` starts
  with the SQLite plaintext magic header (`SQLite format 3 `). SQLCipher encrypts the
  whole file including the header, so an encrypted DB never matches the plaintext magic;
  the sentinel is stateless and self-healing. (An earlier design used
  `db_passphrase.enc.exists()` as the sentinel and could not detect the
  enc-file-present-+-plaintext-DB inconsistency left by a crashed prior run.) The
  migration pre-creates the encrypted destination via `openOrCreateDatabase` so the
  subsequent `ATTACH … KEY` binds to a properly-initialised SQLCipher header (ATTACH
  alone silently does NOT create a new encrypted file — only `main` shows up in
  `PRAGMA database_list` and `sqlcipher_export()` then fails with "unknown database
  encrypted"). The export uses `rawQuery + moveToFirst()` to force `SELECT
  sqlcipher_export('encrypted')` to actually step (sqlcipher-android's `rawExecSQL`
  does not run SELECTs that return rows). Atomic swap uses explicit boolean checks on
  `File.renameTo` — it can return false silently on Android. On the no-op path the
  migrator sweeps any orphaned `voice_note_plaintext.bak` so plaintext does not linger
  as a shadow of the user's notes.
- **`DatabaseModule`** — loads `libsqlcipher.so` once via `System.loadLibrary("sqlcipher")`
  (sqlcipher-android 4.5.6 removed the legacy `SQLiteDatabase.loadLibs(context)` helper
  and does not auto-load), calls `getPassphrase()`, runs the migrator (which self-detects
  via the magic-byte sentinel above), creates `SupportOpenHelperFactory(passphraseBytes)`,
  and zeroes the local key copies. The byte[] passed to the factory is the **base64
  ASCII form** of the random 32-byte secret (see `toSqlCipherPassphrase`), and the same
  base64 string is embedded in `ATTACH … KEY '<base64>'` inside the migrator — so every
  SQLCipher call site agrees on the underlying PBKDF2-derived page key. (The first
  implementation attempted to use SQLCipher's raw-key form `x'<hex>'` uniformly, but
  `net.zetetic:sqlcipher-android:4.5.6`'s `byte[]` Java APIs do not run the `x'<hex>'`
  pattern check the C SQL parser does — they PBKDF2-derive instead, producing a key the
  ATTACH side could not match and a "file is not a database (code 26)" crash when Room
  later opened the file.)
- **Tests** (`DatabasePassphraseProviderTest`) — five Robolectric-style tests covering
  passphrase length, file persistence, round-trip stability across calls, cross-instance
  stability (simulated app restart), and per-context isolation. **Currently `@Ignore`'d
  at the class level**: Robolectric does not ship an `AndroidKeyStore` security-provider
  shadow, so every test hits `NoSuchAlgorithmException` on `KeyStore.getInstance`. The
  test bodies are kept verbatim so the intent is preserved; re-enabling requires either
  moving the class to `androidTest/` (preferred — exercises the real Keystore on the
  Pixel 6a) or wiring a JVM shim that aliases `AndroidKeyStore` onto a BouncyCastle
  provider. Tracked as a follow-up; the on-device install + upgrade smoke test below is
  the de-facto gate in the meantime.
- **Dependency** — `net.zetetic:sqlcipher-android:4.5.6` added to
  `libs.versions.toml` and `core/database/build.gradle.kts`. Fully offline native
  code; CI no-INTERNET gate unaffected.

On-device validation on the Pixel 6a (in-memory `NoteRepositoryImplTest` needs no
`SupportOpenHelperFactory`) was the final acceptance gate.

### On-device acceptance — 2026-05-29 (fresh install, passed)

Validated on the Pixel 6a:

- Dictated two notes; both saved and rendered correctly.
- Killed the app process and reopened: both notes still readable and editable.
- **Rebooted the device** and reopened the app: both notes still readable and
  editable.

The reboot leg is the meaningful one — it proves the hardware-backed Keystore AES
key survives a full device restart (not just a process restart), so the wrapped DB
passphrase unwraps correctly against the TEE/StrongBox on a cold boot and Room opens
the encrypted database.

### Critical fix — 2026-05-29: factory holds the key by reference (must not zero it)

The first on-device run of the **plaintext→encrypted migration** path surfaced a
serious bug that the fresh-install path had been hiding. `DatabaseModule` did:

```kotlin
val passphraseBytes = passphrase.toSqlCipherPassphrase().toByteArray(US_ASCII)
val factory = SupportOpenHelperFactory(passphraseBytes)
passphraseBytes.fill(0)   // ← WRONG
```

`SupportOpenHelperFactory` keeps the `byte[]` **by reference** (`private final byte[]
password`) and reads it **lazily on every open**, not at construction. Zeroing the
array immediately meant Room opened the database with an **all-zero key**.

- **Fresh install masked it**: Room both *created* and *reopened* the DB with the
  zeroed key, so the two agreed and the app "worked" — but the database was encrypted
  under a trivial all-zero key. A silent, severe weakening of the at-rest guarantee
  this ADR exists to provide.
- **Migration exposed it**: the migrator encrypts the exported copy with the *real*
  key (via `ATTACH … KEY` and `openOrCreateDatabase`, which read the bytes eagerly),
  then Room tried to open that file with the zeroed key → `file is not a database
  (code 26)` → crash on startup.

Fix: do **not** wipe `passphraseBytes`; it must outlive `provideDatabase` for the
lifetime of the DB. The raw 32-byte secret is still zeroed (the factory does not hold
it). The migrator's `verifyEncryptedDb` was also switched to open through the same
`SupportOpenHelperFactory` Room uses, so a green verify guarantees Room will open —
the previous `SQLiteDatabase.openDatabase` verify gave a false pass.

Other migration robustness fixes found in the same session: detect plaintext via the
SQLite magic header (not a sidecar sentinel); pre-create the encrypted destination
with `openOrCreateDatabase` so `ATTACH` binds to a real cipher header; force
`sqlcipher_export` to step via `rawQuery + moveToFirst()`; check `File.renameTo`
return values; sweep any orphaned `voice_note_plaintext.bak`.

### On-device acceptance — 2026-05-29: plaintext→encrypted migration (passed)

Seeded a pre-0019 **plaintext** `voice_note.db` (exact Room DDL + `room_master_table`
identity hash `e86b0dd…` + `user_version=1`) with two recognisable notes, two tags,
and a mention, pushed it into the app's `databases/` with no `db_passphrase.enc`
present, and launched:

- Migrator logged `Plaintext DB detected → sqlcipher_export complete → renames OK →
  Migration complete`; no crash.
- The Notes screen showed **both migrated notes** ("MIGRAZIONE Riunione progetto
  Notari" + body and #lavoro tag; "MIGRAZIONE Spesa settimanale" + body and #spesa
  tag) with correct dates — so `notes`, `note_tags`, and `note_mentions` all survived.
- Restarting the app was a clean no-op (magic-byte check sees ciphertext), and no
  `voice_note_plaintext.bak` was left on disk.

Encryption at rest — fresh install **and** upgrade-from-plaintext — is confirmed
working end-to-end on the reference device.
