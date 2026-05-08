package com.example.gateway.controller;

import com.example.gateway.model.AiRequest;
import com.example.gateway.model.ChartResponse;
import com.example.gateway.model.ConversationTurn;
import com.example.gateway.model.SessionContext;
import com.example.gateway.model.SkillDefinition;
import com.example.gateway.model.ToolCall;
import com.example.gateway.model.ToolDefinition;
import com.example.gateway.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Thrown when the LLM determines the prompt does not match any available tool.
 */
class NoToolMatchException extends RuntimeException {
    NoToolMatchException(String message) { super(message); }
}

@RestController
@RequestMapping("/ai")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    private final LlmProvider llmProvider;
    private final ToolCallValidator validator;
    private final McpClientService mcpClient;
    private final PromptInjectionDetector injectionDetector;
    private final SkillRegistry skillRegistry;
    private final AuditLogger auditLogger;
    private final ChartGenerationService chartService;
    private final ConversationService conversationService;
    private final ContextInjector contextInjector;
    private final String fallbackMessage;

    private static final Set<String> CHART_SKILLS = Set.of("chart_builder");
    private static final String SESSION_ID_HEADER = "X-Session-ID";
    private static final String SESSION_COOKIE_NAME = "session-id";

    public AiController(LlmProvider llmProvider,
                        ToolCallValidator validator,
                        McpClientService mcpClient,
                        PromptInjectionDetector injectionDetector,
                        SkillRegistry skillRegistry,
                        AuditLogger auditLogger,
                        ChartGenerationService chartService,
                        ConversationService conversationService,
                        ContextInjector contextInjector,
                        @Value("${llm.fallback-message:Sorry, I cannot help with this request.}") String fallbackMessage) {
        this.llmProvider = llmProvider;
        this.validator = validator;
        this.mcpClient = mcpClient;
        this.injectionDetector = injectionDetector;
        this.skillRegistry = skillRegistry;
        this.auditLogger = auditLogger;
        this.chartService = chartService;
        this.conversationService = conversationService;
        this.contextInjector = contextInjector;
        this.fallbackMessage = fallbackMessage;
    }

    @PostMapping(value = "/request", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> handleAiRequest(@RequestBody AiRequest request, ServerWebExchange exchange) {
        String correlationId = UUID.randomUUID().toString();
        log.info("[{}] Received AI request: {}", correlationId, request.prompt());

        // Layer 1: Prompt injection check
        injectionDetector.check(request.prompt());

        // Layer 2: Skill matching
        SkillDefinition matchedSkill = skillRegistry.matchSkill(request.prompt());
        String skillName = matchedSkill != null ? matchedSkill.name() : "none";
        log.info("[{}] Matched skill: {}{}", correlationId, skillName,
                matchedSkill != null ? " (server=" + matchedSkill.mcpServer() + ")" : "");

        // Resolve tools: if skill matched, filter to skill's allowed tools
        List<ToolDefinition> tools;
        if (matchedSkill != null && !matchedSkill.allowedTools().isEmpty()) {
            tools = mcpClient.getDiscoveredTools().stream()
                    .filter(t -> matchedSkill.allowedTools().contains(t.name()))
                    .toList();
            log.info("[{}] Skill-scoped tools: {} (filtered from {})", correlationId,
                    tools.size(), mcpClient.getDiscoveredTools().size());
        } else {
            tools = mcpClient.getDiscoveredTools();
        }

        if (tools.isEmpty()) {
            log.warn("[{}] No tools available for request", correlationId);
            return Flux.error(new NoToolMatchException(fallbackMessage));
        }

        String systemPrompt = matchedSkill != null ? matchedSkill.systemPrompt() : null;

        // Extract auth token from request header for downstream propagation
        String userToken = exchange.getRequest().getHeaders().getFirst("X-User-Token");

        // Extract or create session for conversational memory
        String sessionId = extractSessionId(exchange);

        boolean isDownload = request.prompt().toLowerCase().contains("download");

        // Load session, execute, save turn, stream response
        return conversationService.loadOrCreateSession(sessionId)
                .flatMapMany(sessionContext -> {
                    // Step 1: Call LLM provider with skill system prompt
                    Mono<ToolCall> toolCallMono = llmProvider.generateToolCall(request.prompt(), tools, systemPrompt)
                            .switchIfEmpty(Mono.defer(() -> {
                                log.info("[{}] LLM ({}) returned null — prompt does not match any tool", correlationId, llmProvider.providerName());
                                return Mono.error(new NoToolMatchException(fallbackMessage));
                            }))
                            .doOnNext(tc -> log.info("[{}] LLM ({}) returned tool: {}", correlationId, llmProvider.providerName(), tc.tool()));

                    // Step 2: Validate, then save turn with extracted filters
                    Mono<SavedToolCall> savedMono = toolCallMono
                            .doOnNext(tc -> {
                                validator.validate(tc);
                                log.info("[{}] Tool call validated", correlationId);
                            })
                            .flatMap(tc -> {
                                ConversationTurn turn = contextInjector.createTurn(
                                        request.prompt(), tc,
                                        new ContextInjector.ResponseMetadata("report", null, null, null));
                                return conversationService.saveTurn(sessionContext, turn)
                                        .map(ctx -> new SavedToolCall(tc, ctx));
                            });

                    // Step 3: Execute via MCP client, parse JSON array, emit rows as SSE
                    Flux<String> rowStream = savedMono
                            .doOnNext(saved -> {
                                // Set session header before data starts streaming
                                addSessionHeaders(exchange, saved.context().sessionId());
                            })
                            .flatMapMany(saved -> mcpClient.executeToolCall(saved.toolCall(), correlationId, userToken)
                                    .doOnSubscribe(s ->
                                            auditLogger.log(correlationId, "REQUEST", Map.of(
                                                    "skill", skillName,
                                                    "tools_available", tools.size(),
                                                    "sessionId", saved.context().sessionId()
                                            ))
                                    )
                                    .flatMap(jsonArray -> {
                                        ObjectMapper mapper = new ObjectMapper();
                                        try {
                                            List<String> rows = mapper.readValue(jsonArray,
                                                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));
                                            return Flux.fromIterable(rows);
                                        } catch (Exception e) {
                                            log.warn("[{}] Failed to parse MCP array, emitting raw: {}", correlationId, e.getMessage());
                                            return Flux.just(jsonArray);
                                        }
                                    })
                            );

                    // For download mode, prepend a metadata line with the filename
                    if (isDownload) {
                        final String filename = "generate_report-" + System.currentTimeMillis() + ".csv";
                        return Flux.concat(Flux.just(filename), rowStream);
                    }
                    return rowStream;
                });
    }

    /**
     * Dedicated chart endpoint — returns JSON, not SSE.
     * Two-phase: LLM plans data query → fetch data → LLM generates Vega-Lite spec.
     * If chart type is ambiguous, returns a clarification response for user to pick.
     */
    @PostMapping(value = "/chart", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Object> handleChartRequest(@RequestBody AiRequest request, ServerWebExchange exchange) {
        String correlationId = UUID.randomUUID().toString();
        log.info("[{}] Chart request: {}", correlationId, request.prompt());

        // Extract or create session
        String sessionId = extractSessionId(exchange);

        // Layer 1: Prompt injection check
        injectionDetector.check(request.prompt());

        // Layer 2: Match chart_builder skill
        SkillDefinition skill = skillRegistry.matchSkill(request.prompt());
        if (skill == null || !CHART_SKILLS.contains(skill.name())) {
            log.info("[{}] No chart skill matched", correlationId);
            return Mono.error(new NoToolMatchException(fallbackMessage));
        }

        // Detect chart type — if ambiguous, ask user to clarify
        String detectedType = ChartGenerationService.detectChartType(request.prompt());
        boolean isAmbiguous = !ChartGenerationService.isChartTypeExplicit(request.prompt());
        if (isAmbiguous) {
            log.info("[{}] Chart type ambiguous, asking for clarification", correlationId);
            return handleClarificationResponse(request.prompt(), sessionId, exchange);
        }

        List<ToolDefinition> tools = mcpClient.getDiscoveredTools().stream()
                .filter(t -> skill.allowedTools().contains(t.name()))
                .toList();

        String systemPrompt = skill.systemPrompt();
        String userToken = exchange.getRequest().getHeaders().getFirst("X-User-Token");

        // Load session and process request
        return conversationService.loadOrCreateSession(sessionId)
                .flatMap(sessionContext -> {
                    // Phase 1: LLM plans which data tool to call (with context)
                    return chartService.planDataQuery(request.prompt(), tools, systemPrompt, sessionContext.session())
                            .switchIfEmpty(Mono.defer(() -> {
                                log.info("[{}] LLM returned null for chart planning", correlationId);
                                return Mono.error(new NoToolMatchException(fallbackMessage));
                            }))
                            .doOnNext(tc -> validator.validate(tc))
                            // Save turn with tool call info
                            .flatMap(tc -> {
                                ConversationTurn turn = contextInjector.createTurn(
                                        request.prompt(), tc,
                                        new ContextInjector.ResponseMetadata("chart", null, detectedType, null));
                                return conversationService.saveTurn(sessionContext, turn)
                                        .map(ctx -> new TurnAndContext(tc, ctx));
                            })
                            // Phase 2a: Execute tool → collect raw data rows into single string
                            .flatMap(tac -> mcpClient.executeToolCall(tac.toolCall(), correlationId, userToken)
                                    .collectList()
                                    .map(rows -> new RawDataAndContext(String.join("\n", rows), tac.context())))
                            // Phase 2b: LLM generates Vega-Lite spec from the data
                            .flatMap(dac -> {
                                log.info("[{}] Detected chart type: {}", correlationId, detectedType);
                                return chartService.generateChartSpec(
                                        request.prompt(), dac.rawData(), detectedType, systemPrompt)
                                        .map(spec -> new ChartResponseAndContext(spec, dac.context()));
                            })
                            // Save response metadata to turn
                            .flatMap(crc -> {
                                if (crc.chartResponse() instanceof ChartResponse cr) {
                                    ConversationTurn updatedTurn = contextInjector.createTurn(
                                            request.prompt(), null,
                                            new ContextInjector.ResponseMetadata(
                                                    "chart", cr.title(), cr.chartType(), null));
                                    return conversationService.saveTurn(crc.context(), updatedTurn)
                                            .map(finalCtx -> new FinalResponse(crc.chartResponse(), finalCtx));
                                }
                                return Mono.just(new FinalResponse(crc.chartResponse(), crc.context()));
                            });
                })
                .doOnSuccess(finalResp -> {
                    auditLogger.log(correlationId, "CHART_REQUEST", Map.of(
                            "skill", skill.name(),
                            "chartType", finalResp.response() instanceof ChartResponse c ? c.chartType() : "unknown",
                            "sessionId", finalResp.context().sessionId()
                    ));
                    addSessionHeaders(exchange, finalResp.context().sessionId());
                })
                .map(FinalResponse::response);
    }

    private String extractSessionId(ServerWebExchange exchange) {
        // First try header
        String headerValue = exchange.getRequest().getHeaders().getFirst(SESSION_ID_HEADER);
        if (headerValue != null && !headerValue.isBlank()) {
            return headerValue;
        }
        // Then try cookie
        if (exchange.getRequest().getCookies().containsKey(SESSION_COOKIE_NAME)) {
            return exchange.getRequest().getCookies().getFirst(SESSION_COOKIE_NAME).getValue();
        }
        // Generate new session ID
        return UUID.randomUUID().toString();
    }

    private void addSessionHeaders(ServerWebExchange exchange, String sessionId) {
        exchange.getResponse().getHeaders().set(SESSION_ID_HEADER, sessionId);
        // Note: Cookie would be added via response, but headers are easier for API clients
    }

    private Mono<Object> handleClarificationResponse(String prompt, String sessionId, ServerWebExchange exchange) {
        // For clarification responses, we don't need to save to session yet
        // Just return the clarification and let the client respond with chart type
        exchange.getResponse().getHeaders().set(SESSION_ID_HEADER, sessionId);
        return Mono.just(ChartGenerationService.chartTypeClarification(prompt));
    }

    @GetMapping("/status")
    public Map<String, String> status() {
        return Map.of("status", "ok", "name", "report-gateway");
    }

    @GetMapping("/tools")
    public Map<String, Object> listTools() {
        List<ToolDefinition> tools = mcpClient.getDiscoveredTools();
        return Map.of(
                "tools", tools.stream().map(t -> Map.of(
                        "name", t.name(),
                        "description", t.description()
                )).toList(),
                "count", tools.size()
        );
    }

    @GetMapping("/skills")
    public Map<String, Object> listSkills() {
        List<SkillDefinition> skills = skillRegistry.getAllSkills();
        return Map.of(
                "skills", skills.stream().map(s -> Map.of(
                        "name", s.name(),
                        "description", s.description(),
                        "triggers", s.triggers(),
                        "mcp_server", s.mcpServer(),
                        "allowed_tools", s.allowedTools()
                )).toList(),
                "count", skills.size()
        );
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public Mono<ResponseEntity<Map<String, String>>> handleValidation(org.springframework.web.bind.MethodArgumentNotValidException ex) {
        return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "VALIDATION_FAILED", "message", ex.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<Map<String, String>>> handleBadToolCall(IllegalArgumentException ex) {
        return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "INVALID_TOOL_CALL", "message", ex.getMessage())));
    }

    @ExceptionHandler(PromptInjectionDetector.PromptInjectionException.class)
    public Mono<ResponseEntity<Map<String, String>>> handlePromptInjection(PromptInjectionDetector.PromptInjectionException ex) {
        return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "PROMPT_INJECTION", "message", ex.getMessage())));
    }

    @ExceptionHandler(NoToolMatchException.class)
    public Mono<ResponseEntity<Map<String, String>>> handleNoToolMatch(NoToolMatchException ex) {
        return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "NO_TOOL_MATCH", "message", ex.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public Mono<ResponseEntity<Map<String, String>>> handleLlmError(IllegalStateException ex) {
        return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "LLM_ERROR", "message", "Could not understand your request. Try rephrasing with a report type and date range.")));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<Map<String, String>>> handleGeneral(Exception ex) {
        log.error("Unexpected error", ex);
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "INTERNAL_ERROR", "message", "An unexpected error occurred")));
    }

    // Helper records for threading context through reactive chain
    private record SavedToolCall(ToolCall toolCall, SessionContext context) {}
    private record TurnAndContext(ToolCall toolCall, SessionContext context) {}
    private record RawDataAndContext(String rawData, SessionContext context) {}
    private record ChartResponseAndContext(Object chartResponse, SessionContext context) {}
    private record FinalResponse(Object response, SessionContext context) {}
}
