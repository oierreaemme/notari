package com.voicenotemd.core.database.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Provides the SQLCipher database passphrase, backed by an Android Keystore AES-GCM key.
 *
 * ### Design (ADR 0019)
 * - The key is **device-bound but not auth-bound**: no biometric requirement, no
 *   `setUserAuthenticationRequired`. The DB opens at any point the process runs on an
 *   unlocked device, with no Hilt / lifecycle ripple.
 * - StrongBox is requested first (Pixel 4+); falls back to TEE-backed Keystore silently.
 * - The random 32-byte passphrase is generated once, wrapped (AES-256-GCM) with the
 *   Keystore key, and persisted as `<filesDir>/db_passphrase.enc` (IV + ciphertext).
 * - [getPassphrase] returns the plaintext bytes. **Callers must zero the array after use.**
 *
 * ### File format of `db_passphrase.enc`
 * ```
 * [ iv_len : 1 byte ] [ iv : iv_len bytes ] [ ciphertext : remaining bytes ]
 * ```
 */
internal class DatabasePassphraseProvider(context: Context) {

    private val filesDir: File = context.filesDir
    private val encFile: File get() = File(filesDir, ENC_FILE_NAME)

    /**
     * Returns the plaintext DB passphrase (32 bytes).
     *
     * On first call: generates a random passphrase, wraps it with the Keystore key, and
     * persists the wrapped blob. On subsequent calls: unwraps the existing blob.
     *
     * The returned array is a fresh copy — zero it after passing to SupportOpenHelperFactory.
     */
    fun getPassphrase(): ByteArray {
        return if (encFile.exists()) {
            unwrap()
        } else {
            val passphrase = generateRandom()
            wrapAndStore(passphrase)
            passphrase
        }
    }

    // ── Key management ────────────────────────────────────────────────────────

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        return createKey()
    }

    private fun createKey(): SecretKey {
        // Attempt StrongBox (hardware security module) first; fall back to TEE.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateKey(strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                Log.i(TAG, "StrongBox unavailable — falling back to TEE-backed key")
            } catch (_: Exception) {
                Log.i(TAG, "StrongBox keygen failed — falling back to TEE-backed key")
            }
        }
        return generateKey(strongBox = false)
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && strongBox) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    // ── Wrap / unwrap ─────────────────────────────────────────────────────────

    private fun wrapAndStore(passphrase: ByteArray) {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(AES_GCM_NO_PADDING).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val iv = cipher.iv              // 12 bytes for GCM
        val ciphertext = cipher.doFinal(passphrase)
        // Format: [iv_len (1 byte)][iv][ciphertext]
        val blob = ByteArray(1 + iv.size + ciphertext.size)
        blob[0] = iv.size.toByte()
        iv.copyInto(blob, destinationOffset = 1)
        ciphertext.copyInto(blob, destinationOffset = 1 + iv.size)
        encFile.writeBytes(blob)
    }

    private fun unwrap(): ByteArray {
        val key = getOrCreateKey()
        val blob = encFile.readBytes()
        val ivLen = blob[0].toInt() and 0xFF
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val ciphertext = blob.copyOfRange(1 + ivLen, blob.size)
        val cipher = Cipher.getInstance(AES_GCM_NO_PADDING).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(ciphertext)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun generateRandom(): ByteArray =
        ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }

    companion object {
        private const val TAG = "DbPassphraseProvider"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "notari_db_key_v1"
        private const val KEY_SIZE_BITS = 256
        private const val AES_GCM_NO_PADDING = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PASSPHRASE_BYTES = 32
        internal const val ENC_FILE_NAME = "db_passphrase.enc"
    }
}
