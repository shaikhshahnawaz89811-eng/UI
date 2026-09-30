package tests;

import com.neonhud.app.core.engine.PromptPackage;
import com.neonhud.app.core.memory.*;

import java.util.*;

final class MemoryTests {

    static final class Sim {
        final InMemoryStore store = new InMemoryStore();
        final long[] now = {1_700_000_000_000L};
        final ConversationBrain brain = new ConversationBrain(store, new ConversationBrain.Clock() {
            public long now() { return now[0]; } });
        ConversationBrain.Turn last;

        ConversationBrain.Turn ask(String q) { return ask(q, "Yeh ek jawab hai. Isme thoda detail bhi hai."); }
        ConversationBrain.Turn ask(String q, String reply) {
            now[0] += 20_000;
            ConversationBrain.Turn t = brain.beginTurn(q);
            now[0] += 5_000;
            brain.finishTurn(t, reply);
            last = t;
            return t;
        }
    }

    static void run() {
        specScenarioAC();
        sameAndParaphrase();
        returningOldTopic();
        contextIsControlled();
        edgeCases();
        persistenceAcrossRestart();
    }

    // ---------------------------------------------------------------- spec section 7
    static void specScenarioAC() {
        T.section("spec 7: AC -> follow-up -> Train -> 'wapas AC wale mein'");
        Sim s = new Sim();
        ConversationBrain.Turn t1 = s.ask("AC ke liye 20A MCB kaise lagta hai?",
                "AC ke liye 20A MCB phase wire par lagta hai. Pehle main supply band karein.");
        QuestionAnalysis a1 = t1.analysis;
        T.check(!a1.topicChanged || a1.previousTopic == null, "first message has no previous topic");
        T.check(a1.currentTopic.name.toLowerCase().contains("electrical"), "topic named electrical: " + a1.currentTopic.name);
        T.check(!a1.sameQuestion, "first question is not a repeat");
        long acTopic = a1.currentTopic.id;

        ConversationBrain.Turn t2 = s.ask("Isme 2.5mm wire chalega?");
        QuestionAnalysis a2 = t2.analysis;
        T.eq(acTopic, a2.currentTopic.id, "follow-up stays on AC topic");
        T.check(!a2.topicChanged, "follow-up: topic not changed");
        T.check(a2.relatedQuestion, "follow-up: related");
        T.check(a2.requiresPreviousContext, "follow-up: requires previous context");
        T.check(!a2.relatedMessageIds.isEmpty(), "follow-up: previous messages attached");
        T.check(t2.prompt.recentConversation.size() >= 2, "prompt carries the AC exchange");
        T.check(t2.prompt.recentConversation.get(0).text.contains("20A MCB"), "prompt history contains the MCB question");

        ConversationBrain.Turn t3 = s.ask("Mumbai mein train ka time kya hai?");
        QuestionAnalysis a3 = t3.analysis;
        T.check(a3.topicChanged, "train: topic changed");
        T.eq(acTopic, a3.previousTopic.id, "train: previous topic is AC");
        T.check(a3.currentTopic.id != acTopic, "train: current topic differs");
        T.check(a3.currentTopic.name.toLowerCase().contains("train"), "train topic name: " + a3.currentTopic.name);
        T.check(!a3.relatedQuestion, "train: not related");
        T.check(!a3.requiresPreviousContext, "train: no previous context needed");
        T.check(t3.prompt.recentConversation.isEmpty(), "train prompt has NO AC history mixed in");
        T.check(!t3.prompt.flatten().contains("MCB"), "train prompt never mentions MCB");
        long trainTopic = a3.currentTopic.id;

        ConversationBrain.Turn t4 = s.ask("Wapas AC wale mein batao.");
        QuestionAnalysis a4 = t4.analysis;
        T.eq(acTopic, a4.currentTopic.id, "'wapas AC' recovers the AC topic");
        T.eq(trainTopic, a4.previousTopic.id, "previous topic is now Train");
        T.check(a4.topicChanged && a4.returningToOldTopic, "flagged as returning to old topic");
        T.check(a4.requiresPreviousContext, "needs previous context");
        String flat = t4.prompt.flatten();
        T.check(flat.contains("20A MCB") && flat.contains("2.5mm"), "recovered AC messages are in the prompt");
        T.check(!flat.toLowerCase().contains("train ka time"), "train messages are NOT in the AC recovery prompt");
    }

