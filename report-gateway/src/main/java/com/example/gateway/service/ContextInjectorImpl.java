package com.example.gateway.service;

import com.example.gateway.config.SessionConfig;
import com.example.gateway.model.*;
import com.example.gateway.service.RagService.ScoredChunk;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * Implementation of ContextInjector with sliding window context building.
 */
@Service
public class ContextInjectorImpl implements ContextInjector {

    private static final Logger log = LoggerFactory.getLogger(ContextInjectorImpl.class);

    private final SessionConfig sessionConfig;
    private final RagService ragService;

    public ContextInjectorImpl(SessionConfig sessionConfig, RagService ragService) {
        this.sessionConfig = sessionConfig;
        this.ragService = ragService;
    }

    @Override
    public String retrieveRagContext(String query) {
        List<ScoredChunk> chunks = ragService.retrieve(query, 3);
        if (chunks.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("=== Relevant Knowledge Base Context ===\n");
        sb.append("The following snippets may be relevant to the user's query:\n\n");
        for (ScoredChunk scored : chunks) {
            DocumentChunk chunk = scored.chunk();
            sb.append("[Document chunk ").append(chunk.chunkIndex() + 1)
              .append(", score=").append(String.format("%.2f", scored.score()))
              .append("] ").append(chunk.text()).append("\n\n");
        }
        log.info("RAG context injected: {} chunks for query", chunks.size());
        return sb.toString();
    }

    @Override
    public String buildContextPrompt(String currentPrompt, ConversationSession session) {
        StringBuilder prompt = new StringBuilder();

        // RAG context — retrieved from knowledge base
        String ragContext = retrieveRagContext(currentPrompt);
        if (!ragContext.isEmpty()) {
            prompt.append(ragContext);
        }

        // Build date reference section
        prompt.append(buildDateReference());

        // Add conversation summary if present (session was summarized)
        if (session != null && !session.summary().isBlank()) {
            prompt.append("=== Previous Conversation Summary ===\n");
            prompt.append(session.summary()).append("\n\n");
        }

        // Add conversation context if session exists and has turns
        if (session != null && !session.isEmpty()) {
            List<ConversationTurn> recentTurns = session.getRecentTurns(
                    sessionConfig.getContextWindowSize());

            if (!recentTurns.isEmpty()) {
                prompt.append("\n=== Conversation History ===\n");
                for (ConversationTurn turn : recentTurns) {
                    prompt.append("User: ").append(turn.userPrompt()).append("\n");
                    if (turn.extractedFilters() != null) {
                        prompt.append("[Filters: ").append(turn.extractedFilters()).append("]\n");
                    }
                    if (turn.responseType() != null) {
                        prompt.append("[System: ").append(turn.responseType());
                        if (turn.chartType() != null) {
                            prompt.append(" (").append(turn.chartType()).append(")");
                        }
                        if (turn.responseTitle() != null) {
                            prompt.append(" - \"").append(turn.responseTitle()).append("\"");
                        }
                        prompt.append("]\n");
                    }
                    prompt.append("\n");
                }
            }
        }

        // Add current request
        prompt.append("=== Current Request ===\n");
        prompt.append("User: ").append(currentPrompt).append("\n");

        return prompt.toString();
    }

    @Override
    public ExtractedFilters extractFilters(String userPrompt, ToolCall toolCall) {
        if (toolCall == null || toolCall.parameters() == null) {
            return ExtractedFilters.builder().build();
        }

        JsonNode params = toolCall.parameters();
        ExtractedFilters.Builder builder = ExtractedFilters.builder();

        // Extract reportType
        if (params.has("reportType")) {
            builder.reportType(params.get("reportType").asText());
        }

        // Extract region
        if (params.has("region")) {
            builder.region(params.get("region").asText());
        }

        // Extract date range
        if (params.has("startDate")) {
            String startDate = params.get("startDate").asText();
            builder.startDate(parseDate(startDate));
        }
        if (params.has("endDate")) {
            String endDate = params.get("endDate").asText();
            builder.endDate(parseDate(endDate));
        }

        // Extract additional filters
        params.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (!isStandardFilter(key)) {
                builder.additionalFilter(key, entry.getValue().asText());
            }
        });

