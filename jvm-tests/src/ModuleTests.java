package tests;

import com.neonhud.app.core.module.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

final class ModuleTests {

    static ModuleManager newManager(Fakes.FakeEngine e, Fakes.FakeStorage s, Fakes.MemStateStore st) {
        return new ModuleManager(e, s, st);
    }

    static void run() throws Exception {
        stateMachineMatrix();
        happyPath();
        bypassAttempts();
        concurrentDoubleLoad();
        concurrentMixedHammer();
        failuresRollBack();
        coldStartRestore();
        replyBlocksUnload();
        fileStorage();
    }

    // ---------------------------------------------------------------- exhaustive transition table
    static void stateMachineMatrix() {
        T.section("state machine: full 4x4 transition table");
        ModuleState[] S = ModuleState.values();
        ModuleAction[] A = ModuleAction.values();
        // expected[state][action] -> target or null
        ModuleState NI = ModuleState.NOT_IMPORTED, IM = ModuleState.IMPORTED, LO = ModuleState.LOADED, UN = ModuleState.UNLOADED;
        ModuleState[][] exp = {
            /* NOT_IMPORTED */ {IM, null, null, null},
            /* IMPORTED     */ {null, LO, null, null},
            /* LOADED       */ {null, null, UN, null},
            /* UNLOADED     */ {null, LO, null, NI},
        };
        for (int i = 0; i < S.length; i++) {
            for (int j = 0; j < A.length; j++) {
                ModuleStateMachine m = new ModuleStateMachine(S[i]);
                ModuleState want = exp[i][j];
                T.eq(want, ModuleStateMachine.target(S[i], A[j]), "target(" + S[i] + "," + A[j] + ")");
                T.eq(want != null, m.can(A[j]), "can(" + S[i] + "," + A[j] + ")");
                try {
                    ModuleStateMachine.Ticket t = m.begin(A[j]);
                    T.check(want != null, "begin allowed but should be refused: " + S[i] + "/" + A[j]);
                    m.commit(t);
                    T.eq(want, m.state(), "state after " + S[i] + "/" + A[j]);
                } catch (IllegalTransitionException ex) {
                    T.check(want == null, "begin refused but should be allowed: " + S[i] + "/" + A[j]);
                    T.eq(S[i], m.state(), "state unchanged after refused " + A[j]);
                }
            }
        }
        // while one action is in flight nothing else is allowed
        ModuleStateMachine m = new ModuleStateMachine(ModuleState.UNLOADED);
        ModuleStateMachine.Ticket t = m.begin(ModuleAction.DELETE);
        for (ModuleAction a : A) T.check(!m.can(a), "nothing allowed while DELETE in flight, but " + a + " was");
        m.rollback(t);
        T.check(m.can(ModuleAction.DELETE) && m.can(ModuleAction.LOAD), "after rollback actions are available again");
        // tickets are single use
        ModuleStateMachine.Ticket t2 = m.begin(ModuleAction.LOAD);
        m.commit(t2);
        boolean threw = false;
        try { m.commit(t2); } catch (IllegalStateException e) { threw = true; }
        T.check(threw, "committing a ticket twice must fail");
    }

