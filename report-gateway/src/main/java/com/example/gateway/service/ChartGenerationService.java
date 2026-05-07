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

/**
 * Two-phase chart generation:
 * Phase 1 — Plan: LLM decides which data tool to call and what chart type to use
 * Phase 2 — Render: LLM receives the data and produces a Vega-Lite JSON spec
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
     * Phase 1: Ask the LLM to plan — which tool to call and what chart type.
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
        String renderPrompt = String.format(
                "Create a Vega-Lite JSON specification for a %s chart.\n\n"
                        + "User request: %s\n\n"
                        + "Raw data (NDJSON array):\n%s\n\n"
                        + "Rules:\n"
                        + "- Output ONLY valid JSON, no markdown formatting, no backticks\n"
                        + "- Use data.values with the actual data records\n"
                        + "- Include a title, axis labels, and appropriate color scheme\n"
                        + "- The spec must be a complete Vega-Lite 5.x specification\n"
                        + "- For time fields, use timeUnit: 'yearmonthdatehoursminutes'\n"
                        + "- For string fields, use aggregate: 'count' for bar charts or 'sum' where appropriate",
                chartType != null ? chartType : "bar",
                userMessage,
                rawData.length() > 3000 ? rawData.substring(0, 3000) + "..." : rawData
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
