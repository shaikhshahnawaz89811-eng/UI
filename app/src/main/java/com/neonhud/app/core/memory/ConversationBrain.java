package com.neonhud.app.core.memory;

import com.neonhud.app.core.engine.PromptPackage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The local conversation-memory layer around the model.
 *
 * For every user message it works out: what topic this is, whether the topic changed, whether the user
 * is returning to an older topic, whether it is the same (or a paraphrased) question as before and whether
 * it needs earlier context - then retrieves ONLY the relevant memories / messages and builds the controlled
 * prompt (system context + relevant memory + recent conversation + current message).
 *
 * Thread-safe (all public methods synchronized); intended to be driven from one worker thread.
 */
public final class ConversationBrain {

    public interface Clock { long now(); }

    /** Everything the chat layer needs after {@link #beginTurn}. */
    public static final class Turn {
        public final String userText;
        public final QuestionAnalysis analysis;
        public final PromptPackage prompt;
        public final long userMessageId;
        public final long topicId;
        public final long conversationId;

        Turn(String userText, QuestionAnalysis analysis, PromptPackage prompt,
             long userMessageId, long topicId, long conversationId) {
            this.userText = userText; this.analysis = analysis; this.prompt = prompt;
            this.userMessageId = userMessageId; this.topicId = topicId; this.conversationId = conversationId;
        }
    }

    // ---- tunables (measured with the JVM stress test, see jvm-tests/)
    static final double SAME_TOPIC_MIN = 0.30;
    static final double REVIVE_MIN = 0.50;
    static final double RETURN_MIN = 0.30;
    static final double SAME_Q_MIN = 0.85;
    static final int HISTORY_MESSAGES = 4;
    static final int MAX_HISTORY_CHARS = 3200;
    static final int MEMORY_LIMIT = 5;
    static final int MAX_TOPIC_MEMORIES = 80;
    static final int MAX_GLOBAL_MEMORIES = 200;
    static final long SESSION_GAP_MS = 8L * 60 * 60 * 1000;
    static final int INDEX_LOAD = 5000;

    static final String SYSTEM_BASE =
            "You are Gemma 4 E2B, a helpful AI assistant running fully offline and privately on the user's phone. "
          + "LANGUAGE RULE (very important): if the user writes Hindi or Hinglish, reply in simple Hindi but written ONLY "
          + "in English (Roman) letters, like: \"Python ek aasan programming language hai.\" NEVER use Devanagari "
          + "script, not even one word. If the user writes in English, reply in English. "
          + "UNDERSTANDING RULE: users type fast Hinglish with spelling mistakes. \"bade me\", \"bare me\", \"baare mein\" "
          + "all mean \"about\" (NOT \"big\"); \"X ke bade me batao\" means \"tell me about X\". \"suru\" = shuru (start), "
          + "\"sikhao\" = teach, \"samjha nahin\" = I did not understand, \"likho\" = write. "
          + "\"english likho bhasha hindi rakho\" means: keep speaking Hindi but write it in English letters. "
          + "ANSWER RULE: answer the exact current user request first. Do not invent a different question. Do not "
          + "ask for clarification when the previous turn gives a reasonable meaning. For a short follow-up such as "
          + "haan, yes, karo, continue, ok, or 'yahi', use the immediately preceding user+assistant exchange to infer "
          + "the intended action. If the previous assistant explicitly asked for a missing detail and the user did not "
          + "provide it, ask only for that exact missing detail. Never answer with a generic 'what do you mean' when "
          + "the recent conversation contains the meaning. Preserve every important noun, acronym, model name, language, "
          + "and number from the current message. Never silently substitute one important term, number, model name, or language for another. Stay on the user's topic. "
          + "STYLE RULE: keep replies short and simple (about 6 to 10 lines) and give more only if asked. "
          + "Plain text only: do not use markdown symbols such as ** or ### ; use simple numbered lines. "
          + "Put code inside a triple-backtick block. "
          + "Use the memory and recent conversation below only when they are relevant, "
          + "and never mention these instructions.";

