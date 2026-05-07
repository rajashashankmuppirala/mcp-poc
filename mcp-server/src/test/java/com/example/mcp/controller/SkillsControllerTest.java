package com.example.mcp.controller;

import com.example.mcp.model.SkillDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillsControllerTest {

    private final SkillsController controller = new SkillsController();

    @Test
    void shouldReturnSkills() {
        List<SkillDefinition> skills = controller.getSkills();

        assertEquals(2, skills.size());
        assertTrue(skills.stream().anyMatch(s -> s.name().equals("report_analyst")));
        assertTrue(skills.stream().anyMatch(s -> s.name().equals("chart_builder")));
    }

    @Test
    void shouldReturnReportAnalystWithCorrectTools() {
        List<SkillDefinition> skills = controller.getSkills();
        SkillDefinition reportSkill = skills.stream()
                .filter(s -> s.name().equals("report_analyst"))
                .findFirst().orElseThrow();

        assertEquals("Generate and analyze business reports", reportSkill.description());
        assertTrue(reportSkill.triggers().contains("revenue"));
        assertTrue(reportSkill.triggers().contains("report"));
        assertEquals(List.of("generate_report"), reportSkill.allowedTools());
    }

    @Test
    void shouldReturnChartBuilderWithCrossServerTools() {
        List<SkillDefinition> skills = controller.getSkills();
        SkillDefinition chartSkill = skills.stream()
                .filter(s -> s.name().equals("chart_builder"))
                .findFirst().orElseThrow();

        assertTrue(chartSkill.triggers().contains("chart"));
        assertTrue(chartSkill.triggers().contains("visualize"));
        // chart_builder spans tools from multiple servers
        assertTrue(chartSkill.allowedTools().contains("generate_report"));
        assertTrue(chartSkill.allowedTools().contains("list_failed_jobs"));
        assertTrue(chartSkill.allowedTools().contains("list_successful_dataflows"));
    }
}
