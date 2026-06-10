// Several lines in this test file embed full JSON literals that exceed 120 chars
// when nested inside the test's indentation (4+ levels: class > fun > runTest > builder
// > listOf). Splitting them across lines obscures the test fixtures' intent — the JSON
// is the contract being verified, not incidental data. Suppress max-line-length for
// this file rather than mangling the literals.
@file:Suppress("ktlint:standard:max-line-length")

package com.voicenotemd.core.inference.structure

import com.google.common.truth.Truth.assertThat
import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.inference.prompt.StaticPromptTemplate
import com.voicenotemd.core.inference.schema.StructuredNoteParser
import com.voicenotemd.core.inference.session.GemmaSession
import com.voicenotemd.core.inference.session.GemmaUnavailableException
import com.voicenotemd.core.inference.session.InferenceBackend
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Coverage map for the three branches in ADR 0005:
 *   - happy path                 → see `should produce structured note...`
 *   - retry-then-success         → see `should retry once with stricter prompt...`
 *   - retry-then-fail-fallback   → see `should fall back to plain text...`
 *
 * Plus session-unavailable fallback and several invariants (no hallucination of dates,
 * tags normalized, language honored).
 */
class StructureNoteUseCaseImplTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-05-09T12:00:00Z"), ZoneOffset.UTC)
    private val basePrompt = StaticPromptTemplate("BASE: {{TRANSCRIPT}}")
    private val parser = StructuredNoteParser()

    private fun useCase(
        session: GemmaSession,
        ids: List<String> = listOf("note-id-1"),
    ): StructureNoteUseCaseImpl {
        val iter = ids.iterator()
        return StructureNoteUseCaseImpl(
            session = session,
            basePrompt = basePrompt,
            parser = parser,
            clock = fixedClock,
            idGenerator = { iter.next() },
        )
    }

    @Test
    fun `should produce structured note when given clean model output`() =
        runTest {
            val session =
                ScriptedSession(
                    listOf(
                        """{"language":"en","title":"Buy milk","tags":["errands"],"mentions":[],"body_markdown":"Buy milk on the way home."}""",
                    ),
                )
            val note = useCase(session).invoke("buy milk on the way home").note

            assertThat(note.structured).isTrue()
            assertThat(note.title).isEqualTo("Buy milk")
            assertThat(note.tags).hasSize(1)
            assertThat(note.tags.first().value).isEqualTo("errands")
            assertThat(note.bodyMarkdown).isEqualTo("Buy milk on the way home.")
            assertThat(note.language).isEqualTo(Language.English)
            assertThat(session.callCount).isEqualTo(1)
        }

    @Test
    fun `mention backstop fires when the model emits none but the transcript names a day`() =
        runTest {
            val session =
                ScriptedSession(
                    listOf(
                        """{"language":"it","title":"Riepilogo per Luca","tags":["lavoro"],"mentions":[],"body_markdown":"- [ ] Mandare il riepilogo a Luca entro venerdì"}""",
                    ),
                )
            val note = useCase(session).invoke("devo mandare il riepilogo a Luca entro venerdì").note

            // The model dropped the mention; the deterministic scanner recovers it from
            // the transcript (review 2026-06-10). Surface form is a literal substring.
            assertThat(note.mentions).hasSize(1)
            assertThat(note.mentions.first().surfaceForm).isEqualTo("venerdì")
            assertThat(note.mentions.first().resolved).isNotNull()
        }

    @Test
    fun `a bare-number mention surface is junk and gets dropped`() =
        runTest {
            val session =
                ScriptedSession(
                    listOf(
                        """{"language":"it","title":"Scadenza","tags":["lavoro"],"mentions":[{"surface_form":"2","iso_resolved":null}],"body_markdown":"Non ricordo se la scadenza è il 2 o il 21."}""",
                    ),
                )
            // The transcript carries no scannable future reference either, so the
            // backstop must stay silent too: zero chips, not a junk "2" chip.
            val note = useCase(session).invoke("non ricordo se la scadenza è il 2 o il 21").note

            assertThat(note.mentions).isEmpty()
        }

    @Test
    fun `an unresolved compound mention is resolved by scanning its own surface`() =
        runTest {
            val session =
                ScriptedSession(
                    listOf(
                        """{"language":"it","title":"Dentista","tags":["salute"],"mentions":[{"surface_form":"domani alle 15","iso_resolved":null}],"body_markdown":"- [ ] Richiamare il dentista"}""",
                    ),
                )
            val note = useCase(session).invoke("domani alle 15 devo richiamare il dentista").note

            // Real-device 2026-06-10: the model emitted the surface but left iso null.
            // The scanner covers the WHOLE surface ("domani" + "alle 15" merge) so the
            // deterministic resolution is accepted.
            assertThat(note.mentions).hasSize(1)
            assertThat(note.mentions.first().surfaceForm).isEqualTo("domani alle 15")
            assertThat(note.mentions.first().resolved).isNotNull()
        }

    @Test
    fun `mention backstop stays silent on conversational narration`() =
        runTest {
            val session =
                ScriptedSession(
                    listOf(
                        """{"language":"it","title":"Riunione","tags":["lavoro"],"mentions":[],"body_markdown":"Oggi la riunione è andata bene."}""",
                    ),
                )
            val note = useCase(session).invoke("oggi la riunione è andata bene").note

            // "oggi" is narration, not a schedulable reference — no junk chips.
            assertThat(note.mentions).isEmpty()
        }

    @Test
    fun `a plain first body line that echoes the title is stripped`() =
        runTest {
            val session =
                ScriptedSession(
                    listOf(
                        """{"language":"it","title":"Lista della spesa","tags":["personale"],"mentions":[],"body_markdown":"Lista della spesa.\n\n- [ ] Comprare pane\n- [ ] Comprare latte"}""",
                    ),
                )
            val note = useCase(session).invoke("lista della spesa pane e latte").note

            // The echo duplicated the title field (real-device 2026-06-10); the items survive.
            assertThat(note.bodyMarkdown).startsWith("- [ ] Comprare pane")
            assertThat(note.bodyMarkdown).contains("- [ ] Comprare latte")
        }

    @Test
    fun `should retry once with stricter prompt when given prose-wrapped output then succeed`() =
        runTest {
            val session =
                ScriptedSession(
                    listOf(
                        "Sure! Here you go: this is not parseable JSON, just prose.",
                        """{"language":"en","title":"Idea","tags":[],"mentions":[],"body_markdown":"An idea."}""",
                    ),
                )
            val note = useCase(session).invoke("an idea").note

            assertThat(note.structured).isTrue()
            assertThat(note.title).isEqualTo("Idea")
            assertThat(session.callCount).isEqualTo(2)
            // The second prompt must be the stricter variant.
            assertThat(session.lastPromptOrNull()).contains("RETURN JSON ONLY")
        }

    @Test
    fun `should fall back to plain text when given two failures in a row`() =
        runTest {
            val session =
                ScriptedSession(
                    listOf(
                        "I cannot help with that.",
                        "Still not JSON.",
                    ),
                )
            val note = useCase(session).invoke("a transcript that the model refuses").note

            assertThat(note.structured).isFalse()
            assertThat(note.bodyMarkdown).isEqualTo("a transcript that the model refuses")
            assertThat(note.title).isEqualTo("a transcript that the model refuses")
            assertThat(note.tags).isEmpty()
            assertThat(note.mentions).isEmpty()
            assertThat(session.callCount).isEqualTo(2)
        }

    @Test
    fun `should fall back to plain text when session is unavailable on the first call`() =
        runTest {
            val session = ThrowingSession(GemmaUnavailableException("loading"))
            val note = useCase(session).invoke("hello world").note

            assertThat(note.structured).isFalse()
            assertThat(note.bodyMarkdown).isEqualTo("hello world")
            // We do NOT retry — a stricter prompt cannot save an unloaded model.
            assertThat(session.callCount).isEqualTo(1)
        }

    @Test
    fun `should never invent a date when given mention with unparseable iso_resolved`() =
        runTest {
            // The surface form must be a COMPOUND/uncommon expression that
            // `RelativeDateTimeResolver` does not handle deterministically — otherwise
            // the deterministic override would resolve it and the test would not
            // exercise the path where Gemma's bad iso_resolved must be discarded.
            // "the third Friday of the month" is intentionally outside the resolver's
            // tables (which only cover simple "today / tomorrow / next weekday"
            // phrases), so resolution falls back to parsing Gemma's iso_resolved
            // which is intentionally garbage here.
            val session =
                ScriptedSession(
                    listOf(
                        """{"language":"en","title":"x","tags":[],"mentions":[
                    {"surface_form":"the third Friday of the month","iso_resolved":"not-a-date"}
                ],"body_markdown":"x"}""",
                    ),
                )
            val note = useCase(session).invoke("the third Friday of the month").note
            assertThat(note.mentions).hasSize(1)
            assertThat(note.mentions.first().surfaceForm).isEqualTo("the third Friday of the month")
            // The model gave us garbage for the resolution. We KEEP the surface form (the user
            // actually said it) but we DO NOT invent an instant. Per CLAUDE.md pillar 4.
            assertThat(note.mentions.first().resolved).isNull()
        }

    @Test
    fun `should resolve a valid ISO instant when given well-formed mention`() =
        runTest {
            val session =
                ScriptedSession(
                    listOf(
                        """{"language":"en","title":"x","tags":[],"mentions":[
                    {"surface_form":"tomorrow at 3pm","iso_resolved":"2026-05-10T15:00:00Z"}
                ],"body_markdown":"x"}""",
                    ),
                )
            val note = useCase(session).invoke("tomorrow at 3pm").note
            assertThat(note.mentions.first().resolved).isEqualTo(Instant.parse("2026-05-10T15:00:00Z"))
        }

    @Test
    fun `should normalize tags when given dirty model output`() =
        runTest {
            // Transcript must anchor multi-part tag "project-plan" — `TagValidator`
            // strips kebab tags whose chunks don't appear as standalone words in the
            // transcript (the rule that kills hallucinated tags). "work" is mono-part
            // ≥4 chars and is trusted unconditionally regardless.
            val session =
                ScriptedSession(
                    listOf(
                        """{"language":"en","title":"x","tags":["Work!!","   project plan   "],"mentions":[],"body_markdown":"x"}""",
                    ),
                )
            val note = useCase(session).invoke("Let's review the project plan together").note
            assertThat(note.tags.map { it.value }).containsExactly("work", "project-plan").inOrder()
        }

    @Test
    fun `should honor forceLanguage even when model claims a different language`() =
        runTest {
            val session =
                ScriptedSession(
                    listOf(
                        """{"language":"en","title":"x","tags":[],"mentions":[],"body_markdown":"x"}""",
                    ),
                )
            val note = useCase(session).invoke("ciao", forceLanguage = Language.Italian).note
            assertThat(note.language).isEqualTo(Language.Italian)
        }

    @Test
    fun `should read backend after warmUp so budget can be backend-aware`() =
        runTest {
            // Regression for the 2026-05-17 evening fix: the structuring use case must
            // call session.backend() AFTER warmUp completes (so the engine is loaded
            // and the real backend is known), not before. Reading before warm-up returns
            // UNKNOWN, which is still safe (it picks the conservative CPU budget) but
            // wastes the optimization on GPU devices.
            val session =
                OrderTrackingSession(
                    response = """{"language":"en","title":"x","tags":[],"mentions":[],"body_markdown":"x"}""",
                )
            useCase(session).invoke("hello")
            // backend() must be called at or after warmUp, never before.
            val warmUpIndex = session.calls.indexOf("warmUp")
            val backendIndex = session.calls.indexOf("backend")
            val generateIndex = session.calls.indexOf("generate")
            assertThat(warmUpIndex).isAtLeast(0)
            assertThat(backendIndex).isAtLeast(warmUpIndex)
            assertThat(generateIndex).isAtLeast(backendIndex)
        }

    @Test
    fun `should warm up the session before issuing the first generate call`() =
        runTest {
            // Regression for ADR 0016 / 2026-05-17 incident: the use case must call
            // session.warmUp() BEFORE entering the timed Pass 1, so that engine cold-load
            // time is not deducted from the inference budget. Collapsing this ordering
            // re-introduces the bug where short notes timed out at exactly the cold budget.
            val session =
                OrderTrackingSession(
                    response = """{"language":"en","title":"x","tags":[],"mentions":[],"body_markdown":"x"}""",
                )
            useCase(session).invoke("hello")
            // The 2026-05-17 evening follow-up added an interstitial `backend()` read
            // between warmUp and generate; the ordering must remain warmUp → ... → generate
            // (backend() may or may not appear here depending on use-case evolution, but
            // warmUp must precede generate without exception).
            val warmUpIndex = session.calls.indexOf("warmUp")
            val generateIndex = session.calls.indexOf("generate")
            assertThat(warmUpIndex).isAtLeast(0)
            assertThat(generateIndex).isGreaterThan(warmUpIndex)
        }

    @Test
    fun `should produce a sane plain-text fallback when given empty transcript`() =
        runTest {
            val session = ScriptedSession(emptyList())
            val note = useCase(session).invoke("   ").note
            assertThat(note.structured).isFalse()
            assertThat(note.title).isEqualTo("Untitled note")
            // We MUST NOT invoke the session for an empty transcript.
            assertThat(session.callCount).isEqualTo(0)
        }
}

