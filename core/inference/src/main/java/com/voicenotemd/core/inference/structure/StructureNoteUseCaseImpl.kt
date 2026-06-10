package com.voicenotemd.core.inference.structure

import com.voicenotemd.core.common.domain.DateMention
import com.voicenotemd.core.common.domain.Language
import com.voicenotemd.core.common.domain.Note
import com.voicenotemd.core.common.domain.RawDateMention
import com.voicenotemd.core.common.domain.StructuredNote
import com.voicenotemd.core.common.domain.Tag
import com.voicenotemd.core.common.result.DomainResult
import com.voicenotemd.core.common.usecase.StructureNoteUseCase
import com.voicenotemd.core.common.usecase.StructuringResult
import com.voicenotemd.core.inference.normalize.CommitmentDeduplicator
import com.voicenotemd.core.inference.normalize.DeterministicMentionScanner
import com.voicenotemd.core.inference.normalize.MarkdownBodyFormatter
import com.voicenotemd.core.inference.normalize.RelativeDateTimeResolver
import com.voicenotemd.core.inference.normalize.TagValidator
import com.voicenotemd.core.inference.prompt.LanguageScopedPromptTemplate
import com.voicenotemd.core.inference.prompt.PromptTemplate
import com.voicenotemd.core.inference.prompt.StricterPromptTemplate
import com.voicenotemd.core.inference.schema.StructuredNoteParser
import com.voicenotemd.core.inference.session.GemmaSession
import com.voicenotemd.core.inference.session.GemmaUnavailableException
import com.voicenotemd.core.inference.session.InferenceBackend
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Concrete orchestrator for the prompt → parse → retry → fallback flow described in
 * ADR 0005. The use case never throws on routine failures — it always returns a [Note],
 * structured or plain. The user is never blocked.
 *
 * @param idGenerator pluggable so tests can pin note IDs.
 * @param clock pluggable so tests can pin createdAt/updatedAt.
 */
