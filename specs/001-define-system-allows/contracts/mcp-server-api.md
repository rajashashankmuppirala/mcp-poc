# Contract: MCP Server API

**Service**: `mcp-server` (MCP Protocol + REST)
**Base URL**: `http://mcp-server:8081`

## MCP Tool Definitions

### generate_report

Executes a report generation query against the domain API.

**Input Schema**:
```json
{
  "name": "generate_report",
  "description": "Generate a structured report from domain data sources",
  "inputSchema": {
    "type": "object",
    "properties": {
      "reportType": {
        "type": "string",
        "enum": ["revenue", "sales", "inventory", "user_activity"],
        "description": "The type of report to generate"
      },
      "dateRange": {
        "type": "object",
        "properties": {
          "start": { "type": "string", "format": "date" },
          "end": { "type": "string", "format": "date" }
        },
        "required": ["start", "end"]
      },
      "filters": {
        "type": "object",
        "additionalProperties": { "type": "string" },
        "description": "Key-value filter pairs applied to results"
      },
      "groupBy": {
        "type": "array",
        "items": { "type": "string" },
        "description": "Fields to group results by"
      },
      "limit": {
        "type": "integer",
        "minimum": 1,
        "maximum": 10000,
        "default": 1000
      }
    },
    "required": ["reportType"]
  }
}
```

**Output**: Streamed report chunks (see `ReportChunk` in data-model.md)

### list_reports

Returns available report types for discovery.

**Input Schema**: `{}` (no parameters)

**Output**:
```json
{
  "tools": [
    { "name": "generate_report", "description": "..." },
    { "name": "list_reports", "description": "..." }
  ]
}
```

## Internal REST Endpoints

### POST /internal/tools/execute

Gateway calls this to execute a parsed tool call (used when not using MCP protocol directly).

**Request**:
```
POST /internal/tools/execute
Content-Type: application/json
X-User-Id: <from JWT>
X-User-Roles: <from JWT>
```

**Request Body**:
```json
{
  "tool": "generate_report",
  "parameters": {
    "reportType": "revenue",
    "dateRange": { "start": "2026-01-01", "end": "2026-03-31" },
    "groupBy": ["category"],
    "limit": 1000
  }
}
```

**Response**: `StreamingResponseBody` — same chunk format as gateway API

**Status Codes**:
- `200` — Streaming started
- `400` — Invalid tool name or parameters
- `401` — Missing user identity headers
- `403` — User not authorized for this tool
- `404` — Tool not found
- `500` — Domain API unavailable
