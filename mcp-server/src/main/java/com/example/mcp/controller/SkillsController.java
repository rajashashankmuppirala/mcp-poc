package com.example.mcp.controller;

import com.example.mcp.model.SkillDefinition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes skill metadata for this MCP server.
 * Skills group related tools under a common domain with trigger keywords.
 */
@RestController
public class SkillsController {

    @GetMapping("/skills")
    public List<SkillDefinition> getSkills() {
        return List.of(
                new SkillDefinition(
                        "report_analyst",
                        "Generate and analyze business reports",
                        List.of("report", "revenue", "sales", "income", "earnings", "analytics", "dashboard", "summary"),
                        List.of("generate_report")
                ),
                new SkillDefinition(
                        "chart_builder",
                        "Create custom charts and visualizations from data",
                        List.of("chart", "graph", "plot", "visualize", "visualization", "bar chart", "line chart", "pie chart", "dashboard", "breakdown"),
                        List.of("generate_report", "list_failed_jobs", "list_successful_dataflows")
                )
        );
    }
}