class StructureNoteUseCaseImpl(
    private val session: GemmaSession,
    private val basePrompt: PromptTemplate,
    private val parser: StructuredNoteParser,
    private val clock: Clock = Clock.systemUTC(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : StructureNoteUseCase {
    /**
     * Delegates to [GemmaSession.warmUp] so the ~1.5 GB engine can be loaded while the
     * user is dictating, eliminating the cold-start latency from the first inference.
     */
    override suspend fun warmUp() {
        session.warmUp()
    }

    override suspend fun invoke(
        transcript: String,
        forceLanguage: Language?,
        existingTags: List<String>,
    ): StructuringResult {
        // Treat Language.Unknown as "no pin" (auto-detect). A non-null Unknown would
        // otherwise wrap the prompt in a nonsensical "LANGUAGE LOCK … Unknown (und)"
        // directive AND skip language detection in buildStructuredNote. This reaches us
        // from NoteDetailViewModel.handleRestructure, which forwards the note's stored
        // language verbatim — and a plain-text note's language can be Unknown.
        val pinnedLanguage = forceLanguage?.takeIf { it != Language.Unknown }
        val cleaned = transcript.trim()
        if (cleaned.isEmpty()) {
            // Defensive: the capture flow guards against this, but we still want a sane
            // fallback rather than producing a blank note that confuses the user.
            return StructuringResult(
                note = plainTextFallback(cleaned, pinnedLanguage),
                lastRawResponse = null,
            )
        }

        var lastRaw: String? = null

        // Engine warm-up FIRST, OUTSIDE the Pass 1 timeout. This is the 2026-05-17 fix
        // (see ADR 0016): the original design conflated "load 1.5 GB from disk" (15-30s,
        // highly variable depending on thermal state, disk pressure, and whether GPU
        // init falls through to CPU mid-session) with "actually run inference" (5-15s,
        // predictable). When the engine was released by onTrimMemory between captures
        // and the next dictation hit a cold reload, the conflated budget would expire
        // on the load alone — the model never even got to see the prompt. The user-
        // facing symptom was a short note timing out at exactly the cold-start budget
        // (incident: 278-char note timed out at 68900ms = 55000 + 278*50, matching
        // coldStartBudgetFor() to the millisecond).
        //
        // We give warm-up its own generous untimed-ish budget. If the model file is
        // genuinely missing/corrupt, GemmaUnavailableException surfaces inside the
        // generate() call below the standard way and we fall back to plain text.
        runCatching {
            withTimeoutOrNull(ENGINE_LOAD_BUDGET_MS) { session.warmUp() }
        }

        // Pass 1 — base prompt. The budget now only has to cover prefill + decode,
        // not engine load. CRITICAL: budget is BACKEND-DEPENDENT. The 2026-05-17
        // evening incident showed real-device CPU inference taking 78s for a 470-char
        // note (Pixel device where Backend.GPU init fails on the current LiteRT-LM
        // build and we fall back to CPU). The GPU formula (50ms/char) cannot cover
        // CPU; on CPU the per-char cost is ~150-180ms because the ~8KB static prompt
        // has to be re-prefilled on every Conversation, with no KV cache reuse.
        //
        // We read session.backend() AFTER warmUp so the engine is loaded and we know
        // which path is live. UNKNOWN is treated as CPU-like (assume the worst) so a
        // stub or pre-load read can't underestimate the budget.
        //
        // We pass `now` so the prompt's CURRENT TIMESTAMP marker resolves to the actual
        // wall-clock time the user is dictating — this lets Gemma anchor relative
        // references ("tomorrow at 3pm", "domani alle 15", "venerdì prossimo") to real
        // ISO-8601 timestamps in the resulting JSON instead of returning `null`.
        //
        // We also pass `existingTags` so the prompt's EXISTING_TAGS list nudges Gemma
        // to reuse a tag the user's corpus already has instead of coining a synonymous
        // new one ("app-development" already exists → don't emit "app" for the same
        // topic). See ADR 0012.
        val now = Instant.now(clock)
        // When the user has PINNED a language, wrap the base prompt with an explicit
        // single-language directive so the pin actually constrains the model's output.
        // forceLanguage previously never reached the prompt (it only set the stored
        // Language enum + ASR locale), so Gemma auto-detected and could emit a mixed-
        // language title or foreign tags on a short note (real device, 2026-05-22).
        // With no pin, the base prompt's own "detect the language" rule applies.
        val effectiveBase: PromptTemplate =
            pinnedLanguage?.let { LanguageScopedPromptTemplate(basePrompt, it) } ?: basePrompt
        val stricterPrompt = StricterPromptTemplate(effectiveBase)
        val backend = session.backend()
        // Surfaced on every result so the UI can show the one-time "running on CPU"
        // advisory (ADR 0016 UX follow-up): the user deserves to know why the same
        // app is slower on their hardware. UNKNOWN is not reported as CPU — no engine
        // was loaded, so there is nothing meaningful to advise about.
        val cpuFallback = backend == InferenceBackend.CPU
        val pass1Budget = coldStartBudgetFor(cleaned, backend)
        val pass1Outcome =
            runCatching {
                withTimeoutOrNull(pass1Budget) {
                    session.generate(effectiveBase.render(cleaned, now, existingTags = existingTags))
                }
            }
        val pass1Raw: String? = pass1Outcome.getOrNull()
        if (pass1Raw == null) {
            val reason =
                pass1Outcome.exceptionOrNull()?.let { "exception: ${it.message}" }
                    ?: "timeout after ${pass1Budget}ms"
            return StructuringResult(
                note = plainTextFallback(cleaned, pinnedLanguage),
                lastRawResponse = "Pass 1 failed ($reason)",
                cpuFallback = cpuFallback,
            )
        }
        lastRaw = pass1Raw
        tryBuildStructuredNote(pass1Raw, cleaned, pinnedLanguage)
            ?.let { return StructuringResult(note = it, lastRawResponse = null, cpuFallback = cpuFallback) }

        // Pass 2 — stricter prompt. Engine is now warm so we drop the engine-load
        // overhead, but the prefill cost still scales with transcript length and
        // backend (CPU prefill is ~3-4× slower than GPU). Same `now` and `existingTags`
        // as Pass 1 so resolution and tag-consistency are applied across attempts.
        val pass2Budget = warmBudgetFor(cleaned, backend)
        val pass2Outcome =
            runCatching {
                withTimeoutOrNull(pass2Budget) {
                    session.generate(stricterPrompt.render(cleaned, now, existingTags = existingTags))
                }
            }
        val pass2Raw: String? = pass2Outcome.getOrNull()
        if (pass2Raw != null) {
            lastRaw = pass2Raw
            tryBuildStructuredNote(pass2Raw, cleaned, pinnedLanguage)
                ?.let { return StructuringResult(note = it, lastRawResponse = null, cpuFallback = cpuFallback) }
        } else {
            val pass2Reason =
                pass2Outcome.exceptionOrNull()?.let { "exception: ${it.message}" }
                    ?: "timeout after ${pass2Budget}ms"
            lastRaw = "$lastRaw\n\nPass 2 failed ($pass2Reason)"
        }

        // Both passes failed. Save the transcript verbatim so the user keeps the content,
        // and surface the last raw response so the UI can show what actually came back.
        return StructuringResult(
            note = plainTextFallback(cleaned, pinnedLanguage),
            lastRawResponse = lastRaw,
            cpuFallback = cpuFallback,
        )
    }

    private fun tryBuildStructuredNote(
        raw: String,
        transcript: String,
        forceLanguage: Language?,
    ): Note? {
        val parsed =
            when (val r = parser.parse(raw)) {
                is DomainResult.Success -> r.value
                is DomainResult.Failure -> return null
            }
        return buildStructuredNote(parsed, transcript, forceLanguage)
    }

    /**
     * Build the final [Note] from Gemma's parsed [StructuredNote] intent. This is
     * where the deterministic post-processing pipeline runs (see ADR 0015):
     *
     *  1. `RelativeDateTimeResolver` overrides Gemma's `iso_resolved` on simple
     *     multilingual relative expressions ("stasera", "tonight", "domani sera"…).
     *  2. `TagValidator` strips tags that have no anchor in the transcript —
     *     kills hallucinated tags (corpus consistency is handled upstream in
     *     the prompt's EXISTING_TAGS, not here).
     *  3. `MarkdownBodyFormatter` enforces line breaks before checkboxes/bullets
     *     and collapses excess blank lines.
     *  4. Title sanitization strips trailing punctuation and caps length.
     *
     * Every step is a pure subtractive/normalizing transform — none can add
     * content or fabricate fields. The "no invention" pillar (CLAUDE.md §3.4)
     * is preserved through the whole pipeline.
     */
    private fun buildStructuredNote(
        s: StructuredNote,
        transcript: String,
        forceLanguage: Language?,
    ): Note {
        val now = Instant.now(clock)
        val resolvedLanguage =
            forceLanguage
                ?: Language.fromBcp47(s.languageBcp47).takeIf { it != Language.Unknown }
                ?: Language.Unknown

        // 1. Mentions: deterministic override for simple relative expressions.
        //    First drop JUNK mentions: when a note has no time reference at all, E2B
        //    sometimes still emits a placeholder mention with an empty surface_form or the
        //    literal string "null"/"none" (real device 2026-05-22: the "montagna" note
        //    showed a "null" datetime chip). These carry no information and must never
        //    surface in the UI. A genuinely vague-but-real phrase ("una di queste sere")
        //    has a real surface_form and is kept, resolving to null by design.
        val modelMentions =
            s.mentions
                .filter { it.surfaceForm.isJunkDateSurface().not() }
                .map { resolveMention(it, s.languageBcp47, now) }
        // Deterministic backstop (review 2026-06-10): when the model emitted NO
        // mentions, scan the transcript for unambiguous future-oriented datetime
        // references ("entro venerdì", "domani alle 15") and resolve them with the
        // ADR 0015 machinery. Surface forms are literal transcript substrings —
        // nothing is invented. When the model DID emit mentions, its judgment wins.
        val mentions =
            modelMentions.ifEmpty {
                DeterministicMentionScanner.scan(transcript, s.languageBcp47, now)
                    .map { DateMention(surfaceForm = it.surfaceForm, resolved = it.resolved) }
            }

        // 2. Tags: hallucination guard against the transcript. The prior corpus
        //    steers tag *generation* upstream in the prompt (EXISTING_TAGS, ADR 0012);
        //    this downstream guard is a pure backstop and does not re-consult it.
        val rawTags = s.tags.mapNotNull(Tag::normalize).distinct()
        val validatedTags = TagValidator.validate(rawTags, transcript)

        // 4. Title: strip trailing punctuation that the model occasionally adds
        //    ("Riunione con Marco." or "Domani?"), then cap. (Computed before the
        //    body so we can de-duplicate a repeated title heading below.)
        val cleanedTitle =
            s.title
                .trim()
                .trimEnd(*TRAILING_TITLE_PUNCTUATION)
                .take(MAX_TITLE_LEN)
                .trim()

        // 3. Body: drop a leading `# Title` heading E2B sometimes repeats despite
        //    the prompt forbidding it (the title is a separate field, and the
        //    exporter adds its own H1), then enforce checkbox/bullet line breaks
        //    and collapse blank lines. After the lines are normalized, remove
        //    prose sentences that duplicate a checkbox (CommitmentDeduplicator —
        //    the model re-emits commitments in both shapes despite v13/v14's
        //    rules; round-4 eval 2026-06-10), then re-format to collapse the
        //    blank lines the removal may leave behind. Every pass is
        //    content-preserving.
        val formattedBody =
            MarkdownBodyFormatter.format(
                CommitmentDeduplicator.dedupe(
                    MarkdownBodyFormatter.format(
                        stripDuplicateTitleHeading(s.bodyMarkdown, cleanedTitle),
                    ),
                ),
            )

        return Note(
            id = idGenerator(),
            title = cleanedTitle,
            bodyMarkdown = formattedBody,
            tags = validatedTags,
            mentions = mentions,
            language = resolvedLanguage,
            createdAt = now,
            updatedAt = now,
            structured = true,
        )
    }

    /**
     * Drop a leading first line from [body] when it merely repeats [title] — either
     * as a `# Heading` or as a plain prose echo ("Lista della spesa." as the first
     * body line, real-device 2026-06-10). The title is a separate field and the
     * exporter prepends its own `# Title`, so the echo shows the title twice.
     *
     * Content-preserving: the text survives in the title field, and only an exact
     * (case-insensitive, trailing-punctuation-insensitive) match is removed — a
     * genuine first sentence that happens to START like the title is left intact.
     */
    private fun stripDuplicateTitleHeading(
        body: String,
        title: String,
    ): String {
        val titleText = title.trim()
        if (titleText.isEmpty()) return body
        val trimmed = body.trimStart()
        val newlineIdx = trimmed.indexOf('\n')
        val firstLine = if (newlineIdx < 0) trimmed else trimmed.substring(0, newlineIdx)
        val lineText =
            firstLine
                .trimStart('#')
                .trim()
                .trimEnd(*TRAILING_TITLE_PUNCTUATION)
                .trim()
        val normalizedTitle = titleText.trimEnd(*TRAILING_TITLE_PUNCTUATION).trim()
        if (!lineText.equals(normalizedTitle, ignoreCase = true)) return body
        return if (newlineIdx < 0) "" else trimmed.substring(newlineIdx + 1).trimStart()
    }

    /**
     * Resolve a [RawDateMention]'s ISO string into an [Instant].
     *
     * Order of operations matters: we first ask [RelativeDateTimeResolver] whether
     * the surface form is a simple multilingual relative expression we can
     * canonicalize ourselves. If so, that wins — Gemma's `iso_resolved` is
     * ignored for these (it was the source of "stasera"→yesterday and similar
     * bugs we hit during real-device testing 2026-05-16). For everything else
     * — compound expressions, numeric times, explicit dates — we trust Gemma's
     * resolution and only run our parser cascade to convert it to [Instant].
     */
    private fun resolveMention(
        raw: RawDateMention,
        languageBcp47: String?,
        now: Instant,
    ): DateMention {
        val deterministic =
            RelativeDateTimeResolver.resolve(
                surfaceForm = raw.surfaceForm,
                languageBcp47 = languageBcp47,
                now = now,
            )
        val resolved =
            if (deterministic != null) {
                // Our deterministic resolver already biases weekdays/relative phrases to
                // the future (nextDow), so its output is trusted as-is.
                deterministic
            } else {
                // Gemma's own resolution. Apply the future-bias guard: Gemma sometimes
                // anchors a bare time-only mention to a PAST date (real-device 2026-05-19:
                // "alle quindici e trenta" → three days prior). See
                // RelativeDateTimeResolver.biasToFuture for the exact, conservative rule.
                raw.isoResolved
                    ?.takeIf(String::isNotBlank)
                    ?.let(::tryParseInstant)
                    ?.let { RelativeDateTimeResolver.biasToFuture(it, raw.surfaceForm, now) }
                    // Last resort (real-device 2026-06-10: "domani alle 15" emitted with
                    // iso null): run the deterministic scanner ON THE SURFACE FORM itself.
                    // It handles the compound day+time merge the simple-table resolver
                    // refuses. Accepted ONLY when the scan covers the WHOLE surface —
                    // a partial match ("Friday" inside "the third Friday of the month")
                    // means the full phrase carries extra semantics the scanner cannot
                    // honor; resolving from the fragment would be wrong, so it stays
                    // null per the "genuinely vague" contract.
                    ?: DeterministicMentionScanner.scan(raw.surfaceForm, languageBcp47, now)
                        .firstOrNull()
                        ?.takeIf { scanned ->
                            scanned.surfaceForm.trim().equals(
                                raw.surfaceForm.trim().trimEnd('.', ',', ';', '!', '?').trim(),
                                ignoreCase = true,
                            )
                        }
                        ?.resolved
            }
        return DateMention(surfaceForm = raw.surfaceForm, resolved = resolved)
    }

    /**
     * True when a mention's surface form carries no real date/time reference and is just
     * a placeholder the model emitted for a note that has none. We drop these so the UI
     * never shows an empty or `"null"` datetime chip. A real vague phrase like "una di
     * queste sere" is NOT junk — it has meaningful text and is kept (resolving to null).
     */
    private fun String.isJunkDateSurface(): Boolean {
        val s = trim().trim('"').trim()
        return s.isEmpty() ||
            s.equals("null", ignoreCase = true) ||
            s.equals("none", ignoreCase = true) ||
            // A surface with no letters ("2" from "il 2 o 21" — real device 2026-06-10)
            // is never a complete datetime reference: bare numbers are ambiguous noise
            // and render as meaningless chips. Real surfaces always carry a word
            // ("alle 15", "domani", "entro venerdì").
            s.none { it.isLetter() }
    }

    private fun tryParseInstant(iso: String): Instant? {
        // Gemma's `iso_resolved` field can take three shapes:
        //  1. ISO instant ending in Z: "2026-05-14T13:30:00Z" — `Instant.parse` handles
        //     this directly.
        //  2. Offset datetime: "2026-05-14T15:00:00+02:00" — the most common case for
        //     resolved times in our context (user is in a fixed TZ). `Instant.parse`
        //     REJECTS this format because it requires the Z marker; we route it through
        //     `OffsetDateTime.parse` instead.
        //  3. Date only: "2026-05-14" — emitted when the time-of-day is not stated.
        //     We anchor it to start-of-day in the user's timezone so it round-trips to
        //     a meaningful Instant.
        //
        // Without this cascade, formats (2) and (3) silently degrade to `null` even
        // though Gemma resolved them correctly. We saw both in the field — see ADR 0009.
        runCatching { return Instant.parse(iso) }
        runCatching { return OffsetDateTime.parse(iso).toInstant() }
        runCatching {
            return LocalDate.parse(iso)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
        }
        // Genuinely unparseable — keep the surface form, set resolved = null. Never invent.
        return null
    }

    private fun plainTextFallback(
        transcript: String,
        forceLanguage: Language?,
    ): Note {
        val now = Instant.now(clock)
        val firstLine =
            transcript.lineSequence()
                .map(String::trim)
                .firstOrNull(String::isNotEmpty)
                .orEmpty()
        val title = firstLine.take(MAX_TITLE_LEN).ifEmpty { "Untitled note" }
        return Note(
            id = idGenerator(),
            title = title,
            bodyMarkdown = transcript.ifBlank { "" },
            tags = emptyList(),
            // Even an unstructured note deserves its datetime chips when the
            // transcript carries unambiguous references ("sabato" in the round-4
            // "due cose" fallback, 2026-06-10): the deterministic scanner is
            // model-free, so it works exactly the same on the fallback path.
            mentions =
                DeterministicMentionScanner.scan(transcript, forceLanguage?.bcp47, now)
                    .map { DateMention(surfaceForm = it.surfaceForm, resolved = it.resolved) },
            language = forceLanguage ?: Language.Unknown,
            createdAt = now,
            updatedAt = now,
            structured = false,
        )
    }

    /**
     * Pass 1 budget. Since the 2026-05-17 ADR 0016 split, engine load happens
     * BEFORE this timeout starts — so this budget only has to cover prefill +
     * decode for a warm engine.
     *
     * BOTH terms are backend-dependent (revised 2026-05-17 evening after the
     * second incident on the same 278-char note timed out at exactly the
     * GPU-baselined budget):
     *
     *  - baseline ≈ static-prompt prefill + decode. The static prompt is
     *    ~8 KB / ~2000 tokens and is re-prefilled on every Conversation
     *    (no KV cache reuse across calls). On CPU this dominates total
     *    latency (~46s empirical); on GPU it's negligible (~3-5s).
     *  - per-char ≈ user-transcript prefill cost. Linear in transcript
     *    length on both backends, but CPU is ~3-4× the GPU per-token cost.
     *
     * Empirical fit from 2026-05-17 evening Pixel CPU traces:
     *   T(L_chars) ≈ 46s + 0.068s × L  ⇒  470 char → 78s, 278 char → 65s
     */
    private fun coldStartBudgetFor(
        transcript: String,
        backend: InferenceBackend,
    ): Long {
        val baseline = baselineBudgetMs(backend)
        val perChar = perCharBudgetMs(backend)
        return (baseline + transcript.length * perChar).coerceAtMost(MAX_PASS_BUDGET_MS)
    }

    /**
     * Pass 2 budget. The baseline is slightly smaller than Pass 1 because the
     * engine has just served Pass 1 — but the static-prompt prefill cost still
     * applies (no KV cache reuse across Conversations), so on CPU the warm
     * baseline only saves the small "engine-warmup race" slack, not the prompt
     * prefill.
     */
    private fun warmBudgetFor(
        transcript: String,
        backend: InferenceBackend,
    ): Long {
        val baseline = warmBaselineBudgetMs(backend)
        val perChar = perCharBudgetMs(backend)
        return (baseline + transcript.length * perChar).coerceAtMost(MAX_PASS_BUDGET_MS)
    }

    /**
     * Backend-specific Pass 1 baseline. GPU: ~15s (covers warm-up race + small
     * GPU prefill + decode). CPU/UNKNOWN: ~60s (covers static-prompt prefill
     * which dominates CPU latency). UNKNOWN treated as CPU to never under-allocate.
     */
    private fun baselineBudgetMs(backend: InferenceBackend): Long =
        when (backend) {
            InferenceBackend.GPU -> COLD_START_BASE_GPU_MS
            InferenceBackend.CPU, InferenceBackend.UNKNOWN -> COLD_START_BASE_CPU_MS
        }

    /**
     * Backend-specific Pass 2 baseline. Slightly trimmed vs Pass 1 (engine is
     * already known-loaded), but on CPU the prefill cost is unchanged because
     * the static prompt re-prefills per Conversation.
     */
    private fun warmBaselineBudgetMs(backend: InferenceBackend): Long =
        when (backend) {
            InferenceBackend.GPU -> WARM_BASE_GPU_MS
            InferenceBackend.CPU, InferenceBackend.UNKNOWN -> WARM_BASE_CPU_MS
        }

    /**
     * Per-character budget contribution. GPU is the fast path; CPU is ~3-4× slower
     * because of the static-prompt prefill cost on every Conversation. UNKNOWN is
     * treated as CPU-like — better to over-wait than to time out a valid inference.
     */
    private fun perCharBudgetMs(backend: InferenceBackend): Long =
        when (backend) {
            InferenceBackend.GPU -> PER_CHAR_BUDGET_GPU_MS
            InferenceBackend.CPU, InferenceBackend.UNKNOWN -> PER_CHAR_BUDGET_CPU_MS
        }

    private companion object {
        const val MAX_TITLE_LEN = 60

        /**
         * Characters stripped from the end of a title before display. Gemma
         * occasionally ends titles with sentence punctuation ("Domani.",
         * "Riunione?", "Idee!") which reads poorly as a heading.
         */
        val TRAILING_TITLE_PUNCTUATION: CharArray =
            charArrayOf('.', ',', ';', ':', '!', '?', '"', '\'')

        /**
         * Untimed-ish engine warm-up window, used once per `invoke()` BEFORE the
         * Pass 1 timeout starts. Generous because:
         *   - first-ever load on a fresh process can take 25-30s on Pixel 6a CPU
         *   - the second-ever load after an `onTrimMemory(TRIM_MEMORY_COMPLETE)`
         *     release is comparable
         *   - we want the warm-up to *succeed* and produce a fast inference, not
         *     time out and force a Pass 1 cold path
         * If this expires the model file is almost certainly corrupt or the device
         * is in real trouble (thermal shutdown imminent) — generate() will surface
         * the actual failure mode immediately after.
         */
        const val ENGINE_LOAD_BUDGET_MS = 60_000L

        /**
         * Per-pass timeout sizing. Engine load happens outside these budgets
         * (ADR 0016); the constants here cover prefill + decode on a warm
         * engine. BOTH baseline and per-char are backend-dependent.
         *
         * Empirical fit from real-device traces 2026-05-17 (Pixel CPU fallback,
         * Gemma 4 E2B INT4, ~8KB static prompt, no MTP / MTP not engaging
         * effectively on this device):
         *
         *   T_cpu(L) ≈ 46s + 0.068s × L_chars
         *
         * Two evening data points fit this: 470 char → 78s ✓, and the
         * subsequent 278-char attempt that timed out at the 56.7s budget
         * (extrapolated need: 46 + 0.068×278 = 65s, which 56.7s does not cover).
         *
         * GPU model (when Backend.GPU init succeeds — Adreno-class):
         *   T_gpu(L) ≈ 8s + 0.030s × L_chars
         *   470 char → ~22s.
         *
         * Sample budgets at the new sizing:
         *   200-char   GPU: P1  21s / P2  14s    CPU: P1  90s / P2  80s
         *   278-char   GPU: P1  23s / P2  16s    CPU: P1 102s / P2  92s   ← incident note, was 56.7s
         *   470-char   GPU: P1  29s / P2  22s    CPU: P1 131s / P2 121s   ← also empirically 78s
         *   1000-char  GPU: P1  45s / P2  38s    CPU: P1 210s / P2 200s
         *   2000-char  GPU: P1  75s / P2  68s    CPU: P1 250s (capped) / P2 250s (capped)
         *
         * The 15s GPU baseline covers the small race window where warm-up
         * may not have published the engine when generate() is called. The
         * 60s CPU baseline reflects the dominant static-prompt prefill cost
         * (~46s measured, +30% headroom for thermal noise).
         *
         * Trade-off: CPU users now wait up to 250s before a genuinely
         * broken inference surfaces as a fallback. Accepted because a
         * spurious fallback on valid content (the 2026-05-17 incidents)
         * is the worst possible outcome.
         */
        const val COLD_START_BASE_GPU_MS = 15_000L
        const val COLD_START_BASE_CPU_MS = 60_000L
        const val WARM_BASE_GPU_MS = 8_000L
        const val WARM_BASE_CPU_MS = 50_000L

        /** GPU prefill per-transcript-char. */
        const val PER_CHAR_BUDGET_GPU_MS = 30L

        /** CPU prefill per-transcript-char — ~5× GPU because the static prompt
         *  is re-prefilled cold on every Conversation. */
        const val PER_CHAR_BUDGET_CPU_MS = 150L
        const val MAX_PASS_BUDGET_MS = 250_000L
    }
}

/**
 * Convenience for callers that have the GemmaUnavailableException visible. Re-exported
 * here so feature code doesn't have to import from the deeper package.
 */
typealias StructureNoteUnavailable = GemmaUnavailableException
