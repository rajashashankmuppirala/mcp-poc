# System Flow Guide: Discovery, RAG, and Conversational Memory

This document walks through the complete end-to-end flows — from gateway startup through tool discovery, RAG auto-ingestion, user queries, conversational follow-ups, and runtime sync.

---

## 1. Startup: Tool Discovery + RAG Auto-Ingestion

When the gateway starts, `ToolDiscoveryInitializer` runs after the Spring context is ready.

### 1.1 Tool Discovery

```
Gateway Startup
  ↓
ToolDiscoveryInitializer.run()
  ↓
For each MCP server configured:
  POST /mcp  {"method": "initialize"}
  POST /mcp  {"method": "tools/list"}

MCP Servers respond:
  reports (8081): [generate_report]
  ops (8083):     [list_failed_jobs, list_successful_dataflows]
  ↓
McpClientService caches:
  allTools = [generate_report, list_failed_jobs, list_successful_dataflows]
  toolServerMap = {
    generate_report -> reports,
    list_failed_jobs -> ops,
    list_successful_dataflows -> ops
  }
```

### 1.2 RAG Auto-Ingestion

After tool discovery, `RagAutoIngestionService.ingestAll(allTools)` runs:

**Tool schemas** — each tool becomes a RAG document:

| Document ID | Title | Content | Tags |
|-------------|-------|---------|------|
| `auto-tool:generate_report` | Tool: generate_report | Tool Name, Description, Parameters (reportType required, region optional, etc.) | `tool`, `mcp`, `generate-report` |
| `auto-tool:list_failed_jobs` | Tool: list_failed_jobs | Tool Name, Description, Parameters (hours optional) | `tool`, `mcp`, `list-failed-jobs` |
| `auto-tool:list_successful_dataflows` | Tool: list_successful_dataflows | Tool Name, Description, Parameters (days optional) | `tool`, `mcp`, `list-successful-dataflows` |

**Report schemas** — fetched from Domain API:

```
GET http://localhost:8082/api/v1/reports/metadata
→ Returns 8 report schemas:
```

| Document ID | Title | Content | Tags |
|-------------|-------|---------|------|
| `auto-report:revenue` | Report: revenue Report | Report Type, Description, Required Params (dateRange, region), Available Regions (US, EU, APAC), Example Queries | `report`, `revenue`, `data`, `regional` |
| `auto-report:churn` | Report: churn Report | ... | `report`, `churn`, `data`, `regional` |
| `auto-report:usage` | Report: usage Report | ... | `report`, `usage`, `data`, `regional` |
| (5 more report types) | | | |

**Key point**: All auto-ingested docs use **deterministic IDs** (`auto-tool:*`, `auto-report:*`). On re-ingestion, old docs with matching prefixes are deleted first — preventing duplicates.

---

## 2. User Query: First Request

### Example: "Show me Q3 revenue as a pie chart"

```
POST /ai/request
{"prompt": "Show me Q3 revenue as a pie chart"}
```

### Step-by-Step Flow

**Step 1 — Security layers**: Rate limiting + prompt injection check (passes).

**Step 2 — Skill matching**: `SkillRegistry` scans prompt for trigger keywords:
- "revenue" matches `report_analyst` skill
- "pie chart" matches `chart_builder` skill
- Revenue is more specific → `report_analyst` wins (or chart_builder if higher priority)

**Step 3 — RAG retrieval**: `ContextInjectorImpl.retrieveRagContext("pie chart revenue Q3")`:

```
Query tokens: ["pie", "chart", "revenue", "q3"]

Scoring against all chunks:
  auto-report:revenue chunk 1: "Revenue breakdown report. Required params: dateRange, region"
    Keywords: [revenue, breakdown, report, required, params, daterange, region]
    Intersection: [revenue] → score = 1 / (4 + 7 - 1) = 0.1

  auto-tool:generate_report chunk 1: "Generate a report. Parameters: reportType, region"
    Keywords: [generate, report, parameters, reporttype, region]
    Intersection: [] → score = 0

  auto-report:revenue chunk 2: "Example queries: show me revenue for last quarter"
    Keywords: [example, queries, show, revenue, last, quarter]
    Intersection: [revenue] → score = 1 / (4 + 6 - 1) = 0.11

Top 3 chunks selected (those with score > 0).
```

