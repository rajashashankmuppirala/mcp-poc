package com.example.domain.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportExportControllerTest {

    @Autowired
    private ReportExportController controller;

    @Test
    void exportReport_shouldReturnStreamingBody() {
        var response = controller.exportReport("revenue", "2025-01-01", "2026-04-30", null);
        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
    }
}
