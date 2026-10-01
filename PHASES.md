# Web search (Tavily) - phase plan

Goal: the saved Tavily key is used by the app on its own. The AI decides when to search, can show pictures, only read them,
give the right page link, give steps, compare - and every failure has a clear reason.
Everything below the UI is pure Java (app/src/main/java/com/neonhud/app/core/web) and is tested on a plain JVM:
    cd jvm-tests && bash run-tests.sh

## Phase 1 - part 1  - the search brain          [DONE]
- SearchPlanner: per message decides search / no search, pictures (show / read only), link (one / many), steps, compare,
  read-only ("dikhana mat"), follow-ups ("uski photo dikhao"), attachments, Settings mode (Auto / Always / Off)
- TavilyJson + MiniJson: request body + answer parsing (works with both image formats)
- KeyPool + WebSearchService: several keys, next key on limit / rejection, rest times, bad-request retry, one wider retry on empty,
  clear reason for every failure (no key, all limited, all rejected, no internet, timeout, server, empty)
- LinkPicker: the right page (official / deep for how-to / home for "official website", no social junk, no duplicates, only safe urls)
- ImagePicker, ResultCleaner (prompt-injection + markup + url removal), ContextBuilder (small context for the phone model)
- Tests: WebCoreTests (233 checks) + all old tests still green (719 checks, 0 failures)

## Phase 1 - part 2  (THIS ZIP)  - connected to the chat          [DONE]
- ChatController: per message plan -> "Searching the web..." only when a search really happens -> search -> "Reading the results..." -> reply.
  Web results go to the model as a separate block right before the user message (PromptPackage.webContext); they are never saved
  into the conversation history, and an answer built from the net is not turned into long-term memories (prices / scores go stale).
  Links + pictures appear under the finished reply; "dikhana mat" (read-only) shows none. Every failure = one clear notice after the reply.
  The coding chat has no search service: it stays fully offline.
- HttpWebApi (core/web, plain java.net): POST https://api.tavily.com/search, Bearer key, timeouts, 2 MB answer cap, HTTP status ->
  WebApi.Fail (401/403 key, 429/432/433 limit, 400/422 bad request, 408/504 timeout, other = server), key never in an error text.
- Settings > "Web search": Auto / Always / Off (read on every message, so it applies to the very next one) + a hint when no key is saved.
- Chat screen: status line, link chips (open in the browser), up to 4 thumbnails (WebImageLoader: public http(s) only, every redirect
  checked, images only, 4 MB cap, memory cache). Gemma prompt: GemmaEngine puts the web block next to the question.
- Tests: ChatWebTests (108 checks, incl. real HTTP against a local server and key failover over real status codes)
  -> all tests: 827 checks, 0 failures.
- NOT verified here: the Android-only code (ChatPage / SettingsPage / WebSearchCard / WebImageLoader / App / GemmaEngine.kt) needs the
  Android SDK, so it was written to match the existing code but not compiled in this environment - build it (GitHub Actions) and
  check on a phone: Settings switch, "Searching the web..." line, links, pictures.

## Phase 2  (THIS ZIP)  - links, pages, PDFs, web pictures          [DONE]
- Pasted link = the page is READ, not searched for (Phase 1 searched the words of the address). WebSearchService.readLinks:
  up to 3 links per message, www.x.com without https is accepted, duplicates / tracking parameters removed, every address checked
  (no localhost, private IPs, user:pass@, file:) BEFORE anything is sent or downloaded.
- Tavily Extract (WebApi.extract / TavilyJson.extractBody+parseExtract / HttpWebApi POST /extract): same key failover as search
  (next key on limit / rejection, rest times, clear reason for every failure). Cheap depth first; only pages that came back empty get ONE deeper try.
- PageReader: a long page is cleaned (markup, urls, hidden characters, prompt-injection sentences - same ResultCleaner as search),
  cut into chunks, and only the top of the page + the chunks that best match the question are kept (6000 chars for all pages together,
  gaps marked "[...]"). A link with no question = "give a short summary".
- Pictures and PDFs for the vision model: MediaReader (Fetcher -> MediaDecoder). A link to a picture, a PDF whose text Tavily could not
  give (scans), or a link with no extension that really is a PDF / picture is downloaded directly (no Tavily key and no credit needed),
  pictures shrunk to 1024 px, PDFs = first 3 pages. At most 4 images per message. "Read the picture" searches ("... photo padh ke batao")
  now really hand 2 web pictures to the vision model; nothing is shown in the chat for them.