**Step 4 — Prompt assembly**: `ContextInjectorImpl.buildContextPrompt()`:

```
=== Relevant Knowledge Base Context ===
[chunk: auto-report:revenue, score=0.11] Example queries: show me revenue for last quarter
[chunk: auto-report:revenue, score=0.10] Revenue breakdown report. Required params: dateRange, region
...

=== Date Reference (today is 2026-05-08) ===
- "this year" → startDate: 2026-01-01, endDate: 2026-12-31
- "last year" → startDate: 2025-01-01, endDate: 2025-12-31
- "Q1" → startDate: 2026-01-01, endDate: 2026-03-31
- "Q2" → startDate: 2026-04-01, endDate: 2026-06-30
- "Q3" → startDate: 2026-07-01, endDate: 2026-09-30
- "Q4" → startDate: 2026-10-01, endDate: 2026-12-31

=== Current Request ===
User: Show me Q3 revenue as a pie chart
```

**Step 5 — LLM tool call**: LLM receives the assembled prompt + scoped tools + system prompt:

```json
{
  "tool": "generate_report",
  "parameters": {
    "reportType": "revenue",
    "region": "US",
    "startDate": "2026-07-01",
    "endDate": "2026-09-30"
  }
}
```

**Step 6 — Validation**: `ToolCallValidator` checks tool name in allowlist, params match regex patterns.

**Step 7 — MCP execution**: `McpClientService` looks up `generate_report → reports server`:

```
POST http://localhost:8081/mcp
{"method": "tools/call", "params": {"name": "generate_report", "arguments": {...}}}
```

**Step 8 — Report data streams back**: MCP Server → Domain API → NDJSON rows → Gateway → Browser SSE (separate from MCP transport; gateway uses Streamable HTTP to talk to MCP servers, SSE to stream to browser).

---

## 3. Conversational Follow-Up

### Example: "Now show it as a bar chart"

```
POST /ai/request
{"prompt": "Now show it as a bar chart"}
Cookie: session-id=abc-123
```

### Step-by-Step Flow

**Step 1 — Load session**: `ConversationService.loadOrCreateSession("abc-123")`:

```
SessionStore.find("abc-123")
→ Returns existing session with Turn 1:
  {
    turnNumber: 1,
    userPrompt: "Show me Q3 revenue as a pie chart",
    extractedFilters: {
      reportType: "revenue",
      region: "US",
      startDate: "2026-07-01",
      endDate: "2026-09-30"
    },
    responseType: "pie_chart"
  }
```

**Step 2 — Context injection**: `ContextInjectorImpl.buildContextPrompt("Now show it as a bar chart", session)`:

```
=== Relevant Knowledge Base Context ===
[chunk: auto-report:revenue, score=0.11] Example queries: show me revenue for last quarter
...

=== Date Reference ===
(today is 2026-05-08, Q3 mappings, etc.)

=== Conversation History ===
Turn 1:
  User: Show me Q3 revenue as a pie chart
  [Filters: reportType=revenue, region=US, startDate=2026-07-01, endDate=2026-09-30]
  Response: pie_chart

=== Current Request ===
User: Now show it as a bar chart
```

**Step 3 — LLM tool call**: The LLM sees the conversation history and **inherits filters**:

```json
{
  "tool": "generate_report",
  "parameters": {
    "reportType": "revenue",       ← inherited from Turn 1
    "region": "US",                ← inherited from Turn 1
    "startDate": "2026-07-01",    ← inherited from Turn 1
    "endDate": "2026-09-30"       ← inherited from Turn 1
  }
}
```

**Step 4 — Filter merge**: `ExtractedFilters.mergeWithContext()` fills in missing params from previous turn.

**Step 5 — Execute & stream**: Same MCP execution as before, but this time the report comes back as a bar chart.

**Step 6 — Save turn**: `ConversationService.saveTurn()` appends Turn 2 to the session.

### Pronoun Resolution Examples

| Follow-up | What LLM Inherits | What Changes |
|-----------|-------------------|--------------|
| "Show **it** as a bar chart" | reportType, region, dates | Chart type only |
| "What about **Europe**?" | reportType, dates | Region → EU |
| "**Same thing** for last month" | reportType, region | Dates → last month |
| "Give me the **numbers**" | reportType, region, dates | Format → raw numbers |

---

## 4. Runtime Sync: Keeping Tools Fresh