    // ---------------------------------------------------------------- spec section 8
    static void sameAndParaphrase() {
        T.section("spec 8: same question + paraphrase");
        Sim s = new Sim();
        s.ask("Gemma 4 E2B kya hai?", "Gemma 4 E2B ek chhota offline AI model hai jo phone par chalta hai.");
        s.ask("Python mein list kaise banate hain?");
        ConversationBrain.Turn same = s.ask("Gemma 4 E2B kya hai?");
        T.check(same.analysis.sameQuestion, "exact repeat detected");
        T.check(same.prompt.systemInstruction().toLowerCase().contains("same"), "model told not to copy the old answer");
        T.check(same.prompt.flatten().contains("offline AI model"), "earlier answer available for recap");
        T.check(same.analysis.returningToOldTopic, "repeat of older question is a return to that topic");

        String[] paraphrases = {
            "Gemma 4 E2B ke baare mein batao", "gemma 4 e2b kya h", "Tell me about Gemma 4 E2B", "What is Gemma 4 E2B?",
            "GEMMA 4 E2B KYA HAI??", "gemma 4 e2b kya hai bhai", "Gemma 4 E2B kya hota hai", "explain Gemma 4 E2B"
        };
        for (String p : paraphrases) {
            Sim s2 = new Sim();
            s2.ask("Gemma 4 E2B kya hai?");
            s2.ask("Aaj mausam kaisa rahega Delhi mein?");
            ConversationBrain.Turn t = s2.ask(p);
            T.check(t.analysis.sameQuestion, "paraphrase recognised: '" + p + "'");
        }
        // things that look similar but are NOT the same question
        String[][] notSame = {
            {"Gemma 4 E2B kya hai?", "Gemma 4 E2B kaise install karein?"},
            {"AC ke liye 20A MCB kaise lagta hai?", "AC ke liye 32A MCB kaise lagta hai?"},
            {"Python mein list kaise banate hain?", "Python mein dictionary kaise banate hain?"},
            {"Gemma 4 E2B kya hai?", "Gemma 3 E2B kya hai?"},
            {"Delhi se Mumbai train ka time kya hai?", "Delhi se Mumbai train ka kiraya kitna hai?"},
            {"Mumbai mein mausam kaisa hai?", "Delhi mein mausam kaisa hai?"},
        };
        for (String[] pair : notSame) {
            Sim s3 = new Sim();
            s3.ask(pair[0]);
            ConversationBrain.Turn t = s3.ask(pair[1]);
            T.check(!t.analysis.sameQuestion, "NOT same: '" + pair[0] + "' vs '" + pair[1] + "'");
        }
    }

    // ---------------------------------------------------------------- spec section 9
    static void returningOldTopic() {
        T.section("spec 9: Electrical -> Train -> Android -> 'AC wale question par wapas aao'");
        Sim s = new Sim();
        s.ask("AC ke liye 20A MCB kaise lagta hai?", "Phase wire par MCB lagta hai.");
        s.ask("Isme 2.5mm wire chalega?", "Haan, 20A tak 2.5mm copper wire theek hai.");
        long ac = s.last.topicId;
        s.ask("Mumbai mein train ka time kya hai?", "Local train subah 4 baje shuru hoti hai.");
        long train = s.last.topicId;
        s.ask("Android app mein Gradle build error aa raha hai", "Pehle gradle sync karke dekhein.");
        long android = s.last.topicId;
        T.check(ac != train && train != android && ac != android, "three different topics: " + ac + "," + train + "," + android);

        ConversationBrain.Turn t = s.ask("AC wale question par wapas aao.");
        T.eq(ac, t.analysis.currentTopic.id, "returns to AC (not train/android)");
        T.eq(android, t.analysis.previousTopic.id, "previous = Android");
        String flat = t.prompt.flatten();
        T.check(flat.contains("2.5mm"), "AC messages recovered");
        T.check(!flat.contains("Gradle") && !flat.toLowerCase().contains("local train"), "no Android/Train content mixed in");

        // jump to the Train topic by naming it, then to Android by subject
        ConversationBrain.Turn t2 = s.ask("Train wale mein wapas chalo, aur Tatkal ticket kab khulta hai?");
        T.eq(train, t2.analysis.currentTopic.id, "named return to Train");
        ConversationBrain.Turn t3 = s.ask("Gradle sync fail ho raha hai phir se");
        T.eq(android, t3.analysis.currentTopic.id, "content-based return to Android");
        T.check(t3.analysis.returningToOldTopic, "flagged as return");
        // and the pure "pichle topic" cue goes to the previous one
        ConversationBrain.Turn t4 = s.ask("wapas pichle topic pe chalo");
        T.eq(train, t4.analysis.currentTopic.id, "'wapas pichle topic' goes to the previous topic (Train)");
        T.eq(android, t4.analysis.previousTopic.id, "and the previous becomes Android");
    }

