package com.example.domain.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportStreamController {

    private static final int CHUNK_INTERVAL_MS = 300;
    private static final int MIN_ROWS = 30;
    private static final int MAX_ROWS = 50;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public record ReportQuery(
            String reportType,
            DateRange dateRange,
            java.util.Map<String, String> filters,
            java.util.List<String> groupBy,
            Integer limit
    ) {
        public record DateRange(String start, String end) {}
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_PLAIN_VALUE)
    public StreamingResponseBody streamReport(@RequestBody ReportQuery query) {
        int limit = query.limit() != null ? Math.min(query.limit(), 10000) : 1000;
        int rowCount = Math.min(MIN_ROWS + new Random().nextInt(MAX_ROWS - MIN_ROWS + 1), limit);
        List<String> rows = generateRows(rowCount);

        return outputStream -> {
            CountDownLatch latch = new CountDownLatch(rows.size());
            AtomicInteger index = new AtomicInteger(0);

            for (String row : rows) {
                int seq = index.getAndIncrement();
                scheduler.schedule(() -> {
                    try {
                        outputStream.write((row + "\n").getBytes());
                        outputStream.flush();
                    } catch (Exception e) {
                        // Client disconnected
                    } finally {
                        latch.countDown();
                    }
                }, (long) seq * CHUNK_INTERVAL_MS, TimeUnit.MILLISECONDS);
            }

            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    private List<String> generateRows(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> "row" + i + ",data-" + i + "," + System.currentTimeMillis())
                .toList();
    }
}
