package com.neonhud.app.core.coder;

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
        String current = p.userMessage;
        StringBuilder out = new StringBuilder();
        List<PromptPackage.Turn> turns = p.recentConversation;
        int budget = MAX_USER_PROMPT_CHARS - current.length() - 64;
        // newest turns first, stop when the budget is used: the current message must always fit
        int from = turns.size();
        int used = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            int len = turns.get(i).text.length() + 12;
            if (used + len > budget) break;
            used += len;
            from = i;
        }
        // a history must start with a user turn, otherwise the model sees an answer to nothing
        while (from < turns.size() && !turns.get(from).fromUser) from++;
        if (from < turns.size()) {
            out.append("Earlier in this chat:\n");
            for (int i = from; i < turns.size(); i++) {
                PromptPackage.Turn t = turns.get(i);
                out.append(t.fromUser ? "User: " : "Assistant: ").append(t.text).append('\n');
            }
            out.append("\nCurrent message from the user (answer this one):\n");
        }
        out.append(current);
        return out.toString();
    }
}
