# Contract: LLM Provider Interface

**Component**: `LlmProvider` interface in `report-gateway` service

## Interface

```java
public interface LlmProvider {
    ToolCall generateToolCall(String userMessage, List<ToolDefinition> tools);
    String providerName();
}
```

### Input: `generateToolCall(userMessage, tools)`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `userMessage` | String | Yes | Validated natural language prompt (1-500 chars) |
| `tools` | List\<ToolDefinition\> | Yes | Available tool schemas for the LLM to choose from |

### ToolDefinition

```java
public record ToolDefinition(String name, String description, JsonNode parameters) {}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | String | Yes | Tool identifier (e.g., `generate_report`) |
| `description` | String | Yes | Human-readable description of what the tool does |
| `parameters` | JsonNode | Yes | JSON Schema object defining accepted parameters |

### Output: `ToolCall`

```java
public record ToolCall(String tool, JsonNode arguments) {}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `tool` | String | Yes | Selected tool name (must match one of the provided `ToolDefinition` names) |
| `arguments` | JsonNode | Yes | Parsed JSON object of tool parameters |

### Error Behavior

| Condition | Exception Thrown |
|-----------|-----------------|
| LLM returns natural language instead of tool call | `IllegalStateException` |
| LLM returns unparseable JSON | `IllegalArgumentException` |
| LLM returns tool name not in provided definitions | `IllegalArgumentException` |
| Provider network error (timeout, connection refused) | `RuntimeException` |

## Provider Implementations

### Azure OpenAI Provider (`azure-openai`)

**Endpoint**: `POST {endpoint}/openai/deployments/{deployment}/chat/completions?api-version={version}`

**Auth Header**: `api-key: {api-key}`

**Request Body**:
```json
{
  "messages": [
    {"role": "system", "content": "You are a report generation assistant. Call exactly one tool."},
    {"role": "user", "content": "{userMessage}"}
  ],
  "model": "{deployment}",
  "temperature": 0.1,
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "generate_report",
        "description": "Generate a structured report",
        "parameters": { ... }
      }
    }
  ]
}
```

**Response Parsing**:
- Extract `choices[0].message.tool_calls[0]`
- `tool` = `function.name`
- `arguments` = parsed `function.arguments` JSON string

### Mock Provider (`mock`)

**Behavior**: Returns a deterministic `ToolCall` without calling any external service.

**Logic**:
- Parses user message for keywords (`revenue`, `sales`, `us-east`, etc.)
- Returns `ToolCall("generate_report", { "reportType": "revenue", "region": "us-east" })` as default
- No network calls, no credentials needed

### Local Provider (`local`)

**Endpoint**: `POST {endpoint}/v1/chat/completions` (OpenAI-compatible format)

**Auth Header**: None (or `Authorization: Bearer {api-key}` if configured)

**Request/Response**: Same format as Azure OpenAI (OpenAI-compatible API)

## Configuration

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| `llm.provider` | No | `azure-openai` | Active provider: `azure-openai`, `mock`, `local` |
| `llm.azure.endpoint` | Yes (if azure) | - | Azure OpenAI resource endpoint |
| `llm.azure.api-key` | Yes (if azure) | - | Azure OpenAI API key |
| `llm.azure.deployment` | No | `gpt-4o-mini` | Model deployment name |
| `llm.azure.api-version` | No | `2024-06-01` | API version string |
| `llm.local.endpoint` | Yes (if local) | `http://localhost:11434` | Local LLM endpoint |
| `llm.local.model` | No | `llama3.1` | Local LLM model name |
