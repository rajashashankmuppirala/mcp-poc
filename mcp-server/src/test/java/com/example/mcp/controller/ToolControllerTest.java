package com.example.mcp.controller;

import com.example.mcp.service.ReportStreamService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ToolController.class)
class ToolControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReportStreamService reportStreamService;

    @Test
    void generateReport_shouldRejectEmptyReportType() throws Exception {
        String body = """
                {"reportType": ""}
                """;
        mockMvc.perform(post("/tools/generate-report")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void generateReport_shouldRejectMissingReportType() throws Exception {
        String body = """
                {"filters": {"region": "us"}}
                """;
        mockMvc.perform(post("/tools/generate-report")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void generateReport_shouldAcceptValidRequestAndStream() throws Exception {
        StreamingResponseBody mockStream = outputStream -> {
            outputStream.write("row1,data\n".getBytes());
        };
        when(reportStreamService.streamReport(any(), anyString())).thenReturn(mockStream);

        String body = """
                {"reportType": "revenue", "limit": 100}
                """;
        mockMvc.perform(post("/tools/generate-report")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-ID"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("row1")));
    }
}
