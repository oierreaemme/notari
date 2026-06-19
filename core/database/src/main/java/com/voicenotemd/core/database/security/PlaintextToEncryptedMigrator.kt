package com.voicenotemd.core.database.security

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.voicenotemd.core.database.VoiceNoteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/*
 * One-time migration from the plaintext `voice_note.db` (pre-ADR-0019 installs) to
 * an SQLCipher-encrypted copy, using SQLCipher's built-in `sqlcipher_export()` pragma.
 *
 * ### Trigger condition
 * The DB file exists AND starts with the SQLite plaintext magic header
 * (`SQLite format 3\u0000`). SQLCipher encrypts the entire file including the header,
 * so an encrypted DB never matches the plaintext magic — making the check a precise
 * "plaintext or not" signal regardless of any sidecar file state.
 *
 * ### Why magic bytes instead of a "passphrase file present?" sentinel
 * An earlier design used `db_passphrase.enc` existence as the sentinel: if the enc file
 * already existed, skip migration. That coupled the DB's state to the passphrase
 * provider's side-effect, and any prior failed run (the provider had written the enc
 * file but the migration then crashed) left an inconsistent state — enc file present
 * + plaintext DB — that the sentinel could no longer detect. Reading the DB header is
 * stateless and self-healing.
 *
 * ### Algorithm
 * 1. Open the plaintext DB via SQLCipher (empty passphrase = unencrypted).
 * 2. ATTACH a temp encrypted DB alongside it using the new passphrase.
 * 3. `SELECT sqlcipher_export('encrypted')` — copies every table, index, and trigger.
 * 4. DETACH the encrypted copy.
 * 5. Rename: plaintext → `.bak`, encrypted temp → `voice_note.db`.
 * 6. Verify the encrypted DB opens cleanly; only then delete the backup.
 *
 * A crash between steps 5 and 6 leaves the `.bak` file. The next startup's magic-byte
 * check sees an encrypted `voice_note.db` and skips migration (Room opens it directly);
 * stale `.bak` is left for manual cleanup but does not block the app.
 */

/**
 * Encode a raw 32-byte secret as a SQLCipher *passphrase string* — a base64 ASCII string
 * with no padding/newlines, safe to embed inside a single-quoted SQL literal (`KEY
 * 'base64'`) AND to pass to the `SupportOpenHelperFactory(byte[])` and
 * `SQLiteDatabase.openDatabase(path, byte[], …)` Java APIs.
 *
 * ### Why a base64 *passphrase* and not the raw 32 bytes (or the `x'<hex>'` raw-key form)
 * SQLCipher accepts two key styles: a *passphrase* (any bytes → PBKDF2-derived key) and a
 * *raw key* (the literal text `x'<hex>'`, used as the page key with no derivation). The
 * raw-key form is detected by the C layer's SQL parser when it sees `KEY "x'…'"`, **but
 * `net.zetetic:sqlcipher-android:4.5.6`'s `byte[]` Java APIs do NOT run the same pattern
 * check on the bytes they receive** — every byte[] passed to `SupportOpenHelperFactory`
 * or `openDatabase(path, byte[], …)` is treated as a passphrase and PBKDF2-derived.
 *
 * That produced a real bug on-device: opening an encrypted file via Room's factory with
 * `x'<hex>'.toByteArray()` PBKDF2-derived a key from those 67 ASCII bytes, while the file
 * itself had been written by an `ATTACH ... KEY "x'<hex>'"` statement using the raw 32
 * bytes. The keys did not match and Room crashed with "file is not a database (code 26)".
 *
 * Standardising on PBKDF2 with a base64 passphrase string removes the asymmetry:
 *
 *  * `KEY '<base64>'` in SQL — string passphrase, PBKDF2-derived.
 *  * `SupportOpenHelperFactory('<base64>'.toByteArray())` — same passphrase, same PBKDF2.
 *  * `SQLiteDatabase.openOrCreateDatabase(path, '<base64>'.toByteArray(), …)` — same.
 *
 * Base64 also has no characters that need SQL-escaping (only `A–Z a–z 0–9 + / =`), so
 * `KEY 'value'` is always safe.
 */
internal fun ByteArray.toSqlCipherPassphrase(): String =
    Base64.encodeToString(this, Base64.NO_WRAP or Base64.NO_PADDING)

