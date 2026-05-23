# Notari — recording-day cue card

> Print this (or keep it on a second screen) while you shoot. It turns
> `08-video-script.md` into a do-this-now checklist. The script is the *why*;
> this is the *what to do, in order*. Target ~2 min (relaxed), no hard ceiling
> but keep it tight — no dead air.
>
> **Locked decisions:** no spoken voice (music + on-screen subtitles only); the
> dictation audio is NOT in the final cut. You shoot one clean take per scene,
> then cut them together. Captions and subtitles are added later in editing —
> during the shoot you only worry about the phone screen.

---

## A. Pre-flight (do this once, before any recording)

- [ ] Phone on a stable mount or propped up; you won't hold it.
- [ ] **Do Not Disturb ON** — no notifications mid-take.
- [ ] Screen brightness up; auto-rotate off (stay portrait).
- [ ] **Status bar — keep it simple.** A normal, real status bar (Wi-Fi,
      battery, clock) looks authentic and is totally fine. Heads-up: Android's
      **Demo Mode does NOT reliably hide the "USB debugging" icon** on Pixel (it
      removes legit icons like Wi-Fi/battery instead), so it's not worth using —
      exit it with
      `adb shell am broadcast -a com.android.systemui.demo -e command exit`.
      If you want a pristine bar, **crop the top status-bar strip** out of the
      frame in Kdenlive — *except* Scene 4, where the bar must stay (the
      airplane icon is the proof).
- [ ] `scrcpy` running and mirroring (or Android's built-in screen recorder
      armed). Test one throwaway capture and play it back before the real takes.
      Suggested: `scrcpy --record=scene_01.mp4 --max-fps=30 --video-bit-rate=8M`.
- [ ] Gemma model already imported in Settings → On-device model (so no "import
      the model" detour appears on camera).
- [ ] **Stage the notes list:** the test notes already saved so the gallery
      looks lived-in. **Re-record the two corrected notes** (English privacy
      note, Italian Atlas note) on the rebuilt app, so the doubled-title and
      mixed-language-title bugs are gone from what's on screen.
- [ ] **Clean home screen for the Scene 1 launch.** Use a **mid-tone** wallpaper
      (medium neutral grey/taupe — NOT the dark plum), so both the white system
      icons and Notari's dark-plum icon read as crisp circles. Keep only a few
      icons, no clutter.
- [ ] **No voice to record — it's music + subtitles.** You do NOT record your
      voice anywhere. You still *speak* the dictation into the phone (that's how
      the transcript is generated), but that audio is not used; the video shows
      the waveform reacting and a subtitle of what was said. This means: no
      separate audio recorder, no sync, no Bluetooth-mic worries. For the best
      transcription accuracy, dictate with the phone's **built-in mic** (don't
      route through a Bluetooth headset — its HFP mic hurts recognition).
- [ ] **Pick the music in advance.** One calm, **royalty-free / CC0**
      instrumental track (YouTube Audio Library, Pixabay Music, FMA CC0) so
      there's zero copyright issue on the submission. Have it ready to drop onto
      the audio track in Kdenlive.
- [ ] **Warm up the engine before each structuring take.** Open the capture
      screen and wait ~15–20s before recording, so the model is loaded and the
      structuring you film isn't also paying the cold-load cost. On your Pixel 6a
      (CPU only) a short note structures in **~40–50s**. That's real — you'll
      compress it in editing (see §F), never fake it.
- [ ] **Language gotcha — read this.** Notari's "auto" follows the phone's system
      language (English on your Pixel); it does NOT auto-detect spoken Italian.
      Pin English (or auto) for the English scenes, and **pin Italian for Scene
      5** — via the capture-screen language picker or Settings → Language. Skip
      this and the Italian beat records as garbled English.

---

## B. The shoot, scene by scene

