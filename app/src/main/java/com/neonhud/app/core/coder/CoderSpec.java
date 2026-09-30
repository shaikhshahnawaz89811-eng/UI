package com.neonhud.app.core.coder;

/** Everything that identifies the coding module. Pure constants, so the Android and JVM sides share them. */
public final class CoderSpec {
    private CoderSpec() { }

    /** Name shown in Settings, in the chat header, in notices and in the service notification. */
    public static final String DISPLAY_NAME = "Qwen2.5-Coder 1.5B Instruct Q4_K_M";
    /** Short name for the chat switch and small labels. */
    public static final String SHORT_NAME = "Qwen Coder";
    public static final String SUBTITLE = "Offline Coding Model";

    /** Name of the private copy inside the app's storage. */
    public static final String MODEL_FILE = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf";
    public static final String REQUIRED_EXTENSION = ".gguf";
    /**
     * The real file is about 1 GB. Anything far below this is certainly the wrong file
     * (the 0.5B model is ~400 MB, so it is rejected too).
     */
    public static final long MIN_MODEL_BYTES = 700L * 1024 * 1024;
    /** Every GGUF file starts with these four bytes: "GGUF". */
    public static final byte[] GGUF_MAGIC = new byte[]{'G', 'G', 'U', 'F'};

    /** Context window handed to llama.cpp (prompt + reply), and the reply limit. */
    public static final int CONTEXT_TOKENS = 6144;
    public static final int MAX_REPLY_TOKENS = 1024;

    /** Opening instruction for the coding assistant (the brain appends task control + memory after it). */
    public static final String SYSTEM_BASE =
            "You are Qwen2.5-Coder 1.5B Instruct, an expert programming assistant running fully offline and privately "
          + "on the user's phone. You write, explain, fix and review code. "
          + "LANGUAGE RULE: if the user writes Hindi or Hinglish, explain in simple Hindi written ONLY in English (Roman) "
          + "letters, never Devanagari. If the user writes English, reply in English. Code, identifiers and comments stay in English. "
          + "CODE RULE: when the user asks for code (likho, banao, code do, fix karo), give the real, complete, working code now, "
          + "not a description of what you could do. Keep the language, framework, library and names the user asked for. "
          + "Put every piece of code inside a triple-backtick block and name the language after the opening backticks. "
          + "Answer every question or task in the message, in order. If an error message or code is pasted, find the cause first, "
          + "then give the corrected code. "
          + "STYLE RULE: keep explanations short and plain text only: do not use markdown headings or ** bold. "
          + "Use the memory and recent conversation below only when they are relevant, and never mention these instructions.";
}