    // ---------------------------------------------------------------- spec walk-through
    static void happyPath() throws Exception {
        T.section("module flow: NOT_IMPORTED -> IMPORT -> LOAD -> UNLOAD -> DELETE");
        Fakes.FakeEngine e = new Fakes.FakeEngine();
        Fakes.FakeStorage s = new Fakes.FakeStorage();
        Fakes.MemStateStore st = new Fakes.MemStateStore(null);
        ModuleManager mm = newManager(e, s, st);

        ModuleSnapshot n = mm.snapshot();
        T.eq(ModuleState.NOT_IMPORTED, n.state, "initial state");
        T.check(n.canImport && !n.canLoad && !n.canUnload && !n.canDelete, "A) before import: only Import");

        T.check(mm.requestImport(Fakes.src("gemma-4-E2B-it.litertlm", 10)).accepted, "import accepted");
        Fakes.awaitIdle(mm);
        n = mm.snapshot();
        T.eq(ModuleState.IMPORTED, n.state, "after import");
        T.check(!n.canImport && n.canLoad && !n.canUnload && !n.canDelete, "B) after import: Load enabled, Unload/Delete disabled");

        T.check(mm.requestLoad().accepted, "load accepted");
        Fakes.awaitIdle(mm);
        n = mm.snapshot();
        T.eq(ModuleState.LOADED, n.state, "after load");
        T.check(!n.canLoad && n.canUnload && !n.canDelete && !n.canImport, "C) after load: Load disabled, Unload enabled, Delete disabled");
        ModuleManager.Result second = mm.requestLoad();
        T.check(!second.accepted, "C) second load refused");
        T.eq(1, e.loadCalls.get(), "engine.load called exactly once");
        ModuleManager.Result del = mm.requestDelete();
        T.check(!del.accepted, "C) delete refused while loaded");
        T.check(s.present, "model file still present after refused delete");

        T.check(mm.requestUnload().accepted, "unload accepted");
        Fakes.awaitIdle(mm);
        n = mm.snapshot();
        T.eq(ModuleState.UNLOADED, n.state, "after unload");
        T.check(n.canLoad && !n.canUnload && n.canDelete && !n.canImport, "D) after unload: Load + Delete enabled, Unload disabled");
        T.check(!mm.requestUnload().accepted, "D) unload while unloaded refused");

        T.check(mm.requestDelete().accepted, "delete accepted");
        Fakes.awaitIdle(mm);
        n = mm.snapshot();
        T.eq(ModuleState.NOT_IMPORTED, n.state, "E) after delete");
        T.check(n.canImport && !n.canLoad && !n.canUnload && !n.canDelete, "E) back to import-only");
        T.check(!s.present, "model file removed");
        T.eq(ModuleState.NOT_IMPORTED, st.s, "state persisted");

        // a full second cycle works (re-import after delete)
        mm.requestImport(Fakes.src("g.litertlm", 10)); Fakes.awaitIdle(mm);
        mm.requestLoad(); Fakes.awaitIdle(mm);
        T.eq(ModuleState.LOADED, mm.state(), "second cycle loads");
        // load -> unload -> load again is legal (spec D: Load enabled again)
        mm.requestUnload(); Fakes.awaitIdle(mm);
        T.check(mm.requestLoad().accepted, "reload after unload accepted");
        Fakes.awaitIdle(mm);
        T.eq(ModuleState.LOADED, mm.state(), "reloaded");
        T.eq(0, e.violations.get(), "engine never misused");
    }

    // ---------------------------------------------------------------- programmatic bypass
    static void bypassAttempts() throws Exception {
        T.section("protection: programmatic calls in every wrong state");
        for (ModuleState start : ModuleState.values()) {
            Fakes.FakeEngine e = new Fakes.FakeEngine();
            Fakes.FakeStorage s = new Fakes.FakeStorage();
            ModuleState persisted = start;
            s.present = start != ModuleState.NOT_IMPORTED;
            // drive to the wanted start state legitimately
            Fakes.MemStateStore st = new Fakes.MemStateStore(start == ModuleState.NOT_IMPORTED ? null : ModuleState.IMPORTED);
            ModuleManager mm = newManager(e, s, st);
            if (start == ModuleState.LOADED || start == ModuleState.UNLOADED) {
                mm.requestLoad(); Fakes.awaitIdle(mm);
                if (start == ModuleState.UNLOADED) { mm.requestUnload(); Fakes.awaitIdle(mm); }
            }
            T.eq(start, mm.state(), "reached start " + start);
            int loadsBefore = e.loadCalls.get();
            boolean filePresentBefore = s.present;
            for (ModuleAction a : ModuleAction.values()) {
                boolean legal = ModuleStateMachine.target(start, a) != null;
                if (legal) continue;
                ModuleManager.Result r;
                switch (a) {
                    case IMPORT: r = mm.requestImport(Fakes.src("x.litertlm", 5)); break;
                    case LOAD: r = mm.requestLoad(); break;
                    case UNLOAD: r = mm.requestUnload(); break;
                    default: r = mm.requestDelete(); break;
                }
                T.check(!r.accepted, "illegal " + a + " refused in " + start);
                Fakes.awaitIdle(mm);
                T.eq(start, mm.state(), "state untouched by refused " + a + " in " + start);
            }
            T.eq(loadsBefore, e.loadCalls.get(), "no engine.load leaked through in " + start);
            T.eq(filePresentBefore, s.present, "file untouched in " + start);
            T.eq(0, e.violations.get(), "engine violations in " + start);
        }
    }

