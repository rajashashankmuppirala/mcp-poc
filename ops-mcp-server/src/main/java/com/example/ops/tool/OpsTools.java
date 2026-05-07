package com.example.ops.tool;

import com.example.ops.service.OpsDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP tools for operations monitoring — failed jobs and dataflow status.
 */
@Component
public class OpsTools {

    private static final Logger log = LoggerFactory.getLogger(OpsTools.class);
    private final OpsDataService opsDataService;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpsTools(OpsDataService opsDataService) {
        this.opsDataService = opsDataService;
    }

    @McpTool(description = "List failed jobs within a time window. Returns tabular data with job name, error, timestamp, and duration.")
    public String list_failed_jobs(
            @McpToolParam(description = "Number of hours to look back (mutually exclusive with days)", required = false) Integer hours,
            @McpToolParam(description = "Number of days to look back (mutually exclusive with hours)", required = false) Integer days
    ) {
        String correlationId = java.util.UUID.randomUUID().toString();
        int h = hours != null ? hours : (days != null ? days * 24 : 24);
        int d = days != null ? days : (hours != null ? hours / 24 : 1);

        log.info("[{}] MCP tool invoked: list_failed_jobs(hours={}, days={})", correlationId, hours, days);

        List<Map<String, Object>> jobs = opsDataService.getFailedJobs(h, d);
        log.info("[{}] Returning {} failed job records", correlationId, jobs.size());
        return formatTabular(jobs);
    }

    @McpTool(description = "List successfully completed dataflows within a time window. Returns tabular data with flow name, records processed, duration, and completion timestamp.")
    public String list_successful_dataflows(
            @McpToolParam(description = "Number of hours to look back (mutually exclusive with days)", required = false) Integer hours,
            @McpToolParam(description = "Number of days to look back (mutually exclusive with hours)", required = false) Integer days
    ) {
        String correlationId = java.util.UUID.randomUUID().toString();
        int h = hours != null ? hours : (days != null ? days * 24 : 24);
        int d = days != null ? days : (hours != null ? hours / 24 : 1);

        log.info("[{}] MCP tool invoked: list_successful_dataflows(hours={}, days={})", correlationId, hours, days);

        List<Map<String, Object>> flows = opsDataService.getSuccessfulDataflows(h, d);
        log.info("[{}] Returning {} successful dataflow records", correlationId, flows.size());
        return formatTabular(flows);
    }

    /**
     * Format a list of maps as NDJSON lines for streaming compatibility.
     */
    private String formatTabular(List<Map<String, Object>> records) {
        try {
            return mapper.writeValueAsString(records);
        } catch (Exception e) {
            log.error("Failed to serialize records", e);
            return "[]";
        }
    }
}
