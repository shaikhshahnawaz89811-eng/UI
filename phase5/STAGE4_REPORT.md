# Stage 4 Report — 5,000-Question Regression + Attachment/Send Race Hardening

Date: 2026-10-01
Source: working Stage 3 project (`neon-hud-skills-stage3-final` source)

## Scope

Stage 4 implements and verifies the planned 5,000-question skills regression plus deeper real-file execution and the attachment/send race requested during this phase.

The 5,000 rows are deterministic, unique and divided as follows:

- Read: 2,500 (image 350, PDF 350, audio 300, video 300, ZIP 300, DOCX 300, XLSX/CSV 300, PPTX 300)
- Create: 1,000 (PDF/DOCX/XLSX/PPTX, 250 each)
- Edit: 600 (DOCX/XLSX/PPTX, 200 each)
- Multi-task: 500
- Negative/failure: 400

Every row carries expected routing/output information and explicit `NO_*` must-not constraints. Eight wording styles are represented: Hinglish, English, spelling variants, very short, long, reversed order, no punctuation, and follow-up wording.

## Layer 1 — Router

The complete corpus was run through the actual deterministic `SkillRouter` with attachment descriptors.

**Result: 5,000 / 5,000 = 100.00% exact routing.**

All 17 blocks passed exactly, all eight styles were represented, IDs and commands were unique, and all must-not columns were well formed.

## Layer 2 — Real skill execution

Every routed CREATE/EDIT write task produced by the 5,000 rows was executed through the actual JVM `SkillExecution` path. CREATE uses a controlled fake model marker response; DOCX/XLSX/PPTX use the real Office skills; PDF uses the existing `PdfWriter` interface implementation used by tests. EDIT always works on a copy and verifies the original source remains unchanged.

Latest deep run:

- Write tasks: 2,500
- CREATE tasks: 1,850
- EDIT tasks: 650
- Successful write tasks: 2,500
- Success rate: 100%

Generated artifacts were independently reopened/validated with:

- `python-docx`: 700 DOCX outputs
- `openpyxl`: 600 XLSX outputs
- `python-pptx`: 650 PPTX outputs
- PDF header validation: 550 PDF outputs

**External artifact validation: PASS.**

## Attachment → typing → send race

The UI path now has one explicit `AttachmentSendGate`. While asynchronous attachment validation/materialization is active:

- Send stays disabled even when text has already been typed.
- The attachment picker stays disabled.
- Completion commits validated attachments first.
- Only after that does the validation gate release Send.
- Multiple overlapping validation calls are counted safely.
- A failed/cancelled attachment does not leave a ghost attachment in the next message.

Dedicated JVM gate tests and Android source-order sanity checks pass. `ATTACHMENT_RACE_CASES.tsv` contains 32 additional deterministic race scenarios for the phone/device run.

## Layer 3 — 300-case phone sample

A deterministic `phase5/phone_sample300.tsv` has been prepared across read/create/edit/multi/negative blocks and all wording styles, with mandatory attachment/send boundary cases documented in `phase5/PHONE_SAMPLE300.md`.

**Device status: PENDING.** This environment has no Android SDK/compiler and no `adb`, so no device pass is falsely reported.

## Full regression

The complete current JVM regression, including previous Stage 1–3 suites, Phase 3/4 suites, the new 5,000-row router corpus, attachment/send gate, and 1,000 assistant regression, is **20,886 checks / 0 failures** via `jvm-tests/run-tests.sh`. The preserved Stage 1 source baseline was re-run at **10,714 checks / 0 failures**. The prior Stage 3 baseline was **10,800 checks / 0 failures**.

The known conversation stress diagnostic remains 1242/1300 (95.5%) and is retained as a diagnostic corpus; its 58 pre-existing misses are not hidden.

## Source safety

Stage 4 did not intentionally delete any existing project source file. Stage 3 files remain present. A final SHA-256 filename/content comparison against the Stage 3 delivery ZIP is included in `phase5/STAGE4_SOURCE_AUDIT.txt`: **0 existing files removed**; 15 Stage 4 files added/changed as documented.

## Android validation limitation

No local APK compile or runtime result is claimed because the container does not have the Android SDK, Gradle executable/toolchain, or `adb`. The GitHub Actions workflow remains the available Android build path and now includes the JVM regression gate before the APK build.

## Final audit addendum — 2026-10-01

A second end-to-end wiring pass was run after the original Stage 4 report. It found and fixed three correctness/documentation issues:

1. **Multiple-attachment READ targeting:** explicit ordinal targets (`first/second/third/last`, Hindi equivalents) and unambiguous filenames now select only the intended attachment. Ordinary `isko/these/all` requests continue to read the current whole attachment set for backwards compatibility; ambiguous cached targets request clarification.
2. **Files picker MIME filtering:** the single Files entry now uses `*/*` and relies on post-selection content sniffing, matching the actual AttachmentReader classifier instead of trusting provider-reported MIME types.
3. **PDF skill metadata:** the registry descriptor was stale and claimed the chat export path was not wired. It now reflects the real ChatController -> SkillExecution -> PdfCreator path.

Final local gates after these fixes:
- Full JVM regression: **20,886 / 20,886 checks passed**.
- Stage 4 deep execution: **2,500 / 2,500 write tasks successful**.
- Independent artifact validation: **PASS**.
- Stage 4 source/race sanity: **PASS**.
- CI runs the full JVM regression, Stage 4 source/race sanity, and deep write/artifact validation before attempting `assembleDebug`.

The Android APK/device gate is still environment-dependent: this container has no Android SDK/compiler and no `adb`, so no local APK or device pass is claimed. GitHub Actions now runs both `run-tests.sh` and `check-stage4-source.sh` before `assembleDebug`.


## Post-video final wiring hardening — 2026-10-01

The recorded-device failure where a CREATE request stopped at `NO_SKILL_FILE` was traced to the real model not following the file-marker protocol; model loading itself was not the failing component. The final working source now:

- retries one CREATE turn with a format-only `SKILL RECOVERY` contract when the first model reply has no complete required marker artifacts;
- accepts an accidental markdown fence around marker lines without weakening the artifact validation rules;
- exposes a visible Gemma 4 E2B / Qwen2.5-Coder 1.5B model switch and blocks switching while the active reply is generating;
- enables the same deterministic conversation-driven document skills, attachment reader and verified file executor in the coder chat;
- serializes shared file execution so two chats cannot race the same output directory;
- keeps the original honest failure path when no verified executor is connected.

Latest local gates: **20,886 / 20,886 JVM checks passed**, **2,500 / 2,500 Stage 4 deep write tasks successful**, and Stage 4 source/race sanity **PASS**. Android APK/device smoke validation remains dependent on an Android SDK/Gradle/adb environment and is not claimed from this container.
