#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
check() {
  local pattern="$1" file="$2" label="$3"
  if grep -Fq "$pattern" "$ROOT/$file"; then
    echo "PASS: $label"
  else
    echo "FAIL: $label (missing '$pattern' in $file)" >&2
    exit 1
  fi
}

# Attachment/send race guard: the same UI gate must own both the send button and the async attachment check.
check 'private final AttachmentSendGate attachmentGate' 'app/src/main/java/com/neonhud/app/ui/ChatPage.java' 'ChatPage owns the attachment send gate'
check 'if (!attachmentGate.canSend(generating, !text.isEmpty(), !pending.isEmpty(), handler != null)) return;' 'app/src/main/java/com/neonhud/app/ui/ChatPage.java' 'send exits while attachment validation is busy'
check 'if (!attachEnabled || attachmentGate.isBusy()) return;' 'app/src/main/java/com/neonhud/app/ui/ChatPage.java' 'attachment picker cannot reopen while busy'
check 'chatPage.setAttachmentBusy(true);' 'app/src/main/java/com/neonhud/app/MainActivity.java' 'async attachment validation locks sending before work'
check 'chatPage.setAttachmentBusy(false);' 'app/src/main/java/com/neonhud/app/MainActivity.java' 'async attachment validation always releases the gate'

# Verify the release occurs only after the validated attachments are committed to the pending list.
python3 - "$ROOT/app/src/main/java/com/neonhud/app/MainActivity.java" <<'PY'
import sys
p=sys.argv[1]
s=open(p,encoding='utf-8').read()
needle='addPicked(ok);\n                            } finally {\n                                chatPage.setAttachmentBusy(false);'
if needle not in s:
    raise SystemExit('FAIL: attachment result is released before addPicked(ok)')
print('PASS: validated attachments are added before send gate release')
PY

# File-level Stage 3 checks remain part of the Stage 4 gate.
"$ROOT/jvm-tests/check-stage3-source.sh"

echo 'PASS: Stage 4 Android/source race + Stage 3 sanity suite'
