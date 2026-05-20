package com.voicenotemd.core.inference.schema

import com.google.common.truth.Truth.assertThat
import com.voicenotemd.core.common.result.DomainResult
import org.junit.Test

class StructuredNoteParserTest {
    private val parser = StructuredNoteParser()

    @Test
    fun `should parse when given a clean well-formed JSON response`() {
        val raw =
            """
            {
              "language": "en",
              "title": "Call Dr. Lopez and get groceries",
              "tags": ["health", "errands"],
              "mentions": [{"surface_form": "tomorrow at 3pm", "iso_resolved": null}],
              "body_markdown": "- Call Dr. Lopez at 3pm tomorrow.\n- Buy groceries."
            }
            """.trimIndent()

        val result = parser.parse(raw)

        assertThat(result).isInstanceOf(DomainResult.Success::class.java)
        val note = (result as DomainResult.Success).value
        assertThat(note.title).isEqualTo("Call Dr. Lopez and get groceries")
        assertThat(note.tags).containsExactly("health", "errands").inOrder()
        assertThat(note.mentions).hasSize(1)
        assertThat(note.mentions.first().surfaceForm).isEqualTo("tomorrow at 3pm")
        assertThat(note.mentions.first().isoResolved).isNull()
        assertThat(note.languageBcp47).isEqualTo("en")
    }

    @Test
    fun `should sanitize when given fenced code block wrapper`() {
        val raw =
            """
            ```json
            {"language":"it","title":"Idea","tags":[],"mentions":[],"body_markdown":"Una piccola idea."}
            ```
            """.trimIndent()

        val result = parser.parse(raw)
        assertThat(result).isInstanceOf(DomainResult.Success::class.java)
    }

    @Test
    fun `should sanitize when given preamble before JSON`() {
        val raw =
            """
            Sure! Here is the structured note:
            {"language":"en","title":"x","tags":[],"mentions":[],"body_markdown":"x"}
            Hope this helps!
            """.trimIndent()

        val result = parser.parse(raw)
        assertThat(result).isInstanceOf(DomainResult.Success::class.java)
    }

    @Test
    fun `should fail with NoJsonObject when given pure prose`() {
        val raw = "I cannot help with that."
        val result = parser.parse(raw)
        assertThat(result).isEqualTo(
            DomainResult.Failure(StructuredNoteParser.ParseError.NoJsonObject),
        )
    }

    @Test
    fun `should fail with MissingField when given JSON without title`() {
        val raw = """{"language":"en","title":"","tags":[],"mentions":[],"body_markdown":"hi"}"""
        val result = parser.parse(raw)
        assertThat(result).isEqualTo(
            DomainResult.Failure(StructuredNoteParser.ParseError.MissingField("title")),
        )
    }

    @Test
    fun `should fail with MissingField when given JSON without body_markdown`() {
        val raw = """{"language":"en","title":"x","tags":[],"mentions":[]}"""
        val result = parser.parse(raw)
        assertThat(result).isEqualTo(
            DomainResult.Failure(StructuredNoteParser.ParseError.MissingField("body_markdown")),
        )
    }

    @Test
    fun `should fail with MalformedJson when given truncated input`() {
        val raw = """{"language":"en","title":"x","""
        val result = parser.parse(raw)
        assertThat(result).isInstanceOf(DomainResult.Failure::class.java)
        val err = (result as DomainResult.Failure).error
        // Could be MalformedJson OR NoJsonObject depending on where it cuts off — both valid.
        assertThat(err).isAnyOf(
            StructuredNoteParser.ParseError.NoJsonObject,
            StructuredNoteParser.ParseError.MalformedJson(""),
        )
    }

    @Test
    fun `should drop empty mentions when given malformed entries`() {
        val raw =
            """
            {"language":"en","title":"x","tags":[],"mentions":[
                {"surface_form":"","iso_resolved":null},
                {"surface_form":"   ","iso_resolved":null},
                {"surface_form":"next Friday","iso_resolved":null}
            ],"body_markdown":"x"}
            """.trimIndent()

        val result = parser.parse(raw)
        assertThat(result).isInstanceOf(DomainResult.Success::class.java)
        val mentions = (result as DomainResult.Success).value.mentions
        assertThat(mentions).hasSize(1)
        assertThat(mentions.first().surfaceForm).isEqualTo("next Friday")
    }

