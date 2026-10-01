# Skills wiring — Stage 3 report
Date: 2026-10-01

## Scope
Stage 3 from `SKILLS_WIRING_PLAN.md` is the attachment-read control layer: total/per-file read budget, numbered vision images, page/slide/sheet selection, read cache for follow-ups, and correct attachment icons. Stage 1 routing and Stage 2 real file execution are preserved.

## Implemented

### 1. Read budget
- `ReadBudget` caps attachment text at **24,000 characters total** and **8,000 per file**.
- Vision payload is capped at **4 images total** and **4 images per file**.
- Clipping is explicit (`[content clipped by read budget]`) and the final visible text stays within the configured cap even when the remaining budget is smaller than the notice.
- The budget is applied in `ChatController` after real readers and before `PromptPackage.withAttachments(...)`, so Stage 2 file execution and model output handling are not changed.

### 2. Numbered vision images
- `Attachment` now carries `imageLabels`.
- PDF pages are labelled `page N`; video frames are labelled `frame N`; other images default to `image N`.
- `GemmaEngine` sends explicit markers such as `[IMAGE 1 | file=... | page 3]` immediately before each image.
- This removes the old ambiguity where several images were all sent without stable identifiers.

### 3. Page / slide / sheet selection
- `ReadSelection` is attached to READ tasks.
- Router accepts single/range syntax for PDF pages and PPTX slides, plus exact XLSX sheet names.
- Android `AttachmentReader` uses the selection for PDF rendering.
- `OfficeAttachmentReader` forwards sheet/slide selections to `XlsxSkill` / `PptxSkill`.
- Invalid PDF/PPTX selections and missing XLSX sheets fail with explicit errors rather than guessing.
- An Excel cell-edit form like `sheet me C3 me 250 likho` is not misinterpreted as a sheet named `me C3`. A real named sheet form such as `sheet Data me C3 me 250 likho` still preserves the Stage 2 edit target `Data`.

### 4. File cache for follow-ups
- `AttachmentReadCache` is an in-process bounded LRU cache (12 loaded selection entries).
- Cache keys use source identity + read selection, so `page 3` and `page 4` are distinct.
- Router can produce `Source.CACHE` tasks for read-only follow-ups when the original attachment is no longer in the current message.
- `ChatController` first checks the exact selection cache, then the latest read for the same source, then falls back to the real reader.
- The cache stores payloads only for the current process; it does not replace the source file or change history storage.

### 5. Attachment icons
`AttachViews` now maps:
- AUDIO → Audio icon
- DOCX → Word icon
- XLSX → Excel icon
- PPTX → PowerPoint icon
Existing Camera/Image/PDF/ZIP/Video/CLOSE/TICKS paths remain intact.

## Regression / validation

### Stage 3 + all prior JVM suites
**10,800 checks, 0 failures.**

This includes:
- Stage 1 router/honesty tests
- Stage 2 marker/create/edit/copy-on-edit/cleanup tests
- Stage 3 budget/selection/cache/ChatController tests
- Office package tests
- Phase 3 1,000-question web planner corpus
- Phase 4 1,500-command web routing corpus
- 1,000 mixed assistant-contract messages
- 1,300-message conversation stress diagnostic

### Stage 3 specific tests
- strict total/per-file text budgets, including tiny remaining-budget edge case
- total/per-file image count limits
- PDF/video image-label propagation
- `page 3`, page ranges, `slide 2`, slide ranges, exact sheet names
- Excel cell-edit false selection guard
- cached read routing, ambiguous saved-file clarification, exact cache hit and latest-read reuse
- real XLSX selected-sheet read + missing-sheet failure
- real PPTX selected-slide read
- ChatController first load / new-selection load / cached follow-up without reload

### Original Stage 1 ZIP
The uploaded Stage 1 source was extracted separately and its existing JVM suite was run independently: **10,714/10,714 PASS**.

### Source lineage audit
Initial Stage 1 source → final Stage 3: **0 original files removed**. Stage 3 diff over the Stage 2 working source was restricted to the attachment read/budget/cache/selection paths, vision labelling, Office selection forwarding, attachment icon mapping, router/task cache selection, and Stage 3 tests.

### Android verification limitation
The environment has no usable Android SDK/Gradle toolchain, so the APK was **not** locally compiled or run. A targeted Stage 3 Android/source sanity script was added and passes all required wiring checks. This report does not promote source inspection into an APK build claim.

## Not included
Stage 4's planned 5,000-question corpus and phone-device sample test are intentionally not implemented in this ZIP.
