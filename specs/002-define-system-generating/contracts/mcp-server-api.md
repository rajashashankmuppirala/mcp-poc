# Contract: MCP Server API

**Service**: `mcp-server` (port 8081)

## POST /tools/generate-report

Execute a report generation tool call.

### Request

```json
{
  "tool": "generate_report",
  "parameters": {
    "reportType": "revenue",
    "dateRange": {
      "start": "2026-01-01",
      "end": "2026-03-31"
    },
    "region": "us-east",
    "groupBy": ["product"],
    "limit": 1000
  }
}
```

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `tool` | string | Yes | Must be `generate_report` |
| `parameters.reportType` | string | Yes | Alphanumeric + underscore only |
| `parameters.dateRange.start` | string | No | `YYYY-MM-DD` format |
| `parameters.dateRange.end` | string | No | `YYYY-MM-DD` format, >= start |
| `parameters.region` | string | No | Lowercase region pattern (e.g., `us-east`) |
| `parameters.groupBy` | string[] | No | Valid field names |
| `parameters.limit` | integer | No | 1-10000 (default: 1000) |

**Headers**: `Content-Type: application/json`, `X-Correlation-ID: <uuid>`

### Response (Streaming)

`Content-Type: text/plain` (NDJSON stream)

Same format as gateway response — chunks forwarded directly from domain API.

### Error Responses

| Status | Body | Condition |
|--------|------|-----------|
| 400 | `{"error":"VALIDATION_FAILED","message":"..."}` | Invalid parameters |
| 404 | `{"error":"TOOL_NOT_FOUND","message":"..."}` | Unknown tool name |
| 502 | `{"error":"UPSTREAM_ERROR","message":"..."}` | Domain API failure |
| 504 | `{"error":"UPSTREAM_TIMEOUT","message":"..."}` | Domain API timeout |

## POST /tools/cancel/{streamId}

Cancel an in-progress tool execution.

**Response**: `204 No Content`
