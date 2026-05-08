package com.example.gateway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptCapabilityCheckerTest {

    private PromptCapabilityChecker checker;

    @BeforeEach
    void setUp() {
        checker = new PromptCapabilityChecker();
    }

    @Test
    void greetingGetsFriendlyResponse() {
        String result = checker.checkOutOfScope("hello");
        assertNotNull(result);
        assertTrue(result.contains("report"));
        assertTrue(result.contains("chart"));
    }

    @Test
    void weatherIsOutOfScope() {
        String result = checker.checkOutOfScope("What's the weather today?");
        assertNotNull(result);
        assertTrue(result.contains("report generation"));
        assertTrue(result.contains("analytics"));
    }

    @Test
    void jokeIsOutOfScope() {
        String result = checker.checkOutOfScope("Tell me a funny joke");
        assertNotNull(result);
        assertTrue(result.contains("can't help"));
    }

    @Test
    void inScopePromptPassesThrough() {
        String result = checker.checkOutOfScope("Show me revenue for last year");
        assertNull(result);
    }

    @Test
    void chartRequestPassesThrough() {
        String result = checker.checkOutOfScope("Create a pie chart of sales by region");
        assertNull(result);
    }

    @Test
    void shortPromptGetsNudge() {
        String result = checker.checkOutOfScope("blah blah");
        assertNotNull(result);
        assertTrue(result.contains("reports"));
    }

    @Test
    void nullPromptReturnsNull() {
        assertNull(checker.checkOutOfScope(null));
    }

    @Test
    void blankPromptReturnsNull() {
        assertNull(checker.checkOutOfScope("   "));
    }

    @Test
    void ambiguousLongPromptPassesThrough() {
        // Long enough, no in-scope keywords, no out-of-scope keywords
        String result = checker.checkOutOfScope("What is the meaning of existence in the modern world");
        assertNull(result);
    }

    @Test
    void noToolMatchMessageIsFriendly() {
        String msg = checker.noToolMatchMessage("What's the stock price of Apple?");
        assertNotNull(msg);
        assertTrue(msg.contains("not sure how to help"));
        assertTrue(msg.contains("report"));
    }

    @Test
    void noToolMatchMessageTruncatesLongPrompts() {
        String longPrompt = "This is a very very very very very very very very very very very long prompt that should get truncated";
        String msg = checker.noToolMatchMessage(longPrompt);
        assertFalse(msg.contains("that should get truncated"));
        assertTrue(msg.contains("..."));
    }

    @Test
    void internalErrorMessageIsFriendly() {
        String msg = checker.internalErrorMessage();
        assertNotNull(msg);
        assertTrue(msg.contains("went wrong"));
        assertFalse(msg.contains("error"));
        assertFalse(msg.contains("exception"));
    }

    @Test
    void sportsIsOutOfScope() {
        String result = checker.checkOutOfScope("Who won the football game last night?");
        assertNotNull(result);
        assertTrue(result.contains("report generation"));
    }

    @Test
    void recipeIsOutOfScope() {
        String result = checker.checkOutOfScope("Give me a recipe for pasta");
        assertNotNull(result);
    }

    @Test
    void thanksGetsFriendlyRedirect() {
        String result = checker.checkOutOfScope("thank you");
        assertNotNull(result);
        assertTrue(result.contains("can help"));
    }
}
