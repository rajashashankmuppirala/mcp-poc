# MCP Architecture V2 - Report Generator System

## Overview

This document explains the Model Context Protocol (MCP) architecture implemented in the report generator system, including multi-server routing, markdown-based skills for multi-agent orchestration, component responsibilities, and how each layer integrates.

---

## System Architecture

### Component Overview

```mermaid
flowchart TD
    User[User]
    Browser[Browser Chat UI]
    Gateway[Report Gateway :8080]
    LLM[LLM Provider]
    SkillReg[SkillRegistry]
    RagStore[(RAG Knowledge Store\ntools + reports + docs)]
    MCPClient[MCP Client multi-server]
    ReportsMCP["Reports MCP :8081\nGET /skills"]
    OpsMCP["Ops MCP Server :8083\nGET /skills"]
    ReportTools[ReportTools generate_report]
    OpsTools[OpsTools list_failed_jobs list_successful_dataflows]
    DomainAPI[Domain API :8082]

    User -->|types prompt| Browser
    Browser -->|POST /ai/request| Gateway
    Gateway -->|retrieve relevant context| RagStore
    RagStore -->|top-K chunks| Gateway
    Gateway -->|1. match skill| SkillReg
    SkillReg -->|2. scoped tools + systemPrompt + RAG context| LLM
    LLM -->|3. ToolCall JSON| Gateway
    Gateway -->|4. validate| Gateway
    Gateway -->|5. route to server| MCPClient
    MCPClient -->|reports| ReportsMCP
    MCPClient -->|ops| OpsMCP
    ReportsMCP -->|invoke| ReportTools
    OpsMCP -->|invoke| OpsTools
    ReportTools -->|POST /api/v1/reports/stream| DomainAPI
    OpsTools -->|generates| OpsTools
    DomainAPI -->|streaming rows| ReportTools
    ReportTools -->|JSON array| ReportsMCP
    OpsTools -->|JSON array| OpsMCP
    ReportsMCP -->|Streamable HTTP responses| MCPClient
    OpsMCP -->|Streamable HTTP responses| MCPClient
    MCPClient -->|Flux of rows| Gateway
    Gateway -->|"SSE to browser"| Browser
    Browser -->|renders rows| User
```

### Layered Architecture

```mermaid
flowchart LR
    subgraph BrowserLayer["Browser Layer"]
        UI["Chat UI\nindex.html"]
    end

    subgraph Gateway["Gateway Layer :8080"]
        AiCtrl["AiController\nPOST /ai/request\nGET /ai/tools\nGET /ai/skills\nPOST /ai/chart"]
        ConvCtrl["ConversationController\nGET /session/history\nPOST /session/clear\nGET /session/status"]
        SyncCtrl["SyncController\nPOST /admin/sync/*"]
        RagCtrl["DocumentIngestionController\nPOST/GET/DELETE /rag/documents"]
        InjDet["PromptInjectionDetector"]
        SkillReg["SkillRegistry\nmarkdown files\nkeyword matching"]
        SkillScope["Tool Scoping\nfilter by allowed_tools"]
        LLMProv["LLM Provider\nNL to ToolCall\n+ skill systemPrompt"]
        Validator["ToolCall Validator\nallowlist + regex"]
        McpSvc["MCP Client Service\nmulti-server routing"]
        AuditLog["AuditLogger\ncorrelation ID trail"]
        ConvSvc["ConversationService\nsession lifecycle"]
        CtxInj["ContextInjectorImpl\nRAG + date + history"]
        SessStore[(SessionStore\nRedis / In-Memory)]
        RagSvc["RagService\ningest, retrieve, delete"]
        RagStore[(InMemoryRagStore\nConcurrentHashMap)]
        RagAuto["RagAutoIngestionService\ndeterministic IDs"]
        SyncSvc["SyncService\norchestrator"]
        SyncSched["SyncScheduler\n@Scheduled 5min"]
        ToolDisc["ToolDiscoveryInitializer\nstartup"]
    end

    subgraph Reports["Reports MCP Server :8081"]
        RptTools["@McpTool generate_report"]
        RptStreamSvc["ReportStreamService\nWebClient to Domain API"]
    end

    subgraph Ops["Ops MCP Server :8083"]
        OpsToolList["@McpTool list_failed_jobs"]
        OpsToolDF["@McpTool list_successful_dataflows"]
        OpsData["OpsDataService\nsimulated ops data"]
    end

    subgraph Domain["Domain API :8082"]
        DomainCtrl["ReportStreamController\nNDJSON streaming"]
        MetaCtrl["ReportMetadataController\nGET /reports/metadata"]
    end

    UI --> AiCtrl
    UI --> ConvCtrl
    AiCtrl --> InjDet
    AiCtrl --> ConvSvc
    AiCtrl --> CtxInj
    CtxInj --> RagSvc
    ConvCtrl --> ConvSvc
    ConvSvc --> SessStore
    SyncCtrl --> SyncSvc
    RagCtrl --> RagSvc
    RagSvc --> RagStore
    RagSvc --> RagAuto
    SyncSvc --> RagAuto
    SyncSvc --> McpSvc
    SyncSched --> SyncSvc
    ToolDisc --> McpSvc
    ToolDisc --> RagAuto
    InjDet --> SkillReg
    SkillReg --> SkillScope
    SkillScope --> LLMProv
    LLMProv --> Validator
    Validator --> McpSvc
    McpSvc -->|reports| RptTools
    McpSvc -->|ops| OpsToolList
    McpSvc -->|ops| OpsToolDF
    RptTools --> RptStreamSvc
    OpsToolList --> OpsData
    OpsToolDF --> OpsData
    RptStreamSvc -. HTTP POST .-> DomainCtrl
    RagAuto -. GET /reports/metadata .-> MetaCtrl
```

### Deployment View

