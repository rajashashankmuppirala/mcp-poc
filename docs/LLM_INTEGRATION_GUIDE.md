# LLM Integration Guide

This document covers the end-to-end request flow and how to integrate any LLM provider into the report generation system.

---

## System Architecture

```
User → Report Gateway (8080) → LLM Provider (external)
                     ↓
              MCP Server (8081)
                     ↓
              Domain API (8082)
                     ↓
              Report data streamed back to user
```

## End-to-End Request Flow

### Step 1: User Input
User types a natural language prompt in the chat UI or CLI:
- *"Show me revenue by product for Q1"* → displays report inline
- *"Download revenue report"* → triggers browser file download

The frontend checks if the prompt contains "download" to determine display mode, but the backend pipeline is identical for both.

### Step 2: Gateway Receives Request
`POST /ai/request` with body:
```json
{ "prompt": "Show me revenue by product for Q1" }
```

`AiController` generates a correlation ID and begins the orchestration.

### Step 3: LLM Converts Prompt → Tool Call
The gateway calls `LlmProvider.generateToolCall()`, sending the user prompt along with tool definitions (JSON Schema). The LLM returns a structured tool call:

```json
{
  "tool": "generate_report",
  "parameters": {
    "reportType": "revenue",
    "startDate": "2024-01-01",
    "endDate": "2024-03-31",
    "region": "us-east"
  }
}
```

The LLM **only** returns tool call JSON — it never calls the domain API directly.

### Step 4: Tool Call Validation
`ToolCallValidator` validates the parameters against JSON Schema:
- `startDate` must match `YYYY-MM-DD`
- `region` must match `^[a-z]+-[a-z]+$` (e.g., `us-east`, `eu-west`)

Invalid calls return a 400 error with a structured message.

### Step 5: MCP Server Execution
The gateway routes the tool call to the MCP server:
```
POST /tools/generate-report
Content-Type: application/json

{ "tool": "generate_report", "parameters": { ... } }
```

