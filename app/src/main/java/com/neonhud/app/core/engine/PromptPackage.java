package com.neonhud.app.core.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The controlled context handed to a model:
 * SYSTEM CONTEXT + RELEVANT MEMORY + RECENT CONVERSATION + CURRENT USER MESSAGE.
 * Engine adapters map it onto their own API; {@link #flatten()} is the plain-text fallback.
 */
public final class PromptPackage {

    public static final class Turn {
        public final boolean fromUser;
        public final String text;

        public Turn(boolean fromUser, String text) {
            this.fromUser = fromUser;
            this.text = text;
        }
    }

    public final String systemContext;
    public final String relevantMemory;      // may be empty
    public final List<Turn> recentConversation;
    public final String userMessage;
    /** Files attached to the current message (already loaded); empty for a plain text message. */
    public final List<Attachment> attachments;
    /**
     * Internet search results for the current message (already cleaned and shortened by the web layer), or "".
     * Never stored in memory or history: it belongs to this one reply only.
     */
    public final String webContext;
    /** Short capability note from the skill router for this message only ("" = none). Never stored. */
    public final String skillContext;
    /** Cross-model conversation context for a handoff; never stored as a normal turn. */
    public final String handoffContext;

    public PromptPackage(String systemContext, String relevantMemory,
                         List<Turn> recentConversation, String userMessage) {
        this(systemContext, relevantMemory, recentConversation, userMessage, null, null, null, null);
    }

    public PromptPackage(String systemContext, String relevantMemory,
                         List<Turn> recentConversation, String userMessage, List<Attachment> attachments) {
        this(systemContext, relevantMemory, recentConversation, userMessage, attachments, null, null, null);
    }

    private PromptPackage(String systemContext, String relevantMemory,
                          List<Turn> recentConversation, String userMessage, List<Attachment> attachments,
                          String webContext, String skillContext, String handoffContext) {
        this.webContext = webContext == null ? "" : webContext;
        this.skillContext = skillContext == null ? "" : skillContext;
        this.handoffContext = handoffContext == null ? "" : handoffContext;
        this.attachments = attachments == null
                ? Collections.<Attachment>emptyList()
                : Collections.unmodifiableList(new ArrayList<Attachment>(attachments));
        this.systemContext = systemContext == null ? "" : systemContext;
        this.relevantMemory = relevantMemory == null ? "" : relevantMemory;
        this.recentConversation = recentConversation == null
                ? Collections.<Turn>emptyList()
                : Collections.unmodifiableList(new ArrayList<Turn>(recentConversation));
        this.userMessage = userMessage == null ? "" : userMessage;
    }

    /** Same prompt with the current message's files attached. */
    public PromptPackage withAttachments(List<Attachment> files) {
        return new PromptPackage(systemContext, relevantMemory, recentConversation, userMessage, files, webContext, skillContext, handoffContext);
    }

    /** Same prompt with the internet search block for the current message ("" = none). */
    public PromptPackage withWebContext(String web) {
        return new PromptPackage(systemContext, relevantMemory, recentConversation, userMessage, attachments, web, skillContext, handoffContext);
    }

    /** Same prompt with the skill-router note for the current message ("" = none). */
    public PromptPackage withSkillContext(String note) {
        return new PromptPackage(systemContext, relevantMemory, recentConversation, userMessage, attachments, webContext, note, handoffContext);
    }

    /** Same prompt with transient context handed over from the other offline model. */
    public PromptPackage withHandoffContext(String handoff) {
        return new PromptPackage(systemContext, relevantMemory, recentConversation, userMessage, attachments, webContext, skillContext, handoff);
    }

    /** System text for engines that have a dedicated system slot: context + memory + transient handoff. */
    public String systemInstruction() {
        StringBuilder sb = new StringBuilder(systemContext);
        if (!relevantMemory.isEmpty()) sb.append("\n\n").append(relevantMemory);
        if (!handoffContext.isEmpty()) sb.append("\n\nCONVERSATION HANDOFF (from the other offline model; use only when relevant):\n").append(handoffContext);
        return sb.toString();
    }

    /** Whole prompt as one labelled string (for engines without a chat API). */
    public String flatten() {
        StringBuilder sb = new StringBuilder();
        sb.append("SYSTEM CONTEXT:\n").append(systemContext).append("\n\n");
        if (!relevantMemory.isEmpty()) sb.append(relevantMemory).append("\n\n");
        if (!recentConversation.isEmpty()) {
            sb.append("RECENT CONVERSATION:\n");
            for (Turn t : recentConversation) {
                sb.append(t.fromUser ? "User: " : "Assistant: ").append(t.text).append('\n');
            }
            sb.append('\n');
        }
        for (Attachment a : attachments) {
            if (!a.text.isEmpty()) sb.append(a.text).append("\n\n");
        }
        if (!webContext.isEmpty()) sb.append(webContext).append("\n\n");
        if (!skillContext.isEmpty()) sb.append(skillContext).append("\n\n");
        if (!handoffContext.isEmpty()) sb.append("CONVERSATION HANDOFF:\n").append(handoffContext).append("\n\n");
        sb.append("CURRENT USER MESSAGE:\n").append(userMessage);
        return sb.toString();
    }
}
