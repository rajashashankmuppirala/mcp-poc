# Report Generator — MCP POC

A natural language report generation system using the Model Context Protocol (MCP) with Streamable HTTP transport, multi-server routing, and a skills-based multi-agent system.

## Quick Start

```bash
# Build all modules
mvn clean package -DskipTests

# Terminal 1: Domain API
java -jar domain-api/target/domain-api-0.0.1-SNAPSHOT.jar --server.port=8082

# Terminal 2: Reports MCP Server
java -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar --server.port=8081

# Terminal 3: Operations MCP Server
java -jar ops-mcp-server/target/ops-mcp-server-0.0.1-SNAPSHOT.jar --server.port=8083

# Terminal 4: Gateway
java -jar report-gateway/target/report-gateway-0.0.1-SNAPSHOT.jar --server.port=8080

# Browser: http://localhost:8080
```

Or with Docker Compose:

```bash
docker compose up --build
```

## System Overview

```
Browser (chat UI) → Gateway (:8080) → LLM → MCP Client → ┬─→ Reports MCP Server (:8081) → Domain API (:8082)
                                                            └─→ Ops MCP Server (:8083)
```

Four services, each with a distinct responsibility:

| Service | Port | Role |
|---------|------|------|
| **report-gateway** | 8080 | AI Gateway + MCP Client. Converts natural language to tool calls, matches skills, orchestrates multi-server execution, streams results |
| **mcp-server** | 8081 | Reports MCP Server. Exposes `generate_report` tool that calls the Domain API |
| **ops-mcp-server** | 8083 | Operations MCP Server. Exposes `list_failed_jobs` and `list_successful_dataflows` tools |
| **domain-api** | 8082 | Domain API. Generates report data and streams rows as NDJSON |

The gateway has a **dual role**:
- **Control plane**: Receives user prompts, matches skills, calls the LLM to resolve them into structured tool calls
- **Data plane**: Acts as an MCP Client, invoking tools on multiple MCP Servers via Streamable HTTP with automatic tool-to-server routing

For detailed architecture diagrams, see [ARCHITECTURE_V2.md](ARCHITECTURE_V2.md).

---

## Skills System (Multi-Agent)

The gateway implements a **skills-based routing system** that narrows the LLM's tool context per request. Skills are defined as **Markdown files** on the gateway and validated against MCP server capabilities at startup.

| Skill | Triggers | MCP Server | Allowed Tools |
|-------|----------|------------|---------------|
| `report_analyst` | report, revenue, sales, analytics, dashboard | reports | `generate_report` |
| `operations_monitor` | job, failed, failure, dataflow, pipeline, status | ops | `list_failed_jobs`, `list_successful_dataflows` |
| `chart_builder` | chart, graph, plot, visualize, bar chart, line chart, pie chart | reports | `generate_report`, `list_failed_jobs`, `list_successful_dataflows` |

### How Skills Work

1. **Markdown files** — Each skill is a `.md` file in `src/main/resources/skills/` with YAML frontmatter for metadata (name, triggers, mcp_server, allowed_tools) and a markdown body for the agent system prompt
2. **Server validation** — At startup, the gateway calls `GET /skills` on each MCP server to validate that declared skills and tools are actually available. Mismatches are logged as warnings; new server-discovered skills are added dynamically
3. **Keyword matching** — `SkillRegistry` scans the prompt for trigger keywords from frontmatter (<10ms match)
4. **Tool scoping** — When a skill matches, only its `allowed_tools` are sent to the LLM, reducing attention dilution
5. **System prompt injection** — The markdown body becomes the LLM's system prompt for behavioral guidance

### Skill File Example

```markdown
---
name: report_analyst
description: Generate and analyze business reports
mcp_server: reports
triggers: [report, revenue, sales, analytics, dashboard, summary]
allowed_tools: [generate_report]
---

# Report Analyst

You are a business report analyst specializing in revenue,
sales, and analytics data.

When the user asks for a report:
1. Identify the report type from their request
2. Extract region if mentioned
3. Use date ranges if provided
```

### MCP Server Skills Endpoint

Each MCP server exposes a `GET /skills` endpoint that returns its capabilities:

```json
[
  {
    "name": "report_analyst",
    "description": "Generate and analyze business reports",
    "triggers": ["report", "revenue", "sales", "analytics"],
    "allowedTools": ["generate_report"]
  }
]
```

This endpoint serves two purposes:
- **Validation**: Gateway compares server-reported skills against local markdown definitions and logs warnings for mismatches
- **Dynamic discovery**: If a server exposes a skill not defined locally, it is automatically registered

### Example Flows

```
"Show me revenue report" → report_analyst skill → {generate_report} → reports MCP server
"Show me failed jobs" → operations_monitor skill → {list_failed_jobs, list_successful_dataflows} → ops MCP server
"What is the weather?" → no skill match → all tools → fallback message
```

