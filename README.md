# Report Generator — MCP POC

A natural language report generation system using the Model Context Protocol (MCP) with Streamable HTTP transport.

## Quick Start

```bash
# Terminal 1: Domain API
java -jar domain-api/target/domain-api-0.0.1-SNAPSHOT.jar --server.port=8082

# Terminal 2: MCP Server
java -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar --server.port=8081

# Terminal 3: Gateway
java -jar report-gateway/target/report-gateway-0.0.1-SNAPSHOT.jar --server.port=8080

# Browser: http://localhost:8080
```

Or with Docker Compose:

```bash
docker compose up
```

## System Overview

```
Browser (chat UI) → Gateway (:8080) → LLM → MCP Client → MCP Server (:8081) → Domain API (:8082)
```

Three services, each with a distinct responsibility:

| Service | Port | Role |
|---------|------|------|
| **report-gateway** | 8080 | AI Gateway + MCP Client. Converts natural language to tool calls, orchestrates execution, streams results back to browser |
| **mcp-server** | 8081 | MCP Server. Exposes `@McpTool` annotated methods that call the Domain API |
| **domain-api** | 8082 | Domain API. Generates report data and streams rows as NDJSON |

The gateway has a **dual role**:
- **Control plane**: Receives user prompts, calls the LLM to resolve them into structured tool calls
- **Data plane**: Acts as an MCP Client, invoking tools on the MCP Server via Streamable HTTP

For detailed architecture diagrams, see [ARCHITECTURE_V2.md](ARCHITECTURE_V2.md).

---

## End-to-End Flow: Step by Step

### Example: "Show me revenue for us-east from January to March" (with auth)

#### Step 1: User Sends Prompt with Auth Token

```http
POST /ai/request
Content-Type: application/json
X-User-Token: eyJhbGciOiJSUzI1NiIs...

{
  "prompt": "Show me revenue for us-east from January to March"
}
```

#### Step 2: Gateway — Security Layer 1 (Rate Limiting)

The `RequestLoggingWebFilter` checks:
- Rate limit: 60 requests/minute per client IP
- Generates correlation ID for tracing

#### Step 3: Gateway — Security Layer 2 (Prompt Injection Check)

The `PromptInjectionDetector` scans the prompt for injection patterns:
- Instruction overrides (`"ignore previous"`, `"disregard"`)
- System impersonation (`"system prompt"`, `"you are now"`)
- Role changes (`"pretend you are"`, `"role: developer"`)
- Suspicious characters (``` ````, `<<`, `>>`)
- Length limit: 4000 characters

If any pattern matches, returns `400 PROMPT_INJECTION`.

#### Step 4: Gateway → LLM (Natural Language → ToolCall)

```java
ToolCall toolCall = llmProvider.generateToolCall(
    "Show me revenue for us-east from January to March",
    List.of(ToolDefinition.generate_report())
);
```

**LLM returns:**

```json
{
  "tool": "generate_report",
  "parameters": {
    "reportType": "revenue",
    "region": "us-east",
    "startDate": "2026-01-01",
    "endDate": "2026-03-31"
  }
}
```

#### Step 5: Gateway — Security Layer 3 (Tool Call Validation)

The `ToolCallValidator` checks:
- Tool name is in the allowlist (`generate_report` ✓)
- `reportType` matches `^[a-zA-Z_]+$` ✓
- `startDate`/`endDate` match `^\d{4}-\d{2}-\d{2}$` ✓
- `region` matches `^[a-z]+-[a-z]+$` ✓

If validation fails, returns `400 INVALID_TOOL_CALL`.

#### Step 6: Gateway → MCP Server (Streamable HTTP with user token)

The `McpClientService` injects the user token into the tool arguments and POSTs:

```http
POST /mcp
Content-Type: application/json
Accept: application/json, text/event-stream
Mcp-Session-Id: a69f8bf6-...

{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "generate_report",
    "arguments": {
      "reportType": "revenue",
      "region": "us-east",
      "startDate": "2026-01-01",
      "endDate": "2026-03-31",
      "_userToken": "eyJhbGciOiJSUzI1NiIs..."
    }
  }
}
```

#### Step 7: MCP Server — Security Layer 4 (Input Sanitization + Auth Forwarding)

The `@McpTool` method sanitizes inputs and forwards the auth token:

```java
// ReportTools — sanitize all string inputs
private String sanitize(String input, String defaultVal) {
    if (input == null || input.isBlank()) return defaultVal;
    return input.replaceAll("[^a-zA-Z0-9_-]", "");
}
```

Then calls the Domain API with Bearer auth:

```http
POST /api/v1/reports/stream
Content-Type: application/json
Authorization: Bearer eyJhbGciOiJSUzI1NiIs...
X-Correlation-ID: <uuid>