The MCP server proxies this to the Domain API via `HttpURLConnection` (chosen because Spring's `RestClient` buffers the entire response, breaking streaming).

### Step 6: Domain API Generates Report
The Domain API simulates report data and streams it back as NDJSON (newline-delimited JSON) using Spring's `StreamingResponseBody`. Each row is sent as an independent chunk with ~300ms intervals to simulate real report generation.

### Step 7: Response Streams Back to User
The data flows back through the chain: Domain API → MCP Server → Gateway → User. Each service forwards chunks immediately without buffering.

For **display mode**: The browser uses `ReadableStream.getReader()` to consume chunks and render them progressively in the chat bubble.

For **download mode**: The browser consumes the full response as a blob and triggers a file download via a hidden `<a>` element.

---

## LLM Provider Interface

All LLM providers implement the `LlmProvider` interface:

```java
public interface LlmProvider {
    ToolCall generateToolCall(String userMessage, List<ToolDefinition> tools);
    String providerName();
}
```

The interface has a single method: given a user message and a list of available tools (with JSON Schema descriptions), return a `ToolCall` with the selected tool name and its arguments.

### Provider Selection

Providers are selected at runtime via `@ConditionalOnProperty` in `application.yml`:

```yaml
llm:
  provider: mock  # or azure-openai, anthropic, openai, etc.
```

Spring loads all `LlmProvider` implementations as beans, but only the one matching `llm.provider` is activated.

---

## Built-in Providers

### 1. Mock Provider (for testing)

**File**: `MockLlmProvider.java`
**Activation**: `llm.provider=mock`

Requires zero configuration. Parses the user prompt for known keywords and returns a hardcoded `generate_report` tool call.

```yaml
llm:
  provider: mock
```

### 2. Azure OpenAI Provider

**File**: `AzureOpenAiProvider.java`
**Activation**: `llm.provider=azure-openai` (default)

Calls Azure OpenAI Chat Completions API with function calling.

```yaml
llm:
  provider: azure-openai
  azure:
    endpoint: https://your-resource.openai.azure.com
    api-key: your-api-key
    deployment: gpt-4o-mini
    api-version: "2024-06-01"
```

**Environment variables**:
```bash
AZURE_OPENAI_ENDPOINT=https://your-resource.openai.azure.com
AZURE_OPENAI_API_KEY=your-api-key
AZURE_OPENAI_DEPLOYMENT=gpt-4o-mini
```

---

## Integrating a New LLM Provider

To add support for any LLM (Anthropic, MiniMax, OpenAI, etc.), create a new class that implements `LlmProvider` and add the configuration properties.

### Step-by-Step Example: Anthropic Claude

**1. Create the provider class:**

`report-gateway/src/main/java/com/example/gateway/service/AnthropicProvider.java`

```java
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
@ConditionalOnProperty(name = "llm.provider", havingValue = "anthropic")
public class AnthropicProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);
    private final RestClient restClient;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnthropicProvider(
            @Value("${llm.anthropic.api-key}") String apiKey,
            @Value("${llm.anthropic.model:claude-sonnet-4-20250514}") String model) {
        this.restClient = RestClient.builder()
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("content-type", "application/json")
                .build();
        this.model = model;
    }

    @Override
    public ToolCall generateToolCall(String userMessage, List<ToolDefinition> tools) {
        log.info("Calling Anthropic Claude (model={}) with prompt: {}", model, userMessage);

        String requestBody = buildRequestBody(userMessage, tools);

        String responseBody = restClient.post()
                .uri("/v1/messages")
                .body(requestBody)
                .retrieve()
                .body(String.class);

        return extractToolCall(responseBody);
    }

    @Override
    public String providerName() {
        return "anthropic";
    }

    private String buildRequestBody(String userMessage, List<ToolDefinition> tools) {
        // Build Anthropic Messages API request format
        // ... same pattern as AzureOpenAiProvider
    }

    private ToolCall extractToolCall(String responseBody) {
        // Parse Anthropic response → ToolCall
        // ... same pattern as AzureOpenAiProvider
    }
}
```

**2. Add configuration to `application.yml`:**

```yaml
llm:
  provider: anthropic
  anthropic:
    api-key: ${ANTHROPIC_API_KEY:your-api-key}
    model: claude-sonnet-4-20250514
```

**3. Set environment variable:**

```bash
export ANTHROPIC_API_KEY=sk-ant-xxxxx
```

**4. Restart the gateway:**

```bash
docker compose up -d ai-gateway --force-recreate
```

---

### Step-by-Step Example: MiniMax

**1. Create the provider class:**

`report-gateway/src/main/java/com/example/gateway/service/MiniMaxProvider.java`

```java
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
@ConditionalOnProperty(name = "llm.provider", havingValue = "minimax")
public class MiniMaxProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(MiniMaxProvider.class);
    private final RestClient restClient;
    private final String model;
    private final String groupId;
    private final ObjectMapper mapper = new ObjectMapper();

    public MiniMaxProvider(
            @Value("${llm.minimax.api-key}") String apiKey,
            @Value("${llm.minimax.group-id:your-group-id}") String groupId,
            @Value("${llm.minimax.model:MiniMax-M2}") String model) {
        this.restClient = RestClient.builder()
                .baseUrl("https://api.minimax.io")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("content-type", "application/json")
                .build();
        this.model = model;
        this.groupId = groupId;
    }

    @Override
    public ToolCall generateToolCall(String userMessage, List<ToolDefinition> tools) {
        log.info("Calling MiniMax (model={}, group={}) with prompt: {}", model, groupId, userMessage);

        String requestBody = buildRequestBody(userMessage, tools);

        String uri = "/v1/text/chatcompletion_v2?GroupId=" + groupId;
        String responseBody = restClient.post()
                .uri(uri)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        return extractToolCall(responseBody);
    }

    @Override
    public String providerName() {
        return "minimax";
    }

    private String buildRequestBody(String userMessage, List<ToolDefinition> tools) {
        // Build MiniMax request format
    }

    private ToolCall extractToolCall(String responseBody) {
        // Parse MiniMax response → ToolCall
    }
}
```

**2. Add configuration:**

```yaml
llm:
  provider: minimax
  minimax:
    api-key: ${MINIMAX_API_KEY:your-api-key}
    group-id: ${MINIMAX_GROUP_ID:your-group-id}
    model: MiniMax-M2
```

**3. Set environment variables:**

```bash
export MINIMAX_API_KEY=xxxxx
export MINIMAX_GROUP_ID=xxxxx
```

---

### Step-by-Step Example: OpenAI (direct, not Azure)

**1. Create the provider class:**

`report-gateway/src/main/java/com/example/gateway/service/OpenAiProvider.java`

```java
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
@ConditionalOnProperty(name = "llm.provider", havingValue = "openai")
public class OpenAiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProvider.class);
    private final RestClient restClient;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiProvider(
            @Value("${llm.openai.api-key}") String apiKey,
            @Value("${llm.openai.model:gpt-4o}") String model) {
        this.restClient = RestClient.builder()
                .baseUrl("https://api.openai.com")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("content-type", "application/json")
                .build();
        this.model = model;
    }

    @Override
    public ToolCall generateToolCall(String userMessage, List<ToolDefinition> tools) {
        log.info("Calling OpenAI (model={}) with prompt: {}", model, userMessage);

        String requestBody = buildRequestBody(userMessage, tools);

        String responseBody = restClient.post()
                .uri("/v1/chat/completions")
                .body(requestBody)
                .retrieve()
                .body(String.class);

        return extractToolCall(responseBody);
    }

    @Override
    public String providerName() {
        return "openai";
    }

    private String buildRequestBody(String userMessage, List<ToolDefinition> tools) {
        // Same format as AzureOpenAiProvider — OpenAI and Azure share the same API
        String systemContent = "You are a report generation assistant. "
                + "Given a user request, call exactly ONE of the available tools. "
                + "Return ONLY the tool call — no explanation, no natural language.";

        // messages array
        String messages = "[{\"role\":\"system\",\"content\":\"" + escapeJson(systemContent) + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + escapeJson(userMessage) + "\"}]";

        // tools array (same format for OpenAI)
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

        return "{\"model\":\"" + model + "\""
                + ",\"messages\":" + messages
                + ",\"temperature\":0.1"
                + ",\"tools\":" + toolsJson + "}";
    }

    private ToolCall extractToolCall(String responseBody) {
        // Same parsing as Azure — OpenAI response format is identical
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
            throw new IllegalStateException("LLM returned natural language instead of tool call");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse OpenAI response: " + e.getMessage(), e);
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
        } catch (Exception e) {
            return "{}";
        }
    }
}
```

**2. Add configuration:**

```yaml
llm:
  provider: openai
  openai:
    api-key: ${OPENAI_API_KEY:your-api-key}
    model: gpt-4o
```

**3. Set environment variable:**

```bash
export OPENAI_API_KEY=sk-xxxxx
```

---

## Configuration Quick Reference

### Switch providers (change only `llm.provider`):

| Provider | `llm.provider` value | Required config keys |
|----------|---------------------|---------------------|
| Mock | `mock` | none |
| Azure OpenAI | `azure-openai` | `llm.azure.endpoint`, `llm.azure.api-key`, `llm.azure.deployment` |
| OpenAI (direct) | `openai` | `llm.openai.api-key`, `llm.openai.model` |
| Anthropic | `anthropic` | `llm.anthropic.api-key`, `llm.anthropic.model` |
| MiniMax | `minimax` | `llm.minimax.api-key`, `llm.minimax.group-id` |

### Environment variable mapping:

All sensitive values are injected via `${ENV_VAR:default}` syntax in `application.yml`.

| Provider | Environment Variables |
|----------|----------------------|
| Azure OpenAI | `AZURE_OPENAI_ENDPOINT`, `AZURE_OPENAI_API_KEY`, `AZURE_OPENAI_DEPLOYMENT` |
| OpenAI | `OPENAI_API_KEY` |
| Anthropic | `ANTHROPIC_API_KEY` |
| MiniMax | `MINIMAX_API_KEY`, `MINIMAX_GROUP_ID` |

### Docker Compose usage:

Update the `ai-gateway` service in `docker-compose.yml`:

```yaml
ai-gateway:
  build: ./report-gateway
  ports:
    - "8080:8080"
  environment:
    - SPRING_PROFILES_ACTIVE=docker
    - LLM_PROVIDER=anthropic
    - MCP_SERVER_URL=http://mcp-server:8081
    - ANTHROPIC_API_KEY=${ANTHROPIC_API_KEY}
    - ANTHROPIC_MODEL=claude-sonnet-4-20250514
```

Then run:
```bash
export ANTHROPIC_API_KEY=sk-ant-xxxxx
docker compose up -d
```

---

## What Each Provider Must Implement

Every `LlmProvider` implementation does three things:

1. **Build the request body** — Convert the user message and tool definitions into the provider's API format (messages array + tools array).

2. **Make the HTTP call** — POST to the provider's endpoint with appropriate auth headers (`api-key`, `Authorization: Bearer`, etc.).

3. **Parse the response** — Extract the tool name and arguments from the provider's response JSON into a `ToolCall` record.

The system handles everything else: validation, routing to MCP, streaming the report back to the user.

---

## Testing Without a Real LLM

Use the mock provider during development:

```yaml
llm:
  provider: mock
```

The mock provider:
- Requires no API keys or network calls
- Parses simple keywords from prompts (`us-east`, `eu-west`, `revenue`)
- Always returns `generate_report` with predictable parameters
- Is activated automatically when no real LLM is configured

This lets you test the full pipeline (Gateway → MCP → Domain API → streaming) without any external dependencies.
