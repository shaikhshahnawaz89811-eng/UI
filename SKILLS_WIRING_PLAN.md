# Skills wiring plan (conversation <-> skills)

Status: FINAL AUDIT COMPLETE (2026-10-01). Stage 1–4 source/JVM wiring is implemented and regression-tested; Android APK/device validation remains the only external gate.
Goal: a user can say anything, in any wording, hand over a file or ask for a new one, or mix 2-3 things in one
message - and the right skill runs, in order, and the app never claims something it did not do.

## 1. Historical baseline before Stage 1 (verified)

| Piece | State |
|---|---|
| Readers: image, PDF (pages as images), video (6 frames), audio (metadata only), ZIP, DOCX, XLSX/CSV/TSV, PPTX | Work, via `AttachmentReader` -> `loadAll` in ChatController. Not driven by `SkillPlanner`. |
| Creators / editors: `PdfCreator`, `DocxSkill`, `XlsxSkill`, `PptxSkill` (create + replaceText / setCell) | Built before wiring; at the baseline they were not called from chat. Stage 2 later connected the real execution path. |
| `SkillPlanner.forTurn(...)` | Called in `ChatController.runTurn` and the result is thrown away. |
| Model prompt | Never told which skills exist. `REPLY_HINT` says "banao = actually produce it". |
| Output file | No FileProvider path, no file card in `ChatController.Item`, no Open/Share. |

Measured on the real planner (40 phrasings, no attachments): create/edit phrases 26, planner caught 4.
Missed: "word file banao", "excel sheet banao", "slides banao", "presentation bana do", "docx me de do",
"isko excel me daal do", "make me a pdf", "create a word doc", every edit phrase ("Ravi ki jagah Raj", "B2 me 500 likh do").
False positives: "music theory", "song ke lyrics ka matlab", "screen recording kaise karte hain", "ogg vorbis kya hai"
all selected the audio reader with no audio file.

## 2. Target flow (one message)

```
user text + attachments
  -> SkillRouter  (pure Java, deterministic)         -> SkillPlan: ordered tasks | clarify | unsupported
  -> clarify?  yes -> app answers with ONE short question, model not called
  -> run tasks in order, status line "Step 1/3 ..."
        READ   : existing loaders (budgeted)
        CREATE : model writes content in a fixed marked format -> parser -> Docx/Xlsx/Pptx/Pdf writer -> file
        EDIT   : params from router (old/new or sheet/cell/value) -> skill writes a COPY
  -> model reply built from real results ("File ready: report.pdf") + file card (Open / Share)
  -> history keeps names, never file contents
```

## 3. Router rules

1. Normalise: lowercase, spelling map (banaao/bnao/banado -> banao, excle/exal -> excel, power point -> powerpoint, pdff -> pdf).
2. Split into segments: always at phir / fir / then / uske baad; at aur / and / comma ONLY when both sides carry a verb
   ("word aur excel dono banao" stays one segment with two formats).
3. Per segment: action = EDIT > CREATE > READ.
   - CREATE needs a format noun AND a verb (banao, bana do, de do, nikal do, convert, save, daal do, chahiye, make, create, export).
   - Negation within 3 tokens ("pdf mat banao", "don't make") cancels the verb.
   - "X ko Y me convert": target = the format after me/mein/to/into/as.
   - Bare "document banao" asks which format (PDF or Word). It never guesses.
4. Source of content: attached file / previous step / last reply / whole chat / typed text / topic ("AI par ppt" -> model writes it).
5. Edit parameters: quoted pairs, "X ki jagah Y", "X ko Y kar do", replace X with Y, change X to Y, "B2 me 500 likh do", "set B2 to 500", sheet name.
   Missing parameter or missing file -> clarify, never guess. Original file is never overwritten.
6. Guards: "password", "word count", "ek word", "bed sheet", "slide karna" are not file requests.
   "kaise / how / python / java / api" without an attachment is a how-to question, not a skill call.
7. Audio / video words only count when an audio / video file is attached. Transcript requests -> honest "not available".
8. Limit 3 explicit tasks per message; the rest is reported as not done.

## 4. Honesty rules (hard)

- Never say "file ready / saved / created" unless the file exists on disk and was re-opened by the same skill reader.
- Transcript: not available until a real speech-to-text backend exists. `.doc / .xls / .ppt` legacy: not supported.
- Edit of PDF: not supported (say so). Created decks / sheets are simple (no charts, animations, notes).
- Current honesty rule: never claim a CREATE/EDIT file exists unless the real execution path produced it, the file exists on disk, and the same skill verified it by read-back.

## 5. Weaknesses found -> what to improve

1. Model is never told about skills, and is told to "produce" things -> says "PDF bana diya" with no file (worst lie).
2. No FileProvider path for outputs and no file card -> a created file cannot be opened or shared.
3. Planner phrase lists are adjacency-only; no edit intent; audio false positives; no Hindi words (gaana, awaaz).
4. Office readers allow 200k-240k chars and `loadAll` has no total cap -> too big for a 2B phone model.
5. `GemmaEngine` puts every image first; notes only say "shown above" -> with 3 images the model cannot tell which is which. Number them.
6. Audio / video speech not understood. Omu-style projects feed raw audio to Gemma 4 E2B but need an audio-capable .litertlm and an audio backend; `GemmaEngine` only configures a vision backend. First check whether the model file has audio.
7. Chips for Audio / Word / Excel / PPT still use the picture icon.
8. `PdfCreator` is plain text only (no headings, lists, tables, Hindi script fonts untested).
9. A pasted second file of the same kind -> "which one?" is not handled.
10. Android-only code was never compiled in earlier environments; CI is the only proof.
11. Old test corpora are narrow: the 1,500-command file has ~10 unique lines per category (follow-up: 2) and zero create / edit commands.
12. Conversation stress: same-question 94%, topic recall 99.7% - known misses stay listed, not hidden.