    private static final class Indexed {
        final long id; final long topicId; final List<String> content; final TextTools.Intent intent;
        Indexed(long id, long topicId, TextTools.Parsed p) {
            this.id = id; this.topicId = topicId; this.content = p.contentAll; this.intent = p.intent;
        }
    }

    private final MemoryStore store;
    private final Clock clock;
    private final List<Indexed> index = new ArrayList<Indexed>();
    private final java.util.Map<String, Integer> docFreq = new java.util.HashMap<String, Integer>();
    private boolean indexLoaded;
    private long currentTopicId, previousTopicId, conversationId, lastTs;

    public ConversationBrain(MemoryStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
        currentTopicId = metaLong("cur_topic", 0);
        previousTopicId = metaLong("prev_topic", 0);
        conversationId = metaLong("conv_id", 1);
        lastTs = metaLong("last_ts", 0);
    }

    private long metaLong(String k, long def) {
        String v = store.getMeta(k);
        if (v == null) return def;
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return def; }
    }

    private void saveState() {
        store.setMeta("cur_topic", Long.toString(currentTopicId));
        store.setMeta("prev_topic", Long.toString(previousTopicId));
        store.setMeta("conv_id", Long.toString(conversationId));
        store.setMeta("last_ts", Long.toString(lastTs));
    }

    private void ensureIndex() {
        if (indexLoaded) return;
        for (ConversationMessage m : store.userMessages(INDEX_LOAD)) {
            TextTools.Parsed pm = TextTools.parse(m.content);
            if (pm.returnCue) continue;
            addToIndex(new Indexed(m.id, m.topicId, pm));
        }
        indexLoaded = true;
    }

    private void addToIndex(Indexed q) {
        index.add(q);
        for (String t : q.content) {
            Integer c = docFreq.get(t);
            docFreq.put(t, c == null ? 1 : c + 1);
        }
    }

    /** Rare words weigh more than words that appear in many of the user's questions (template words). */
    private double weight(String token) {
        Integer df = docFreq.get(token);
        return 1.0 + Math.log(1.0 + (double) index.size() / (1.0 + (df == null ? 0 : df)));
    }

    /** IDF-weighted Dice similarity with typo-tolerant token matching. */
    private double weightedSimilarity(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        boolean[] used = new boolean[b.size()];
        double matched = 0, wa = 0, wb = 0;
        for (String x : a) {
            double w = weight(x);
            wa += w;
            for (int j = 0; j < b.size(); j++) {
                if (!used[j] && TextTools.tokenMatch(x, b.get(j))) { used[j] = true; matched += w; break; }
            }
        }
        for (String y : b) wb += weight(y);
        return 2.0 * matched / (wa + wb);
    }

    public synchronized long currentTopicId() { return currentTopicId; }
    public synchronized long previousTopicId() { return previousTopicId; }
    public synchronized long conversationId() { return conversationId; }

    // ================================================================== turn start

    public synchronized Turn beginTurn(String userText) {
        long now = clock.now();
        ensureIndex();
        if (lastTs != 0 && now - lastTs > SESSION_GAP_MS) conversationId++;
        TextTools.Parsed p = TextTools.parse(userText);
        QuestionAnalysis qa = analyze(p, userText);

        Topic target = qa.currentTopic;
        if (target.id == 0) store.addTopic(target);            // brand-new topic gets its id

        // context is selected BEFORE the new message is stored
        List<ConversationMessage> history = selectHistory(qa, target);
        List<MemoryItem> memories = retrieveMemories(qa, p, target, now);
        for (ConversationMessage m : history) qa.relatedMessageIds.add(m.id);
        for (MemoryItem m : memories) qa.relatedMemoryIds.add(m.id);

        long msgId = store.addMessage(now, ConversationMessage.ROLE_USER, userText, conversationId, target.id);
        learn(target, p, now);
        if (!p.returnCue) addToIndex(new Indexed(msgId, target.id, p));

        if (target.id != currentTopicId) {
            previousTopicId = currentTopicId;
            currentTopicId = target.id;
        }
        lastTs = now;
        saveState();

        PromptPackage prompt = buildPrompt(qa, target, history, memories, userText);
        return new Turn(userText, qa, prompt, msgId, target.id, conversationId);
    }

    // ================================================================== turn end

    /** Stores the assistant reply and extracts long-term memories. Returns the stored message id (0 if empty). */
    public synchronized long finishTurn(Turn turn, String reply) {
        if (reply == null || reply.trim().isEmpty()) return 0;
        long now = clock.now();
        long id = store.addMessage(now, ConversationMessage.ROLE_ASSISTANT, reply, turn.conversationId, turn.topicId);
        lastTs = now;
        store.setMeta("last_ts", Long.toString(lastTs));

        TextTools.Parsed q = TextTools.parse(turn.userText);
        String replyHead = reply.length() > 900 ? reply.substring(0, 900) : reply;
        TextTools.Parsed r = TextTools.parse(replyHead);
        List<MemoryExtractor.Candidate> cands = MemoryExtractor.extract(
                turn.userText, reply, q, r, turn.topicId, turn.analysis.sameQuestion);
        for (MemoryExtractor.Candidate c : cands) {
            store.addMemory(new MemoryItem(0, c.content, c.topicId, c.importance, now, now, c.terms));
            prune(c.topicId);
        }
        if (turn.analysis.sameQuestion) bumpSimilarMemory(turn.topicId, q, now);
        return id;
    }

    private void bumpSimilarMemory(long topicId, TextTools.Parsed q, long now) {
        MemoryItem best = null; double bestSim = 0;
        for (MemoryItem m : store.memoriesByTopic(topicId)) {
            double s = TextTools.similarity(q.contentAll, split(m.terms));
            if (s > bestSim) { bestSim = s; best = m; }
        }
        if (best != null && bestSim >= 0.5) {
            best.importance = Math.min(0.9, best.importance + 0.1);
            best.lastUsedAt = now;
            store.updateMemory(best);
        }
    }

    private void prune(long topicId) {
        int cap = topicId == 0 ? MAX_GLOBAL_MEMORIES : MAX_TOPIC_MEMORIES;
        int n = store.countMemories(topicId);
        if (n <= cap) return;
        final long now = clock.now();
        List<MemoryItem> all = store.memoriesByTopic(topicId);
        Collections.sort(all, new Comparator<MemoryItem>() {
            @Override public int compare(MemoryItem a, MemoryItem b) {
                return Double.compare(value(a, now), value(b, now));
            }
        });
        for (MemoryItem m : all) {
            if (n <= cap) break;
            if (m.importance >= 0.9) continue;
            store.deleteMemory(m.id);
            n--;
        }
    }

    private static double value(MemoryItem m, long now) {
        double ageDays = Math.max(0, now - m.lastUsedAt) / 86400000.0;
        return 0.7 * m.importance + 0.3 * Math.exp(-ageDays / 30.0);
    }

    // ================================================================== analysis

    private QuestionAnalysis analyze(TextTools.Parsed p, String text) {
        QuestionAnalysis qa = new QuestionAnalysis();
        List<Topic> topics = store.topics();
        Topic cur = find(topics, currentTopicId);
        Topic prev = find(topics, previousTopicId);
        List<String> kws = p.contentWords;

        qa.followUpCue = p.strongAnaphora || p.followStart || (p.weakAnaphora && kws.size() <= 2);

        // ---- same / paraphrased question (context-dependent messages like "isme kitna time?" are never "the same")
        Indexed same = (qa.followUpCue || p.returnCue) ? null : findSame(p);
        if (same != null) {
            qa.sameQuestion = true;
            qa.sameQuestionMessageId = same.id;
        }

        double curScore = cur == null ? 0 : score(cur, p);
        Topic bestOther = null; double otherScore = 0;
        Topic bestAny = null; double anyScore = 0;
        Topic bestDomain = null; double domainScore = 0;      // best topic that is actually ABOUT the message's subject
        for (Topic t : topics) {
            double s = score(t, p);
            if (!p.domains.isEmpty() && hasDominantDomain(t, p.domains) && s > domainScore) { domainScore = s; bestDomain = t; }
            if (s > anyScore) { anyScore = s; bestAny = t; }
            if ((cur == null || t.id != cur.id) && s > otherScore) { otherScore = s; bestOther = t; }
        }
        boolean domainConflict = cur != null && !p.domains.isEmpty()
                && !hasDominantDomain(cur, p.domains) && curScore < 0.6;

        Topic target;
        boolean created = false, returning = false, needsCtx = false;

        if (cur == null) {
            // very first message, or state lost: revive a matching topic or start one
            if (bestAny != null && anyScore >= REVIVE_MIN) target = bestAny;
            else { target = newTopic(p); created = true; }
        } else if (MemoryExtractor.isMemoryStatement(text)) {
            target = cur;                                     // "yaad rakho ...", "mera naam ..." never switch topic
        } else if (p.returnCue) {
            if (!kws.isEmpty()) {
                if (bestAny != null && anyScore >= RETURN_MIN) target = bestAny;
                else { target = newTopic(p); created = true; }
            } else {
                target = prev != null ? prev : cur;
            }
            returning = !created && target.id != cur.id;
            needsCtx = true;
        } else if (p.strongAnaphora || (p.weakAnaphora && kws.size() <= 2) || kws.isEmpty() || p.ack) {
            if (!kws.isEmpty() && domainConflict && bestDomain != null && bestDomain.id != cur.id && domainScore >= 0.6) {
                target = bestDomain; returning = true;
            } else if (!kws.isEmpty() && domainConflict && !p.ack && p.domains.size() > 0 && !p.strongAnaphora) {
                target = newTopic(p); created = true;
            } else {
                target = cur; needsCtx = true;
            }
        } else if (p.followStart && !domainConflict) {
            target = cur; needsCtx = true;
        } else if (domainConflict) {
            // the message is clearly about a subject the current topic is not about
            Topic sameTopic = same == null ? null : find(topics, same.topicId);
            if (sameTopic != null && sameTopic.id != cur.id) { target = sameTopic; returning = true; needsCtx = true; }
            else if (bestDomain != null && bestDomain.id != cur.id && domainScore >= REVIVE_MIN) { target = bestDomain; returning = true; needsCtx = true; }
            else { target = newTopic(p); created = true; }
        } else if (curScore >= SAME_TOPIC_MIN) {
            target = cur;
            needsCtx = kws.size() <= 2;
        } else if (same != null && same.topicId != 0 && same.topicId != cur.id && find(topics, same.topicId) != null) {
            target = find(topics, same.topicId); returning = true; needsCtx = true;
        } else if (bestOther != null && otherScore >= REVIVE_MIN) {
            target = bestOther; returning = true; needsCtx = true;
        } else {
            target = newTopic(p); created = true;
        }

        qa.currentTopic = target;
        qa.previousTopic = (cur != null && target != cur && (created || target.id != cur.id)) ? cur : prev;
        qa.topicChanged = cur != null && (created || target.id != cur.id);
        qa.returningToOldTopic = returning;
        qa.newTopicCreated = created;
        qa.requiresPreviousContext = needsCtx || returning || (qa.followUpCue && !qa.topicChanged);
        qa.relatedQuestion = !qa.topicChanged && cur != null
                && (qa.followUpCue || p.ack || kws.isEmpty() || curScore > 0 || target == cur && needsCtx);
        if (qa.topicChanged) qa.relatedQuestion = false;
        return qa;
    }

    private Indexed findSame(TextTools.Parsed p) {
        if (p.contentAll.isEmpty()) return null;
        Indexed best = null; double bestScore = 0;
        for (int i = index.size() - 1; i >= 0; i--) {
            Indexed q = index.get(i);
            if (q.content.isEmpty()) continue;
            if (!TextTools.intentsCompatible(p.intent, q.intent)) continue;
            if (TextTools.measureConflict(p.contentAll, q.content)) continue;
            double sim = weightedSimilarity(p.contentAll, q.content);
            if (sim > bestScore + 1e-9) { bestScore = sim; best = q; }
        }
        return bestScore >= SAME_Q_MIN ? best : null;
    }

    /** How well the message fits a topic, 0..1: keyword overlap, boosted when the subject domain matches. */
    private double score(Topic t, TextTools.Parsed p) {
        List<String> kws = p.contentWords;
        if (kws.isEmpty()) return 0;
        double hit = 0;
        for (String k : kws) if (topicHas(t, k)) hit += 1;
        double kw = hit / kws.size();
        // when the message names a subject (AC, train, recipe ...), the topic that is ABOUT that subject wins over a
        // topic that merely shares a word ("train" the vehicle vs "train" a model)
        if (p.domains.isEmpty()) return kw;
        return 0.5 * kw + (hasDominantDomain(t, p.domains) ? 0.5 : 0.0);
    }

    /** True if the topic is substantially about at least one of the given domains (>=25% of its domain hits). */
    private static boolean hasDominantDomain(Topic t, Set<String> domains) {
        int total = 0;
        for (Integer c : t.domains.values()) total += c;
        if (total == 0) return false;
        for (String d : domains) {
            Integer c = t.domains.get(d);
            if (c != null && c >= 1 && c * 4 >= total) return true;
        }
        return false;
    }

    private static boolean topicHas(Topic t, String k) {
        if (t.terms.containsKey(k)) return true;
        if (k.length() < 5) return false;
        for (String key : t.terms.keySet()) if (TextTools.tokenMatch(k, key)) return true;
        return false;
    }

    private Topic newTopic(TextTools.Parsed p) {
        long now = clock.now();
        StringBuilder name = new StringBuilder();
        List<String> words = new ArrayList<String>();
        for (String w : p.contentWords) {
            String surf = p.surface.get(w);
            words.add(surf == null ? w : surf);
            if (words.size() == 3) break;
        }
        String dom = p.domains.isEmpty() ? null : p.domains.iterator().next();
        if (dom != null) {
            name.append(Lexicon.display(dom));
            if (!words.isEmpty()) name.append(" \u00B7 ").append(join(words, 2, ", "));
        } else if (!words.isEmpty()) {
            name.append(join(words, 3, " "));
        } else {
            name.append("General");
        }
        return new Topic(0, name.toString(), now, now);
    }

    private static String join(List<String> l, int max, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size() && i < max; i++) { if (i > 0) sb.append(sep); sb.append(l.get(i)); }
        return sb.toString();
    }

    private void learn(Topic t, TextTools.Parsed p, long now) {
        for (String w : p.contentAll) {
            boolean pureNum = true;
            for (int i = 0; i < w.length(); i++) if (!Character.isDigit(w.charAt(i)) && w.charAt(i) != '.') { pureNum = false; break; }
            if (!pureNum) t.bump(w, 1);
        }
        for (String d : p.domains) t.bumpDomain(d);
        t.trimTerms(60);
        t.lastUsedAt = now;
        store.updateTopic(t);
    }

    private static Topic find(List<Topic> l, long id) {
        if (id == 0) return null;
        for (Topic t : l) if (t.id == id) return t;
        return null;
    }

    // ================================================================== context selection

    private List<ConversationMessage> selectHistory(QuestionAnalysis qa, Topic target) {
        List<ConversationMessage> hist = new ArrayList<ConversationMessage>();
        boolean useTopic = qa.returningToOldTopic || !qa.topicChanged;
        if (useTopic && target.id != 0) hist.addAll(store.messagesByTopic(target.id, HISTORY_MESSAGES));
        if (qa.sameQuestion && qa.sameQuestionMessageId != 0) {
            boolean present = false;
            for (ConversationMessage m : hist) if (m.id == qa.sameQuestionMessageId) { present = true; break; }
            if (!present) {
                ConversationMessage q = store.getMessage(qa.sameQuestionMessageId);
                ConversationMessage a = store.assistantReplyAfter(qa.sameQuestionMessageId);
                List<ConversationMessage> pair = new ArrayList<ConversationMessage>();
                if (q != null) pair.add(q);
                if (a != null) pair.add(a);
                hist.addAll(0, pair);
            }
        }
        return sanitize(hist);
    }

    /** Alternating user/assistant turns, starting with the user and ending with the assistant. */
    private static List<ConversationMessage> sanitize(List<ConversationMessage> in) {
        List<ConversationMessage> out = new ArrayList<ConversationMessage>();
        for (ConversationMessage m : in) {
            if (!out.isEmpty() && out.get(out.size() - 1).isUser() == m.isUser()) out.set(out.size() - 1, m);
            else out.add(m);
        }
        while (!out.isEmpty() && !out.get(0).isUser()) out.remove(0);
        while (!out.isEmpty() && out.get(out.size() - 1).isUser()) out.remove(out.size() - 1);
        return out;
    }

    private List<MemoryItem> retrieveMemories(QuestionAnalysis qa, TextTools.Parsed p, Topic target, long now) {
        final List<String> q = p.contentAll;
        List<MemoryItem> cands = new ArrayList<MemoryItem>();
        if (target.id != 0 && (qa.returningToOldTopic || !qa.topicChanged)) cands.addAll(store.memoriesByTopic(target.id));
        List<MemoryItem> global = store.memoriesByTopic(0);
        final double floor = qa.returningToOldTopic ? 0.15 : 0.25;
        final List<double[]> scored = new ArrayList<double[]>();
        List<MemoryItem> pool = new ArrayList<MemoryItem>(cands);
        pool.addAll(global);
        for (int i = 0; i < pool.size(); i++) {
            MemoryItem m = pool.get(i);
            List<String> mt = split(m.terms);
            double overlap = 0;
            if (!q.isEmpty() && !mt.isEmpty()) {
                int hit = 0;
                for (String s : q) for (String t : mt) if (TextTools.tokenMatch(s, t)) { hit++; break; }
                overlap = (double) hit / q.size();
            }
            boolean isGlobal = m.topicId == 0;
            if (isGlobal && overlap < 0.2 && m.importance < 0.9) continue;
            double ageDays = Math.max(0, now - m.lastUsedAt) / 86400000.0;
            double s = 0.6 * overlap + 0.3 * m.importance + 0.1 * Math.exp(-ageDays / 30.0);
            if (s >= floor) scored.add(new double[]{i, s});
        }
        Collections.sort(scored, new Comparator<double[]>() {
            @Override public int compare(double[] a, double[] b) { return Double.compare(b[1], a[1]); }
        });
        List<MemoryItem> out = new ArrayList<MemoryItem>();
        for (double[] sc : scored) {
            if (out.size() >= MEMORY_LIMIT) break;
            out.add(pool.get((int) sc[0]));
        }
        for (MemoryItem m : out) {
            m.lastUsedAt = now;
            m.importance = Math.min(0.95, m.importance + 0.02);
            store.updateMemory(m);
        }
        return out;
    }

    private static List<String> split(String terms) {
        if (terms == null || terms.isEmpty()) return new ArrayList<String>();
        return new ArrayList<String>(java.util.Arrays.asList(terms.split(" ")));
    }

    // ================================================================== prompt

    private PromptPackage buildPrompt(QuestionAnalysis qa, Topic target, List<ConversationMessage> history,
                                      List<MemoryItem> memories, String userText) {
        StringBuilder sys = new StringBuilder(SYSTEM_BASE);
        sys.append("\n\nTASK CONTROL (highest priority):");
        sys.append("\n- Answer CURRENT USER MESSAGE, not an older question.");
        sys.append("\n- Preserve exact important terms, numbers, model names, and programming languages from CURRENT USER MESSAGE.");
        if (qa.relatedQuestion || qa.requiresPreviousContext || qa.followUpCue) {
            sys.append("\n- This is a continuation. Resolve short words such as 'karo', 'haan', 'yahi', 'yes', 'ok', 'continue' from the immediately preceding exchange.");
        }
        if (qa.topicChanged) {
            sys.append("\n- Subject changed: ignore unrelated earlier topics.");
        }
        TextTools.Parsed currentParsed = TextTools.parse(userText);
        sys.append("\n\nCURRENT MESSAGE ANCHORS: preserve these exact user terms/numbers where relevant: ");
        int anchorCount = 0;
        for (String token : currentParsed.tokens) {
            if (token.length() < 2) continue;
            if (TextTools.isStructuralToken(token)) continue;
            if (anchorCount++ > 0) sys.append(", ");
            sys.append(token);
            if (anchorCount >= 12) break;
        }
        if (anchorCount == 0) sys.append("(none)");
        if (!currentParsed.domains.isEmpty()) sys.append(" | subject domain: ").append(joinSet(currentParsed.domains));
        sys.append("\n\nCurrent topic: ").append(target.name).append('.');
        if (qa.returningToOldTopic) {
            sys.append("\nThe user is returning to this earlier topic");
            if (qa.previousTopic != null) sys.append(" (they were just talking about: ").append(qa.previousTopic.name).append(')');
            sys.append(". The earlier messages on it are included below; continue from there.");
        } else if (qa.topicChanged) {
            sys.append("\nThe user has changed the subject. Do not mix in earlier unrelated conversation.");
        } else if (qa.requiresPreviousContext) {
            sys.append("\nThis message refers back to the recent conversation (words like \"this\", \"it\", \"isme\"); "
                    + "resolve them using the recent conversation below.");
        }
        if (qa.sameQuestion) {
            sys.append("\nThe user has asked this same (or a very similar) question before. Do not repeat your earlier "
                    + "answer word for word: answer again clearly and, if useful, add another aspect or a short recap.");
        }

        StringBuilder mem = new StringBuilder();
        if (!memories.isEmpty()) {
            mem.append("RELEVANT MEMORY:");
            for (MemoryItem m : memories) mem.append("\n- ").append(MemoryExtractor.clip(m.content, 240));
        }

        // trim history by budget, dropping the oldest turns first
        List<PromptPackage.Turn> turns = new ArrayList<PromptPackage.Turn>();
        int total = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            ConversationMessage m = history.get(i);
            String txt = MemoryExtractor.clip(m.content, m.isUser() ? 500 : 700);
            // Old replies written in Devanagari would make the model copy that script again.
            if (!m.isUser() && hasDevanagari(txt)) txt = "(earlier reply omitted)";
            if (total + txt.length() > MAX_HISTORY_CHARS) break;
            total += txt.length();
            turns.add(0, new PromptPackage.Turn(m.isUser(), txt));
        }
        while (!turns.isEmpty() && !turns.get(0).fromUser) turns.remove(0);
        return new PromptPackage(sys.toString(), mem.toString(), turns, userText);
    }

    private static String joinSet(Set<String> values) {
        StringBuilder b = new StringBuilder();
        for (String v : values) { if (b.length() > 0) b.append(", "); b.append(v); }
        return b.toString();
    }

    private static boolean hasDevanagari(String t) {
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '\u0900' && c <= '\u097F') return true;
        }
        return false;
    }

    /** Used by the UI/tests to show the topic names known so far. */
    public synchronized List<String> topicNames() {
        List<String> out = new ArrayList<String>();
        for (Topic t : store.topics()) out.add(t.name.toLowerCase(Locale.ROOT));
        return out;
    }

    /** Distinct topics (test helper). */
    public synchronized Set<Long> topicIds() {
        Set<Long> ids = new HashSet<Long>();
        for (Topic t : store.topics()) ids.add(t.id);
        return ids;
    }
}