- HttpFetcher (plain java.net): public http(s) only, the guard also checks what the name resolves to and EVERY redirect, size cap,
  content-type check, no key is ever sent.
- Honest failures: each unreadable link gets a reason (blocked, empty page, too big, not a page / file, no key, limit, no internet...);
  the model is told "COULD NOT OPEN ... do NOT guess what it says" and never pretends to have read it; the user gets one short notice.
  No search is run in place of a failed page (it could answer about something else).
- ChatController: "Opening the link..." -> "Reading the page..." -> reply. Web pictures / PDF pages go to the model as attachments of THIS reply
  only (not on the user's bubble, not stored). Page text is never saved in the history and adds no long-term memory. Coding chat: still fully offline.
- Android: WebMediaDecoder (BitmapFactory / PdfRenderer), wired in App; GemmaEngine note wording made generic. Manifest comment updated.
- Tests: WebPhase2Tests (220 checks, incl. real HTTP against a local server: extract endpoint, status mapping, size cap, redirects,
  private-address redirect, wrong content type) -> all tests: 1047 checks, 0 failures.
- NOT verified here (no network, no Android SDK):
  * Tavily's /extract request / answer shape (urls, extract_depth, format, results[].raw_content, failed_results[]) was written from the
    documentation as remembered and tested against a local fake. Phase 3's live runner should check it with your real key - especially
    whether Extract returns text for PDF links (if not, the direct PDF path above takes over automatically).
  * Android-only code: WebMediaDecoder (only type-checked against stubs), App wiring, GemmaEngine.kt. Build via GitHub Actions and try on a phone:
    paste a news / product page link, a link to a .pdf, a link to a .jpg, "taj mahal ki photo padh ke batao (search karke)".
- Known limits: a follow-up about the same page ("isme aur kya hai?") without pasting the link again is not re-read (the page is not cached -
  that belongs to Phase 4 "cache"); pages that need a login or are pure JavaScript apps may come back empty; WebImageLoader (Phase 1
  thumbnails) still only checks literal private IPs, not what a name resolves to - Phase 4 polish can move it onto HttpFetcher's guard.

### Still to do
- Phase 3: 1000-question test set + live runner + problem catalogue + usage patterns report  [DONE]
  - `phase3/questions.tsv`: exactly 1000 deterministic web-planner questions across 10 intent families.
  - `jvm-tests/src/Phase3QuestionSetTest.java`: 1000-row planner regression with exact search/feature expectations.
  - `jvm-tests/src/Phase3LiveRunner.java` + `jvm-tests/run-phase3-live.sh`: real-key Tavily runner; keys come only from `TAVILY_API_KEY` / `TAVILY_API_KEYS`, never printed or stored.
  - `phase3/PROBLEM_CATALOGUE.md`: fixed planner problems plus the remaining conversation-level weak spots.
  - `phase3/USAGE_PATTERNS.md`: corpus mix, feature coverage, and final deterministic results.
  - Final JVM regression after Phase 3 changes: 3051 checks, 0 failures.
  - Real-key Tavily traffic was not executed in this environment because no real key was supplied; the live runner was dry-run compiled and exercised.
- Phase 4: cache (incl. re-reading a page for follow-ups), credit saver, key health in Settings, final polish and release build

## Phase 4 attachment/conversation review follow-up  - DONE
- Removed the separate PDF attachment button. The single Zip entry now accepts ZIP, PDF and video files, with multi-select preserved and the existing per-message limit of 5.
- File type is determined from content first (ZIP/PDF/MP4/MOV/WebM), with MIME/extension fallback for video providers that hide the video signature.
- Added VIDEO attachment kind, thumbnail/first-frame reading, metadata note, and video attachment card.
- Sent and pending image attachments can be tapped for an in-app full-screen preview; video attachments can be opened with the phone's video handler.
- Attachment thumbnail cache now keys by type + requested size + URI, preventing a tiny chat thumbnail from being reused as the full-screen preview.
- Conversation stress review remains explicit: the existing 1,300-message suite found 66 classification misses (94.9% overall). These are recorded as remaining conversation-quality issues rather than being hidden behind a passing test exit code.

## Phase 4 — Conversation + 1,500-command verification

Completed:
- Added `phase4/commands1500.tsv` with 1,500 varied commands across offline, fresh, explicit search, images, image reading, links, procedures, comparisons, URLs, mixed tasks, follow-ups, repeats, cross-language prompts and attachments.
- Added `phase4/PHASE4_REPORT.md` with the test methodology and measured results.
- Added `jvm-tests/src/Phase4Command1500Test.java` and wired it into `AllTests`.
- Fixed explicit `online ... dhoondho` search routing.
- Fixed image-reading vs image-display separation: `photo mat dikhana` no longer blocks image reading.
- Improved English/Hinglish conversation normalization and entity-aware repeat matching.
- Preserved `pehle wale` return-topic detection.
- Full JVM suite: 10,565 checks, 0 failures.


## Phase 4 CI compile fix (2026-10-01)
- Fixed `ChatPage.java` image preview dialog compile error: static method now uses `LinearLayout.VERTICAL` instead of unresolved bare `VERTICAL`.
- Updated GitHub Actions Gradle setup from 8.13 to 8.14.4 to remove the Gradle deprecation warning for the Kotlin Gradle Plugin.
- JVM regression after fix: 10,565 checks, 0 failures.
- Android CI build should be rerun on GitHub Actions; local environment has no Android SDK/toolchain.

## Phase 4 — Office Skills extension [DONE]
- Researched the current Anthropic `docx`, `xlsx`, and `pptx` Skills documentation and implemented an Android-friendly local equivalent as dedicated skills.
- Added pure-Java OOXML package readers/creators/editors for DOCX, XLSX and PPTX; no new third-party Android office dependency was added.
- DOCX: paragraph/table-text reading, styled creation, targeted text replacement.
- XLSX: sheet/cell/formula-aware reading, CSV/TSV reading, spreadsheet creation, targeted cell editing; created formula cells are marked for recalculation on open.
- PPTX: slide-text reading, simple 16:9 presentation creation, targeted text replacement.
- Added OOXML-internal file classification so DOCX/XLSX/PPTX are distinguished from normal project ZIPs; added CSV/TSV fallback for the spreadsheet skill.
- Wired Office attachment loading into the existing AttachmentLoader path and exposed the three skills from the process-wide App for Lab/export use. Stage 2 later connected the same real skills to the chat execution path without redesigning the Office chip UI.
- Added per-skill `SKILL.md` references under `phase4/skills/` following Anthropic's metadata/instruction pattern, without copying their implementation.
- Regression: **10,597 checks, 0 failures**.
- External interoperability: generated DOCX/XLSX/PPTX packages passed ZIP/XML validation and opened successfully with independent `python-docx`, `openpyxl`, and `python-pptx` readers; external Python-generated DOCX/XLSX/PPTX were also read successfully by the new skills.
- Android APK build was not executed here because this environment still lacks the Android SDK/Gradle toolchain; that limitation is unchanged.

## Skills wiring (conversation <-> skills) - PLAN written 2026-10-01
Full plan, verified gaps and the 5,000-question test design: `SKILLS_WIRING_PLAN.md`.
Stage 1 router + honesty, Stage 2 execution + file card, Stage 3 read budget, Stage 4 5,000-question test.

### Stage 1 - router + honesty [DONE 2026-10-01]
SkillRouter / SkillPlan / SkillTask / RouterContext / SkillPrompt, `PromptPackage.skillContext`, clarify without calling the model, honest "no file exists" note,
`REPLY_HINT` no longer lets the model claim a file. Seed corpus 320 rows (`phase5/router_corpus.tsv`), 320/320 after fixes (first run 92.5%).
Full JVM suite: 10,714 checks, 0 failures. Details and limits: `phase5/STAGE1_REPORT.md`.
Next: Stage 2 - execution (marker format + parser, executors, FileProvider output, file card, copy-on-edit, read-back verification).

## Stage 2 — Skills execution + real file outputs  [DONE (JVM/source verified; Android APK build pending)]

Completed on 2026-10-01:
- Added a strict `SKILL_FILE` marker protocol + parser for DOCX, PDF, XLSX and PPTX CREATE tasks.
- Added deterministic `SkillExecution`: CREATE parses markers and calls the existing real Office skills / PDF writer; EDIT writes a new `-edited` copy and never overwrites the source.
- Every successful CREATE/EDIT requires a real output file plus read-back verification. PDF uses an Android `PdfRenderer` verification implementation.
- Added Android `SkillOutputBridge`: content URIs are materialized to a capped temporary input, outputs are published through the existing `FileProvider`, and temporary inputs are removed.
- Added AI output file cards with `Open` and `Share`, including MIME types and read-grant flags for FileProvider URIs.
- ChatController now executes ordered write tasks, hides the model's marker protocol from the visible chat bubble, reports real `Step x/y` results, and keeps the last verified output available for follow-up conversion/edit flows.
- Same-format multi-create requests consume same-kind markers in order, so the first artifact is not duplicated.
- Failure paths remove partially-created outputs, including FileProvider/publisher failure; PDF editing remains explicitly unsupported.
- Stage 2 JVM suite added parser, four CREATE paths, three EDIT-copy paths, ChatController integration, same-kind markers, unsupported PDF edit and failure-cleanup checks.

Verification:
- Full JVM regression: **10,763 checks, 0 failures**.
- Router corpus: **320/320 (100%)**.
- Independent Python interoperability check: generated DOCX/XLSX/PPTX were reopened successfully by `python-docx`, `openpyxl` and `python-pptx`.
- Source sanity checks passed for the Stage 2 production files and FileProvider XML.
- **Android APK build was not executed in this environment:** no Gradle executable/wrapper and no Android SDK compiler were available. The Android Stage 2 wiring is therefore source-inspected but not claimed as locally compiled/runtime-tested.
- Existing conversation stress suite remains at **1242/1300 (95.5%)**; its 58 classification misses are pre-existing conversation-quality findings, and the suite still exits PASS because this corpus is diagnostic rather than a Stage 2 gate.

Report: `phase5/STAGE2_REPORT.md`.

## Stage 3 — Read budget + selections + attachment cache  [DONE (JVM/source verified; Android APK build pending)]

Completed on 2026-10-01:
- Added a strict attachment read budget: 24,000 total text characters, 8,000 per file, 4 images total and 4 images per file. Clipping is explicit and remains inside the cap even when only a few characters remain.
- Added numbered/labelled vision inputs (`IMAGE 1`, `IMAGE 2`, etc.) with file name plus page/frame/image label, so multiple pictures/PDF pages are distinguishable by the local vision model.
- Added explicit read selections: `page 3` / page ranges for PDFs, `slide 2` / slide ranges for PPTX, and exact sheet names for XLSX. Invalid/missing selections fail honestly instead of silently switching to another file.
- Added an in-process LRU attachment read cache keyed by source + selection. Router follow-ups such as `page 4 padho` or `isko summarize karo` can reuse the saved source/read payload without forcing a re-attachment or unnecessary reread.
- Added dedicated attachment icons for Audio, Word, Excel and PowerPoint; existing Camera/Image/PDF/ZIP/Video and remove/sent icons remain unchanged.
- Added Stage 3 regression tests, including ChatController cache wiring, read-budget edge cases, selection parsing, real XLSX/PPTX selection, and image-label propagation.

Verification:
- Current full JVM regression: **10,800 checks, 0 failures**.
- Stage 3 source sanity suite: **PASS**.
- Stage 1 uploaded-source baseline (`neon-hud-skills-stage1.zip`): **10,714 checks, 0 failures**.
- Router seed corpus: **320/320 (100%)**.
- Existing Phase 3/Phase 4 deterministic corpora remain green, and the known 1,300-message conversation diagnostic remains **1242/1300 (95.5%)**; those 58 misses were already present and are not relabelled as Stage 3 failures.
- Initial Stage 1 → Stage 3 audit: **0 original files removed**. Stage 3 changes over the Stage 2 working source are limited to the attachment reader/selection, budget/cache, vision labelling, Office selection methods, router/task wiring, attachment icons, and corresponding tests.

Android build limitation:
- This environment still has no usable Android SDK/Gradle compiler, so Android code was source-inspected and checked by targeted sanity assertions, but **no APK build or device runtime test is claimed**.

Stage 4 remains separate: the planned 5,000-question corpus and its deeper create/edit/multitask validation are intentionally not included in this Stage 3 ZIP.

## Stage 4 — 5,000-question regression + attachment/send race hardening [COMPLETE 2026-10-01]

Stage 4 completion addendum (the earlier Stage 4 planning line above is historical):
- Added `phase5/stage4_5000.tsv`: exact 5,000 unique rows with the planned 2,500 read / 1,000 create / 600 edit / 500 multi-task / 400 negative split and eight wording styles.
- Layer 1: actual `SkillRouter` = **5,000/5,000 (100%)** exact.
- Layer 2: actual `SkillExecution` on every routed write task = **2,500/2,500 successful**, with independent Python validation for DOCX/XLSX/PPTX/PDF outputs.
- Added Stage 4 source audit and attachment/send race protection: `AttachmentSendGate` blocks Send and `+` while async attachment validation is active; validated attachments are committed before the gate releases.
- Added a deterministic 300-case phone sample, 32 dedicated attachment race cases, and a Stage 4 problem catalogue. Device run remains pending because this environment has no Android SDK/Gradle/adb.
- Added the Stage 4 JVM regression as a CI gate before the Android APK build.
