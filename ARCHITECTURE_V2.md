# MCP Architecture V2 - Report Generator System

## Overview

This document explains the Model Context Protocol (MCP) architecture implemented in the report generator system, including the flow of data, component responsibilities, and how each layer integrates.

---

## System Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                                 USER (Browser)                               │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  Chat UI: http://localhost:8080                                      │   │
│  │  • Sends natural language prompts ("Show me revenue")              │   │
│  │  • Displays streaming data rows in real-time                         │   │
│  │  • Triggers download for CSV export                                  │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              REPORT-GATEWAY (8080)                           │
│                        [Spring Cloud Gateway / WebFlux]                      │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  AiController                                                        │   │
│  │  ├─ POST /ai/request  → Orchestrates LLM → MCP → Stream response   │   │
│  │  ├─ GET  /ai/status   → Health check (returns {"status":"ok"})       │   │
│  │  └─ static/index.html → React-like chat interface                   │   │
│  │                                                                      │   │
│  │  Flow:                                                               │   │
│  │  1. LLMProvider.generateToolCall() → Converts "Show me revenue"      │   │
│  │     into structured ToolCall: {tool:"generate_report", params:{...}}  │   │
│  │                                                                      │   │
│  │  2. ToolCallValidator.validate() → Validates tool name and params  │   │
│  │     against JSON schema (regex patterns for dates, regions, etc.)    │   │
│  │                                                                      │   │
│  │  3. McpClientService.executeToolCall() → Calls MCP Server via SSE   │   │
│  │     and streams results back as Flux<String>                        │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  McpClientService (raw MCP SDK)                                    │   │
│  │                                                                      │   │
│  │  Uses: io.modelcontextprotocol.sdk:mcp:0.17.0                       │   │
│  │  NOT Spring AI MCP Client starter (conflicts with SCG)                │   │
│  │                                                                      │   │
│  │  Components:                                                         │   │
│  │  • HttpClientSseClientTransport → Connects to MCP Server SSE        │   │
│  │  • McpAsyncClient → Handles JSON-RPC over SSE                     │   │
│  │  • callTool() → Sends tool/call request, receives content blocks    │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      │  MCP Protocol (SSE over HTTP)
                                      │  • GET /sse      → SSE connection (Spring AI default)
                                      │  • POST /mcp/message → JSON-RPC messages
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                             MCP-SERVER (8081)                                │
│                          [Spring AI MCP Server]                              │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  ReportTools (@Component with @McpTool annotation)                   │   │
│  │                                                                      │   │
│  │  @McpTool(description="Generate structured report...")            │   │
│  │  public List<String> generate_report(                               │   │
│  │      @McpToolParam String reportType,   // e.g., "revenue"        │   │
│  │      @McpToolParam String startDate,      // "2026-01-01"         │   │
│  │      @McpToolParam String endDate,        // "2026-03-31"         │   │
│  │      @McpToolParam String region          // "us-east"            │   │
│  │  ) {                                                                 │   │
│  │      // Calls Domain API via WebClient                              │   │
│  │      return reportStreamService.streamReport(...).collectList()     │   │
│  │  }                                                                   │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  Auto-registered by: org.springaicommunity.mcp.annotation scanner         │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  ReportStreamService                                                │   │
│  │                                                                      │   │
│  │  WebClient → Domain API (8082)                                     │   │
│  │  POST /api/v1/reports/stream                                        │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      │  HTTP POST (NDJSON streaming)
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                             DOMAIN-API (8082)                                │
│                          [Spring Boot WebMVC]                                │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  ReportStreamController                                             │   │
│  │                                                                      │   │
│  │  @PostMapping("/api/v1/reports/stream")                             │   │
│  │  StreamingResponseBody → Generates NDJSON rows                    │   │
│  │                                                                      │   │
│  │  Output:                                                             │   │
│  │  {"type":"header","data":{"reportType":"revenue"}}\n               │   │
│  │  {"type":"data","data":{"row":1,"product":"A",...}}\n             │   │
│  │  {"type":"data","data":{"row":2,"product":"B",...}}\n             │   │
│  │  ...                                                                 │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## What is MCP (Model Context Protocol)?

