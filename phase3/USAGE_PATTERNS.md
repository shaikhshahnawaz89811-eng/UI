# Phase 3 — Usage Patterns Report

Generated from the final `phase3/questions.tsv` corpus after the Phase 3 planner fixes.

## Corpus shape

| Measure | Count |
|---|---:|
| Total questions | 1000 |
| Questions planned for web | 890 (89.0%) |
| Questions planned offline | 110 (11.0%) |
| Categories | 10 |
| Questions per category | 100 each |
| AUTO mode | 990 |
| OFF mode | 5 |
| ALWAYS mode | 5 |
| Attachment-context rows | 5 |
| Previous-query rows | 5 |

## Intent buckets

| Category | Questions | Purpose |
|---|---:|---|
| offline | 100 | Stable explanations that should not need the web |
| fresh | 100 | Current/time-sensitive facts such as prices, scores, news, weather and updates |
| explicit | 100 | User explicitly asks to search online |
| image_show | 100 | Search for pictures/diagrams intended to be shown |
| image_read | 100 | Search for pictures intended to be read/analyzed by vision |
| links | 100 | Official or multiple source links |
| procedure | 100 | Current/official procedure and how-to flows |
| compare | 100 | Side-by-side comparison requests |
| url_read | 100 | Pasted URL/page reading, including PDF/image links |
| mixed | 100 | Multiple constraints, settings, previous context and attachments |

## Output features represented in the corpus

| Feature | Rows |
|---|---:|
| Show images (`img`) | 135 |
| Read images (`readimg`) | 100 |
| One link (`link1`) | 192 |
| Many links (`linkN`) | 40 |
| Steps (`steps`) | 132 |
| Compare (`cmp`) | 100 |
| Read-only (`ro`) | 60 |

The corpus is deliberately mixed-language. A simple lexical marker check finds Hinglish-like wording in 815 rows and English-only wording in 185 rows; this is corpus metadata, not a language-quality score.

## Final deterministic verification

The JVM suite completed with **3,051 checks, 0 failures**. The new Phase 3 corpus itself reports **1,000 questions, 890 planned searches, 890 actual searches, and 0 planner mismatches**.

The existing conversation stress test remains separate from the web-planner score. Its latest run exercised 1,300 messages with 1,234/1,300 ground-truth behaviours matching (94.9%). The report also exposes known weak spots in conversation-level paraphrase handling, so those are catalogued separately rather than hidden by the Phase 3 web result.

## Live-run guidance

The live runner is included but was not automatically pointed at a real Tavily key in this build. That avoids silently consuming live API quota. Use the runner with a key from an environment variable, and start with a small sample before `--all`.
