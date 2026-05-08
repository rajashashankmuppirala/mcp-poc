package com.example.gateway.model;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Structured filters extracted from a user prompt and tool call.
 * These are the parameters the LLM identified in the user's request.
 */
public record ExtractedFilters(
        String reportType,
        String region,
        LocalDate startDate,
        LocalDate endDate,
        Map<String, String> additionalFilters
) {
    private static final Set<String> VALID_REPORT_TYPES = Set.of(
            "revenue", "sales", "orders", "inventory",
            "customers", "expenses", "profit", "subscriptions"
    );

    private static final Set<String> VALID_REGIONS = Set.of(
            "us-east", "us-west", "eu-west", "eu-central", "apac", "latam"
    );

    public ExtractedFilters {
        // Validate report type if provided
        if (reportType != null && !VALID_REPORT_TYPES.contains(reportType.toLowerCase())) {
            throw new IllegalArgumentException("Invalid report type: " + reportType);
        }
        // Normalize and validate region if provided
        if (region != null) {
            String normalizedRegion = region.toLowerCase().trim();
            if (!VALID_REGIONS.contains(normalizedRegion)) {
                throw new IllegalArgumentException("Invalid region: " + region);
            }
            region = normalizedRegion;
        }
        // Validate date range
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate cannot be before startDate");
        }
        // Make additional filters immutable
        additionalFilters = additionalFilters != null
                ? Map.copyOf(additionalFilters)
                : Collections.emptyMap();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Merge these filters with context from a previous turn.
     * Explicit values in this filter override context values.
     */
    public ExtractedFilters mergeWithContext(ExtractedFilters context) {
        if (context == null) {
            return this;
        }
        return new ExtractedFilters(
                this.reportType != null ? this.reportType : context.reportType,
                this.region != null ? this.region : context.region,
                this.startDate != null ? this.startDate : context.startDate,
                this.endDate != null ? this.endDate : context.endDate,
                mergeAdditionalFilters(this.additionalFilters, context.additionalFilters)
        );
    }

    private static Map<String, String> mergeAdditionalFilters(
            Map<String, String> current, Map<String, String> context) {
        Map<String, String> merged = new HashMap<>();
        if (context != null) {
            merged.putAll(context);
        }
        if (current != null) {
            merged.putAll(current);
        }
        return Map.copyOf(merged);
    }

    public boolean hasDateRange() {
        return startDate != null && endDate != null;
    }

    public boolean isComplete() {
        return reportType != null && hasDateRange();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (reportType != null) sb.append("reportType=").append(reportType);
        if (region != null) sb.append(", region=").append(region);
        if (startDate != null) sb.append(", startDate=").append(startDate);
        if (endDate != null) sb.append(", endDate=").append(endDate);
        if (!additionalFilters.isEmpty()) {
            sb.append(", additionalFilters=").append(additionalFilters);
        }
        return sb.toString();
    }

    public static class Builder {
        private String reportType;
        private String region;
        private LocalDate startDate;
        private LocalDate endDate;
        private Map<String, String> additionalFilters = new HashMap<>();

        public Builder reportType(String reportType) {
            this.reportType = reportType;
            return this;
        }

        public Builder region(String region) {
            this.region = region;
            return this;
        }

        public Builder startDate(LocalDate startDate) {
            this.startDate = startDate;
            return this;
        }

        public Builder endDate(LocalDate endDate) {
            this.endDate = endDate;
            return this;
        }

        public Builder additionalFilter(String key, String value) {
            this.additionalFilters.put(key, value);
            return this;
        }

        public Builder additionalFilters(Map<String, String> filters) {
            if (filters != null) {
                this.additionalFilters.putAll(filters);
            }
            return this;
        }

        public ExtractedFilters build() {
            return new ExtractedFilters(reportType, region, startDate, endDate, additionalFilters);
        }
    }
}
