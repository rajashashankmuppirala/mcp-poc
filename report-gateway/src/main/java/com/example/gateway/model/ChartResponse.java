package com.example.gateway.model;

/**
 * Response from the two-phase chart generation pipeline.
 */
public record ChartResponse(
        String chartType,
        String title,
        String vegaLiteSpec,
        String dataSummary
) {}
