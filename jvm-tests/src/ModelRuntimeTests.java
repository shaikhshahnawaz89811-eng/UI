package tests;

import com.neonhud.app.core.chat.ChatController;
import com.neonhud.app.core.chat.ModelTaskRouter;
import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.memory.ConversationBrain;
import com.neonhud.app.core.memory.InMemoryStore;
import com.neonhud.app.core.module.ModuleManager;
import com.neonhud.app.core.module.ModuleState;
import com.neonhud.app.core.module.ModelRuntimeCoordinator;
import com.neonhud.app.core.coder.CoderSpec;

import java.util.Collections;

final class ModelRuntimeTests {

    static void run() throws Exception {
        T.section("runtime: one-model-at-a-time lifecycle + conversation routing");
        Fakes.FakeEngine ge = new Fakes.FakeEngine();
        Fakes.FakeEngine ce = new Fakes.FakeEngine();
        Fakes.FakeStorage gs = new Fakes.FakeStorage();
        Fakes.FakeStorage cs = new Fakes.FakeStorage();
        ModuleManager gemma = new ModuleManager(ge, gs, new Fakes.MemStateStore(null), "Gemma 4 E2B", "rt-gemma");
        ModuleManager coder = new ModuleManager(ce, cs, new Fakes.MemStateStore(null), CoderSpec.DISPLAY_NAME, "rt-coder");
        gemma.requestImport(Fakes.src("g.litertlm", 5));
        coder.requestImport(Fakes.src("q.gguf", 5));
        Fakes.awaitIdle(gemma);
        Fakes.awaitIdle(coder);

        ModelRuntimeCoordinator rt = new ModelRuntimeCoordinator(gemma, coder);
        T.check(rt.ensureLoaded(ModelRuntimeCoordinator.Target.GEMMA, null), "Gemma can become the idle/default model");
        T.eq(ModuleState.LOADED, gemma.state(), "Gemma is loaded");
        T.check(coder.state() != ModuleState.LOADED, "coder stays offline while Gemma is active");

        T.check(rt.beginReply(ModelRuntimeCoordinator.Target.CODER, null), "coding task takes runtime ownership");
        T.eq(ModuleState.UNLOADED, gemma.state(), "Gemma unloads before coder loads");
        T.eq(ModuleState.LOADED, coder.state(), "coder loads after Gemma is offline");
        T.eq(ModelRuntimeCoordinator.Target.CODER, rt.snapshot().active, "runtime marks coder active");
        T.check(!rt.ensureLoaded(ModelRuntimeCoordinator.Target.GEMMA, null), "Gemma cannot interrupt an active coder reply");
        T.eq(ModuleState.LOADED, coder.state(), "coder remains loaded during its reply");

        rt.endReply(ModelRuntimeCoordinator.Target.CODER, null);
        T.eq(ModuleState.UNLOADED, coder.state(), "coder unloads when its task ends");
        T.eq(ModuleState.LOADED, gemma.state(), "Gemma is loaded before coder is considered offline");
        T.eq(ModelRuntimeCoordinator.Target.GEMMA, rt.snapshot().active, "runtime returns to Gemma after coder work");
        T.check(ge.loaded && !ce.loaded, "engines confirm exactly one resident model");

        // A failed coder load must not strand the device with both models offline.
        ce.failNextLoad = true;
        T.check(!rt.beginReply(ModelRuntimeCoordinator.Target.CODER, null), "failed coder load is reported");
        T.eq(ModuleState.LOADED, gemma.state(), "Gemma is restored after failed coder handoff");
        T.check(coder.state() != ModuleState.LOADED, "failed coder handoff leaves coder offline");
        T.check(ge.loaded && !ce.loaded, "failed handoff still preserves one-model invariant");

        // Core conversation route: coding request selects coder, normal request selects Gemma.
        T.eq(ModelTaskRouter.Target.CODER,
                ModelTaskRouter.route("is code ka bug fix karo", Collections.<Attachment>emptyList(), ModelTaskRouter.Target.GEMMA),
                "coding message routes to coder");
        T.eq(ModelTaskRouter.Target.GEMMA,
                ModelTaskRouter.route("Word document banao", Collections.<Attachment>emptyList(), ModelTaskRouter.Target.GEMMA),
                "Office creation stays on Gemma");
        T.eq(ModelTaskRouter.Target.CODER,
                ModelTaskRouter.route("check this file", Collections.singletonList(new Attachment(Attachment.Kind.ZIP, "project.zip", 1, "zip")), ModelTaskRouter.Target.GEMMA),
                "project ZIP review routes to coder");

        // Runtime-managed ChatController does not require a pre-loaded model and returns to Gemma after coder work.
        InMemoryStore gStore = new InMemoryStore();
        InMemoryStore cStore = new InMemoryStore();
        ModuleManager g2 = new ModuleManager(new Fakes.FakeEngine(), new Fakes.FakeStorage(), new Fakes.MemStateStore(ModuleState.IMPORTED), "Gemma 4 E2B", "chat-rt-gemma");
        ModuleManager c2 = new ModuleManager(new Fakes.FakeEngine(), new Fakes.FakeStorage(), new Fakes.MemStateStore(ModuleState.IMPORTED), CoderSpec.DISPLAY_NAME, "chat-rt-coder");
        // Make the model files visible for the restored IMPORTED state.
        // The constructor reconciles a missing file to NOT_IMPORTED, so import is required here.
        g2.requestImport(Fakes.src("g.litertlm", 5)); Fakes.awaitIdle(g2);
        c2.requestImport(Fakes.src("q.gguf", 5)); Fakes.awaitIdle(c2);
        ModelRuntimeCoordinator rt2 = new ModelRuntimeCoordinator(g2, c2);
        ChatController gChat = new ChatController(g2,
                new ConversationBrain(gStore, () -> 1), gStore,
                "Gemma 4 E2B", "chat-rt-g", rt2, ModelRuntimeCoordinator.Target.GEMMA);
        ChatController cChat = new ChatController(c2,
                new ConversationBrain(cStore, () -> 1, CoderSpec.SYSTEM_BASE), cStore,
                CoderSpec.DISPLAY_NAME, "chat-rt-c", rt2, ModelRuntimeCoordinator.Target.CODER);
        T.eq(ChatController.SendResult.ACCEPTED, gChat.send("hello"), "runtime-managed Gemma chat accepts while model is offline");
        ChatTests.waitIdle(gChat);
        T.eq(ModuleState.LOADED, g2.state(), "Gemma is loaded for normal conversation");
        T.check(c2.state() != ModuleState.LOADED, "coder is still offline after Gemma answer");
        cChat.setHandoffContext(gChat.recentHandoffContext());
        T.eq(ChatController.SendResult.ACCEPTED, cChat.send("Python code likho"), "runtime-managed coder chat accepts coding task");
        ChatTests.waitIdle(cChat);
        T.eq(ModuleState.LOADED, g2.state(), "Gemma is automatically restored after coder answer");
        T.check(c2.state() != ModuleState.LOADED, "coder is offline after coder answer");
        T.check(((Fakes.FakeEngine) c2.engine()).loadCalls.get() >= 1, "coder engine really loaded during handoff");
    }
}
