# DEV post — opening hook drafts

> Working document. Three candidate openings for the DEV submission post,
> structured as the first section ("The Problem", 3–4 paragraphs per
> CLAUDE.md §12). All drafts are in English — the DEV post itself is
> English (the app's dictation is multilingual; the documentation is not).
>
> The hook is the highest-leverage paragraph in the entire submission. It
> decides whether a judge or reader keeps scrolling. After analyzing nine
> competing submissions on 2026-05-18 the clearest pattern in the strong
> ones (PhotoLens, DiagramFlowAI, ROO) is a cinematic opening scene that
> makes the architecture feel *inevitable* rather than a feature list.
>
> Constraints baked into every variant below:
>
> - **Universal scene**, not a personal anecdote. Voice-note users span the
>   whole population; the scene must read as everyone-has-been-there.
> - **No use of "ethical position, not capability compromise"** — Susant
>   Swain used that phrase verbatim in PhotoLens on 2026-05-15. We must say
>   the same idea differently.
> - **No contrarian-thesis opening** ("I didn't use cloud Whisper, I didn't
>   use 31B…"). DiagramFlowAI already owns that template; doing it again
>   reads as imitation.
> - Lead with **the productivity failure first, the privacy failure
>   second** — judges have read several "privacy first" submissions, but
>   none of them lead with the retrieval problem voice notes uniquely
>   create.

---

## Variant A — The graveyard of audio files (preferred)

> A bit over a year ago I tried to dictate a half-formed product idea on
> my way to a meeting. I knew I would forget the specifics by the time I
> got to a keyboard, so I tapped the microphone, talked for two minutes,
> and put the phone away.
>
> When I went looking for it three weeks later, I scrolled through eighty
> audio files named `recording_2025-04-17_09-42-18.m4a`. I had a vague
> memory of the day. I did not have a vague memory of the time. I played
> nine of them back at 1.5× speed before giving up.
>
> This is the part of voice-note apps nobody markets. Capturing a voice
> note is easy — every phone has had a microphone button for fifteen
> years. **Finding** a voice note three weeks later, when you only
> half-remember when you said it and what you said, is where the entire
> premise quietly fails.
>
> And there's a second failure underneath the first: the voice notes you
> *didn't* record. The thought you started to dictate at 11 PM and put
> away half-pressed because you didn't want a recording of yourself
> reasoning out loud sitting in someone else's cloud. Both failures have
> the same root cause: the audio capture and the audio understanding live
> in different places, owned by different parties, on different rules.
>
> **Notari** is what happens when you put them back together
> on a single device. You speak. The phone transcribes locally, asks a
> small language model running on the phone to give the transcript a
> title, tags, parsed datetime references, and a clean Markdown body, and
> then forgets the audio buffer before anything is written to disk. The
> note becomes a file you can search, open, and export — not an audio
> blob you have to scrub through.

**Why I lead with this one**: it grounds the problem in retrieval before
privacy. Retrieval is something *every* voice-note user has lived; not
every user has hesitated to record. Privacy enters as the second beat,
which lets it land harder when revealed — the reader has already nodded
to the first failure and is now thinking "and also…". It also sets up
the structuring features (title / tags / datetime / Markdown body) as
the natural answer, not as feature-list filler.

---

## Variant B — The hesitation moment

> There's a specific feeling, late at night, of holding a phone with the
> mic button under your thumb and not pressing it.
>
> Maybe it's an idea you don't want to forget by morning. Maybe it's the
> first honest articulation of something at work that's been bothering
> you for weeks. Maybe it's a sentence you want to send to a friend
> tomorrow and you want to get the wording right. Whatever it is, you
> have it now, you'll lose it by morning, and the obvious tool — the
> voice memo app — is the wrong tool. The voice memo app is going to
> save the audio. To a file. Possibly to the cloud. Definitely somewhere
> you can't fully account for.
>
> So you don't press the button. You try to hold the thought in your
> head until you find paper. You lose half of it anyway.
>
> Voice notes are unusual in this regard. A text note can be deleted
> with a tap; the contents were only ever the words. An audio note is
> different. It contains your voice — the cadence, the hesitation, the
> background sound of your apartment, the cough halfway through. An
> audio file is a richer object than a text file, and once it exists,
> getting rid of it convincingly is harder than people think.
>
> **Notari** removes the audio file from the equation. The
> recording never reaches disk. It is held in memory only during the
> seconds of capture, streamed to an on-device speech recognizer, and
> overwritten the moment the transcript exists. The transcript then
> passes through a small language model — also running on the phone, no
> network call ever — that turns it into a structured Markdown note:
> title, tags, datetime references, a clean body. What's saved is text.
> What spoke the text is gone.

**Why this is the runner-up**: it leads with the privacy pain directly,
which is stronger ethically and weaker universally. Some readers won't
recognize the hesitation moment (heavy phone users have desensitized to
it) and we lose them in the opening. Variant A's retrieval pain is
harder to deny. But B is the better hook if we expect the audience to
skew toward privacy-conscious developers.

---

## Variant C — The translation tax

> If you've ever dictated a note in any language other than English,
> you've probably noticed a small indignity: the result comes back with
> English creeping in. The title, the suggested tags, the summary — even
> when you spoke Italian or Spanish or Portuguese for the whole two
> minutes, the structured output assumes you'd rather read English.
>
> It's not malicious. It's a side effect of where these tools were
> built and what they were trained on. English is the default, the
> implicit "shared language," even when you and your phone are alone
> together in a kitchen in Bologna.
>
> The fix shouldn't be a setting. The fix should be that the note comes
> back in the language you spoke it. Italian transcript → Italian
> title, Italian tags, Italian Markdown body. Spanish transcript →
> Spanish everything. The phone should listen in your language and
> reply in your language, the way every other in-person interaction
> works.
>
> **Notari** does this for six languages (English,
> Italian, Spanish, French, German, Portuguese) by detecting the
> dictation language and instructing the on-device model to preserve
> it end-to-end. The detection is done by the speech recognizer; the
> structuring is done by Gemma 4 E2B running locally on the phone;
> nothing is sent anywhere. The note ends up as a Markdown file in the
> language you spoke, ready to search, open, and export.

**Why this is the fallback**: it owns a real and specific differentiator
(genuine multilingual native output) but speaks to a smaller audience.
A judge whose first language is English may not feel the indignity.
Could work better as a *companion post* than as the main opening — see
the series-strategy memory.

---

## Recommendation

Go with **Variant A** for the main DEV post opening. Use the privacy
beat from Variant B as the bridge sentence that closes paragraph four
("And there's a second failure underneath…") — already done in the draft
above. Hold Variant C in reserve as the opening for a companion post
titled something like *"Voice notes shouldn't translate themselves: how
we kept dictation in the user's language end-to-end"*. That post would
go up 48 hours after the main one to extend the submission's surface area
in the final push to the 2026-05-24 deadline.

The remaining sections of the main post (the approach, technical
highlights, what I learned, what's next, try it) will be drafted as
separate files in this directory once Mario confirms the opening
direction. Drafting them out of order would risk locking decisions
before the hook is final, since the hook sets the rhetorical voice for
the rest.
