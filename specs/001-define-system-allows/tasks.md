# Tasks: Natural Language Report Generator

**Input**: Design documents from `/specs/001-define-system-allows/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Included — integration and contract tests requested per feature spec (FR-012 audit logging, FR-009 authorization at each layer, SC-006 LLM isolation verification).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

Three independent Spring Boot services at repository root:

```
report-gateway/     # AI Gateway (port 8080)
mcp-server/         # MCP Server (port 8081)
domain-api/         # Domain API (port 8082)
```

Each service follows `src/main/java/com/example/<service>/` package structure.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Multi-module Maven project initialization, dependency management, build configuration

- [ ] T001 Create parent `pom.xml` with Spring Boot 4 BOM, Java 21, and three modules (`report-gateway`, `mcp-server`, `domain-api`) at repository root
- [ ] T002 [P] Create `report-gateway/pom.xml` with dependencies: `spring-cloud-starter-gateway`, `spring-boot-starter-web`, `azure-openai-sdk`, `spring-boot-starter-security`, `spring-boot-starter-validation`, `jackson-databind`
- [ ] T003 [P] Create `mcp-server/pom.xml` with dependencies: `spring-boot-starter-web`, `mcp-java-sdk` (or equivalent), `spring-boot-starter-security`, `spring-boot-starter-validation`, `networknt-json-schema-validator`
- [ ] T004 [P] Create `domain-api/pom.xml` with dependencies: `spring-boot-starter-web`, `spring-boot-starter-security`, `spring-boot-starter-validation`, `jackson-databind`
- [ ] T005 [P] Create `report-gateway/src/main/resources/application.yml` with server port 8080, Azure OpenAI endpoint placeholder, MCP server URL
- [ ] T006 [P] Create `mcp-server/src/main/resources/application.yml` with server port 8081, domain API URL, tool registry config
- [ ] T007 [P] Create `domain-api/src/main/resources/application.yml` with server port 8082
- [ ] T008 Create Maven wrapper (`mvnw`) at repository root

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared models, security framework, configuration management, error handling — MUST complete before any user story work

- [ ] T009 [P] Create shared DTO `ReportChatRequest.java` in `report-gateway/src/main/java/com/example/gateway/model/` with fields: `prompt` (String, @NotBlank, @Size(max=500)), `reportType` (String), `sessionId` (String, UUID format)
- [ ] T010 [P] Create shared DTO `ToolCall.java` in `report-gateway/src/main/java/com/example/gateway/model/` with fields: `tool` (String), `parameters` (JsonNode)
- [ ] T011 [P] Create shared DTO `ReportChunk.java` in `report-gateway/src/main/java/com/example/gateway/model/` with fields: `type` (enum: header/data/footer/error), `sessionId` (String), `data` (JsonNode), `sequence` (int), `timestamp` (Instant)
- [ ] T012 [P] Create `ReportQuery.java` in `domain-api/src/main/java/com/example/domain/model/` with fields: `reportType`, `dateRange` (nested object), `filters` (Map<String,String>), `groupBy` (List<String>), `limit` (int, default 1000)
- [ ] T013 [P] Create `ReportDataRow.java` in `domain-api/src/main/java/com/example/domain/model/` as generic row representation (Map<String, Object>)
- [ ] T014 [P] Create `ToolDefinition.java` in `mcp-server/src/main/java/com/example/mcp/model/` with MCP tool schema fields: `name`, `description`, `inputSchema` (JsonNode)
- [ ] T015 [P] Create `ToolExecutionResult.java` in `mcp-server/src/main/java/com/example/mcp/model/` with fields: `success` (boolean), `streamId` (String), `error` (String)
- [ ] T016 Create JWT authentication filter in `report-gateway/src/main/java/com/example/gateway/filter/JwtAuthenticationFilter.java` — validates Authorization header, extracts user claims, propagates via request attributes
- [ ] T017 [P] Create JWT propagation interceptor in `mcp-server/src/main/java/com/example/mcp/config/JwtPropagationInterceptor.java` — reads `X-User-Id` and `X-User-Roles` headers from gateway requests
- [ ] T018 [P] Create JWT authentication filter in `domain-api/src/main/java/com/example/domain/filter/JwtAuthenticationFilter.java` — validates JWT on incoming requests
- [ ] T019 Create global error handler in `report-gateway/src/main/java/com/example/gateway/config/GlobalExceptionHandler.java` — returns structured error JSON with `error`, `message`, `sessionId` fields
- [ ] T020 [P] Create global error handler in `mcp-server/src/main/java/com/example/mcp/config/GlobalExceptionHandler.java`
- [ ] T021 [P] Create global error handler in `domain-api/src/main/java/com/example/domain/config/GlobalExceptionHandler.java`
- [ ] T022 Create environment configuration class in `report-gateway/src/main/java/com/example/gateway/config/AzureOpenAIConfig.java` — reads `azure.openai.endpoint`, `azure.openai.api-key`, `azure.openai.deployment` from environment
- [ ] T023 [P] Create environment configuration class in `mcp-server/src/main/java/com/example/mcp/config/DomainApiClientConfig.java` — reads `domain-api.url`, `domain-api.timeout`
- [ ] T024 [P] Create Spring Security config in each service (`report-gateway`, `mcp-server`, `domain-api`) — permit health check endpoints, require auth for all others

**Checkpoint**: Foundation ready — models, security, error handling, and configuration in place. User story implementation can now begin.

---

## Phase 3: User Story 1 — Generate a Report via Natural Language (Priority: P1)

**Goal**: User sends natural language prompt → LLM converts to tool JSON → MCP server executes → domain API streams data → client receives chunks

**Independent Test**: Send `POST /api/v1/reports/chat` with a valid prompt and receive streamed JSON chunks with report data. Verify LLM output is parsed as tool JSON and never directly calls domain APIs.

### Tests for User Story 1

- [ ] T025 [P] [US1] Contract test for `POST /api/v1/reports/chat` in `report-gateway/src/test/java/com/example/gateway/contract/ReportChatContractTest.java` — validates request/response schema
- [ ] T026 [P] [US1] Contract test for `POST /api/v1/reports/stream` in `domain-api/src/test/java/com/example/domain/contract/ReportStreamContractTest.java` — validates streaming chunk schema
- [ ] T027 [P] [US1] Contract test for `POST /internal/tools/execute` in `mcp-server/src/test/java/com/example/mcp/contract/ToolExecuteContractTest.java` — validates tool execution request/response
- [ ] T028 [US1] Integration test for end-to-end flow in `report-gateway/src/test/java/com/example/gateway/integration/ReportGenerationIntegrationTest.java` — uses mocked Azure OpenAI and domain API to verify full pipeline

### Implementation for User Story 1

#### Domain API (start here — no upstream dependencies)

- [ ] T029 [US1] Create `ReportStreamController.java` in `domain-api/src/main/java/com/example/domain/controller/` with `POST /api/v1/reports/stream` endpoint returning `StreamingResponseBody`
- [ ] T030 [US1] Create `ReportGeneratorService.java` in `domain-api/src/main/java/com/example/domain/service/` — accepts `ReportQuery`, produces `ReportChunk` objects via `StreamingResponseBody` write loop
- [ ] T031 [US1] Create `DataSourceService.java` in `domain-api/src/main/java/com/example/domain/service/` — mock data source returning sample report rows (to be replaced with real data later)
- [ ] T032 [US1] Create `ReportTypeController.java` in `domain-api/src/main/java/com/example/domain/controller/` with `GET /api/v1/reports/types` endpoint returning available report types and their parameter schemas

#### MCP Server

- [ ] T033 [US1] Create `GenerateReportTool.java` in `mcp-server/src/main/java/com/example/mcp/tool/` — MCP tool implementation for `generate_report`, validates input against JSON schema, delegates to `McpToolExecutorService`
- [ ] T034 [US1] Create `ListReportsTool.java` in `mcp-server/src/main/java/com/example/mcp/tool/` — MCP tool for `list_reports`, returns available report types from domain API
- [ ] T035 [US1] Create `McpToolExecutorService.java` in `mcp-server/src/main/java/com/example/mcp/service/` — receives parsed tool call, maps to domain API endpoint, invokes `DomainApiClientService`, streams response back
- [ ] T036 [US1] Create `DomainApiClientService.java` in `mcp-server/src/main/java/com/example/mcp/service/` — `RestClient`-based HTTP client that calls `POST /api/v1/reports/stream` on domain API, handles streaming response with backpressure
- [ ] T037 [US1] Create `ToolExecuteController.java` in `mcp-server/src/main/java/com/example/mcp/controller/` with `POST /internal/tools/execute` endpoint — REST wrapper for tool execution (accepts tool call JSON, returns streaming response)
- [ ] T038 [US1] Create `ToolRegistryConfig.java` in `mcp-server/src/main/java/com/example/mcp/config/` — registers `generate_report` and `list_reports` tool definitions with their JSON schemas

#### AI Gateway

- [ ] T039 [US1] Create `PromptBuilderService.java` in `report-gateway/src/main/java/com/example/gateway/service/` — constructs minimal prompt with user input + tool definition, constrains LLM to output only tool JSON
- [ ] T040 [US1] Create `AzureOpenAIClientService.java` in `report-gateway/src/main/java/com/example/gateway/service/` — calls Azure OpenAI with tool-calling mode, extracts tool call JSON from response
- [ ] T041 [US1] Create `ToolCallParserService.java` in `report-gateway/src/main/java/com/example/gateway/service/` — validates LLM output against tool JSON schema, parses into `ToolCall` object
- [ ] T042 [US1] Create `StreamingResponseService.java` in `report-gateway/src/main/java/com/example/gateway/service/` — receives chunks from MCP server, writes them to client `OutputStream` via `StreamingResponseBody`
- [ ] T043 [US1] Create `ReportChatController.java` in `report-gateway/src/main/java/com/example/gateway/controller/` with `POST /api/v1/reports/chat` — orchestrates: validate input → build prompt → call LLM → parse tool call → execute via MCP → stream to client
- [ ] T044 [US1] Create `ReportTypesController.java` in `report-gateway/src/main/java/com/example/gateway/controller/` with `GET /api/v1/reports/types` — proxies to MCP server's `list_reports` tool

**Checkpoint**: End-to-end flow working. User can type a natural language report request and receive streamed results. LLM is isolated from domain APIs — only produces tool JSON.

---

## Phase 4: User Story 2 — Handle Ambiguous or Invalid Requests (Priority: P2)

**Goal**: Graceful handling of malformed input, ambiguous prompts, unauthorized access — with clear user-facing error messages

**Independent Test**: Send invalid, ambiguous, and unauthorized requests to the gateway and verify appropriate error responses without internal detail leakage.

### Tests for User Story 2

- [ ] T045 [P] [US2] Validation test for empty/long prompts in `report-gateway/src/test/java/com/example/gateway/filter/InputValidationFilterTest.java`
- [ ] T046 [P] [US2] Authorization test in `mcp-server/src/test/java/com/example/mcp/service/McpToolExecutorAuthTest.java` — verifies unauthorized tool calls are rejected
- [ ] T047 [US2] Integration test for ambiguous request handling in `report-gateway/src/test/java/com/example/gateway/integration/AmbiguousRequestIntegrationTest.java`

### Implementation for User Story 2

- [ ] T048 [P] [US2] Create `InputValidationFilter.java` in `report-gateway/src/main/java/com/example/gateway/filter/` — validates prompt length (1-500 chars), rejects control characters, sanitizes input before LLM
- [ ] T049 [US2] Create `AmbiguityHandlerService.java` in `report-gateway/src/main/java/com/example/gateway/service/` — when LLM cannot resolve a report type, returns clarification response listing available report types
- [ ] T050 [US2] Update `ToolCallParserService.java` — add handling for LLM responses that indicate ambiguity (e.g., LLM returns `tool: "clarify"` instead of a concrete tool)
- [ ] T051 [US2] Add authorization check in `McpToolExecutorService.java` — verifies user has permission for the requested report type before calling domain API
- [ ] T052 [US2] Add permission check in `domain-api/src/main/java/com/example/domain/service/ReportGeneratorService.java` — verifies user's data scope before generating report rows
- [ ] T053 [US2] Update error handlers in all three services — map internal exceptions to user-friendly error messages (no stack traces, no internal URLs)

**Checkpoint**: Invalid and ambiguous requests are handled gracefully. Users receive actionable error messages. Unauthorized access is blocked at each layer.

---

## Phase 5: User Story 3 — Stream Large Reports Incrementally (Priority: P3)

**Goal**: Large reports stream in chunks with first chunk under 2s, subsequent chunks under 1s, with completion signal

**Independent Test**: Request a report that produces 5000+ rows and verify chunks arrive incrementally with correct sequence numbers and timing.

### Tests for User Story 3

- [ ] T054 [P] [US3] Streaming timing test in `domain-api/src/test/java/com/example/domain/service/StreamingPerformanceTest.java` — verifies first chunk within 2s
- [ ] T055 [US3] Integration test for chunk ordering in `report-gateway/src/test/java/com/example/gateway/integration/StreamingOrderIntegrationTest.java` — verifies sequence numbers are monotonic

### Implementation for User Story 3

- [ ] T056 [US3] Update `ReportGeneratorService.java` — implement chunked data emission with configurable batch size (default 100 rows per chunk), emit `header` chunk first, `data` chunks for batches, `footer` chunk with totals
- [ ] T057 [US3] Update `DomainApiClientService.java` — handle streaming response from domain API, parse each chunk as it arrives, forward to gateway without buffering
- [ ] T058 [US3] Update `StreamingResponseService.java` — write each chunk to client `OutputStream` immediately, flush after each write, handle client disconnect (broken pipe) gracefully
- [ ] T059 [US3] Implement cancellation support in `ReportChatController.java` — detect client disconnect, propagate cancellation signal to MCP server, which cancels domain API call
- [ ] T060 [US3] Add `TokenUsageFilter.java` in `report-gateway/src/main/java/com/example/gateway/filter/` — tracks and logs LLM token consumption per request for cost monitoring

**Checkpoint**: Large reports stream incrementally. First chunk arrives within 2s. Client disconnect cancels upstream processing. Token usage is tracked.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Docker setup, validation hardening, integration tests, documentation

- [ ] T061 [P] Create `Dockerfile` for `report-gateway` at `report-gateway/Dockerfile` — multi-stage build, JRE 21 base, non-root user
- [ ] T062 [P] Create `Dockerfile` for `mcp-server` at `mcp-server/Dockerfile` — same pattern
- [ ] T063 [P] Create `Dockerfile` for `domain-api` at `domain-api/Dockerfile` — same pattern
- [ ] T064 Create `docker-compose.yml` at repository root — defines all three services with network isolation (gateway on public network, MCP + domain API on private network, Azure OpenAI only reachable from gateway)
- [ ] T065 [P] Create JSON schema validation middleware in `mcp-server/src/main/java/com/example/mcp/service/JsonSchemaValidationService.java` — validates tool call parameters against registered tool schemas before execution
- [ ] T066 [P] Create rate limiting filter in `report-gateway/src/main/java/com/example/gateway/filter/RateLimitingFilter.java` — configurable requests-per-minute per user
- [ ] T067 [P] Create request timeout configuration in `report-gateway/src/main/java/com/example/gateway/config/TimeoutConfig.java` — configurable per-request timeout (default 30s)
- [ ] T068 Add audit logging in `report-gateway/src/main/java/com/example/gateway/service/AuditLoggingService.java` — logs request metadata (prompt hash, report type, user ID, timestamp, outcome) without sensitive data
- [ ] T069 End-to-end integration test in `report-gateway/src/test/java/com/example/gateway/integration/FullPipelineIntegrationTest.java` — tests complete flow from chat request through streamed response with all three services running
- [ ] T070 LLM isolation verification test in `report-gateway/src/test/java/com/example/gateway/integration/LlmIsolationVerificationTest.java` — confirms Azure OpenAI endpoint is the only external call from gateway, no direct domain API calls
- [ ] T071 [P] Update `quickstart.md` with Docker-based startup instructions and verification commands
- [ ] T072 Run `quickstart.md` validation — start all services, send test request, verify streaming output

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **User Stories (Phase 3-5)**: All depend on Foundational phase completion
  - User stories can proceed sequentially (P1 → P2 → P3) or in parallel if staffed
- **Polish (Phase 6)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) — no dependencies on other stories
- **User Story 2 (P2)**: Can start after Foundational (Phase 2) — builds on US1 infrastructure (error handling, auth)
- **User Story 3 (P3)**: Can start after Foundational (Phase 2) — builds on US1 streaming infrastructure

### Within Each User Story

- Tests MUST be written and FAIL before implementation
- Models before services
- Services before controllers/endpoints
- Core implementation before integration
- Story complete before moving to next priority

### Within User Story 1 (critical path)

Domain API must be implemented first (T029-T032), then MCP Server (T033-T038), then AI Gateway (T039-T044). This is because each layer depends on the one below it.

### Parallel Opportunities

- All Setup tasks marked [P] can run in parallel (T002-T007)
- All Foundational models/DTOs marked [P] can run in parallel (T009-T015)
- All error handlers marked [P] can run in parallel (T019-T021)
- All security configs marked [P] can run in parallel (T016-T018, T024)
- All US1 contract tests marked [P] can run in parallel (T025-T027)
- Domain API controllers/services within US1 can start before MCP Server is ready (T029-T032 are independent of T033+)
- All Dockerfiles marked [P] can run in parallel (T061-T063)
- Different user stories can be worked on in parallel by different team members after Foundational phase

---

## Parallel Example: User Story 1

```bash
# Launch all US1 contract tests together:
Task: "Contract test for POST /api/v1/reports/chat in report-gateway/src/test/.../ReportChatContractTest.java"
Task: "Contract test for POST /api/v1/reports/stream in domain-api/src/test/.../ReportStreamContractTest.java"
Task: "Contract test for POST /internal/tools/execute in mcp-server/src/test/.../ToolExecuteContractTest.java"

