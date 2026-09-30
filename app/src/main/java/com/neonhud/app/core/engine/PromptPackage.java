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

    public PromptPackage(String systemContext, String relevantMemory,
                         List<Turn> recentConversation, String userMessage) {
        this.systemContext = systemContext == null ? "" : systemContext;
        this.relevantMemory = relevantMemory == null ? "" : relevantMemory;
        this.recentConversation = recentConversation == null
                ? Collections.<Turn>emptyList()
                : Collections.unmodifiableList(new ArrayList<Turn>(recentConversation));
        this.userMessage = userMessage == null ? "" : userMessage;
    }

    /** System text for engines that have a dedicated system slot: context + memory. */
    public String systemInstruction() {
        StringBuilder sb = new StringBuilder(systemContext);
        if (!relevantMemory.isEmpty()) sb.append("\n\n").append(relevantMemory);
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
        sb.append("CURRENT USER MESSAGE:\n").append(userMessage);
        return sb.toString();
    }
}
