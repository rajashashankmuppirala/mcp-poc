# Data Model: Report Generation System (Provider-Agnostic)

**Date**: 2026-05-05

## Entities

### ReportChatRequest

The user's natural language request sent to the AI Gateway.

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `prompt` | string | Yes | Min 1 char, max 500 chars, no control characters |
| `sessionId` | string | No | UUID format; auto-generated if not provided |

### ToolCall

The structured JSON produced by the LLM from the user's prompt.

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `tool` | string | Yes | Must be a registered MCP tool name (e.g., `generate_report`) |
| `arguments` | JsonNode | Yes | Must conform to the tool's JSON schema |

### ToolDefinition

A tool schema available for the LLM to invoke.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | Yes | Tool identifier (e.g., `generate_report`) |
| `description` | string | Yes | Human-readable description |
| `parameters` | JsonNode | Yes | JSON Schema object defining accepted parameters |

### ToolCall Parameters (for `generate_report`)

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `reportType` | string | Yes | Type of report (e.g., "sales", "revenue", "inventory") |
| `dateRange` | object | No | `{ "start": "YYYY-MM-DD", "end": "YYYY-MM-DD" }` |
| `filters` | object | No | Key-value pairs for filtering results |
| `groupBy` | string[] | No | Fields to group results by |
| `limit` | integer | No | Max rows to return (default: 1000, max: 10000) |

### ReportChunk

A single chunk of streamed report data.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `type` | string | Yes | `"header"`, `"data"`, `"footer"`, or `"error"` |
| `sessionId` | string | Yes | Correlates chunks to the original request |
| `data` | object | No | Chunk content — varies by type |
| `sequence` | integer | Yes | Monotonically increasing order number |
| `timestamp` | string | Yes | ISO 8601 timestamp of chunk generation |

### LlmProviderConfig

Configuration for the active LLM provider.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `provider` | string | Yes | Provider identifier (e.g., `azure-openai`, `anthropic`) |
| `endpoint` | string | Yes | Base URL for the provider API |
| `apiKey` | string | Yes | Authentication key (from env var) |
| `deployment` | string | No | Model deployment name (provider-specific) |
| `apiVersion` | string | No | API version string (provider-specific) |

### ReportSession

The end-to-end lifecycle of a report generation request.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `sessionId` | string | Yes | UUID identifying the session |
| `correlationId` | string | Yes | UUID for request tracing across layers |
| `state` | enum | Yes | `PENDING`, `STREAMING`, `COMPLETED`, `CANCELLED`, `FAILED` |
| `prompt` | string | Yes | Original user prompt |
| `toolCall` | ToolCall | No | Parsed tool call from LLM |
| `startedAt` | timestamp | Yes | Session start time |
| `completedAt` | timestamp | No | Session end time |

## Validation Rules

- All user input validated against JSON schema before reaching LLM
- Tool call JSON validated against tool's registered schema before execution
- Domain API parameters validated against business rules (date ranges, filter keys)
- LLM provider config validated at startup (required fields present)

## State Transitions (Report Session)

```
PENDING → STREAMING → COMPLETED
                    → CANCELLED (user-initiated)
                    → FAILED (error at any layer)
```
