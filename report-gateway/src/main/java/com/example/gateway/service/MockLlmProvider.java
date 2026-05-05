package com.example.gateway.service;

import com.example.gateway.model.ToolCall;
import com.example.gateway.model.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(name = "llm.provider", havingValue = "mock")
public class MockLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(MockLlmProvider.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public ToolCall generateToolCall(String userMessage, List<ToolDefinition> tools) {
        log.info("Mock LLM processing prompt: {}", userMessage);

        String lower = userMessage.toLowerCase();
        String region = lower.contains("us-east") ? "us-east"
                : lower.contains("eu-west") ? "eu-west"
                : null;

        try {
            var params = mapper.createObjectNode();
            params.put("reportType", "revenue");
            if (region != null) params.put("region", region);
            return new ToolCall("generate_report", params);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build mock tool call", e);
        }
    }

    @Override
    public String providerName() {
        return "mock";
    }
}
