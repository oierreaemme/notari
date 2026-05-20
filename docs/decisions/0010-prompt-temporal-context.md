# 10. Prompt temporal context for ISO date resolution

Date: 2026-05-13
Status: Accepted

## Context

The original structuring prompt (`structure_note_v2.txt`) instructed Gemma to
produce a `mentions` array with `surface_form` (the exact words the user said,
e.g. "domani alle 15") and `iso_resolved` (the resolved ISO-8601 timestamp).
Looking at real model output across all 6 supported languages, the `iso_resolved`
field was **always `null`** when the surface form was a relative reference. The
model could not anchor "domani" to an actual date because the prompt gave it no
notion of *when* "now" is.

The user could read "domani alle 15" in the note body and infer the time
themselves. But the structured value — the thing that would let us build calendar
exports, reminders, sort-by-when views, etc. — was being silently lost.

Looked at another way: we were using ~30% of the model's date-reasoning capability.
Gemma 4 E2B knows date arithmetic. We just weren't giving it the anchor.

## Decision

Inject the current wall-clock time and the user's timezone into every prompt
render. Concretely:

1. `PromptTemplate.render(transcript, now: Instant, zone: ZoneId)` — the
   interface accepts `now` and `zone` parameters with sensible defaults
   (`Instant.now()`, `ZoneId.systemDefault()`).

2. `StaticPromptTemplate` substitutes two new markers in addition to
   `{{TRANSCRIPT}}`:
   - `{{NOW_ISO}}` — `yyyy-MM-dd'T'HH:mm:ssXXX` formatted ISO timestamp with
     timezone offset (e.g. `2026-05-14T16:42:00+02:00`)
   - `{{NOW_TIMEZONE}}` — the IANA zone id (e.g. `Europe/Rome`)

3. `StructureNoteUseCaseImpl` computes `now = Instant.now(clock)` once per
   invocation and passes it to both Pass 1 (base prompt) and Pass 2 (stricter
   prompt) so retries are temporally consistent.

4. The active prompt (`structure_note_v4.txt`) carries an explicit datetime
   resolution rule plus examples:
   ```
   CURRENT TIMESTAMP: {{NOW_ISO}}
   USER TIMEZONE: {{NOW_TIMEZONE}}

   Datetime resolution:
   - Resolve RELATIVE datetimes ("tomorrow", "domani", "next Friday",
     "venerdì prossimo", "in 2 hours", "lunedì") against CURRENT TIMESTAMP.
     Output the resolved value in ISO-8601 with timezone offset. If the
     time-of-day is not stated, output date-only (e.g. "2026-05-14"). If
     the reference is genuinely vague ("una di queste sere", "soon"),
     leave iso_resolved as null — never invent.
   ```

5. `StructureNoteUseCaseImpl.tryParseInstant` accepts three ISO formats Gemma
   can emit (full UTC instant, offset datetime, date-only), parsing each into
   a `java.time.Instant`. Earlier code only accepted the UTC `Z` format from
   `Instant.parse`, silently dropping the other two.

## Consequences

- The `mentions` array in stored notes carries meaningful timestamps.
  "domani alle dieci e mezza" round-trips through Gemma as
  `iso_resolved: "2026-05-14T10:30:00+02:00"`, parsed downstream into
  `DateMention.resolved: Instant`.

- The `MentionsSection` composable (in `:core:design`) renders the chip
  with locale-formatted resolution: *"gio 14 mag 2026 · 10:30"*. The
  user (and the demo audience) sees on-device temporal reasoning, not
  just text-to-text transformation.

- The pillar 4 anti-hallucination invariant (CLAUDE.md sec. 3) is
  preserved by the explicit rule that genuinely vague references stay
  `null`. Empirical eval on Pixel 6a confirms Gemma respects this: "una
  di queste sere" reliably comes back as `null`, while specific
  references reliably resolve.

- **Known imprecision**: Gemma 4 E2B's weekday math is approximate.
  Empirically, "lunedì" (next Monday) sometimes resolves to a Sunday or
  the Monday after next — an off-by-one or off-by-two error in the
  calendar walk. This is a known limitation of 2B-effective models on
  date arithmetic and is acceptable for v1 (we publish the resolution
  in the UI, so the user can see and correct any errors). v2 will
  evaluate whether the larger E4B variant improves on this.

- Tests: `PromptEvalTest` and `StructureNoteUseCaseImplTest` continue
  to pass because the new markers in templates that don't contain them
  (e.g. `StaticPromptTemplate("BASE: {{TRANSCRIPT}}")` in tests) result
  in harmless `replace` no-ops.

- Prompt evolution chain: `structure_note_v1.txt` (initial) →
  `structure_note_v2.txt` (compressed for E2B) →
  `structure_note_v3.txt` (added CURRENT TIMESTAMP) →
  `structure_note_v4.txt` (rebalanced examples after observing E2B
  applying `- [ ]` to prose; added REQUIRED framing for tasks and
  enumerations; added "DATETIME REFERENCES ONLY" rule for mentions
  after observing E2B putting person names there). All four versions
  stay in `assets/prompts/` so a rollback is one `AssetPromptLoader.ACTIVE_PROMPT`
  constant change.

## Alternatives considered

- **Server-side date resolution**: parse the surface forms ourselves with a
  natural-language-date library (e.g. ports of `parsedatetime` to Kotlin).
  Rejected because (a) it duplicates capability Gemma already has, (b) NL date
  libraries are language-specific and we support 6, and (c) it splits the
  intelligence between code and model in a way that's harder to test/evolve.

- **Two-call pipeline**: first call extracts mentions, second call resolves
  each. Rejected because it doubles latency for marginal quality gain — Gemma
  resolves dates well *in-context* once it has the anchor.

- **System prompt vs. user prompt placement**: putting `CURRENT TIMESTAMP` in
  a separate system message. Rejected because litertlm-android 0.11.0 exposes
  a single-message API (`Conversation.sendMessage(prompt)`); we'd be simulating
  the system/user split with no semantic difference.
