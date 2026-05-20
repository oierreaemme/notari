package com.voicenotemd.core.inference.prompteval

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.domain.Tag
import com.voicenotemd.core.common.result.DomainResult
import com.voicenotemd.core.inference.prompt.PromptTemplate
import com.voicenotemd.core.inference.schema.StructuredNoteParser
import com.voicenotemd.core.inference.session.GemmaSession
import com.voicenotemd.core.inference.structure.StructureNoteUseCaseImpl
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Living evaluation suite for the structuring prompt — see CLAUDE.md section 6 and
 * `core/inference/src/test/resources/prompt-eval/README.md`.
 *
 * Each `*.transcript.txt` is paired with a `*.expected.json` that captures what we expect
 * the model to emit. We don't test the real model here (it's not available offline) — we
 * stub it with a deterministic [PinnedGemmaSession] that returns the pinned JSON. The
 * value of this suite is two-fold:
 *
 * 1. It proves the parser tolerates every JSON shape the corpus exercises.
 * 2. It proves the use-case orchestration produces a well-formed [Note] for every
 *    language / scenario, including the "right field types end-to-end" check that
 *    Hilt cannot perform at compile time.
 *
 * When the real model misbehaves on a transcript we add the *new* transcript here with
 * the JSON the model actually emitted (or the JSON we wish it had emitted, if the
 * regression is a quality bug rather than a schema bug).
 */
class PromptEvalTest {
    private val parser = StructuredNoteParser()
    private val basePrompt = StubPromptTemplate
    private val clock = Clock.fixed(Instant.parse("2026-05-10T10:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `every fixture pair produces a structured Note`() =
        runTest {
            val pairs = loadFixtures()
            assertThat(pairs).isNotEmpty()
            // Sanity: 6 supported languages should each have at least one fixture.
            val languages = pairs.map { it.language }.toSet()
            assertThat(languages).containsAtLeast("en", "it", "es", "fr", "de", "pt")

            for (fixture in pairs) {
                val session = PinnedGemmaSession(fixture.expectedJson)
                val useCase =
                    StructureNoteUseCaseImpl(
                        session = session,
                        basePrompt = basePrompt,
                        parser = parser,
                        clock = clock,
                        idGenerator = { UUID.nameUUIDFromBytes(fixture.name.toByteArray()).toString() },
                    )

                val note = useCase(fixture.transcript).note

                assertWithMessage("structured(${fixture.name})")
                    .that(note.structured)
                    .isTrue()
                assertWithMessage("title(${fixture.name})")
                    .that(note.title)
                    .isNotEmpty()
                assertWithMessage("body(${fixture.name})")
                    .that(note.bodyMarkdown)
                    .isNotEmpty()
                assertWithMessage("language(${fixture.name})")
                    .that(note.language)
                    .isEqualTo(Language.fromBcp47(fixture.language))
                // Tags must round-trip through normalization without dropping the whole set.
                for (tag in note.tags) assertThat(tag).isInstanceOf(Tag::class.java)
            }
        }

    @Test
    fun `parser accepts every fixtures expected JSON`() {
        for (fixture in loadFixtures()) {
            val parsed = parser.parse(fixture.expectedJson)
            assertWithMessage("parse(${fixture.name})")
                .that(parsed)
                .isInstanceOf(DomainResult.Success::class.java)
        }
    }

    @Test
    fun `adversarial empty transcript falls back gracefully`() =
        runTest {
            val empty = ROOT.resolve("adversarial/empty.transcript.txt")
            check(empty.exists()) { "Missing fixture: $empty" }

            val useCase =
                StructureNoteUseCaseImpl(
                    session = PinnedGemmaSession("{ \"title\": \"x\", \"body_markdown\": \"y\" }"),
                    basePrompt = basePrompt,
                    parser = parser,
                    clock = clock,
                )

            val note = useCase(empty.readText()).note

            assertThat(note.structured).isFalse()
            assertThat(note.title).isNotEmpty()
        }

    private fun loadFixtures(): List<EvalPair> {
        check(ROOT.exists()) { "Missing fixture root: $ROOT" }
        return ROOT.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".transcript.txt") }
            .filterNot { it.parentFile.name == "adversarial" }
            .mapNotNull { transcriptFile ->
                val expectedFile =
                    File(
                        transcriptFile.parentFile,
                        transcriptFile.name.removeSuffix(".transcript.txt") + ".expected.json",
                    )
                if (!expectedFile.exists()) {
                    null
                } else {
                    EvalPair(
                        name = "${transcriptFile.parentFile.name}/${transcriptFile.nameWithoutExtension}",
                        language = transcriptFile.parentFile.name,
                        transcript = transcriptFile.readText().trim(),
                        expectedJson = expectedFile.readText().trim(),
                    )
                }
            }
            .toList()
    }

    private data class EvalPair(
        val name: String,
        val language: String,
        val transcript: String,
        val expectedJson: String,
    )

    private object StubPromptTemplate : PromptTemplate {
        override fun render(
            transcript: String,
            now: java.time.Instant,
            zone: java.time.ZoneId,
            existingTags: List<String>,
        ): String = transcript
    }

    private class PinnedGemmaSession(private val response: String) : GemmaSession {
        override suspend fun generate(prompt: String): String = response

        override fun isReady(): Boolean = true
    }

    private companion object {
        /**
         * Located via the test classloader — this works regardless of the working dir
         * Gradle uses to invoke the JUnit task, which differs across IDE / CI runs.
         */
        val ROOT: File =
            run {
                val url =
                    PromptEvalTest::class.java.classLoader?.getResource("prompt-eval")
                        ?: error(
                            "prompt-eval resources not on the test classpath — make sure they live " +
                                "under src/test/resources/prompt-eval/.",
                        )
                File(url.toURI())
            }
    }
}