```mermaid
flowchart TD
    subgraph User["User Machine"]
        Browser["Browser\nhttp://localhost:8080"]
    end

    subgraph GatewayJVM["Gateway JVM :8080"]
        GApp["report-gateway.jar\nSpring Cloud Gateway WebFlux"]
        GLLM["LLM Provider\nskill-aware, RAG-aware"]
        GSkill["SkillRegistry\nmarkdown skill files"]
        GMcp["MCP Client SDK\nmulti-server routing"]
        GRag["RAG Layer\nauto-ingest + keyword retrieval"]
        GSync["Sync System\nscheduled + admin-triggered"]
    end

    subgraph ReportsJVM["Reports MCP Server JVM :8081"]
        MApp["mcp-server.jar\nSpring Boot WebMVC"]
        MTools["ReportTools\n@McpTool generate_report"]
        MService["ReportStreamService\nWebClient"]
    end

    subgraph OpsJVM["Ops MCP Server JVM :8083"]
        OApp["ops-mcp-server.jar\nSpring Boot WebMVC"]
        OTools["OpsTools\n@McpTool list_failed_jobs\n@McpTool list_successful_dataflows"]
        OData["OpsDataService\nsimulated data"]
    end

    subgraph DomainJVM["Domain API JVM :8082"]
        DApp["domain-api.jar\nSpring Boot WebMVC"]
        DCtrl["ReportStreamController\nNDJSON streaming"]
        DMeta["ReportMetadataController\nGET /reports/metadata"]
    end

    User -->|http://localhost:8080| GatewayJVM
    GatewayJVM -->|reports: :8081/mcp| ReportsJVM
    GatewayJVM -->|ops: :8083/mcp| OpsJVM
    ReportsJVM -->|:8082/api/v1| DomainJVM
    GatewayJVM -. RAG auto-ingest .-> DMeta

    GatewayJVM -. contains .-> GLLM
    GatewayJVM -. contains .-> GSkill
    GatewayJVM -. contains .-> GMcp
    GatewayJVM -. contains .-> GRag
    GatewayJVM -. contains .-> GSync
    ReportsJVM -. contains .-> MTools
    ReportsJVM -. contains .-> MService
    OpsJVM -. contains .-> OTools
    OpsJVM -. contains .-> OData
    DomainJVM -. contains .-> DCtrl
```

---

## Skills System (Multi-Agent)

### What Are Skills

Each skill is a **markdown file** that defines an agent profile:

```
report-gateway/src/main/resources/skills/
├── report_analyst.md
├── operations_monitor.md
└── chart_builder.md
```

### Skill File Structure

```markdown
---
name: report_analyst
description: Generate and analyze business reports
mcp_server: reports
triggers: [report, revenue, sales, income, analytics]
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

### Skill Validation at Startup

Skills are defined as Markdown files on the gateway and validated against MCP server capabilities at startup:

```mermaid
sequenceDiagram
    autonumber
    participant GW as Gateway Startup
    participant SR as SkillRegistry
    participant RMCP as Reports MCP :8081
    participant OMCP as Ops MCP :8083

    GW->>SR: Load skills from markdown files
    Note over SR: Parse frontmatter: name, triggers, mcp_server, allowed_tools
    SR-->>GW: 3 skills loaded

    GW->>RMCP: GET /skills
    RMCP-->>GW: [{name: "report_analyst", ...}, {name: "chart_builder", ...}]
    GW->>SR: validateAgainstServer("reports", serverSkills)
    Note over SR: Compare local vs server tools, log warnings for mismatches

    GW->>OMCP: GET /skills
    OMCP-->>GW: [{name: "operations_monitor", ...}]
    GW->>SR: validateAgainstServer("ops", serverSkills)

    Note over GW,SR: Any server-only skills (not in local markdown) are auto-registered
```

**Validation behavior**:
- If a local skill declares tools not present on the server → **WARNING logged**
- If a server exposes tools not in the local markdown → **INFO logged**
- If a server exposes a skill not defined locally → **skill auto-registered**
- If the `/skills` endpoint is unreachable → **fallback to local skills only**

### How Skills Work

```mermaid
flowchart LR
    subgraph "Startup: Skill Loading"
        MD["Markdown files\nsrc/main/resources/skills/*.md"] --> Parse["Parse frontmatter"]
        Parse --> Local["Local skills loaded"]
        SRV["MCP Servers\nGET /skills"] --> SrvSkills["Server skills discovered"]
        Local --> Merge["Validate + merge"]
        SrvSkills --> Merge
        Merge --> Registry["SkillRegistry\nmerged skill map"]
    end

    subgraph "Runtime: Request Handling"
        Prompt["User prompt\nshow me revenue report"] --> Match["SkillRegistry.matchSkill()\nkeyword scan of triggers"]
        Match -->|matched| Load["Load skill metadata"]
        Load --> Scope["Filter tools to\nallowed_tools only"]
        Load --> Inject["Attach systemPrompt\nfrom markdown body"]
        Scope --> LLM["LLM receives:\n- scoped tools\n- system prompt"]
        Inject --> LLM
    end
```

### Skill Routing Table

| Prompt keyword match | Skill matched | MCP server | Tools visible to LLM |
|---------------------|---------------|------------|---------------------|
| report, revenue, sales, analytics, dashboard | `report_analyst` | reports (8081) | `generate_report` |
| job, failed, dataflow, pipeline, status | `operations_monitor` | ops (8083) | `list_failed_jobs`, `list_successful_dataflows` |
| chart, graph, plot, visualize, visualization, pie chart, bar chart | `chart_builder` | reports (8081) | `generate_report`, `list_failed_jobs`, `list_successful_dataflows` |
| none | no match | all servers | all tools (3) |

---

## End-to-End Request Flow: Report Query

### Example: "Show me revenue for us-east from January to March"

```mermaid
sequenceDiagram
    autonumber
    participant User as User
    participant GW as Gateway :8080
    participant SR as SkillRegistry
    participant LLM as MockLlmProvider
    participant Val as ToolCallValidator
    participant MC as McpClientService
    participant RMCP as Reports MCP :8081
    participant DA as Domain API :8082

    User->>GW: POST /ai/request {prompt: "Show me revenue for us-east"}
    Note over GW,SR: Step 1: Injection check
    Note over GW,SR: Step 2: Skill matching
    GW->>SR: matchSkill("Show me revenue for us-east")
    Note over SR: Scans "revenue" against triggers
    SR-->>GW: report_analyst, tools: [generate_report]

    Note over GW,LLM: Step 3: LLM call with scoped tools
    GW->>LLM: generateToolCall(prompt, [generate_report], systemPrompt)
    LLM-->>GW: ToolCall(tool="generate_report", params={reportType:"revenue", region:"us-east"})

    Note over GW,Val: Step 4: Validate
    GW->>Val: validate(toolCall)
    Note right of Val: tool in allowlist? yes, region matches regex? yes
    Val-->>GW: valid

    Note over GW,DA: Step 5: Execute via MCP
    GW->>MC: executeToolCall(toolCall)
    Note right of MC: toolServerMap: generate_report -> reports
    MC->>RMCP: POST /mcp tools/call generate_report
    RMCP->>DA: POST /api/v1/reports/stream
    DA-->>RMCP: ["row1,data-1,ts", "row2,data-2,ts", ...]
    RMCP-->>MC: Streamable HTTP: data:{"result":{...}}
    MC-->>GW: Flux of parsed rows

    Note over GW,User: Step 6: Stream to browser
    GW-->>User: SSE text/event-stream: row1,data-1,ts
