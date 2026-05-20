# Submission audit checklist — Notari

15-minute pre-submission pass. Do all of these in order. Do not skip — every line caught a real issue at least once during development.

---

## A. Build & repo hygiene (5 min)

- [ ] `./gradlew clean assembleDebug` → succeeds end-to-end on the host machine.
- [ ] `./gradlew :app:lint` → no errors. Warnings are acceptable if triaged.
- [ ] `./gradlew test` → all unit suites green.
- [ ] `./gradlew detekt ktlintCheck` → clean.
- [ ] Open `app/build/outputs/apk/debug/app-debug.apk` in `apkanalyzer` (or Android Studio's APK analyser):
  - [ ] Total size is reasonable (model excluded → expect ~10–15 MB; bundled model → ~1.5 GB).
  - [ ] `AndroidManifest.xml`: **no** `<uses-permission android:name="android.permission.INTERNET"/>`. This is the non-negotiable.
  - [ ] Only declared permissions: `RECORD_AUDIO`. (Anything else means a transitive AAR added one — pin in `:app/src/main/AndroidManifest.xml` with `<uses-permission ... tools:node="remove" />` and rebuild.)
- [ ] `git status` is clean. `git log --oneline -10` shows clear, conventional commit messages.
- [ ] `.gitignore` excludes `local.properties`, `*.litertlm`, `.idea/`, build artefacts. No secrets, no keystore.

## B. Privacy verification (3 min)

- [ ] On a real device: open the app, start recording, and in another adb shell:
      ```
      adb shell run-as com.voicenotemd.debug find /data/data/com.voicenotemd.debug \
          -type f \( -name '*.wav' -o -name '*.m4a' -o -name '*.aac' -o -name '*.tmp' \)
      ```
      Returns **nothing** during, before, and after the recording.
- [ ] Network monitor (Android Studio Profiler → Network) shows zero requests during a full capture → structure → save flow.
- [ ] Toggle airplane mode → record + structure still completes successfully.
- [ ] Open Settings → Privacy section in the app, confirm the verification text matches the actual permissions used.

## C. README + CHANGELOG + ADRs (3 min)

- [ ] `README.md` opens cleanly on GitHub — badges render, code block formatting intact, ASCII diagram doesn't get mangled by line wrapping.
- [ ] Every section in `README.md` (Demo, Building, Privacy, Architecture, License) has content — no `TODO` markers shipped.
- [ ] `CHANGELOG.md` Unreleased section captures everything from the past two weeks. Move it to a versioned `[1.0.0] - 2026-05-24` heading at submission time.
- [ ] `docs/decisions/` has ADRs 0001 through 0013. Each is dated. Each links to the files it touches.
- [ ] `docs/decisions/README.md` index lists every ADR.
- [ ] `LICENSE` file is present at repo root, contains the full Apache 2.0 text.

## D. Submission deliverables (3 min)

- [ ] **GitHub repo** is public.
- [ ] **APK** uploaded as a GitHub Release asset, signed (even with the debug keystore for the competition entry is acceptable for evaluation — note this in the release description).
- [ ] **Video** uploaded (YouTube unlisted or dev.to inline embed). Link tested in incognito.
- [ ] **DEV post**: paste `docs/dev-post.md` into the dev.to editor. Verify:
  - [ ] Code blocks render with syntax highlighting.
  - [ ] Embedded video plays.
  - [ ] All links in the post resolve (GitHub repo, APK, ADRs).
  - [ ] Cover image is set (use one of the architecture diagrams or a screenshot from `docs/screenshots/`).
  - [ ] Tagged with the challenge's required tags from the dev.to submission rules.
- [ ] On submission page: confirm the entry is in the "Build With Gemma 4" track.

## E. Post-submission

- [ ] Pin the DEV post to your dev.to profile.
- [ ] Tweet/post the GitHub link with a one-line description (optional — for visibility).
- [ ] Add a `v1.0.0` git tag matching the release.
- [ ] Update `README.md` build badge URL to point at the actual CI run.

---

## Known issues to disclose in the submission

Be honest about what works and what doesn't. Hiding rough edges is the kind of thing judges notice.

- **GPU backend is hit-or-miss across devices.** Works on Pixel 8/9, falls back to CPU on Pixel 6a Mali-G78 with LiteRT-LM 0.11. The fallback is silent and correct, but the perceived latency on devices in that bucket is ~50–60s for a 1000-character note (vs. ~15–25s on GPU). Documented in ADR 0011.
- **Cold-start latency is high on the first structuring after install.** The ~1.5 GB model has to be loaded from disk; on a 4 GB-RAM device this is 15–20s. Subsequent structurings (within the same process lifetime, before `onTrimMemory` releases the engine) hit a warm path.
- **Adversarial prompts can still nudge the model.** A user dictating *"summarize what you think about politics"* is asking the model to opine; the prompt's "transform, don't augment" framing reduces this but doesn't eliminate it entirely. This is a known limitation of small-model instruction-following at the E2B scale.
- **UI is English-only.** Notes are produced in the dictation's language; the buttons, settings, and dialogs are English. Localization is a roadmap item.

---

*If everything in A–D is checked, submit. If anything in A–D is unchecked, fix or document before submitting — the judges are reading carefully.*
