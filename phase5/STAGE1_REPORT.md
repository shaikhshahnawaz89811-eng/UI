# Skills wiring - Stage 1 report (router + honesty)

Scope: SKILLS_WIRING_PLAN.md section 6, Stage 1. Pure Java, JVM-tested. No file is created or edited yet (that is Stage 2).

## What was added
| File | Purpose |
|---|---|
| `core/skill/SkillRouter.java` | message (text + attachments + minimal context) -> `SkillPlan`. Rules from plan section 3: spelling map, segments (phir / fir / then / uske baad; aur / and / comma only when both sides have a verb), EDIT > CREATE > READ, negation, convert target, guards, 3-job limit |
| `core/skill/SkillPlan.java`, `SkillTask.java`, `RouterContext.java` | plan = ordered tasks OR one clarifying question, plus `unsupported` and `notDone` lists |
| `core/skill/SkillPrompt.java` | short capability note for the model; `EXECUTION_CONNECTED = false` until Stage 2 |
| `PromptPackage.skillContext` | note for this message only, never stored; also in `flatten()` and in `GemmaEngine.kt` |
| `ChatController` | calls the router; clarify -> the app answers itself (no model call, no web search); a file request skips web search; coder chat has skills switched off |
| `GemmaEngine.REPLY_HINT` | added: never claim a file was created / saved / edited unless a SKILLS NOTE says it exists |
| `SkillPlanner.forTurn` | left unchanged (old tests still use it) |

## Behaviour now
- Readers follow the attachments, never the words: "music theory", "ogg vorbis kya hai", "screen recording kaise karte hain" select nothing without a file.
- "word file banao", "excel sheet banao", "slides banao", "docx me de do", "isko excel me daal do", "make me a pdf", "pdf chahiye" -> CREATE.
- Edits are parsed: "Ravi ki jagah Raj", "Raj likh do Ravi ki jagah", "replace X with Y", "X ko Y kar do", "B2 me 500 likh do", "set C3 to 250", "put 5 in E10", several cells in one message, sheet name, "second file" / file name to choose between two files.
- Missing file / format / topic / parameter -> ONE short question, model not called. Bare "document / report / resume banao" asks PDF or Word.
- PDF edit, transcript, legacy .doc/.xls/.ppt, CSV/HTML creation -> honest "not possible" note.
- More than 3 jobs in one message -> first 3 planned, the rest listed as not done.
- Model is told "NO file exists" for any create/edit request until Stage 2 is connected.

## Measured (JVM, `bash jvm-tests/run-tests.sh`)
- Whole suite: **10,714 checks, 0 failures** (10,597 before this stage).
- Seed corpus `phase5/router_corpus.tsv`: **320 rows, 320 correct** (gate 98%). Generator: `phase5/gen_router_corpus.py`.
- How that number was reached (not hidden): first run 271/293 = 92.5%. Bugs found and fixed: `docx` treated like bare "word"; "X ko Y me convert" parsed as a text replace; "C3" wrongly excluded as a cell; "word excel ppt pdf" lost "word"; legacy `.doc` produced a question instead of "not supported"; `put V in B2` and `likhdo` unknown. I then added 27 rows written after tuning: 4 failed first ("chat ko word me save kar do", "pdf ko word kar do", "is ppt ka pdf bana do", "sheet ka pdf nikal do"), all fixed. Those 4 are the honest estimate of the miss rate on unseen phrasing (about 15%), so the 98% gate on this seed is NOT a promise for real traffic. Stage 4 (5,000 rows, written independently of the router) is the real test.

## Known limits / not done
- Nothing creates or edits a file yet. Stage 2.
- Android-only code touched (`GemmaEngine.kt`, `App.java`) is not compiled here (no Android SDK); the change is two small additions. CI must confirm.
- Router vocabulary is Hinglish/English; Devanagari script and other languages are not handled.
- "python / java / code / api / formula / kaise / how" without an attachment is treated as a how-to question, so "python se excel banao" does nothing (by plan rule 6).
- Topic extraction is word-removal based; long sentences give a rough topic string. Fine for Stage 2 (the model gets the whole message anyway).
- Edit of Excel text ("excel me Ravi ki jagah Raj") asks for a cell: `XlsxSkill` only has setCell.
- `lastOutput` (file made earlier) is in `RouterContext` but always null until Stage 2.