### 4.1 Scheduled Sync (Every 5 Minutes)

`SyncScheduler` fires `syncAll()`:

```
SyncScheduler.run()
  ↓
SyncService.syncAll()
  ↓
1. Re-discover tools from all MCP servers:
   - reports (8081): POST /mcp {"method": "tools/list"}
     → [generate_report, list_dashboards, export_csv]
   - ops (8083): POST /mcp {"method": "tools/list"}
     → [list_failed_jobs, list_successful_dataflows]
   ↓
2. Tool cache updated:
   - Old entries cleared
   - allTools = [generate_report, list_dashboards, export_csv, list_failed_jobs, list_successful_dataflows]
   - toolServerMap rebuilt
   ↓
3. ToolCallValidator.updateAllowedTools() — allowlist refreshed
   ↓
4. RagAutoIngestionService.ingestAll(allTools):
   - clearAutoToolDocs() → deletes all "auto-tool:*" docs
   - clearAutoReportDocs() → deletes all "auto-report:*" docs
   - Re-ingests fresh tool schemas for all 5 tools
   - Re-fetches report schemas from Domain API
   ↓
5. SyncResult logged:
   { success: true, toolCount: 5, ragDocCount: 13, durationMs: 1200, errors: [] }
```

### 4.2 What Happens When a New Tool is Added

Scenario: Developer adds `@McpTool download_pdf` to the reports server and deploys.

**Before sync**:
- Gateway has: `[generate_report, list_dashboards, export_csv]`
- RAG has: 3 auto-tool docs + 8 auto-report docs

**After sync** (scheduled or admin-triggered):
- Gateway has: `[generate_report, list_dashboards, export_csv, download_pdf]`
- RAG has: 4 auto-tool docs + 8 auto-report docs
- User can now ask "download the report as PDF" and the LLM will see `download_pdf` in its tool list

### 4.3 Admin-Triggered Sync (Immediate)

Don't want to wait 5 minutes? Hit the admin endpoint:

```bash
# Full sync — all servers + reports metadata
curl -X POST http://localhost:8080/admin/sync

# Single server sync — reports only
curl -X POST http://localhost:8080/admin/sync/reports

# Reports metadata only — new report type added to Domain API
curl -X POST http://localhost:8080/admin/sync/reports-metadata

# Check current state
curl http://localhost:8080/admin/sync/status
```

Response:
```json
{
  "scope": "server:reports",
  "success": true,
  "toolCount": 4,
  "ragDocCount": 13,
  "durationMs": 234,
  "errors": []
}
```

### 4.4 Error Handling

Sync is **non-blocking and fault-tolerant**:

| Failure | Behavior |
|---------|----------|
| Reports MCP server down | Ops server still syncs, error logged for reports |
| Domain API unreachable | Report schema refresh skipped, tool sync still runs |
| All servers down | SyncResult returns `success: false` with error details |
| RAG store unavailable | Tool discovery still works, RAG ingestion skipped |

---

## 5. Manual Documents vs Auto-Ingested

### Manual Document (User-Uploaded)

```bash
POST /rag/documents
{
  "title": "Sales Playbook 2026",
  "content": "Our sales process follows these steps...",
  "tags": ["sales", "playbook", "process"]
}
```

- Gets a **UUID**: `doc-a1b2c3d4-e5f6-7890-abcd-ef1234567890`
- **Never touched by sync** — survives all re-ingestion cycles
- Chunks and keywords extracted at ingestion time

### Auto-Ingested Document (System-Managed)

- Gets a **deterministic ID**: `auto-tool:generate_report`, `auto-report:revenue`
- **Purged and re-inserted** on every sync to stay fresh
- If a tool's parameter schema changes, the RAG doc updates automatically

### The Prefix System

| Prefix | Source | Lifecycle |
|--------|--------|-----------|
| `auto-tool:*` | MCP server tool discovery | Purged + re-ingested on sync |
| `auto-report:*` | Domain API report metadata | Purged + re-ingested on sync |
| UUID | Manual user upload | Permanent (until user deletes) |

This separation ensures that sync operations never accidentally remove user-contributed knowledge.

---

## 6. Complete Flow: End-to-End with All Layers

### Scenario: New Gateway Starts, User Asks Question, MCP Server Deploys New Tool

