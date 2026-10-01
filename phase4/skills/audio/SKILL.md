---
name: audio-reader
description: Read an attached audio file offline for duration, container/codec metadata, sample rate, channels, bitrate, and embedded tags. Report honestly when speech transcription is unavailable; never invent a transcript.
---
# Audio Reader

Use for MP3, WAV, M4A/AAC, OGG, FLAC and Android-decodable audio attachments.

## Workflow
1. Validate the attachment type.
2. Read metadata locally with Android media APIs.
3. Return duration, MIME/container information, sample rate, channel count, bitrate and useful title/artist/album tags when present.
4. If the user asks for a transcript, only return one when a real speech-to-text backend is connected. Otherwise explicitly report that transcription is not available.
5. Keep audio bytes off the network unless a future user-enabled backend explicitly requires it.
