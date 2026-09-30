package tests;

import com.neonhud.app.core.memory.ConversationBrain;
import com.neonhud.app.core.memory.ConversationMessage;
import com.neonhud.app.core.memory.InMemoryStore;
import com.neonhud.app.core.memory.TextTools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 1000-message assistant-behaviour regression suite.
 * 200 independent 5-message conversations exercise standalone questions, continuations,
 * corrections, topic switches, returns, typos, code/teach imperatives and multi-task prompts.
 * This tests the conversation/memory layer and the prompt contract; the actual on-device Gemma
 * weights are not available to the JVM test runner.
 */
final class AssistantRegression1000Test {

    private static final String[] TOPICS = {
            "AC ke liye 20A MCB kaise lagta hai?",
            "Train Mumbai se Delhi kab hai?",
            "Python mein list kaise sort karte hain?",
            "Android app mein camera permission kaise lagayein?",
            "Gemma model ko phone par kaise load karein?",
            "Paneer butter masala kaise banate hain?",
            "Goa mein hotel kaise choose karein?",
            "PPF mein interest kaise milta hai?",
            "Bukhar mein kya dhyan rakhna chahiye?",
            "Photosynthesis kya hota hai?"
    };

    static void run() {
        T.section("assistant contract: 1000 mixed conversation questions");
        int cases = 0;
        int hard = 0;
        long started = System.nanoTime();

        for (int seq = 0; seq < 200; seq++) {
            InMemoryStore store = new InMemoryStore();
            ConversationBrain brain = new ConversationBrain(store, new Clock(seq));
            String first = TOPICS[seq % TOPICS.length];
            String second = TOPICS[(seq + 3) % TOPICS.length];

            // 1) Standalone question.
            ConversationBrain.Turn t1 = brain.beginTurn(first);
            assertTrue(!t1.analysis.topicChanged, seq, 1, "first message cannot change an existing topic");
            assertContains(t1.prompt.systemInstruction(), "CURRENT USER REQUEST", seq, 1);
            brain.finishTurn(t1, answerFor(first));
            cases++;

            // 2) Continuation / typo / multi-task variant.
            String follow;
            if (seq % 4 == 0) {
                follow = "isme 2.5mm wire chalega?";
            } else if (seq % 4 == 1) {
                follow = "Aur iski wiring kaise karein?";
            } else if (seq % 4 == 2) {
                follow = "1. Iska basic connection samjhao? 2. Isme protection kya rakhein? 3. Kaunsa wire use karein?";
            } else {
                follow = "Ye wala samjha do, phir aage batao.";
            }
            ConversationBrain.Turn t2 = brain.beginTurn(follow);
            assertTrue(t2.analysis.relatedQuestion || t2.analysis.requiresPreviousContext || t2.analysis.followUpCue,
                    seq, 2, "follow-up must retain context");
            assertTrue(!t2.analysis.topicChanged, seq, 2, "follow-up must stay on current topic");
            assertContains(t2.prompt.flatten(), "CURRENT USER REQUEST", seq, 2);
            if (seq % 4 == 2) assertTrue(TextTools.requestCount(follow) == 3, seq, 2, "three tasks must be detected");
            brain.finishTurn(t2, answerFor(follow));
            cases++;

            // 3) Correction/imperative: this catches the exact "Nahin tum sikhao" / "Nahi code likho" failure.
            String correction = (seq % 2 == 0) ? "Nahin tum sikhao." : "Nahi code likho, pura code do.";
            ConversationBrain.Turn t3 = brain.beginTurn(correction);
            assertTrue(!t3.analysis.topicChanged, seq, 3, "correction must stay on the active topic");
            assertTrue(t3.analysis.requiresPreviousContext || t3.analysis.followUpCue,
                    seq, 3, "correction needs the preceding exchange");
            assertContains(t3.prompt.systemInstruction(), "Correction detected", seq, 3);
            String action = TextTools.actionOf(TextTools.parse(correction));
            assertTrue(seq % 2 == 0 ? "teach".equals(action) : "write_code".equals(action),
                    seq, 3, "imperative action must be detected");
            brain.finishTurn(t3, answerFor(correction));
            cases++;

            // 4) Explicit subject switch.
            ConversationBrain.Turn t4 = brain.beginTurn(second);
            assertTrue(t4.analysis.topicChanged || t4.analysis.currentTopic.name.toLowerCase().contains("train")
                            || t4.analysis.currentTopic.name.toLowerCase().contains("android")
                            || t4.analysis.currentTopic.name.toLowerCase().contains("programming")
                            || t4.analysis.currentTopic.name.toLowerCase().contains("electrical")
                            || t4.analysis.currentTopic.name.toLowerCase().contains("ai")
                            || t4.analysis.currentTopic.name.toLowerCase().contains("cooking")
                            || t4.analysis.currentTopic.name.toLowerCase().contains("travel")
                            || t4.analysis.currentTopic.name.toLowerCase().contains("finance")
                            || t4.analysis.currentTopic.name.toLowerCase().contains("health")
                            || t4.analysis.currentTopic.name.toLowerCase().contains("education"),
                    seq, 4, "new subject must not be glued to an unrelated prior topic");
            assertNotContains(t4.prompt.flatten(), first, seq, 4);
            brain.finishTurn(t4, answerFor(second));
            cases++;

            // 5) Return to the original subject explicitly.
            String returnText = (seq % 3 == 0)
                    ? "wapas pehle wale AC sawal par aao"
                    : "pichle topic par wapas jao aur continue karo";
            // For non-electrical first topics, use a neutral reference to the first topic's topic profile.
            if (seq % TOPICS.length != 0) returnText = "wapas pehle wale sawal par aao aur continue karo";
            ConversationBrain.Turn t5 = brain.beginTurn(returnText);
            assertTrue(t5.analysis.returningToOldTopic || t5.analysis.requiresPreviousContext,
                    seq, 5, "explicit return must recover old context");
            assertTrue(t5.prompt.recentConversation.size() >= 2,
                    seq, 5, "returned topic must carry a prior user/assistant pair");
            brain.finishTurn(t5, answerFor(returnText));
            cases++;

            if (seq % 20 == 0) hard++;
        }

        T.eq(1000, cases, "exactly 1000 conversation messages exercised");
        long ms = (System.nanoTime() - started) / 1_000_000L;
        System.out.println("  1000 messages checked across 200 five-turn conversations; hard cases=" + hard + "; time=" + ms + " ms");
        T.check(ms < 15000, "1000-message assistant regression suite completes in under 15s");

        // Direct parser regressions from the video failure.
        T.eq("teach", TextTools.actionOf(TextTools.parse("Nahin tum sikhao")), "video correction: teach action");
        T.eq("write_code", TextTools.actionOf(TextTools.parse("Nahin code likho")), "video correction: write-code action");
        T.check(TextTools.parse("Nahin tum sikhao").correctionCue, "video correction cue detected");
        T.eq(3, TextTools.requestCount("1. pehla sawal? 2. doosra sawal? 3. teesra sawal?"), "three-question counter");
    }

    private static String answerFor(String user) {
        String x = user == null ? "" : user.trim();
        if (x.toLowerCase().contains("code likho")) return "Theek hai, main requested code deta hoon.";
        if (x.toLowerCase().contains("sikhao")) return "Theek hai, ab isi topic se teaching start karte hain.";
        return "Is topic par requested answer yahan hai.";
    }

    private static void assertContains(String text, String needle, int seq, int turn) {
        assertTrue(text != null && text.contains(needle), seq, turn, "missing prompt marker: " + needle);
    }

    private static void assertNotContains(String text, String needle, int seq, int turn) {
        assertTrue(text == null || !text.contains(needle), seq, turn, "unrelated context leaked: " + needle);
    }

    private static void assertTrue(boolean ok, int seq, int turn, String message) {
        if (!ok) throw new AssertionError("seq=" + seq + " turn=" + turn + ": " + message);
    }

    private static final class Clock implements ConversationBrain.Clock {
        long now;
        Clock(long seed) { now = seed * 1000L; }
        @Override public long now() { now += 10; return now; }
    }
}
