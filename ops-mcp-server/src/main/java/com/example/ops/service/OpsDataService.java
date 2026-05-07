package com.example.ops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Simulated operations data service — generates job execution records.
 * In production, this would query a job scheduler or workflow engine.
 */
@Service
public class OpsDataService {

    private static final Logger log = LoggerFactory.getLogger(OpsDataService.class);

    private static final List<String> JOB_NAMES = List.of(
            "daily_revenue_calc",
            "customer_segmentation",
            "inventory_sync",
            "payment_reconciliation",
            "email_campaign_dispatch",
            "data_warehouse_etl",
            "report_aggregation",
            "user_activity_rollup"
    );

    private static final String[] ERROR_MESSAGES = {
            "Connection timeout to database cluster",
            "OutOfMemoryError: Java heap space",
            "API rate limit exceeded for upstream service",
            "Invalid data format: expected JSON, got CSV",
            "Authentication failed: token expired",
            "Disk space exhausted on /data volume"
    };

    private static final String[] DATAFLOW_NAMES = {
            "orders → data_warehouse",
            "clickstream → analytics_db",
            "crm → billing_system",
            "inventory → reporting_engine",
            "payments → ledger_service",
            "user_events → personalization_engine"
    };

    /**
     * Generate simulated failed job records for the given time window.
     */
    public List<Map<String, Object>> getFailedJobs(int hours, int days) {
        int totalHours = hours > 0 ? hours : days * 24;
        Instant now = Instant.now();
        Instant windowStart = now.minus(Duration.ofHours(totalHours));

        log.info("Generating failed jobs for last {} hours (window: {} to {})", totalHours, windowStart, now);

        List<Map<String, Object>> results = new ArrayList<>();
        Random rng = new Random(42); // deterministic for POC

        int count = Math.min(totalHours, JOB_NAMES.size() * 2);
        for (int i = 0; i < count; i++) {
            Instant failureTime = windowStart.plusSeconds(rng.nextLong(0, totalHours * 3600L));
            if (failureTime.isAfter(now)) continue;

            Map<String, Object> job = new LinkedHashMap<>();
            job.put("job_name", JOB_NAMES.get(rng.nextInt(JOB_NAMES.size())));
            job.put("status", "FAILED");
            job.put("timestamp", failureTime.toString());
            job.put("duration_seconds", rng.nextInt(5, 600));
            job.put("error_message", ERROR_MESSAGES[rng.nextInt(ERROR_MESSAGES.length)]);
            job.put("retry_count", rng.nextInt(0, 4));
            results.add(job);
        }

        results.sort((a, b) -> ((String) b.get("timestamp")).compareTo((String) a.get("timestamp")));
        return results;
    }

    /**
     * Generate simulated successful dataflow records for the given time window.
     */
    public List<Map<String, Object>> getSuccessfulDataflows(int hours, int days) {
        int totalHours = hours > 0 ? hours : days * 24;
        Instant now = Instant.now();
        Instant windowStart = now.minus(Duration.ofHours(totalHours));

        log.info("Generating successful dataflows for last {} hours (window: {} to {})", totalHours, windowStart, now);

        List<Map<String, Object>> results = new ArrayList<>();
        Random rng = new Random(99); // different seed from failed jobs

        int count = Math.min(totalHours * 2, DATAFLOW_NAMES.length * 3);
        for (int i = 0; i < count; i++) {
            Instant completionTime = windowStart.plusSeconds(rng.nextLong(0, totalHours * 3600L));
            if (completionTime.isAfter(now)) continue;

            Map<String, Object> flow = new LinkedHashMap<>();
            flow.put("flow_name", DATAFLOW_NAMES[rng.nextInt(DATAFLOW_NAMES.length)]);
            flow.put("status", "SUCCESS");
            flow.put("completed_at", completionTime.toString());
            flow.put("duration_seconds", rng.nextInt(10, 300));
            flow.put("records_processed", rng.nextInt(1000, 500000));
            flow.put("source_rows", rng.nextInt(1000, 500000));
            results.add(flow);
        }

        results.sort((a, b) -> ((String) b.get("completed_at")).compareTo((String) a.get("completed_at")));
        return results;
    }
}
