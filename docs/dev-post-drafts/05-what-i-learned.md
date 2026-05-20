# DEV post — Section 5: What I Learned about Gemma 4 E2B

> Section 5 per CLAUDE.md §12: "honest observations: where the model shines,
> where it struggles, what surprised me, what I'd recommend to others."
> Target length: 3–4 paragraphs.
>
> This section is what separates a submission that says "I built a thing"
> from one that says "I learned something concrete and you should care."
> Honesty is the lever — claiming the model is amazing on every axis
> destroys credibility; admitting specific limits earns it. Material below
> is grounded in real on-device measurements taken on 2026-05-19 with
> Gemma 4 E2B INT4 via LiteRT-LM on a Pixel.

---

## What I Learned about Gemma 4 E2B

Three observations from the on-device runs are worth sharing with anyone
considering Gemma 4 E2B for a similar problem. They are not the kind of
takeaways you get from a benchmark; they only show up once you have a real
prompt, a real model file, a real device, and an honest evaluation eye.

**E2B respects the spirit of "transform, don't invent" if the prompt is
strict.** This was the open question I cared about most. A 2-billion-effective
model trained on broad text can produce many things in response to a
free-form transcript — completions, summaries, suggestions, paraphrases —
but the contract I wanted was narrower: clean up the speech, preserve the
meaning, never add. In testing the structuring on a real dictation that
ended with *"…wait, there was something else I wanted to write, something
about sensors, but I don't remember. I'll note it later"*, the model kept
that exact uncertainty in the body, without trying to fill in what "something
about sensors" might have been. It also preserved transcription errors like
*"trbka morena"* (a noun phrase that came out garbled from speech-to-text)
without rewriting them into something plausible. That kind of conservatism
is what makes the output trustworthy. The prompt does most of the work
here — ABSOLUTE RULES section, clear examples, an explicit "stay
conservative — when in doubt, keep the original word" clause — and E2B
follows it reliably enough that I have not needed a hallucination-recovery
path on top.

**The famous "Thinking Mode" isn't accessible from prompt instruction on
this stack.** This one surprised me, in part because the broader Gemma 4
community has been talking about the reasoning trace as one of the model
family's defining features — I had read three independent reports of
developers using `<thought>...</thought>` tags to inspect and surface the
model's chain of thought before the final answer. I ran a controlled spike:
modified the structuring prompt to explicitly request reasoning inside
`<thought>` tags before the JSON, rebuilt, reinstalled, and processed three
real voice notes on a Pixel. Three out of three, the model emitted the
JSON directly with no reasoning trace at all. I cannot tell from outside
whether the LiteRT-LM runtime strips the reasoning tokens before they
reach the application, whether the INT4-quantized E2B variant simply
doesn't surface them, or whether the dense JSON-only prompt context
overrode the late-added instruction. The practical conclusion is the same:
if you're building on edge Gemma 4 via LiteRT-LM on Android today, you
don't get the "show the user the model's reasoning" UX for free. The
parser still strips `<thought>` tags defensively — that protection costs
nothing and earns optionality against future model behavior changes — but
do not plan v1 features that depend on it.

**JSON output is reliable in shape, occasionally non-pristine in punctuation.**
Across the dictations I tested in Italian, English, and Spanish, every
single response was parseable into the schema — `language`, `title`, `tags`,
`mentions`, `body_markdown` — without ever falling through to the
plain-text fallback. The shape is rock-solid. What does vary, run to run,
is presentational detail: sometimes the model returns compact single-line
JSON, sometimes pretty-printed multi-line; tags occasionally include a
literal newline character inside a string (technically invalid per
RFC 8259, since JSON strings cannot contain raw newlines); the body
markdown alternates between task-list style (`- [ ] item`) and prose
paragraphs for similar transcripts. None of this is dangerous, and the
parser absorbs it: I added small targeted sanitization for code fences,
prose preambles, and raw newlines inside strings, and the lenient Moshi
configuration handles the rest. If you build on E2B and care about
structured output, plan on a sanitization layer between the model and a
strict JSON parser — you will need it occasionally, and adding it once
beats living with intermittent parse failures.

**A recommendation, if you're starting from here:** lean on a strict
schema in the prompt and a forgiving parser in the code, not the other
way around. The temptation with a 2B-class model is to write a softer
prompt to avoid "stressing" the model and a strict parser to catch the
fallout — but in my experience the inverse works better. E2B follows a
firm, opinionated, example-rich prompt with surprising consistency, and
the few output quirks it does produce are exactly the kind of thing a
sanitization step handles cheaply. Spend your effort on the prompt and
on the recovery path between model and parser, not on hoping the model
will magically produce RFC-8259-perfect JSON every time. Treat the model
as a small, fast, opinionated colleague who needs clear briefing and
gentle proofreading, not as an oracle. That is the mental model that
makes E2B feel like the right tool instead of a compromise.