```

---

## End-to-End Request Flow: Operations Query

### Example: "Show me failed jobs in the last 24 hours"

```mermaid
sequenceDiagram
    autonumber
    participant User as User
    participant GW as Gateway :8080
    participant SR as SkillRegistry
    participant LLM as MockLlmProvider
    participant Val as ToolCallValidator
    participant MC as McpClientService
    participant OMCP as Ops MCP :8083
    participant OpsData as OpsDataService

    User->>GW: POST /ai/request {prompt: "Show me failed jobs"}

    Note over GW,SR: Step 1: Skill matching
    GW->>SR: matchSkill("failed jobs")
    Note over SR: "failed" and "jobs" match operations_monitor triggers
    SR-->>GW: operations_monitor, tools: [list_failed_jobs, list_successful_dataflows]

    Note over GW,LLM: Step 2: LLM sees only ops tools
    GW->>LLM: generateToolCall(prompt, [list_failed_jobs, list_successful_dataflows], systemPrompt)
    Note over LLM: "failed jobs" + keyword match -> list_failed_jobs(hours: 24)
    LLM-->>GW: ToolCall(tool="list_failed_jobs", params={hours:24})

    Note over GW,Val: Step 3: Validate
    GW->>Val: validate(toolCall)
    Val-->>GW: valid

    Note over GW,OMCP: Step 4: Auto-route to ops server
    GW->>MC: executeToolCall(toolCall)
    Note right of MC: toolServerMap: list_failed_jobs -> ops
    MC->>OMCP: POST /mcp tools/call list_failed_jobs
    OMCP->>OpsData: getFailedJobs(24, 1)
    OpsData-->>OMCP: 16 failed job records
    OMCP-->>MC: JSON array of failed jobs
    MC-->>GW: Flux of strings
    GW-->>User: data:[{"job_name":"data_warehouse_etl",...}, ...]
```

---

## Download Mode Flow

### Example: "Download revenue report"

```mermaid
sequenceDiagram
    autonumber
    participant User as User
    participant GW as Gateway :8080
    participant SR as SkillRegistry
    participant LLM as MockLlmProvider
    participant MC as McpClientService
    participant RMCP as Reports MCP :8081
    participant DA as Domain API :8082

    User->>GW: POST /ai/request {prompt: "Download revenue report"}
    GW->>SR: matchSkill("Download revenue report")
    SR-->>GW: report_analyst (tools: [generate_report])
    GW->>LLM: generateToolCall(prompt, [generate_report], systemPrompt)
    LLM-->>GW: ToolCall(tool="generate_report", params={reportType:"revenue"})

    GW->>MC: executeToolCall(toolCall)
    MC->>RMCP: POST /mcp tools/call generate_report
    RMCP->>DA: POST /api/v1/reports/stream
    DA-->>RMCP: ["row1,...", "row2,...", ...]
    RMCP-->>MC: JSON-RPC result
    MC-->>GW: Flux of rows

    Note over GW: Detects "download" in prompt, prepends filename as first SSE event
    GW-->>User: SSE: generate_report-1778128768955.csv, row1, row2, ...
```

---

## End-to-End Request Flow: Custom Chart Generation

### Example: "Show me a bar chart of revenue by region"

Chart requests use a **dedicated JSON endpoint** (`POST /ai/chart`) — no SSE framing needed since the Vega-Lite spec is produced as a single response.

```mermaid
sequenceDiagram
    autonumber
    participant User as User
    participant GW as Gateway :8080
    participant SR as SkillRegistry
    participant Chart as ChartGenerationService
    participant LLM as MockLlmProvider
    participant Val as ToolCallValidator
    participant MC as McpClientService
    participant RMCP as Reports MCP :8081
    participant DA as Domain API :8082

    User->>GW: POST /ai/chart {prompt: "Show me a bar chart of revenue by region"}
    GW->>SR: matchSkill("bar chart of revenue")
    Note over SR: "bar chart" and "revenue" match chart_builder triggers
    SR-->>GW: chart_builder, tools: [generate_report, ...]

    Note over GW,Chart: Phase 1: Planning
    GW->>Chart: planDataQuery(prompt, tools, systemPrompt)
    Chart->>LLM: "Respond with ONLY a tool call..."
    LLM-->>Chart: ToolCall(tool="generate_report", params={reportType:"revenue"})
    Chart-->>GW: ToolCall (planned)

    Note over GW,Val: Phase 2a: Validate & Fetch Data
    GW->>Val: validate(toolCall)
    Val-->>GW: valid
    GW->>MC: executeToolCall(toolCall)
    MC->>RMCP: POST /mcp tools/call generate_report
    RMCP->>DA: POST /api/v1/reports/stream
    DA-->>RMCP: NDJSON rows
    RMCP-->>MC: JSON array of data
    MC-->>GW: rawData (collected rows)

    Note over GW,Chart: Phase 2b: Generate Vega-Lite Spec
    GW->>Chart: generateChartSpec(prompt, rawData, null, systemPrompt)
    Chart->>LLM: "Create a Vega-Lite JSON spec..."
    LLM-->>Chart: Vega-Lite JSON spec
    Chart-->>GW: ChartResponse(vegaLiteSpec)

    Note over GW,User: Step 6: Return as JSON (not SSE)
    GW-->>User: 200 application/json {chartType:"bar", title:"Revenue by Region", vegaLiteSpec:{...}}
```

### How It Works

1. **Skill Match**: `chart_builder` is matched via triggers (chart, graph, plot, visualize, bar chart, etc.)
2. **Phase 1 — Plan**: `ChartGenerationService.planDataQuery()` asks the LLM which data tool to call and with what parameters
3. **Phase 2a — Fetch**: The planned tool call is validated and executed via MCP client, returning raw NDJSON data (collected into a single string)
4. **Phase 2b — Render**: `ChartGenerationService.generateChartSpec()` gives the raw data to the LLM with a prompt to create a Vega-Lite JSON specification
5. **Return JSON**: `POST /ai/chart` returns `200 application/json` with `ChartResponse` — browser passes `vegaLiteSpec` directly to `vega-embed`

**Why a dedicated endpoint instead of SSE?** Chart specs are generated as a complete unit — there's no incremental data to stream. A JSON response avoids unnecessary SSE parsing overhead on the client and makes the endpoint independently testable.

### Chart Builder Skill

```markdown
---
name: chart_builder
description: Create custom charts and visualizations from data
mcp_server: reports
triggers: [chart, graph, plot, visualize, visualization, bar chart, line chart, pie chart, dashboard, breakdown]
allowed_tools: [generate_report, list_failed_jobs, list_successful_dataflows]
---

# Chart Builder

You are a data visualization specialist. When users ask for charts:

