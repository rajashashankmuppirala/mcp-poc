package com.example.gateway.service;

import com.example.gateway.model.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallValidatorTest {

    private final ToolCallValidator validator = new ToolCallValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldAcceptValidGenerateReportCall() {
        ObjectNode params = mapper.createObjectNode();
        params.put("reportType", "revenue");
        params.put("region", "us-east");
        params.put("startDate", "2026-01-01");
        params.put("endDate", "2026-03-31");

        ToolCall toolCall = new ToolCall("generate_report", params);

        assertDoesNotThrow(() -> validator.validate(toolCall));
    }

    @Test
    void shouldRejectUnknownTool() {
        ObjectNode params = mapper.createObjectNode();
        ToolCall toolCall = new ToolCall("unknown_tool", params);

        Exception ex = assertThrows(IllegalArgumentException.class, () -> validator.validate(toolCall));
        assertTrue(ex.getMessage().contains("Unknown tool"));
    }

    @Test
    void shouldRejectEmptyToolName() {
        ObjectNode params = mapper.createObjectNode();
        ToolCall toolCall = new ToolCall("", params);

        Exception ex = assertThrows(IllegalArgumentException.class, () -> validator.validate(toolCall));
        assertTrue(ex.getMessage().contains("empty"));
    }

    @Test
    void shouldRejectNullParameters() {
        ToolCall toolCall = new ToolCall("generate_report", null);

        Exception ex = assertThrows(IllegalArgumentException.class, () -> validator.validate(toolCall));
        assertTrue(ex.getMessage().contains("null"));
    }

    @Test
    void shouldRejectInvalidDatePattern() {
        ObjectNode params = mapper.createObjectNode();
        params.put("reportType", "revenue");
        params.put("startDate", "01/01/2026");

        ToolCall toolCall = new ToolCall("generate_report", params);

        Exception ex = assertThrows(IllegalArgumentException.class, () -> validator.validate(toolCall));
        assertTrue(ex.getMessage().contains("Invalid tool parameters"));
    }

    @Test
    void shouldRejectInvalidRegionPattern() {
        ObjectNode params = mapper.createObjectNode();
        params.put("reportType", "revenue");
        params.put("region", "INVALID_REGION");

        ToolCall toolCall = new ToolCall("generate_report", params);

        Exception ex = assertThrows(IllegalArgumentException.class, () -> validator.validate(toolCall));
        assertTrue(ex.getMessage().contains("Invalid tool parameters"));
    }

    @Test
    void shouldRejectInvalidReportTypePattern() {
        ObjectNode params = mapper.createObjectNode();
        params.put("reportType", "report with spaces!");

        ToolCall toolCall = new ToolCall("generate_report", params);

        Exception ex = assertThrows(IllegalArgumentException.class, () -> validator.validate(toolCall));
        assertTrue(ex.getMessage().contains("Invalid tool parameters"));
    }
}
