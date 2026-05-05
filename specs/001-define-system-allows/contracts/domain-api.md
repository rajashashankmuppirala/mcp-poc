# Contract: Domain API

**Service**: `domain-api` (Spring Boot 4, Tomcat)
**Base URL**: `http://domain-api:8082`

## Endpoints

### POST /api/v1/reports/stream

Generates report data and streams it in chunks. Called by the MCP server.

**Request**:
```
POST /api/v1/reports/stream
Content-Type: application/json
Authorization: Bearer <jwt>
```

**Request Body** (`ReportQuery`):
```json
{
  "reportType": "revenue",
  "dateRange": { "start": "2026-01-01", "end": "2026-03-31" },
  "filters": { "region": "us-east" },
  "groupBy": ["category"],
  "limit": 1000
}
```

**Response**: `StreamingResponseBody` — chunked JSON

Each chunk:
```json
{
  "type": "data",
  "data": { "category": "Electronics", "revenue": 150000 },
  "sequence": 1
}
```

Final chunk:
```json
{
  "type": "footer",
  "data": { "totalRows": 42, "queryTimeMs": 850 },
  "sequence": 43
}
```

**Error Response**:
```json
{
  "type": "error",
  "error": "INVALID_DATE_RANGE",
  "message": "Start date must be before end date",
  "sequence": 0
}
```

**Status Codes**:
- `200` — Streaming started
- `400` — Invalid parameters (bad date range, unknown report type, invalid filter keys)
- `401` — Missing or invalid JWT
- `403` — User lacks access to requested data scope
- `404` — Report type not found
- `500` — Database or internal error

### GET /api/v1/reports/types

Returns available report types with their parameter schemas.

**Response**:
```json
{
  "reportTypes": [
    {
      "name": "revenue",
      "displayName": "Revenue Report",
      "supportedGroupBy": ["category", "region", "month"],
      "supportedFilters": ["region", "productLine", "channel"]
    },
    {
      "name": "sales",
      "displayName": "Sales Report",
      "supportedGroupBy": ["product", "region", "week"],
      "supportedFilters": ["region", "channel", "status"]
    }
  ]
}
```
