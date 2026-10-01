# Final Skills Wiring Audit

Date: 2026-10-01  
Project: neon-hud / Stage 4 final wiring delivery

## Final status

**Skills conversation wiring: complete at source/JVM level.** Stage 1, Stage 2, Stage 3 and Stage 4 implementation paths are present, connected, and regression-tested. The remaining item is only Android APK/device validation in an environment that has the Android SDK, Gradle toolchain and `adb`.

## End-to-end path verified

`chat input + attachments -> SkillRouter -> ordered SkillPlan -> reads / CREATE / EDIT -> SkillExecution -> real output file -> read-back verification -> FileProvider output -> Open/Share card -> verified last-output cache`

The ChatController injects the skill plan into the model prompt, avoids model calls for clarification-only turns, executes real CREATE/EDIT tasks, and reports only verified outputs.

## Final hardening fixes

- Explicit `first/second/third/last` and unambiguous filename targets select one attachment when multiple files are attached; ambiguous cached references ask instead of guessing.
- The Files picker uses `*/*`, matching the content-sniffing classifier so provider MIME declarations cannot hide supported ZIP/PDF/OOXML/audio/video files.
- The PDF Creator registry metadata now matches the actual chat wiring.
- CI runs the JVM regression, Stage 4 source/race sanity, and deep write/artifact validation before `assembleDebug`.

## Verification

- Full JVM regression: **20,860 / 20,860 passed**.
- Stage 4 exact router corpus: **5,000 / 5,000 exact**.
- Stage 4 deep real execution: **2,500 / 2,500 writes successful**.
- Independent artifact validation: **PASS** (700 DOCX, 600 XLSX, 650 PPTX, 550 PDF).
- Stage 4 source/race sanity: **PASS**.
- Android device sample: **PENDING external environment**; no Android SDK/compiler or `adb` is installed in the current container.

## Known intentional limits

Speech-to-text for audio/video is not implemented; audio is metadata-only and video uses frames. PDF editing is explicitly unsupported. Legacy `.doc/.xls/.ppt` files are rejected honestly. These are documented capability limits, not broken wiring.