private class ScriptedSession(private val responses: List<String>) : GemmaSession {
    private val responseIter = responses.iterator()
    private val prompts = mutableListOf<String>()
    val callCount: Int get() = prompts.size

    fun lastPromptOrNull(): String? = prompts.lastOrNull()

    override suspend fun generate(prompt: String): String {
        prompts += prompt
        if (!responseIter.hasNext()) {
            error(
                "ScriptedSession exhausted (got ${prompts.size} calls, only ${responses.size} responses scripted)",
            )
        }
        return responseIter.next()
    }

    override fun isReady(): Boolean = true
}

private class ThrowingSession(private val cause: Throwable) : GemmaSession {
    var callCount: Int = 0
        private set

    override suspend fun generate(prompt: String): String {
        callCount++
        throw cause
    }

    override fun isReady(): Boolean = false
}

/**
 * Records the order in which [warmUp], [backend], and [generate] are called.
 * Used by the "warm-up before Pass 1" regression test (ADR 0016) and the
 * "backend after warm-up" regression test (2026-05-17 evening follow-up).
 * Returns a single canned response so the structuring use case completes
 * on the first pass.
 */
private class OrderTrackingSession(private val response: String) : GemmaSession {
    private val _calls = mutableListOf<String>()
    val calls: List<String> get() = _calls.toList()

    override suspend fun warmUp() {
        _calls += "warmUp"
    }

    override fun backend(): InferenceBackend {
        _calls += "backend"
        return InferenceBackend.UNKNOWN
    }

    override suspend fun generate(prompt: String): String {
        _calls += "generate"
        return response
    }

    override fun isReady(): Boolean = true
}