{
  "reportType": "revenue",
  "region": "us-east",
  "dateRange": { "start": "2026-01-01", "end": "2026-03-31" },
  "filters": { "region": "us-east" }
}
```

**Auth fallback**: If no `X-User-Token` header was provided, the MCP Server falls back to a configured service account token (`domain-api.auth-token` in `application.yml`).

#### Step 8: Domain API Streams Report Data

```json
{"type":"header","data":{"reportType":"revenue","generatedAt":"2026-01-15"}}
{"type":"data","data":{"row":1,"product":"Widget A","revenue":15000}}
{"type":"data","data":{"row":2,"product":"Widget B","revenue":23000}}
...
{"type":"footer","data":{"totalRows":50,"totalRevenue":1250000}}
```

#### Step 9: MCP Server → MCP Client → Gateway → Browser

Same SSE streaming as described in the original flow.

---

## Download Mode Flow

### Example: "Download revenue report"

The download flow differs at the browser level — the backend flow is identical (same security layers, same auth).

#### Step 1: Browser Detects "download" Keyword

```javascript
var isDownload = text.toLowerCase().indexOf('download') !== -1;
```

If true, it calls `sendAsDownload()` instead of `sendAsStream()`.

#### Step 2: Gateway Generates Filename

```
generate_report-1778036884627.csv
```

Prepended as the first SSE event.

#### Step 3: Browser Collects Full Response, Creates Blob, Triggers Download

```javascript
var events = parseSSE(rawText);
var filename = events[0];  // "generate_report-1778036884627.csv"
var content = events.slice(1).join('\n');
var blob = new Blob([content], { type: 'text/csv' });
var a = document.createElement('a');
a.href = URL.createObjectURL(blob);
a.download = filename;
a.click();
```

### Normal vs Download Mode Comparison

| Aspect | Normal Mode | Download Mode |
|--------|-------------|---------------|
| **Trigger** | Any prompt | Prompt contains "download" |
| **Rendering** | Progressive row-by-row | Waits for full response |
| **First event** | Data row | Filename (e.g., `generate_report-1234567890.csv`) |
| **Browser action** | Render in chat bubble | Create Blob → trigger download |
| **Output** | Visible in chat | File saved to Downloads folder |

---

## Security Architecture

### Defense-in-Depth Layers

```
Layer 1: RequestLoggingWebFilter     → Rate limiting (per IP, 60 req/min)
Layer 2: AiController                → Prompt injection detection
Layer 3: ToolCallValidator           → Tool allowlist + parameter regex validation
Layer 4: ReportStreamService (MCP)   → Input sanitization + auth token forwarding
```

| Layer | Component | What It Blocks | Response on Violation |
|-------|-----------|----------------|----------------------|
| 1 | `RequestLoggingWebFilter` | Rate abuse (DoS) | `429 Too Many Requests` |
| 2 | `PromptInjectionDetector` | LLM prompt injection | `400 PROMPT_INJECTION` |
| 3 | `ToolCallValidator` | Unknown tools, bad params | `400 INVALID_TOOL_CALL` |
| 4 | `ReportTools.sanitize()` | Injection through MCP params | Strips non-alphanumeric chars |

### Prompt Injection Detection Details

Blocked patterns:

| Category | Patterns |
|----------|----------|
| Instruction overrides | `"ignore previous"`, `"disregard previous"`, `"forget all"` |
| System impersonation | `"system prompt"`, `"system instruction"`, `"you are now"` |
| Role changes | `"pretend you are"`, `"role: system"`, `"role: developer"` |
| Output manipulation | `"output only"`, `"don't follow"`, `"bypass"`, `"override"` |
| Tool injection | `"call tool"`, `"invoke tool"`, `"execute tool"` |
| Suspicious chars | ` ``` `, `<<`, `>>`, `{%`, `%}` |
| Length limit | Max 4000 characters |

### Tool Call Validation Details

```java
ALLOWED_TOOLS = Set.of("generate_report")

PARAM_PATTERNS = Map.of(
    "reportType", Pattern.compile("^[a-zA-Z_]+$"),
    "startDate",    Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$"),
    "endDate",      Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$"),
    "region",       Pattern.compile("^[a-z]+-[a-z]+$")
)
```

---

## Authentication Flow

### Two Modes

| Mode | When Used | Token Source | Domain API Sees |
|------|-----------|--------------|-----------------|
| **User OAuth passthrough** | `X-User-Token` header present | User's OAuth token | User identity (per-user auth) |
| **Client credentials** | No user token | `domain-api.auth-token` config | Service account (machine-to-machine) |

### Token Flow