### Definition
MCP is a protocol standardized by Anthropic that defines how **AI clients** (like Claude, LLM gateways) communicate with **tool servers** that expose capabilities to the AI.

### Key Concepts

#### MCP Server
- **Purpose**: Exposes tools (functions) that an LLM can call
- **Transport**: HTTP with Server-Sent Events (SSE) for streaming
- **Protocol**: JSON-RPC 2.0 over SSE
- **Registration**: Tools are registered with names, descriptions, and JSON schemas for parameters

#### MCP Client
- **Purpose**: Connects to an MCP Server and invokes tools
- **Lifecycle**:
  1. **Initialize**: Client sends `initialize` request, server responds with capabilities
  2. **tools/list**: Client discovers available tools
  3. **tools/call**: Client invokes a tool with parameters
- **Response**: Tool returns content blocks (text, images, etc.)

---

## Data Flow Walkthrough

### Step 1: User Sends Prompt
```
User: "Show me revenue for us-east"
```

### Step 2: Gateway Processes Request (AiController)
```java
// AiController.handleAiRequest()
1. LLMProvider.generateToolCall("Show me revenue for us-east", tools)
   → Returns: ToolCall(tool="generate_report", params={reportType="revenue", region="us-east"})

2. ToolCallValidator.validate(toolCall)
   → Validates: tool name is in allowlist, params match regex patterns

3. McpClientService.executeToolCall(toolCall, correlationId)
   → Calls MCP Server via SSE
```

### Step 3: MCP Protocol Handshake (McpClientService)
```
┌─────────────────────────────────────────────────────────────┐
│  MCP Client (Gateway)          MCP Server (8081)           │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  1. GET /sse ────────────────────────────────>             │
│     (SSE connection opens, returns endpoint URL)             │
│     Response: event:endpoint                                   │
│               data:/mcp/message?sessionId=xxx                │
│                                                               │
│  2. POST /mcp/message                                        │
│     {                                                        │
│       "jsonrpc": "2.0",                                      │
│       "method": "initialize",                                │
│       "params": {                                            │
│         "protocolVersion": "2024-11-05",                      │
│         "capabilities": {},                                  │
│         "clientInfo": {                                      │
│           "name": "report-gateway-client",                   │
│           "version": "1.0.0"                                │
│         }                                                    │
│       }                                                      │
│     } ───────────────────────────────────────>             │
│                                                               │
│  3. <────────────────────────── SSE: initialize response     │
│     Server responds with capabilities and tool list         │
│                                                               │
│  4. POST /mcp/message                                        │
│     {                                                        │
│       "jsonrpc": "2.0",                                      │
│       "method": "tools/call",                                │
│       "params": {                                            │
│         "name": "generate_report",                           │
│         "arguments": {                                       │
│           "reportType": "revenue",                           │
│           "region": "us-east"                                │
│         }                                                    │
│       }                                                      │
│     } ───────────────────────────────────────>             │
│                                                               │
│  5. <────────────────────────── SSE: tool result              │
│     Content blocks with report data                         │
│                                                               │
└─────────────────────────────────────────────────────────────┘
```

### Step 4: MCP Server Executes Tool (ReportTools)
```java
@McpTool(description = "Generate a structured report...")
public List<String> generate_report(
    @McpToolParam String reportType,
    @McpToolParam String startDate,
    @McpToolParam String endDate,
    @McpToolParam String region
) {
    // 1. Build request for Domain API
    Map<String, Object> request = Map.of(
        "reportType", reportType,
        "filters", Map.of("region", region)
    );

    // 2. Call Domain API via WebClient (reactive)
    return reportStreamService.streamReport(request, correlationId)
        .collectList()
        .block(); // Convert Flux to List for MCP sync provider
}
```

### Step 5: Domain API Streams Data
```
Domain API receives POST /api/v1/reports/stream
→ Generates rows as NDJSON (Newline-Delimited JSON)
→ Returns Flux<String> with one row per emission

Example output:
{"type":"header","data":{"reportType":"revenue","generatedAt":"2026-01-15"}}
{"type":"data","data":{"row":1,"product":"Widget A","revenue":15000}}
{"type":"data","data":{"row":2,"product":"Widget B","revenue":23000}}
...
{"type":"footer","data":{"totalRows":50,"totalRevenue":1250000}}
```

