package com.voicenotemd.core.inference.prompt

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * Pins the marker-substitution contract of [StaticPromptTemplate] — in particular the
 * substitution ORDER: the transcript is the only user-controlled value and MUST be
 * substituted last, so a transcript that contains a literal marker (plausible via the
 * keyboard input sheet) is passed through verbatim instead of being expanded by the
 * later replaces (template-injection hole, security review 2026-06-10).
 */
class StaticPromptTemplateTest {
    private val template =
        StaticPromptTemplate(
            "NOW: {{NOW_ISO}} TZ: {{NOW_TIMEZONE}} TAGS: {{EXISTING_TAGS}}\n" +
                "TRANSCRIPT: {{TRANSCRIPT}}",
        )
    private val now = Instant.parse("2026-06-10T12:00:00Z")

    @Test
    fun `substitutes all markers`() {
        val rendered =
            template.render(
                transcript = "comprare il latte",
                now = now,
                zone = ZoneOffset.UTC,
                existingTags = listOf("spesa", "casa"),
            )
        assertThat(rendered).contains("NOW: 2026-06-10T12:00:00Z")
        assertThat(rendered).contains("TZ: Z")
        assertThat(rendered).contains("TAGS: spesa, casa")
        assertThat(rendered).contains("TRANSCRIPT: comprare il latte")
        assertThat(rendered).doesNotContain("{{")
    }

    @Test
    fun `a marker inside the transcript is NOT expanded`() {
        val rendered =
            template.render(
                transcript = "nota con {{EXISTING_TAGS}} e {{NOW_ISO}} dentro",
                now = now,
                zone = ZoneOffset.UTC,
                existingTags = listOf("segreto-tag"),
            )
        // The literal marker text survives inside the transcript section…
        assertThat(rendered)
            .contains("TRANSCRIPT: nota con {{EXISTING_TAGS}} e {{NOW_ISO}} dentro")
        // …and the real tag list is only injected at the template's own marker.
        assertThat(rendered).contains("TAGS: segreto-tag")
    }

    @Test
    fun `existing tags are capped at the prompt budget`() {
        val manyTags = (1..200).map { "tag$it" }
        val rendered =
            template.render(
                transcript = "x",
                now = now,
                zone = ZoneOffset.UTC,
                existingTags = manyTags,
            )
        assertThat(rendered).contains("tag${StaticPromptTemplate.MAX_EXISTING_TAGS_IN_PROMPT}")
        assertThat(rendered)
            .doesNotContain("tag${StaticPromptTemplate.MAX_EXISTING_TAGS_IN_PROMPT + 1},")
    }
}
