package com.example.gateway.service;

import com.example.gateway.model.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagAutoIngestionServiceTest {

    @Mock
    private RagService ragService;

    private RagAutoIngestionService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new RagAutoIngestionService(ragService, "http://localhost:8082");
    }

    private JsonNode buildParams(ObjectNode properties, String... requiredFields) {
        ObjectNode root = mapper.createObjectNode();
        root.set("properties", properties);
        if (requiredFields.length > 0) {
            var arr = root.arrayNode();
            for (String f : requiredFields) arr.add(f);
            root.set("required", arr);
        }
        return root;
    }

    private ObjectNode stringProp(String desc) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "string");
        n.put("description", desc);
        return n;
    }

    @Test
    void ingestToolSchemasCreatesDocumentsWithDeterministicId() {
        ObjectNode props = mapper.createObjectNode().set("reportType", stringProp("Report type"));
        List<ToolDefinition> tools = List.of(
                new ToolDefinition("generate_report", "Generate a report", buildParams(props, "reportType"))
        );

        service.ingestToolSchemas(tools);

        verify(ragService).ingestWithId(eq("auto-tool:generate_report"), eq("Tool: generate_report"),
                contains("Tool Name: generate_report"), anyList());
    }

    @Test
    void ingestToolSchemasHandlesEmptyList() {
        service.ingestToolSchemas(List.of());
        verifyNoInteractions(ragService);
    }

    @Test
    void ingestToolSchemasHandlesNullList() {
        service.ingestToolSchemas(null);
        verifyNoInteractions(ragService);
    }

    @Test
    void ingestToolSchemasHandlesRagFailureGracefully() {
        ObjectNode props = mapper.createObjectNode().set("name", stringProp("Name"));
        List<ToolDefinition> tools = List.of(
                new ToolDefinition("test_tool", "A tool", buildParams(props))
        );
        doThrow(new RuntimeException("RAG unavailable")).when(ragService).ingestWithId(any(), any(), any(), any());

        service.ingestToolSchemas(tools);
    }

    @Test
    void ingestReportSchemasHandlesDomainApiUnavailable() {
        service.ingestReportSchemas();
    }
}
