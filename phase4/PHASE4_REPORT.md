# Phase 4 — 1,500 command conversation/web verification

## Corpus

`commands1500.tsv` contains exactly 1,500 deliberately varied commands:

- 100 offline chat/code/teaching commands
- 100 fresh/current-information commands
- 100 explicit web-search commands
- 100 image-show commands
- 100 image-read commands
- 100 link commands
- 100 official-procedure commands
- 100 comparison commands
- 100 pasted-URL reading commands
- 100 mixed web commands
- 100 web follow-up commands
- 100 exact-repeat conversation commands
- 100 same-language paraphrases
- 100 English/Hinglish cross-language commands
- 100 attachment-context commands

## Phase 4 fixes found during the run

1. Explicit online search phrasing such as `online ... dhoondho` now enters the web planner.
2. `photo mat dikhana` no longer disables explicit image-reading; it suppresses rendering while still allowing the vision/read path.
3. Conversation normalization now handles additional English/Hinglish semantic equivalents.
4. Return phrases containing `pehle wale` remain return cues; normalization no longer destroys that cue.
5. Same-question matching now avoids treating different named entities (cities, products, players, appliances, algorithms) as the same question.
6. Cross-language repeat matching has a conservative fallback using domain + meaningful shared content instead of broad domain-only matching.

## Verification

- Phase 3 planner corpus: **1,000/1,000** with zero mismatches.
- Phase 4 command corpus: **1,500/1,500** parsed and completed.
- Phase 4 expected web searches: **1,000**.
- Phase 4 actual web-search decisions: **1,000**.
- Phase 4 planner flags: **0 mismatches**.
- Full JVM suite: **10,565 checks, 0 failures**.
- Existing 1,000-message assistant regression: **PASS**.
- Existing 1,300-message stress suite: **PASS**.
  - Topic-change recall: 99.7%
  - Topic-change precision: 99.4%
  - Same-question recall: 94.1%
  - Same-question false-positive rate: 0.33%
  - Overall stress accuracy: 95.5%

The remaining stress misses are primarily deliberately difficult paraphrase/cross-language semantic matches and a small number of topic-selection edge cases; they do not cause a test failure and are listed by the stress report rather than hidden.

## Build note

The pure-Java core and all JVM checks run successfully. An Android Gradle build was not run in this environment because the Android/Gradle SDK toolchain is not installed here; no APK-build result is claimed from this environment.
