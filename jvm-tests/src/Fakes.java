package tests;

import com.neonhud.app.core.engine.GenerationCallback;
import com.neonhud.app.core.engine.ModelEngine;
import com.neonhud.app.core.engine.PromptPackage;
import com.neonhud.app.core.module.ImportSource;
import com.neonhud.app.core.module.ModelStorage;
import com.neonhud.app.core.module.ModuleManager;
import com.neonhud.app.core.module.ModuleSnapshot;
import com.neonhud.app.core.module.ModuleState;
import com.neonhud.app.core.module.ProgressSink;
import com.neonhud.app.core.module.StateStore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

final class Fakes {

    /** Engine that records misuse (load while loaded, unload while generating, ...). */
    static class FakeEngine implements ModelEngine {
        final AtomicInteger loadCalls = new AtomicInteger();
        final AtomicInteger unloadCalls = new AtomicInteger();
        final AtomicInteger violations = new AtomicInteger();
        volatile boolean loaded;
        volatile boolean generating;
        volatile boolean failNextLoad;
        volatile long loadDelayMs = 0;
        volatile long tokenDelayMs = 0;
        volatile String lastSystem = "";
        volatile PromptPackage lastPrompt;
        volatile String cannedReply = "Sure. Here is a short answer. It has a second sentence too.";
        volatile String[] replySequence;
        final AtomicInteger generateCalls = new AtomicInteger();

        void setReplySequence(String... replies) {
            replySequence = replies == null ? null : replies.clone();
            generateCalls.set(0);
        }

        @Override public void load(String path) throws Exception {
            loadCalls.incrementAndGet();
            if (loaded) violations.incrementAndGet();          // a second load must never reach the engine
            if (loadDelayMs > 0) Thread.sleep(loadDelayMs);
            if (failNextLoad) { failNextLoad = false; throw new Exception("simulated load failure"); }
            loaded = true;
        }

        @Override public void unload() {
            unloadCalls.incrementAndGet();
            if (generating) violations.incrementAndGet();      // never unload mid-reply
            loaded = false;
        }

        @Override public boolean isLoaded() { return loaded; }

        @Override public void generate(PromptPackage p, GenerationCallback cb) throws Exception {
            if (!loaded) { violations.incrementAndGet(); throw new IllegalStateException("not loaded"); }
            generating = true;
            lastPrompt = p;
            String answer = cannedReply;
            String[] sequence = replySequence;
            int call = generateCalls.getAndIncrement();
            if (sequence != null && sequence.length > 0) answer = sequence[Math.min(call, sequence.length - 1)];
            try {
                for (String w : answer.split("(?<= )")) {
                    if (tokenDelayMs > 0) Thread.sleep(tokenDelayMs);
                    cb.onToken(w);
                }
            } finally { generating = false; }
        }

        @Override public void cancelGeneration() { }
    }

    static final class MemStateStore implements StateStore {
        volatile ModuleState s;
        MemStateStore(ModuleState s) { this.s = s; }
        @Override public ModuleState load() { return s; }
        @Override public void save(ModuleState state) { s = state; }
    }

    static final class FakeStorage implements ModelStorage {
        volatile boolean present;
        volatile boolean failImport, failDelete;
        volatile long importDelayMs = 0;
        @Override public boolean isModelPresent() { return present; }
        @Override public String modelPath() { return "/fake/gemma.litertlm"; }
        @Override public void importModel(ImportSource src, ProgressSink p) throws IOException {
            try { if (importDelayMs > 0) Thread.sleep(importDelayMs); } catch (InterruptedException ignored) { }
            if (failImport) throw new IOException("simulated import failure");
            p.onProgress(50);
            present = true;
        }
        @Override public void deleteModel() throws IOException {
            if (failDelete) throw new IOException("simulated delete failure");
            present = false;
        }
        @Override public void cleanupPartial() { }
    }

    static ImportSource src(final String name, final long size) {
        return new ImportSource() {
            @Override public String displayName() { return name; }
            @Override public long sizeBytes() { return size; }
            @Override public InputStream open() { return new ByteArrayInputStream(new byte[(int) Math.max(size, 0)]); }
        };
    }

    static void awaitIdle(ModuleManager m) {
        long end = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < end) {
            ModuleSnapshot s = m.snapshot();
            if (s.inFlight == null) return;
            try { Thread.sleep(2); } catch (InterruptedException ignored) { }
        }
        throw new AssertionError("module never became idle");
    }
}
