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


## CI compile fix
GitHub Actions reported `ChatPage.java:678: cannot find symbol VERTICAL` in the static image-preview helper. The call is now `root.setOrientation(LinearLayout.VERTICAL)`. CI Gradle setup was also moved from 8.13 to 8.14.4.

## Office Skills extension

The Phase 4 attachment/skill layer was extended with three Office skills after checking Anthropic's current public skill definitions: `docx`, `xlsx`, and `pptx`. Anthropic describes these as primary-input/output workflows for reading, creating and editing modern Office files. citeturn565469search0turn565469search4turn565469search2

Local implementation:
- DOCX: paragraph/table text extraction, styled creation, targeted text replacement.
- XLSX: sheet/cell/formula-aware extraction, CSV/TSV input, spreadsheet creation, targeted cell editing. Formula results are not fabricated when there is no cached result.
- PPTX: slide text extraction, simple 16:9 deck creation, targeted text replacement.
- File classification checks OOXML internal package parts (`word/document.xml`, `xl/workbook.xml`, `ppt/presentation.xml`) so an ordinary ZIP is not confused with an Office file.

Verification:
- Full JVM regression: **10,597 checks, 0 failures**.
- Generated Office packages: ZIP entries and all XML parts validated as well-formed.
- Independent readers: `python-docx`, `openpyxl`, and `python-pptx` opened the generated DOCX/XLSX/PPTX.
- External compatibility read test: Python-generated DOCX/XLSX/PPTX content was successfully extracted by the new Java skills.

The Office skills are connected to the live chat execution path. The final wiring pass also added a visible Gemma/Qwen model switch and enabled attachment/skill routing in the coding chat.

## Skills review (audio / video / docx / xlsx / pptx) - bugs found and fixed

Scope at the time of this historical review: only the existing skills were reviewed and fixed; the later final wiring pass added the live model switch and chat execution hardening described below.

Android
1. `AttachmentReader.load()` had no `case AUDIO`, so an attached audio file was never read (the model got no audio note at all). Added.
2. Audio sample rate was read with `extractMetadata(39)` (that key is bits-per-sample, API 31+ only) and channels with key `40` (does not exist), and the text said "1 channels". Sample rate and channel count now come from `MediaExtractor` (all Android versions); duration is shown as m:ss.

Pure-Java core
3. XLSX read: an empty styled cell `<c .../>` swallowed the next cell (values were attributed to the wrong cell and a cell vanished); a self-closing row swallowed the next row. Fixed.
4. XLSX read: rich-text strings came out as "Hel lo"; inline text with `<` / `>` lost words; entities were decoded twice; an empty `<si/>` shifted every later shared string by one. Fixed.
5. XLSX setCell: editing an empty styled cell destroyed the cell next to it; new cells/rows were appended at the end instead of in column/row order (Excel reports such a file as damaged); numbers were saved as text; a stale `calcChain.xml` could make Excel ask to repair the file; the master cell of a shared formula is now refused instead of silently breaking the others.
6. XLSX create: sheet names with `\ / ? * : [ ]` or longer than 31 characters made an invalid workbook; "007" lost its zeros and 16+ digit numbers were rounded. Fixed (such values stay text).
7. DOCX: replacing text that contains `'` or `"` always failed (the file keeps them unescaped); tabs/line breaks were dropped so words were glued; `<w:p .../>` (self-closing) merged two paragraphs; page margins lacked required attributes.
8. PPTX package: slide master had `clrMap` after `txStyles` and a layout id outside the valid range, theme had empty format lists and fonts without ea/cs - PowerPoint can ask to "repair" such a file. Rebuilt in schema order. Body text box ran below the slide edge; now inside it. Every bullet is its own paragraph and runs are really Arial.
9. PPTX read: a sentence split into several runs read as "Hel | lo"; now one sentence per paragraph. PPTX replace: same apostrophe problem as DOCX. Fixed.
10. Text with control characters (e.g. pasted from a PDF) made the generated XML invalid; they are now removed. `xmlUnescape` decoded `&amp;#65;` twice; now one pass.
11. `SkillPlanner` matched pieces of words: "password banao" selected the Word skill, "logging" / "wave" selected the audio skill. Whole-word matching now.
12. `FileSniffer`: M4A audio and HEIC/AVIF pictures were reported as VIDEO (ftyp brand now decides); AMR voice notes are recognised. `SkillRegistry`: audio reader no longer claims it needs the vision model. CSV with a UTF-8 BOM no longer shows an invisible character in the first cell.

Verification
- New `SkillBugfixTests` (79 checks) - the same tests fail on the previous code.
- Full JVM suite at the skills review point: **10,683 checks, 0 failures**; stress / 1,000-message regression unchanged (topic-change 99.7% / 99.4%, same-question recall 94.1%, false positives 0.33%).
- Generated DOCX/XLSX/PPTX re-opened with python-docx, openpyxl, python-pptx and LibreOffice (rendered slide checked by eye); all XML parts well-formed.
- Android-only code (`AttachmentReader`) could not be built here (no Android SDK); the new audio method was type-checked against hand-written stubs only. Build it with GitHub Actions and try one MP3 / M4A / WAV on a phone.

Not changed on purpose (outside the skill scope): `SkillPlanner.forTurn(...)` is still only called and not used to drive anything; attachment chips for Audio / Word / Excel / PowerPoint still show the picture icon (`AttachViews.iconKind`) because the UI was not to be touched.
