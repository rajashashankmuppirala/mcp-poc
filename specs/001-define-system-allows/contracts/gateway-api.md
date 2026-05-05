# Contract: AI Gateway API

**Service**: `report-gateway` (Spring Cloud Gateway)
**Base URL**: `http://gateway:8080`

## Endpoints

### POST /api/v1/reports/chat

Accepts a natural language report request, converts it to a tool call via LLM, routes to MCP server, and streams results back.

**Request**:
```
POST /api/v1/reports/chat
Content-Type: application/json
Authorization: Bearer <jwt>
Accept: text/event-stream
```

**Request Body** (`ReportChatRequest`):
```json
{
  "prompt": "Show me revenue by product category for Q1 2026",
  "reportType": "revenue",
  "sessionId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response**: `StreamingResponseBody` — chunked JSON stream

Each chunk is a JSON object:
```json
{ "type": "header", "sessionId": "...", "data": { "reportType": "revenue", "columns": ["category", "revenue"] }, "sequence": 0, "timestamp": "2026-05-05T10:00:00Z" }
{ "type": "data", "sessionId": "...", "data": { "category": "Electronics", "revenue": 150000 }, "sequence": 1, "timestamp": "2026-05-05T10:00:01Z" }
{ "type": "data", "sessionId": "...", "data": { "category": "Clothing", "revenue": 85000 }, "sequence": 2, "timestamp": "2026-05-05T10:00:01Z" }
{ "type": "footer", "sessionId": "...", "data": { "totalRows": 2, "elapsedMs": 1200 }, "sequence": 3, "timestamp": "2026-05-05T10:00:02Z" }
```

**Error Response** (non-streaming, HTTP error status):
```json
{
  "error": "VALIDATION_FAILED",
  "message": "Prompt must be between 1 and 500 characters",
  "sessionId": "..."
}
```

**Status Codes**:
- `200` — Streaming response started
- `400` — Validation error (prompt empty, too long, invalid characters)
- `401` — Missing or invalid JWT
- `403` — User lacks permission for requested report type
- `429` — Rate limit exceeded
- `500` — Internal server error (LLM unavailable, MCP server down)

### GET /api/v1/reports/types

Returns available report types for discovery and UI rendering.

**Response**:
```json
{
  "reportTypes": [
    { "name": "revenue", "displayName": "Revenue Report", "description": "Revenue breakdown by configurable dimensions" },
    { "name": "sales", "displayName": "Sales Report", "description": "Sales volume and trends" }
  ]
}
```