    // ---------------------------------------------------------------- races
    static void concurrentDoubleLoad() throws Exception {
        T.section("protection: 64 threads hit Load at once");
        Fakes.FakeEngine e = new Fakes.FakeEngine();
        e.loadDelayMs = 40;
        Fakes.FakeStorage s = new Fakes.FakeStorage(); s.present = true;
        final ModuleManager mm = newManager(e, s, new Fakes.MemStateStore(ModuleState.IMPORTED));
        final AtomicInteger accepted = new AtomicInteger();
        ExecutorService ex = Executors.newFixedThreadPool(64);
        final CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<Future<?>>();
        for (int i = 0; i < 64; i++) fs.add(ex.submit(new Runnable() { public void run() {
            try { go.await(); } catch (InterruptedException ignored) { }
            if (mm.requestLoad().accepted) accepted.incrementAndGet();
        }}));
        go.countDown();
        for (Future<?> f : fs) f.get();
        Fakes.awaitIdle(mm);
        ex.shutdown();
        T.eq(1, accepted.get(), "exactly one of 64 concurrent loads accepted");
        T.eq(1, e.loadCalls.get(), "engine.load ran once");
        T.eq(0, e.violations.get(), "no violations");
        T.eq(ModuleState.LOADED, mm.state(), "loaded");
    }

    static void concurrentMixedHammer() throws Exception {
        T.section("protection: 8 threads x 3000 random actions (invariants checked every step)");
        final Fakes.FakeEngine e = new Fakes.FakeEngine();
        final Fakes.FakeStorage s = new Fakes.FakeStorage();
        final Fakes.MemStateStore st = new Fakes.MemStateStore(null);
        final ModuleManager mm = newManager(e, s, st);
        final AtomicInteger bad = new AtomicInteger();
        final AtomicInteger delWhileLoaded = new AtomicInteger();
        ExecutorService ex = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new ArrayList<Future<?>>();
        for (int t = 0; t < 8; t++) {
            final long seed = 1000 + t;
            fs.add(ex.submit(new Runnable() { public void run() {
                java.util.Random r = new java.util.Random(seed);
                for (int i = 0; i < 3000; i++) {
                    switch (r.nextInt(4)) {
                        case 0: mm.requestImport(Fakes.src("m.litertlm", 4)); break;
                        case 1: mm.requestLoad(); break;
                        case 2: mm.requestUnload(); break;
                        default: mm.requestDelete(); break;
                    }
                    ModuleSnapshot n = mm.snapshot();
                    // UI-derived invariants
                    if (n.canLoad && n.canUnload) bad.incrementAndGet();
                    if (n.state == ModuleState.LOADED && (n.canDelete || n.canLoad)) bad.incrementAndGet();
                    if (n.state == ModuleState.NOT_IMPORTED && (n.canLoad || n.canUnload || n.canDelete)) bad.incrementAndGet();
                    if (n.inFlight != null && (n.canImport || n.canLoad || n.canUnload || n.canDelete)) bad.incrementAndGet();
                    if (e.loaded && !s.present) delWhileLoaded.incrementAndGet();
                }
            }}));
        }
        for (Future<?> f : fs) f.get();
        Fakes.awaitIdle(mm);
        ex.shutdown();
        T.eq(0, bad.get(), "snapshot invariants violated");
        T.eq(0, delWhileLoaded.get(), "model deleted while engine had it loaded");
        T.eq(0, e.violations.get(), "engine violations (second load / unload mid-reply)");
        T.eq(mm.state() == ModuleState.LOADED, e.loaded, "manager state matches engine");
        System.out.println("  (engine.load calls: " + e.loadCalls.get() + ", unload calls: " + e.unloadCalls.get() + ")");
    }

