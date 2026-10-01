# Current local Skills

This build adds a small, explicit Skill layer without replacing the existing chat, web, memory, or attachment systems.

## Implemented skills

| Skill | Real work | Input | Output to the model |
|---|---|---|---|
| `image-reader` | Decodes and downscales an attached image | Image attachment | One JPEG image + note |
| `pdf-reader` | Opens the PDF and renders the first pages; scanned PDFs work because pages are sent as images | PDF attachment | Up to 4 page JPEGs + page-count note |
| `pdf-creator` | Creates an offline text PDF with Android `PdfDocument` | Title + text | PDF file |
| `audio-reader` | Reads supported audio metadata offline and reports transcription availability honestly | Audio attachment | Duration, MIME, sample rate, channels, bitrate and embedded tags when present |
| `video-reader` | Reads metadata, samples up to 6 points across the video, and detects an audio track | Video attachment | Sampled JPEG frames + duration/resolution + audio-track note |
| `project-zip-reader` | Lists a project ZIP and reads bounded text/code excerpts | ZIP attachment | File list + bounded text/code |

## Skill selection

`SkillRegistry` is the central catalog. `SkillPlanner` deterministically selects the skill(s) required by the current attachment types plus explicit PDF/Word/Excel/PowerPoint creation phrases. Office attachments are classified from OOXML package parts when they are ZIP-based.

Attachment reading still happens through the existing `AttachmentLoader` path, so the new layer does not duplicate or bypass the safety limits already present in the project.

## Current audio/video boundary

The video skill is genuine visual sampling, not a claim of full continuous video understanding. It sends several frames from across the timeline to the vision-capable model and detects whether an audio track exists. The audio skill reads media metadata locally. Neither skill claims speech transcription yet: a real speech-to-text backend is still required before the assistant can truthfully produce a transcript or say it heard the spoken audio.

## Current PDF creation boundary

The PDF creator is implemented as an offline reusable Android service. A dedicated chat/Lab export button is intentionally not added in this step; the current task is the skill foundation only.

## Office skill boundaries

The Office skills follow the current Anthropic skill intent of using dedicated workflows for primary Word, Excel/spreadsheet, and PowerPoint inputs/outputs. They are implemented here without a new third-party Android office runtime: modern OOXML packages are read/written directly, which keeps the APK dependency footprint small. The readers support DOCX, XLSX, PPTX plus CSV/TSV for the spreadsheet path. They do not claim legacy `.doc`, `.xls`, or `.ppt` compatibility. XLSX formulas are preserved/read as formulas; the offline skill does not invent calculated results when no cached result exists.

Creation/editing services are exposed through the Android process-wide `App` so a future Lab/export surface can call them; this phase deliberately does not redesign the current chat UI.