internal class PlaintextToEncryptedMigrator(private val context: Context) {
    /**
     * Migrates the plaintext DB to encrypted **only if** a plaintext SQLite file is present
     * at the target location. No-op on fresh installs (no DB file yet) and on already-encrypted
     * DBs (header doesn't match plaintext magic). Safe to call on every startup.
     */
    fun migrateIfNeeded(passphrase: ByteArray) {
        val dbFile = context.getDatabasePath(VoiceNoteDatabase.DATABASE_NAME)
        if (!dbFile.exists()) return // fresh install — Room will create encrypted
        if (!isPlaintextSqlite(dbFile)) {
            // Already encrypted. Sweep any leftover plaintext .bak from a prior migration
            // run before returning — leaving it on disk would defeat the whole point of
            // encrypting at rest (ADR 0019). Safe: by definition the encrypted dbFile is
            // the source of truth at this point.
            sweepStalePlaintextBackup(dbFile)
            return
        }

        Log.i(TAG, "Plaintext DB detected; starting one-time migration to encrypted.")
        // Note: System.loadLibrary("sqlcipher") is called unconditionally in DatabaseModule
        // before this migrator is invoked — no need to repeat it here.
        val tempFile = File(dbFile.parent, TEMP_DB_NAME)
        val backupFile = File(dbFile.parent, BACKUP_DB_NAME)

        // Pre-clean any stale artefacts from prior interrupted runs so SQLCipher's
        // sqlcipher_export() does not attempt to ATTACH to a pre-existing file.
        if (tempFile.exists()) {
            Log.i(TAG, "stale tempFile from prior run — deleting before migration.")
            tempFile.delete()
            cleanupSidecarFiles(tempFile)
        }
        if (backupFile.exists()) {
            Log.i(TAG, "stale backupFile from prior run — deleting before migration.")
            backupFile.delete()
            cleanupSidecarFiles(backupFile)
        }

        try {
            migratePlaintextToEncrypted(dbFile, tempFile, passphrase)
            Log.i(TAG, "sqlcipher_export complete (tempFile=${tempFile.length()} bytes).")

            // The plaintext open by SQLCipher above leaves -shm/-wal sidecars next to
            // dbFile. Clear them BEFORE renaming so the rename moves only the main file
            // and the sidecars don't ride along to corrupt the backup view of plaintext.
            cleanupSidecarFiles(dbFile)

            // Atomic swap: plaintext → backup, temp → production. File.renameTo() can
            // return false on Android without throwing (e.g., if the destination exists
            // on some filesystems); check explicitly so a silent failure cannot leave
            // the plaintext live while verifyEncryptedDb opens it as if it were ciphertext.
            if (!dbFile.renameTo(backupFile)) {
                throw IllegalStateException(
                    "renameTo failed: dbFile→backupFile " +
                        "(dbFile.exists=${dbFile.exists()}, backupFile.exists=${backupFile.exists()})",
                )
            }
            if (!tempFile.renameTo(dbFile)) {
                throw IllegalStateException(
                    "renameTo failed: tempFile→dbFile " +
                        "(tempFile.exists=${tempFile.exists()}, dbFile.exists=${dbFile.exists()})",
                )
            }
            Log.i(TAG, "renames OK; verifying encrypted DB opens with the passphrase.")

            verifyEncryptedDb(dbFile, passphrase)
            backupFile.delete()
            cleanupSidecarFiles(backupFile)
            Log.i(TAG, "Migration complete.")
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed — recovering plaintext DB.", e)
            tempFile.delete()
            cleanupSidecarFiles(tempFile)
            // If the swap completed before we threw, restore the plaintext so the next
            // startup sees the original DB and retries (or surfaces the error cleanly).
            if (!dbFile.exists() && backupFile.exists()) {
                backupFile.renameTo(dbFile)
            }
            throw IllegalStateException("DB encryption migration failed", e)
        }
    }