## 6. Build stages (each stage = its own zip)

- **Stage 1 [DONE, see phase5/STAGE1_REPORT.md] - router + honesty (pure Java, JVM-tested):** SkillRouter / SkillPlan / SkillTask, clarify without calling the model,
  capability note in the prompt (`PromptPackage.skillContext`), no false "file created" claims, 300-row seed corpus. Legacy `SkillPlanner.forTurn` kept unchanged.
- **Stage 2 [DONE 2026-10-01; Android APK build pending] - execution:** content marker format + parser, executor for DOCX / XLSX / PPTX / PDF (PDF through an interface, Android impl),
  FileProvider outputs path, file card Open / Share, step status lines, copy-on-edit, read-back verification.
  Verification: `phase5/STAGE2_REPORT.md`; full JVM suite 10,763 checks / 0 failures. Android source wiring is present but was not locally compiled because this environment has no Android SDK/Gradle toolchain.
- **Stage 3 [DONE 2026-10-01] - read budget + selections + attachment cache:** total attachment-read cap (24k chars), per-file cap (8k chars), numbered/labelled vision images, PDF page and PPTX slide ranges, exact XLSX sheet selection, in-process cached reads for follow-ups, and dedicated Audio / Word / Excel / PowerPoint attachment icons.
  Verification: full JVM suite 10,800/10,800; router seed 320/320; Stage 3-specific real Office selection + ChatController cache tests; Android-only source sanity passed. Android APK build remains pending because the environment has no Android SDK/Gradle toolchain.
- **Stage 4 - 5,000 questions:** corpus, 3 layers (router on all 5,000; fake model + real skills + file validation; 300-sample on a phone), problem catalogue.

## 7. The 5,000-question test

| Block | Count |
|---|---|
| Read: image 350, pdf 350, audio 300, video 300, zip 300, docx 300, xlsx/csv 300, pptx 300 | 2,500 |
| Create: pdf, docx, xlsx, pptx (250 each) | 1,000 |
| Edit: docx, xlsx, pptx (200 each) | 600 |
| Multi-task (2-3 jobs in one message, mixed files) | 500 |
| Negative + failure (password banao, music theory, .doc, transcript, corrupt / locked / huge file, "kaise" how-to) | 400 |

Eight styles per block: Hinglish, English, wrong spelling, very short, very long, reversed word order, no punctuation (voice typed), follow-up ("isko", "pehle wala").
Each row: expected skills in order, expected output (file type / no file / clarifying question), must-not (false claim, web search, overwrite original). All rows unique (dedupe-checked).
Gates: false "file created" = 0; every created file re-opens with python-docx / openpyxl / python-pptx; router >= 98%; per-skill and per-style failure tables in the report.

## Stage 4 completion addendum — 2026-10-01

The Stage 4 plan above is now implemented/verified in the working source. The 5,000-row corpus, real write-task execution, independent Office artifact validation, 300-case phone sample, problem catalogue, and attachment/send race guard are present. See `phase5/STAGE4_REPORT.md` and `phase5/STAGE4_PROBLEM_CATALOGUE.md`.

## Final wiring audit — 2026-10-01

The Stage 4 delivery was re-audited end-to-end after the original report was written. The following correctness fixes are now included in the final source:

- **Explicit attachment target:** with multiple files attached, `first/pehli`, `second/doosri`, `third/teesri`, `last/aakhri`, or an unambiguous filename now narrows READ to that file. Ordinary pronouns such as `isko/these/sab` keep their existing whole-set behavior. Ambiguous saved-file follow-ups ask one clarification instead of guessing.
- **Files picker:** the single `Files` attachment entry accepts `*/*` and lets the existing content sniffing/classification layer decide ZIP/PDF/OOXML/audio/video support. This avoids provider MIME declarations hiding valid supported files; unsupported content is still rejected after selection.
- **Skill metadata:** the PDF Creator descriptor no longer claims chat is unwired; actual ChatController -> SkillExecution -> PdfCreator wiring is reflected accurately.
- **CI gates:** GitHub Actions runs the complete JVM regression, Stage 4 Android/source sanity, and deep write/artifact validation (with Python Office validators) before attempting `assembleDebug`.

Final local verification after these fixes and the recorded-device hardening pass: **20,886 checks / 0 failures**, Stage 4 deep execution **2,500/2,500 successful writes**, external DOCX/XLSX/PPTX/PDF validation **PASS**, and Stage 4 source sanity **PASS**.

The only verification not executable in this container is the Android APK/device smoke test because the Android SDK/compiler and `adb` are not installed. CI contains the build path and all local automated gates, so this remains an environment/device validation item rather than an unimplemented wiring item.


## 2026-10-01 post-video completion pass

The live-device recording showed that the first model response could omit the CREATE marker even while the model was loaded. This is handled in the final source by a single recovery generation with a strict marker-only contract; the app still refuses to report a file until the real executor creates and verifies it.

The final wiring also makes the existing model switch usable from the chat UI, keeps switching blocked during generation, enables the shared skills/attachment loader/executor for Qwen coder chat, serializes shared writes, and keeps conversation-based routing/clarification in both chats.

Current checks: **20,886/20,886 JVM checks passed**; **2,500/2,500 deep Stage 4 writes passed**; Stage 4 source/race checks passed. Android build/device smoke test remains an external CI/device validation step because this container has no Android SDK/Gradle/adb.
