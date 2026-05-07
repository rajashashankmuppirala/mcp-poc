package com.example.domain.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
public class ReportExportController {

    private static final int CHUNK_INTERVAL_MS = 150;
    private static final int MIN_ROWS = 30;
    private static final int MAX_ROWS = 60;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Random random = new Random();

    @GetMapping("/reports/export")
    public ResponseEntity<StreamingResponseBody> exportReport(
            @RequestParam(defaultValue = "revenue") String type,
            @RequestParam(defaultValue = "2025-01-01") String startDate,
            @RequestParam(defaultValue = "2026-04-30") String endDate,
            @RequestParam(required = false) String region) {

        ReportStreamController.ReportQuery.DateRange dateRange = new ReportStreamController.ReportQuery.DateRange(startDate, endDate);
        Map<String, String> filters = region != null ? Map.of("region", region) : Map.of();
        ReportStreamController.ReportQuery query = new ReportStreamController.ReportQuery(
                type, dateRange, filters, null, 1000);

        ReportStreamController.ReportGenerator generator = getGenerator(type);
        int rowCount = MIN_ROWS + random.nextInt(MAX_ROWS - MIN_ROWS + 1);
        List<String> rows = generator.generateRows(rowCount, query, random);

        // CSV header based on first row keys
        String header = rows.isEmpty() ? "" : buildCsvHeader(rows.get(0));

        StreamingResponseBody stream = outputStream -> {
            CountDownLatch latch = new CountDownLatch(rows.size() + 1);
            AtomicInteger index = new AtomicInteger(0);

            // Write header
            scheduler.schedule(() -> {
                try {
                    outputStream.write((header + "\n").getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                } catch (Exception e) { /* client disconnected */ }
                finally { latch.countDown(); }
            }, 0, TimeUnit.MILLISECONDS);

            // Write data rows
            for (String row : rows) {
                int seq = index.getAndIncrement();
                scheduler.schedule(() -> {
                    try {
                        String chunk = row + "\n";
                        outputStream.write(chunk.getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                    } catch (Exception e) { /* client disconnected */ }
                    finally { latch.countDown(); }
                }, (long) seq * CHUNK_INTERVAL_MS, TimeUnit.MILLISECONDS);
            }

            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "text/csv");
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + type + "_report.csv\"");

        return ResponseEntity.ok().headers(headers).body(stream);
    }

    private ReportStreamController.ReportGenerator getGenerator(String type) {
        return switch (type.toLowerCase()) {
            case "revenue" -> new ReportStreamController.RevenueGenerator();
            case "sales" -> new ReportStreamController.SalesGenerator();
            case "orders" -> new ReportStreamController.OrdersGenerator();
            case "inventory" -> new ReportStreamController.InventoryGenerator();
            case "customers" -> new ReportStreamController.CustomersGenerator();
            case "expenses" -> new ReportStreamController.ExpensesGenerator();
            case "profit" -> new ReportStreamController.ProfitGenerator();
            case "subscriptions" -> new ReportStreamController.SubscriptionsGenerator();
            default -> new ReportStreamController.RevenueGenerator();
        };
    }

    private String buildCsvHeader(String jsonRow) {
        try {
            // Extract keys from first JSON object as CSV headers
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            int depth = 0;
            StringBuilder key = new StringBuilder();
            for (char c : jsonRow.toCharArray()) {
                if (c == '{') depth++;
                else if (c == '}') depth--;
                else if (c == '"' && depth == 1) {
                    if (key.length() == 0) { /* start of key */ }
                    else {
                        // end of key
                        if (!first) sb.append(",");
                        sb.append(key);
                        first = false;
                        key = new StringBuilder();
                    }
                } else if (c == ':' && depth == 1) { /* skip colon */ }
                else if (depth == 1 && c != ',' && c != ' ') {
                    key.append(c);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "data";
        }
    }
}