### Step 6: Response Flows Back
```
Domain API → MCP Server → MCP Client (Gateway) → Browser

Gateway transforms:
1. Receives JSON array from MCP: ["row1,data...", "row2,data..."]
2. Parses with Jackson: mapper.readValue(jsonArray, List.class)
3. Emits each row as Flux<String> → SSE events → Browser

Browser receives:
data:row1,data-1,1778036884627
data:

data:row2,data-2,1778036884627
data:
...
```

---

## Component Deep Dive

### MCP Client (report-gateway)

**File**: `report-gateway/src/main/java/com/example/gateway/service/McpClientService.java`

```java
@Service
public class McpClientService {
    private final McpAsyncClient mcpClient;  // From raw MCP SDK

    public Flux<String> executeToolCall(ToolCall toolCall, String correlationId) {
        // Convert ToolCall to MCP CallToolRequest
        Map<String, Object> args = mapper.convertValue(toolCall.parameters(), Map.class);

        return mcpClient.callTool(new McpSchema.CallToolRequest(toolCall.tool(), args))
            .flatMapMany(result -> {
                // Extract text content from MCP result
                return Flux.fromIterable(result.content())
                    .filter(content -> content instanceof McpSchema.TextContent)
                    .map(content -> ((McpSchema.TextContent) content).text());
            });
    }
}
```

**Configuration**: `McpClientConfig.java`
```java
@Bean
public McpAsyncClient mcpAsyncClient() {
    // Build SSE transport pointing to MCP Server
    // Note: Spring AI MCP Server exposes SSE at /sse (not /mcp/sse)
    var transport = HttpClientSseClientTransport.builder("http://localhost:8081/sse").build();

    // Build async client with implementation info
    return McpClient.async(transport)
        .clientInfo(new McpSchema.Implementation("report-gateway-client", "1.0.0"))
        .requestTimeout(Duration.ofSeconds(60))
        .build();
}
```

---

### MCP Server (mcp-server)

**File**: `mcp-server/src/main/java/com/example/mcp/tool/ReportTools.java`

```java
@Component
public class ReportTools {
    private final ReportStreamService reportStreamService;

    @McpTool(description = "Generate a structured report from domain data")
    public List<String> generate_report(
            @McpToolParam(description = "Type of report", required = true) String reportType,
            @McpToolParam(description = "Start date YYYY-MM-DD") String startDate,
            @McpToolParam(description = "End date YYYY-MM-DD") String endDate,
            @McpToolParam(description = "Region filter") String region
    ) {
        // Build request and call Domain API
        Map<String, Object> request = buildRequest(reportType, startDate, endDate, region);
        return reportStreamService.streamReport(request, UUID.randomUUID().toString())
            .collectList()
            .block();
    }
}
```

**Auto-Configuration**: Spring AI MCP Server starter scans for `@McpTool` methods and automatically registers them.

---

### LLM Provider Integration

**File**: `report-gateway/src/main/java/com/example/gateway/service/MockLlmProvider.java`

The LLM Provider is the bridge between natural language and structured tool calls:

```java
public interface LlmProvider {
    // Converts: "Show me revenue for us-east"
    // Into: ToolCall(tool="generate_report", params={reportType="revenue", region="us-east"})
    ToolCall generateToolCall(String userMessage, List<ToolDefinition> tools);
}
```

**Current Implementation**: Mock provider with simple pattern matching.

**Future**: Replace with real LLM (OpenAI, Claude, etc.) that uses the tool definitions to generate proper JSON tool calls.

---

## Technology Stack

| Layer | Technology | Purpose |
|-------|------------|---------|
| Gateway | Spring Cloud Gateway 2025.1.1 | WebFlux-based API Gateway |
| Gateway | Spring Boot 4.0.0 | WebFlux reactive framework |
| Gateway | MCP SDK 0.17.0 | Raw MCP client (no Spring AI) |
| MCP Server | Spring AI MCP Server 1.1.5 | MCP server with @McpTool |
| MCP Server | Spring Boot 4.0.0 | WebMVC for SSE transport |
| Domain API | Spring Boot 4.0.0 | Report generation service |
| All | Java 21 | LTS runtime |

