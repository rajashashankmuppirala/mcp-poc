package com.example.domain.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportStreamController {

    private static final int CHUNK_INTERVAL_MS = 150;
    private static final int MIN_ROWS = 30;
    private static final int MAX_ROWS = 60;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Random random = new Random();
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final Map<String, ReportGenerator> GENERATORS;

    static {
        Map<String, ReportGenerator> map = new HashMap<>();
        map.put("revenue", new RevenueGenerator());
        map.put("sales", new SalesGenerator());
        map.put("orders", new OrdersGenerator());
        map.put("inventory", new InventoryGenerator());
        map.put("customers", new CustomersGenerator());
        map.put("expenses", new ExpensesGenerator());
        map.put("profit", new ProfitGenerator());
        map.put("subscriptions", new SubscriptionsGenerator());
        GENERATORS = Collections.unmodifiableMap(map);
    }

    public record ReportQuery(
            String reportType,
            DateRange dateRange,
            Map<String, String> filters,
            List<String> groupBy,
            Integer limit
    ) {
        public record DateRange(String start, String end) {}
    }

    @PostMapping(value = "/stream", produces = MediaType.APPLICATION_JSON_VALUE)
    public StreamingResponseBody streamReport(@RequestBody ReportQuery query) {
        String type = query.reportType() != null ? query.reportType().toLowerCase() : "revenue";
        int limit = query.limit() != null ? Math.min(query.limit(), 10000) : 1000;
        int rowCount = Math.min(MIN_ROWS + random.nextInt(MAX_ROWS - MIN_ROWS + 1), limit);

        ReportGenerator generator = GENERATORS.getOrDefault(type, GENERATORS.get("revenue"));
        List<String> rows = generator.generateRows(rowCount, query, random);

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

    interface ReportGenerator {
        List<String> generateRows(int count, ReportQuery query, Random random);
    }

    static String randomDate(String start, String end, Random random) {
        try {
            long startMs = java.time.LocalDate.parse(start).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
            long endMs = java.time.LocalDate.parse(end).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
            long randomMs = startMs + (long) (random.nextDouble() * (endMs - startMs));
            return java.time.Instant.ofEpochMilli(randomMs).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
        } catch (Exception e) {
            return "2025-06-15";
        }
    }

    static class RevenueGenerator implements ReportGenerator {
        private static final String[] REGIONS = {"us-east", "us-west", "eu-west", "eu-central", "apac", "latam"};
        private static final String[] PRODUCTS = {"Enterprise Suite", "Starter Plan", "API Access", "Professional Services", "Support Add-on", "Data Platform", "Analytics Module", "Custom Integration"};
        private static final String[] CHANNELS = {"Direct", "Partner", "Online", "Marketplace", "Reseller"};

        @Override
        public List<String> generateRows(int count, ReportQuery query, Random random) {
            List<String> rows = new ArrayList<>();
            String regionFilter = query.filters() != null ? query.filters().get("region") : null;
            String ds = query.dateRange() != null ? query.dateRange().start() : "2025-01-01";
            String de = query.dateRange() != null ? query.dateRange().end() : "2026-04-30";
            for (int i = 0; i < count; i++) {
                String region = regionFilter != null ? regionFilter : REGIONS[random.nextInt(REGIONS.length)];
                String product = PRODUCTS[random.nextInt(PRODUCTS.length)];
                String channel = CHANNELS[random.nextInt(CHANNELS.length)];
                double revenue = 5000 + random.nextDouble() * 95000;
                double cost = revenue * (0.2 + random.nextDouble() * 0.4);
                int customers = 10 + random.nextInt(500);
                String currency = "us-east".equals(region) || "us-west".equals(region) ? "USD"
                        : "eu-west".equals(region) || "eu-central".equals(region) ? "EUR" : "USD";
                ObjectNode row = mapper.createObjectNode();
                row.put("id", "rev-" + (1000 + i));
                row.put("date", randomDate(ds, de, random));
                row.put("region", region);
                row.put("product", product);
                row.put("channel", channel);
                row.put("revenue", Math.round(revenue * 100.0) / 100.0);
                row.put("cost", Math.round(cost * 100.0) / 100.0);
                row.put("profit", Math.round((revenue - cost) * 100.0) / 100.0);
                row.put("margin_pct", Math.round(((revenue - cost) / revenue) * 1000.0) / 10.0);
                row.put("customer_count", customers);
                row.put("currency", currency);
                rows.add(row.toString());
            }
            return rows;
        }
    }

    static class SalesGenerator implements ReportGenerator {
        private static final String[] REGIONS = {"us-east", "us-west", "eu-west", "eu-central", "apac", "latam"};
        private static final String[] SALES_REPS = {"Alice Johnson", "Bob Martinez", "Carol Chen", "David Kim", "Eva Rossi", "Frank Mueller", "Grace Okafor", "Henry Patel"};
        private static final String[] STAGES = {"Qualified", "Proposal", "Negotiation", "Closed Won", "Closed Lost"};
        private static final String[] INDUSTRIES = {"Technology", "Healthcare", "Finance", "Retail", "Manufacturing", "Education", "Government"};

        @Override
        public List<String> generateRows(int count, ReportQuery query, Random random) {
            List<String> rows = new ArrayList<>();
            String regionFilter = query.filters() != null ? query.filters().get("region") : null;
            String ds = query.dateRange() != null ? query.dateRange().start() : "2025-01-01";
            String de = query.dateRange() != null ? query.dateRange().end() : "2026-04-30";
            for (int i = 0; i < count; i++) {
                String region = regionFilter != null ? regionFilter : REGIONS[random.nextInt(REGIONS.length)];
                String rep = SALES_REPS[random.nextInt(SALES_REPS.length)];
                String industry = INDUSTRIES[random.nextInt(INDUSTRIES.length)];
                double dealSize = 10000 + random.nextDouble() * 490000;
                int daysInPipeline = 5 + random.nextInt(180);
                String stage = STAGES[random.nextInt(STAGES.length)];
                int probability;
                if ("Qualified".equals(stage)) probability = 10 + random.nextInt(20);
                else if ("Proposal".equals(stage)) probability = 30 + random.nextInt(25);
                else if ("Negotiation".equals(stage)) probability = 60 + random.nextInt(20);
                else if ("Closed Won".equals(stage)) probability = 100;
                else if ("Closed Lost".equals(stage)) probability = 0;
                else probability = 50;
                ObjectNode row = mapper.createObjectNode();
                row.put("id", "deal-" + (5000 + i));
                row.put("date", randomDate(ds, de, random));
                row.put("region", region);
                row.put("sales_rep", rep);
                row.put("industry", industry);
                row.put("deal_size", Math.round(dealSize * 100.0) / 100.0);
                row.put("stage", stage);
                row.put("probability", probability);
                row.put("days_in_pipeline", daysInPipeline);
                rows.add(row.toString());
            }
            return rows;
        }
    }

    static class OrdersGenerator implements ReportGenerator {
        private static final String[] REGIONS = {"us-east", "us-west", "eu-west", "eu-central", "apac", "latam"};
        private static final String[] STATUS = {"pending", "processing", "shipped", "delivered", "cancelled", "refunded"};
        private static final String[] PAYMENT = {"credit_card", "wire_transfer", "paypal", "invoice", "crypto"};

        @Override
        public List<String> generateRows(int count, ReportQuery query, Random random) {
            List<String> rows = new ArrayList<>();
            String regionFilter = query.filters() != null ? query.filters().get("region") : null;
            String ds = query.dateRange() != null ? query.dateRange().start() : "2025-01-01";
            String de = query.dateRange() != null ? query.dateRange().end() : "2026-04-30";
            for (int i = 0; i < count; i++) {
                String region = regionFilter != null ? regionFilter : REGIONS[random.nextInt(REGIONS.length)];
                String status = STATUS[random.nextInt(STATUS.length)];
                String payment = PAYMENT[random.nextInt(PAYMENT.length)];
                int items = 1 + random.nextInt(20);
                double total = items * (9.99 + random.nextDouble() * 490);
                double shipping = region.startsWith("us") ? 5 + random.nextDouble() * 15 : 15 + random.nextDouble() * 35;
                ObjectNode row = mapper.createObjectNode();
                row.put("order_id", "ORD-" + (100000 + i));
                row.put("date", randomDate(ds, de, random));
                row.put("region", region);
                row.put("status", status);
                row.put("payment_method", payment);
                row.put("item_count", items);
                row.put("subtotal", Math.round(total * 100.0) / 100.0);
                row.put("shipping", Math.round(shipping * 100.0) / 100.0);
                row.put("total", Math.round((total + shipping) * 100.0) / 100.0);
                row.put("customer_id", "CUST-" + (1000 + random.nextInt(9000)));
                rows.add(row.toString());
            }
            return rows;
        }
    }

    static class InventoryGenerator implements ReportGenerator {
        private static final String[] WAREHOUSES = {"WH-East Virginia", "WH-Oregon", "WH-Ireland", "WH-Singapore", "WH-Sao Paulo"};
        private static final String[] CATEGORIES = {"Electronics", "Software Licenses", "Hardware", "Accessories", "Networking", "Storage", "Peripherals"};
        private static final String[] SKU_PREFIX = {"ELEC", "SW", "HW", "ACC", "NET", "STR", "PER"};

        @Override
        public List<String> generateRows(int count, ReportQuery query, Random random) {
            List<String> rows = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String warehouse = WAREHOUSES[random.nextInt(WAREHOUSES.length)];
                int catIdx = random.nextInt(CATEGORIES.length);
                String category = CATEGORIES[catIdx];
                int stock = random.nextInt(2000);
                int reorderPoint = 50 + random.nextInt(200);
                double unitCost = 10 + random.nextDouble() * 990;
                String sku = SKU_PREFIX[catIdx] + "-" + (10000 + random.nextInt(90000));
                ObjectNode row = mapper.createObjectNode();
                row.put("sku", sku);
                row.put("category", category);
                row.put("warehouse", warehouse);
                row.put("stock_qty", stock);
                row.put("reorder_point", reorderPoint);
                row.put("unit_cost", Math.round(unitCost * 100.0) / 100.0);
                row.put("total_value", Math.round(stock * unitCost * 100.0) / 100.0);
                row.put("low_stock", stock < reorderPoint);
                rows.add(row.toString());
            }
            return rows;
        }
    }

    static class CustomersGenerator implements ReportGenerator {
        private static final String[] REGIONS = {"us-east", "us-west", "eu-west", "eu-central", "apac", "latam"};
        private static final String[] PLANS = {"Free", "Starter", "Professional", "Enterprise", "Custom"};
        private static final String[] INDUSTRIES = {"Technology", "Healthcare", "Finance", "Retail", "Manufacturing", "Education", "Government", "Media", "Logistics"};
        private static final String[] SOURCES = {"Organic Search", "Paid Ads", "Referral", "Social Media", "Direct", "Partner", "Event"};

        @Override
        public List<String> generateRows(int count, ReportQuery query, Random random) {
            List<String> rows = new ArrayList<>();
            String regionFilter = query.filters() != null ? query.filters().get("region") : null;
            String ds = query.dateRange() != null ? query.dateRange().start() : "2025-01-01";
            String de = query.dateRange() != null ? query.dateRange().end() : "2026-04-30";
            for (int i = 0; i < count; i++) {
                String region = regionFilter != null ? regionFilter : REGIONS[random.nextInt(REGIONS.length)];
                String plan = PLANS[random.nextInt(PLANS.length)];
                String industry = INDUSTRIES[random.nextInt(INDUSTRIES.length)];
                String source = SOURCES[random.nextInt(SOURCES.length)];
                int mrr;
                if ("Free".equals(plan)) mrr = 0;
                else if ("Starter".equals(plan)) mrr = 29 + random.nextInt(70);
                else if ("Professional".equals(plan)) mrr = 99 + random.nextInt(200);
                else if ("Enterprise".equals(plan)) mrr = 499 + random.nextInt(1500);
                else if ("Custom".equals(plan)) mrr = 1000 + random.nextInt(9000);
                else mrr = 0;
                int seats;
                if ("Free".equals(plan)) seats = 1;
                else if ("Starter".equals(plan)) seats = 1 + random.nextInt(5);
                else if ("Professional".equals(plan)) seats = 5 + random.nextInt(20);
                else if ("Enterprise".equals(plan)) seats = 20 + random.nextInt(200);
                else if ("Custom".equals(plan)) seats = 50 + random.nextInt(500);
                else seats = 1;
                double nps = -100 + random.nextDouble() * 200;
                ObjectNode row = mapper.createObjectNode();
                row.put("customer_id", "CUST-" + (1000 + i));
                row.put("signup_date", randomDate(ds, de, random));
                row.put("region", region);
                row.put("plan", plan);
                row.put("industry", industry);
                row.put("acquisition_source", source);
                row.put("mrr", mrr);
                row.put("seats", seats);
                row.put("nps_score", Math.round(nps));
                row.put("active", random.nextDouble() > 0.15);
                rows.add(row.toString());
            }
            return rows;
        }
    }

    static class ExpensesGenerator implements ReportGenerator {
        private static final String[] DEPARTMENTS = {"Engineering", "Sales", "Marketing", "Operations", "HR", "Finance", "Support", "R&D"};
        private static final String[] CATEGORIES = {"Salaries", "Software", "Infrastructure", "Travel", "Marketing", "Office", "Consulting", "Training"};
        private static final String[] REGIONS = {"us-east", "us-west", "eu-west", "eu-central", "apac", "latam"};

        @Override
        public List<String> generateRows(int count, ReportQuery query, Random random) {
            List<String> rows = new ArrayList<>();
            String regionFilter = query.filters() != null ? query.filters().get("region") : null;
            String ds = query.dateRange() != null ? query.dateRange().start() : "2025-01-01";
            String de = query.dateRange() != null ? query.dateRange().end() : "2026-04-30";
            for (int i = 0; i < count; i++) {
                String region = regionFilter != null ? regionFilter : REGIONS[random.nextInt(REGIONS.length)];
                String dept = DEPARTMENTS[random.nextInt(DEPARTMENTS.length)];
                String category = CATEGORIES[random.nextInt(CATEGORIES.length)];
                double amount = 1000 + random.nextDouble() * 99000;
                boolean approved = random.nextDouble() > 0.1;
                ObjectNode row = mapper.createObjectNode();
                row.put("id", "EXP-" + (20000 + i));
                row.put("date", randomDate(ds, de, random));
                row.put("region", region);
                row.put("department", dept);
                row.put("category", category);
                row.put("amount", Math.round(amount * 100.0) / 100.0);
                row.put("approved", approved);
                row.put("approver", approved ? "Manager" : "Pending");
                rows.add(row.toString());
            }
            return rows;
        }
    }

    static class ProfitGenerator implements ReportGenerator {
        private static final String[] REGIONS = {"us-east", "us-west", "eu-west", "eu-central", "apac", "latam"};
        private static final String[] PRODUCT_LINES = {"SaaS Platform", "Professional Services", "Marketplace", "API Products", "Consulting"};

        @Override
        public List<String> generateRows(int count, ReportQuery query, Random random) {
            List<String> rows = new ArrayList<>();
            String regionFilter = query.filters() != null ? query.filters().get("region") : null;
            String ds = query.dateRange() != null ? query.dateRange().start() : "2025-01-01";
            String de = query.dateRange() != null ? query.dateRange().end() : "2026-04-30";
            for (int i = 0; i < count; i++) {
                String region = regionFilter != null ? regionFilter : REGIONS[random.nextInt(REGIONS.length)];
                String productLine = PRODUCT_LINES[random.nextInt(PRODUCT_LINES.length)];
                double revenue = 20000 + random.nextDouble() * 180000;
                double cogs = revenue * (0.15 + random.nextDouble() * 0.35);
                double opex = revenue * (0.1 + random.nextDouble() * 0.25);
                double grossProfit = revenue - cogs;
                double netProfit = grossProfit - opex;
                ObjectNode row = mapper.createObjectNode();
                row.put("id", "PROF-" + (30000 + i));
                row.put("date", randomDate(ds, de, random));
                row.put("region", region);
                row.put("product_line", productLine);
                row.put("revenue", Math.round(revenue * 100.0) / 100.0);
                row.put("cogs", Math.round(cogs * 100.0) / 100.0);
                row.put("gross_profit", Math.round(grossProfit * 100.0) / 100.0);
                row.put("gross_margin_pct", Math.round((grossProfit / revenue) * 1000.0) / 10.0);
                row.put("opex", Math.round(opex * 100.0) / 100.0);
                row.put("net_profit", Math.round(netProfit * 100.0) / 100.0);
                row.put("net_margin_pct", Math.round((netProfit / revenue) * 1000.0) / 10.0);
                rows.add(row.toString());
            }
            return rows;
        }
    }

    static class SubscriptionsGenerator implements ReportGenerator {
        private static final String[] REGIONS = {"us-east", "us-west", "eu-west", "eu-central", "apac", "latam"};
        private static final String[] PLANS = {"Starter", "Professional", "Enterprise"};
        private static final String[] EVENTS = {"new", "upgrade", "downgrade", "churn", "reactivate", "renewal"};

        @Override
        public List<String> generateRows(int count, ReportQuery query, Random random) {
            List<String> rows = new ArrayList<>();
            String regionFilter = query.filters() != null ? query.filters().get("region") : null;
            String ds = query.dateRange() != null ? query.dateRange().start() : "2025-01-01";
            String de = query.dateRange() != null ? query.dateRange().end() : "2026-04-30";
            for (int i = 0; i < count; i++) {
                String region = regionFilter != null ? regionFilter : REGIONS[random.nextInt(REGIONS.length)];
                String plan = PLANS[random.nextInt(PLANS.length)];
                String event = EVENTS[random.nextInt(EVENTS.length)];
                int mrr;
                if ("Starter".equals(plan)) mrr = 29 + random.nextInt(70);
                else if ("Professional".equals(plan)) mrr = 99 + random.nextInt(200);
                else mrr = 499 + random.nextInt(1500);
                int lifetime = "churn".equals(event) ? 30 + random.nextInt(365)
                        : "new".equals(event) ? 0 : 90 + random.nextInt(730);
                int mrrChange = "churn".equals(event) ? -mrr
                        : "downgrade".equals(event) ? -mrr / 2 : mrr;
                int finalMrr = "churn".equals(event) ? 0 : mrr;
                ObjectNode row = mapper.createObjectNode();
                row.put("subscription_id", "SUB-" + (40000 + i));
                row.put("date", randomDate(ds, de, random));
                row.put("region", region);
                row.put("plan", plan);
                row.put("event", event);
                row.put("mrr_change", mrrChange);
                row.put("mrr", Math.max(0, finalMrr));
                row.put("lifetime_days", lifetime);
                rows.add(row.toString());
            }
            return rows;
        }
    }
}
