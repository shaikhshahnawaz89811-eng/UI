package com.neonhud.app.core.coder;

import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.engine.PromptPackage;

import java.util.List;

/**
 * The llama.cpp runtime used for the coder takes ONE system prompt and ONE user prompt (it applies the model's own
 * chat template itself). This turns the controlled {@link PromptPackage} into exactly those two strings, and keeps the
 * whole thing inside a character budget so the prompt can never eat the reply's share of the context window.
 */
public final class CoderPrompt {
    private CoderPrompt() { }

    /** ~3 chars per token for code-heavy text: 4096-token window => keep the prompt near 1500 tokens, leave the rest for the reply. */
    public static final int MAX_USER_PROMPT_CHARS = 4500;
    public static final int MAX_SYSTEM_CHARS = 5200;

    /** Removes chat-template end markers that some llama.cpp builds leave in the text, and trims the ends. */
    public static String cleanReply(String raw) {
        if (raw == null) return "";
        String s = raw;
        String[] markers = {"<|im_end|>", "<|im_start|>", "<|endoftext|>"};
        for (String m : markers) {
            int i = s.indexOf(m);
            if (i >= 0) s = s.substring(0, i);       // everything after an end marker is not part of the reply
        }
        return s.trim();
    }

    public static String system(PromptPackage p) {
        String s = p.systemInstruction();
        if (s.length() <= MAX_SYSTEM_CHARS) return s;
        // keep the start (role + rules) and the tail (current request contract); drop the middle
        int head = MAX_SYSTEM_CHARS * 2 / 3;
        int tail = MAX_SYSTEM_CHARS - head - 5;
        return s.substring(0, head) + "\n...\n" + s.substring(s.length() - tail);
    }

    public static String user(PromptPackage p) {
        String current = p.userMessage == null ? "" : p.userMessage;
        int remaining = Math.max(0, MAX_USER_PROMPT_CHARS - current.length());

        StringBuilder prefix = new StringBuilder();
        String skill = p.skillContext == null ? "" : p.skillContext.trim();
        if (!skill.isEmpty()) {
            int cap = Math.min(1800, Math.max(0, remaining - 20));
            if (cap > 0) {
                String block = skill.length() > cap ? skill.substring(0, cap) : skill;
                prefix.append("Skill execution contract:\n").append(block).append("\n\n");
                remaining = Math.max(0, remaining - block.length() - 28);
            }
        }

        String attachmentBlock = attachmentText(p.attachments, Math.min(1500, Math.max(0, remaining - 20)));
        if (!attachmentBlock.isEmpty()) {
            prefix.append("Attached file data (read-only context):\n").append(attachmentBlock).append("\n\n");
            remaining = Math.max(0, remaining - attachmentBlock.length() - 36);
        }

        List<PromptPackage.Turn> turns = p.recentConversation;
        if (skill.isEmpty() && (p.attachments == null || p.attachments.isEmpty()) && turns.isEmpty()) {
            return current;
        }
        StringBuilder history = new StringBuilder();
        int used = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            String piece = (turns.get(i).fromUser ? "User: " : "Assistant: ") + turns.get(i).text + '\n';
            if (used + piece.length() > Math.max(0, remaining - 30)) break;
            used += piece.length();
            history.insert(0, piece);
        }
        // A trimmed history must start with a user turn.
        while (history.indexOf("Assistant: ") == 0) {
            int nl = history.indexOf("\n");
            if (nl < 0) { history.setLength(0); break; }
            history.delete(0, nl + 1);
        }
        if (history.length() > 0) prefix.append("Earlier in this chat:\n").append(history).append('\n');

        // Current message is always appended intact; the UI already caps it below the global prompt budget.
        String header = "Current message from the user (answer this one):\n";
        int allowedPrefix = Math.max(0, MAX_USER_PROMPT_CHARS - current.length() - header.length());
        if (prefix.length() > allowedPrefix) prefix.setLength(allowedPrefix);
        return prefix.append(header).append(current).toString();
    }

    private static String attachmentText(List<Attachment> attachments, int max) {
        if (attachments == null || attachments.isEmpty() || max <= 0) return "";
        StringBuilder b = new StringBuilder();
        for (Attachment a : attachments) {
            if (a == null) continue;
            String part = "FILE: " + a.name + "\n" + (a.text == null ? "" : a.text) + "\n";
            if (b.length() + part.length() > max) {
                int room = max - b.length();
                if (room > 0) b.append(part, 0, Math.min(room, part.length()));
                break;
            }
            b.append(part);
        }
        return b.toString().trim();
    }

}
