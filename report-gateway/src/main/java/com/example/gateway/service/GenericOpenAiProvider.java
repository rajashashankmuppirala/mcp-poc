package com.example.gateway.service;

import com.example.gateway.model.ToolCall;
import com.example.gateway.model.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * Generic OpenAI-compatible LLM provider (fully reactive).
 * Works with any endpoint that implements the OpenAI Chat Completions API:
 * OpenAI, MiniMax, Ollama, LM Studio, Groq, OpenRouter, TogetherAI, etc.
 *
 * Configure via application.yml:
 *   llm.provider=openai-compatible
 *   llm.openai.endpoint=https://api.minimax.chat/v1
 *   llm.openai.api-key=sk-xxx
 *   llm.openai.model=MiniMax-M2
 */
@Service
@ConditionalOnProperty(name = "llm.provider", havingValue = "openai-compatible")
public class GenericOpenAiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(GenericOpenAiProvider.class);

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are a report generation assistant. "
            + "Given a user request, call exactly ONE of the available tools. "
            + "Return ONLY the tool call — no explanation, no natural language.";

    private final WebClient webClient;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();

    public GenericOpenAiProvider(
            @Value("${llm.openai.endpoint:https://api.openai.com/v1}") String endpoint,
            @Value("${llm.openai.api-key:}") String apiKey,
            @Value("${llm.openai.model:gpt-4o}") String model) {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(endpoint)
                .defaultHeader("Content-Type", "application/json");

        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }

        this.webClient = builder.build();
        this.model = model;
        log.info("Generic OpenAI provider initialized: endpoint={}, model={}", endpoint, model);
    }

    @Override
    public Mono<ToolCall> generateToolCall(String userMessage, List<ToolDefinition> tools, String systemPrompt) {
        String prompt = systemPrompt != null && !systemPrompt.isBlank() ? systemPrompt : DEFAULT_SYSTEM_PROMPT;
        log.info("Calling LLM (model={}) with prompt: {} [system: {}]",
                model, userMessage, systemPrompt != null ? "custom" : "default");

        String requestBody = buildRequestBody(userMessage, tools, prompt);

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::extractToolCall)
                .doOnSuccess(tc -> {
                    if (tc != null) log.info("LLM returned tool: {}", tc.tool());
                    else log.info("LLM returned no tool call — prompt may not match any available tool");
                });
    }

    @Override
    public String providerName() {
        return "openai-compatible";
    }

    @Override
    public Mono<String> generateText(String userMessage, String systemPrompt) {
        String prompt = systemPrompt != null && !systemPrompt.isBlank() ? systemPrompt : DEFAULT_SYSTEM_PROMPT;
        log.info("Calling LLM (model={}) generateText: {}", model, userMessage.substring(0, Math.min(80, userMessage.length())));

        String messages = "[{\"role\":\"system\",\"content\":\"" + escapeJson(prompt) + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + escapeJson(userMessage) + "\"}]";

        String requestBody = "{\"messages\":" + messages
                + ",\"model\":\"" + model + "\""
                + ",\"temperature\":0.1}";

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .map(responseBody -> {
                    try {
                        JsonNode root = mapper.readTree(responseBody);
                        JsonNode choices = root.path("choices");
                        if (choices.isArray() && choices.size() > 0) {
                            return choices.get(0).path("message").path("content").asText();
                        }
                        return null;
                    } catch (Exception e) {
                        throw new IllegalStateException("Failed to parse LLM response: " + e.getMessage(), e);
                    }
                });
    }

    private String buildRequestBody(String userMessage, List<ToolDefinition> tools, String systemPrompt) {
        String messages = "[{\"role\":\"system\",\"content\":\"" + escapeJson(systemPrompt) + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + escapeJson(userMessage) + "\"}]";

        StringBuilder toolsJson = new StringBuilder("[");
        for (int i = 0; i < tools.size(); i++) {
            ToolDefinition tool = tools.get(i);
            if (i > 0) toolsJson.append(",");
            toolsJson.append("{\"type\":\"function\",\"function\":{")
                    .append("\"name\":\"").append(escapeJson(tool.name())).append("\",")
                    .append("\"description\":\"").append(escapeJson(tool.description())).append("\",")
                    .append("\"parameters\":").append(tryWriteJson(tool.parameters()))
                    .append("}}");
        }
        toolsJson.append("]");

        return "{\"messages\":" + messages
                + ",\"model\":\"" + model + "\""
                + ",\"temperature\":0.1"
                + ",\"tools\":" + toolsJson + "}";
    }

    private ToolCall extractToolCall(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            log.warn("LLM returned empty response");
            return null;
        }
        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode toolCalls = choices.get(0).path("message").path("tool_calls");
                if (toolCalls.isArray() && toolCalls.size() > 0) {
                    String toolName = toolCalls.get(0).path("function").path("name").asText();
                    String args = toolCalls.get(0).path("function").path("arguments").asText();
                    JsonNode params = mapper.readTree(args);
                    return new ToolCall(toolName, params);
                }
                return null;
            }
            // Check for API error response
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) {
                log.error("LLM API error: {}", error);
                throw new IllegalStateException("LLM API error: " + error);
            }
            return null;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse LLM response: " + e.getMessage(), e);
        }
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String tryWriteJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }
}