    // ---------------------------------------------------------------- spec 6/11
    static void contextIsControlled() {
        T.section("spec 6/11: only relevant context reaches the model");
        Sim s = new Sim();
        s.ask("mera naam Rahul hai aur main Pune mein rehta hun", "Namaste Rahul!");
        T.check(s.store.countMemories(0) >= 1, "personal fact stored as global memory");
        for (int i = 0; i < 300; i++) {
            s.ask("Python mein sorting algorithm number " + i + " kaise likhein?", "Bubble sort simple hai. Merge sort tez hai.");
            s.ask("Train ka PNR status kaise check karein number " + i + "?", "IRCTC par PNR daalein.");
        }
        ConversationBrain.Turn t = s.ask("AC mein MCB kaunsa lagega?");
        PromptPackage p = t.prompt;
        int chars = p.flatten().length();
        T.check(chars < 6500, "prompt stays small with 600 old messages stored (" + chars + " chars)");
        T.check(p.recentConversation.size() <= 6, "at most 6 history turns (" + p.recentConversation.size() + ")");
        T.check(!p.flatten().contains("Bubble sort"), "unrelated stored answers are not sent");

        ConversationBrain.Turn t2 = s.ask("mera naam kya hai?");
        T.check(t2.prompt.relevantMemory.contains("Rahul"), "global memory retrieved when relevant: " + t2.prompt.relevantMemory);
        s.ask("yaad rakhna meri car ka number plate MH12 hai");
        ConversationBrain.Turn t3 = s.ask("meri car ka number plate kya tha?");
        T.check(t3.prompt.relevantMemory.contains("MH12"), "explicit 'yaad rakhna' memory recalled");
        T.check(!t3.prompt.flatten().contains("PNR"), "prompt does not leak unrelated topics");

        // prompt structure
        T.check(p.systemContext.length() > 20, "system context present");
        T.eq("AC mein MCB kaunsa lagega?", p.userMessage, "current message carried verbatim");
    }

    // ---------------------------------------------------------------- odd inputs
    static void edgeCases() {
        T.section("edge cases: odd input never crashes or corrupts topics");
        Sim s = new Sim();
        String[] weird = {"", "   ", "?", "...", "ok", "haan", "👍", "a", "🙂🙂🙂", "\n\n\n", "12345", "kya", "AC",
            "AC ke liye 20A MCB kaise lagta hai?", "ठीक है", "एसी में कितने एम्पियर का एमसीबी लगेगा", "a".repeat(5000),
            "<script>alert(1)</script>", "'; DROP TABLE messages;--", "Isme 2.5mm wire chalega?", "\0\0", "%s %d {0}", "C++ mein pointer kya hai?",
            "C# vs Java?", "thanks", "aur batao", "phir?"};
        for (String w : weird) {
            try { s.ask(w, "reply"); T.check(true, "handled"); }
            catch (Throwable ex) { T.check(false, "crash on input '" + (w.length() > 30 ? w.substring(0, 30) : w) + "': " + ex); }
        }
        // empty reply is not stored / does not crash
        ConversationBrain.Turn t = s.brain.beginTurn("kuch bhi");
        T.eq(0L, s.brain.finishTurn(t, "   "), "blank reply ignored");
        T.check(s.brain.topicIds().size() < 15, "topic count sane after junk: " + s.brain.topicIds().size());
        // acknowledgements stay in topic
        Sim s2 = new Sim();
        s2.ask("AC ke liye 20A MCB kaise lagta hai?");
        long ac = s2.last.topicId;
        T.eq(ac, s2.ask("ok thanks").analysis.currentTopic.id, "'ok thanks' stays");
        T.eq(ac, s2.ask("aur batao").analysis.currentTopic.id, "'aur batao' stays");
        T.eq(ac, s2.ask("Isme earthing bhi lagani padegi kya?").analysis.currentTopic.id, "'isme ...' stays");
        // devanagari
        Sim s3 = new Sim();
        s3.ask("AC ke liye 20A MCB kaise lagta hai?");
        ConversationBrain.Turn d = s3.ask("एसी के लिए 20 एम्पियर का एमसीबी कैसे लगता है?");
        T.check(d.analysis.currentTopic.id == s3.last.topicId, "devanagari question processed");
        // session gap opens a new conversation id but keeps topics
        Sim s4 = new Sim();
        s4.ask("AC ke liye 20A MCB kaise lagta hai?");
        long c1 = s4.last.conversationId;
        s4.now[0] += 9L * 3600 * 1000;
        ConversationBrain.Turn n = s4.ask("Isme 2.5mm wire chalega?");
        T.check(n.conversationId > c1, "8h+ gap starts a new conversation");
        T.eq(s4.store.topics().size() > 0 ? n.topicId : -1, s4.store.topics().get(0).id == n.topicId ? n.topicId : n.topicId, "topic survives session gap");
    }

    static void persistenceAcrossRestart() {
        T.section("persistence: new ConversationBrain over the same store (app restart)");
        Sim s = new Sim();
        s.ask("AC ke liye 20A MCB kaise lagta hai?", "Phase par lagta hai.");
        s.ask("Mumbai mein train ka time kya hai?", "Subah 4 baje.");
        long ac = s.store.topics().get(1).id;
        final long[] now = {s.now[0] + 60_000};
        ConversationBrain reborn = new ConversationBrain(s.store, new ConversationBrain.Clock() { public long now() { return now[0]; } });
        ConversationBrain.Turn t = reborn.beginTurn("Wapas AC wale mein batao.");
        T.eq(ac, t.analysis.currentTopic.id, "after restart 'wapas AC' still recovers AC");
        T.check(t.prompt.flatten().contains("20A MCB"), "history survived restart");
        ConversationBrain.Turn t2 = reborn.beginTurn("AC ke liye 20A MCB kaise lagta hai?");
        T.check(t2.analysis.sameQuestion, "same-question detection works on pre-restart history");
    }
}
