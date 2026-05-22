# Notari — recording-day cue card

> Print this (or keep it on a second screen) while you shoot. It turns
> `08-video-script.md` into a do-this-now checklist. The script is the *why*;
> this is the *what to do, in order*. Target 80s, hard ceiling 90s.
>
> You shoot one clean take per scene, then cut them together. Captions are
> added later in editing — during the shoot you only worry about the phone
> screen and the lines you read.

---

## A. Pre-flight (do this once, before any recording)

- [ ] Phone on a stable mount or propped up; you won't hold it.
- [ ] **Do Not Disturb ON** — no notifications mid-take.
- [ ] Screen brightness up; auto-rotate off (stay portrait).
- [ ] **Status bar — keep it simple.** A normal, real status bar (Wi-Fi,
      battery, clock) looks authentic and is totally fine — don't over-engineer
      it. Heads-up: Android's **Demo Mode does NOT reliably hide the "USB
      debugging" icon** on Pixel (it removes legit icons like Wi-Fi/battery
      instead), so it's not worth using here — exit it with
      `adb shell am broadcast -a com.android.systemui.demo -e command exit`.
      If you want a pristine bar, the reliable fix is to **crop the top
      status-bar strip** out of the frame in Kdenlive — *except* Scene 4, where
      the bar must stay (the airplane icon is the proof). Just turn DND on so no
      notifications pop in mid-take.
- [ ] `scrcpy` running and mirroring (or Android's built-in screen recorder
      armed). Test one throwaway capture and play it back before the real takes.
- [ ] Gemma model already imported in Settings → On-device model (so no
      "import the model" detour appears on camera).
- [ ] **Stage the notes list:** the four test notes (dream, Jira meeting,
      mountain app idea ×2) already saved, so the gallery looks lived-in.
- [ ] **Clean home screen for the Scene 1 launch.** Use a **mid-tone** wallpaper
      (a medium neutral grey/taupe — NOT the dark plum), so both the white system
      icons and Notari's dark-plum icon read as crisp, deliberate circles. On a
      plum wallpaper the plum icon disappears into the background and looks
      accidental. Keep only a few icons, no clutter.
- [ ] Quiet room for the dictation takes. The dictation IS the audio track of
      those scenes — background noise will show.
- [ ] **Plan your audio — scrcpy does NOT record your voice.** scrcpy mirrors
      the screen, and your dictation is never played aloud (it goes straight to
      the recognizer), so the spoken note won't be in the screen capture. Run a
      separate audio recorder (laptop mic, a second phone, a headset) **at the
      same time** as each dictation take, so the spoken note stays in sync with
      the on-screen waveform and the transcript appearing word by word. Use the
      "tap record" instant as the sync point when you line the tracks up in
      Kdenlive. Do NOT re-dub the dictation afterwards — a voice that doesn't
      match the live transcript timing looks fake. **Avoid a Bluetooth headset
      mic** — the moment the mic engages, Bluetooth drops to a low-quality mono
      profile (HFP) and the audio comes out distorted; use a wired mic, wired
      earbuds, the laptop mic, or a USB mic instead. (And in Scene 4 airplane
      mode disables Bluetooth entirely.) For the dictation INTO Notari, unplug
      Bluetooth and let the phone's built-in mic feed the recognizer — the BT
      HFP mic also hurts transcription accuracy. (The optional English voiceover
      lines are the opposite: record those separately and calm, lay them over
      later — they need no tight sync.)
- [ ] **Warm up the engine before each structuring take.** Open the capture
      screen and wait ~15–20s before recording, so the model is loaded and the
      structuring you film isn't also paying the cold-load cost. On your Pixel
      6a (CPU only — no GPU backend) a short note structures in **roughly a
      minute, sometimes more**. That's real; you'll compress it in editing (see
      §F), never fake it. Consider a small "on CPU (Pixel 6a)" caption so the
      latency reads as honest hardware reality, not slowness.
- [ ] **Language gotcha — read this.** Notari's "auto" follows the phone's
      system language (English on your Pixel); it does NOT auto-detect spoken
      Italian. Pin the dictation language per take: English (or auto) for the
      English scenes, and **pin Italian for Scene 5** — via the language picker
      on the capture screen or Settings → Language. Skip this and the Italian
      beat records as garbled English.

---

## B. The shoot, scene by scene

