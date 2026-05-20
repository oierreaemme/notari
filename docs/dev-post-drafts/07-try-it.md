# DEV post — Section 7: Try it

> The closing section. Boilerplate links, but the small choices matter:
> the order signals what the author wants the reader to actually do.
> Code first (signals "this is real"), then APK (signals "and you can
> run it"), then video (signals "or just watch"), then README (for
> readers who want context before clicking install).
>
> All four links need to be filled in with real URLs before the DEV
> post is published. The placeholders below are clearly marked.

---

## Try it

- **Code** — [github.com/<USER>/notari](https://github.com/<USER>/notari)
  *(replace placeholder before publishing; repo to be made public the
  day of submission)*
- **APK** — direct download on the GitHub Releases page above; SHA-256
  published alongside so you can verify the file you installed is the
  one the source produced.
- **Demo video** — [<2-minute YouTube embed>](https://youtu.be/PLACEHOLDER)
  showing a real Italian dictation structured on-device, a verification
  in airplane mode, and a quick filesystem inspection confirming no
  audio file is created.
- **Architecture & decisions** — `docs/decisions/` in the repo holds the
  16+ ADRs written during development. ADR 0008 is the one to read if
  you want to understand why the model is delivered via the Storage
  Access Framework instead of `DownloadManager`; ADR 0002 is the one
  to read if you want to understand the privacy enforcement.

Notari is a research project as much as a product — every architectural
choice that went in is documented in the open, so if you disagree with
one, you can read why I disagreed with you. Pull requests, issues, and
honest criticism are all welcome.

---

*Built for the [Google Gemma 4 Challenge: Build with Gemma 4](https://dev.to/challenges/google-gemma-2026-05-06)
on dev.to · May 2026 · Apache 2.0 license*
