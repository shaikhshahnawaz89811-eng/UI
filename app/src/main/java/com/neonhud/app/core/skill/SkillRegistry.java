package com.neonhud.app.core.skill;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Central list of implemented skills. Adding a future skill should not require changing chat logic. */
public final class SkillRegistry {
    private final Map<SkillKind, SkillDescriptor> skills = new EnumMap<SkillKind, SkillDescriptor>(SkillKind.class);

    public SkillRegistry() {
        register(new SkillDescriptor(
                SkillKind.IMAGE_READER, "image-reader", "Image Reader",
                "Reads an attached or web image and gives the vision model the actual image bytes.",
                true, true,
                Arrays.asList("JPG", "PNG", "WEBP", "HEIC when Android can decode it"),
                Arrays.asList("vision image", "short metadata note")));
        register(new SkillDescriptor(
                SkillKind.PDF_READER, "pdf-reader", "PDF Reader",
                "Opens a PDF, renders its pages as images so scanned PDFs can also be read, and reports page coverage.",
                true, true,
                Arrays.asList("PDF"),
                Arrays.asList("page images", "page-count note")));
        register(new SkillDescriptor(
                SkillKind.PDF_CREATOR, "pdf-creator", "PDF Creator",
                "Creates a normal text PDF locally without a network service. Chat CREATE tasks are wired through SkillExecution.",
                true, false,
                Arrays.asList("title", "plain text"),
                Arrays.asList("PDF file")));
        register(new SkillDescriptor(
                SkillKind.AUDIO_READER, "audio-reader", "Audio Reader",
                "Reads supported audio attachments offline for duration, codec/container metadata, channels, sample rate, and embedded tags when available. Speech transcription is reported as unavailable unless a speech-to-text backend is connected.",
                true, false,
                Arrays.asList("MP3", "WAV", "M4A/AAC", "OGG", "FLAC", "3GP audio when Android can decode it"),
                Arrays.asList("audio metadata", "transcription backend status")));
        register(new SkillDescriptor(
                SkillKind.VIDEO_READER, "video-reader", "Video Reader",
                "Samples several points across an attached video and sends those frames to the vision model with duration and resolution metadata.",
                true, true,
                Arrays.asList("MP4/MOV", "WebM/Matroska when Android can decode it"),
                Arrays.asList("sampled video frames", "duration/resolution note")));
        register(new SkillDescriptor(
                SkillKind.PROJECT_ZIP_READER, "project-zip-reader", "Project ZIP Reader",
                "Lists an attached ZIP and reads the beginning of relevant text/code files within a strict budget.",
                true, false,
                Collections.singletonList("ZIP"),
                Arrays.asList("file list", "bounded text/code excerpts")));
        register(new SkillDescriptor(
                SkillKind.DOCX, "docx", "Word Skill",
                "Reads DOCX paragraphs/tables, creates a styled DOCX, and can replace text while preserving unrelated package parts.",
                true, false,
                Arrays.asList("DOCX"),
                Arrays.asList("document text", "DOCX file", "edited DOCX")));
        register(new SkillDescriptor(
                SkillKind.XLSX, "xlsx", "Excel Skill",
                "Reads XLSX sheets and formulas, creates a simple Arial spreadsheet, handles CSV/TSV, and edits a selected cell without rebuilding unrelated package parts.",
                true, false,
                Arrays.asList("XLSX", "CSV", "TSV"),
                Arrays.asList("sheet/cell text", "XLSX file", "edited XLSX")));
        register(new SkillDescriptor(
                SkillKind.PPTX, "pptx", "PowerPoint Skill",
                "Reads slide text, creates a simple 16:9 presentation with Arial text, and replaces text while preserving unrelated slide package parts.",
                true, false,
                Arrays.asList("PPTX"),
                Arrays.asList("slide text", "PPTX file", "edited PPTX")));
    }

    public void register(SkillDescriptor descriptor) {
        if (descriptor == null || descriptor.kind == null) throw new IllegalArgumentException("skill descriptor is required");
        skills.put(descriptor.kind, descriptor);
    }

    public SkillDescriptor get(SkillKind kind) { return skills.get(kind); }

    public List<SkillDescriptor> all() {
        return Collections.unmodifiableList(Arrays.asList(
                skills.get(SkillKind.IMAGE_READER),
                skills.get(SkillKind.PDF_READER),
                skills.get(SkillKind.PDF_CREATOR),
                skills.get(SkillKind.AUDIO_READER),
                skills.get(SkillKind.VIDEO_READER),
                skills.get(SkillKind.PROJECT_ZIP_READER),
                skills.get(SkillKind.DOCX),
                skills.get(SkillKind.XLSX),
                skills.get(SkillKind.PPTX)));
    }
}
