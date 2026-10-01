---
name: docx
description: Use for DOCX reading, creation, and targeted text editing. Preserve unrelated package parts and do not claim support for legacy .doc files.
---
# DOCX Skill
Read paragraph text from `word/document.xml`, including text inside table cells through the normal paragraph stream. Create a standards-based OOXML DOCX with Arial defaults and a title/body structure. For edits, replace text inside Word text runs and preserve unrelated ZIP package entries. If a requested replacement spans multiple runs, report that boundary instead of silently changing unrelated text.
