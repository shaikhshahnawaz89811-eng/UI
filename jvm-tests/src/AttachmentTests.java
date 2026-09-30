package tests;

import com.neonhud.app.core.chat.ChatController;
import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.engine.AttachmentLoader;
import com.neonhud.app.core.memory.*;
import com.neonhud.app.core.module.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class AttachmentTests {

    static void run() throws Exception {
        T.section("attachments: send with files, loader, failures, memory");
        Fakes.FakeEngine e = new Fakes.FakeEngine();
        ModuleManager mm = new ModuleManager(e, new Fakes.FakeStorage(), new Fakes.MemStateStore(null));
        InMemoryStore store = new InMemoryStore();
        ConversationBrain brain = new ConversationBrain(store, new ConversationBrain.Clock() { public long now() { return System.currentTimeMillis(); } });
        ChatController chat = new ChatController(mm, brain, store);
        mm.requestImport(Fakes.src("g.litertlm", 5)); Fakes.awaitIdle(mm);
        mm.requestLoad(); Fakes.awaitIdle(mm);

        final Attachment zip = new Attachment(Attachment.Kind.ZIP, "project.zip", 12_400_000L, "content://zip");
        final Attachment pdf = new Attachment(Attachment.Kind.PDF, "doc.pdf", 4_200_000L, "content://pdf");
        final Attachment img = new Attachment(Attachment.Kind.IMAGE, "image.png", 2_800_000L, "content://img");

        T.eq(ChatController.SendResult.EMPTY, chat.send("  ", Collections.<Attachment>emptyList()), "no text and no files -> ignored");

        chat.setAttachmentLoader(new AttachmentLoader() {
            public Attachment load(Attachment a) throws Exception {
                if (a == pdf) throw new Exception("password protected");
                List<byte[]> imgs = a.kind == Attachment.Kind.IMAGE ? Arrays.asList(new byte[]{1, 2, 3}) : Collections.<byte[]>emptyList();
                return a.loaded("ATTACHED " + a.kind + ": " + a.name, imgs);
            }
        });
        T.eq(ChatController.SendResult.ACCEPTED, chat.send("isko dekho", Arrays.asList(zip, pdf, img)), "text + 3 files accepted");
        ChatTests.waitIdle(chat);
        T.check(e.lastPrompt != null && e.lastPrompt.attachments.size() == 3, "engine got all 3 attachments");
        T.check(e.lastPrompt.attachments.get(0).text.contains("project.zip"), "zip payload reached the engine");
        T.check(e.lastPrompt.attachments.get(1).text.contains("COULD NOT BE READ") && e.lastPrompt.attachments.get(1).text.contains("password"),
                "unreadable pdf is passed on with a note, turn still completes");
        T.eq(1, e.lastPrompt.attachments.get(2).images.size(), "image bytes reached the engine");
        List<ChatController.Item> items = chat.items();
        ChatController.Item user = items.get(items.size() - 2);
        T.eq(ChatController.Kind.USER, user.kind, "user bubble present");
        T.eq("isko dekho", user.text, "bubble shows only what was typed");
        T.eq(3, user.attachments.size(), "bubble keeps the 3 file cards");
        T.check(user.time > 0, "bubble has a send time");
        T.check(items.get(items.size() - 1).kind == ChatController.Kind.AI, "reply arrived");

        // files only, nothing typed
        T.eq(ChatController.SendResult.ACCEPTED, chat.send("", Arrays.asList(img)), "files without text accepted");
        ChatTests.waitIdle(chat);
        T.check(e.lastPrompt.userMessage.contains("attached file"), "default ask used when nothing typed");

        // memory keeps names, never file content
        boolean namesStored = false, contentStored = false;
        for (ConversationMessage m : store.lastMessages(50)) {
            if (m.content.contains("[Attached: project.zip, doc.pdf, image.png]")) namesStored = true;
            if (m.content.contains("ATTACHED ZIP")) contentStored = true;
        }
        T.check(namesStored, "memory stores the file names");
        T.check(!contentStored, "memory never stores file content");

        // more than the limit is cut
        List<Attachment> many = new ArrayList<Attachment>();
        for (int i = 0; i < 9; i++) many.add(new Attachment(Attachment.Kind.IMAGE, "p" + i + ".jpg", 10, "content://p" + i));
        chat.send("sab dekho", many);
        ChatTests.waitIdle(chat);
        T.eq(ChatController.MAX_ATTACHMENTS, e.lastPrompt.attachments.size(), "attachments capped");

        // no loader plugged in: still works, names only
        chat.setAttachmentLoader(null);
        T.eq(ChatController.SendResult.ACCEPTED, chat.send("bina loader", Arrays.asList(zip)), "works without a loader");
        ChatTests.waitIdle(chat);
        T.eq(1, e.lastPrompt.attachments.size(), "file passed through unloaded");

        T.eq("12.4 MB", Attachment.formatSize(13_000_000L), "size label MB");
        T.eq("2.0 KB", Attachment.formatSize(2048), "size label KB");
        T.eq("", Attachment.formatSize(-1), "unknown size hidden");
        T.eq(0, e.violations.get(), "engine never misused");
    }
}