---

## Why Raw MCP SDK in Gateway?

Spring AI MCP Client starter (`spring-ai-starter-mcp-client-webflux`) was attempted but caused conflicts:

```
Error: NoClassDefFoundError: tools/jackson/core/JacksonException
```

**Root Cause**: Spring AI MCP annotations (v1.1.5) use Jackson 3.x classes, while Spring Cloud Gateway uses Jackson 2.x. The annotation scanner triggers during bean post-processing and fails.

**Solution**: Use raw `io.modelcontextprotocol.sdk:mcp:0.17.0` which:
- Has no Spring dependencies
- Works with standard Jackson 2.x
- Manual configuration in `McpClientConfig.java`

---

## Why Spring AI MCP Server in mcp-server?

Spring AI MCP Server starter works perfectly because:
- It runs in its own JVM (port 8081)
- No conflict with Spring Cloud Gateway
- `@McpTool` annotation scanning works correctly
- Auto-configures SSE transport

---

## Key Files Reference

| Component | File | Purpose |
|-----------|------|---------|
| Gateway Controller | `report-gateway/src/main/java/com/example/gateway/controller/AiController.java` | REST API + SSE streaming |
| MCP Client Config | `report-gateway/src/main/java/com/example/gateway/config/McpClientConfig.java` | Raw MCP client setup |
| MCP Client Service | `report-gateway/src/main/java/com/example/gateway/service/McpClientService.java` | Tool execution |
| MCP Tool | `mcp-server/src/main/java/com/example/mcp/tool/ReportTools.java` | @McpTool annotated methods |
| Domain Controller | `domain-api/src/main/java/com/example/domain/controller/ReportStreamController.java` | NDJSON streaming |
| Frontend | `report-gateway/src/main/resources/static/index.html` | Chat UI with SSE parsing |

---

## Running the System

```bash
# Terminal 1: Domain API
java -jar domain-api/target/domain-api-0.0.1-SNAPSHOT.jar --server.port=8082

# Terminal 2: MCP Server
java -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar --server.port=8081

# Terminal 3: Gateway
java -jar report-gateway/target/report-gateway-0.0.1-SNAPSHOT.jar --server.port=8080

# Browser: http://localhost:8080
```

---

## Configuration

### MCP Server URL

The Gateway connects to the MCP Server via the `MCP_SERVER_URL` environment variable or configuration property:

```yaml
# application.yml (report-gateway)
mcp:
  client:
    server-url: ${MCP_SERVER_URL:http://localhost:8081/sse}
```

**Important**: The URL must include the `/sse` path suffix. Spring AI MCP Server exposes the SSE endpoint at `/sse` by default (not `/mcp/sse`).

```bash
# Correct
export MCP_SERVER_URL=http://localhost:8081/sse

# Incorrect - will fail to connect
export MCP_SERVER_URL=http://localhost:8081
```

---

## MCP Protocol Specification

### Initialize Request
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2024-11-05",
    "capabilities": {},
    "clientInfo": {
      "name": "report-gateway-client",
      "version": "1.0.0"
    }
  }
}
```

### Tools/Call Request
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "generate_report",
    "arguments": {
      "reportType": "revenue",
      "region": "us-east"
    }
  }
}
```

### Tool Result Response
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "[\"row1,data...\", \"row2,data...\", ...]"
      }
    ],
    "isError": false
  }
}
```

---

## Summary

| Question | Answer |
|----------|--------|
| **What is MCP Client?** | Component in Gateway that connects to MCP Server and invokes tools |
| **What does MCP Client do?** | Handles JSON-RPC over SSE, manages connection lifecycle, calls tools |
| **What integrates with LLM?** | LlmProvider interface converts natural language → ToolCall |
| **What does MCP Server do?** | Exposes @McpTool annotated methods that can be called by clients |
| **How does data flow?** | Browser → Gateway → LLM → ToolCall → MCP Client → MCP Server → Domain API → back up |
| **Why raw MCP SDK?** | Avoids Jackson 3.x vs 2.x conflict with Spring Cloud Gateway |
