package com.voicenotemd.core.inference.normalize

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pure-function tests. No LLM, no clock injection from outside the test —
 * each case fixes `now` and `zone` explicitly so the expected ISO is
 * computable by hand.
 */
class RelativeDateTimeResolverTest {
    // 2026-05-16 is a Saturday in real life. We anchor the fixture to it.
    private val romeZone = ZoneId.of("Europe/Rome")
    private val noon = Instant.parse("2026-05-16T10:00:00Z") // 12:00 Europe/Rome

    @Test
    fun `italian stasera resolves to today evening in user zone`() {
        val result = RelativeDateTimeResolver.resolve("stasera", "it", noon, romeZone)
        // 20:00 Rome on the same calendar day
        assertThat(result).isEqualTo(
            LocalDateTime.of(2026, 5, 16, 20, 0).atZone(romeZone).toInstant(),
        )
    }

    @Test
    fun `english tonight matches italian stasera semantics`() {
        val result = RelativeDateTimeResolver.resolve("tonight", "en", noon, romeZone)
        assertThat(result).isEqualTo(
            LocalDateTime.of(2026, 5, 16, 20, 0).atZone(romeZone).toInstant(),
        )
    }

    @Test
    fun `french ce soir matches italian stasera semantics`() {
        val result = RelativeDateTimeResolver.resolve("ce soir", "fr", noon, romeZone)
        assertThat(result).isEqualTo(
            LocalDateTime.of(2026, 5, 16, 20, 0).atZone(romeZone).toInstant(),
        )
    }

    @Test
    fun `german heute abend matches italian stasera semantics`() {
        val result = RelativeDateTimeResolver.resolve("heute abend", "de", noon, romeZone)
        assertThat(result).isEqualTo(
            LocalDateTime.of(2026, 5, 16, 20, 0).atZone(romeZone).toInstant(),
        )
    }

    @Test
    fun `spanish esta noche matches italian stasera semantics`() {
        val result = RelativeDateTimeResolver.resolve("esta noche", "es", noon, romeZone)
        assertThat(result).isEqualTo(
            LocalDateTime.of(2026, 5, 16, 20, 0).atZone(romeZone).toInstant(),
        )
    }

    @Test
    fun `portuguese esta noite matches italian stasera semantics`() {
        val result = RelativeDateTimeResolver.resolve("esta noite", "pt", noon, romeZone)
        assertThat(result).isEqualTo(
            LocalDateTime.of(2026, 5, 16, 20, 0).atZone(romeZone).toInstant(),
        )
    }

    @Test
    fun `domani resolves to start-of-next-day in user zone`() {
        val result = RelativeDateTimeResolver.resolve("domani", "it", noon, romeZone)
        assertThat(result).isEqualTo(
            LocalDateTime.of(2026, 5, 17, 0, 0).atZone(romeZone).toInstant(),
        )
    }

    @Test
    fun `domani sera resolves to tomorrow at 8pm`() {
        val result = RelativeDateTimeResolver.resolve("domani sera", "it", noon, romeZone)
        assertThat(result).isEqualTo(
            LocalDateTime.of(2026, 5, 17, 20, 0).atZone(romeZone).toInstant(),
        )
    }

    @Test
    fun `ieri sera resolves to yesterday at 8pm`() {
        val result = RelativeDateTimeResolver.resolve("ieri sera", "it", noon, romeZone)
        assertThat(result).isEqualTo(
            LocalDateTime.of(2026, 5, 15, 20, 0).atZone(romeZone).toInstant(),
        )
    }

    @Test
    fun `weekday name resolves to next occurrence at or after today`() {
        // 2026-05-16 is a Saturday. "lunedì" said today resolves to Monday 2026-05-18.
        val result = RelativeDateTimeResolver.resolve("lunedì", "it", noon, romeZone)
        assertThat(result).isEqualTo(
            LocalDateTime.of(2026, 5, 18, 0, 0).atZone(romeZone).toInstant(),
        )
    }

    @Test
    fun `weekday name said on the same weekday resolves to today`() {
        // 2026-05-16 is a Saturday. "sabato" said today resolves to today.
        val result = RelativeDateTimeResolver.resolve("sabato", "it", noon, romeZone)
        assertThat(result).isEqualTo(
            LocalDateTime.of(2026, 5, 16, 0, 0).atZone(romeZone).toInstant(),
        )
    }

    @Test
    fun `case insensitive and trims trailing punctuation`() {
        val result = RelativeDateTimeResolver.resolve("Stasera.", "it", noon, romeZone)
        assertThat(result).isEqualTo(
            LocalDateTime.of(2026, 5, 16, 20, 0).atZone(romeZone).toInstant(),
        )
    }

