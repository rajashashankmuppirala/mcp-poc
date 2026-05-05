# Data Model: Report Generation System

**Date**: 2026-05-05

## Entities

### ReportChatRequest

The user's natural language request sent to the AI Gateway.

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `prompt` | string | Yes | Min 1 char, max 500 chars, no control characters |
| `reportType` | string | No | If provided, must match a registered MCP tool name |
| `sessionId` | string | No | UUID format; auto-generated if not provided |

### ToolCall

The structured JSON produced by the LLM from the user's prompt.

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `tool` | string | Yes | Must be a registered MCP tool name (e.g., `generate_report`) |
| `parameters` | object | Yes | Must conform to the tool's JSON schema |

### ToolCall Parameters (for `generate_report`)

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `reportType` | string | Yes | Type of report (e.g., "sales", "revenue", "inventory") |
| `dateRange` | object | No | `{ "start": "YYYY-MM-DD", "end": "YYYY-MM-DD" }` |
| `filters` | object | No | Key-value pairs for filtering results |
| `groupBy` | string[] | No | Fields to group results by |
| `limit` | integer | No | Max rows to return (default: 1000, max: 10000) |

### ReportChunk

A single chunk of streamed report data sent to the client.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `type` | string | Yes | `"header"`, `"data"`, `"footer"`, or `"error"` |
| `sessionId` | string | Yes | Correlates chunks to the original request |
| `data` | object | No | Chunk content — varies by type |
| `sequence` | integer | Yes | Monotonically increasing order number |
| `timestamp` | string | Yes | ISO 8601 timestamp of chunk generation |

### ToolExecutionResult

The result of an MCP tool execution.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `success` | boolean | Yes | Whether the tool call executed successfully |
| `streamId` | string | Yes | Identifier for the streaming session |
| `error` | string | No | Error message if success is false |

## Validation Rules

- All user input is validated against JSON schema before reaching the LLM
- Tool call JSON is validated against the tool's registered schema before execution
- Domain API parameters are validated against business rules (date ranges, valid filter keys)
- JWT tokens are validated at each layer; user claims determine data access scope

## State Transitions (Report Session)

```
PENDING → STREAMING → COMPLETED
                    → CANCELLED (user-initiated)
                    → FAILED (error at any layer)
```
