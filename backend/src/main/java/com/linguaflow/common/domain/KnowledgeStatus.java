package com.linguaflow.common.domain;

/**
 * How well a learner knows a word or grammar topic. Adding material never makes it
 * {@link #MASTERED}; that is earned through practice (design principle 6, PRG-02).
 */
public enum KnowledgeStatus {

	NEW, LEARNING, LEARNED, MASTERED

}
