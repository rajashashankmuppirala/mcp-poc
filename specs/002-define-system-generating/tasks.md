# Tasks: Natural Language Report Generator

**Input**: Design documents from `/specs/002-define-system-generating/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Multi-module project initialization and dependency configuration

- [X] T001 Create multi-module Maven parent POM at `report-gateway/pom.xml` with modules: `domain-api`, `mcp-server`, `report-gateway`
- [X] T002 [P] Initialize `domain-api/` Spring Boot 4 module with `spring-boot-starter-web` dependency in `domain-api/pom.xml`
- [X] T003 [P] Initialize `mcp-server/` Spring Boot 4 module with `spring-boot-starter-web`, `spring-boot-starter-validation`, `networknt/json-schema-validator` in `mcp-server/pom.xml`
- [X] T004 [P] Initialize `report-gateway/` Spring Boot 4 module with `spring-boot-starter-web`, `spring-boot-starter-validation`, `networknt/json-schema-validator` in `report-gateway/pom.xml`
- [X] T005 [P] Create `.gitignore` with Java/Maven patterns at repository root

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that ALL user stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T006 [P] Create `DomainApiApplication.java` entry point in `domain-api/src/main/java/com/example/domain/`
- [X] T007 [P] Create `McpServerApplication.java` entry point in `mcp-server/src/main/java/com/example/mcp/`
- [X] T008 [P] Create `ReportGatewayApplication.java` entry point in `report-gateway/src/main/java/com/example/gateway/`
- [X] T009 [P] Configure `application.yml` for domain-api (port 8082) in `domain-api/src/main/resources/`
- [X] T010 [P] Configure `application.yml` for mcp-server (port 8081, `domain-api.url`) in `mcp-server/src/main/resources/`
- [X] T011 Configure `application.yml` for report-gateway (port 8080, `azure.openai.*`, `mcp.server.url`, `llm.provider`) in `report-gateway/src/main/resources/`
- [X] T012 Create `LlmProvider` interface in `report-gateway/src/main/java/com/example/gateway/service/LlmProvider.java` with `callWithTool(String, String)` and `providerName()` methods
- [X] T013 [P] Create `AiRequest` record with `@NotBlank` prompt and `@Size(max=500)` in `report-gateway/src/main/java/com/example/gateway/model/AiRequest.java`
- [X] T014 [P] Create `ToolCall` record with `tool` (String) and `parameters` (JsonNode) in `report-gateway/src/main/java/com/example/gateway/model/ToolCall.java`
- [X] T015 Create `GenerateReportRequest` record with `reportType`, `DateRange`, `filters`, `groupBy`, `limit` in `mcp-server/src/main/java/com/example/mcp/model/GenerateReportRequest.java`
- [X] T016 [P] Add unit test scaffolding: `pom.xml` test dependencies (JUnit 5, Mockito) for all three modules

**Checkpoint**: Foundation ready — all three services compile, start, and have models in place

---

## Phase 3: User Story 1 — Generate a Report via Natural Language (Priority: P1) 🎯 MVP

**Goal**: User submits a natural language prompt, system converts it to a tool call via LLM, executes through MCP server, and streams report data back

**Independent Test**: Send a natural language request (e.g., "Show me revenue by product for Q1") and verify a correctly formatted, streamed report is returned

### Tests for User Story 1

- [X] T017 [P] [US1] Domain API streaming controller test in `domain-api/src/test/java/com/example/domain/controller/ReportStreamControllerTest.java` — verify POST `/api/v1/reports/stream` returns NDJSON chunks
- [X] T018 [P] [US1] MCP Server tool controller test in `mcp-server/src/test/java/com/example/mcp/controller/ToolControllerTest.java` — verify POST `/tools/generate-report` validates input and streams response
- [X] T019 [P] [US1] Gateway AI controller test in `report-gateway/src/test/java/com/example/gateway/controller/AiControllerTest.java` — verify POST `/ai/request` orchestrates LLM → validate → MCP → stream
- [X] T020 [US1] Mock LLM provider test in `report-gateway/src/test/java/com/example/gateway/service/MockLlmProviderTest.java` — verify mock returns predetermined ToolCall

### Implementation for User Story 1

- [X] T021 [US1] Implement `ReportStreamController` in `domain-api/src/main/java/com/example/domain/controller/ReportStreamController.java` — POST `/api/v1/reports/stream` accepting `ReportQuery`, returning `StreamingResponseBody` with simulated NDJSON chunks
- [X] T022 [US1] Implement `ReportStreamService` in `mcp-server/src/main/java/com/example/mcp/service/ReportStreamService.java` — uses `HttpURLConnection` to call domain API `/api/v1/reports/stream`, streams response line-by-line without buffering
- [X] T023 [US1] Implement `ToolController` in `mcp-server/src/main/java/com/example/mcp/controller/ToolController.java` — POST `/tools/generate-report` with `@Valid` validation, delegates to `ReportStreamService`
- [X] T024 [US1] Implement `AzureOpenAiProvider` in `report-gateway/src/main/java/com/example/gateway/service/AzureOpenAiProvider.java` — `LlmProvider` implementation calling Azure OpenAI chat completions API with tool definitions, parsing response into `ToolCall`
- [X] T025 [US1] Implement `MockLlmProvider` in `report-gateway/src/main/java/com/example/gateway/service/MockLlmProvider.java` — `LlmProvider` implementation returning hardcoded `ToolCall` for testing, activated when `llm.provider=mock`
- [X] T026 [US1] Implement `McpClientService` in `report-gateway/src/main/java/com/example/gateway/service/McpClientService.java` — calls MCP server `/tools/generate-report`, returns `StreamingResponseBody` forwarding NDJSON chunks
- [X] T027 [US1] Implement `AiController` in `report-gateway/src/main/java/com/example/gateway/controller/AiController.java` — POST `/ai/request` orchestrating: validate input → call `LlmProvider.generateToolCall()` → validate tool call → route to `McpClientService` → stream response
- [X] T028 [US1] Implement `RequestLoggingFilter` in `report-gateway/src/main/java/com/example/gateway/filter/RequestLoggingFilter.java` — servlet filter generating UUID correlation IDs, logging request/response, rate limiting (60 req/min per IP)

**Checkpoint**: User Story 1 fully functional — prompt → LLM → tool call → MCP → domain API → streamed report

---

## Phase 4: User Story 2 — Handle Invalid or Ambiguous Requests (Priority: P2)

**Goal**: Graceful error handling for malformed, ambiguous, or unauthorized requests with actionable user feedback

**Independent Test**: Send vague, invalid, or unauthorized requests and verify helpful error responses are returned

### Tests for User Story 2

- [X] T029 [P] [US2] Gateway validation error test in `report-gateway/src/test/java/com/example/gateway/controller/AiControllerTest.java` — verify 400 response for empty/oversized prompts
- [X] T030 [P] [US2] Tool call validation error test in `report-gateway/src/test/java/com/example/gateway/service/ToolCallValidatorTest.java` — verify rejection of invalid tool JSON
- [X] T031 [US2] MCP server error handling test in `mcp-server/src/test/java/com/example/mcp/integration/McpServerStreamingIntegrationTest.java` — verify error responses for unknown tool, domain API timeout

### Implementation for User Story 2

- [X] T032 [US1] Implement `ToolCallValidator` in `report-gateway/src/main/java/com/example/gateway/service/ToolCallValidator.java` — JSON schema validation for tool parameters (startDate pattern `^\d{4}-\d{2}-\d{2}$`, region pattern `^[a-z]+-[a-z]+$`)
- [X] T033 [US2] Add `@ExceptionHandler` methods to `AiController` for `MethodArgumentNotValidException` (400), `IllegalArgumentException` (400), and generic `Exception` (500) returning structured error JSON
- [X] T034 [US2] Add input sanitization to `ReportTools` in `mcp-server/src/main/java/com/example/mcp/tool/ReportTools.java` — strip non-alphanumeric characters from tool name, validate parameter schema
- [X] T035 [US2] Add error chunk type to streaming response — domain API returns `{"type":"error","data":{"message":"..."}}` chunk on failure

**Checkpoint**: All error paths return structured, user-friendly responses

---

## Phase 5: User Story 3 — Stream Large Reports Efficiently (Priority: P3)

**Goal**: Large reports delivered progressively as chunks are generated, with first chunk within 2 seconds

**Independent Test**: Request a report known to produce large output and verify chunks arrive incrementally within timing bounds

### Tests for User Story 3

- [X] T036 [P] [US3] End-to-end streaming integration test in `report-gateway/src/test/java/com/example/gateway/integration/StreamingIntegrationTest.java` — verify streaming with mock LLM
- [X] T037 [US3] Domain API large dataset streaming test in `domain-api/src/test/java/com/example/domain/controller/ReportStreamControllerLargeDataSetTest.java` — verify 30+ rows streamed

### Implementation for User Story 3

- [X] T038 [US3] Configure WebFlux async timeout in `report-gateway/src/main/resources/application.yml` — set appropriate timeout for streaming responses
- [X] T039 [US3] Implement chunk timing instrumentation in `McpClientService` — log time-to-first-chunk and inter-chunk latency
- [X] T040 [US3] Implement report completion signal — domain API sends `{"type":"footer","data":{"totalRows":N,"elapsedMs":M}}` as final chunk

**Checkpoint**: Large reports stream efficiently with measurable chunk timing

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [X] T048 [P] Create `report.sh` CLI client at repository root — reads user input (argv or stdin), POSTs to Gateway, prints streaming response in real-time
- [ ] T041 [P] Implement `POST /ai/cancel/{sessionId}` endpoint in `AiController` — sends cancel request to MCP server
- [ ] T042 [P] Implement `POST /tools/cancel/{streamId}` endpoint in `ToolController` — cancels in-flight domain API call
- [X] T043 Add structured logging with correlation ID propagation across all three services
- [X] T044 [P] Create `docker-compose.yml` at repository root with all three services
- [X] T045 [P] Create `Dockerfile` for each service in their respective module directories
- [ ] T046 Run integration test suite end-to-end with mock LLM provider
- [ ] T047 Run quickstart.md validation — verify all three services start and streaming works

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Foundational phase completion
  - User stories can then proceed in parallel (if staffed)
  - Or sequentially in priority order (P1 → P2 → P3)
- **Polish (Phase 6)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) — No dependencies on other stories
- **User Story 2 (P2)**: Can start after Foundational (Phase 2) — Integrates with US1 error handling paths
- **User Story 3 (P3)**: Can start after Foundational (Phase 2) — Builds on US1 streaming infrastructure

### Within Each User Story

- Tests MUST be written and FAIL before implementation
- Models before services
- Services before endpoints
- Core implementation before integration
- Story complete before moving to next priority

### Parallel Opportunities

- All Setup tasks T002–T005 marked [P] can run in parallel
- All Foundational tasks T006–T016 marked [P] can run in parallel (within Phase 2)
- Once Foundational phase completes, all user stories can start in parallel
- All tests for a user story marked [P] can run in parallel
- Models within a story marked [P] can run in parallel

---

## Parallel Example: User Story 1

```bash
# Launch all tests for User Story 1 together:
Task: "Domain API streaming controller test (T017)"
Task: "MCP Server tool controller test (T018)"
Task: "Gateway AI controller test (T019)"
Task: "Mock LLM provider test (T020)"

# Launch services in parallel (different files):
Task: "ReportStreamController (T021) — domain-api"
Task: "ReportStreamService (T022) — mcp-server"
Task: "ToolController (T023) — mcp-server"
Task: "AzureOpenAiProvider (T024) — report-gateway"
Task: "MockLlmProvider (T025) — report-gateway"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL — blocks all stories)
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: Test end-to-end flow with mock LLM provider
5. Deploy/demo if ready

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test independently → Deploy/Demo (MVP!)
3. Add User Story 2 → Test independently → Deploy/Demo
4. Add User Story 3 → Test independently → Deploy/Demo
5. Each story adds value without breaking previous stories

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. Once Foundational is done:
   - Developer A: User Story 1 (gateway + LLM)
   - Developer B: User Story 1 (MCP server + domain API)
   - Developer C: User Story 2 (error handling)
3. Stories complete and integrate independently
