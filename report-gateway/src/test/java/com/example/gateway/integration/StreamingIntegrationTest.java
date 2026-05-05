package com.example.gateway.integration;

import com.example.gateway.controller.AiController;
import com.example.gateway.model.ToolCall;
import com.example.gateway.service.LlmProvider;
import com.example.gateway.service.McpClientService;
import com.example.gateway.service.ToolCallValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AiController.class)
class StreamingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlmProvider llmProvider;

    @MockitoBean
    private ToolCallValidator validator;

    @MockitoBean
    private McpClientService mcpClient;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldReturn200ForValidRequest() throws Exception {
        // Arrange: Mock LLM response
        ToolCall mockToolCall = new ToolCall("generate_report",
                mapper.createObjectNode()
                        .put("reportType", "revenue")
                        .put("region", "us-east"));
        when(llmProvider.generateToolCall(anyString(), any())).thenReturn(mockToolCall);
        when(llmProvider.providerName()).thenReturn("mock");

        // Mock streaming response from MCP
        StreamingResponseBody mockStream = os -> {
            os.write("{\"type\":\"data\",\"data\":{\"row\":1}}\n".getBytes());
            os.write("{\"type\":\"data\",\"data\":{\"row\":2}}\n".getBytes());
            os.write("{\"type\":\"footer\",\"data\":{\"totalRows\":2}}\n".getBytes());
        };
        when(mcpClient.executeToolCall(any(), anyString())).thenReturn(mockStream);

        // Act & Assert
        MvcResult result = mockMvc.perform(post("/ai/request")
                        .contentType("application/json")
                        .content("{\"prompt\": \"Show me revenue\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-ID"))
                .andReturn();

        // Verify streaming behavior: all chunks arrive in response
        String response = result.getResponse().getContentAsString();
        assertTrue(response.contains("row"), "Should contain report data");
        assertTrue(response.contains("footer"), "Should contain completion signal");
    }

    @Test
    void shouldValidateEmptyPrompt() throws Exception {
        mockMvc.perform(post("/ai/request")
                        .contentType("application/json")
                        .content("{\"prompt\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void shouldRejectOversizedPrompt() throws Exception {
        String longPrompt = "x".repeat(501);
        mockMvc.perform(post("/ai/request")
                        .contentType("application/json")
                        .content("{\"prompt\": \"" + longPrompt + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void shouldRejectInvalidToolCall() throws Exception {
        // Arrange: LLM returns an invalid tool call
        ToolCall invalidToolCall = new ToolCall("unknown_tool", mapper.createObjectNode());
        when(llmProvider.generateToolCall(anyString(), any())).thenReturn(invalidToolCall);
        when(llmProvider.providerName()).thenReturn("mock");
        doThrow(new IllegalArgumentException("Unknown tool: unknown_tool")).when(validator).validate(any());

        mockMvc.perform(post("/ai/request")
                        .contentType("application/json")
                        .content("{\"prompt\": \"Show me data\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TOOL_CALL"));
    }

    @Test
    void shouldHandleLlmError() throws Exception {
        // Arrange: LLM throws an error
        when(llmProvider.generateToolCall(anyString(), any()))
                .thenThrow(new IllegalStateException("LLM returned natural language"));

        mockMvc.perform(post("/ai/request")
                        .contentType("application/json")
                        .content("{\"prompt\": \"Show me data\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("LLM_ERROR"));
    }

    @Test
    void shouldVerifyToolCallProperties() throws Exception {
        // Arrange
        ToolCall mockToolCall = new ToolCall("generate_report",
                mapper.createObjectNode()
                        .put("reportType", "revenue")
                        .put("region", "us-east")
                        .put("startDate", "2026-01-01")
                        .put("endDate", "2026-03-31"));
        when(llmProvider.generateToolCall(anyString(), any())).thenReturn(mockToolCall);
        when(llmProvider.providerName()).thenReturn("mock");
        StreamingResponseBody mockStream = os -> os.write("test\n".getBytes());
        when(mcpClient.executeToolCall(any(), anyString())).thenReturn(mockStream);

        // Act
        mockMvc.perform(post("/ai/request")
                        .contentType("application/json")
                        .content("{\"prompt\": \"Show me revenue for us-east in Q1\"}"))
                .andExpect(status().isOk());

        // Verify the tool call passed to MCP has correct properties
        verify(mcpClient).executeToolCall(
                argThat(tc ->
                        tc != null &&
                        tc.tool().equals("generate_report") &&
                        tc.parameters().get("reportType").asText().equals("revenue") &&
                        tc.parameters().get("region").asText().equals("us-east")),
                anyString());
    }
}
