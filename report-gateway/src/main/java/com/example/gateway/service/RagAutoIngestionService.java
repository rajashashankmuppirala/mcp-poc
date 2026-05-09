package com.example.gateway.service;

import com.example.gateway.model.ReportSchema;
import com.example.gateway.model.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Automatically ingests tool schemas and report metadata into the RAG layer.
 * Uses deterministic document IDs so that re-ingestion overwrites instead of duplicating.
 * Supports graceful degradation when Domain API or MCP servers are unavailable.
 */
@Service
public class RagAutoIngestionService {

    private static final Logger log = LoggerFactory.getLogger(RagAutoIngestionService.class);

    private static final String TOOL_PREFIX = "auto-tool:";
    private static final String REPORT_PREFIX = "auto-report:";

    private final RagService ragService;
    private final WebClient webClient;
    private final String domainApiUrl;

    public RagAutoIngestionService(
            RagService ragService,
            @Value("${domain-api.url:http://localhost:8082}") String domainApiUrl) {
        this.ragService = ragService;
        this.webClient = WebClient.builder().build();
        this.domainApiUrl = domainApiUrl;
    }

    /**
     * Returns the total number of documents currently in the RAG store.
     */
    public int ragDocCount() {
        return ragService.listAll().size();
    }

    /**
     * Ingest all schemas (tools + reports) into RAG.
     * Called from SyncService or ToolDiscoveryInitializer at startup.
     */
    public void ingestAll(List<ToolDefinition> tools) {
        clearAutoToolDocs();
        clearAutoReportDocs();
        ingestToolSchemas(tools);
        ingestReportSchemas();
    }

    /**
     * Re-ingest only tool schemas (keeps existing report docs).
     * Used during per-server refresh.
     */
    public void refreshToolSchemas(List<ToolDefinition> tools) {
        clearAutoToolDocs();
        ingestToolSchemas(tools);
    }

    /**
     * Re-ingest only report schemas (keeps existing tool docs).
     */
    public void refreshReportSchemas() {
        clearAutoReportDocs();
        ingestReportSchemas();
    }

    void ingestToolSchemas(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            log.info("No tools to ingest into RAG");
            return;
        }

        try {
            for (ToolDefinition tool : tools) {
                String id = TOOL_PREFIX + tool.name();
                String title = "Tool: " + tool.name();
                String content = formatToolContent(tool);
                List<String> tags = List.of("tool", "mcp", tool.name().replaceAll("_", "-"));

                ragService.ingestWithId(id, title, content, tags);
                log.debug("Ingested tool schema into RAG: {}", tool.name());
            }
            log.info("Ingested {} tool schemas into RAG", tools.size());
        } catch (Exception e) {
            log.warn("Failed to ingest tool schemas into RAG: {}. Continuing without tool metadata.", e.getMessage());
        }
    }

    void ingestReportSchemas() {
        List<ReportSchema> schemas;
        try {
            schemas = webClient.get()
                    .uri(domainApiUrl + "/api/v1/reports/metadata")
                    .retrieve()
                    .bodyToFlux(ReportSchema.class)
                    .collectList()
                    .block();
        } catch (Exception e) {
            log.warn("Failed to fetch report schemas from Domain API: {}. Continuing without report metadata.", e.getMessage());
            return;
        }

        if (schemas == null || schemas.isEmpty()) {
            log.info("Domain API returned no report metadata");
            return;
        }

        for (ReportSchema schema : schemas) {
            String id = REPORT_PREFIX + schema.name();
            String title = "Report: " + schema.name() + " Report";
            String content = formatReportContent(schema);
            List<String> tags = buildReportTags(schema);

            ragService.ingestWithId(id, title, content, tags);
            log.debug("Ingested report schema into RAG: {}", schema.name());
        }
        log.info("Ingested {} report schemas into RAG", schemas.size());
    }

    private void clearAutoToolDocs() {
        int cleared = 0;
        for (var doc : ragService.listAll()) {
            if (doc.id().startsWith(TOOL_PREFIX)) {
                ragService.delete(doc.id());
                cleared++;
            }
        }
        if (cleared > 0) log.debug("Cleared {} old auto-tool docs from RAG", cleared);
    }

    private void clearAutoReportDocs() {
        int cleared = 0;
        for (var doc : ragService.listAll()) {
            if (doc.id().startsWith(REPORT_PREFIX)) {
                ragService.delete(doc.id());
                cleared++;
            }
        }
        if (cleared > 0) log.debug("Cleared {} old auto-report docs from RAG", cleared);
    }

    private String formatToolContent(ToolDefinition tool) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tool Name: ").append(tool.name()).append("\n");
        sb.append("Description: ").append(tool.description()).append("\n\n");

        if (tool.parameters() != null && tool.parameters().has("properties")) {
            sb.append("Parameters:\n");
            JsonNode properties = tool.parameters().get("properties");
            JsonNode required = tool.parameters().get("required");
            properties.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode prop = entry.getValue();
                boolean isRequired = required != null && required.isArray() &&
                        required.toString().contains("\"" + key + "\"");
                sb.append("  - ").append(key).append(isRequired ? " (required)" : " (optional)");
                if (prop.has("type")) sb.append(": ").append(prop.get("type").asText());
                if (prop.has("description")) sb.append(" — ").append(prop.get("description").asText());
                sb.append("\n");
            });
        }

        sb.append("\nExample usage: Use this tool when the user asks for data, reports, or analysis.\n");
        return sb.toString();
    }

    private String formatReportContent(ReportSchema schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("Report Type: ").append(schema.name()).append("\n");
        sb.append("Description: ").append(schema.description()).append("\n\n");

        if (!schema.requiredParams().isEmpty()) {
            sb.append("Required Parameters:\n");
            for (String param : schema.requiredParams()) {
                sb.append("  - ").append(param);
                String desc = schema.paramDescriptions().get(param);
                if (desc != null) sb.append(": ").append(desc);
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (!schema.regions().isEmpty()) {
            sb.append("Available Regions: ").append(String.join(", ", schema.regions())).append("\n\n");
        }

        if (!schema.exampleQueries().isEmpty()) {
            sb.append("Example Queries:\n");
            for (String query : schema.exampleQueries()) {
                sb.append("  - \"").append(query).append("\"\n");
            }
        }

        return sb.toString();
    }

    private List<String> buildReportTags(ReportSchema schema) {
        return schema.regions().isEmpty()
                ? List.of("report", schema.name(), "data")
                : List.of("report", schema.name(), "data", "regional");
    }
}