```
Browser → Gateway (X-User-Token: <oauth-token>)
        ↓
        Extracts token, injects into MCP args as _userToken
        ↓
Gateway → MCP Server (tools/call with _userToken in arguments)
          ↓
          Extracts _userToken, falls back to service token if absent
          ↓
MCP Server → Domain API (Authorization: Bearer <token>)
```

### Configuration

```yaml
# mcp-server application.yml
domain-api:
  url: ${DOMAIN_API_URL:http://localhost:8082}
  auth-token: ${DOMAIN_API_TOKEN:dummy-oauth-token-for-poc}  # POC default token
```

For production: replace `dummy-oauth-token-for-poc` with a real OAuth2 client credentials token or service account JWT.

---

## Tool Parameter Mapping

### How the LLM Maps Natural Language to Parameters

The `generate_report` tool defines its parameters via `@McpToolParam` annotations. These generate a JSON schema the LLM uses:

```json
{
  "name": "generate_report",
  "description": "Generate a structured report from domain data",
  "inputSchema": {
    "type": "object",
    "properties": {
      "reportType": { "type": "string", "description": "Type of report" },
      "startDate": { "type": "string", "description": "Start date YYYY-MM-DD" },
      "endDate": { "type": "string", "description": "End date YYYY-MM-DD" },
      "region": { "type": "string", "description": "Region filter" }
    },
    "required": ["reportType"]
  }
}
```

| User Prompt | Resolved Parameters |
|-------------|-------------------|
| "Show me revenue" | `{reportType: "revenue"}` |
| "Revenue for us-east" | `{reportType: "revenue", region: "us-east"}` |
| "Revenue from Jan to Mar" | `{reportType: "revenue", startDate: "2026-01-01", endDate: "2026-03-31"}` |
| "All orders for eu-west last quarter" | `{reportType: "orders", region: "eu-west", startDate: "2025-10-01", endDate: "2025-12-31"}` |

### What Happens When the LLM Maps Incorrectly

1. **Unknown tool name** → `INVALID_TOOL_CALL`
2. **Invalid parameter format** (bad date, special chars) → `INVALID_TOOL_CALL`
3. **Semantically wrong value** (e.g., `reportType: "holidays"`) → Caught by regex pattern

---

## Adding New Domain Endpoints

To add a new report type:

1. **Domain API**: Add a new handler in `ReportStreamController`
2. **MCP Server**: No changes needed — `generate_report` forwards all params to Domain API
3. **Gateway**: Update `ToolCallValidator` regex to include the new `reportType` value

---

## Project Structure

```
mcp-poc/
├── report-gateway/          # Spring Cloud Gateway + MCP Client
│   └── src/main/java/com/example/gateway/
│       ├── controller/AiController.java        # WebFlux SSE, injection check, auth extraction
│       ├── config/McpClientConfig.java         # Streamable HTTP transport
│       ├── service/McpClientService.java       # MCP tool execution, token forwarding
│       ├── service/MockLlmProvider.java        # NL → ToolCall (mock)
│       ├── service/ToolCallValidator.java      # Tool allowlist + regex validation
│       ├── service/PromptInjectionDetector.java # Injection pattern detection
│       ├── filter/RequestLoggingWebFilter.java # Rate limiting + correlation IDs
│       └── static/index.html                   # Chat UI
├── mcp-server/              # Spring AI MCP Server
│   └── src/main/java/com/example/mcp/
│       ├── tool/ReportTools.java               # @McpTool + input sanitization
│       └── service/ReportStreamService.java    # WebClient → Domain API with Bearer auth
├── domain-api/              # Spring Boot Domain API
│   └── src/main/java/com/example/domain/
│       └── controller/ReportStreamController.java  # NDJSON streaming
├── ARCHITECTURE_V2.md       # Detailed architecture with Mermaid diagrams
└── docker-compose.yml       # All three services
```

## Technology Stack

| Layer | Technology |
|-------|------------|
| Gateway | Spring Cloud Gateway 2025.1.1 (WebFlux) |
| MCP Client | `io.modelcontextprotocol.sdk:mcp:0.17.0` (Streamable HTTP) |
| MCP Server | Spring AI MCP Server 1.1.5 (`protocol: STREAMABLE`) |
| Domain API | Spring Boot 4.0.0 (WebMVC) |
| Runtime | Java 21 |

## Why Raw MCP SDK in Gateway?

Spring AI MCP Client starter caused Jackson 3.x vs 2.x conflicts with Spring Cloud Gateway. The raw MCP SDK (`io.modelcontextprotocol.sdk:mcp`) has no Spring dependencies and works cleanly with Jackson 2.x.

## Why Streamable HTTP?

SSE transport was deprecated in MCP spec 2025-03-26. Streamable HTTP uses a single `POST /mcp` endpoint (no separate SSE connection), manages sessions via `Mcp-Session-Id` header, and works better with standard HTTP infrastructure (load balancers, proxies).
