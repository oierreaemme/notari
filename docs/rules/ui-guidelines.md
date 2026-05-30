# UX/UI Principles - Notari

## Material 3 Baseline (Non-negotiable)
* **Dynamic color** on Android 12+, fallback to brand palette below.
* **Edge-to-edge layouts** with proper insets handling.
* **Predictive back** gesture and animation.
* **Adaptive layouts** for foldables/tablets (WindowSizeClass).
* **Motion:** M3 motion specs (emphasized easing for transitions).
* **Typography:** M3 type scale strictly.
* **Accessibility:** Minimum 48dp touch targets, WCAG AAA contrast on critical paths.
* **Iconography:** Material Symbols (rounded). No third-party packs.

## Beyond Baseline
* **Onboarding:** Maximum 3 screens ("1. Speak. 2. We make it Markdown. 3. Audio never leaves your phone.").
* **Capture Screen:** This is the home screen. Big record button. No dashboard. No drawer navigation.
* **Recording UI:** Real audio amplitude waveform, one stop button, language indicator. No timers by default.
* **Post-recording:** Show "Structuring your note..." animated indicator. Structured note slides in after processing.
* **Notes List:** Chronological, filterable by tag. Cards show title, first line, tags, date.
* **Empty States:** One-line invitation: "Tap the mic to capture your first thought." No "No data" screens.
* **Errors:** Friendly and actionable. No technical panics.

## UI Edge Cases to Handle
* Note with no tags: render gracefully, no empty chips.
* Long titles: ellipsize at 60 chars in list, full text in detail.
* Hundreds of notes: list must scroll smoothly (LazyColumn with stable keys).
* Rapid taps on record: debounce.
* Configuration changes: preserve recording state across rotation.