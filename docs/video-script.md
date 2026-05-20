# Notari — Demo Video Script (60–90s)

Target length: **75 seconds**. Hard ceiling: 90s. Recorded on a real device with `scrcpy` mirroring the Pixel 6a so the actual UI latency is what the viewer sees.

Voiceover is plain spoken English. No background music — speech needs the audio bandwidth.

---

## 0:00 – 0:05 — Hook

**On screen:** App icon → app opens to the capture screen. No animation help, just the real cold-start.

**Voiceover:**
> "This is a voice note app. The audio never leaves your phone."

---

## 0:05 – 0:25 — Live recording + structuring

**On screen:** Tap mic. Live waveform reacts to amplitude. Speak naturally:

> *"Tomorrow at three thirty I have a meeting with Federico about the Lighthouse project. I need to prepare the slides and bring my MacBook. Also I have to call my mum sometime this week."*

Tap stop. "Structuring your note…" progress affordance appears. Wait through the **real** wait (do not cut). On the reference Pixel 6a with the CPU backend this is ~25–35 seconds for that transcript length. **Do not speed up.** The honesty of the latency is the credibility of the demo.

**Voiceover during the wait:**
> "Gemma 4 E2B is running locally. About 1.5 gigabytes of model on the device. No cloud."

---

## 0:25 – 0:45 — The resulting Markdown

**On screen:** The structured note slides in. It has:

- A title: *"Meeting with Federico — Lighthouse"*
- Tags: *#work*, *#lighthouse*
- A dated mention: *"tomorrow at three thirty"* → ISO-resolved to the actual tomorrow at 15:30 local time
- A body with `## Lighthouse meeting` heading, **bold** entity for *Federico* and *Lighthouse*, two `- [ ]` checkboxes for the commitments, and a final paragraph about calling Mum

Tap into the note. Show the editor. Tap **Share as Markdown** → the Android share sheet appears with the YAML-frontmatter Markdown ready to paste into Obsidian.

**Voiceover:**
> "Title, tags, dates, checkboxes. All extracted by Gemma. Ready to share as Markdown."

---

## 0:45 – 0:60 — The privacy proof

**On screen:** Swipe down → enable **Airplane mode**. Tap "back to capture". Record a quick new note:

> *"Quick reminder, buy bread on the way home."*

Stop. Structuring runs. Done.

**Voiceover:**
> "Airplane mode. Everything still works."

---

## 0:60 – 0:75 — Multilingual

**On screen:** Open language picker → tap **IT** (Italian). Record:

> *"Domani alle dieci e mezza vado dal dentista, poi devo passare in lavanderia."*

The structured note returns with Italian title (*"Dentista e lavanderia"*), Italian tags, Italian body, dated mention resolved to the actual ISO instant.

**Voiceover:**
> "Six languages. The note comes out in whatever you said."

---

## 0:75 – 0:90 — Outro

**On screen:** Settings → Privacy panel showing "No INTERNET permission. Audio never written to disk." Fade to GitHub repo URL on screen.

**Voiceover:**
> "Open source. Apache 2.0. Notari."

---

## Production notes

- Record at 1080×2400 native Pixel 6a resolution; let the editor crop / pillar-box if uploading to a 16:9 destination.
- No cuts during the structuring waits. Real latency is the whole point.
- The voiceover lines above add up to ~18 spoken seconds — there's deliberate breathing room around them.
- Caption track in English for accessibility. The Italian segment gets a translated subtitle.
- Export: H.264, 30fps, AAC audio at 128 kbps. Aim for under 25 MB so it embeds on dev.to without external hosting friction.
