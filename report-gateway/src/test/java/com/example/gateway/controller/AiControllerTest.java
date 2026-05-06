package com.example.gateway.controller;

import com.example.gateway.model.ToolCall;
import com.example.gateway.service.LlmProvider;
import com.example.gateway.service.McpClientService;
import com.example.gateway.service.ToolCallValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiControllerTest {

    private final LlmProvider llmProvider = mock(LlmProvider.class);
    private final ToolCallValidator validator = mock(ToolCallValidator.class);
    private final McpClientService mcpClient = mock(McpClientService.class);
    private final WebTestClient webTestClient = WebTestClient
            .bindToController(new AiController(llmProvider, validator, mcpClient))
            .build();

    @Test
    void handleAiRequest_shouldCallLLMThenMCP() {
        ToolCall toolCall = new ToolCall("generate_report",
                new ObjectMapper().createObjectNode().put("region", "us-east"));
        when(llmProvider.generateToolCall(anyString(), any())).thenReturn(toolCall);
        when(llmProvider.providerName()).thenReturn("mock");
        when(mcpClient.executeToolCall(any(), anyString())).thenReturn(Flux.just("report data"));

        webTestClient.post()
                .uri("/ai/request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"prompt\": \"Show me sales for us-east\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> {
                    assert body.contains("report data");
                });
    }

    @Test
    void handleAiRequest_shouldReturnErrorForBadToolCall() {
        ToolCall toolCall = new ToolCall("unknown_tool",
                new ObjectMapper().createObjectNode());
        when(llmProvider.generateToolCall(anyString(), any())).thenReturn(toolCall);
        when(llmProvider.providerName()).thenReturn("mock");
        when(mcpClient.executeToolCall(any(), anyString()))
                .thenReturn(Flux.error(new IllegalArgumentException("Unknown tool")));

        webTestClient.post()
                .uri("/ai/request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"prompt\": \"Show me data\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("INVALID_TOOL_CALL");
    }

    @Test
    void handleAiRequest_shouldReturnErrorForLlmFailure() {
        when(llmProvider.generateToolCall(anyString(), any()))
                .thenThrow(new IllegalStateException("LLM failed"));

        webTestClient.post()
                .uri("/ai/request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"prompt\": \"Show me data\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("LLM_ERROR");
    }
}
