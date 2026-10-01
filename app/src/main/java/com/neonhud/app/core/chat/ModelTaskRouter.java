package com.neonhud.app.core.chat;

import com.neonhud.app.core.engine.Attachment;

import java.util.List;
import java.util.Locale;

/** Deterministic conversation-to-model routing: coding work goes to Qwen; other work stays on Gemma. */
public final class ModelTaskRouter {
    public enum Target { GEMMA, CODER }
    private ModelTaskRouter() { }

    public static Target route(String rawText, List<Attachment> files, Target current) {
        String text = rawText == null ? "" : rawText.toLowerCase(Locale.ROOT);
        if (isCoding(text, files)) return Target.CODER;
        // Short follow-ups inherit the currently active conversation/model unless they explicitly ask for a non-code skill.
        if (current == Target.CODER && isShortFollowup(text)) return Target.CODER;
        return Target.GEMMA;
    }

    private static boolean isCoding(String t, List<Attachment> files) {
        String[] strong = {
            "code", "coding", "program", "programming", "bug", "debug", "debugging", "error", "exception",
            "stacktrace", "crash", "logcat", "compile", "build.gradle", "gradle", "android studio", "android app",
            "kotlin", "java", "python", "javascript", "typescript", "html", "css", "sql", "json", "xml",
            "bash", "shell script", "script", "termux", "github", "git", "repository", "repo", "apk", "sdk",
            "api", "class ", "function ", "method ", "interface ", "dependency", "library", "framework",
            "refactor", "implement", "implementation", "source code", "fix this code", "fix the code", "write code",
            "code likho", "code banao", "pura code", "full code", "code do", "bug fix", "fix karo", "error fix",
            "project check", "project dekho", "project fix", "zip check", "repo check", "repository check"
        };
        for (String cue : strong) if (t.contains(cue)) return true;
        if (files != null) {
            for (Attachment a : files) {
                if (a == null) continue;
                String n = a.name == null ? "" : a.name.toLowerCase(Locale.ROOT);
                if (isCodeFile(n) && (t.contains("dekho") || t.contains("check") || t.contains("fix") || t.contains("review")
                        || t.contains("read") || t.contains("open") || t.contains("samjho") || t.contains("analyze") || t.isEmpty())) return true;
                if (a.kind == Attachment.Kind.ZIP && (t.contains("project") || t.contains("repo") || t.contains("source") || t.contains("code")
                        || t.contains("check") || t.contains("review") || t.contains("analyze") || t.contains("dekho") || t.contains("samjho")
                        || t.contains("fix") || t.contains("open") || t.contains("read"))) return true;
            }
        }
        return false;
    }

    private static boolean isShortFollowup(String t) {
        return t.isEmpty() || t.length() <= 48 || t.equals("haan") || t.equals("ha") || t.equals("yes") || t.equals("okay")
                || t.equals("ok") || t.equals("karo") || t.equals("continue") || t.equals("continue karo")
                || t.equals("isko fix karo") || t.equals("ab ye karo") || t.equals("phir se check karo");
    }

    private static boolean isCodeFile(String n) {
        String[] exts = {".kt", ".kts", ".java", ".py", ".js", ".jsx", ".ts", ".tsx", ".html", ".css", ".scss", ".xml",
                ".json", ".gradle", ".properties", ".sh", ".bash", ".c", ".h", ".cc", ".cpp", ".hpp", ".rs", ".go", ".sql", ".yaml", ".yml"};
        for (String e : exts) if (n.endsWith(e)) return true;
        return n.equals("build.gradle") || n.equals("settings.gradle") || n.equals("gradle.properties") || n.equals("dockerfile") || n.endsWith("/dockerfile");
    }
}
