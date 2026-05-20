# Notari — demo video script (16:9 frame, vertical phone centered)

> Working document for the 75–90s demo video. Structure follows CLAUDE.md
> §12. Format is a **16:9 (1920×1080) canvas with the vertical phone screen
> capture centered**; the side pillars and a lower band carry English
> captions and callouts so a global / English-speaking judge can follow
> even when the dictation is in Italian.
>
> Every scene below has three tracks:
> - **PHONE (center):** what happens on the device screen.
> - **FRAME (English overlay):** caption / callout text in the 16:9 side or
>   lower band. Keep each callout ≤ 6 words so it's readable in 2–3 seconds.
> - **AUDIO:** voiceover (optional) and/or what the user dictates.
>
> Decision baked in: the **main demo is in English** (so judges follow it
> directly), and the **language beat at 60–75s is in Italian** to prove the
> "your language in, your language out" claim — the genuine differentiator
> over the English-only competitors. If you'd rather lead in Italian, the
> beats swap cleanly; just move the English captions to translate the
> Italian instead of mirroring it.

---

## Pre-production checklist

- **Capture:** record the phone screen with `scrcpy` (mirrors to desktop,
  clean capture, no notification bar clutter) or Android's built-in screen
  recorder. Turn on Do Not Disturb so no notifications pop in.
- **Canvas:** 1920×1080. Phone capture centered, ~500–600px wide. Optional:
  a thin device-bezel mockup around it to make the vertical strip look
  intentional rather than cropped.
- **State before recording:** the four notes from testing (dream, Jira
  meeting, mountain app idea ×2) should already be in the notes list — a
  populated gallery sells the product far better than an empty one.
- **Captions:** sans-serif, high contrast, lower band or right pillar.
  English throughout. Keep them short.
- **Length target:** aim for 80s. Hard ceiling 90s. Shorter and tighter
  beats slow.
- **No fancy editing.** Per CLAUDE.md: clarity over polish. One clean take
  per beat, simple cuts.

---

## Scene 1 — Hook (0–5s)

- **PHONE:** Notari opening to the capture screen, the big record button
  centered, calm and still.
- **FRAME:** Title card fades in over/around the phone:
  **"Notari — voice notes that never leave your phone."**
  Smaller line below: *"Speak. Get clean Markdown. On-device. No cloud."*
- **AUDIO:** One spoken line, if using voiceover: *"This is a voice note
  app where the audio never leaves your phone."* Otherwise let the title
  card carry it in silence.

## Scene 2 — Live capture + structuring (5–25s)

- **PHONE:** Tap record. The waveform responds to the voice. Dictate a
  realistic, slightly messy English note (false starts are GOOD — they
  show the cleanup):
  > *"Reminder, uh, tomorrow at 3pm I need to call the dentist to move
  > the appointment, and also pick up bread and milk on the way home."*
  Tap stop. The "Structuring your note…" indicator appears. **Show the
  real latency — do not cut it out.** After ~15–20s the structured note
  slides in.
- **FRAME:** Callout while recording: *"Recorded in memory only — never
  written to disk."* Callout during structuring: *"Gemma 4 E2B running
  locally on the phone."*
- **AUDIO:** the dictation above. No voiceover over the structuring wait —
  let the indicator and the callout speak.

## Scene 3 — The result: read, edit, save, export (25–45s)

- **PHONE:** The structured note is on screen: a clean title, tags, the
  datetime mention surfaced, a Markdown body with the call + groceries as
  a tidy list. Scroll it slowly. Tap to edit one word to show editing is
  possible. Save. Then tap export → show the Markdown file / share sheet.
- **FRAME:** Callouts in sequence: *"Title, tags, dates — extracted, not
  invented."* → *"Fully editable before saving."* → *"Export as a real
  Markdown file."*
- **AUDIO:** optional single voiceover line: *"A messy two-minute thought
  becomes a clean, searchable note — as a file you own."*

## Scene 4 — Why on-device matters: airplane mode (45–60s)

- **PHONE:** Pull down quick settings, **turn on airplane mode** (show the
  icon clearly). Go back to Notari. Record a short note:
  > *"Note to self: the privacy promise is the product."*
  It transcribes and structures normally — **with no connectivity.**
- **FRAME:** Big callout: *"Airplane mode. No Wi-Fi. No signal."* →
  *"Still works. Because nothing was ever leaving."* Optionally a small
  terminal overlay showing `adb` confirming no audio file exists, if you
  can capture it cleanly — strong proof, but skip if it complicates the
  shot.
- **AUDIO:** voiceover: *"No internet. It still works — because the model
  is on the phone, and the audio never leaves it."*

## Scene 5 — Language beat: Italian in, Italian out (60–75s)

- **PHONE:** Record a note **in Italian** (your natural voice):
  > *"Allora, domani alle quindici riunione con Marco per il progetto
  > Atlas, devo preparare le slide della parte di onboarding."*
  The structured note appears with an **Italian** title, Italian tags,
  Italian Markdown body. No English creeps in.
- **FRAME:** Callout: *"Dictated in Italian →"* then *"← structured in
  Italian. No translation."* The English captions translate the dictation
  so the judge follows: *"(\"Tomorrow at 3pm, meeting with Marco about
  project Atlas…\")"*.
- **AUDIO:** the Italian dictation. Let it breathe — this beat is the
  differentiator, don't rush it.

## Scene 6 — Outro (75–90s)

- **PHONE:** Return to the notes list, now showing the full set of notes
  in two languages — a real, lived-in gallery.
- **FRAME:** Closing card: **"Notari"** + tagline *"Speak. Get a clean
  Markdown note. The audio never leaves your phone."* Then the links:
  *"Open source — github.com/<user>/notari"* and *"Built with Gemma 4
  E2B · on-device · Apache 2.0."*
- **AUDIO:** optional closing voiceover: *"Notari. Open source, on-device,
  and yours."*

---

## Editing notes

- The single most important beat is **Scene 4 (airplane mode)** — it's the
  thing no cloud competitor and no text-input competitor can show. Give it
  room and make the airplane-mode icon unmistakable.
- The second most important is **Scene 5 (Italian)** — it's the
  differentiator over the English-only on-device submissions (e.g.
  PhotoLens). Don't cut it for time; cut Scene 3's editing sub-beat
  instead if you're over 90s.
- Resist the urge to speed up the structuring wait in Scene 2. Showing the
  real ~15–20s latency and labeling it honestly ("running locally") builds
  more trust than a suspiciously instant cut. If 20s feels too long on
  screen, a subtle speed-ramp to ~8s is acceptable — but keep a visible
  "running locally" label so you're not implying false speed.
