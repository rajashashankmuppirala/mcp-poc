# Contract: Domain API

**Service**: `domain-api` (port 8082)

## POST /api/v1/reports/stream

Generate and stream report data.

### Request

```json
{
  "reportType": "revenue",
  "dateRange": {
    "start": "2026-01-01",
    "end": "2026-03-31"
  },
  "filters": {
    "region": "us-east"
  },
  "groupBy": ["product"],
  "limit": 1000
}
```

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `reportType` | string | Yes | Registered report type |
| `dateRange.start` | string | No | `YYYY-MM-DD` format |
| `dateRange.end` | string | No | `YYYY-MM-DD` format |
| `filters` | object | No | Key-value filter pairs |
| `groupBy` | string[] | No | Grouping fields |
| `limit` | integer | No | 1-10000 (default: 1000) |

**Headers**: `Content-Type: application/json`

### Response (Streaming)

`Content-Type: text/plain` (NDJSON stream)

```json
{"type":"header","data":{"title":"Revenue Report","columns":["product","revenue"]},"sequence":0,"timestamp":"2026-05-05T10:00:00Z"}
{"type":"data","data":{"product":"Widget A","revenue":12345.67},"sequence":1,"timestamp":"2026-05-05T10:00:01Z"}
{"type":"footer","data":{"totalRows":2,"elapsedMs":2000},"sequence":2,"timestamp":"2026-05-05T10:00:03Z"}
```

### GET /reports/export

Legacy endpoint — simple streaming export with no parameters.

**Response**: Same NDJSON stream format.

### Error Responses

| Status | Body | Condition |
|--------|------|-----------|
| 400 | `{"error":"INVALID_QUERY","message":"..."}` | Malformed request |
| 403 | `{"error":"ACCESS_DENIED","message":"..."}` | User lacks permission |
| 404 | `{"error":"REPORT_TYPE_NOT_FOUND","message":"..."}` | Unknown report type |
| 500 | `{"error":"INTERNAL_ERROR","message":"..."}` | Data source failure |