    private fun migratePlaintextToEncrypted(
        plaintextFile: File,
        encryptedOut: File,
        passphrase: ByteArray,
    ) {
        val passphraseString = passphrase.toSqlCipherPassphrase()
        val passphraseBytes = passphraseString.toByteArray(Charsets.US_ASCII)

        // Step 1 — Pre-create the destination as a properly-initialised encrypted SQLite DB.
        // SQLCipher will NOT create a brand-new encrypted file via ATTACH alone: an ATTACH to a
        // non-existent path returns successfully but silently does not register the alias (only
        // `main` ends up in PRAGMA database_list), and sqlcipher_export() then fails with
        // "unknown database encrypted". Opening the file via openOrCreateDatabase with the
        // passphrase writes the SQLCipher header so the subsequent ATTACH finds an actual
        // encrypted SQLite file to bind to.
        SQLiteDatabase.openOrCreateDatabase(
            encryptedOut.absolutePath,
            passphraseBytes,
            null,
            null,
        ).close()

        // Step 2 — Open the plaintext source (empty passphrase = unencrypted) and export.
        val plainDb =
            SQLiteDatabase.openDatabase(
                plaintextFile.absolutePath,
                ByteArray(0),
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null,
                null,
            )
        try {
            // ATTACH ... KEY '<base64>' — the same base64 passphrase the byte[] APIs use, so
            // both code paths PBKDF2-derive the same page key. Base64 has no SQL-special
            // characters, so single-quote string delimitation is safe.
            plainDb.rawExecSQL(
                "ATTACH DATABASE '${encryptedOut.absolutePath}' AS encrypted KEY '$passphraseString'",
            )
            // sqlcipher_export() is a SELECT — rawExecSQL on sqlcipher-android does NOT step a
            // query that returns rows. Use rawQuery + moveToFirst() to force execution.
            plainDb.rawQuery("SELECT sqlcipher_export('encrypted')", null).use { cursor ->
                cursor.moveToFirst()
            }
            plainDb.rawExecSQL("DETACH DATABASE encrypted")
        } finally {
            plainDb.close()
        }
    }

    private fun verifyEncryptedDb(
        dbFile: File,
        passphrase: ByteArray,
    ) {
        // Verify through the SAME path Room will use to open the DB — SupportOpenHelperFactory,
        // not the lower-level SQLiteDatabase.openDatabase. Both decrypt with the same key and
        // cipher params, but verifying via the production factory makes this check faithful to
        // exactly what Room does at first DAO access (down to the SupportSQLiteOpenHelper /
        // SupportSQLiteDriver plumbing). A green verify here means Room will open cleanly.
        val keyBytes = passphrase.toSqlCipherPassphrase().toByteArray(Charsets.US_ASCII)
        val cfg =
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.name) // resolved under databases/ → the same dbFile
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(VoiceNoteDatabase.SCHEMA_VERSION) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build()
        val helper = SupportOpenHelperFactory(keyBytes).create(cfg)
        try {
            helper.writableDatabase.query("SELECT count(*) FROM sqlite_master").use { cursor ->
                check(cursor.moveToFirst()) { "sqlite_master query failed on encrypted DB" }
            }
        } finally {
            helper.close()
        }
    }

    /**
     * Delete a `voice_note_plaintext.bak` left behind by a prior migration run. This is
     * called on the no-op path (the DB is already encrypted) so a backup from any earlier
     * attempt — even one that completed before this sweep was added — does not linger as
     * a plaintext shadow of the user's notes.
     */
    private fun sweepStalePlaintextBackup(dbFile: File) {
        val backup = File(dbFile.parent, BACKUP_DB_NAME)
        if (backup.exists()) {
            Log.i(TAG, "sweeping stale plaintext backup left by a prior migration.")
            backup.delete()
            cleanupSidecarFiles(backup)
        }
    }

    /** SQLite writes -wal and -shm sidecar files; clean them up from the backup. */
    private fun cleanupSidecarFiles(base: File) {
        File("${base.absolutePath}-wal").delete()
        File("${base.absolutePath}-shm").delete()
    }

    /**
     * Returns true if [file] starts with the SQLite plaintext magic header
     * (`SQLite format 3\u0000`, 16 bytes). SQLCipher encrypts the entire file including
     * the header, so an encrypted DB never matches.
     */
    private fun isPlaintextSqlite(file: File): Boolean {
        if (file.length() < SQLITE_MAGIC.size) return false
        return file.inputStream().use { stream ->
            val header = ByteArray(SQLITE_MAGIC.size)
            val read = stream.read(header)
            read == SQLITE_MAGIC.size && header.contentEquals(SQLITE_MAGIC)
        }
    }

    companion object {
        private const val TAG = "PlaintextToEncryptedMigrator"
        private const val TEMP_DB_NAME = "voice_note_enc_tmp.db"
        private const val BACKUP_DB_NAME = "voice_note_plaintext.bak"

        // "SQLite format 3\u0000" — every unencrypted SQLite database starts with this
        // 16-byte ASCII header. See https://www.sqlite.org/fileformat.html § 1.3.
        private val SQLITE_MAGIC =
            byteArrayOf(
                0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66,
                0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33, 0x00,
            )
    }
}