        ExtractedFilters filters = builder.build();
        log.debug("Extracted filters from tool call: {}", filters);
        return filters;
    }

    @Override
    public ConversationTurn createTurn(String userPrompt, ToolCall toolCall,
                                          ResponseMetadata responseMetadata) {
        ExtractedFilters filters = extractFilters(userPrompt, toolCall);

        return ConversationTurn.builder()
                .userPrompt(userPrompt)
                .extractedFilters(filters)
                .responseType(responseMetadata.type())
                .responseTitle(responseMetadata.title())
                .chartType(responseMetadata.chartType())
                .build();
    }

    @Override
    public Mono<String> summarizeConversation(LlmProvider llmProvider, List<ConversationTurn> turns) {
        if (turns.isEmpty()) {
            return Mono.just("");
        }

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("Summarize the following conversation history into a concise summary.\n");
        promptBuilder.append("Include: report types requested, date ranges discussed, regions mentioned, and any chart types generated.\n");
        promptBuilder.append("Keep it under 200 words. Do not include any preamble — just the summary.\n\n");
        promptBuilder.append("Conversation (").append(turns.size()).append(" turns):\n\n");

        for (ConversationTurn turn : turns) {
            promptBuilder.append("[Turn ").append(turn.turnNumber()).append("] User: ").append(turn.userPrompt()).append("\n");
            if (turn.extractedFilters() != null) {
                promptBuilder.append("  Filters: ").append(turn.extractedFilters()).append("\n");
            }
            if (turn.responseType() != null) {
                promptBuilder.append("  Response: ").append(turn.responseType());
                if (turn.chartType() != null) {
                    promptBuilder.append(" (").append(turn.chartType()).append(" chart)");
                }
                if (turn.responseTitle() != null) {
                    promptBuilder.append(" - \"").append(turn.responseTitle()).append("\"");
                }
                promptBuilder.append("\n");
            }
            promptBuilder.append("\n");
        }

        String summarizationPrompt = promptBuilder.toString();

        return llmProvider.generateText(summarizationPrompt, "You are a conversation summarizer. Provide concise, factual summaries.")
                .map(summary -> {
                    log.info("Generated summary: {} chars from {} turns", summary.length(), turns.size());
                    return summary.trim();
                })
                .onErrorResume(e -> {
                    log.warn("Summarization failed, falling back to simple summary: {}", e.getMessage());
                    return Mono.just(buildFallbackSummary(turns));
                });
    }

    private String buildFallbackSummary(List<ConversationTurn> turns) {
        if (turns.isEmpty()) return "";

        StringBuilder summary = new StringBuilder();
        summary.append("Conversation summary (").append(turns.size()).append(" turns): ");

        List<String> reportTypes = turns.stream()
                .filter(t -> t.extractedFilters() != null)
                .map(t -> t.extractedFilters().reportType())
                .filter(rt -> rt != null)
                .distinct()
                .toList();

        if (!reportTypes.isEmpty()) {
            summary.append("Report types: ").append(String.join(", ", reportTypes)).append(". ");
        }

        List<String> dateRanges = turns.stream()
                .filter(t -> t.extractedFilters() != null && t.extractedFilters().hasDateRange())
                .map(t -> t.extractedFilters().startDate() + " to " + t.extractedFilters().endDate())
                .distinct()
                .toList();

        if (!dateRanges.isEmpty()) {
            summary.append("Date ranges: ").append(String.join("; ", dateRanges)).append(". ");
        }

        return summary.toString();
    }

    private String buildDateReference() {
        LocalDate today = LocalDate.now();
        int currentYear = today.getYear();
        int lastYear = currentYear - 1;
        int currentQuarter = (today.getMonthValue() - 1) / 3 + 1;
        int lastQuarter = currentQuarter == 1 ? 4 : currentQuarter - 1;
        int lastQuarterYear = currentQuarter == 1 ? lastYear : currentYear;

        String thisQuarterStart = YearMonth.of(currentYear, currentQuarter).atDay(1).toString();
        String thisQuarterEnd = YearMonth.of(currentYear, currentQuarter).atEndOfMonth().toString();
        String lastQuarterStart = YearMonth.of(lastQuarterYear, lastQuarter).atDay(1).toString();
        String lastQuarterEnd = YearMonth.of(lastQuarterYear, lastQuarter).atEndOfMonth().toString();

        return String.format(
                "=== Date Reference (today is %s) ===\n" +
                "- \"this year\" → startDate: \"%d-01-01\", endDate: \"%d-12-31\"\n" +
                "- \"last year\" → startDate: \"%d-01-01\", endDate: \"%d-12-31\"\n" +
                "- \"this quarter\" / \"Q%d\" → startDate: \"%s\", endDate: \"%s\"\n" +
                "- \"last quarter\" → startDate: \"%s\", endDate: \"%s\"\n\n",
                today,
                currentYear, currentYear,
                lastYear, lastYear,
                currentQuarter, thisQuarterStart, thisQuarterEnd,
                lastQuarterStart, lastQuarterEnd
        );
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr);
        } catch (Exception e) {
            log.warn("Failed to parse date: {}", dateStr);
            return null;
        }
    }

    private boolean isStandardFilter(String key) {
        return key.equals("reportType") ||
               key.equals("region") ||
               key.equals("startDate") ||
               key.equals("endDate");
    }
}
