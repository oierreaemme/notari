package com.voicenotemd.core.database.security

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests for [DatabasePassphraseProvider] (ADR 0019).
 *
 * These run **on a device/emulator**, not under Robolectric: the provider wraps the DB
 * passphrase with an Android Keystore AES-GCM key, and Robolectric ships no shadow for the
 * `AndroidKeyStore` security provider (`KeyStore.getInstance("AndroidKeyStore")` throws
 * `NoSuchAlgorithmException`). Running instrumented exercises the real Keystore — including
 * the StrongBox-preferred / TEE-fallback path — which is the whole point of the design.
 *
 * The passphrase file is written to the test app's `filesDir`, redirected per-test by
 * [setUp]/[tearDown]. The Keystore key alias persists across runs (per the test app's uid);
 * `getOrCreateKey` is idempotent, so that is fine and intended.
 */
@RunWith(AndroidJUnit4::class)
class DatabasePassphraseProviderTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val encFile: File get() = File(context.filesDir, DatabasePassphraseProvider.ENC_FILE_NAME)

    @Before
    fun setUp() {
        encFile.delete()
    }

    @After
    fun tearDown() {
        encFile.delete()
    }

    @Test
    fun getPassphrase_returns32Bytes_onFirstCall() {
        val provider = DatabasePassphraseProvider(context)
        val passphrase = provider.getPassphrase()
        try {
            assertThat(passphrase).hasLength(32)
        } finally {
            passphrase.fill(0)
        }
    }

    @Test
    fun getPassphrase_persistsEncFile_afterFirstCall() {
        val provider = DatabasePassphraseProvider(context)
        assertThat(encFile.exists()).isFalse()
        provider.getPassphrase().fill(0)
        assertThat(encFile.exists()).isTrue()
    }

    @Test
    fun getPassphrase_returnsSameBytes_onRepeatedCalls() {
        val provider = DatabasePassphraseProvider(context)
        val first = provider.getPassphrase()
        val second = provider.getPassphrase()
        try {
            assertThat(first).isEqualTo(second)
        } finally {
            first.fill(0)
            second.fill(0)
        }
    }

    @Test
    fun getPassphrase_acrossProviderInstances_returnsSameBytes() {
        // Simulates app restart: two distinct provider instances, same enc file + Keystore key.
        val p1 = DatabasePassphraseProvider(context)
        val first = p1.getPassphrase()

        val p2 = DatabasePassphraseProvider(context)
        val second = p2.getPassphrase()

        try {
            assertThat(first).isEqualTo(second)
        } finally {
            first.fill(0)
            second.fill(0)
        }
    }

    @Test
    fun getPassphrase_differentContexts_generateDifferentPassphrases() {
        // Passphrase is random: two providers starting fresh in different dirs must differ.
        val dir2 = File(context.filesDir, "alt").also { it.mkdirs() }
        val ctx2 =
            object : android.content.ContextWrapper(context) {
                override fun getFilesDir(): File = dir2
            }
        val p1 = DatabasePassphraseProvider(context)
        val p2 = DatabasePassphraseProvider(ctx2)
        val a = p1.getPassphrase()
        val b = p2.getPassphrase()
        try {
            assertThat(a).isNotEqualTo(b)
        } finally {
            a.fill(0)
            b.fill(0)
            File(dir2, DatabasePassphraseProvider.ENC_FILE_NAME).delete()
        }
    }
}