### Why Markdown Skills?

- **Industry standard**: Same pattern used by Claude Code skills, GitHub Copilot Skills, and Cursor Rules
- **Rich instructions**: Full markdown body as system prompt — multi-paragraph instructions, examples, constraints
- **Independent files**: Add/remove skills without touching shared config — different teams can own different skills
- **Git-friendly**: Each skill is its own file with clean diffs and ownership
- **Server-synced**: Skills are validated against MCP server capabilities at startup, ensuring declared skills match actual tool availability

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
- Suspicious characters (```` ``` ```, `<<`, `>>`)
- Length limit: 4000 characters

If any pattern matches, returns `400 PROMPT_INJECTION`.

#### Step 4: Gateway — Skill Matching

The `SkillRegistry` matches the prompt against skill triggers:
- If matched → filter tools to skill's `allowed_tools`, attach skill's `systemPrompt`
- If no match → use all discovered tools, no system prompt

#### Step 5: Gateway → LLM (Natural Language → ToolCall)

```java
ToolCall toolCall = llmProvider.generateToolCall(
    "Show me revenue for us-east from January to March",
    List.of(ToolDefinition.generate_report()),
    "You are a business report analyst..."  // skill system prompt
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

#### Step 6: Gateway — Security Layer 3 (Tool Call Validation)

The `ToolCallValidator` checks:
- Tool name is in the allowlist (`generate_report` ✓)
- `reportType` matches `^[a-zA-Z_]+$` ✓
- `startDate`/`endDate` match `^\d{4}-\d{2}-\d{2}$` ✓
- `region` matches `^[a-z]+-[a-z]+$` ✓

If validation fails, returns `400 INVALID_TOOL_CALL`.

#### Step 7: Gateway → MCP Server (Auto-Routed)

The `McpClientService` looks up which server owns the tool, then POSTs:

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

#### Step 8: MCP Server → Domain API Streams Report Data

```json
{"type":"header","data":{"reportType":"revenue","generatedAt":"2026-01-15"}}
{"type":"data","data":{"row":1,"product":"Widget A","revenue":15000}}
...
{"type":"footer","data":{"totalRows":50,"totalRevenue":1250000}}
```

#### Step 9: MCP Server → MCP Client → Gateway → Browser

SSE events stream back to the browser for progressive rendering.

---

## Operations Queries

### Example: "Show me failed jobs in the last 24 hours"

The same flow applies, but with different skill routing:

1. `SkillRegistry` matches `operations_monitor` (trigger: "failed jobs")
2. LLM sees only `{list_failed_jobs, list_successful_dataflows}` — not `generate_report`
3. LLM returns `ToolCall(tool="list_failed_jobs", params={hours: 24})`
4. `McpClientService` auto-routes to `ops-mcp-server` on port 8083
5. Ops server returns tabular JSON of failed jobs

### Example: "Show me successful dataflows in the last 7 days"

Same skill match, different tool:

1. LLM returns `ToolCall(tool="list_successful_dataflows", params={days: 7})`
2. Auto-routed to `ops-mcp-server`
3. Returns dataflow records with source, destination, rows processed, duration

---

## Download Mode Flow

### Example: "Download revenue report"

The download flow differs at the browser level — the backend flow is identical (same security layers, same auth).

| Aspect | Normal Mode | Download Mode |
|--------|-------------|---------------|
| **Trigger** | Any prompt | Prompt contains "download" |
| **Rendering** | Progressive row-by-row | Waits for full response |
| **First event** | Data row | Filename (e.g., `generate_report-1234567890.csv`) |
| **Browser action** | Render in chat bubble | Create Blob → trigger download |
| **Output** | Visible in chat | File saved to Downloads folder |

---

## Custom Chart Generation

When a user asks for a chart, graph, or visualization, the gateway runs a **two-phase pipeline**:

### Example: "Show me a bar chart of revenue by region"

Chart requests use a **dedicated JSON endpoint** (`POST /ai/chart`) — no SSE framing since the Vega-Lite spec is produced as a single response.

```
POST /ai/chart
{"prompt": "Show me a bar chart of revenue by region"}

