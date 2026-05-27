package com.voicenotemd.core.asr

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Source-set-level guard for ADR 0002 pillar 2 (no audio persistence).
 *
 * Defence in depth — the canonical enforcement is `style.ForbiddenImport` in
 * `config/detekt/detekt.yml`, which blocks the imports at compile-time. This test
 * provides a second pass that also catches fully-qualified usages like
 * `android.media.MediaRecorder()` that bypass an import statement.
 *
 * The grep deliberately strips Kotlin comments before searching so that the *prose*
 * in this module's KDoc (which legitimately mentions these forbidden classes for
 * documentation purposes) does not trigger a false positive.
 */
class NoAudioPersistenceTest {
    private val sourceRoot: File =
        // Walk up from this test's working dir to the module root, then into main sources.
        File("src/main/java").takeIf { it.exists() }
            ?: File("core/asr/src/main/java").takeIf { it.exists() }
            ?: error("Could not locate :core:asr main sources from working dir: ${File(".").absolutePath}")

    @Test
    fun `no source file should reference MediaRecorder in code`() {
        assertNoMatch("MediaRecorder")
    }

    @Test
    fun `no source file should reference MediaMuxer in code`() {
        assertNoMatch("MediaMuxer")
    }

    @Test
    fun `no source file should reference MediaCodec in code`() {
        assertNoMatch("MediaCodec")
    }

    @Test
    fun `no source file should reference FileOutputStream in code`() {
        assertNoMatch("FileOutputStream")
    }

    @Test
    fun `no source file should reference RandomAccessFile in code`() {
        assertNoMatch("RandomAccessFile")
    }

    @Test
    fun `AudioRecord is referenced only in the designated capture session files`() {
        // Evolved guard (ADR 0018): the on-device capture paths own the mic, so AudioRecord
        // is permitted — but ONLY in the designated session files. The real invariant (no
        // persistence sink; buffer zeroed) is enforced by the other tests here. Owning a
        // RAM-only PCM buffer does not violate the cardinal rule or pillar 2.
        val offenders =
            sourceRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { stripComments(it.readText()).contains("AudioRecord") }
                .map { it.name }
                .toList()
        assertThat(offenders)
            .containsExactly("VoskSpeechToTextSession.kt", "BatchSpeechToTextSession.kt")
    }

    @Test
    fun `every capture session that owns the mic zeroes its PCM buffer`() {
        // The privacy contract for an owned buffer (ADR 0002 / ADR 0019): in-RAM PCM MUST be
        // overwritten with zeros before release. Assert a zeroing call exists in every file
        // that touches AudioRecord, so it cannot be silently dropped in a refactor.
        val captureFiles =
            sourceRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { stripComments(it.readText()).contains("AudioRecord") }
                .toList()
        assertThat(captureFiles).isNotEmpty()
        captureFiles.forEach { file ->
            assertThat(stripComments(file.readText())).contains(".fill(0)")
        }
    }

    private fun assertNoMatch(needle: String) {
        val offending =
            sourceRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { stripComments(it.readText()).contains(needle) }
                .map { it.relativeTo(sourceRoot).path }
                .toList()
        assertThat(offending).isEmpty()
    }

    /**
     * Remove Kotlin block comments (/* … */, including KDoc) and line comments (//) so that
     * intentional documentation mentioning the forbidden APIs (for the express purpose of
     * forbidding them) does not trip the check. We don't try to be a real Kotlin lexer —
     * a regex is enough for the cases this test cares about.
     */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
            .replace(Regex("(?m)//.*$"), " ")
}
