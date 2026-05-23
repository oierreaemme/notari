# Notari — demo video script (16:9 frame, vertical phone centered)

> Working document for the demo video. Format is a **16:9 (1920×1080) canvas
> with the vertical phone screen capture centered**; the side pillars and a
> lower band carry the English captions and subtitles so a global / English-
> speaking judge can follow even when the dictation is in Italian.
>
> **Audio decision (locked):** the video has **no spoken voice** — a discreet
> royalty-free background-music bed plus **on-screen subtitles/captions** carry
> everything. The dictation audio is *not* in the final cut; what the user says
> into the phone is shown as a subtitle while the on-screen waveform reacts.
> This removes all voice-recording, sync, and Bluetooth-mic headaches.
>
> **Length decision (locked):** relaxed target **~2 minutes** (~120–125s). We
> let the two differentiator beats (airplane mode, Italian) breathe rather than
> squeezing into 90s. Still tight — no dead air.
>
> Every scene below has three tracks:
> - **PHONE (center):** what happens on the device screen.
> - **SUBTITLE/CAPTION (English overlay):** the text in the 16:9 side bar or
>   lower band. Subtitles render the dictation; callouts label what's happening.
>   Keep each callout ≤ 6 words so it reads in 2–3 seconds.
> - **MUSIC:** one calm instrumental bed runs the whole video; note the energy.
>
> Decision baked in: the **main demo is in English** (so judges follow it
> directly), and the **language beat near the end is in Italian** to prove the
> "your language in, your language out" claim — the genuine differentiator over
> the English-only competitors.

---

## The latency strategy (read first — it's the key change)

On the Pixel 6a (CPU only, no GPU backend) one short note structures in
**~40–50s**. The old plan showed that wait three separate times, which alone
was 2+ minutes of "Structuring your note…". The fix:

1. **Establish the latency once, honestly, in Scene 2.** Film the whole wait,
   then speed-ramp it to ~8–10s in editing with a visible caption:
   *"running locally on the phone · on CPU (Pixel 6a) · real time compressed"*.
   The judge now knows it's real, on-device, and roughly how long it costs.
2. **In Scenes 4 and 5, compress the wait hard** — a fast ramp to ~3–4s, or a
   clean cut with a small *"≈45s on device"* caption. This is honest because
   the real latency was already shown once; we're not implying false speed.

That single change is what gets us from 4+ minutes of footage down to a ~2-min
cut without cutting any beat.

---

## Pre-production checklist

- **Capture:** record the phone screen with `scrcpy` (mirrors to desktop, clean
  capture) or Android's built-in screen recorder. Turn on Do Not Disturb so no
  notifications pop in.
- **Canvas:** 1920×1080. Phone capture centered, ~500–600px wide. Optional thin
  device-bezel mockup so the vertical strip looks intentional, not cropped.
- **Music:** pick **one** calm, royalty-free / CC0 instrumental track (so there
  are zero copyright issues on the submission). Sources: YouTube Audio Library,
  Pixabay Music, Free Music Archive (CC0). Keep it low under the captions; let
  it lift slightly on the Scene 1 title and the Scene 6 outro.
- **No voiceover, no dictation audio in the cut.** Everything spoken on screen
  is conveyed by subtitles. You still *speak* the dictation into the phone to
  generate the transcript — that audio just isn't used in the video.
- **State before recording:** the test notes (dream, Jira meeting, mountain app
  idea, etc.) should already be in the notes list — a populated gallery sells
  the product far better than an empty one. Re-record the two corrected notes
  (English privacy note, Italian Atlas note) **after the language-lock + title-
  dedup rebuild**, so the on-screen notes are clean (no mixed-language title, no
  doubled heading).
- **Captions:** sans-serif, high contrast, in the right pillar or lower band.
  English throughout. Keep them short.

---

## Scene 1 — Hook (0–6s)

- **PHONE:** Notari opening to the capture screen, big record button centered,
  calm and still. (Or go straight to the full-frame title card — your call.)
- **CAPTION:** Full-frame title card on the brand-plum background:
  **"Notari — voice notes that never leave your phone."**
  Smaller line below: *"Speak. Get clean Markdown. On-device. No cloud."*
- **MUSIC:** track opens; let it breathe for a beat before the first cut.

## Scene 2 — Live capture + structuring · establish latency (6–42s)

- **PHONE:** Tap record. The waveform responds to the voice. Dictate a
  realistic, slightly messy English note (false starts are GOOD — they show the
  cleanup):
  > *"Reminder, uh, tomorrow at 3pm I need to call the dentist to move the
  > appointment, and also pick up bread and milk on the way home."*
  Tap stop. The "Structuring your note…" indicator appears. **Film the whole
  ~45s wait**, then speed-ramp it to ~8–10s in editing. The structured note
  slides in.