→ chart_builder skill matched
→ Phase 1: LLM plans data query → ToolCall(generate_report, {reportType: "revenue"})
→ Phase 2a: MCP executes tool → collects NDJSON data
→ Phase 2b: LLM generates Vega-Lite JSON spec
→ 200 application/json
{
  "chartType": "bar",
  "title": "Revenue by Region",
  "vegaLiteSpec": { "$schema": "...", "mark": "bar", ... },
  "dataSummary": "..."
}
```

| Phase | Component | Action |
|-------|-----------|--------|
| **1. Plan** | `ChartGenerationService.planDataQuery()` | LLM decides which data tool to call and with what params |
| **2a. Fetch** | `McpClientService.executeToolCall()` | Execute the planned tool, collect NDJSON data into single string |
| **2b. Render** | `ChartGenerationService.generateChartSpec()` | LLM creates Vega-Lite JSON spec from the data |
| **3. Return** | `POST /ai/chart` | Return `200 application/json` — browser passes spec to `vega-embed` |

**Why a dedicated endpoint?** Chart specs are complete units — no incremental streaming needed. JSON avoids SSE parsing overhead and makes the endpoint independently testable.

### Supported Chart Types

| Chart Type | Trigger Keywords | Data Source |
|------------|-----------------|-------------|
| Bar chart | "bar chart", "breakdown", "by region" | `generate_report`, `list_failed_jobs`, `list_successful_dataflows` |
| Line chart | "line chart", "trend", "over time" | Same as above |
| Pie chart | "pie chart", "proportion", "distribution" | Same as above |

---

## LLM Integration

The gateway uses a pluggable `LlmProvider` interface to convert natural language prompts into structured tool calls. Two providers ship with the project:

### Providers

| Provider | Config Value | Use Case |
|----------|-------------|----------|
| **MockLlmProvider** | `mock` | Testing, offline development — uses keyword matching |
| **GenericOpenAiProvider** | `openai-compatible` | Any OpenAI Chat Completions API compatible endpoint |

### Configuration

```yaml
llm:
  provider: ${LLM_PROVIDER:mock}       # mock | openai-compatible
  fallback-message: ${LLM_FALLBACK_MESSAGE:Sorry, I cannot help with this request.}
  openai:
    endpoint: ${LLM_ENDPOINT:https://api.openai.com/v1}
    api-key: ${LLM_API_KEY:}
    model: ${LLM_MODEL:gpt-4o}
```

### Supported Endpoints (openai-compatible)

| Provider | Endpoint URL | Example Model |
|----------|-------------|---------------|
| OpenAI | `https://api.openai.com/v1` | `gpt-4o` |
| MiniMax | `https://api.minimax.chat/v1` | `MiniMax-M2` |
| Ollama (local) | `http://localhost:11434/v1` | `llama3` |
| Groq | `https://api.groq.com/openai/v1` | `llama-3.1-70b` |
| OpenRouter | `https://openrouter.ai/api/v1` | `anthropic/claude-sonnet-4-20250514` |

### Quick Start with a Real LLM

**MiniMax:**
```bash
LLM_PROVIDER=openai-compatible \
LLM_ENDPOINT=https://api.minimax.chat/v1 \
LLM_API_KEY=your-minimax-key \
LLM_MODEL=MiniMax-M2 \
java -jar report-gateway/target/report-gateway-0.0.1-SNAPSHOT.jar --server.port=8080
```

**Ollama (local):**
```bash
ollama pull llama3
LLM_PROVIDER=openai-compatible \
LLM_ENDPOINT=http://localhost:11434/v1 \
LLM_MODEL=llama3 \
java -jar report-gateway/target/report-gateway-0.0.1-SNAPSHOT.jar --server.port=8080
```

**Docker Compose:**
```bash
LLM_PROVIDER=openai-compatible LLM_API_KEY=sk-xxx LLM_MODEL=gpt-4o docker compose up --build
```

### LLM Interface

```java
public interface LlmProvider {
    ToolCall generateToolCall(String userMessage, List<ToolDefinition> tools, String systemPrompt);
    String generateText(String userMessage, String systemPrompt);
    String providerName();
}
```

New providers only need to implement this interface and annotate with `@ConditionalOnProperty(name = "llm.provider", havingValue = "your-provider-id")`.

---

## Security Architecture

### Defense-in-Depth Layers

```
Layer 1: RequestLoggingWebFilter     → Rate limiting (per IP, 60 req/min)
Layer 2: PromptInjectionDetector     → LLM prompt injection detection
Layer 3: Skill scoping               → Tool allowlist per skill domain
Layer 4: ToolCallValidator           → Tool allowlist + parameter regex validation
Layer 5: MCP Server input sanitize   → Input sanitization + auth token forwarding
Layer 6: AuditLogger                 → All requests logged with correlation IDs
```

| Layer | Component | What It Blocks | Response on Violation |
|-------|-----------|----------------|----------------------|
| 1 | `RequestLoggingWebFilter` | Rate abuse (DoS) | `429 Too Many Requests` |
| 2 | `PromptInjectionDetector` | LLM prompt injection | `400 PROMPT_INJECTION` |
| 3 | `SkillRegistry` | Cross-domain tool access | Skill-scoped tool subset |
| 4 | `ToolCallValidator` | Unknown tools, bad params | `400 INVALID_TOOL_CALL` |
| 5 | `OpsTools.sanitize()` / `ReportTools.sanitize()` | Injection through MCP params | Strips non-alphanumeric chars |
| 6 | `AuditLogger` | N/A (observability) | Audit trail for compliance |

### Audit Logging

Every request is logged with:
- Correlation ID (UUID)
- Event type (REQUEST, TOOL_CALL, ERROR)
- Skill matched
- Tools available count
- No sensitive data (prompts, tokens, or parameters are excluded)

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

---

## Project Structure

```
mcp-poc/
├── report-gateway/          # Spring Cloud Gateway + MCP Client
│   └── src/main/java/com/example/gateway/
│       ├── controller/AiController.java        # WebFlux SSE (/ai/request), chart JSON (/ai/chart)
│       ├── config/McpClientConfig.java         # Multi-server MCP client beans
│       ├── config/ToolDiscoveryInitializer.java # Tool discovery + skill validation against servers
│       ├── service/McpClientService.java       # Multi-server tool execution, auto-routing
│       ├── service/SkillRegistry.java           # Markdown skills + server capability validation
│       ├── service/LlmProvider.java             # Interface for pluggable LLM providers
│       ├── service/MockLlmProvider.java         # NL → ToolCall (keyword-based mock)
│       ├── service/GenericOpenAiProvider.java   # NL → ToolCall (OpenAI-compatible REST API)
│       ├── service/ToolCallValidator.java       # Tool allowlist + regex validation
│       ├── service/PromptInjectionDetector.java # Injection pattern detection
│       ├── service/AuditLogger.java             # Audit trail with correlation IDs
│       ├── service/ChartGenerationService.java  # Two-phase chart planning + Vega-Lite generation
│       ├── filter/RequestLoggingWebFilter.java  # Rate limiting + correlation IDs
│       ├── model/SkillDefinition.java           # Skill record (name, triggers, tools)
│       ├── model/ChartResponse.java             # Chart response record (vegaLiteSpec)
│       └── static/index.html                    # Chat UI
│   └── src/main/resources/skills/
│       ├── report_analyst.md                    # Business report agent skill
│       ├── operations_monitor.md                # Operations monitoring agent skill
│       └── chart_builder.md                     # Chart visualization agent skill
├── mcp-server/              # Reports MCP Server
│   └── src/main/java/com/example/mcp/
│       ├── tool/ReportTools.java                # @McpTool generate_report
│       ├── service/ReportStreamService.java     # WebClient → Domain API
│       ├── controller/SkillsController.java     # GET /skills — exposes skill metadata
│       └── model/SkillDefinition.java           # Skill record for /skills endpoint
├── ops-mcp-server/          # Operations MCP Server
│   └── src/main/java/com/example/ops/
│       ├── tool/OpsTools.java                   # @McpTool list_failed_jobs, list_successful_dataflows
│       ├── service/OpsDataService.java          # Simulated ops data generation
│       ├── controller/SkillsController.java     # GET /skills — exposes skill metadata
│       └── model/SkillDefinition.java           # Skill record for /skills endpoint
├── domain-api/              # Spring Boot Domain API
│   └── src/main/java/com/example/domain/
│       └── controller/ReportStreamController.java  # NDJSON streaming
├── ARCHITECTURE_V2.md       # Detailed architecture with Mermaid diagrams
└── docker-compose.yml       # All four services
```

## Technology Stack

| Layer | Technology |
|-------|------------|
| Gateway | Spring Cloud Gateway 2025.1.1 (WebFlux) |
| MCP Client | `io.modelcontextprotocol.sdk:mcp:0.17.0` (Streamable HTTP) |
| MCP Server | Spring AI MCP Server 1.1.5 (`protocol: STREAMABLE`) |
| Skills | Markdown files with YAML frontmatter (industry standard pattern) |
| LLM | Pluggable `LlmProvider` interface — mock or any OpenAI-compatible endpoint |
| Charts | Two-phase LLM pipeline → Vega-Lite JSON spec → client-side rendering |
| Domain API | Spring Boot 4.0.0 (WebMVC) |
| Runtime | Java 21 |

## Why Raw MCP SDK in Gateway?

Spring AI MCP Client starter caused Jackson 3.x vs 2.x conflicts with Spring Cloud Gateway. The raw MCP SDK (`io.modelcontextprotocol.sdk:mcp`) has no Spring dependencies and works cleanly with Jackson 2.x.

## Why Streamable HTTP?

SSE transport was deprecated in MCP spec 2025-03-26. Streamable HTTP uses a single `POST /mcp` endpoint (no separate SSE connection), manages sessions via `Mcp-Session-Id` header, and works better with standard HTTP infrastructure (load balancers, proxies).
