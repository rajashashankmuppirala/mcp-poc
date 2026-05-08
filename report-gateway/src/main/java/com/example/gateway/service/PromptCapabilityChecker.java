package com.example.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Detects prompts that are clearly outside the product's capabilities
 * and returns a friendly out-of-scope message.
 *
 * Industry pattern: a lightweight keyword gate before the LLM call
 * catches obvious mismatches (weather, jokes, general Q&A) while
 * letting ambiguous prompts through to the LLM for tool matching.
 */
@Service
public class PromptCapabilityChecker {

    private static final Logger log = LoggerFactory.getLogger(PromptCapabilityChecker.class);

    /**
     * Keywords that signal the prompt is out of scope.
     * These are topics the system definitively cannot help with.
     */
    private static final List<String> OUT_OF_SCOPE_KEYWORDS = List.of(
            "weather", "forecast", "temperature", "humidity",
            "joke", "funny", "humor", "comedy",
            "recipe", "cook", "food", "restaurant",
            "news", "headlines", "breaking news",
            "sports", "football", "basketball", "soccer", "baseball",
            "music", "song", "playlist", "artist",
            "movie", "film", "cinema", "netflix",
            "travel", "flight", "hotel", "vacation",
            "translate", "translation",
            "spell", "grammar", "dictionary",
            "calculate", "calculator", "math",
            "stock price", "crypto", "bitcoin", "forex",
            "dating", "relationship",
            "medical", "doctor", "health", "symptom",
            "legal", "lawyer", "law", "court"
    );

    /**
     * Keywords that indicate the prompt is IN scope.
     * If any match, we skip the out-of-scope check.
     */
    private static final List<String> IN_SCOPE_KEYWORDS = List.of(
            "report", "revenue", "sales", "data", "chart", "graph", "plot",
            "analytics", "dashboard", "export", "download", "stream",
            "orders", "inventory", "customers", "expenses", "profit",
            "subscription", "pipeline", "dataflow", "job", "failed",
            "success", "status", "summary", "breakdown", "trend",
            "show me", "generate", "create", "visualize", "breakdown"
    );

    /**
     * Greetings and short conversational messages that should get a friendly redirect.
     */
    private static final List<String> GREETING_PATTERNS = List.of(
            "hello", "hi", "hey", "good morning", "good afternoon", "good evening",
            "how are you", "how's it going", "what's up",
            "thanks", "thank you", "thank", "great", "awesome", "perfect"
    );

    /**
     * Check if a prompt is out of scope.
     * Returns a friendly message if it is, or null if it should proceed to the LLM.
     */
    public String checkOutOfScope(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }

        String lower = prompt.toLowerCase().trim();

        // Greetings get a friendly redirect, not an error
        if (isGreeting(lower)) {
            return "Hello! I can help you generate reports, create charts, and download data. "
                    + "Try asking something like \"Show me revenue for last quarter\" or \"Create a pie chart of sales by region.\"";
        }

        // Short prompts (1-2 words) that aren't greetings get a nudge
        if (lower.split("\\s+").length <= 2 && !hasInScopeKeyword(lower)) {
            return "I'm here to help with reports and charts. Try something like \"Show me revenue for last year\" or \"Create a bar chart of orders.\"";
        }

        // If the prompt has ANY in-scope keywords, let it through to the LLM
        if (hasInScopeKeyword(lower)) {
            return null;
        }

        // Check for clear out-of-scope signals
        boolean isOutOfScope = OUT_OF_SCOPE_KEYWORDS.stream().anyMatch(lower::contains);
        if (isOutOfScope) {
            log.info("Prompt flagged as out of scope: {}", prompt);
            return "I'm a report generation and analytics assistant. I can't help with that, but I'd be happy to generate reports, create charts, or analyze data for you. Try asking for something like \"Show me revenue trends\" or \"Create a pie chart of sales by region.\"";
        }

        // Ambiguous — let the LLM decide
        return null;
    }

    private boolean isGreeting(String lower) {
        return GREETING_PATTERNS.stream().anyMatch(lower::contains)
                || lower.matches("^(hi|hello|hey|thanks|thank you|great|awesome|perfect)\\!*?$");
    }

    private boolean hasInScopeKeyword(String lower) {
        return IN_SCOPE_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * Returns a friendly error message for when the LLM can't find a matching tool.
     * This is called when the LLM itself says "no tool matches."
     */
    public String noToolMatchMessage(String prompt) {
        return "I'm not sure how to help with \"" + truncate(prompt, 60) + "\". I can generate reports, create charts, and analyze data. Try rephrasing with a report type and date range — for example, \"Show me revenue for last quarter\" or \"Create a pie chart of sales by region.\"";
    }

    /**
     * Returns a friendly error message for when an internal error occurs.
     */
    public String internalErrorMessage() {
        return "Something went wrong on my end. Please try again in a moment. If the issue persists, try a different query like \"Show me revenue for last quarter.\"";
    }

    private String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