    // ---------------------------------------------------------------- failures
    static void failuresRollBack() throws Exception {
        T.section("failure handling rolls the state back");
        Fakes.FakeEngine e = new Fakes.FakeEngine();
        Fakes.FakeStorage s = new Fakes.FakeStorage();
        ModuleManager mm = newManager(e, s, new Fakes.MemStateStore(null));

        s.failImport = true;
        mm.requestImport(Fakes.src("a.litertlm", 5)); Fakes.awaitIdle(mm);
        T.eq(ModuleState.NOT_IMPORTED, mm.state(), "failed import stays NOT_IMPORTED");
        T.check(mm.snapshot().message.startsWith("Import failed"), "import error message shown");
        T.check(mm.snapshot().canImport, "can retry import");
        s.failImport = false;
        mm.requestImport(Fakes.src("a.litertlm", 5)); Fakes.awaitIdle(mm);
        T.eq(ModuleState.IMPORTED, mm.state(), "retry works");

        e.failNextLoad = true;
        mm.requestLoad(); Fakes.awaitIdle(mm);
        T.eq(ModuleState.IMPORTED, mm.state(), "failed load stays IMPORTED");
        T.check(!e.loaded, "engine not left loaded after failed load");
        T.check(mm.snapshot().canLoad && !mm.snapshot().canDelete, "can retry load, delete still locked");
        mm.requestLoad(); Fakes.awaitIdle(mm);
        T.eq(ModuleState.LOADED, mm.state(), "load retry works");
        mm.requestUnload(); Fakes.awaitIdle(mm);

        s.failDelete = true;
        mm.requestDelete(); Fakes.awaitIdle(mm);
        T.eq(ModuleState.UNLOADED, mm.state(), "failed delete stays UNLOADED");
        T.check(s.present, "file still there");
        s.failDelete = false;
        mm.requestDelete(); Fakes.awaitIdle(mm);
        T.eq(ModuleState.NOT_IMPORTED, mm.state(), "delete retry works");
    }

    static void coldStartRestore() throws Exception {
        T.section("cold start reconciliation");
        T.eq(ModuleState.NOT_IMPORTED, ModuleManager.restore(ModuleState.LOADED, false), "file gone -> NOT_IMPORTED");
        T.eq(ModuleState.NOT_IMPORTED, ModuleManager.restore(null, false), "fresh install");
        T.eq(ModuleState.UNLOADED, ModuleManager.restore(ModuleState.LOADED, true), "process died while LOADED -> UNLOADED (nothing is in RAM)");
        T.eq(ModuleState.UNLOADED, ModuleManager.restore(ModuleState.UNLOADED, true), "UNLOADED stays (delete stays available)");
        T.eq(ModuleState.IMPORTED, ModuleManager.restore(ModuleState.IMPORTED, true), "IMPORTED stays");
        T.eq(ModuleState.IMPORTED, ModuleManager.restore(null, true), "orphan file recovered");
        T.eq(ModuleState.IMPORTED, ModuleManager.restore(ModuleState.NOT_IMPORTED, true), "import finished but state lost");
        // a real manager instance restores too
        Fakes.FakeStorage s = new Fakes.FakeStorage(); s.present = true;
        ModuleManager mm = newManager(new Fakes.FakeEngine(), s, new Fakes.MemStateStore(ModuleState.LOADED));
        T.eq(ModuleState.UNLOADED, mm.state(), "manager restores LOADED as UNLOADED");
        T.check(mm.snapshot().canLoad && mm.snapshot().canDelete && !mm.snapshot().canUnload, "buttons after restore");
    }

