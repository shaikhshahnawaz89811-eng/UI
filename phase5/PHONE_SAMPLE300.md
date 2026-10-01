# Stage 4 — 300-case phone sample

This is the deterministic on-device sample extracted from the 5,000-row Stage 4 corpus.

**Prepared:** yes
**Device execution:** pending — this environment has no `adb`/Android SDK device toolchain.

The sample contains all major read/create/edit/multi/negative blocks and all eight wording styles. The sample must be run on a real Android build before the project can claim phone validation.

## Mandatory phone checks

1. Attach a file and immediately type text while attachment validation is still running. The Send button must remain disabled until validation finishes and the validated attachment is actually committed to the pending list.
2. Tap Send repeatedly at the exact boundary where attachment validation completes. The message must contain the attachment exactly once; the attachment must not spill into the next message.
3. Tap `+` while validation is running. The picker/cart must remain locked until the check finishes.
4. Cancel or fail the attachment provider. No ghost attachment may appear and Send must recover to the correct state.
5. Attach multiple files. Their order must remain stable and no file may migrate to a later message.
6. Repeat a cached read follow-up (`page 4`, `isko summarize karo`) and verify no unnecessary re-read is triggered.
7. Check PDF page, PPTX slide and XLSX sheet selections on-device.
8. Verify Audio / Word / Excel / PowerPoint attachment icons are distinct and correct.
9. Verify progress/status states show only actual operations; no `File ready` is shown without a real verified file.

The JVM source/race suite can prove the gate logic, but it cannot replace this real-device run.
