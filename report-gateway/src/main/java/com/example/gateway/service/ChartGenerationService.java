package com.example.gateway.service;

import com.example.gateway.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Two-phase chart generation:
 * Phase 1 — Plan: LLM decides which data tool to call (with conversation context)
 * Phase 2 — Render: LLM receives the data + chart type hint and produces a Vega-Lite JSON spec
 */
@Service
public class ChartGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ChartGenerationService.class);

    private final LlmProvider llmProvider;
    private final McpClientService mcpClient;
    private final ContextInjector contextInjector;

    public ChartGenerationService(LlmProvider llmProvider, McpClientService mcpClient, ContextInjector contextInjector) {
        this.llmProvider = llmProvider;
        this.mcpClient = mcpClient;
        this.contextInjector = contextInjector;
    }

    /**
     * Detect chart type from user prompt by keyword matching.
     */
    public static String detectChartType(String userMessage) {
        if (userMessage == null) return "bar";
        String lower = userMessage.toLowerCase();
        // Check explicit chart type prefix first (e.g., "Chart type: bar.")
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("chart type:\\s*(\\w+)").matcher(lower);
        if (m.find()) {
            String type = m.group(1);
            if ("pie".equals(type)) return "pie";
            if ("line".equals(type)) return "line";
            if ("area".equals(type)) return "area";
            if ("scatter".equals(type)) return "scatter";
            if ("bar".equals(type)) return "bar";
        }
        if (lower.contains("pie chart") || lower.contains("pie graph")) return "pie";
        if (lower.contains("line chart") || lower.contains("line graph") || lower.contains("trend")) return "line";
        if (lower.contains("area chart") || lower.contains("area graph")) return "area";
        if (lower.contains("scatter") || lower.contains("correlation")) return "scatter";
        if (lower.contains("bar chart") || lower.contains("bar graph") || lower.contains("histogram")) return "bar";
        return "bar";
    }

    /**
     * Check if the user explicitly mentioned a chart type.
     * Returns false when the prompt is ambiguous (just "chart" without type).
     */
    public static boolean isChartTypeExplicit(String userMessage) {
        if (userMessage == null) return false;
        String lower = userMessage.toLowerCase();
        // Recognize explicit chart type prefix from clarification flow
        if (lower.startsWith("chart type:")) return true;
        return lower.contains("pie chart") || lower.contains("pie graph")
                || lower.contains("line chart") || lower.contains("line graph") || lower.contains("trend")
                || lower.contains("area chart") || lower.contains("area graph")
                || lower.contains("scatter") || lower.contains("correlation")
                || lower.contains("bar chart") || lower.contains("bar graph") || lower.contains("histogram");
    }

    /**
     * Build a clarification response when chart type is ambiguous.
     */
    public static ClarificationResponse chartTypeClarification(String originalPrompt) {
        return new ClarificationResponse(
                "chart_type",
                "What type of chart would you like?",
                List.of(
                        new ClarificationResponse.Option("Bar", "bar", "Compare values across categories"),
                        new ClarificationResponse.Option("Line", "line", "Show trends over time"),
                        new ClarificationResponse.Option("Pie", "pie", "Show proportions or distribution"),
                        new ClarificationResponse.Option("Area", "area", "Show volume over time"),
                        new ClarificationResponse.Option("Scatter", "scatter", "Show correlation between values")
                ),
                "Specify the chart type and I'll generate it for you.",
                originalPrompt
        );
    }

    /**
     * Phase 1: Ask the LLM to plan — which tool to call.
     * Now with conversation context injection.
     */
    public Mono<ToolCall> planDataQuery(String userMessage, List<ToolDefinition> tools,
                                         String systemPrompt, ConversationSession session) {
        // Build context-aware prompt using conversation history
        String planningPrompt = contextInjector.buildContextPrompt(userMessage, session) + "\n"
                + "You MUST call a tool with ALL applicable parameters.\n"
                + "If the user mentions a time period, you MUST provide startDate AND endDate.\n"
                + "If the user mentions a region, you MUST provide region.\n"
                + "If the user refers to something from the conversation history (using \"it\", \"that\", \"the same\"), use the filters from that context.\n"
                + "Respond with ONLY a tool call — no explanation, no natural language.";

        log.info("=== CHART PLAN PROMPT ===");
        log.info("userMessage: {}", userMessage);
        log.info("session present: {}", session != null);
        if (session != null) {
            log.info("session turns: {}", session.turns().size());
        }

        return llmProvider.generateToolCall(planningPrompt, tools, systemPrompt)
                .doOnNext(tc -> {
                    if (tc == null) log.warn("LLM returned null for chart planning prompt");
                })
                // Enforce filter inheritance from previous turn at code level
                .map(tc -> inheritFilters(tc, session));
    }

    /**
     * Merge previous turn's filters into the current tool call for any missing fields.
     * This ensures context inheritance even when the LLM doesn't follow prompt instructions.
     */
    private ToolCall inheritFilters(ToolCall toolCall, ConversationSession session) {
        if (toolCall == null || toolCall.parameters() == null || session == null) {
            log.debug("inheritFilters: skipping - no toolCall or session");
            return toolCall;
        }

        ConversationTurn lastTurn = session.getLastTurn();
        if (lastTurn == null || lastTurn.extractedFilters() == null) {
            log.info("inheritFilters: no previous filters to inherit (lastTurn={}, filters={})",
                    lastTurn != null ? "present" : "null",
                    lastTurn != null && lastTurn.extractedFilters() != null ? "present" : "null");
            return toolCall;
        }

        ExtractedFilters currentFilters = contextInjector.extractFilters("", toolCall);
        ExtractedFilters mergedFilters = currentFilters.mergeWithContext(lastTurn.extractedFilters());

        // Only update if something actually changed
        if (mergedFilters.equals(currentFilters)) {
            log.info("inheritFilters: no changes needed — LLM already provided all filters");
            return toolCall;
        }

        log.info("Inheriting filters from previous turn: previous={}, current={}, merged={}",
                lastTurn.extractedFilters(), currentFilters, mergedFilters);

        // Update the ToolCall parameters with merged filters
        ObjectNode params = (ObjectNode) toolCall.parameters();
        if (mergedFilters.startDate() != null && !params.has("startDate")) {
            params.put("startDate", mergedFilters.startDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
        }
        if (mergedFilters.endDate() != null && !params.has("endDate")) {
            params.put("endDate", mergedFilters.endDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
        }
        if (mergedFilters.reportType() != null && !params.has("reportType")) {
            params.put("reportType", mergedFilters.reportType());
        }
        if (mergedFilters.region() != null && !params.has("region")) {
            params.put("region", mergedFilters.region());
        }

        log.info("inheritFilters: updated tool call params with inherited dates: {}", params);
        return new ToolCall(toolCall.tool(), params);
    }

    /**
     * Phase 2: Give the raw data to the LLM and ask it to produce a Vega-Lite spec.
     * Returns the spec as a JSON string (not JsonNode) for clean serialization.
     */
    public Mono<ChartResponse> generateChartSpec(String userMessage, String rawData,
                                                  String chartType, String systemPrompt) {
        String detectedType = chartType != null && !chartType.isBlank()
                ? chartType : detectChartType(userMessage);

        String typeGuidance = switch (detectedType) {
            case "pie" -> "Create a pie chart (mark type arc with innerRadius 0). Use theta encoding for the value field and color for the category.";
            case "line" -> "Create a line chart (mark line). Use x for the time/ordinal field and y for the value. Include points: true on the line mark.";
            case "area" -> "Create an area chart (mark area). Use x for the time field and y for the value. Set interpolate to 'monotone' for smooth curves.";
            case "scatter" -> "Create a scatter plot (mark point or circle). Use x and y for the two numeric fields being compared.";
            case "bar" -> "Create a bar chart (mark bar). Use x for the category field and y for the value.";
            default -> "Create an appropriate Vega-Lite visualization (mark bar as fallback).";
        };

        String renderPrompt = String.format(
                "User request: %s\n\n"
                        + "Chart type requested: %s\n\n"
                        + "Raw data (NDJSON array):\n%s\n\n"
                        + "Instructions:\n"
                        + "%s\n\n"
                        + "Rules:\n"
                        + "- Output ONLY valid JSON, no markdown formatting, no backticks, no thinking tags\n"
                        + "- Use data.values with the actual data records from above\n"
                        + "- Include a descriptive title, axis labels, and appropriate color scheme\n"
                        + "- The spec must be a complete Vega-Lite 5.x specification\n"
                        + "- For time fields, use timeUnit: 'yearmonthdatehoursminutes'\n"
                        + "- For string/categorical fields, use type: 'nominal'; for numbers use type: 'quantitative'",
                userMessage,
                detectedType,
                rawData.length() > 5000 ? rawData.substring(0, 5000) + "..." : rawData,
                typeGuidance
        );

        return llmProvider.generateText(renderPrompt, systemPrompt)
                .map(result -> {
                    if (result == null || result.isBlank()) {
                        log.warn("LLM returned empty chart spec");
                        return new ChartResponse("bar", "Chart", "{}", "No data");
                    }

                    // Strip markdown code fences and <thinking> blocks
                    String cleaned = result
                            .replaceAll("(?s)<thinking>.*?</thinking>", "")
                            .replaceAll("(?s)<think>.*?</think>", "")
                            .replaceAll("^```(?:json)?\\s*", "")
                            .replaceAll("\\s*```\\s*$", "")
                            .trim();

                    // If still not starting with {, try to find first { and last }
                    if (!cleaned.startsWith("{")) {
                        int firstBrace = cleaned.indexOf('{');
                        int lastBrace = cleaned.lastIndexOf('}');
                        if (firstBrace >= 0 && lastBrace > firstBrace) {
                            cleaned = cleaned.substring(firstBrace, lastBrace + 1);
                        }
                    }

                    try {
                        JsonNode spec = new com.fasterxml.jackson.databind.ObjectMapper().readTree(cleaned);
                        String title = spec.has("title") ? spec.get("title").asText() : "Generated Chart";
                        String actualType = chartType != null ? chartType : "bar";
                        String summary = "Vega-Lite spec generated with " + cleaned.length() + " chars";
                        return new ChartResponse(actualType, title, cleaned, summary);
                    } catch (Exception e) {
                        log.warn("Failed to parse Vega-Lite spec: {}", e.getMessage());
                        return new ChartResponse("bar", "Chart Error", cleaned, "Parse error: " + e.getMessage());
                    }
                });
    }
}
