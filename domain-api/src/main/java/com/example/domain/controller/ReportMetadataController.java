package com.example.domain.controller;

import com.example.domain.model.ReportSchema;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportMetadataController {

    private static final List<ReportSchema> SCHEMAS = List.of(
            new ReportSchema(
                    "revenue",
                    "Revenue report showing product revenue by region, channel, and time period. Includes revenue, cost, profit, margin percentage, and customer count.",
                    List.of("reportType", "startDate", "endDate"),
                    Map.of(
                            "reportType", "Must be 'revenue'",
                            "startDate", "Start date in YYYY-MM-DD format",
                            "endDate", "End date in YYYY-MM-DD format",
                            "region", "Optional filter: us-east, us-west, eu-west, eu-central, apac, latam"
                    ),
                    List.of("us-east", "us-west", "eu-west", "eu-central", "apac", "latam"),
                    List.of(
                            "Show revenue for this year",
                            "Revenue by region for Q1 2025",
                            "What is our revenue trend?",
                            "Compare revenue across regions"
                    )
            ),
            new ReportSchema(
                    "sales",
                    "Sales pipeline report with deal information including sales rep, industry, deal size, stage, and probability.",
                    List.of("reportType", "startDate", "endDate"),
                    Map.of(
                            "reportType", "Must be 'sales'",
                            "startDate", "Start date in YYYY-MM-DD format",
                            "endDate", "End date in YYYY-MM-DD format",
                            "region", "Optional filter: us-east, us-west, eu-west, eu-central, apac, latam"
                    ),
                    List.of("us-east", "us-west", "eu-west", "eu-central", "apac", "latam"),
                    List.of(
                            "Show me the sales pipeline",
                            "Deals closed by Alice Johnson",
                            "Sales by industry this quarter"
                    )
            ),
            new ReportSchema(
                    "orders",
                    "Order management report with order status, payment method, item count, and totals.",
                    List.of("reportType", "startDate", "endDate"),
                    Map.of(
                            "reportType", "Must be 'orders'",
                            "startDate", "Start date in YYYY-MM-DD format",
                            "endDate", "End date in YYYY-MM-DD format",
                            "region", "Optional filter: us-east, us-west, eu-west, eu-central, apac, latam"
                    ),
                    List.of("us-east", "us-west", "eu-west", "eu-central", "apac", "latam"),
                    List.of(
                            "Show me recent orders",
                            "Cancelled orders this month",
                            "Orders by payment method"
                    )
            ),
            new ReportSchema(
                    "inventory",
                    "Inventory report with warehouse stock levels, reorder points, and low stock alerts.",
                    List.of("reportType", "startDate", "endDate"),
                    Map.of(
                            "reportType", "Must be 'inventory'",
                            "startDate", "Start date in YYYY-MM-DD format",
                            "endDate", "End date in YYYY-MM-DD format"
                    ),
                    List.of(),
                    List.of(
                            "Show current inventory levels",
                            "Which items are below reorder point",
                            "Inventory value by warehouse"
                    )
            ),
            new ReportSchema(
                    "customers",
                    "Customer report with plan details, MRR, seats, NPS score, and acquisition source.",
                    List.of("reportType", "startDate", "endDate"),
                    Map.of(
                            "reportType", "Must be 'customers'",
                            "startDate", "Start date in YYYY-MM-DD format",
                            "endDate", "End date in YYYY-MM-DD format",
                            "region", "Optional filter: us-east, us-west, eu-west, eu-central, apac, latam"
                    ),
                    List.of("us-east", "us-west", "eu-west", "eu-central", "apac", "latam"),
                    List.of(
                            "Show me new customers this quarter",
                            "Customers by plan type",
                            "Customer NPS scores by region"
                    )
            ),
            new ReportSchema(
                    "expenses",
                    "Expense report with department, category, amount, and approval status.",
                    List.of("reportType", "startDate", "endDate"),
                    Map.of(
                            "reportType", "Must be 'expenses'",
                            "startDate", "Start date in YYYY-MM-DD format",
                            "endDate", "End date in YYYY-MM-DD format",
                            "region", "Optional filter: us-east, us-west, eu-west, eu-central, apac, latam"
                    ),
                    List.of("us-east", "us-west", "eu-west", "eu-central", "apac", "latam"),
                    List.of(
                            "Show expenses for last quarter",
                            "Expenses by department",
                            "Unapproved expenses"
                    )
            ),
            new ReportSchema(
                    "profit",
                    "Profit and loss report with revenue, cost of goods sold, operating expenses, and net margin.",
                    List.of("reportType", "startDate", "endDate"),
                    Map.of(
                            "reportType", "Must be 'profit'",
                            "startDate", "Start date in YYYY-MM-DD format",
                            "endDate", "End date in YYYY-MM-DD format",
                            "region", "Optional filter: us-east, us-west, eu-west, eu-central, apac, latam"
                    ),
                    List.of("us-east", "us-west", "eu-west", "eu-central", "apac", "latam"),
                    List.of(
                            "Show profit margins by region",
                            "Net profit this quarter",
                            "Profit vs cost analysis"
                    )
            ),
            new ReportSchema(
                    "subscriptions",
                    "Subscription analytics with MRR changes, events (new, churn, upgrade, downgrade), and lifetime.",
                    List.of("reportType", "startDate", "endDate"),
                    Map.of(
                            "reportType", "Must be 'subscriptions'",
                            "startDate", "Start date in YYYY-MM-DD format",
                            "endDate", "End date in YYYY-MM-DD format",
                            "region", "Optional filter: us-east, us-west, eu-west, eu-central, apac, latam"
                    ),
                    List.of("us-east", "us-west", "eu-west", "eu-central", "apac", "latam"),
                    List.of(
                            "Show me subscription churn rate",
                            "MRR trend this year",
                            "New subscriptions last month"
                    )
            )
    );

    @GetMapping("/metadata")
    public List<ReportSchema> getReportMetadata() {
        return SCHEMAS;
    }
}