- **SUBTITLE/CAPTION:** subtitle renders the dictation as it's spoken
  (in sync with the waveform). Callout while recording: *"Recorded in memory
  only — never written to disk."* Callout during the (ramped) wait: *"Gemma 4
  E2B running locally · on CPU (Pixel 6a) · real time compressed."*
- **MUSIC:** steady, unobtrusive.

## Scene 3 — The result: read, edit, save, export (42–72s)

- **PHONE:** The structured note is on screen: clean title, tags, the datetime
  mention surfaced, a Markdown body with the call + groceries as a tidy list.
  Scroll it slowly. Tap to edit one word (shows it's editable). Save. Then tap
  export → show the Markdown file / share sheet.
- **SUBTITLE/CAPTION:** callouts in sequence: *"Title, tags, dates — extracted,
  not invented."* → *"Fully editable before saving."* → *"Export as a real
  Markdown file."* Optionally one more: *"YAML frontmatter — drops into your
  Obsidian vault."*
- **MUSIC:** steady.

## Scene 4 — Why on-device matters: airplane mode (72–96s) ★ MOST IMPORTANT BEAT

- **PHONE:** Pull down quick settings, **turn on airplane mode** (linger on the
  icon so it's unmistakable). Go back to Notari. Record a short note:
  > *"Note to self: the privacy promise is the product."*
  It transcribes and structures normally — **with no connectivity.** Compress
  the structuring wait here (fast ramp / clean cut, *"≈45s on device"* caption)
  — the real latency was already shown in Scene 2.
- **SUBTITLE/CAPTION:** big callout: *"Airplane mode. No Wi-Fi. No signal."* →
  *"Still works. Because nothing was ever leaving."* Optional small terminal
  overlay showing `adb` confirming no audio file exists — strong proof, but skip
  if it complicates the shot.
- **MUSIC:** can drop to near-silence on "No Wi-Fi. No signal." for emphasis,
  then return.

## Scene 5 — Language beat: Italian in, Italian out (96–116s) ★ DIFFERENTIATOR

- **PHONE:** **First pin the language to Italian** (capture-screen language
  picker or Settings → Language) — on "auto" an English-locale phone hears
  Italian as garbled English. Then record in **Italian**, your natural voice:
  > *"Allora, domani alle quindici riunione con Marco per il progetto Atlas,
  > devo preparare le slide della parte di onboarding."*
  The structured note appears with an **Italian** title, Italian tags, Italian
  Markdown body. No English creeps in. Compress the wait as in Scene 4.
- **SUBTITLE/CAPTION:** *"Dictated in Italian → structured in Italian. No
  translation."* plus an English gloss in the lower band so the judge follows:
  *"(\"Tomorrow at 3pm, meeting with Marco about project Atlas…\")"*.
- **MUSIC:** let it breathe — this beat is the differentiator, don't rush it.

## Scene 6 — Outro (116–126s)

- **PHONE:** Return to the notes list, now showing the full set in two
  languages — a real, lived-in gallery.
- **CAPTION:** full-frame closing card on plum: **"Notari"** + tagline *"Speak.
  Get a clean Markdown note. The audio never leaves your phone."* Then the
  links: *"Open source — github.com/oierreaemme/notari"* and *"Built with Gemma
  4 E2B · on-device · Apache 2.0."*
- **MUSIC:** lift for the outro, resolve, fade out.

---

## Timeline at a glance (~2 min)

| Scene | Window | Length | Beat |
|-------|--------|--------|------|
| 1 | 0–6s | 6s | Hook / title card |
| 2 | 6–42s | 36s | Capture + structuring (latency shown once) |
| 3 | 42–72s | 30s | Result: read, edit, save, export |
| 4 | 72–96s | 24s | Airplane mode (compressed wait) |
| 5 | 96–116s | 20s | Italian in, Italian out (compressed wait) |
| 6 | 116–126s | 10s | Outro / links |

Total ≈ **126s**. If a beat runs a little long, that's fine within the relaxed
~2-min target; trim Scene 3's editing sub-beat first.

---

## Editing notes

- The single most important beat is **Scene 4 (airplane mode)** — it's the thing
  no cloud competitor and no text-input competitor can show. Give it room and
  make the airplane-mode icon unmistakable.
- The second most important is **Scene 5 (Italian)** — the differentiator over
  English-only on-device submissions (e.g. PhotoLens). Don't cut it for time;
  cut Scene 3's editing sub-beat instead if you're over budget.
- **Show the real latency once (Scene 2), compress the rest.** A visible
  "running locally · real time compressed" caption keeps it honest. A hard cut
  to an instant result everywhere would look fake; ramping a wait you've already
  established does not.
- Speed-ramps and simple cuts are all easy in Kdenlive: one video track for the
  phone clip, a colour clip behind it for the plum bars, title clips for the
  captions/cards, a Speed effect on each structuring segment, one audio track
  for the music bed.
