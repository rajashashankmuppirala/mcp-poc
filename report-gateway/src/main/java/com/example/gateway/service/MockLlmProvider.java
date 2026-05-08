package com.example.gateway.service;

import com.example.gateway.model.ToolCall;
import com.example.gateway.model.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(name = "llm.provider", havingValue = "mock")
public class MockLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(MockLlmProvider.class);
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Keywords that indicate the user is asking for a report.
     * If none match, return null so the gateway returns a fallback message.
     */
    private static final List<String> REPORT_KEYWORDS = List.of(
            "report", "revenue", "sales", "generate", "show me", "data",
            "summary", "analytics", "dashboard", "export"
    );

    /**
     * Operations tools
     */
    private static final List<String> FAILED_JOB_KEYWORDS = List.of(
            "failed job", "failure job", "job failed", "job failure"
    );
    private static final List<String> DATAFLOW_KEYWORDS = List.of(
            "dataflow", "pipeline status", "pipeline success", "data flow"
    );

    @Override
    public Mono<ToolCall> generateToolCall(String userMessage, List<ToolDefinition> tools, String systemPrompt) {
        log.info("Mock LLM processing prompt: {}{}", userMessage,
                systemPrompt != null ? " (skill prompt: " + systemPrompt.substring(0, Math.min(50, systemPrompt.length())) + "...)" : "");

        String lower = userMessage.toLowerCase();

        List<String> toolNames = tools.stream().map(ToolDefinition::name).toList();

        boolean isFailedJob = FAILED_JOB_KEYWORDS.stream().anyMatch(lower::contains)
                || (lower.contains("failed") && lower.contains("job"));
        boolean isDataflow = DATAFLOW_KEYWORDS.stream().anyMatch(lower::contains)
                || lower.contains("dataflow");

        if (isFailedJob && toolNames.contains("list_failed_jobs")) {
            int hours = parseHours(lower);
            var params = mapper.createObjectNode();
            if (hours > 0) params.put("hours", hours);
            return Mono.just(new ToolCall("list_failed_jobs", params));
        }
        if (isDataflow && toolNames.contains("list_successful_dataflows")) {
            int hours = parseHours(lower);
            var params = mapper.createObjectNode();
            if (hours > 0) params.put("hours", hours);
            return Mono.just(new ToolCall("list_successful_dataflows", params));
        }
        if ((isFailedJob || isDataflow) && toolNames.contains("list_failed_jobs")) {
            int hours = parseHours(lower);
            var params = mapper.createObjectNode();
            if (hours > 0) params.put("hours", hours);
            return Mono.just(new ToolCall("list_failed_jobs", params));
        }

        boolean isReportRequest = REPORT_KEYWORDS.stream().anyMatch(lower::contains);
        if (!isReportRequest) {
            log.info("Mock LLM: prompt does not match any available tool, returning null");
            return Mono.empty();
        }

        String region = lower.contains("us-east") ? "us-east"
                : lower.contains("eu-west") ? "eu-west"
                : null;

        try {
            var params = mapper.createObjectNode();
            params.put("reportType", "revenue");
            if (region != null) params.put("region", region);

            // Parse relative date phrases from the prompt
            String[] dates = parseDates(lower);
            if (dates != null) {
                params.put("startDate", dates[0]);
                params.put("endDate", dates[1]);
            }

            return Mono.just(new ToolCall("generate_report", params));
        } catch (Exception e) {
            return Mono.error(new IllegalStateException("Failed to build mock tool call", e));
        }
    }

    /**
     * Parse relative date phrases from the user prompt.
     * Returns [startDate, endDate] or null if no date phrase matched.
     */
    private String[] parseDates(String lower) {
        int currentYear = LocalDate.now().getYear();
        int currentMonth = LocalDate.now().getMonthValue();
        int currentQuarter = (currentMonth - 1) / 3 + 1;

        // Explicit ISO dates: "2025-01-01 to 2025-12-31"
        Matcher explicitMatcher = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\s*(?:to|until|through|\\u2013|\\u2014|-)\\s*(\\d{4}-\\d{2}-\\d{2})").matcher(lower);
        if (explicitMatcher.find()) {
            return new String[]{explicitMatcher.group(1), explicitMatcher.group(2)};
        }

        // "last year" / "previous year"
        if (lower.contains("last year") || lower.contains("previous year")) {
            int y = currentYear - 1;
            return new String[]{y + "-01-01", y + "-12-31"};
        }
        // "this year" / "current year"
        if (lower.contains("this year") || lower.contains("current year")) {
            return new String[]{currentYear + "-01-01", currentYear + "-12-31"};
        }
        // "last quarter" / "previous quarter"
        if (lower.contains("last quarter") || lower.contains("previous quarter")) {
            int lq = currentQuarter == 1 ? 4 : currentQuarter - 1;
            int lqy = currentQuarter == 1 ? currentYear - 1 : currentYear;
            int startMonth = (lq - 1) * 3 + 1;
            int endMonth = lq * 3;
            int endDay = LocalDate.of(lqy, endMonth, 1).lengthOfMonth();
            return new String[]{
                    String.format("%d-%02d-01", lqy, startMonth),
                    String.format("%d-%02d-%02d", lqy, endMonth, endDay)
            };
        }
        // "this quarter" / "current quarter" / "Q1" / "Q2" etc.
        Matcher qMatcher = Pattern.compile("(?:this|current)?\\s*(q(\\d))").matcher(lower);
        if (qMatcher.find()) {
            int q = Integer.parseInt(qMatcher.group(2));
            int startMonth = (q - 1) * 3 + 1;
            int endMonth = q * 3;
            int endDay = LocalDate.of(currentYear, endMonth, 1).lengthOfMonth();
            return new String[]{
                    String.format("%d-%02d-01", currentYear, startMonth),
                    String.format("%d-%02d-%02d", currentYear, endMonth, endDay)
            };
        }
        // "last month" / "previous month"
        if (lower.contains("last month") || lower.contains("previous month")) {
            LocalDate lastMonth = LocalDate.now().minusMonths(1);
            int endDay = lastMonth.lengthOfMonth();
            return new String[]{
                    String.format("%d-%02d-01", lastMonth.getYear(), lastMonth.getMonthValue()),
                    String.format("%d-%02d-%02d", lastMonth.getYear(), lastMonth.getMonthValue(), endDay)
            };
        }
        // "this month" / "current month"
        if (lower.contains("this month") || lower.contains("current month")) {
            LocalDate now = LocalDate.now();
            return new String[]{
                    String.format("%d-%02d-01", now.getYear(), now.getMonthValue()),
                    String.format("%d-%02d-%02d", now.getYear(), now.getMonthValue(), now.lengthOfMonth())
            };
        }
        // "past N days" / "last N days"
        Matcher daysMatcher = Pattern.compile("(?:past|last)\\s+(\\d+)\\s+days?").matcher(lower);
        if (daysMatcher.find()) {
            int days = Integer.parseInt(daysMatcher.group(1));
            LocalDate end = LocalDate.now();
            LocalDate start = end.minusDays(days);
            return new String[]{start.toString(), end.toString()};
        }

        return null;
    }

    private int parseHours(String lower) {
        // Extract hours using word boundary matching to avoid "24" matching "4"
        Matcher m = Pattern.compile("(\\d+)\\s*(?:hours?|hrs?)").matcher(lower);
        if (m.find()) {
            int val = Integer.parseInt(m.group(1));
            if (val >= 1 && val <= 168) return val;
        }
        // Extract days
        m = Pattern.compile("(\\d+)\\s*(?:days?)").matcher(lower);
        if (m.find()) {
            int val = Integer.parseInt(m.group(1));
            if (val >= 1 && val <= 30) return val * 24;
        }
        return 24; // default
    }

    @Override
    public Mono<String> generateText(String userMessage, String systemPrompt) {
        log.info("Mock LLM generateText: {}{}", userMessage.substring(0, Math.min(50, userMessage.length())),
                systemPrompt != null ? " (skill prompt)" : "");
        return Mono.just(generateVegaLiteSpec(userMessage));
    }

    /**
     * Generate a simple Vega-Lite spec based on the prompt keywords.
     * This is a mock — a real LLM would produce the full spec from context.
     */
    private String generateVegaLiteSpec(String prompt) {
        String lower = prompt.toLowerCase();

        // Determine chart type from prompt
        String chartType = "bar";
        if (lower.contains("line") || lower.contains("trend") || lower.contains("over time")) chartType = "line";
        else if (lower.contains("pie") || lower.contains("proportion") || lower.contains("distribution")) chartType = "pie";

        // Determine fields from context
        boolean isFailedJobs = lower.contains("failed") && lower.contains("job");
        boolean isDataflow = lower.contains("dataflow") || lower.contains("pipeline");
        boolean isRevenue = lower.contains("revenue") || lower.contains("sales") || lower.contains("report");

        if (isFailedJobs) {
            return failedJobsSpec(chartType);
        } else if (isDataflow) {
            return dataflowSpec(chartType);
        } else if (isRevenue) {
            return revenueSpec(chartType);
        }

        // Generic bar chart fallback
        return genericBarSpec(chartType);
    }

    private String failedJobsSpec(String chartType) {
        if ("line".equals(chartType)) {
            return """
                    {"$schema":"https://vega.github.io/schema/vega-lite/v5.json","mark":"line","data":{"values":[{"hour":"00:00","count":3},{"hour":"04:00","count":7},{"hour":"08:00","count":12},{"hour":"12:00","count":5},{"hour":"16:00","count":9},{"hour":"20:00","count":4}]},"encoding":{"x":{"field":"hour","type":"temporal","title":"Hour"},"y":{"field":"count","type":"quantitative","title":"Failed Jobs"},"color":{"value":"#e74c3c"}},"title":"Failed Jobs Over Time"}
                    """;
        }
        return """
                {"$schema":"https://vega.github.io/schema/vega-lite/v5.json","mark":"bar","data":{"values":[{"job":"etl-extract","count":12},{"job":"data-transform","count":8},{"job":"report-gen","count":5},{"job":"sync-inventory","count":3}]},"encoding":{"x":{"field":"job","type":"nominal","title":"Job"},"y":{"field":"count","type":"quantitative","title":"Failures"},"color":{"value":"#e74c3c"}},"title":"Failed Jobs by Type"}
                """;
    }

    private String dataflowSpec(String chartType) {
        if ("line".equals(chartType)) {
            return """
                    {"$schema":"https://vega.github.io/schema/vega-lite/v5.json","mark":"line","data":{"values":[{"hour":"00:00","count":15},{"hour":"04:00","count":22},{"hour":"08:00","count":45},{"hour":"12:00","count":38},{"hour":"16:00","count":30},{"hour":"20:00","count":18}]},"encoding":{"x":{"field":"hour","type":"temporal","title":"Hour"},"y":{"field":"count","type":"quantitative","title":"Successful Dataflows"},"color":{"value":"#27ae60"}},"title":"Dataflow Success Over Time"}
                    """;
        }
        return """
                {"$schema":"https://vega.github.io/schema/vega-lite/v5.json","mark":"bar","data":{"values":[{"dataflow":"ingest-pipeline","count":45},{"dataflow":"transform-pipeline","count":38},{"dataflow":"export-pipeline","count":22},{"dataflow":"sync-pipeline","count":18}]},"encoding":{"x":{"field":"dataflow","type":"nominal","title":"Dataflow"},"y":{"field":"count","type":"quantitative","title":"Successful Runs"},"color":{"value":"#27ae60"}},"title":"Successful Dataflows by Pipeline"}
                """;
    }

    private String revenueSpec(String chartType) {
        if ("pie".equals(chartType)) {
            return """
                    {"$schema":"https://vega.github.io/schema/vega-lite/v5.json","mark":{"type":"arc","innerRadius":50},"data":{"values":[{"region":"US East","revenue":45000},{"region":"US West","revenue":38000},{"region":"EU West","revenue":32000},{"region":"APAC","revenue":21000}]},"encoding":{"theta":{"field":"revenue","type":"quantitative"},"color":{"field":"region","type":"nominal","title":"Region"}},"title":"Revenue by Region"}
                    """;
        }
        return """
                {"$schema":"https://vega.github.io/schema/vega-lite/v5.json","mark":"bar","data":{"values":[{"region":"US East","revenue":45000},{"region":"US West","revenue":38000},{"region":"EU West","revenue":32000},{"region":"APAC","revenue":21000}]},"encoding":{"x":{"field":"region","type":"nominal","title":"Region"},"y":{"field":"revenue","type":"quantitative","title":"Revenue ($)"},"color":{"field":"region","type":"nominal"}},"title":"Revenue by Region"}
                """;
    }

    private String genericBarSpec(String chartType) {
        return """
                {"$schema":"https://vega.github.io/schema/vega-lite/v5.json","mark":"bar","data":{"values":[{"category":"A","value":28},{"category":"B","value":55},{"category":"C","value":43},{"category":"D","value":91},{"category":"E","value":81}]},"encoding":{"x":{"field":"category","type":"nominal"},"y":{"field":"value","type":"quantitative"},"color":{"field":"category","type":"nominal"}},"title":"Data Overview"}
                """;
    }

    @Override
    public String providerName() {
        return "mock";
    }
}