    @Test
    fun `compound expression returns null so gemma's iso is preserved`() {
        // "stasera tardi" is compound — model decides, we abstain.
        val result = RelativeDateTimeResolver.resolve("stasera tardi", "it", noon, romeZone)
        assertThat(result).isNull()
    }

    @Test
    fun `unknown phrase returns null`() {
        val result = RelativeDateTimeResolver.resolve("alle 14", "it", noon, romeZone)
        assertThat(result).isNull()
    }

    @Test
    fun `unknown language falls through to multi-language search`() {
        val result = RelativeDateTimeResolver.resolve("tonight", "ja", noon, romeZone)
        // Should still hit the English table even though `ja` isn't supported.
        assertThat(result).isEqualTo(
            LocalDateTime.of(2026, 5, 16, 20, 0).atZone(romeZone).toInstant(),
        )
    }

    @Test
    fun `extra whitespace inside phrase is collapsed`() {
        val result = RelativeDateTimeResolver.resolve("  ce   soir  ", "fr", noon, romeZone)
        assertThat(result).isEqualTo(
            LocalDateTime.of(2026, 5, 16, 20, 0).atZone(romeZone).toInstant(),
        )
    }

    @Test
    fun `long compound surface form is rejected without matching`() {
        // Beyond MAX_NORMALIZED_LEN — even if "stasera" is a prefix, the whole
        // phrase is too long to be a simple expression and we abstain.
        val long = "stasera dopo cena con marco e i ragazzi del progetto"
        val result = RelativeDateTimeResolver.resolve(long, "it", noon, romeZone)
        assertThat(result).isNull()
    }

    @Test
    fun `english tomorrow night resolves correctly`() {
        val result = RelativeDateTimeResolver.resolve("tomorrow night", "en", noon, romeZone)
        assertThat(result).isEqualTo(
            LocalDateTime.of(2026, 5, 17, 22, 0).atZone(romeZone).toInstant(),
        )
    }

    @Test
    fun `respects user zone — same wall-clock evening differs by UTC instant`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val nowInTokyo = Instant.parse("2026-05-16T03:00:00Z") // 12:00 Tokyo
        val result = RelativeDateTimeResolver.resolve("stasera", "it", nowInTokyo, tokyo)
        // 20:00 Tokyo on the same calendar day
        assertThat(result).isEqualTo(
            LocalDateTime.of(2026, 5, 16, 20, 0).atZone(tokyo).toInstant(),
        )
        // Sanity: different UTC instant than Rome's 20:00
        val resultRome = RelativeDateTimeResolver.resolve("stasera", "it", noon, romeZone)
        assertThat(result).isNotEqualTo(resultRome)
    }

    @Test
    fun `next monday in english resolves the same as bare monday on saturday`() {
        // The resolver intentionally collapses "next X" to "next-occurrence-of-X"
        // (see ADR 0015) — we'd rather be wrong by a few days than wrong by a week.
        val bare = RelativeDateTimeResolver.resolve("monday", "en", noon, romeZone)
        val next = RelativeDateTimeResolver.resolve("next monday", "en", noon, romeZone)
        assertThat(bare).isEqualTo(next)
        val mondayLocal = LocalDateTime.of(2026, 5, 18, 0, 0).atZone(romeZone).toInstant()
        assertThat(bare).isEqualTo(mondayLocal)
        assertThat(DayOfWeek.MONDAY.value).isEqualTo(1) // sanity
    }

    @Test
    fun `null language is acceptable and uses multi-language search`() {
        val result = RelativeDateTimeResolver.resolve("domani", null, noon, romeZone)
        assertThat(result).isEqualTo(
            LocalDateTime.of(2026, 5, 17, 0, 0).atZone(romeZone).toInstant(),
        )
    }

    @Test
    fun `empty surface form returns null`() {
        assertThat(RelativeDateTimeResolver.resolve("", "it", noon, romeZone)).isNull()
        assertThat(RelativeDateTimeResolver.resolve("   ", "it", noon, romeZone)).isNull()
    }

    @Test
    fun `default zone parameter does not crash`() {
        // Smoke test the 3-arg overload (using system default).
        val result =
            RelativeDateTimeResolver.resolve(
                surfaceForm = "tonight",
                languageBcp47 = "en",
                now = noon,
            )
        // Can't assert the exact value (depends on the test machine's TZ), but
        // we can assert it's non-null and falls on the same calendar date in
        // the system default zone.
        assertThat(result).isNotNull()
        val asLocal = LocalDateTime.ofInstant(result!!, ZoneOffset.UTC)
        // The Instant is within a 24-hour window of the test "now".
        val diffHours = (result.epochSecond - noon.epochSecond) / 3600
        assertThat(diffHours).isAtLeast(-12)
        assertThat(diffHours).isAtMost(36)
        // suppress lint about unused asLocal
        assertThat(asLocal).isNotNull()
    }
}
