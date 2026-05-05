package com.example.gateway.controller;

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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AiController.class)
class AiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlmProvider llmProvider;

    @MockitoBean
    private ToolCallValidator validator;

    @MockitoBean
    private McpClientService mcpClient;

    @Test
    void handleAiRequest_shouldRejectEmptyPrompt() throws Exception {
        mockMvc.perform(post("/ai/request")
                        .contentType("application/json")
                        .content("{\"prompt\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void handleAiRequest_shouldRejectLongPrompt() throws Exception {
        String longPrompt = "x".repeat(501);
        mockMvc.perform(post("/ai/request")
                        .contentType("application/json")
                        .content("{\"prompt\": \"" + longPrompt + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void handleAiRequest_shouldCallLLMThenMCP() throws Exception {
        ToolCall toolCall = new ToolCall("generate_report",
                new ObjectMapper().createObjectNode().put("region", "us-east"));
        when(llmProvider.generateToolCall(anyString(), any())).thenReturn(toolCall);
        when(llmProvider.providerName()).thenReturn("mock");
        StreamingResponseBody mockStream = os -> os.write("report data\n".getBytes());
        when(mcpClient.executeToolCall(any(), anyString())).thenReturn(mockStream);

        mockMvc.perform(post("/ai/request")
                        .contentType("application/json")
                        .content("{\"prompt\": \"Show me sales for us-east\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-ID"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("report data")));
    }
}
