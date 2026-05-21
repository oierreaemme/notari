package com.voicenotemd.core.inference.normalize

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Deterministic override layer for Gemma's `iso_resolved` field on simple relative
 * datetime expressions across the v1 languages (en, it, es, fr, de, pt).
 *
 * Design (see ADR 0015):
 * - Gemma is allowed to be wrong on relative-time resolution because it has no
 *   reliable wall-clock grounding even when we put `CURRENT TIMESTAMP` in the
 *   prompt (we saw "stasera" → yesterday in real-device traces 2026-05-16).
 * - For SIMPLE expressions ("stasera", "tonight", "ce soir", "esta noche",
 *   "heute abend", "esta noite") the resolution is a pure function of
 *   (clock, zone), so we compute it ourselves and override whatever Gemma said.
 * - For COMPOUND expressions ("stasera tardi", "tomorrow around 9pm", "vendredì
 *   prossimo intorno alle 15") this resolver returns null and the caller keeps
 *   Gemma's `iso_resolved`. The model is good at compositional time reasoning;
 *   we only fix the cases where it has no excuse to be wrong.
 *
 * The matcher is case-insensitive and trims surrounding punctuation but otherwise
 * requires an exact match against the canonical phrases. This is deliberately
 * conservative: a `surface_form` like "stasera tardi" should NOT match "stasera"
 * because the user added information ("tardi") that changes the resolution.
 */
object RelativeDateTimeResolver {
    /**
     * Resolve [surfaceForm] against [now] in [zone] for the given [languageBcp47].
     * Returns the canonical [Instant] for simple matches, or `null` to mean
     * "no deterministic resolution — keep whatever Gemma emitted".
     *
     * `languageBcp47` is the value coming from `StructuredNote.languageBcp47`
     * (Gemma's detection). We use it only to pick which language table to search;
     * if the language is unknown or unsupported, we fall through to a multi-language
     * search across all tables (some users dictate in mixed languages).
     */
    fun resolve(
        surfaceForm: String,
        languageBcp47: String?,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Instant? {
        val normalized = normalize(surfaceForm) ?: return null
        val localNow = LocalDateTime.ofInstant(now, zone)

        val tables = pickTables(languageBcp47)
        for (table in tables) {
            val resolver = table.entries[normalized] ?: continue
            return resolver(localNow).atZone(zone).toInstant()
        }
        return null
    }

    /**
     * Strip leading/trailing punctuation and lowercase. Collapse internal multiple
     * whitespace to single space. Returns null when the result is empty or longer
     * than `MAX_NORMALIZED_LEN` (long phrases are by definition compound — we don't
     * want to match "stasera dopo cena con marco" against any table entry).
     */
    private fun normalize(raw: String): String? {
        val trimmed = raw.trim().trim('.', ',', ';', ':', '!', '?', '"', '‘', '’', '"', '"')
        if (trimmed.isEmpty()) return null
        val collapsed = trimmed.lowercase(Locale.ROOT).replace(MULTI_WS, " ")
        if (collapsed.length > MAX_NORMALIZED_LEN) return null
        return collapsed
    }

    private fun pickTables(languageBcp47: String?): List<Table> {
        val tag = languageBcp47?.lowercase(Locale.ROOT)?.substringBefore('-') ?: return AllTables
        return when (tag) {
            "it" -> listOf(ItalianTable)
            "en" -> listOf(EnglishTable)
            "es" -> listOf(SpanishTable)
            "fr" -> listOf(FrenchTable)
            "de" -> listOf(GermanTable)
            "pt" -> listOf(PortugueseTable)
            else -> AllTables
        }
    }

    private val MULTI_WS = Regex("\\s+")
    private const val MAX_NORMALIZED_LEN = 40

    private class Table(val entries: Map<String, (LocalDateTime) -> LocalDateTime>)

    // ---- Default time-of-day constants ----
    // Anchored to the user's locale defaults from CLAUDE.md UX guidance + common
    // conversational meaning. "morning" = 09:00, "afternoon" = 15:00,
    // "evening" = 20:00, "night" = 22:00. These are wall-clock times in the
    // user's zone, not UTC.
    private val MORNING: LocalTime = LocalTime.of(9, 0)
    private val NOON: LocalTime = LocalTime.of(12, 0)
    private val AFTERNOON: LocalTime = LocalTime.of(15, 0)
    private val EVENING: LocalTime = LocalTime.of(20, 0)
    private val NIGHT: LocalTime = LocalTime.of(22, 0)

    // ---- Per-language tables ----
    // Each entry maps a canonical normalized surface phrase to a function that
    // takes "now in user's zone" and returns the resolved LocalDateTime.

    private val ItalianTable =
        Table(
            mapOf(
                "stasera" to { now -> now.toLocalDate().atTime(EVENING) },
                "stanotte" to { now -> now.toLocalDate().atTime(NIGHT) },
                "stamattina" to { now -> now.toLocalDate().atTime(MORNING) },
                "stamane" to { now -> now.toLocalDate().atTime(MORNING) },
                "oggi" to { now -> now.toLocalDate().atStartOfDay() },
                "oggi pomeriggio" to { now -> now.toLocalDate().atTime(AFTERNOON) },
                "oggi sera" to { now -> now.toLocalDate().atTime(EVENING) },
                "stamattina presto" to { now -> now.toLocalDate().atTime(7, 0) },
                "a mezzogiorno" to { now -> now.toLocalDate().atTime(NOON) },
                "a mezzanotte" to { now -> now.toLocalDate().plusDays(1).atStartOfDay() },
                "domani" to { now -> now.toLocalDate().plusDays(1).atStartOfDay() },
                "domani mattina" to { now -> now.toLocalDate().plusDays(1).atTime(MORNING) },
                "domani pomeriggio" to { now -> now.toLocalDate().plusDays(1).atTime(AFTERNOON) },
                "domani sera" to { now -> now.toLocalDate().plusDays(1).atTime(EVENING) },
                "domani notte" to { now -> now.toLocalDate().plusDays(1).atTime(NIGHT) },
                "dopodomani" to { now -> now.toLocalDate().plusDays(2).atStartOfDay() },
                "ieri" to { now -> now.toLocalDate().minusDays(1).atStartOfDay() },
                "ieri sera" to { now -> now.toLocalDate().minusDays(1).atTime(EVENING) },
                "ieri mattina" to { now -> now.toLocalDate().minusDays(1).atTime(MORNING) },
                "l'altro ieri" to { now -> now.toLocalDate().minusDays(2).atStartOfDay() },
                "lunedì" to nextDow(DayOfWeek.MONDAY),
                "martedì" to nextDow(DayOfWeek.TUESDAY),
                "mercoledì" to nextDow(DayOfWeek.WEDNESDAY),
                "giovedì" to nextDow(DayOfWeek.THURSDAY),
                "venerdì" to nextDow(DayOfWeek.FRIDAY),
                "sabato" to nextDow(DayOfWeek.SATURDAY),
                "domenica" to nextDow(DayOfWeek.SUNDAY),
                "lunedì prossimo" to nextDow(DayOfWeek.MONDAY),
                "martedì prossimo" to nextDow(DayOfWeek.TUESDAY),
                "mercoledì prossimo" to nextDow(DayOfWeek.WEDNESDAY),
                "giovedì prossimo" to nextDow(DayOfWeek.THURSDAY),
                "venerdì prossimo" to nextDow(DayOfWeek.FRIDAY),
                "sabato prossimo" to nextDow(DayOfWeek.SATURDAY),
                "domenica prossima" to nextDow(DayOfWeek.SUNDAY),
                "la prossima settimana" to {
                        now ->
                    now.toLocalDate().plusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay()
                },
                "questa settimana" to {
                        now ->
                    now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay()
                },
            ),
        )

    private val EnglishTable =
        Table(
            mapOf(
                "tonight" to { now -> now.toLocalDate().atTime(EVENING) },
                "today" to { now -> now.toLocalDate().atStartOfDay() },
                "this morning" to { now -> now.toLocalDate().atTime(MORNING) },
                "this afternoon" to { now -> now.toLocalDate().atTime(AFTERNOON) },
                "this evening" to { now -> now.toLocalDate().atTime(EVENING) },
                "at noon" to { now -> now.toLocalDate().atTime(NOON) },
                "at midnight" to { now -> now.toLocalDate().plusDays(1).atStartOfDay() },
                "tomorrow" to { now -> now.toLocalDate().plusDays(1).atStartOfDay() },
                "tomorrow morning" to { now -> now.toLocalDate().plusDays(1).atTime(MORNING) },
                "tomorrow afternoon" to { now -> now.toLocalDate().plusDays(1).atTime(AFTERNOON) },
                "tomorrow evening" to { now -> now.toLocalDate().plusDays(1).atTime(EVENING) },
                "tomorrow night" to { now -> now.toLocalDate().plusDays(1).atTime(NIGHT) },
                "yesterday" to { now -> now.toLocalDate().minusDays(1).atStartOfDay() },
                "yesterday evening" to { now -> now.toLocalDate().minusDays(1).atTime(EVENING) },
                "last night" to { now -> now.toLocalDate().minusDays(1).atTime(NIGHT) },
                "the day after tomorrow" to { now -> now.toLocalDate().plusDays(2).atStartOfDay() },
                "the day before yesterday" to { now -> now.toLocalDate().minusDays(2).atStartOfDay() },
                "monday" to nextDow(DayOfWeek.MONDAY),
                "tuesday" to nextDow(DayOfWeek.TUESDAY),
                "wednesday" to nextDow(DayOfWeek.WEDNESDAY),
                "thursday" to nextDow(DayOfWeek.THURSDAY),
                "friday" to nextDow(DayOfWeek.FRIDAY),
                "saturday" to nextDow(DayOfWeek.SATURDAY),
                "sunday" to nextDow(DayOfWeek.SUNDAY),
                "next monday" to nextDow(DayOfWeek.MONDAY),
                "next tuesday" to nextDow(DayOfWeek.TUESDAY),
                "next wednesday" to nextDow(DayOfWeek.WEDNESDAY),
                "next thursday" to nextDow(DayOfWeek.THURSDAY),
                "next friday" to nextDow(DayOfWeek.FRIDAY),
                "next saturday" to nextDow(DayOfWeek.SATURDAY),
                "next sunday" to nextDow(DayOfWeek.SUNDAY),
                "next week" to {
                        now ->
                    now.toLocalDate().plusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay()
                },
                "this week" to {
                        now ->
                    now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay()
                },
            ),
        )

    private val SpanishTable =
        Table(
            mapOf(
                "esta noche" to { now -> now.toLocalDate().atTime(EVENING) },
                "hoy" to { now -> now.toLocalDate().atStartOfDay() },
                "esta mañana" to { now -> now.toLocalDate().atTime(MORNING) },
                "esta tarde" to { now -> now.toLocalDate().atTime(AFTERNOON) },
                "al mediodía" to { now -> now.toLocalDate().atTime(NOON) },
                "a medianoche" to { now -> now.toLocalDate().plusDays(1).atStartOfDay() },
                // ambiguo con "morning" ma in spagnolo "mañana" standalone = "domani"
                "mañana" to {
                        now ->
                    now.toLocalDate().plusDays(1).atStartOfDay()
                },
                "mañana por la mañana" to { now -> now.toLocalDate().plusDays(1).atTime(MORNING) },
                "mañana por la tarde" to { now -> now.toLocalDate().plusDays(1).atTime(AFTERNOON) },
                "mañana por la noche" to { now -> now.toLocalDate().plusDays(1).atTime(EVENING) },
                "ayer" to { now -> now.toLocalDate().minusDays(1).atStartOfDay() },
                "ayer por la noche" to { now -> now.toLocalDate().minusDays(1).atTime(EVENING) },
                "ayer por la tarde" to { now -> now.toLocalDate().minusDays(1).atTime(AFTERNOON) },
                "anoche" to { now -> now.toLocalDate().minusDays(1).atTime(NIGHT) },
                "pasado mañana" to { now -> now.toLocalDate().plusDays(2).atStartOfDay() },
                "anteayer" to { now -> now.toLocalDate().minusDays(2).atStartOfDay() },
                "lunes" to nextDow(DayOfWeek.MONDAY),
                "martes" to nextDow(DayOfWeek.TUESDAY),
                "miércoles" to nextDow(DayOfWeek.WEDNESDAY),
                "jueves" to nextDow(DayOfWeek.THURSDAY),
                "viernes" to nextDow(DayOfWeek.FRIDAY),
                "sábado" to nextDow(DayOfWeek.SATURDAY),
                "domingo" to nextDow(DayOfWeek.SUNDAY),
                "el lunes que viene" to nextDow(DayOfWeek.MONDAY),
                "el martes que viene" to nextDow(DayOfWeek.TUESDAY),
                "el miércoles que viene" to nextDow(DayOfWeek.WEDNESDAY),
                "el jueves que viene" to nextDow(DayOfWeek.THURSDAY),
                "el viernes que viene" to nextDow(DayOfWeek.FRIDAY),
                "el sábado que viene" to nextDow(DayOfWeek.SATURDAY),
                "el domingo que viene" to nextDow(DayOfWeek.SUNDAY),
                "el próximo lunes" to nextDow(DayOfWeek.MONDAY),
                "el próximo martes" to nextDow(DayOfWeek.TUESDAY),
                "el próximo miércoles" to nextDow(DayOfWeek.WEDNESDAY),
                "el próximo jueves" to nextDow(DayOfWeek.THURSDAY),
                "el próximo viernes" to nextDow(DayOfWeek.FRIDAY),
                "el próximo sábado" to nextDow(DayOfWeek.SATURDAY),
                "el próximo domingo" to nextDow(DayOfWeek.SUNDAY),
                "la próxima semana" to {
                        now ->
                    now.toLocalDate().plusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay()
                },
                "esta semana" to {
                        now ->
                    now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay()
                },
            ),
        )

    private val FrenchTable =
        Table(
            mapOf(
                "ce soir" to { now -> now.toLocalDate().atTime(EVENING) },
                "cette nuit" to { now -> now.toLocalDate().atTime(NIGHT) },
                "aujourd'hui" to { now -> now.toLocalDate().atStartOfDay() },
                "ce matin" to { now -> now.toLocalDate().atTime(MORNING) },
                "cet après-midi" to { now -> now.toLocalDate().atTime(AFTERNOON) },
                "à midi" to { now -> now.toLocalDate().atTime(NOON) },
                "à minuit" to { now -> now.toLocalDate().plusDays(1).atStartOfDay() },
                "demain" to { now -> now.toLocalDate().plusDays(1).atStartOfDay() },
                "demain matin" to { now -> now.toLocalDate().plusDays(1).atTime(MORNING) },
                "demain après-midi" to { now -> now.toLocalDate().plusDays(1).atTime(AFTERNOON) },
                "demain soir" to { now -> now.toLocalDate().plusDays(1).atTime(EVENING) },
                "hier" to { now -> now.toLocalDate().minusDays(1).atStartOfDay() },
                "hier soir" to { now -> now.toLocalDate().minusDays(1).atTime(EVENING) },
                "hier matin" to { now -> now.toLocalDate().minusDays(1).atTime(MORNING) },
                "après-demain" to { now -> now.toLocalDate().plusDays(2).atStartOfDay() },
                "avant-hier" to { now -> now.toLocalDate().minusDays(2).atStartOfDay() },
                "lundi" to nextDow(DayOfWeek.MONDAY),
                "mardi" to nextDow(DayOfWeek.TUESDAY),
                "mercredi" to nextDow(DayOfWeek.WEDNESDAY),
                "jeudi" to nextDow(DayOfWeek.THURSDAY),
                "vendredi" to nextDow(DayOfWeek.FRIDAY),
                "samedi" to nextDow(DayOfWeek.SATURDAY),
                "dimanche" to nextDow(DayOfWeek.SUNDAY),
                "lundi prochain" to nextDow(DayOfWeek.MONDAY),
                "mardi prochain" to nextDow(DayOfWeek.TUESDAY),
                "mercredi prochain" to nextDow(DayOfWeek.WEDNESDAY),
                "jeudi prochain" to nextDow(DayOfWeek.THURSDAY),
                "vendredi prochain" to nextDow(DayOfWeek.FRIDAY),
                "samedi prochain" to nextDow(DayOfWeek.SATURDAY),
                "dimanche prochain" to nextDow(DayOfWeek.SUNDAY),
                "la semaine prochaine" to {
                        now ->
                    now.toLocalDate().plusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay()
                },
                "cette semaine" to {
                        now ->
                    now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay()
                },
            ),
        )

    private val GermanTable =
        Table(
            mapOf(
                "heute abend" to { now -> now.toLocalDate().atTime(EVENING) },
                "heute nacht" to { now -> now.toLocalDate().atTime(NIGHT) },
                "heute" to { now -> now.toLocalDate().atStartOfDay() },
                "heute morgen" to { now -> now.toLocalDate().atTime(MORNING) },
                "heute nachmittag" to { now -> now.toLocalDate().atTime(AFTERNOON) },
                "mittags" to { now -> now.toLocalDate().atTime(NOON) },
                "um mitternacht" to { now -> now.toLocalDate().plusDays(1).atStartOfDay() },
                // "morgen" standalone = tomorrow; "heute morgen" = this morning
                "morgen" to {
                        now ->
                    now.toLocalDate().plusDays(1).atStartOfDay()
                },
                "morgen früh" to { now -> now.toLocalDate().plusDays(1).atTime(MORNING) },
                "morgen nachmittag" to { now -> now.toLocalDate().plusDays(1).atTime(AFTERNOON) },
                "morgen abend" to { now -> now.toLocalDate().plusDays(1).atTime(EVENING) },
                "gestern" to { now -> now.toLocalDate().minusDays(1).atStartOfDay() },
                "gestern abend" to { now -> now.toLocalDate().minusDays(1).atTime(EVENING) },
                "gestern morgen" to { now -> now.toLocalDate().minusDays(1).atTime(MORNING) },
                "übermorgen" to { now -> now.toLocalDate().plusDays(2).atStartOfDay() },
                "vorgestern" to { now -> now.toLocalDate().minusDays(2).atStartOfDay() },
                "montag" to nextDow(DayOfWeek.MONDAY),
                "dienstag" to nextDow(DayOfWeek.TUESDAY),
                "mittwoch" to nextDow(DayOfWeek.WEDNESDAY),
                "donnerstag" to nextDow(DayOfWeek.THURSDAY),
                "freitag" to nextDow(DayOfWeek.FRIDAY),
                "samstag" to nextDow(DayOfWeek.SATURDAY),
                "sonntag" to nextDow(DayOfWeek.SUNDAY),
                "nächsten montag" to nextDow(DayOfWeek.MONDAY),
                "nächsten dienstag" to nextDow(DayOfWeek.TUESDAY),
                "nächsten mittwoch" to nextDow(DayOfWeek.WEDNESDAY),
                "nächsten donnerstag" to nextDow(DayOfWeek.THURSDAY),
                "nächsten freitag" to nextDow(DayOfWeek.FRIDAY),
                "nächsten samstag" to nextDow(DayOfWeek.SATURDAY),
                "nächsten sonntag" to nextDow(DayOfWeek.SUNDAY),
                "nächste woche" to {
                        now ->
                    now.toLocalDate().plusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay()
                },
                "diese woche" to {
                        now ->
                    now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay()
                },
            ),
        )

    private val PortugueseTable =
        Table(
            mapOf(
                "esta noite" to { now -> now.toLocalDate().atTime(EVENING) },
                "hoje" to { now -> now.toLocalDate().atStartOfDay() },
                "esta manhã" to { now -> now.toLocalDate().atTime(MORNING) },
                "esta tarde" to { now -> now.toLocalDate().atTime(AFTERNOON) },
                "ao meio-dia" to { now -> now.toLocalDate().atTime(NOON) },
                "à meia-noite" to { now -> now.toLocalDate().plusDays(1).atStartOfDay() },
                "amanhã" to { now -> now.toLocalDate().plusDays(1).atStartOfDay() },
                "amanhã de manhã" to { now -> now.toLocalDate().plusDays(1).atTime(MORNING) },
                "amanhã à tarde" to { now -> now.toLocalDate().plusDays(1).atTime(AFTERNOON) },
                "amanhã à noite" to { now -> now.toLocalDate().plusDays(1).atTime(EVENING) },
                "ontem" to { now -> now.toLocalDate().minusDays(1).atStartOfDay() },
                "ontem à noite" to { now -> now.toLocalDate().minusDays(1).atTime(EVENING) },
                "ontem de manhã" to { now -> now.toLocalDate().minusDays(1).atTime(MORNING) },
                "depois de amanhã" to { now -> now.toLocalDate().plusDays(2).atStartOfDay() },
                "anteontem" to { now -> now.toLocalDate().minusDays(2).atStartOfDay() },
                "segunda-feira" to nextDow(DayOfWeek.MONDAY),
                "terça-feira" to nextDow(DayOfWeek.TUESDAY),
                "quarta-feira" to nextDow(DayOfWeek.WEDNESDAY),
                "quinta-feira" to nextDow(DayOfWeek.THURSDAY),
                "sexta-feira" to nextDow(DayOfWeek.FRIDAY),
                "sábado" to nextDow(DayOfWeek.SATURDAY),
                "domingo" to nextDow(DayOfWeek.SUNDAY),
                "próxima segunda" to nextDow(DayOfWeek.MONDAY),
                "próxima terça" to nextDow(DayOfWeek.TUESDAY),
                "próxima quarta" to nextDow(DayOfWeek.WEDNESDAY),
                "próxima quinta" to nextDow(DayOfWeek.THURSDAY),
                "próxima sexta" to nextDow(DayOfWeek.FRIDAY),
                "próxima semana" to {
                        now ->
                    now.toLocalDate().plusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay()
                },
                "esta semana" to {
                        now ->
                    now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay()
                },
            ),
        )

    private val AllTables: List<Table> =
        listOf(
            ItalianTable,
            EnglishTable,
            SpanishTable,
            FrenchTable,
            GermanTable,
            PortugueseTable,
        )

    /**
     * Resolves a weekday name to the NEXT occurrence at or after today.
     * "lunedì" said on a Monday means today; said on Tuesday means in 6 days.
     * For "lunedì prossimo" ("next Monday") this still resolves to the next
     * upcoming Monday — Italian doesn't make a strong distinction between
     * "this Monday" and "next Monday" in everyday speech, and we'd rather
     * be wrong by a few days than wrong by a week. See ADR 0015.
     */
    private fun nextDow(target: DayOfWeek): (LocalDateTime) -> LocalDateTime =
        { now ->
            val today: LocalDate = now.toLocalDate()
            val daysUntil = ((target.value - today.dayOfWeek.value + 7) % 7).toLong()
            today.plusDays(daysUntil).atStartOfDay()
        }

    /**
     * Future-bias guard for datetimes that came from GEMMA's `iso_resolved` (NOT from
     * [resolve], whose output is already future-correct via [nextDow]). Gemma
     * occasionally anchors an ambiguous time-only mention to a past date — real-device
     * 2026-05-19: a bare "alle quindici e trenta" came back dated three days earlier.
     * For a voice note the intent is almost always the next future occurrence.
     *
     * Rolls [resolved] forward whole days (preserving its wall-clock time in [zone]) to
     * the first occurrence at or after [now], but ONLY when all of these hold:
     *  - [resolved] is before [now] (otherwise there is nothing to fix);
     *  - the gap is at most [MAX_BACKSHIFT_DAYS] days (a datetime far in the past is more
     *    likely a deliberate historical reference than a misanchored "this week");
     *  - [surfaceForm] names no specific non-future day — past ("ieri", "yesterday")
     *    or present ("oggi", "today"). If the user named the day, we keep it; e.g.
     *    "oggi alle 9:00" stays today even if 9:00 already passed. Future-day words
     *    ("domani"/"tomorrow") are NOT in that set, so a mis-anchored "domani alle 9"
     *    still gets rolled forward.
     *
     * Otherwise [resolved] is returned unchanged. This never fabricates a datetime; it
     * only shifts one Gemma already produced, so the "no invention" pillar holds.
     */
    fun biasToFuture(
        resolved: Instant,
        surfaceForm: String,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Instant {
        if (!resolved.isBefore(now)) return resolved
        if (Duration.between(resolved, now).toDays() > MAX_BACKSHIFT_DAYS) return resolved
        if (hasNonFutureDayAnchor(surfaceForm)) return resolved

        val nowLocal = LocalDateTime.ofInstant(now, zone)
        var shifted = LocalDateTime.ofInstant(resolved, zone)
        while (shifted.isBefore(nowLocal)) {
            shifted = shifted.plusDays(1)
        }
        return shifted.atZone(zone).toInstant()
    }

    private fun hasNonFutureDayAnchor(surfaceForm: String): Boolean {
        val words =
            surfaceForm.lowercase(Locale.ROOT)
                .split(NON_WORD)
                .filter(String::isNotEmpty)
        return words.any { it in NON_FUTURE_DAY_TOKENS }
    }

    private val NON_WORD = Regex("[^\\p{L}]+")

    /**
     * Words that anchor a mention to a specific PAST or PRESENT (today) day, across the
     * v1 languages. When any of these is present we leave the datetime where it is — the
     * user named the day, so we must not roll the time to a different day (e.g. "oggi
     * alle 9:00" stays today even if 9:00 has already passed).
     *
     * Future-day words ("domani", "tomorrow", "mañana", …) are deliberately NOT here: if
     * the model mis-anchors "domani alle 9" to a past date, we DO want the future-bias
     * guard to roll it forward. "aujourd'hui" splits into "aujourd"/"hui" under
     * [NON_WORD]; we list "aujourd" so the French today-anchor is still caught.
     */
    private val NON_FUTURE_DAY_TOKENS =
        setOf(
            // it — past + today
            "ieri", "scorso", "scorsa", "scorsi", "scorse", "fa", "passato", "passata", "oggi",
            // en — past + today
            "yesterday", "last", "ago", "today",
            // es — past + today
            "ayer", "anoche", "pasado", "pasada", "hace", "hoy",
            // fr — past + today
            "hier", "dernier", "dernière", "passé", "passée", "aujourd", "hui",
            // de — past + today
            "gestern", "vorgestern", "letzte", "letzten", "letzter", "letztes", "vor", "heute",
            // pt — past + today
            "ontem", "anteontem", "passado", "passada", "atrás", "hoje",
        )

    private const val MAX_BACKSHIFT_DAYS = 8L
}
