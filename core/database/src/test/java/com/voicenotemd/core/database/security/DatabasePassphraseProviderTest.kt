package com.voicenotemd.core.database.security

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Unit tests for [DatabasePassphraseProvider].
 *
 * **Currently @Ignore'd** — every test path here exercises `KeyStore.getInstance("AndroidKeyStore")`,
 * but Robolectric does NOT ship a shadow for the `AndroidKeyStore` security provider, so the JVM
 * call fails with `NoSuchAlgorithmException`. An earlier KDoc on this file optimistically claimed
 * Robolectric stubs it; that claim was wrong and is now removed.
 *
 * Two viable paths to re-enable:
 *
 *  1. Move this class to `androidTest/` (instrumented) so it runs against the real Android
 *     KeyStore on an emulator/device. This is the canonical fix; the trade-off is that
 *     instrumented tests are not part of `./gradlew test` and need an emulator on CI.
 *  2. Register a JVM shim that aliases `AndroidKeyStore` onto a BouncyCastle (or in-memory)
 *     provider in a `@Before` block. Keeps the tests on the JVM but is fragile — the shim
 *     does not exercise the StrongBox / TEE behaviour that matters.
 *
 * Until one of those lands, the encryption-at-rest path is covered by on-device acceptance
 * (Pixel 6a fresh install + plaintext-to-encrypted upgrade smoke test) called out as the
 * gating follow-up in ADR 0019.
 */
@RunWith(RobolectricTestRunner::class)
@Ignore("AndroidKeyStore not provided by Robolectric; see class KDoc and ADR 0019 follow-ups.")
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
    fun `getPassphrase returns 32 bytes on first call`() {
        val provider = DatabasePassphraseProvider(context)
        val passphrase = provider.getPassphrase()
        try {
            assertThat(passphrase).hasLength(32)
        } finally {
            passphrase.fill(0)
        }
    }

    @Test
    fun `getPassphrase persists enc file after first call`() {
        val provider = DatabasePassphraseProvider(context)
        assertThat(encFile.exists()).isFalse()
        provider.getPassphrase().fill(0)
        assertThat(encFile.exists()).isTrue()
    }

    @Test
    fun `getPassphrase returns same bytes on repeated calls (round-trip)`() {
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
    fun `getPassphrase across provider instances returns same bytes`() {
        // Simulates app restart: two distinct provider instances, same enc file.
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
    fun `getPassphrase generates different passphrases for different contexts (dirs)`() {
        // Passphrase is random: two providers starting fresh in different dirs must differ.
        val dir2 = File(context.filesDir, "alt").also { it.mkdirs() }
        val ctx2 = object : android.content.ContextWrapper(context) {
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
