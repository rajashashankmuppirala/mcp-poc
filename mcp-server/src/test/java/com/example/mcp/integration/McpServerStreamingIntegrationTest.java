package com.example.mcp.integration;

import com.example.mcp.service.ReportStreamService;
import com.example.mcp.tool.ReportTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
class McpServerStreamingIntegrationTest {

    @Autowired
    private ReportTools reportTools;

    @MockitoBean
    private ReportStreamService reportStreamService;

    @Test
    void shouldStreamReportFromDomainApi() {
        when(reportStreamService.streamReport(any(), anyString()))
                .thenReturn(Flux.just(
                        "row1,Product A,100,10.00,1000.00",
                        "row2,Product B,200,20.00,4000.00",
                        "row3,Product C,150,15.00,2250.00"
                ));

        java.util.List<String> result = reportTools.generate_report("revenue", "2026-01-01", "2026-03-31", "us-east");

        assertEquals(3, result.size());
        assertEquals("row1,Product A,100,10.00,1000.00", result.get(0));
        assertEquals("row2,Product B,200,20.00,4000.00", result.get(1));
        assertEquals("row3,Product C,150,15.00,2250.00", result.get(2));
    }

    @Test
    void shouldHandleMissingOptionalParams() {
        when(reportStreamService.streamReport(any(), anyString()))
                .thenReturn(Flux.just("row1,data"));

        java.util.List<String> result = reportTools.generate_report("sales", null, null, null);

        assertEquals(1, result.size());
        assertEquals("row1,data", result.get(0));
    }
}