    static void replyBlocksUnload() throws Exception {
        T.section("an AI reply in progress blocks Unload (and Delete)");
        Fakes.FakeEngine e = new Fakes.FakeEngine();
        Fakes.FakeStorage s = new Fakes.FakeStorage(); s.present = true;
        ModuleManager mm = newManager(e, s, new Fakes.MemStateStore(ModuleState.IMPORTED));
        T.check(!mm.tryBeginReply(), "no reply possible before load");
        mm.requestLoad(); Fakes.awaitIdle(mm);
        T.check(mm.tryBeginReply(), "reply can start when loaded");
        T.check(!mm.tryBeginReply(), "second simultaneous reply refused");
        T.check(!mm.requestUnload().accepted, "unload refused during reply");
        T.check(!mm.snapshot().canUnload, "Unload button disabled during reply");
        mm.endReply();
        T.check(mm.requestUnload().accepted, "unload allowed after reply");
        Fakes.awaitIdle(mm);
        T.check(!mm.tryBeginReply(), "no reply after unload");
    }

    // ---------------------------------------------------------------- real files
    static void fileStorage() throws Exception {
        T.section("FileModelStorage (real files)");
        File dir = Files.createTempDirectory("gemma-test").toFile();
        File extra = new File(dir, "cache"); extra.mkdirs(); new File(extra, "x.bin").createNewFile();
        final long MIN = 1000;
        FileModelStorage st = new FileModelStorage(dir, "model.litertlm", ".litertlm", MIN, extra);

        T.check(!st.isModelPresent(), "empty at start");
        try { st.importModel(Fakes.src("model.gguf", 5000), null); T.check(false, "wrong extension accepted"); }
        catch (IOException ex) { T.check(true, "wrong extension rejected"); }
        try { st.importModel(Fakes.src("model.litertlm", 10), null); T.check(false, "tiny file accepted"); }
        catch (IOException ex) { T.check(true, "tiny file rejected"); }
        T.check(!st.isModelPresent(), "nothing stored after rejects");

        final int[] last = {-1};
        st.importModel(Fakes.src("Gemma-4-E2B.LITERTLM", 5000), new ProgressSink() {
            public void onProgress(int p) { last[0] = p; } });
        T.check(st.isModelPresent(), "imported");
        T.eq(100, last[0], "progress reached 100");
        T.eq(5000L, new File(st.modelPath()).length(), "size matches");
        T.check(!new File(dir, "model.litertlm.part").exists(), "no .part left behind");

        // interrupted copy leaves nothing
        FileModelStorage st2 = new FileModelStorage(Files.createTempDirectory("g2").toFile(), "model.litertlm", ".litertlm", MIN);
        ImportSource broken = new ImportSource() {
            public String displayName() { return "m.litertlm"; }
            public long sizeBytes() { return 5000; }
            public InputStream open() { return new InputStream() {
                int n = 0;
                public int read() throws IOException { if (n++ > 2000) throw new IOException("disk yanked"); return 1; }
                public int read(byte[] b, int o, int l) throws IOException { if (n > 2000) throw new IOException("disk yanked"); n += l; return l; }
            }; }
        };
        try { st2.importModel(broken, null); T.check(false, "broken import succeeded"); } catch (IOException ex) { T.check(true, "broken import throws"); }
        T.check(!st2.isModelPresent(), "broken import leaves no model");
        T.eq(0, st2.modelPath() == null ? 1 : new File(st2.modelPath()).getParentFile().list().length, "broken import leaves no files");

        // size mismatch (truncated copy) is rejected
        FileModelStorage st3 = new FileModelStorage(Files.createTempDirectory("g3").toFile(), "model.litertlm", ".litertlm", MIN);
        ImportSource lying = new ImportSource() {
            public String displayName() { return "m.litertlm"; }
            public long sizeBytes() { return 9000; }
            public InputStream open() { return new java.io.ByteArrayInputStream(new byte[4000]); }
        };
        try { st3.importModel(lying, null); T.check(false, "truncated import accepted"); } catch (IOException ex) { T.check(true, "truncated import rejected"); }
        T.check(!st3.isModelPresent(), "truncated import leaves no model");

        st.deleteModel();
        T.check(!st.isModelPresent(), "deleted");
        T.check(!extra.exists(), "extra cache dir removed with the model");
        st.deleteModel();   // idempotent
        T.check(true, "second delete does not throw");
    }
}
