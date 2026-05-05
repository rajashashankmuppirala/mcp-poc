package com.example.mcp.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GenerateReportRequest(
        @NotBlank(message = "reportType is required")
        String reportType,
        DateRange dateRange,
        java.util.Map<String, String> filters,
        java.util.List<String> groupBy,
        Integer limit
) {
    public record DateRange(
            @NotBlank(message = "start date is required") String start,
            @NotBlank(message = "end date is required") String end
    ) {}

    public int effectiveLimit() {
        if (limit == null || limit < 1) return 1000;
        return Math.min(limit, 10000);
    }
}