# Launch all US1 foundational models together:
Task: "Create ReportChatRequest, ToolCall, ReportChunk DTOs in report-gateway/src/main/.../model/"
Task: "Create ReportQuery, ReportDataRow DTOs in domain-api/src/main/.../model/"
Task: "Create ToolDefinition, ToolExecutionResult DTOs in mcp-server/src/main/.../model/"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (7 tasks)
2. Complete Phase 2: Foundational (16 tasks) — BLOCKS all stories
3. Complete Phase 3: User Story 1 (20 tasks)
   - Start with Domain API (T029-T032) — no upstream dependencies
   - Then MCP Server (T033-T038) — depends on Domain API contract
   - Then AI Gateway (T039-T044) — depends on MCP Server contract
4. **STOP and VALIDATE**: Test end-to-end flow with mocked Azure OpenAI
5. Deploy/demo if ready

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready (23 tasks)
2. Add User Story 1 → Test independently → Deploy/Demo (MVP! 20 tasks)
3. Add User Story 2 → Test independently → Deploy/Demo (9 tasks)
4. Add User Story 3 → Test independently → Deploy/Demo (8 tasks)
5. Add Polish & Docker → Complete system (12 tasks)

Each story adds value without breaking previous stories.

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. Once Foundational is done:
   - Developer A: User Story 1 (Domain API → MCP Server → Gateway)
   - Developer B: User Story 2 (validation, ambiguity handling)
   - Developer C: User Story 3 (streaming optimization, cancellation)
3. Stories complete and integrate independently

---

## Task Summary

| Phase | Tasks | Description |
|-------|-------|-------------|
| Phase 1: Setup | 8 | Multi-module project, dependencies, config files |
| Phase 2: Foundational | 16 | Models, security, error handling, config |
| Phase 3: US1 (P1) | 20 | End-to-end NL report generation with streaming |
| Phase 4: US2 (P2) | 9 | Error handling, validation, authorization |
| Phase 5: US3 (P3) | 8 | Streaming optimization, cancellation, token tracking |
| Phase 6: Polish | 12 | Docker, JSON schema validation, rate limiting, audit, integration tests |
| **Total** | **73** | |

**Parallel opportunities identified**: 28 tasks marked [P] can run in parallel within their phase.

**Independent test criteria**:
- US1: Send NL prompt → receive streamed report chunks
- US2: Send invalid/ambiguous/unauthorized request → receive actionable error
- US3: Request large report → first chunk under 2s, monotonic sequence numbers, cancellation works

**Suggested MVP scope**: Phases 1 + 2 + 3 (44 tasks) — delivers core natural language report generation with streaming.
