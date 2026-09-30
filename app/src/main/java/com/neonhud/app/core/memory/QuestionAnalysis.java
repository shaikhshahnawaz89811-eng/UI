package com.neonhud.app.core.memory;

import java.util.ArrayList;
import java.util.List;

/** Result of analysing one new user message against everything remembered so far. */
public final class QuestionAnalysis {
    public Topic currentTopic;            // topic this message belongs to (never null after analysis)
    public Topic previousTopic;           // topic that was active before this message (may be null)
    public boolean topicChanged;
    public boolean sameQuestion;          // same or paraphrased question asked earlier
    public boolean relatedQuestion;       // continues / relates to the previous question
    public boolean requiresPreviousContext;
    public final List<Long> relatedMessageIds = new ArrayList<Long>();
    public final List<Long> relatedMemoryIds = new ArrayList<Long>();

    // extra detail (not in the minimum spec, useful for prompt building and tests)
    public boolean returningToOldTopic;   // user came back to an older topic
    public long sameQuestionMessageId;    // the earlier question that matched, 0 if none
    public double sameQuestionScore;
    public boolean followUpCue;           // "isme", "aur", "and what about" ...
    public boolean newTopicCreated;

    @Override public String toString() {
        return "QA{topic=" + (currentTopic == null ? null : currentTopic.name)
                + ", prev=" + (previousTopic == null ? null : previousTopic.name)
                + ", changed=" + topicChanged + ", same=" + sameQuestion
                + ", related=" + relatedQuestion + ", needsCtx=" + requiresPreviousContext
                + ", returning=" + returningToOldTopic + "}";
    }
}
