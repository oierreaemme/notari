# Prompt evaluation suite

This folder is the *living test suite* for the Gemma structuring prompt
(`structure_note_v1.txt` and any successors). Per CLAUDE.md section 6,
every prompt change is accompanied by a run against this suite, and any
edge case found in the wild is added here.

## Layout

Each `.transcript.txt` file contains a real (or realistic) raw
transcript. The matching `.expected.json` file contains a snapshot of
what the model produced when the prompt was authored, OR a hand-written
expectation when we want to pin a specific behavior.

```
prompt-eval/
├── en/
│   ├── reminder-call.transcript.txt
│   ├── reminder-call.expected.json
│   └── ...
├── it/
├── es/
├── fr/
├── de/
├── pt/
└── adversarial/   <- empty input, lyrics, prompt-injection attempts, etc.
```

## How it runs

A JUnit suite (to be added in `core/inference/src/test`) loads each pair,
feeds the transcript through a fake/recorded inference session, and
compares the parsed output against the expectation. The match is
*structural*, not byte-exact:

- JSON validity → binary
- Schema conformance → binary
- Tag set equality (order-insensitive) → binary
- Title — fuzzy match (Levenshtein within threshold)
- Body — fuzzy match (token overlap > 0.8)

Pinned outputs catch regressions; fuzzy matching tolerates harmless
phrasing drift.

## Initial seed (TODO before v1)

The first 20 transcript pairs need to be authored — see the project
TODO list. Until then this folder is intentionally sparse.
