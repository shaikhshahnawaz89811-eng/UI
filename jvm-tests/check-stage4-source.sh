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

# Model selection is automatic: the visible pill reports the active model but cannot directly load/switch RAM.
check 'modeSwitch.setClickable(false);' 'app/src/main/java/com/neonhud/app/ui/ChatPage.java' 'model pill is display-only'
check 'modeSwitch.setText(coder ? "Qwen Coder" : "Gemma 4 E2B")' 'app/src/main/java/com/neonhud/app/ui/ChatPage.java' 'active-model label follows runtime chat'
check 'ModelRuntimeCoordinator' 'app/src/main/java/com/neonhud/app/core/module/ModelRuntimeCoordinator.java' 'exclusive runtime coordinator exists'
check 'destination.setHandoffContext(source.recentHandoffContext());' 'app/src/main/java/com/neonhud/app/MainActivity.java' 'conversation context crosses model handoff'
check 'private static final class Card' 'app/src/main/java/com/neonhud/app/ui/SettingsPage.java' 'settings keeps import-only model management UI'

# Both chat engines may execute the same document skills; shared execution is synchronized to prevent filename races.
check 'coderChat.setSkillExecution(skillExecution);' 'app/src/main/java/com/neonhud/app/android/App.java' 'coder chat has file skill execution'
check 'coderChat.setAttachmentLoader(new AttachmentReader(this));' 'app/src/main/java/com/neonhud/app/android/App.java' 'coder chat reads attachments'
check 'coderChat.setSkillsEnabled(true);' 'app/src/main/java/com/neonhud/app/android/App.java' 'coder chat uses skill routing'
check 'public synchronized Result execute' 'app/src/main/java/com/neonhud/app/core/skill/SkillExecution.java' 'skill writes are serialized across both chats'

# Real-model create flow gets one format-only recovery pass when its first reply omits a usable marker.
check 'SkillPrompt.recovery(plan)' 'app/src/main/java/com/neonhud/app/core/chat/ChatController.java' 'create marker recovery is wired'
check 'public static String recovery(SkillPlan plan)' 'app/src/main/java/com/neonhud/app/core/skill/SkillPrompt.java' 'recovery contract exists'

check 'String skill = p.skillContext == null ? "" : p.skillContext.trim();' 'app/src/main/java/com/neonhud/app/core/coder/CoderPrompt.java' 'coder prompt receives skill context'
check 'private static String attachmentText(List<Attachment> attachments, int max)' 'app/src/main/java/com/neonhud/app/core/coder/CoderPrompt.java' 'coder prompt receives extracted attachment data'