    @Test
    fun `should truncate when given long title`() {
        val long = "a".repeat(200)
        val raw = """{"language":"en","title":"$long","tags":[],"mentions":[],"body_markdown":"x"}"""
        val result = parser.parse(raw)
        val note = (result as DomainResult.Success).value
        assertThat(note.title.length).isAtMost(StructuredNoteParser.MAX_TITLE_LEN)
    }

    @Test
    fun `should preserve language tag when parsing succeeds`() {
        val raw = """{"language":"it","title":"x","tags":[],"mentions":[],"body_markdown":"x"}"""
        val result = parser.parse(raw)
        val note = (result as DomainResult.Success).value
        assertThat(note.languageBcp47).isEqualTo("it")
    }

    // --- Reasoning-trace tag handling ---
    //
    // Gemma 4 supports an in-band "Thinking Mode" reasoning trace, surfaced as
    // <thought>...</thought> tags directly in the generated text (three independent
    // confirmations from dev.to Gemma 4 Challenge submissions analyzed 2026-05-18:
    // DiagramFlowAI, HumanLayer, and Vinod Kumar Jaipal's AI Studio observations).
    // Our current prompt v1 instructs JSON-only output, so we don't expect these tags
    // in practice yet — but Gemma may still emit them on harder transcripts, and a
    // future prompt revision may explicitly enable Thinking Mode. The parser strips
    // them defensively so the JSON extraction is robust either way.

    @Test
    fun `should sanitize when given thought tags before JSON`() {
        val raw =
            """
            <thought>The user is reminding themselves about a call and groceries. Both
            are concrete actions, no false starts. Output as JSON.</thought>
            {"language":"en","title":"Call and groceries","tags":[],"mentions":[],"body_markdown":"x"}
            """.trimIndent()

        val result = parser.parse(raw)
        assertThat(result).isInstanceOf(DomainResult.Success::class.java)
        val note = (result as DomainResult.Success).value
        assertThat(note.title).isEqualTo("Call and groceries")
    }

    @Test
    fun `should sanitize when given thought tags after JSON`() {
        val raw =
            """
            {"language":"en","title":"x","tags":[],"mentions":[],"body_markdown":"x"}
            <thought>I think that captured everything.</thought>
            """.trimIndent()

        val result = parser.parse(raw)
        assertThat(result).isInstanceOf(DomainResult.Success::class.java)
    }

    @Test
    fun `should sanitize when given thought tags containing JSON-like content`() {
        // A thought block that mentions a fake JSON object inside its reasoning.
        // Without explicit thought-stripping, the first-brace / last-brace scan
        // would latch onto the fake `{...}` inside the thought and produce garbage.
        val raw =
            """
            <thought>If this were a hunger cue I would return {"title": "fake"} but
            it's clearly a meeting reminder.</thought>
            {"language":"en","title":"Meeting reminder","tags":[],"mentions":[],"body_markdown":"x"}
            """.trimIndent()

        val result = parser.parse(raw)
        assertThat(result).isInstanceOf(DomainResult.Success::class.java)
        val note = (result as DomainResult.Success).value
        assertThat(note.title).isEqualTo("Meeting reminder")
    }

    @Test
    fun `should sanitize when given multi-line thought tags`() {
        val raw =
            """
            <thought>
            Line one of reasoning.
            Line two of reasoning.
            Line three of reasoning.
            </thought>
            {"language":"it","title":"Nota","tags":[],"mentions":[],"body_markdown":"x"}
            """.trimIndent()

        val result = parser.parse(raw)
        assertThat(result).isInstanceOf(DomainResult.Success::class.java)
        val note = (result as DomainResult.Success).value
        assertThat(note.languageBcp47).isEqualTo("it")
    }

    @Test
    fun `should sanitize when given think tag alternate form`() {
        // Some Gemma 4 variants and other model families emit <think>...</think>
        // or <thinking>...</thinking>. We strip all three defensively, case-insensitive.
        val raw =
            """
            <THINK>quick reasoning</THINK>
            <thinking>longer pondering across
            multiple lines</thinking>
            {"language":"en","title":"x","tags":[],"mentions":[],"body_markdown":"x"}
            """.trimIndent()

        val result = parser.parse(raw)
        assertThat(result).isInstanceOf(DomainResult.Success::class.java)
    }
}
