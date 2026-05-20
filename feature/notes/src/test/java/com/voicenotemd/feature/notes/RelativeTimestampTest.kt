package com.voicenotemd.feature.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Pure-function tests for the note list timestamp formatter.
 *
 * The list-card date used to be a long absolute string ("16 May 2026, 08:37")
 * that visually collapsed into a 5-line vertical column whenever tag chips
 * pushed it sideways. The relative formatter shipped on 2026-05-16 fixes that
 * by keeping the most common case (today) at ~5 characters and degrading
 * gracefully for older notes.
 */
class RelativeTimestampTest {
    private val rome = ZoneId.of("Europe/Rome")

    // Fixed "now" for the whole suite: Saturday 16 May 2026, 12:00 Europe/Rome.
    private val now: Instant =
        LocalDateTime.of(2026, 5, 16, 12, 0)
            .atZone(rome).toInstant()

    @Test
    fun `today shows time only`() {
        val noteTime = LocalDateTime.of(2026, 5, 16, 8, 37).atZone(rome).toInstant()
        assertThat(formatRelativeTimestamp(noteTime, rome, now)).isEqualTo("08:37")
    }

    @Test
    fun `today midnight is still time only`() {
        val noteTime = LocalDateTime.of(2026, 5, 16, 0, 0).atZone(rome).toInstant()
        assertThat(formatRelativeTimestamp(noteTime, rome, now)).isEqualTo("00:00")
    }

    @Test
    fun `yesterday shows literal Yesterday plus time`() {
        val noteTime = LocalDateTime.of(2026, 5, 15, 22, 14).atZone(rome).toInstant()
        assertThat(formatRelativeTimestamp(noteTime, rome, now)).isEqualTo("Yesterday 22:14")
    }

    @Test
    fun `earlier this week shows weekday abbreviation plus time`() {
        // 2026-05-12 was a Tuesday — within 2-6 days of Saturday 2026-05-16.
        val noteTime = LocalDateTime.of(2026, 5, 12, 18, 2).atZone(rome).toInstant()
        // Verify the format shape rather than the locale-specific weekday string,
        // because the abbreviation depends on the JVM default locale.
        val formatted = formatRelativeTimestamp(noteTime, rome, now)
        assertThat(formatted).contains(" 18:02")
        assertThat(formatted.length).isAtMost(10)
    }

    @Test
    fun `older this year shows day and month without year`() {
        val noteTime = LocalDateTime.of(2026, 4, 12, 9, 30).atZone(rome).toInstant()
        val formatted = formatRelativeTimestamp(noteTime, rome, now)
        assertThat(formatted).doesNotContain("2026")
        assertThat(formatted).contains("12")
    }

    @Test
    fun `last year shows full date with year`() {
        val noteTime = LocalDateTime.of(2025, 11, 3, 14, 0).atZone(rome).toInstant()
        val formatted = formatRelativeTimestamp(noteTime, rome, now)
        assertThat(formatted).contains("2025")
        assertThat(formatted).contains("3")
    }

    @Test
    fun `respects user timezone — same UTC instant differs by zone`() {
        // 23:30 UTC on 2026-05-16 — in Rome that's already 01:30 on May 17
        // (next day), but in Los Angeles it's 16:30 on May 16 (same day).
        val noteTime = Instant.parse("2026-05-16T23:30:00Z")
        val nowSummer = Instant.parse("2026-05-17T08:00:00Z")
        val inRome = formatRelativeTimestamp(noteTime, ZoneId.of("Europe/Rome"), nowSummer)
        val inLa = formatRelativeTimestamp(noteTime, ZoneId.of("America/Los_Angeles"), nowSummer)
        // Rome: "now" is 10:00 local on May 17, note was 01:30 local May 17 → today.
        assertThat(inRome).isEqualTo("01:30")
        // LA: "now" is 01:00 local on May 17, note was 16:30 local May 16 → yesterday.
        assertThat(inLa).isEqualTo("Yesterday 16:30")
    }

    @Test
    fun `boundary — exactly 7 days ago uses date-no-year, not weekday`() {
        // 7 days before Saturday is the previous Saturday — outside the 2..6 window.
        val noteTime = LocalDateTime.of(2026, 5, 9, 10, 0).atZone(rome).toInstant()
        val formatted = formatRelativeTimestamp(noteTime, rome, now)
        // Should be "9 May" or "9 Mag" depending on JVM locale — neither contains a weekday.
        assertThat(formatted).doesNotContain("10:00")
        assertThat(formatted).contains("9")
    }

    @Test
    fun `boundary — 2 days ago uses weekday format`() {
        val noteTime = LocalDateTime.of(2026, 5, 14, 16, 0).atZone(rome).toInstant()
        val formatted = formatRelativeTimestamp(noteTime, rome, now)
        assertThat(formatted).contains("16:00")
    }
}
