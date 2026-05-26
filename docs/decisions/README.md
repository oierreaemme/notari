# Architecture Decision Records

Each ADR captures a single decision with its context, alternatives,
and consequences. We follow [Michael Nygard's
template](https://github.com/joelparkerhenderson/architecture-decision-record/blob/main/locales/en/templates/decision-record-template-by-michael-nygard/index.md).

| ID   | Status   | Title |
|------|----------|-------|
| 0001 | Accepted | [Module structure and Clean Architecture layering](0001-module-structure.md) |
| 0002 | Accepted | [Privacy enforcement (the cardinal rule)](0002-privacy-enforcement.md) |
| 0003 | Accepted | [ASR strategy: SpeechRecognizer for v1, Gemma audio for v2](0003-asr-strategy.md) (v2 direction superseded by 0018) |
| 0004 | Superseded | [Gemma 4 E2B packaging and runtime delivery](0004-gemma-model-packaging.md) (runtime + delivery sections superseded by 0008) |
| 0005 | Accepted | [JSON output contract for the structuring step](0005-json-output-contract.md) |
| 0006 | Accepted | [MVI state shape for feature ViewModels](0006-mvi-state-shape.md) |
| 0007 | Accepted | [Strip transitive network permissions](0007-strip-transitive-network-perms.md) |
| 0008 | Accepted | [LiteRT-LM runtime + SAF-based model delivery](0008-litertlm-and-saf-import.md) |
| 0009 | Accepted | [Engine lifecycle + dynamic timeouts](0009-engine-lifecycle.md) |
| 0010 | Accepted | [Prompt temporal context for ISO date resolution](0010-prompt-temporal-context.md) |
| 0011 | Accepted | [Backend probing (GPU → CPU) + MTP speculative decoding](0011-backend-probing-and-mtp.md) |
| 0012 | Accepted | [Tag consistency via existing-tags prompt context](0012-tag-consistency-via-prompt.md) |
| 0013 | Accepted | [Optional biometric launch lock](0013-biometric-launch-lock.md) |
| 0014 | Accepted | [Orthographic cleanup: where "transform" stops being "invent"](0014-orthographic-cleanup.md) |
| 0015 | Accepted | [LLM emits intent, deterministic code imposes form](0015-llm-intent-code-form.md) |
| 0016 | Accepted | [Separate engine load from inference timeout](0016-engine-load-inference-split.md) |
| 0017 | Accepted | [Language bleeding and inference contention](0017-language-bleeding-and-inference-contention.md) |
| 0018 | Proposed | [Continuous-streaming ASR: replace SpeechRecognizer with Vosk](0018-continuous-streaming-asr-vosk.md) |
| 0019 | Proposed | [Encryption at rest, decoupled from the biometric lock](0019-encryption-at-rest-decoupled-from-biometric.md) |

## Adding a new ADR

1. Copy the most recent file as the next number.
2. Set status to `Proposed` while drafting.
3. Open a PR; flip to `Accepted` when merged.
4. If a future ADR supersedes one, mark the old one's status
   `Superseded by 00XX` and link forward.
