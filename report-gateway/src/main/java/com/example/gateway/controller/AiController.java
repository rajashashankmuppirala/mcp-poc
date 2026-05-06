package com.example.gateway.controller;

import com.example.gateway.model.AiRequest;
import com.example.gateway.model.ToolCall;
import com.example.gateway.model.ToolDefinition;
import com.example.gateway.service.LlmProvider;
import com.example.gateway.service.McpClientService;
import com.example.gateway.service.PromptInjectionDetector;
import com.example.gateway.service.ToolCallValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/ai")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    private final LlmProvider llmProvider;
    private final ToolCallValidator validator;
    private final McpClientService mcpClient;
    private final PromptInjectionDetector injectionDetector;

    public AiController(LlmProvider llmProvider, ToolCallValidator validator,
                        McpClientService mcpClient, PromptInjectionDetector injectionDetector) {
        this.llmProvider = llmProvider;
        this.validator = validator;
        this.mcpClient = mcpClient;
        this.injectionDetector = injectionDetector;
    }

    @PostMapping(value = "/request", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> handleAiRequest(@RequestBody AiRequest request, ServerWebExchange exchange) {
        String correlationId = UUID.randomUUID().toString();
        log.info("[{}] Received AI request: {}", correlationId, request.prompt());

        // Layer 1: Prompt injection check
        injectionDetector.check(request.prompt());

        // Resolve tools dynamically from MCP server (discovered at startup)
        List<ToolDefinition> tools = mcpClient.getDiscoveredTools();
        if (tools.isEmpty()) {
            log.warn("[{}] No tools discovered from MCP server", correlationId);
            return Flux.error(new IllegalStateException("No tools available from MCP server"));
        }

        // Extract auth token from request header for downstream propagation
        String userToken = exchange.getRequest().getHeaders().getFirst("X-User-Token");

        boolean isDownload = request.prompt().toLowerCase().contains("download");

        // Step 1: Call LLM provider to convert prompt → tool call
        Mono<ToolCall> toolCallMono = Mono.fromCallable(() -> {
            ToolCall toolCall = llmProvider.generateToolCall(request.prompt(), tools);
            log.info("[{}] LLM ({}) returned tool: {}", correlationId, llmProvider.providerName(), toolCall.tool());
            return toolCall;
        });

        // Step 2: Validate
        toolCallMono = toolCallMono.doOnNext(tc -> {
            validator.validate(tc);
            log.info("[{}] Tool call validated", correlationId);
        });

        // Step 3: Execute via MCP client, parse JSON array, emit rows as SSE
        Flux<String> rowStream = toolCallMono
                .flatMapMany(tc -> mcpClient.executeToolCall(tc, correlationId, userToken))
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
                });

        // For download mode, prepend a metadata line with the filename
        if (isDownload) {
            final String filename = "generate_report-" + System.currentTimeMillis() + ".csv";
            return Flux.concat(Flux.just(filename), rowStream);
        }

        return rowStream;
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

    @ExceptionHandler(IllegalStateException.class)
    public Mono<ResponseEntity<Map<String, String>>> handleLlmError(IllegalStateException ex) {
        return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "LLM_ERROR", "message", "Could not understand your request. Try rephrasing with a report type and date range.")));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<Map<String, String>>> handleGeneral(Exception ex) {
        log.error("Unexpected error", ex);
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "INTERNAL_ERROR", "message", "An unexpected error occurred")));
    }
}