```
Timeline:

T+0s    Gateway starts
        ↓
        ToolDiscoveryInitializer discovers 3 tools from reports, 2 from ops
        ↓
        RagAutoIngestionService ingests 3 auto-tool docs + 8 auto-report docs
        ↓
        Total RAG docs: 11

T+30s   User: "Show me revenue for Q3 as a pie chart"
        ↓
        RAG retrieves top 3 chunks (revenue report schema, generate_report tool schema)
        ↓
        ContextInjector assembles prompt: RAG context + date refs + current request
        ↓
        LLM generates ToolCall: generate_report(revenue, Q3 dates)
        ↓
        MCP Server streams report data → Browser renders pie chart
        ↓
        Session saved: Turn 1 (filters: reportType=revenue, Q3 dates)

T+2min  User: "Now show it as a bar chart"
        ↓
        Session loaded, conversation history injected into prompt
        ↓
        LLM sees Turn 1 filters, inherits revenue + Q3 dates
        ↓
        Bar chart rendered
        ↓
        Session saved: Turn 2

T+5min  Scheduled sync fires
        ↓
        Tools re-discovered, RAG re-ingested
        ↓
        Total RAG docs: 11 (same count, fresh data)

T+6min  Developer deploys new @McpTool "download_pdf" to reports server

T+10min Scheduled sync fires
        ↓
        Reports server now returns 4 tools (including download_pdf)
        ↓
        Tool cache updated: allTools = 5 total
        ↓
        RAG cleared auto-tool docs, re-ingests 4 auto-tool docs
        ↓
        Total RAG docs: 12 (4 tools + 8 reports)

T+11min User: "Download the revenue report as PDF"
        ↓
        RAG retrieves download_pdf tool schema
        ↓
        LLM sees download_pdf in tool list, generates ToolCall
        ↓
        PDF download initiated
```

---

## 7. Configuration Reference

### Sync Configuration

```yaml
sync:
  interval-minutes: 5         # How often to run scheduled sync
  initial-delay-minutes: 10   # Wait after startup before first scheduled sync
```

### Session Configuration

```yaml
session:
  storage:
    type: in-memory           # or "redis" for production
  timeout-minutes: 30         # Session TTL
```

### Domain API URL (for report schema ingestion)

```yaml
domain-api:
  url: http://localhost:8082  # Used by RagAutoIngestionService
```

### MCP Server Configuration

```yaml
mcp:
  client:
    servers:
      reports:
        url: http://localhost:8081
        name: "Reports MCP Server"
      ops:
        url: http://localhost:8083
        name: "Operations MCP Server"
```

---

## 8. Key Files Reference

| Component | File | Purpose |
|-----------|------|---------|
| Tool Discovery | `report-gateway/.../config/ToolDiscoveryInitializer.java` | Discovers tools at startup, triggers RAG auto-ingestion |
| Tool Cache | `report-gateway/.../service/McpClientService.java` | `discoverAndCacheTools()`, `refreshServerTools()`, `removeServerTools()` |
| Sync Orchestrator | `report-gateway/.../service/SyncService.java` | `syncAll()`, `refreshServer()`, `refreshReportSchemas()` |
| Sync Scheduler | `report-gateway/.../config/SyncScheduler.java` | `@Scheduled` periodic sync |
| Sync Endpoints | `report-gateway/.../controller/SyncController.java` | `POST /admin/sync/*` |
| RAG Auto-Ingestion | `report-gateway/.../service/RagAutoIngestionService.java` | `ingestAll()`, `refreshToolSchemas()`, `refreshReportSchemas()` |
| RAG Interface | `report-gateway/.../service/RagService.java` | `ingestWithId()`, `retrieve()`, `listAll()`, `delete()` |
| RAG Store | `report-gateway/.../service/InMemoryRagStore.java` | ConcurrentHashMap-backed implementation |
| Context Injection | `report-gateway/.../service/ContextInjectorImpl.java` | `buildContextPrompt()` with RAG + date + history |
| Session Management | `report-gateway/.../service/ConversationService.java` | `loadOrCreateSession()`, `saveTurn()`, `clearSession()` |
| Session Store | `report-gateway/.../service/InMemorySessionStore.java` | Dev/test storage |
| Session Store | `report-gateway/.../service/RedisSessionStore.java` | Production storage |
| Domain API Metadata | `domain-api/.../controller/ReportMetadataController.java` | `GET /api/v1/reports/metadata` |
