# Skills wiring — Stage 2 report
Date: 2026-10-01

## Scope
Stage 2 connects the deterministic Stage 1 router to real file execution for DOCX, XLSX, PPTX and PDF creation, plus DOCX/XLSX/PPTX copy-on-edit. The visible chat only reports a file as ready after the output is present and verified.

## Implementation

### 1. Model-to-file protocol
`app/src/main/java/com/neonhud/app/core/skill/SkillContentMarker.java` defines a strict line-oriented marker format:
- DOCX/PDF: one `SKILL_FILE` block with body text.
- XLSX: one or more `SHEET` blocks with TAB-separated rows; Stage 2 writer currently executes one sheet per XLSX create request.
- PPTX: one or more `SLIDE` blocks with one bullet per line.
- bounded marker count / content / row limits reject malformed or oversized model output.

The model prompt in `SkillPrompt` tells CREATE tasks to use the protocol and explicitly forbids claiming a file is ready before app verification.

### 2. Real executor
`SkillExecution` is Android-free and uses the existing real skills:
- `DocxSkill.create/read/replaceText`
- `XlsxSkill.create/read/setCell`
- `PptxSkill.create/read/replaceText`
- `PdfWriter.createTextPdf/verify`

CREATE parses the requested marker, writes the real file, reopens it with the corresponding reader/verifier, and only then publishes it.

EDIT resolves the selected attachment or last verified output, writes to a new `-edited` file, reopens it and checks the requested replacement/value, then publishes the copy. The original file is not overwritten.

Failure after a file is created deletes the partial output, so a failed operation cannot leave a ghost file behind.

### 3. Android file bridge
`SkillOutputBridge`:
- reads `file://` sources directly when they are real files;
- copies other content URIs into an app cache directory with a 50 MB copy cap;
- publishes successful outputs through the existing `${applicationId}.files` FileProvider;
- maps the skill kind to the correct attachment kind.

`res/xml/file_paths.xml` exposes only the existing camera cache plus `files/skill-outputs` for skill results.

### 4. Chat + UI
`ChatController` now:
- executes write tasks in router order;
- supports same-format multiple CREATE markers by selecting the same-kind marker ordinal;
- keeps marker protocol out of the visible CREATE bubble while the model is generating;
- shows deterministic `Step x/y` results from real executor results;
- stores verified output attachments on the AI item and keeps the last verified output for suitable follow-ups.

`ChatPage` + `AttachViews` show a real output file card with `Open` and `Share`. Open/Share include read permission flags and MIME types for FileProvider URIs.

### 5. PDF verification
`PdfCreator` implements `PdfWriter`. After creation, Stage 2 verification opens the generated file through Android `PdfRenderer` and requires at least one page.

PDF editing is intentionally unsupported and returns a not-created result instead of pretending the edit happened.

## Verification performed

### Full JVM regression
**10,763 checks, 0 failures.**

This includes all previous Stage 1–Phase 4 suites plus new Stage 2 tests.

### Stage 2-specific tests
- parser: four file kinds, quoted titles, tabs, malformed/nested markers, missing terminators;
- CREATE: real DOCX, XLSX, PPTX files and PDF writer interface;
- CREATE read-back: output content reopened through the matching skill;
- same-kind multi-create: first/second DOCX markers produce distinct matching files;
- EDIT: DOCX, XLSX and PPTX edited copies leave originals unchanged;
- ChatController integration: real executor result reaches the AI item as an output attachment;
- marker protocol stays out of the final visible chat text;
- missing/wrong markers fail without creating output;
- PDF EDIT is rejected honestly;
- publisher/FileProvider failure leaves no ghost output file.

### Independent Office interoperability
A separate probe generated DOCX/XLSX/PPTX with the same production skills. The files were reopened successfully by:
- `python-docx`
- `openpyxl`
- `python-pptx`

### Router baseline
The Stage 1 seed corpus remains **320/320 = 100%**.

### Conversation diagnostic
The existing 1,300-message stress corpus remains **1242/1300 = 95.5%**, with 58 known classification misses. These are pre-existing conversation-quality findings and were not hidden or relabeled as Stage 2 failures.

## Android build limitation
The source was inspected and wired against the existing Android project APIs, but this environment does **not** contain a Gradle executable/wrapper or an Android SDK compiler. Therefore no claim is made that an APK was locally compiled or run for Stage 2.

The exact remaining verification is an Android/CI build plus a device check of:
1. create Word/Excel/PowerPoint/PDF;
2. edit a supplied Word/Excel/PowerPoint file and confirm the original is unchanged;
3. Open and Share from the AI output card;
4. PDF read-back through `PdfRenderer`;
5. a follow-up that converts/edits the last verified output.

## Stage 2 conclusion
The Stage 2 source implementation and Android wiring are present, deterministic and covered by the JVM execution suite. The only unverified portion is the Android compile/runtime environment itself; this report does not treat that as complete until CI/device validation is performed.
