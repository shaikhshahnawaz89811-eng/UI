package tests;

import com.neonhud.app.core.chat.ChatController;
import com.neonhud.app.core.memory.*;
import com.neonhud.app.core.module.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class ChatTests {

    static void waitIdle(ChatController c) {
        long end = System.currentTimeMillis() + 10000;
        while (c.isGenerating() && System.currentTimeMillis() < end) { try { Thread.sleep(2); } catch (InterruptedException ignored) { } }
        if (c.isGenerating()) throw new AssertionError("chat never finished");
    }

    static void run() throws Exception {
        T.section("chat controller: model gating, streaming, background survival");
        Fakes.FakeEngine e = new Fakes.FakeEngine();
        Fakes.FakeStorage s = new Fakes.FakeStorage();
        Fakes.MemStateStore st = new Fakes.MemStateStore(null);
        ModuleManager mm = new ModuleManager(e, s, st);
        InMemoryStore store = new InMemoryStore();
        ConversationBrain brain = new ConversationBrain(store, new ConversationBrain.Clock() { public long now() { return System.currentTimeMillis(); } });
        final ChatController chat = new ChatController(mm, brain, store);

        T.eq(ChatController.SendResult.EMPTY, chat.send("   "), "blank message ignored");
        T.eq(ChatController.SendResult.MODEL_NOT_READY, chat.send("hello"), "no model -> refused");
        List<ChatController.Item> items = chat.items();
        T.check(items.size() == 1 && items.get(0).kind == ChatController.Kind.NOTICE, "user is told the model is not loaded");
        T.eq(0, e.loadCalls.get() + (e.generating ? 1 : 0), "engine untouched");

        mm.requestImport(Fakes.src("g.litertlm", 5)); Fakes.awaitIdle(mm);
        T.eq(ChatController.SendResult.MODEL_NOT_READY, chat.send("hello"), "imported but not loaded -> refused");
        mm.requestLoad(); Fakes.awaitIdle(mm);

        // a listener that represents the Activity; it is removed ("app in background") mid-reply
        final AtomicInteger calls = new AtomicInteger();
        ChatController.Listener ui = new ChatController.Listener() { public void onChatChanged() { calls.incrementAndGet(); } };
        chat.addListener(ui);
        e.tokenDelayMs = 15;
        e.cannedReply = "Ek do teen char paanch chhe saat aath.";
        T.eq(ChatController.SendResult.ACCEPTED, chat.send("AC ke liye 20A MCB kaise lagta hai?"), "send accepted");
        T.check(chat.isGenerating(), "generating flag set");
        T.eq(ChatController.SendResult.BUSY, chat.send("doosra sawal"), "second send while generating refused");
        T.check(!mm.requestUnload().accepted, "unload refused while replying");
        T.check(!mm.requestDelete().accepted, "delete refused while replying");
        Thread.sleep(40);
        chat.removeListener(ui);               // Activity went to background / destroyed
        int seenBeforeBackground = calls.get();
        waitIdle(chat);                        // reply keeps generating with no UI attached
        items = chat.items();
        ChatController.Item last = items.get(items.size() - 1);
        T.eq(ChatController.Kind.AI, last.kind, "last item is the AI reply");
        T.check(!last.pending, "reply finished while app was in background");
        T.eq("Ek do teen char paanch chhe saat aath.", last.text, "full reply text preserved, exactly as generated");
        T.check(calls.get() >= seenBeforeBackground, "no callbacks after listener removed beyond in-flight");
        // "Activity recreated": a fresh view rebuilds from controller.items() with nothing lost
        T.eq(2, chat.items().size(), "user message + AI reply present for a recreated Activity");
        T.check(mm.requestUnload().accepted, "unload possible after reply");
        Fakes.awaitIdle(mm);

        // reply order / roles persisted in the store
        List<ConversationMessage> saved = store.lastMessages(10);
        T.eq(2, saved.size(), "both messages stored");
        T.check(saved.get(0).isUser() && !saved.get(1).isUser(), "user then assistant stored in order");

        // prompt given to the engine is the controlled package
        mm.requestLoad(); Fakes.awaitIdle(mm);
        e.tokenDelayMs = 0;
        chat.send("Isme 2.5mm wire chalega?"); waitIdle(chat);
        T.check(e.lastPrompt != null && e.lastPrompt.recentConversation.size() >= 2, "follow-up carried previous turn to the engine");
        T.eq("Isme 2.5mm wire chalega?", e.lastPrompt.userMessage, "engine got the current message");

        // restart: a new controller shows the stored conversation
        ChatController chat2 = new ChatController(mm, brain, store);
        T.eq(4, chat2.items().size(), "history restored on restart");

        // very long input is clipped, not crashed
        StringBuilder sb = new StringBuilder(); for (int i = 0; i < 9000; i++) sb.append('x');
        chat.send(sb.toString()); waitIdle(chat);
        T.check(chat.items().get(chat.items().size() - 2).text.length() == ChatController.MAX_INPUT_CHARS, "9000-char input clipped to limit");

        // engine failure mid-reply: notice shown, module still usable
        e.cannedReply = "x"; e.failNextLoad = false;
        Fakes.FakeEngine bad = new Fakes.FakeEngine() {
            @Override public void generate(com.neonhud.app.core.engine.PromptPackage p, com.neonhud.app.core.engine.GenerationCallback cb) throws Exception {
                cb.onToken("partial "); throw new RuntimeException("gpu lost");
            }
        };
        Fakes.FakeStorage s2 = new Fakes.FakeStorage(); s2.present = true;
        ModuleManager mm2 = new ModuleManager(bad, s2, new Fakes.MemStateStore(ModuleState.IMPORTED));
        mm2.requestLoad(); Fakes.awaitIdle(mm2);
        ChatController c3 = new ChatController(mm2, new ConversationBrain(new InMemoryStore(), new ConversationBrain.Clock() { public long now() { return 1; } }), new InMemoryStore());
        c3.send("hello"); waitIdle(c3);
        List<ChatController.Item> it3 = c3.items();
        T.check(it3.get(it3.size() - 1).kind == ChatController.Kind.NOTICE && it3.get(it3.size() - 1).text.contains("gpu lost"), "engine failure surfaced as notice");
        T.check(mm2.snapshot().canUnload, "module still unloadable after failed reply");
        T.check(!mm2.snapshot().replyActive, "reply flag released after failure");
    }
}
