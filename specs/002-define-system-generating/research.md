# Research: MCP-Based Report Generation Architecture (Provider-Agnostic)

**Date**: 2026-05-05
**Feature**: Natural Language Report Generator (v2 — Provider-Agnostic)

## Decisions

### Decision 1: LLM Provider Abstraction

**Decision**: Define `LlmProvider` interface with `generateToolCall(String userMessage, List<ToolDefinition> tools)` returning `ToolCall`. Concrete implementations registered via `@ConditionalOnProperty(name = "llm.provider")`.

**Method signature**: `generateToolCall(userMessage, tools)` — tools are passed as `List<ToolDefinition>` so each provider handles its own format translation internally. This avoids a shared builder and keeps each provider self-contained.

**Rationale**: FR-014 requires provider-agnostic AI integration. An interface isolates provider-specific details (auth headers, endpoint format, response parsing, tool schema format) behind a uniform contract. Spring's conditional beans enable runtime selection without code changes. Passing tools as a parameter (not building them inside the provider) keeps the interface pure — the caller controls which tools are available.

**Alternatives considered**:
- **Strategy pattern with factory**: Rejected — Spring DI handles selection naturally with `@ConditionalOnProperty`
- **Configuration-driven dynamic loading**: Rejected — over-engineered for v1; three providers max
- **Shared ToolSchemaBuilder injected into providers**: Rejected — adds coupling; each provider knows its own tool format best

### Decision 2: Tool Schema Format Per Provider

**Decision**: Each provider implementation translates `ToolDefinition` objects into its native tool schema format internally. The `AiController` passes a `List<ToolDefinition>` and each provider handles the format mapping.

**Rationale**: Different providers use different tool schema formats (Azure OpenAI uses `tools: [{type: "function", function: {...}}]`, Anthropic uses `tools: [{name, description, input_schema}]`). By keeping the translation inside each provider, we avoid a shared builder that must understand every provider's format. The `ToolDefinition` record is the common denominator; providers are adapters.

**Alternatives considered**:
- **Shared builder that produces all provider formats**: Rejected — builder must know every provider's format, becomes a maintenance burden when adding new providers

### Decision 3: Streaming Chunk Format

**Decision**: NDJSON (newline-delimited JSON) for inter-service streaming. Each line is a self-contained JSON object with `type`, `data`, `sequence`, and `timestamp` fields.

**Rationale**: Simple to parse line-by-line, works with any HTTP client, no framing protocol needed. Compatible with `RestClient` streaming response handling.

**Alternatives considered**:
- **SSE (Server-Sent Events)**: Rejected — adds `event:` and `id:` framing we don't need; raw NDJSON is simpler
- **Chunked JSON array**: Rejected — requires buffering to parse; NDJSON enables immediate line-by-line processing

### Decision 4: Cancellation Handling

**Decision**: Client closes HTTP connection → gateway detects broken pipe → sends cancel request to MCP server → MCP server cancels in-flight domain API call via interrupt.

**Rationale**: Standard HTTP semantics for cancellation. No WebSocket or separate cancel channel needed.

**Alternatives considered**:
- **Separate WebSocket cancel channel**: Rejected — adds connection management; HTTP close is sufficient
- **Polling-based status check**: Rejected — adds latency and complexity

### Decision 5: Token Usage Minimization

**Decision**: Constrained tool schema with minimal system prompt. Only user's validated prompt + tool definition sent to LLM. No examples, no few-shot prompts.

**Rationale**: Cost and latency optimization. Schema constraints are sufficient for tool selection accuracy.

### Decision 6: Provider Selection Mechanism

**Decision**: `@ConditionalOnProperty(name = "llm.provider", havingValue = "azure-openai")` on the Azure implementation, with a fallback default. New providers add a new class with their own condition.

**Rationale**: Zero-code provider addition — just drop in a new `LlmProvider` implementation with the right annotation and config properties.

### Decision 7: Network Isolation for LLM

**Decision**: LLM provider is an external service (Azure, Anthropic) accessed via public HTTPS. MCP server and domain API run on a private network segment with no inbound routes from the internet.

**Rationale**: Physical network boundary prevents LLM output from reaching data plane. Even if the LLM were compromised, it cannot call internal APIs.

## Resolved Unknowns

| Unknown | Resolution |
|---------|-----------|
| How to abstract LLM providers? | `LlmProvider` interface with `generateToolCall(String, List<ToolDefinition>)` returning `ToolCall` |
| How to handle different tool schema formats? | Each provider translates `ToolDefinition` → native format internally |
| How to keep tool schemas consistent? | `ToolDefinition` record is the common contract; providers are adapters |
| How to stream between services? | NDJSON over HTTP with `RestClient` streaming response |
| How to cancel in-flight requests? | HTTP connection close → broken pipe detection → cancel endpoint |
| How to validate provider-agnostic tool calls? | JSON schema validation happens in gateway, independent of provider |
| What if LLM returns natural language? | Provider throws `IllegalStateException`; controller returns 400 with guidance |
