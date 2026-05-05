# Feature Specification: Natural Language Report Generator

**Feature Branch**: `001-define-system-allows`  
**Created**: 2026-05-05  
**Status**: Draft  
**Input**: User description: "Define a system that allows users to generate reports via natural language. Constraints: Spring Boot 4 (Servlet stack, Tomcat), Spring Cloud Gateway as AI Gateway, Azure OpenAI for LLM, LLM must ONLY convert prompt to tool JSON, LLM must NEVER call domain API. Functional: User sends chat request, System converts to structured tool call, MCP server executes report generation, Domain API streams report data. Non-Functional: Streaming response (chunked), Secure boundaries between layers, Minimal token usage, Input validation at all layers. Output: Architecture design, Component responsibilities, API contracts"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Generate a Report via Natural Language (Priority: P1)

A user interacts with a chat interface to request a report using plain language. The system interprets the request, maps it to a structured report generation tool call, executes the report against domain data sources, and streams the results back to the user in real-time.

**Why this priority**: This is the core value proposition — users must be able to describe what report they want in natural language and receive accurate results without knowing technical query syntax.

**Independent Test**: Can be fully tested by sending a natural language request (e.g., "Show me monthly sales for Q1") and verifying that a streamed, correctly formatted report is returned.

**Acceptance Scenarios**:

1. **Given** a user is authenticated and has access to the chat interface, **When** the user types "Show me revenue by product category for last quarter", **Then** the system streams a structured report with revenue figures grouped by product category for the specified period.
2. **Given** the user sends a valid report request, **When** the LLM translates the prompt into a tool call, **Then** the tool call is routed to the MCP server which executes it against the domain API, never directly calling the domain API from the LLM layer.
3. **Given** the domain API returns report data, **When** the data is streamed back, **Then** the user sees incremental chunks of the report as they become available.

---

### User Story 2 - Handle Ambiguous or Invalid Requests (Priority: P2)

A user sends a request that is ambiguous, malformed, or references data they don't have access to. The system provides clear feedback without exposing internal errors or sensitive data.

**Why this priority**: Users will inevitably send unclear requests; graceful handling maintains trust and usability.

**Independent Test**: Can be fully tested by sending vague, contradictory, or unauthorized requests and verifying that helpful error responses are returned.

**Acceptance Scenarios**:

1. **Given** a user sends an ambiguous request like "show me the numbers", **When** the system cannot determine the report type, **Then** it responds with a clarification prompt listing available report categories.
2. **Given** a user requests data outside their access scope, **When** the domain API evaluates the request, **Then** the system returns a permission-denied message without exposing internal details.
3. **Given** a user sends a malformed input (e.g., empty string, special characters only), **When** input validation runs at the boundary, **Then** the system rejects the request with a clear validation error.

---

### User Story 3 - Stream Large Reports Incrementally (Priority: P3)

A user requests a report that produces a large volume of data. Instead of waiting for the full result, the user receives data chunks progressively as they are generated.

**Why this priority**: Large reports should not block the user experience; streaming ensures responsiveness even for heavy queries.

**Independent Test**: Can be fully tested by requesting a report known to produce large output and verifying that chunks arrive incrementally within expected timing bounds.

**Acceptance Scenarios**:

1. **Given** a user requests a report with a large dataset, **When** the domain API begins generating results, **Then** the first chunk arrives at the user within 2 seconds.
2. **Given** a streaming session is active, **When** additional data chunks are produced, **Then** each chunk is delivered to the user within 1 second of generation.
3. **Given** the report generation completes, **When** the final chunk is sent, **Then** the stream terminates with a completion signal.

---

### Edge Cases

- What happens when the domain API is unavailable or times out? The system returns a timeout error with a retry suggestion.
- How does the system handle concurrent report requests from the same user? Requests are queued and processed in order, with a configurable concurrency limit.
- What happens when the LLM fails to produce valid tool JSON? The system catches the parsing error and returns a message indicating the request could not be understood.
- How does the system handle malformed or malicious input designed to exploit the LLM? Input is validated and sanitized at every layer boundary before processing.
- What happens when the requested report type does not exist? The system returns a clear error listing available report types.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST accept natural language report requests from authenticated users via a chat interface
- **FR-002**: System MUST validate all user input at the API boundary before forwarding to the LLM
- **FR-003**: System MUST send the validated prompt to an LLM with a tool definition that constrains the LLM to output only structured tool call JSON
- **FR-004**: LLM output MUST be parsed and validated as a tool call JSON object before any downstream processing
- **FR-005**: System MUST route the parsed tool call to an MCP server for execution — the LLM MUST NOT directly call any domain API
- **FR-006**: MCP server MUST execute the tool call by invoking the appropriate domain API endpoint
- **FR-007**: Domain API MUST stream report data in chunks as it is generated
- **FR-008**: System MUST forward streamed chunks to the user in real-time via a streaming HTTP response
- **FR-009**: System MUST enforce authorization checks at each layer: gateway, MCP server, and domain API
- **FR-010**: System MUST handle invalid, ambiguous, or malformed requests with user-friendly error messages
- **FR-011**: System MUST support report cancellation by the user during streaming
- **FR-012**: System MUST log all report generation requests and outcomes for audit purposes (excluding sensitive data)
- **FR-013**: System MUST enforce a configurable request timeout to prevent long-running queries from consuming resources

### Key Entities

- **Report Request**: A user-initiated natural language request to generate a report, containing the user's prompt, timestamp, and session context.
- **Tool Call**: A structured JSON object derived from the LLM output that specifies the report type, parameters (e.g., date range, filters), and target data source.
- **Report Stream**: A sequence of data chunks streamed from the domain API to the user, representing incremental portions of the final report.
- **Report Session**: The end-to-end lifecycle of a single report generation request, from initial prompt through final delivery or error.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can describe a report in natural language and receive valid streamed results within 5 seconds for queries returning under 1,000 rows
- **SC-002**: 95% of valid natural language requests are correctly translated to tool calls without requiring user rephrasing
- **SC-003**: The first streamed chunk reaches the user within 2 seconds of request submission for all queries under normal load
- **SC-004**: System handles 50 concurrent streaming report sessions without degradation in response time
- **SC-005**: All user-facing error messages are actionable — users can understand what went wrong and what to do next (validated via user testing with 90% comprehension rate)
- **SC-006**: Zero incidents of the LLM directly invoking domain APIs — verified through network trace analysis and gateway logs

## Assumptions

- Users are already authenticated via the existing identity system before accessing the chat interface
- Domain APIs already exist and support streaming responses (e.g., via Server-Sent Events or chunked transfer encoding)
- An MCP server infrastructure is available or will be provisioned for tool execution
- Azure OpenAI service is provisioned and accessible within the deployment environment
- Report types and their parameter schemas are predefined and registered as MCP tools
- Target users are business analysts or operators familiar with the domain terminology used in reports
- The system operates within a single cloud region for v1; multi-region support is out of scope
- Token usage from the LLM is minimized by using a constrained tool schema and minimal prompt context
