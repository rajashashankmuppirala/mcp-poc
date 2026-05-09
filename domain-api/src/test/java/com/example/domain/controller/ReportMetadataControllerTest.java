package com.example.domain.controller;

import com.example.domain.model.ReportSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReportMetadataControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ReportMetadataController()).build();
    }

    @Test
    void getReportMetadataReturnsAllSchemas() throws Exception {
        mockMvc.perform(get("/api/v1/reports/metadata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8));
    }

    @Test
    void getReportMetadataIncludesRevenue() throws Exception {
        mockMvc.perform(get("/api/v1/reports/metadata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'revenue')].description").exists())
                .andExpect(jsonPath("$[?(@.name == 'revenue')].regions").isArray())
                .andExpect(jsonPath("$[?(@.name == 'revenue')].exampleQueries").isArray());
    }

    @Test
    void allSchemasHaveRequiredFields() throws Exception {
        String json = mockMvc.perform(get("/api/v1/reports/metadata"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        ObjectMapper mapper = new ObjectMapper();
        List<ReportSchema> schemas = mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, ReportSchema.class));

        for (ReportSchema schema : schemas) {
            assert schema.name() != null && !schema.name().isBlank() : "name is blank for " + schema;
            assert schema.description() != null && !schema.description().isBlank() : "description is blank for " + schema;
            assert schema.requiredParams() != null : "requiredParams is null for " + schema;
            assert schema.exampleQueries() != null && !schema.exampleQueries().isEmpty() : "exampleQueries is empty for " + schema;
        }
    }
}
