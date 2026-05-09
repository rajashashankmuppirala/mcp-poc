package com.example.gateway.model;

import java.util.List;
import java.util.Map;

/**
 * Mirror of the Domain API ReportSchema for deserialization during RAG auto-ingestion.
 */
public record ReportSchema(
        String name,
        String description,
        List<String> requiredParams,
        Map<String, String> paramDescriptions,
        List<String> regions,
        List<String> exampleQueries
) {}