Each scene: **what you do on the phone**. Captions in *italics* are added in
editing — ignore them while shooting. There is nothing to *say*; just dictate
into the phone where noted (that audio won't be in the cut).

### Scene 1 — Hook · 0–6s
- **Do:** Open Notari to the capture screen, hold still on the record button.
  (Or cut straight to the full-frame title card.)
- *Caption added later: "Notari — voice notes that never leave your phone."*

### Scene 2 — Capture + structuring · 6–42s · ★ establishes latency
- **Do:** Tap record. Let the waveform react. Dictate the line below into the
  phone (the stumbles are intentional — they show the cleanup). Tap stop. Let
  the "Structuring your note…" indicator run and **film the whole ~45s wait** —
  you'll speed-ramp it to ~8–10s in editing (§F), not hard-cut it.
- **Dictate into the phone (English):**
  > "Reminder, uh, tomorrow at 3pm I need to call the dentist to move the
  > appointment, and also pick up bread and milk on the way home."
- *Captions later: "Recorded in memory only — never written to disk." → "Gemma
  4 E2B running locally · on CPU (Pixel 6a) · real time compressed."*

### Scene 3 — The result · 42–72s
- **Do:** Scroll the structured note slowly (title, tags, the date surfaced, the
  Markdown list). Tap to edit one word. Save. Then export → show the Markdown /
  share sheet.
- *Captions: "Title, tags, dates — extracted, not invented." → "Fully editable
  before saving." → "Export as a real Markdown file."*

### Scene 4 — Airplane mode · 72–96s · ★ MOST IMPORTANT BEAT
- **Do:** Pull down quick settings. **Turn ON airplane mode** — linger on the
  icon. Go back to Notari. Record the short line below. It transcribes and
  structures normally, no connectivity. **Compress this wait** in editing (fast
  ramp / clean cut) — the real latency was already shown in Scene 2.
- **Dictate into the phone (English):**
  > "Note to self: the privacy promise is the product."
- *Captions: "Airplane mode. No Wi-Fi. No signal." → "Still works. Because
  nothing was ever leaving." Optional: "≈45s on device".*
- Optional power move: a small terminal overlay showing the `adb` find command
  returning no audio file. Only if it captures cleanly.
- **Status-bar caveat (important):** do NOT use demo mode here. The whole beat is
  the *real* airplane-mode icon and absent signal. Use the genuine status bar.
  (scrcpy works over USB even in airplane mode — it kills Wi-Fi/cellular, not the
  USB cable.) A small "USB debugging" icon next to the airplane icon is fine —
  it's not a network, so it doesn't weaken the proof.

### Scene 5 — Italian in, Italian out · 96–116s · ★ DIFFERENTIATOR
- **Do:** **First pin the language to Italian** (capture-screen picker or
  Settings → Language) — on "auto" an English-locale phone hears Italian as
  garbled English. Then dictate in **Italian**, your natural voice. The note
  comes back with an Italian title, Italian tags, Italian body. Let it breathe.
  Compress the wait as in Scene 4. (Switch the pin back afterward if you reshoot
  the English scenes.)
- **Dictate into the phone (Italian):**
  > "Allora, domani alle quindici riunione con Marco per il progetto Atlas, devo
  > preparare le slide della parte di onboarding."
- *Captions: "Dictated in Italian → structured in Italian. No translation." plus
  an English gloss of the sentence in the lower band so judges follow.*

### Scene 6 — Outro · 116–126s
- **Do:** Return to the notes list, now full in two languages.
- *Closing card: "Notari" + tagline + "github.com/oierreaemme/notari" + "Built
  with Gemma 4 E2B · on-device · Apache 2.0."*

---

## C. All dictation lines, in one place (to read into the phone)

**English dictation — Scene 2:**
> Reminder, uh, tomorrow at 3pm I need to call the dentist to move the
> appointment, and also pick up bread and milk on the way home.

**English dictation — Scene 4:**
> Note to self: the privacy promise is the product.

**Italian dictation — Scene 5 (pin Italian first):**
> Allora, domani alle quindici riunione con Marco per il progetto Atlas, devo
> preparare le slide della parte di onboarding.

(There are no voiceover lines anymore — the video is music + subtitles only.)

---

## D. Re-shoot triggers (when to do the take again)

- A notification slipped onto the screen → re-shoot (this is why DND is on).
- Scene 2 structuring on CPU is ~45s — film the whole wait, then speed-ramp it
  to ~8–10s with a visible "running locally · real time compressed" caption.
- The airplane-mode icon isn't clearly visible in Scene 4 → re-shoot. That beat
  only lands if the viewer sees there's no connectivity.
- Any English creeps into the Italian note in Scene 5 → re-shoot; the whole
  point is that none does. (The language-lock fix should prevent this now —
  confirm on the rebuilt app.)
- A note on screen still shows a doubled title or a mixed-language title →
  you're filming a note made before the rebuild; re-record it on the fixed app.

## E. If you run long

Within the relaxed ~2-min target this is rarely a problem, but if you need to
trim: cut **Scene 3's editing sub-beat** first. **Never** cut Scene 4 (airplane
mode) or Scene 5 (Italian) — those are the two things no competitor can show.

## F. Framing, captions & music (16:9 with a vertical phone)

- The vertical phone capture, centred on a 1920×1080 canvas, leaves wide bars
  left and right. **Fill those bars with the brand plum** (the app icon's
  background colour) instead of pure black — it ties the frame to the app and
  reads as intentional, not cropped.
- Put the running callouts in the **right-hand bar**, vertically centred, in the
  cream/off-white from the icon. Keep each ≤ 6 words. The left bar can stay clean
  or hold a small static Notari logo.
- The Scene 2 dictation subtitle and the Scene 5 Italian gloss sit in a **lower
  band** across the full width.
- Title (Scene 1) and closing (Scene 6) cards use the **full frame** on the plum
  background — no phone shown there.
- **Music:** one royalty-free / CC0 instrumental bed on a single audio track,
  low under the captions; lift slightly on the title and outro, and you can drop
  it to near-silence on "No Wi-Fi. No signal." in Scene 4 for emphasis.
- **Speed-ramps:** a Speed effect on each structuring segment — full ramp (~8–10s)
  in Scene 2, hard compression (~3–4s) in Scenes 4 and 5. All easy in Kdenlive:
  one video track for the phone clip, a colour clip behind it for the plum bars,
  title clips for captions/cards.
