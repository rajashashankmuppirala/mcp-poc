# Contract: AI Gateway API

**Service**: `report-gateway` (port 8080)

## POST /ai/request

Convert a natural language report request into a tool call and stream results.

### Request

```json
{
  "prompt": "Show me revenue by product for Q1 2026",
  "sessionId": "optional-uuid"
}
```

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `prompt` | string | Yes | 1-500 chars, no control characters |
| `sessionId` | string | No | UUID format; auto-generated if omitted |

**Headers**: `Content-Type: application/json`

### Response (Streaming)

`Content-Type: text/plain` (NDJSON stream)

Each line is a JSON object:

```json
{"type":"header","sessionId":"...","data":{"title":"Revenue Report","columns":["product","revenue"]},"sequence":0,"timestamp":"2026-05-05T10:00:00Z"}
{"type":"data","sessionId":"...","data":{"product":"Widget A","revenue":12345.67},"sequence":1,"timestamp":"2026-05-05T10:00:01Z"}
{"type":"data","sessionId":"...","data":{"product":"Widget B","revenue":67890.12},"sequence":2,"timestamp":"2026-05-05T10:00:02Z"}
{"type":"footer","sessionId":"...","data":{"totalRows":2,"elapsedMs":2000},"sequence":3,"timestamp":"2026-05-05T10:00:03Z"}
```

**Headers**: `X-Correlation-ID: <uuid>`

### Error Responses

| Status | Body | Condition |
|--------|------|-----------|
| 400 | `{"error":"VALIDATION_FAILED","message":"..."}` | Invalid prompt (empty, too long) |
| 400 | `{"error":"INVALID_TOOL_CALL","message":"..."}` | LLM returned unparseable tool JSON |
| 429 | `{"error":"RATE_LIMITED","message":"Too many requests"}` | >60 requests/minute from same IP |
| 500 | `{"error":"INTERNAL_ERROR","message":"An unexpected error occurred"}` | Unexpected failure |

### POST /ai/cancel/{sessionId}

Cancel an in-progress streaming session.

**Response**: `204 No Content`
