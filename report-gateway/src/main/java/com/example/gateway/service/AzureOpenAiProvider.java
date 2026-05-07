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
import org.springframework.web.client.RestClient;

import java.util.List;

@Service
@ConditionalOnProperty(name = "llm.provider", havingValue = "azure-openai", matchIfMissing = true)
public class AzureOpenAiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(AzureOpenAiProvider.class);

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are a report generation assistant. "
            + "Given a user request, call exactly ONE of the available tools. "
            + "Return ONLY the tool call — no explanation, no natural language.";

    private final RestClient restClient;
    private final String deployment;
    private final String apiVersion;
    private final ObjectMapper mapper = new ObjectMapper();

    public AzureOpenAiProvider(
            @Value("${llm.azure.endpoint:http://localhost:9999}") String endpoint,
            @Value("${llm.azure.api-key:test-key}") String apiKey,
            @Value("${llm.azure.deployment:gpt-4o-mini}") String deployment,
            @Value("${llm.azure.api-version:2024-06-01}") String apiVersion) {
        this.restClient = RestClient.builder()
                .baseUrl(endpoint)
                .defaultHeader("api-key", apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
        this.deployment = deployment;
        this.apiVersion = apiVersion;
    }

    @Override
    public ToolCall generateToolCall(String userMessage, List<ToolDefinition> tools, String systemPrompt) {
        String prompt = systemPrompt != null && !systemPrompt.isBlank() ? systemPrompt : DEFAULT_SYSTEM_PROMPT;
        log.info("Calling Azure OpenAI (deployment={}) with prompt: {} [system: {}]",
                deployment, userMessage, systemPrompt != null ? "custom" : "default");

        String requestBody = buildRequestBody(userMessage, tools, prompt);

        String uri = "/openai/deployments/" + deployment + "/chat/completions?api-version=" + apiVersion;
        String responseBody = restClient.post()
                .uri(uri)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        return extractToolCall(responseBody);
    }

    @Override
    public String providerName() {
        return "azure-openai";
    }

    @Override
    public String generateText(String userMessage, String systemPrompt) {
        String prompt = systemPrompt != null && !systemPrompt.isBlank() ? systemPrompt : DEFAULT_SYSTEM_PROMPT;
        log.info("Calling Azure OpenAI (deployment={}) generateText: {}", deployment, userMessage);

        // Build request without tools — free-form text generation
        String messages = "[{\"role\":\"system\",\"content\":\"" + escapeJson(prompt) + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + escapeJson(userMessage) + "\"}]";

        String requestBody = "{\"messages\":" + messages
                + ",\"model\":\"" + deployment + "\""
                + ",\"temperature\":0.1}";

        String uri = "/openai/deployments/" + deployment + "/chat/completions?api-version=" + apiVersion;
        String responseBody = restClient.post()
                .uri(uri)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                return choices.get(0).path("message").path("content").asText();
            }
            return null;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Azure OpenAI response: " + e.getMessage(), e);
        }
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
                + ",\"model\":\"" + deployment + "\""
                + ",\"temperature\":0.1"
                + ",\"tools\":" + toolsJson + "}";
    }

    private ToolCall extractToolCall(String responseBody) {
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
            }
            log.info("Azure OpenAI returned no tool call — prompt may not match any available tool");
            return null;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Azure OpenAI response: " + e.getMessage(), e);
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
