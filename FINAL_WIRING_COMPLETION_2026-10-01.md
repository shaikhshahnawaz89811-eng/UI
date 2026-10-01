# Final Wiring Completion — 2026-10-01

## What was completed

- Replaced the manual Gemma 4 E2B / Qwen2.5-Coder 1.5B switch with an automatic runtime handoff. The chat pill is now display-only and follows the active model.
- Kept Gemma and Qwen as separate module state machines, engines, chats, memories and model files. A shared runtime coordinator now owns which engine may be resident.
- Enabled the existing conversation-driven `SkillRouter` in the coder chat and connected the shared attachment reader and verified file executor.
- Extended the Qwen coder prompt to receive the current skill contract, bounded extracted attachment text, and prior conversation while preserving the current message.
- Serialized shared `SkillExecution` writes so Gemma and Qwen cannot race the same output directory.
- Fixed the real-model CREATE failure seen in the recorded device run: when a CREATE reply does not contain every required executable artifact marker, the controller performs one format-only recovery generation. A file is still reported as ready only after strict parsing, on-disk creation, and same-skill read-back verification.
- Tightened the CREATE prompt so each requested file type gets an exact marker example and every marker must be on its own line. The Gemma execution hint repeats that contract.
- Made the marker parser tolerant of an accidental markdown fence around marker lines without weakening type/content validation.

## Conversation-driven skill behavior

The current message, attached files, and relevant cached/previous output context are routed automatically. The user does not need a separate skill button for DOCX, XLSX, PPTX, PDF, image, PDF, audio, video, ZIP/project and Office read/edit flows that are already supported. Clarification is used when the format/file/selection is genuinely ambiguous; unsupported capabilities remain explicitly reported rather than guessed.

## Verification

- JVM regression: **20,913 / 20,913 checks passed**.
- Stage 4 exact router corpus: **5,000 / 5,000 exact**.
- Stage 4 deep execution: **2,500 / 2,500 write tasks successful**.
- Independent Office/PDF artifact validation: **PASS**.
- Stage 4 source/race sanity: **PASS**.

## Device-build limitation

The current container does not have an Android SDK, Gradle executable/wrapper, or `adb`, so an APK compile/install/device smoke test cannot be honestly claimed from this environment. The project CI workflow remains the available Android build path.

## Model runtime redesign — 2026-10-01

- Replaced independent runtime loading semantics with a shared `ModelRuntimeCoordinator` that permits at most one resident model.
- Both model files remain importable; Gemma is the only default/idle model loaded automatically.
- Coding intent is routed conversation-first to Qwen Coder; Gemma is unloaded before Qwen loads.
- After a Qwen reply, Qwen unloads and Gemma is loaded before the coder handoff completes.
- A failed Qwen load restores Gemma when it was the previous active model.
- Cross-model transient handoff context preserves conversational continuity without merging persistent stores.
- Settings now exposes Import/Delete management; manual Load/Unload controls are removed from the UI.
- The chat model pill is display-only; model selection is owned by the runtime router.
