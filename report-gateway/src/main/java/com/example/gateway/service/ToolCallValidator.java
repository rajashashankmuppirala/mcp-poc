package com.example.gateway.service;

import com.example.gateway.model.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class ToolCallValidator {

    private static final Logger log = LoggerFactory.getLogger(ToolCallValidator.class);
    private static final Set<String> ALLOWED_TOOLS = Set.of("generate_report");

    private final JsonSchema schema;

    public ToolCallValidator() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode params = mapper.createObjectNode();
        params.put("type", "object");
        ObjectNode properties = mapper.createObjectNode();

        ObjectNode reportType = mapper.createObjectNode();
        reportType.put("type", "string");
        reportType.put("pattern", "^[a-zA-Z_]+$");
        properties.set("reportType", reportType);

        ObjectNode startDate = mapper.createObjectNode();
        startDate.put("type", "string");
        startDate.put("pattern", "^\\d{4}-\\d{2}-\\d{2}$");
        properties.set("startDate", startDate);

        ObjectNode endDate = mapper.createObjectNode();
        endDate.put("type", "string");
        endDate.put("pattern", "^\\d{4}-\\d{2}-\\d{2}$");
        properties.set("endDate", endDate);

        ObjectNode region = mapper.createObjectNode();
        region.put("type", "string");
        region.put("pattern", "^[a-z]+-[a-z]+$");
        properties.set("region", region);

        params.set("properties", properties);

        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        this.schema = factory.getSchema(params);
    }

    public void validate(ToolCall toolCall) {
        if (toolCall.tool() == null || toolCall.tool().isBlank()) {
            throw new IllegalArgumentException("Tool name is empty");
        }
        if (!ALLOWED_TOOLS.contains(toolCall.tool())) {
            throw new IllegalArgumentException("Unknown tool: " + toolCall.tool()
                    + ". Available: " + String.join(", ", ALLOWED_TOOLS));
        }
        if (toolCall.parameters() == null) {
            throw new IllegalArgumentException("Tool parameters are null");
        }

        Set<ValidationMessage> errors = schema.validate(toolCall.parameters());
        if (!errors.isEmpty()) {
            String msg = errors.stream().map(ValidationMessage::getMessage).reduce((a, b) -> a + "; " + b).orElse("");
            log.warn("Tool call validation failed: {}", msg);
            throw new IllegalArgumentException("Invalid tool parameters: " + msg);
        }
    }
}
