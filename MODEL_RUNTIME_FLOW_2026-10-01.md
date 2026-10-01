# Model Runtime Flow — 2026-10-01

## Runtime policy
Both offline model files are independently imported and stored. Only one model is allowed in RAM at any moment.

- Gemma 4 E2B is the idle/default runtime model.
- Qwen2.5-Coder is never auto-loaded merely because it was imported.
- A coding message is routed to Qwen Coder.
- Before Qwen loads, Gemma is unloaded completely.
- While Qwen replies, Gemma stays offline.
- When the Qwen task finishes, Qwen is unloaded first; Gemma is then loaded before the coder runtime is considered finished.
- Failed Qwen handoff attempts restore Gemma when possible, so a model transition does not strand the phone with both models offline.

## Conversation-driven routing
The message and attachments are classified before a chat turn. Coding intent and project/code attachments route to Qwen; normal chat, web research, Office creation, media reading and other existing skills remain on Gemma.

Short cross-model context is passed transiently so the target model can continue the conversation without merging the two persistent memory stores.

## Frontend behavior
Settings is import/delete management only. There are no manual Load/Unload controls for either model.

The chat model pill is display-only and reports the active model. During a handoff, the input/reply lifecycle remains protected by the existing chat busy gate.

## Backend wiring
`ModelRuntimeCoordinator` is the single RAM ownership gate. Both `ChatController` instances use it in the Android app. `ModelService` observes coordinator state and keeps the process alive during import, switching and generation.

The existing skill router/executor, attachment readers, web layer, conversation memory and file verification remain connected to both chats where previously supported.

## Verification
- Full JVM regression: PASS.
- Stage 4 source gate: PASS.
- 5,000-question Stage 4 router corpus: PASS.
- Stage 4 deep execution and Office/PDF artifact validation: PASS via the existing regression suite.
- Android APK compile/device smoke test is not claimed in this container because Android SDK/Gradle/adb are unavailable here.
