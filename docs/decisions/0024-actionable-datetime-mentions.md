# 0024. Make resolved datetime mentions actionable (reminders / calendar)

- **Status:** Proposed
- **Date:** 2026-05-31

## Context

Notari already does the hard part of temporal reasoning: `RelativeDateTimeResolver`
plus Gemma resolve phrases like "domani alle 15", "venerdì prossimo", "tomorrow
night" into a concrete `Instant`, stored on `DateMention.resolved`, shown as a
chip (`MentionsSection`) and exported in the note's YAML frontmatter.

But that `Instant` is **inert** — it is only ever displayed. The user reads
"Fri 6 Jun 2026 · 15:00" on a chip and then has to re-enter it by hand into
whatever reminder app they actually use. We are sitting on the most valuable,
hardest-won piece of structured data in the app and doing nothing actionable
with it. This is the lowest-effort, highest-delight feature available.

Hard constraint (cardinal rule, ADR 0002/0007): **the app holds no `INTERNET`
permission**. Any mechanism must be fully on-device or hand off to another app
the user chose — it must not make Notari itself a network client.

## Decision

**Let the user turn a resolved mention into a reminder, on-device, with one tap.**

Two privacy-clean mechanisms, shipped in order of cost:

1. **Calendar intent (ship first).** A "Add to calendar" action on a resolved
   chip fires `Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)`
   prefilled with the note title and the resolved start time. This requires
   **no permission and no `INTERNET`** — it hands off to the user's installed
   calendar app, which they already trust. Whether that calendar later syncs to
   a cloud is the calendar app's behaviour and the user's choice, not Notari's;
   document this explicitly so the headline privacy claim stays honest.

2. **In-app local reminder (optional, follow-up).** For users who want a
   self-contained reminder without a calendar app: schedule a local notification.
   - `POST_NOTIFICATIONS` is already requested.
   - Exact-time alarms need `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` on API 31+;
     prefer an **inexact** `AlarmManager.setWindow` / `WorkManager` reminder to
     avoid the exact-alarm permission unless the user explicitly opts into exact.
   - Reminders are derived state — store the scheduled mention→alarm mapping so
     they can be rebuilt after reboot (`BOOT_COMPLETED`) or note deletion.

An optional **"Upcoming" agenda view** (notes with a future resolved mention,
sorted by time) falls out naturally once mentions are actionable.

## Alternatives considered

- **Do nothing / display only (status quo).** Wastes the app's best data.
- **AlarmManager exact alarms as the primary path.** Stronger UX but drags in
  the `SCHEDULE_EXACT_ALARM` permission and Doze edge cases for v1. Defer to
  opt-in.
- **Write directly to the calendar provider** (`WRITE_CALENDAR`). Avoids the
  hand-off UI but needs a dangerous permission and silently writes to the user's
  calendar — worse privacy optics than the intent. Rejected.

## Consequences

- **Pro:** turns inert data into the feature most likely to make the app
  "sticky"; mechanism (1) is days of work and zero new permissions.
- **Con:** mechanism (2) adds reminder-lifecycle state (reschedule on boot,
  cancel on note delete/edit, timezone changes) — real but contained.
- **Privacy:** mechanism (1) adds no permission and no network. Must clearly
  state in-app that handing an event to the calendar app passes that data out of
  Notari's sandbox, so the "nothing leaves the device" claim is scoped to the
  app itself, not to a calendar the user chooses to populate.
