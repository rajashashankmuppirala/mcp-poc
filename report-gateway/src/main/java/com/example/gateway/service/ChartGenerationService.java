package com.example.gateway.service;

import com.example.gateway.model.ChartResponse;
import com.example.gateway.model.ToolCall;
import com.example.gateway.model.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Two-phase chart generation:
 * Phase 1 — Plan: LLM decides which data tool to call
 * Phase 2 — Render: LLM receives the data + chart type hint and produces a Vega-Lite JSON spec
 */
@Service
public class ChartGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ChartGenerationService.class);

    private final LlmProvider llmProvider;
    private final McpClientService mcpClient;

    public ChartGenerationService(LlmProvider llmProvider, McpClientService mcpClient) {
        this.llmProvider = llmProvider;
        this.mcpClient = mcpClient;
    }

    /**
     * Detect chart type from user prompt by keyword matching.
     */
    public static String detectChartType(String userMessage) {
        if (userMessage == null) return "bar";
        String lower = userMessage.toLowerCase();
        if (lower.contains("pie chart") || lower.contains("pie graph")) return "pie";
        if (lower.contains("line chart") || lower.contains("line graph") || lower.contains("trend")) return "line";
        if (lower.contains("area chart") || lower.contains("area graph")) return "area";
        if (lower.contains("scatter") || lower.contains("correlation")) return "scatter";
        if (lower.contains("bar chart") || lower.contains("bar graph") || lower.contains("histogram")) return "bar";
        return "bar";
    }

    /**
     * Phase 1: Ask the LLM to plan — which tool to call.
     */
    public Mono<ToolCall> planDataQuery(String userMessage, List<ToolDefinition> tools, String systemPrompt) {
        String planningPrompt = userMessage + "\n\n"
                + "Respond with ONLY a tool call. Choose the best tool for fetching the data needed "
                + "to create the requested visualization. Include relevant parameters.";

        return llmProvider.generateToolCall(planningPrompt, tools, systemPrompt)
                .doOnNext(tc -> {
                    log.info("Chart plan: tool={} params={}", tc.tool(), tc.parameters());
                })
                .doOnNext(tc -> {
                    if (tc == null) log.warn("LLM returned null for chart planning prompt");
                });
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
