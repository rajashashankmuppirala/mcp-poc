package com.example.domain.model;

import java.util.List;
import java.util.Map;

public record ReportSchema(
        String name,
        String description,
        List<String> requiredParams,
        Map<String, String> paramDescriptions,
        List<String> regions,
        List<String> exampleQueries
) {}
