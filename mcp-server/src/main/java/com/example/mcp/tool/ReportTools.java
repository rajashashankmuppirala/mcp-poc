package com.example.mcp.tool;

import com.example.mcp.service.ReportStreamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool component — exposed via Spring AI MCP Server annotation scanner.
 */
@Component
public class ReportTools {

    private static final Logger log = LoggerFactory.getLogger(ReportTools.class);

    private final ReportStreamService reportStreamService;

    public ReportTools(ReportStreamService reportStreamService) {
        this.reportStreamService = reportStreamService;
    }

    @McpTool(description = "Generate a structured report from domain data. Returns rows as newline-delimited text.")
    public List<String> generate_report(
            @McpToolParam(description = "Type of report (e.g., revenue, sales, inventory)", required = true) String reportType,
            @McpToolParam(description = "Start date in YYYY-MM-DD format", required = false) String startDate,
            @McpToolParam(description = "End date in YYYY-MM-DD format", required = false) String endDate,
            @McpToolParam(description = "Region filter (e.g., us-east, eu-west)", required = false) String region,
            @McpToolParam(description = "User OAuth token for domain API auth", required = false) String _userToken
    ) {
        String correlationId = java.util.UUID.randomUUID().toString();
        log.info("[{}] MCP tool invoked: generate_report(reportType={}, startDate={}, endDate={}, region={}, token={})",
                correlationId, reportType, startDate, endDate, region,
                _userToken != null ? "present" : "none");

        String sanitizedType = sanitize(reportType, "revenue");

        Map<String, Object> domainRequest = new LinkedHashMap<>();
        domainRequest.put("reportType", sanitizedType);
        if (startDate != null || endDate != null) {
            Map<String, Object> dateRange = new LinkedHashMap<>();
            if (startDate != null) dateRange.put("start", startDate);
            if (endDate != null) dateRange.put("end", endDate);
            domainRequest.put("dateRange", dateRange);
        }
        if (region != null) {
            domainRequest.put("filters", Map.of("region", sanitize(region, null)));
        }

        return reportStreamService.streamReport(domainRequest, correlationId, _userToken)
                .collectList().block();
    }

    private String sanitize(String input, String defaultVal) {
        if (input == null || input.isBlank()) return defaultVal;
        return input.replaceAll("[^a-zA-Z0-9_-]", "");
    }
}
