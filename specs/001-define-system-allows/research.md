# Research: MCP-Based Report Generation Architecture

**Date**: 2026-05-05
**Feature**: Natural Language Report Generator

## Decisions

### Decision 1: Control Plane vs Data Plane Separation

**Decision**: Strict separation — AI Gateway (control plane) handles prompt engineering and LLM interaction; MCP Server + Domain API (data plane) handle execution and data retrieval.

**Rationale**: The core constraint is that the LLM must NEVER call domain APIs directly. By physically separating the LLM interaction layer from the execution layer, we enforce this at the network boundary. The control plane only produces JSON; the data plane only consumes it.

**Alternatives considered**:
- **Single service with internal modules**: Rejected — harder to enforce LLM isolation, a compromised LLM output could reach internal APIs
- **Function calling with direct API routing**: Rejected — couples LLM output to specific endpoints, violates the MCP tool abstraction

### Decision 2: Streaming Mechanism

**Decision**: Tomcat `StreamingResponseBody` for server-to-client streaming.

**Rationale**: Spring Boot 4 on Tomcat supports `StreamingResponseBody` natively on the Servlet stack. It provides backpressure-aware streaming without requiring WebFlux, keeping the architecture simpler and aligned with the constraint to use the Servlet stack.

**Alternatives considered**:
- **WebFlux (reactive stack)**: Rejected — constraint specifies Servlet stack/Tomcat
- **Server-Sent Events (SSE)**: Considered for server-to-client, but `StreamingResponseBody` gives us more control over chunk format and works well with JSON chunk envelopes
- **WebSocket**: Rejected — adds connection management complexity unnecessary for one-way streaming

### Decision 3: Inter-Service Communication

**Decision**: REST over HTTP with JSON payloads.

**Rationale**: Simple, well-understood, and Spring provides excellent REST client support via `RestClient` (Spring 6.2+). The MCP server calls the domain API via REST, and the gateway calls the MCP server via REST (wrapping MCP protocol).

**Alternatives considered**:
- **gRPC**: Rejected — adds protobuf complexity; REST is sufficient for report generation latency profile
- **Message queue (RabbitMQ/Kafka)**: Rejected — streaming needs real-time delivery, not async batching

### Decision 4: Tool Call Validation

**Decision**: JSON Schema validation against predefined tool schemas before execution.

**Rationale**: LLM output is inherently untrustworthy. Validating against a strict JSON schema ensures the tool call has the correct structure, required fields, and valid parameter types before any downstream call is made. Use `networknt/json-schema-validator` or Spring's built-in validation.

**Alternatives considered**:
- **Manual field-by-field validation**: Rejected — error-prone, hard to maintain as tools evolve
- **Bean Validation (@Valid)**: Rejected — applies to Java objects, but we need to validate raw JSON from LLM before deserialization

### Decision 5: MCP Protocol Implementation

**Decision**: Use the official MCP Java SDK for server implementation.

**Rationale**: The Model Context Protocol has an official Java SDK that handles protocol negotiation, tool registration, and message formatting. Using it ensures compatibility with the broader MCP ecosystem and reduces boilerplate.

**Alternatives considered**:
- **Custom MCP-like protocol**: Rejected — reinvents standardized protocol, loses ecosystem compatibility

### Decision 6: Token Usage Minimization

**Decision**: Use a constrained tool schema with minimal prompt context. Only send the user's validated prompt + the tool definition — no system instructions beyond what's necessary for tool selection.

**Rationale**: Each extra token costs money and adds latency. By keeping the prompt tight — just the user input and a compact tool schema — we minimize costs while maintaining accuracy.

**Alternatives considered**:
- **Rich system prompts with examples**: Rejected — increases token count significantly
- **Few-shot prompting**: Rejected — useful for accuracy but costs too many tokens; rely on schema constraints instead

### Decision 7: Authentication Propagation

**Decision**: JWT token propagation through all layers. Gateway validates JWT, propagates it to MCP server, which propagates to domain API.

**Rationale**: Each layer needs to know the user's identity for authorization. JWT is stateless, doesn't require token introspection at each hop, and Spring Security handles it natively.

**Alternatives considered**:
- **Session-based auth**: Rejected — not suitable for multi-service architecture
- **API key per service**: Rejected — doesn't carry user identity for per-user authorization at domain API level

## Resolved Unknowns

| Unknown | Resolution |
|---------|-----------|
| How to stream from domain API through MCP to gateway? | `StreamingResponseBody` at gateway; MCP server uses `RestClient` with streaming response; domain API uses `StreamingResponseBody` |
| What happens to LLM after it returns tool JSON? | Disconnected — LLM is out of the request flow after tool call is parsed. MCP server takes over execution. |
| How to handle report cancellation? | Gateway closes the `StreamingResponseBody` output stream on client disconnect; MCP server detects broken pipe and cancels the domain API call. |
| How to prevent LLM from calling domain APIs? | Network isolation: LLM (Azure OpenAI) is only reachable from the gateway. MCP server and domain API are on a private network segment not accessible from Azure OpenAI. |
