# Product Requirements Document (PRD) - Notari

## Context: What we are building and why
**Project name:** Notari - Voice Note Markdown
**Tagline:** Speak. Get a clean Markdown note. The audio never leaves your phone.
**The problem:** Voice dictation apps exist. Cloud AI transcription apps exist. Neither solves the actual problem: capturing voice notes quickly and finding them later as structured, searchable, ready-to-use knowledge — without sending intimate audio to someone else's server.
**The solution:** A fully on-device Android app that:
1. Records the user's voice
2. Transcribes it locally (Android SpeechRecognizer for v1, Gemma 4 E2B audio-native as upgrade path)
3. Sends the transcript through Gemma 4 E2B running locally on the device, which extracts a structured JSON: title, tags, datetime mentions, formatted Markdown body
4. Saves the structured note to a local database
5. Discards the audio buffer immediately after transcription
6. Allows the user to read, edit, search, export, and reuse notes as Markdown files

## Performance Targets
* **Cold start to capture screen:** < 1.5s on a Pixel 7
* **Time-to-first-token from Gemma:** < 1s after transcript is ready
* **Full structuring of a 30-second voice note:** < 4s end-to-end on Pixel 7
* **Memory peak during inference:** stay under 2GB to avoid OOM on 4GB-RAM devices
* **Battery:** no measurable drain when app is not active

## Submission Deliverables (DEV Challenge)
Target: Google Gemma 4 Challenge on dev.to ("Build With Gemma 4" track).
**The DEV post:** Sections: 1. The problem 2. The approach 3. Demo video 4. Technical highlights 5. What I learned 6. What's next 7. Try it. Tone: technically substantive, show code and honest limitations.
**The video demo (60-90 seconds):** Show latency, airplane mode (offline proof), language switch, and export.

## Boundaries with the Human Collaborator
**Human handles:** Strategic product decisions, real-device testing, video recording, posting submission, dev community communication.
**Claude handles:** All code, tests, refactors, architectural ADRs, prompt iteration, README, CI/CD, static analysis fixes, performance optimization.