## Phase 1: Data Query
- For business data: use `generate_report`
- For failed jobs: use `list_failed_jobs`
- For dataflow status: use `list_successful_dataflows`

## Phase 2: Chart Spec Generation
After receiving the data, create a Vega-Lite JSON specification...
```

---

## Skills Discovery and Loading

### Startup Flow

```mermaid
sequenceDiagram
    autonumber
    participant Spring as Spring Boot
    participant SR as SkillRegistry
    participant FS as classpath:skills/*.md
    participant MC as McpClientService
    participant RMCP as Reports MCP Server
    participant OMCP as Ops MCP Server

    Note over Spring,SR: Phase 1: Load skill files
    Spring->>SR: SkillRegistry(config-dir)
    SR->>FS: scan *.md files
    FS-->>SR: report_analyst.md, operations_monitor.md, chart_builder.md
    SR->>SR: Parse frontmatter + markdown body
    Note over SR: name, description, triggers, mcp_server, allowed_tools, systemPrompt = markdown body

    Note over Spring,OMCP: Phase 2: Discover tools from MCP servers
    Spring->>MC: ToolDiscoveryInitializer.run()
    MC->>RMCP: tools/list (MCP protocol)
    MC->>OMCP: tools/list (MCP protocol)
    RMCP-->>MC: [generate_report {schema...}]
    OMCP-->>MC: [list_failed_jobs, list_successful_dataflows]
    MC->>MC: Build toolServerMap: generate_report->reports, list_failed_jobs->ops

    Note over Spring,OMCP: Phase 3: Validate skills against servers
    MC->>RMCP: GET /skills
    RMCP-->>MC: [report_analyst, chart_builder]
    MC->>SR: validateAgainstServer("reports", skills)
    Note over SR: Compare local vs server tools, log warnings for mismatches

    MC->>OMCP: GET /skills
    OMCP-->>MC: [operations_monitor]
    MC->>SR: validateAgainstServer("ops", skills)
```

### Skill File Parsing

The `SkillRegistry` reads each markdown file and splits it into two parts:

```
---
name: operations_monitor          ← frontmatter (YAML-like key: value)
description: Monitor system ops   ← parsed into SkillDefinition fields
mcp_server: ops
triggers: [job, failed, dataflow]
allowed_tools: [list_failed_jobs, list_successful_dataflows]
---
# Operations Monitor               ← markdown body
You are an operations...          ← becomes the systemPrompt
```

The body is the full agent instruction — rich, multi-paragraph, with bullet points and examples. This is the same pattern used by Claude Code skills, GitHub Copilot Skills, and Cursor Rules.

---

## MCP Connection Lifecycle

### Initialize and Tool Discovery

```mermaid
sequenceDiagram
    autonumber
    participant Client as Gateway MCP Client
    participant Transport as POST /mcp
    participant Server as MCP Server
    participant Tools as Registered Tools

    Note over Client,Tools: Phase 1: Connection Setup
    Client->>Transport: POST /mcp {method: "initialize", protocolVersion: "2025-03-26"}
    Transport->>Server: Forward JSON-RPC
    Server-->>Transport: 200 OK + Mcp-Session-Id header
    Transport-->>Client: ServerCapabilities + Session ID

    Note over Client,Tools: Phase 2: Tool Discovery
    Client->>Transport: POST /mcp {method: "tools/list"}
    Transport->>Server: Forward with session header
    Server->>Tools: Scan @McpTool methods
    Tools-->>Server: tool definitions with JSON schemas
    Server-->>Transport: tools/list result
    Transport-->>Client: [{name, description, inputSchema}]
```

### Tool Invocation

```mermaid
sequenceDiagram
    autonumber
    participant Client as Gateway MCP Client
    participant Transport as POST /mcp
    participant Server as MCP Server
    participant Tool as Tool Handler
    participant Backend as Backend Service

    Client->>Transport: POST /mcp {method: "tools/call", name: "generate_report"}
    Transport->>Server: Forward with session header
    Server->>Tool: invoke generate_report()
    Tool->>Backend: call backend API
    Backend-->>Tool: response data
    Tool-->>Server: List of String results
    Server-->>Transport: JSON-RPC result (Streamable HTTP)
    Transport-->>Client: data:{jsonrpc: "2.0", id: 2, result: {...}}
```

---

## MCP Protocol Details

### Streamable HTTP

| Aspect | Value |
|--------|-------|
| **MCP Spec** | 2025-03-26 |
| **Transport** | Single `POST /mcp` endpoint |
| **Protocol** | JSON-RPC 2.0 over HTTP POST (single endpoint, Streamable HTTP) |
| **Session** | `Mcp-Session-Id` header |
| **Spring AI** | `protocol: STREAMABLE` |

### Initialize Request

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-03-26",
    "clientInfo": {"name": "report-gateway-client", "version": "1.0.0"}
  }
}
```

### Tool Call Request

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "list_failed_jobs",
    "arguments": {"hours": 24}
  }
}
```

### Tool Result

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [{"type": "text", "text": "[{\"job_name\": \"etl\", ...}]"}],
    "isError": false
  }
}
```

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

### Prompt Injection Detection

Blocked patterns:

| Category | Examples |
|----------|----------|
| Instruction overrides | `"ignore previous"`, `"disregard"`, `"forget all"` |
| System impersonation | `"system prompt"`, `"system instruction"`, `"you are now"` |
| Role changes | `"pretend you are"`, `"role: system"`, `"role: developer"` |
| Output manipulation | `"output only"`, `"don't follow"`, `"bypass"`, `"override"` |
| Tool injection | `"call tool"`, `"invoke tool"`, `"execute tool"` |
| Suspicious chars | backtick code blocks, `<<`, `>>`, `{%`, `%}` |
| Length limit | Max 4000 characters |

Response on match: `400 PROMPT_INJECTION`

### Skill Scoping (Layer 3)

Before the LLM processes the prompt, the `SkillRegistry` matches triggers from markdown frontmatter:

- **Keyword matching**: Scans for trigger words from skill files
- **Tool filtering**: Only `allowed_tools` are sent to the LLM
- **System prompt**: The markdown body becomes the system prompt

This provides **security isolation** between skill domains:
- `report_analyst` can only see `generate_report` — cannot access ops tools
- `operations_monitor` can only see `list_failed_jobs` and `list_successful_dataflows`
- Unmatched prompts get all tools but still pass through Layer 4 validation

### Tool Call Validation (Layer 4)

```
ALLOWED_TOOLS = {
  generate_report,
  list_failed_jobs,
  list_successful_dataflows
}

PARAM_PATTERNS = {
  "reportType": ^[a-zA-Z_]+$
  "startDate":    ^\d{4}-\d{2}-\d{2}$
  "endDate":      ^\d{4}-\d{2}-\d{2}$
  "region":       ^[a-z]+-[a-z]+$
  "hours":        integer 1-168
  "days":         integer 1-30
}
```

### Audit Logging (Layer 6)

Every request logged with correlation ID, event type, and skill name. No sensitive data (prompts, tokens, or parameters) included.

---

## Authentication Flow

### Two Modes

| Mode | When Used | Token Source |
|------|-----------|--------------|
| **User OAuth passthrough** | `X-User-Token` header present | User identity |
| **Client credentials** | No user token | Service account fallback |

### Token Flow

```
Browser → Gateway (X-User-Token header)
        ↓ extracts token
        ↓ passes as _userToken in MCP tool args
Gateway → MCP Server (tools/call with _userToken)
          ↓ extracts _userToken
          ↓ sets Authorization: Bearer
MCP Server → Domain API (Bearer token)
```

---

## Technology Stack

| Layer | Technology | Purpose |
|-------|------------|---------|
| Gateway | Spring Cloud Gateway 2025.1.1 | WebFlux-based API Gateway |
| Gateway | Spring Boot 4.0.0 | WebFlux reactive framework |
| Gateway | io.modelcontextprotocol.sdk:mcp 0.17.0 | Raw MCP client (Streamable HTTP) |
| Gateway | Spring Core ResourceResolver | Markdown skill file scanning |
| Gateway | LlmProvider (pluggable) | NL → ToolCall via mock or OpenAI-compatible endpoint |
| Gateway | ChartGenerationService | Two-phase chart planning + Vega-Lite spec generation |
| Gateway | Vega-Lite (client-side) | Browser chart rendering via vega-embed |
| Reports MCP | Spring AI MCP Server 1.1.5 | MCP server with @McpTool |
| Ops MCP | Spring AI MCP Server 1.1.5 | MCP server with @McpTool |
| Domain API | Spring Boot 4.0.0 | NDJSON streaming |
| All | Java 21 | LTS runtime |

---

## Key Files Reference

| Component | File | Purpose |
|-----------|------|---------|
| Gateway Controller | `report-gateway/.../controller/AiController.java` | WebFlux SSE (`/ai/request`), chart JSON (`/ai/chart`), skill matching, prompt injection check |
| MCP Client Config | `report-gateway/.../config/McpClientConfig.java` | Multi-server MCP client beans (reports + ops) |
| Tool Discovery | `report-gateway/.../config/ToolDiscoveryInitializer.java` | Discovers tools + validates skills against servers via GET /skills |
| MCP Client Service | `report-gateway/.../service/McpClientService.java` | Multi-server tool execution, auto-routing via toolServerMap |
| Skill Registry | `report-gateway/.../service/SkillRegistry.java` | Markdown skills + server capability validation + merge |
| Report Analyst Skill | `report-gateway/.../resources/skills/report_analyst.md` | Business report agent definition + system prompt |
| Ops Monitor Skill | `report-gateway/.../resources/skills/operations_monitor.md` | Operations monitoring agent definition + system prompt |
| Chart Builder Skill | `report-gateway/.../resources/skills/chart_builder.md` | Chart visualization agent with two-phase flow |
| Chart Generation | `report-gateway/.../service/ChartGenerationService.java` | Two-phase: plan data query → generate Vega-Lite spec |
| Chart Model | `report-gateway/.../model/ChartResponse.java` | Record: chartType, title, vegaLiteSpec, dataSummary |
| LLM Interface | `report-gateway/.../service/LlmProvider.java` | Pluggable interface for NL → ToolCall conversion |
| LLM Provider (mock) | `report-gateway/.../service/MockLlmProvider.java` | Keyword-based mock for testing |
| LLM Provider (real) | `report-gateway/.../service/GenericOpenAiProvider.java` | OpenAI-compatible REST API (MiniMax, Ollama, Groq, etc.) |
| Prompt Injection | `report-gateway/.../service/PromptInjectionDetector.java` | Detects injection patterns |
| Tool Validator | `report-gateway/.../service/ToolCallValidator.java` | Tool allowlist + parameter regex |
| Rate Limiter | `report-gateway/.../filter/RequestLoggingWebFilter.java` | Rate limiting + correlation IDs |
| Audit Logger | `report-gateway/.../service/AuditLogger.java` | Audit trail with correlation IDs |
| Reports MCP Tool | `mcp-server/.../tool/ReportTools.java` | @McpTool generate_report |
| Reports MCP Svc | `mcp-server/.../service/ReportStreamService.java` | WebClient to Domain API with Bearer auth |
| Reports Skills | `mcp-server/.../controller/SkillsController.java` | GET /skills — exposes report_analyst + chart_builder |
| Ops MCP Tools | `ops-mcp-server/.../tool/OpsTools.java` | @McpTool list_failed_jobs, list_successful_dataflows |
| Ops Skills | `ops-mcp-server/.../controller/SkillsController.java` | GET /skills — exposes operations_monitor |
| Ops Data Service | `ops-mcp-server/.../service/OpsDataService.java` | Simulated failed job and dataflow data |
| Domain Controller | `domain-api/.../controller/ReportStreamController.java` | NDJSON streaming |
| Domain Controller | `domain-api/.../controller/ReportMetadataController.java` | GET /api/v1/reports/metadata — report schemas |
| Frontend | `report-gateway/.../static/index.html` | Chat UI with SSE parsing |
| RAG Models | `report-gateway/.../model/KnowledgeDocument.java` | Document record with auto-chunking |
| RAG Models | `report-gateway/.../model/DocumentChunk.java` | Chunked text with keyword extraction |
| RAG Models | `report-gateway/.../model/ReportSchema.java` | Mirror model for Domain API report metadata |
| RAG Service | `report-gateway/.../service/RagService.java` | Interface for document management |
| RAG Service | `report-gateway/.../service/InMemoryRagStore.java` | ConcurrentHashMap-backed store with keyword retrieval |
| RAG Service | `report-gateway/.../service/RagAutoIngestionService.java` | Auto-ingests tool/report schemas with deterministic IDs |
| RAG Controller | `report-gateway/.../controller/DocumentIngestionController.java` | CRUD endpoints for manual documents |
| Sync Service | `report-gateway/.../service/SyncService.java` | Orchestrates tool + RAG sync across servers |
| Sync Controller | `report-gateway/.../controller/SyncController.java` | Admin endpoints: POST /admin/sync/* |
| Sync Scheduler | `report-gateway/.../config/SyncScheduler.java` | @Scheduled periodic sync (every 5 min) |
| Tool Discovery | `report-gateway/.../config/ToolDiscoveryInitializer.java` | Discovers tools + triggers RAG auto-ingestion at startup |

---

## Running the System

```bash
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

Or with Docker Compose (all 4 services):

```bash
docker compose up --build
```

---

## Configuration

### Gateway

```yaml
# application.yml (report-gateway)
llm:
  provider: ${LLM_PROVIDER:mock}          # mock | openai-compatible
  openai:
    endpoint: ${LLM_ENDPOINT:https://api.openai.com/v1}
    api-key: ${LLM_API_KEY:}
    model: ${LLM_MODEL:gpt-4o}

# Multi-server MCP configuration
mcp:
  client:
    servers:
      reports:
        url: ${MCP_SERVER_URL:http://localhost:8081}
        name: "Reports MCP Server"
      ops:
        url: ${OPS_MCP_SERVER_URL:http://localhost:8083}
        name: "Operations MCP Server"

# Skills configuration — directory of markdown files
skills:
  config-dir: classpath:skills/*.md
```

### Reports MCP Server

```yaml
# application.yml (mcp-server)
spring:
  ai:
    mcp:
      server:
        protocol: STREAMABLE
        name: "report-generator-mcp-server"
        version: "1.0.0"
        streamable-http:
          mcp-endpoint: /mcp

domain-api:
  url: ${DOMAIN_API_URL:http://localhost:8082}
  auth-token: ${DOMAIN_API_TOKEN:dummy-oauth-token-for-poc}
```

### Ops MCP Server

```yaml
# application.yml (ops-mcp-server)
server:
  port: 8083

spring:
  ai:
    mcp:
      server:
        protocol: STREAMABLE
        name: "operations-monitoring-mcp-server"
        version: "1.0.0"
        streamable-http:
          mcp-endpoint: /mcp
```

---

## Conversational Memory System

The system maintains conversation context across multiple turns using an externalized session store, enabling follow-up requests that reference previous context.

### Architecture Overview

```mermaid
flowchart TD
    subgraph Browser["Browser Layer"]
        UI["Chat UI\nindex.html"]
        Cookie["session-id cookie"]
    end

    subgraph Gateway["Gateway Layer :8080"]
        AiCtrl["AiController\nPOST /ai/chart"]
        ConvCtrl["ConversationController\nGET /session/history"]
        CtxInj["ContextInjector\nbuildContextPrompt()"]
        ConvSvc["ConversationService\nloadOrCreateSession()"]
        SessionStore["SessionStore Interface"]
    end

    subgraph Storage["Session Storage"]
        InMem[(InMemorySessionStore\nConcurrentHashMap)]
        Redis[(RedisSessionStore\nJSON serialization)]
    end

    subgraph Models["Domain Models"]
        Session["ConversationSession\nsessionId, userId, turns[]"]
        Turn["ConversationTurn\nturnNumber, filters, response"]
        Filters["ExtractedFilters\nreportType, region, dates"]
    end

    UI -->|"X-Session-ID header\nor cookie"| AiCtrl
    UI -->|View History / Clear| ConvCtrl
    AiCtrl -->|Extract/Inject| CtxInj
    AiCtrl -->|Load/Save| ConvSvc
    ConvCtrl -->|Load/Delete| ConvSvc
    ConvSvc -->|Reactive ops| SessionStore
    SessionStore -->|Conditional| InMem
    SessionStore -->|Conditional| Redis
    Turn -.->|part of| Session
    Filters -.->|extracted from| Turn
```

### Key Components

| Component | Responsibility | Implementation |
|-----------|---------------|----------------|
| **ConversationController** | REST endpoints for session management | `GET /session/history`, `POST /session/clear`, `GET /session/status` |
| **ConversationService** | Session lifecycle management | Load/create sessions, save turns, clear history |
| **ContextInjector** | Build context-aware prompts | Sliding window of recent turns + date reference |
| **SessionStore** | Interface for session persistence | `find()`, `save()`, `delete()`, `exists()` |
| **InMemorySessionStore** | Dev/test storage | `ConcurrentHashMap` with TTL cleanup |
| **RedisSessionStore** | Production storage | ReactiveRedisTemplate with JSON serialization |
| **ConversationSession** | Immutable session record | `sessionId`, `userId`, `turns[]`, `turnCount`, `MAX_TURNS=100` |
| **ConversationTurn** | Single exchange record | `turnNumber`, `timestamp`, `userPrompt`, `extractedFilters`, `responseType`, `chartType` |
| **ExtractedFilters** | Structured parameters | `reportType`, `region`, `startDate`, `endDate`, `additionalFilters`, `mergeWithContext()` |

### Session Flow: Follow-Up Request

```mermaid
sequenceDiagram
    autonumber
    participant User as User
    participant Browser as Browser
    participant GW as Gateway :8080
    participant CS as ConversationService
    participant CI as ContextInjector
    participant SS as SessionStore
    participant LLM as LLM Provider

    Note over User,Browser: Turn 1: Initial Request
    User->>Browser: "Show me revenue for last year"
    Browser->>GW: POST /ai/chart, Cookie: session-id=abc-123
    GW->>CS: loadOrCreateSession("abc-123")
    CS->>SS: find("abc-123")
    SS-->>CS: null (new session)
    CS-->>GW: new SessionContext
    GW->>LLM: generateToolCall(prompt, tools, systemPrompt) [no context yet]
    LLM-->>GW: ToolCall(tool="generate_report", params={reportType:"revenue", dates:2025})
    GW->>CS: saveTurn(session, turn)
    CS->>SS: save(session with Turn 1)
    SS-->>CS: session persisted
    GW-->>Browser: 200 OK, X-Session-ID: abc-123, ChartResponse
    Browser-->>User: Renders bar chart

    Note over User,Browser: Turn 2: Follow-Up with Context
    User->>Browser: "Show it as a pie chart"
    Browser->>GW: POST /ai/chart, Cookie: session-id=abc-123
    GW->>CS: loadOrCreateSession("abc-123")
    CS->>SS: find("abc-123")
    SS-->>CS: Session with Turn 1 (revenue, 2025 dates)
    GW->>CI: buildContextPrompt("Show it as a pie chart", session)
    CI-->>GW: Context prompt with history and filters
    GW->>LLM: generateToolCall(contextPrompt, tools, systemPrompt)
    Note over LLM: LLM sees previous filters, inherits reportType and dates
    LLM-->>GW: ToolCall(tool="generate_report", params={reportType:"revenue", dates:2025})
    GW->>CS: saveTurn(session, turn)
    CS->>SS: save(session with Turn 2)
    GW-->>Browser: 200 OK, ChartResponse (pie chart)
    Browser-->>User: Renders pie chart
```

### Context Injection

The `ContextInjector` builds prompts that include conversation history:

```java
public String buildContextPrompt(String currentPrompt, ConversationSession session) {
    StringBuilder prompt = new StringBuilder();

    // Date reference section (today is 2026-05-07)
    prompt.append("=== Date Reference (today is 2026-05-07) ===\n");
    prompt.append("- \"this year\" → startDate: \"2026-01-01\", endDate: \"2026-12-31\"\n");
    prompt.append("- \"last year\" → startDate: \"2025-01-01\", endDate: \"2025-12-31\"\n");
    // ... more date mappings

    // Add conversation context (sliding window: last 8 turns)
    if (session != null && !session.isEmpty()) {
        List<ConversationTurn> recentTurns = session.getRecentTurns(8);
        prompt.append("\n=== Conversation History ===\n");
        for (ConversationTurn turn : recentTurns) {
            prompt.append("User: ").append(turn.userPrompt()).append("\n");
            if (turn.extractedFilters() != null) {
                prompt.append("[Filters: ").append(turn.extractedFilters()).append("]\n");
            }
            // ... response metadata
        }
    }

    // Current request
    prompt.append("=== Current Request ===\n");
    prompt.append("User: ").append(currentPrompt).append("\n");

    return prompt.toString();
}
```

### Filter Extraction and Merge

When the LLM generates a tool call, filters are extracted and can inherit from previous turns:

```java
public ExtractedFilters mergeWithContext(ExtractedFilters context) {
    if (context == null) {
        return this;
    }
    return new ExtractedFilters(
        this.reportType != null ? this.reportType : context.reportType,
        this.region != null ? this.region : context.region,
        this.startDate != null ? this.startDate : context.startDate,
        this.endDate != null ? this.endDate : context.endDate,
        mergeAdditionalFilters(this.additionalFilters, context.additionalFilters)
    );
}
```

This enables pronoun resolution:
- User: "Show me **revenue** for **last year**"
- User: "Show **it** as a **pie chart**" → LLM sees context, inherits revenue + 2025 dates

### Session Configuration

```yaml
# application.yml (report-gateway)
session:
  storage:
    type: in-memory  # or "redis" for production
  timeout-minutes: 30  # Session TTL

spring:
  data:
    redis:
      host: localhost
      port: 6379
```

### Session Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/session/history` | GET | Retrieve full conversation history for session |
| `/session/clear` | POST | Delete session and all conversation history |
| `/session/status` | GET | Check session status (active, turn count, expiry) |

### Frontend Integration

The browser manages session via cookie:

```javascript
// On page load: restore session from cookie
var sessionId = getCookie('session-id');

// On each request: include session header
fetch('/ai/chart', {
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'X-Session-ID': sessionId  // or from cookie
    },
    body: JSON.stringify({prompt: text})
});

// On response: update session ID
var newSessionId = response.headers.get('X-Session-ID');
if (newSessionId) {
    setCookie('session-id', newSessionId, 1800);  // 30 min TTL
}

// History panel: load from endpoint
document.getElementById('btn-history').onclick = function() {
    fetch('/session/history', {
        headers: {'X-Session-ID': sessionId}
    }).then(r => r.json()).then(data => {
        // Render turns in slide-out panel
    });
};
```

### Statelessness Guarantee

The gateway remains stateless — all session data is externalized:

| Aspect | Implementation |
|--------|---------------|
| **Session Storage** | External (Redis or in-memory, not gateway heap) |
| **Session ID** | Client-provided (cookie or header) |
| **Horizontal Scaling** | Multiple gateway instances share Redis session store |
| **Restart Safety** | Sessions survive gateway restart (with Redis) |
| **TTL Management** | Automatic expiry handled by storage layer |

---

## RAG Layer (Retrieval-Augmented Generation)

The RAG layer enriches LLM prompts with domain-specific knowledge from ingested documents, improving tool selection accuracy and response quality for domain-specific queries.

### Architecture Overview

```mermaid
flowchart TD
    subgraph Ingestion["Document Ingestion"]
        API["DocumentIngestionController\nPOST /rag/documents"]
        RagSvc["RagService Interface"]
        Store[(InMemoryRagStore\nConcurrentHashMap)]
    end

    subgraph Retrieval["Context Retrieval"]
        CtxInj2["ContextInjectorImpl\nretrieveRagContext()\nbuildContextPrompt()"]
        Chunk["DocumentChunk\nchunkContent()\nextractKeywords()"]
        Score["ScoredChunk\nkeyword overlap scoring"]
    end

    subgraph Prompt["LLM Prompt Assembly"]
        RAGCtx["=== Relevant Knowledge Base Context ===\n[chunk 1, score=0.75] ...\n[chunk 2, score=0.50] ..."]
        DateCtx["=== Date Reference ==="]
        ConvCtx["=== Conversation History ==="]
        CurrReq["=== Current Request ==="]
    end

    API --> RagSvc
    RagSvc --> Store
    RagSvc --> Chunk
    CtxInj2 --> RagSvc
    RagSvc --> Score
    CtxInj2 --> RAGCtx
    RAGCtx --> DateCtx
    DateCtx --> ConvCtx
    ConvCtx --> CurrReq
```

### Key Components

| Component | Responsibility | Implementation |
|-----------|---------------|----------------|
| **RagService** | Interface for document management | `ingest()`, `ingestWithId()`, `retrieve()`, `getById()`, `listAll()`, `delete()`, `clear()` |
| **InMemoryRagStore** | POC storage implementation | `ConcurrentHashMap` with keyword overlap scoring |
| **KnowledgeDocument** | Immutable document record with auto-chunking | `id`, `title`, `content`, `tags`, `createdAt`, `chunks[]` |
| **DocumentChunk** | Chunked text with keywords | `documentId`, `chunkIndex`, `text`, `keywords[]` |
| **DocumentIngestionController** | REST API for manual document CRUD | `POST/GET/DELETE /rag/documents` |
| **RagAutoIngestionService** | Auto-ingests tool schemas + report metadata | Deterministic IDs (`auto-tool:`, `auto-report:`) for idempotent re-ingestion |
| **ContextInjectorImpl** | RAG + conversation context injection | `retrieveRagContext()` + `buildContextPrompt()` with sliding window |

### Prompt Assembly Order

The `ContextInjectorImpl.buildContextPrompt()` assembles the final prompt in this order:

1. **RAG context** — Top 3 relevant document snippets (if any match)
2. **Date reference** — Dynamic date mappings ("last year", "this quarter")
3. **Conversation summary** — Condensed summary if session exceeded max turns
4. **Conversation history** — Sliding window of recent turns (last 8)
5. **Current request** — User's latest message

### Retrieval Algorithm (POC)

For the POC, retrieval uses keyword overlap scoring — no embeddings required:

1. **Chunking**: Document content split by sentence boundaries into ~200 char chunks
2. **Keyword extraction**: All words >2 chars extracted per chunk (lowercased)
3. **Query tokenization**: User query split into lowercase tokens
4. **Scoring**: For each chunk, compute `intersection(queryTokens, chunkKeywords) / (|queryTokens| + |keywords| - intersection)`
5. **Ranking**: Chunks sorted by score descending, top K returned

This approach is sufficient for POC scale (up to ~100 documents) and requires no external embedding service.

### Storage Strategy

| Environment | Store | Notes |
|-------------|-------|-------|
| **POC / Dev** | `InMemoryRagStore` (ConcurrentHashMap) | Stateless, no persistence |
| **Production** | Future: Redis or vector DB (e.g., pgvector, Pinecone) | Implement `RagService` interface |

### RAG + Conversational Memory Integration

RAG context is injected **before** conversation history in the prompt, so the LLM sees:
- Relevant domain knowledge first (terminology, schemas)
- Then conversation context (previous requests, extracted filters)
- Then the current request

This ordering ensures domain knowledge is available as reference when interpreting the conversation.

---

## Tool & Schema Sync System

The gateway keeps tools, report schemas, and RAG data in sync with MCP servers and the Domain API without requiring a restart.

### Problem Solved

Without sync, if an MCP server adds a new `@McpTool` or the Domain API adds a new report type, the gateway would remain unaware until it's restarted. The sync system solves this through three triggers:

| Trigger | When | Component |
|---------|------|-----------|
| **Startup** | Gateway launches | `ToolDiscoveryInitializer` + `RagAutoIngestionService` |
| **Scheduled** | Every 5 minutes (configurable) | `SyncScheduler` (`@Scheduled`) |
| **Admin** | On-demand via HTTP POST | `SyncController` → `SyncService` |

### Architecture

```mermaid
flowchart TD
    subgraph Triggers["Sync Triggers"]
        Start["Startup\nToolDiscoveryInitializer"]
        Sched["Scheduled\nSyncScheduler (5 min)"]
        Admin["Admin Endpoint\nPOST /admin/sync/*"]
    end

    subgraph Sync["SyncService Orchestrator"]
        SyncAll["syncAll()"]
        Refresh["refreshServer(serverId)"]
        RefreshRpt["refreshReportSchemas()"]
    end

    subgraph Discovery["Tool Discovery"]
        McpSvc["McpClientService\nrefreshServerTools()\ndiscoverAndCacheTools()"]
        Servers["MCP Servers\nreports (8081)\nops (8083)"]
    end

    subgraph RAG["RAG Re-ingestion"]
        RagAuto["RagAutoIngestionService\ningestWithId()\nrefreshToolSchemas()"]
        Domain["Domain API\nGET /api/v1/reports/metadata"]
    end

    subgraph Validator["Allowlist Update"]
        Val["ToolCallValidator\nupdateAllowedTools()"]
    end

    Start --> McpSvc
    Start --> RagAuto
    Sched --> SyncAll
    Admin --> SyncAll
    Admin --> Refresh
    Admin --> RefreshRpt
    SyncAll --> McpSvc
    Refresh --> McpSvc
    RefreshRpt --> RagAuto
    McpSvc -->|"tools/list"| Servers
    McpSvc --> Val
    SyncAll --> RagAuto
    RagAuto -->|"fetch schemas"| Domain
```

### Idempotent RAG Ingestion

Auto-ingested documents use deterministic IDs to prevent duplication on re-ingestion:

| Document Type | ID Prefix | Example |
|---------------|-----------|---------|
| Tool schema | `auto-tool:` | `auto-tool:generate_report` |
| Report schema | `auto-report:` | `auto-report:revenue` |
| Manual (user) | UUID | `a1b2c3d4-...` |

On re-ingestion, the service deletes all docs matching the `auto-tool:` or `auto-report:` prefix before inserting fresh ones. Manually ingested documents are never touched by the sync process.

### Tool Cache Invalidation

When refreshing a specific server, `McpClientService`:
1. Removes all tools previously discovered from that server from `toolServerMap` and `allTools`
2. Calls `tools/list` to get fresh data
3. Adds the new tools back — ensuring no duplicates

### Error Handling

All sync operations are non-blocking and tolerate individual server failures:

- If one MCP server is down during scheduled sync, the error is logged but the other server still syncs
- If the Domain API is unreachable, report schema refresh is skipped with a warning
- Admin endpoints always return a `SyncResult` with `success: false` and error details

---

## Summary

| Question | Answer |
|----------|--------|
| **What is MCP Client?** | Component in Gateway that connects to multiple MCP Servers and invokes tools |
| **What does MCP Client do?** | Handles JSON-RPC over Streamable HTTP, manages sessions, auto-routes tool calls to correct server |
| **What integrates with LLM?** | LlmProvider interface converts natural language to ToolCall |
| **What does MCP Server do?** | Exposes @McpTool annotated methods that can be called by clients |
| **What are Skills?** | Markdown files with YAML frontmatter that define agent profiles: triggers, allowed tools, and system prompts |
| **How are Skills loaded?** | Scanned from `classpath:skills/*.md` at startup — one file per agent |
| **Why markdown skills?** | Industry standard (Claude Code, Copilot, Cursor). Rich instructions in body, metadata in frontmatter. |
| **Why multiple MCP servers?** | Separation of concerns: reports handle business data, ops handles monitoring. Skills route automatically. |
| **How does data flow?** | Browser to Gateway to Skill Match to LLM to ToolCall to MCP Client to Auto-route to MCP Server to Domain API and back |
| **Why raw MCP SDK?** | Avoids Jackson 3.x vs 2.x conflict with Spring Cloud Gateway |
| **Why Streamable HTTP?** | SSE is deprecated in MCP spec 2025-03-26. Streamable HTTP uses a single POST endpoint |
| **What is Conversational Memory?** | Session storage that maintains conversation history across multiple turns |
| **How does context injection work?** | Previous turns prepended to LLM prompts with sliding window (last 8 turns) |
| **Where are sessions stored?** | Redis for production, ConcurrentHashMap for local dev — both implement SessionStore interface |
| **How do follow-up requests work?** | User references like "it" resolve via context — filters inherit from previous turns |
| **What is the RAG layer?** | Keyword-based document retrieval that injects domain knowledge into LLM prompts |
| **How are RAG docs populated?** | Auto-ingested at startup (tool schemas + report metadata) + manual upload via API |
| **How does sync prevent staleness?** | Three triggers: startup, scheduled (5 min), admin endpoint — re-discovers tools and re-ingests RAG docs |
| **How is idempotent ingestion achieved?** | Deterministic IDs (`auto-tool:`, `auto-report:`) — re-ingestion deletes old prefix-matching docs first |
