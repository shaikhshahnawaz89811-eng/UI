---
name: video-reader
description: Read an attached video offline by sampling frames across its timeline and reporting duration, resolution, and whether an audio track is present. Do not fabricate speech transcription.
---
# Video Reader

Use for MP4/MOV/WebM/Matroska and Android-decodable video attachments.

## Workflow
1. Validate and open the video locally.
2. Sample frames across the complete duration, within the phone-safe frame budget.
3. Give the vision model those frames with duration and resolution metadata.
4. Detect whether an audio track exists.
5. If speech transcription is requested, only use a real speech-to-text backend; otherwise state that video audio is detected but not transcribed.
