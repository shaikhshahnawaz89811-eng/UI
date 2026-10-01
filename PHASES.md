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
