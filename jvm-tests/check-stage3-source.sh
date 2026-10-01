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

check 'implements SelectableAttachmentLoader' 'app/src/main/java/com/neonhud/app/android/AttachmentReader.java' 'Android attachment reader accepts selections'
check 'selection.hasPages()' 'app/src/main/java/com/neonhud/app/android/AttachmentReader.java' 'PDF page selection is wired'
check 'selection.hasSheet()' 'app/src/main/java/com/neonhud/app/android/OfficeAttachmentReader.java' 'Excel sheet selection is wired'
check 'selection.hasSlides()' 'app/src/main/java/com/neonhud/app/android/OfficeAttachmentReader.java' 'PowerPoint slide selection is wired'
check 'Content.Text("[IMAGE $imageNumber | file=${a.name} | $label]")' 'app/src/main/java/com/neonhud/app/android/GemmaEngine.kt' 'Vision images are explicitly numbered and labelled'
check 'a.imageLabels' 'app/src/main/java/com/neonhud/app/android/GemmaEngine.kt' 'Vision labels reach the model'
check 'case AUDIO: return AttachIcon.AUDIO;' 'app/src/main/java/com/neonhud/app/ui/AttachViews.java' 'Audio attachment has its own chip icon'
check 'case DOCX: return AttachIcon.WORD;' 'app/src/main/java/com/neonhud/app/ui/AttachViews.java' 'Word attachment has its own chip icon'
check 'case XLSX: return AttachIcon.EXCEL;' 'app/src/main/java/com/neonhud/app/ui/AttachViews.java' 'Excel attachment has its own chip icon'
check 'case PPTX: return AttachIcon.PPT;' 'app/src/main/java/com/neonhud/app/ui/AttachViews.java' 'PowerPoint attachment has its own chip icon'
check 'MAX_TOTAL_TEXT_CHARS = 24000' 'app/src/main/java/com/neonhud/app/core/engine/ReadBudget.java' 'Total read text cap exists'
check 'MAX_FILE_TEXT_CHARS = 8000' 'app/src/main/java/com/neonhud/app/core/engine/ReadBudget.java' 'Per-file read text cap exists'
check 'readCache.get(source, task.selection)' 'app/src/main/java/com/neonhud/app/core/chat/ChatController.java' 'Exact read cache hit is used'
check 'readCache.getLatest(source)' 'app/src/main/java/com/neonhud/app/core/chat/ChatController.java' 'Latest cached read is reused'
check 'SkillTask.readCached' 'app/src/main/java/com/neonhud/app/core/skill/SkillRouter.java' 'Router can produce cached read tasks'
check 'return new SkillPlan(tasks, "", unsupported, notDone);' 'app/src/main/java/com/neonhud/app/core/skill/SkillRouter.java' 'Cached read follow-up can return without entering write analysis'

echo 'PASS: Stage 3 Android/source sanity suite'
