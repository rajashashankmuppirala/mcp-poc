package com.example.mcp.controller;

import com.example.mcp.model.GenerateReportRequest;
import com.example.mcp.service.ReportStreamService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/tools")
public class ToolController {

    private static final Logger log = LoggerFactory.getLogger(ToolController.class);

    private final ReportStreamService reportStreamService;

    public ToolController(ReportStreamService reportStreamService) {
        this.reportStreamService = reportStreamService;
    }

    @PostMapping(value = "/generate-report", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<StreamingResponseBody> generateReport(@Valid @RequestBody GenerateReportRequest request) {
        String correlationId = UUID.randomUUID().toString();
        log.info("[{}] Received generate-report request: reportType={}", correlationId, request.reportType());

        String sanitizedType = request.reportType().replaceAll("[^a-zA-Z0-9_]", "");

        StreamingResponseBody stream = reportStreamService.streamReport(
                new GenerateReportRequest(
                        sanitizedType,
                        request.dateRange(),
                        request.filters(),
                        request.groupBy(),
                        request.limit()
                ),
                correlationId
        );

        return ResponseEntity.ok()
                .header("X-Correlation-ID", correlationId)
                .body(stream);
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(org.springframework.web.bind.MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Invalid request");
        return ResponseEntity.badRequest().body(Map.of("error", "VALIDATION_FAILED", "message", message));
    }
}
