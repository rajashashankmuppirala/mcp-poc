package com.example.domain.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
public class ReportExportController {

    private static final int CHUNK_INTERVAL_MS = 300;
    private static final int MIN_ROWS = 30;
    private static final int MAX_ROWS = 50;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @GetMapping("/reports/export")
    public ResponseEntity<StreamingResponseBody> exportReport() {
        List<String> rows = generateRows();

        StreamingResponseBody stream = outputStream -> {
            CountDownLatch latch = new CountDownLatch(rows.size());
            AtomicInteger index = new AtomicInteger(0);

            for (String row : rows) {
                int seq = index.getAndIncrement();
                scheduler.schedule(() -> {
                    try {
                        String chunk = row + "\n";
                        outputStream.write(chunk.getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                    } catch (Exception e) {
                        // Client disconnected — propagate to stop further scheduling
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

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "text/plain");

        return ResponseEntity.ok()
                .headers(headers)
                .body(stream);
    }

    private List<String> generateRows() {
        int count = MIN_ROWS + new Random().nextInt(MAX_ROWS - MIN_ROWS + 1);
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> "row" + i + ",data-" + i + "," + System.currentTimeMillis())
                .toList();
    }
}
