package com.example.domain.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureMockMvc
class ReportExportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final RestTemplate restTemplate = new RestTemplate();

    @Test
    void exportReport_shouldReturnOkWithTextPlain() throws Exception {
        mockMvc.perform(get("/reports/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain"));
    }

    @Test
    void exportReport_shouldContainMultipleRows() {
        String body = restTemplate.getForObject("http://localhost:8082/reports/export", String.class);
        String[] lines = body.split("\n");
        assert lines.length >= 30 : "Expected at least 30 rows, got " + lines.length;
        assert lines.length <= 50 : "Expected at most 50 rows, got " + lines.length;
    }
}