Each scene: **what you do on the phone**, then **the exact line to say**
(read it naturally, don't recite). Captions in *italics* are added in editing —
ignore them while shooting.

### Scene 1 — Hook · 0–5s
- **Do:** Open Notari to the capture screen. Hold still on the record button.
- **Say (voiceover, optional):** "This is a voice note app where the audio
  never leaves your phone."
- *Caption added later: "Notari — voice notes that never leave your phone."*

### Scene 2 — Capture + structuring · 5–25s
- **Do:** Tap record. Let the waveform react. Dictate the line below
  (the stumbles are intentional — they show the cleanup). Tap stop. Let the
  "Structuring your note…" indicator run and **film the whole wait** — on CPU
  it's ~1 minute; you'll speed-ramp it in editing (§F), not hard-cut it.
- **Dictate (English):**
  > "Reminder, uh, tomorrow at 3pm I need to call the dentist to move the
  > appointment, and also pick up bread and milk on the way home."
- *Captions added later: "Recorded in memory only — never written to disk." →
  "Gemma 4 E2B running locally on the phone."*

### Scene 3 — The result · 25–45s
- **Do:** Scroll the structured note slowly (title, tags, the date surfaced,
  the Markdown list). Tap to edit one word. Save. Then export → show the
  Markdown / share sheet.
- **Say (voiceover, optional):** "A messy two-minute thought becomes a clean,
  searchable note — as a file you own."
- *Captions: "Title, tags, dates — extracted, not invented." → "Fully editable
  before saving." → "Export as a real Markdown file."*

### Scene 4 — Airplane mode · 45–60s  ★ MOST IMPORTANT BEAT
- **Do:** Pull down quick settings. **Turn ON airplane mode** — linger on the
  icon so it's unmistakable. Go back to Notari. Record the short line below.
  It transcribes and structures normally, with no connectivity.
- **Dictate (English):**
  > "Note to self: the privacy promise is the product."
- **Say (voiceover):** "No internet. It still works — because the model is on
  the phone, and the audio never leaves it."
- *Captions: "Airplane mode. No Wi-Fi. No signal." → "Still works. Because
  nothing was ever leaving."*
- Optional power move: a small terminal overlay showing the `adb` find command
  returning no audio file. Only if you can capture it cleanly — skip if fiddly.
- **Status-bar caveat (important):** do NOT use demo mode here. The whole beat
  is the *real* airplane-mode icon and absent signal — faking the status bar
  would gut it. Use the genuine status bar. (Good news: scrcpy works over USB
  even in airplane mode — airplane mode kills Wi-Fi/cellular, not the USB cable
  — so you can still mirror.) A small "USB debugging" icon may sit next to the
  airplane icon; that's fine, USB debugging is not a network, so it doesn't
  weaken the no-connectivity proof. If you want it gone, record just this scene
  with the phone's built-in recorder instead.

### Scene 5 — Italian in, Italian out · 60–75s  ★ DIFFERENTIATOR
- **Do:** **First pin the language to Italian** (capture-screen language picker
  or Settings → Language) — on "auto" an English-locale phone hears Italian as
  garbled English. Then record in **Italian**, your natural voice. The
  structured note comes back with an Italian title, Italian tags, Italian body.
  Let it breathe. (Switch the pin back to Auto/English afterward if you reshoot
  the English scenes.)
- **Dictate (Italian):**
  > "Allora, domani alle quindici riunione con Marco per il progetto Atlas,
  > devo preparare le slide della parte di onboarding."
- *Captions: "Dictated in Italian → structured in Italian. No translation."
  plus an English gloss of the sentence so judges follow.*

### Scene 6 — Outro · 75–90s
- **Do:** Return to the notes list, now full in two languages.
- **Say (voiceover, optional):** "Notari. Open source, on-device, and yours."
- *Closing card: "Notari" + tagline + "github.com/oierreaemme/notari" +
  "Built with Gemma 4 E2B · on-device · Apache 2.0."*

---

## C. All spoken lines, in one place (for reading on camera)

**English dictation — Scene 2:**
> Reminder, uh, tomorrow at 3pm I need to call the dentist to move the
> appointment, and also pick up bread and milk on the way home.

**English dictation — Scene 4:**
> Note to self: the privacy promise is the product.

**Italian dictation — Scene 5:**
> Allora, domani alle quindici riunione con Marco per il progetto Atlas, devo
> preparare le slide della parte di onboarding.

**Voiceover lines (optional, record separately and lay over):**
1. (Scene 1) This is a voice note app where the audio never leaves your phone.
2. (Scene 3) A messy two-minute thought becomes a clean, searchable note — as a
   file you own.
3. (Scene 4) No internet. It still works — because the model is on the phone,
   and the audio never leaves it.
4. (Scene 6) Notari. Open source, on-device, and yours.

---

## D. Re-shoot triggers (when to do the take again)

- A notification slipped onto the screen → re-shoot (this is why DND is on).
- Scene 2 structuring on CPU is ~1 minute — too long to show in full. Film the
  whole wait, then speed-ramp it to ~6–10s in editing with a visible "running
  locally on the phone — real time compressed" caption. That's honest; a hard
  cut to an instant result is not.
- The airplane-mode icon isn't clearly visible in Scene 4 → re-shoot. That beat
  only lands if the viewer sees there's no connectivity.
- Any English creeps into the Italian note in Scene 5 → re-shoot; the whole
  point of the beat is that none does.

## E. If you run long (over 90s)

Cut in this order: Scene 3's editing sub-beat first. **Never** cut Scene 4
(airplane mode) or Scene 5 (Italian) — those are the two things no competitor
can show.

## F. Framing & captions (16:9 with a vertical phone)

- The vertical phone capture, centred on a 1920×1080 canvas, leaves wide bars
  left and right. **Fill those bars with the brand plum** (the app icon's
  background colour) instead of pure black — it ties the frame to the app and
  reads as intentional, not cropped.
- Put the running callouts in the **right-hand bar**, vertically centred, in the
  cream/off-white from the icon. Keep each ≤ 6 words. The left bar can stay clean
  or hold a small static Notari logo.
- The Italian gloss in Scene 5, and any longer line, can sit in a **lower band**
  across the full width.
- Title (Scene 1) and closing (Scene 6) cards use the **full frame** on the plum
  background — no phone shown there.
- Speed-ramp (for the Scene 2 wait) and simple cuts are all easy in Kdenlive:
  one video track for the phone clip, a colour clip behind it for the plum bars,
  title clips for captions/cards, a Speed effect on the structuring segment.
