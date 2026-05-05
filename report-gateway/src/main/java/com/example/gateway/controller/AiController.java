package com.example.gateway.controller;

import com.example.gateway.model.AiRequest;
import com.example.gateway.model.ToolCall;
import com.example.gateway.model.ToolDefinition;
import com.example.gateway.service.LlmProvider;
import com.example.gateway.service.McpClientService;
import com.example.gateway.service.ToolCallValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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
    private final List<ToolDefinition> toolDefinitions;

    public AiController(LlmProvider llmProvider, ToolCallValidator validator, McpClientService mcpClient) {
        this.llmProvider = llmProvider;
        this.validator = validator;
        this.mcpClient = mcpClient;
        this.toolDefinitions = buildToolDefinitions();
    }

    @PostMapping(value = "/request", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<StreamingResponseBody> handleAiRequest(@Valid @RequestBody AiRequest request) {
        return handleAiRequestInternal(request, false);
    }

    @PostMapping(value = "/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> handleDownloadRequest(@Valid @RequestBody AiRequest request) {
        return handleAiRequestInternal(request, true);
    }

    private ResponseEntity<StreamingResponseBody> handleAiRequestInternal(@Valid @RequestBody AiRequest request, boolean asDownload) {
        String correlationId = UUID.randomUUID().toString();
        log.info("[{}] Received AI request: {}", correlationId, request.prompt());

        // Step 1: Call LLM provider to convert prompt → tool call
        ToolCall toolCall = llmProvider.generateToolCall(request.prompt(), toolDefinitions);
        log.info("[{}] LLM ({}) returned tool: {}", correlationId, llmProvider.providerName(), toolCall.tool());

        // Step 2: Validate tool call JSON
        validator.validate(toolCall);
        log.info("[{}] Tool call validated", correlationId);

        // Step 3: Route to MCP server and stream response
        StreamingResponseBody stream = mcpClient.executeToolCall(toolCall, correlationId);

        var builder = ResponseEntity.ok().header("X-Correlation-ID", correlationId);
        if (asDownload) {
            String filename = toolCall.tool() + "-" + System.currentTimeMillis() + ".csv";
            builder.header("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        }
        return builder.body(stream);
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(org.springframework.web.bind.MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "VALIDATION_FAILED", "message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadToolCall(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID_TOOL_CALL", "message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleLlmError(IllegalStateException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "LLM_ERROR", "message", "Could not understand your request. Try rephrasing with a report type and date range."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneral(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "INTERNAL_ERROR", "message", "An unexpected error occurred"));
    }

    private List<ToolDefinition> buildToolDefinitions() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode params = mapper.createObjectNode();
        params.put("type", "object");
        ObjectNode properties = mapper.createObjectNode();

        ObjectNode reportType = mapper.createObjectNode();
        reportType.put("type", "string");
        reportType.put("description", "Type of report (e.g., revenue, sales, inventory)");
        properties.set("reportType", reportType);

        ObjectNode startDate = mapper.createObjectNode();
        startDate.put("type", "string");
        startDate.put("description", "Start date in YYYY-MM-DD format");
        properties.set("startDate", startDate);

        ObjectNode endDate = mapper.createObjectNode();
        endDate.put("type", "string");
        endDate.put("description", "End date in YYYY-MM-DD format");
        properties.set("endDate", endDate);

        ObjectNode region = mapper.createObjectNode();
        region.put("type", "string");
        region.put("description", "Region filter (e.g., us-east, eu-west)");
        properties.set("region", region);

        params.set("properties", properties);
        params.set("required", mapper.createArrayNode());

        return List.of(new ToolDefinition(
                "generate_report",
                "Generate a structured report from domain data",
                params
        ));
    }
}
