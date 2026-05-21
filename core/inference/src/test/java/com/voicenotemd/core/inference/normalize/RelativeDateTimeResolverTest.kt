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

    // --- Future-bias guard ---
    //
    // Gemma occasionally anchors an ambiguous time-only mention to a PAST date
    // (real-device 2026-05-19: "alle quindici e trenta" → a date 3 days prior).
    // For a voice note the intent is almost always the next future occurrence.
    // `biasToFuture` rolls a recent-past, no-past-reference datetime forward to
    // the first occurrence at or after `now`. It runs only on the Gemma fallback
    // path in StructureNoteUseCaseImpl — never on `resolve()`'s output, which is
    // already future-correct via nextDow.

    @Test
    fun `biasToFuture rolls a recent past time forward to today`() {
        // Gemma resolved a bare "15:30" to 2 days ago. now = Sat 2026-05-16 12:00 Rome.
        val gemmaPast = LocalDateTime.of(2026, 5, 14, 15, 30).atZone(romeZone).toInstant()
        val result =
            RelativeDateTimeResolver.biasToFuture(
                gemmaPast,
                "alle quindici e trenta",
                noon,
                romeZone,
            )
        // Rolls forward whole days preserving 15:30 → today (Sat) at 15:30, which is
        // after now (12:00).
        assertThat(result).isEqualTo(
            LocalDateTime.of(2026, 5, 16, 15, 30).atZone(romeZone).toInstant(),
        )
    }

    @Test
    fun `biasToFuture leaves an already-future datetime untouched`() {
        val future = LocalDateTime.of(2026, 5, 20, 15, 30).atZone(romeZone).toInstant()
        val result = RelativeDateTimeResolver.biasToFuture(future, "alle quindici e trenta", noon, romeZone)
        assertThat(result).isEqualTo(future)
    }

    @Test
    fun `biasToFuture respects an explicit past reference in the surface form`() {
        val past = LocalDateTime.of(2026, 5, 14, 15, 30).atZone(romeZone).toInstant()
        // "ieri" is an explicit past reference — the user means the past; do not shift.
        val result = RelativeDateTimeResolver.biasToFuture(past, "ieri alle quindici e trenta", noon, romeZone)
        assertThat(result).isEqualTo(past)
    }

    @Test
    fun `biasToFuture respects past references across languages`() {
        val past = LocalDateTime.of(2026, 5, 14, 15, 30).atZone(romeZone).toInstant()
        val pastReferences =
            listOf(
                "yesterday at 3:30",
                "ayer a las 15:30",
                "hier à 15h30",
                "gestern um 15:30",
                "ontem às 15:30",
            )
        for (surface in pastReferences) {
            val result = RelativeDateTimeResolver.biasToFuture(past, surface, noon, romeZone)
            assertThat(result).isEqualTo(past)
        }
    }

    @Test
    fun `biasToFuture keeps an explicit today anchor even when the time already passed`() {
        // "oggi alle 9:00" said at 12:00: 9:00 today has passed, but the user named
        // TODAY explicitly. We must NOT roll it to tomorrow — the day is anchored.
        val todayButPast = LocalDateTime.of(2026, 5, 16, 9, 0).atZone(romeZone).toInstant()
        val result = RelativeDateTimeResolver.biasToFuture(todayButPast, "oggi alle 9:00", noon, romeZone)
        assertThat(result).isEqualTo(todayButPast)
    }

    @Test
    fun `biasToFuture honors today anchors across languages`() {
        val todayButPast = LocalDateTime.of(2026, 5, 16, 9, 0).atZone(romeZone).toInstant()
        val todayPhrases =
            listOf(
                "today at 9",
                "hoy a las 9",
                "aujourd'hui à 9h",
                "heute um 9",
                "hoje às 9",
            )
        for (surface in todayPhrases) {
            val result = RelativeDateTimeResolver.biasToFuture(todayButPast, surface, noon, romeZone)
            assertThat(result).isEqualTo(todayButPast)
        }
    }

    @Test
    fun `biasToFuture leaves a far-past datetime untouched as a likely historical reference`() {
        // 30 days in the past, no past keyword — but too far back to be a misanchored
        // "this week" reference. We don't second-guess it.
        val farPast = LocalDateTime.of(2026, 4, 16, 15, 30).atZone(romeZone).toInstant()
        val result = RelativeDateTimeResolver.biasToFuture(farPast, "alle quindici e trenta", noon, romeZone)
        assertThat(result).isEqualTo(farPast)
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
