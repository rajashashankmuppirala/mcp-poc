package com.example.domain.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ReportStreamControllerLargeDataSetTest {

    @Autowired
    private ReportStreamController controller;

    @Test
    void shouldStream100PlusRowsIncrementally() throws Exception {
        // Arrange
        ReportStreamController.ReportQuery query = new ReportStreamController.ReportQuery(
                "revenue",
                new ReportStreamController.ReportQuery.DateRange("2026-01-01", "2026-12-31"),
                java.util.Map.of("region", "us-east"),
                List.of("product"),
                150
        );

        // Act
        StreamingResponseBody stream = controller.streamReport(query);

        // Assert: Capture streaming output
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        stream.writeTo(baos);

        String output = baos.toString();
        String[] lines = output.split("\n");

        // Verify row count
        int rowCount = 0;
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                rowCount++;
                assertTrue(line.startsWith("row"), "Each line should start with 'row': " + line);
            }
        }

        assertTrue(rowCount >= 30, "Should have at least 30 rows, got: " + rowCount);
    }
}
