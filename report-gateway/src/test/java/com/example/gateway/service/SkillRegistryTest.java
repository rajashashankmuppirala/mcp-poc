package com.example.gateway.service;

import com.example.gateway.model.SkillDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {

    private final SkillRegistry registry = new SkillRegistry("classpath:skills/*.md");

    @Test
    void shouldLoadAllSkills() {
        assertEquals(3, registry.getAllSkills().size());
    }

    @Test
    void shouldMatchReportAnalystSkill() {
        SkillDefinition skill = registry.matchSkill("Show me revenue report for Q1");

        assertNotNull(skill);
        assertEquals("report_analyst", skill.name());
        assertEquals("reports", skill.mcpServer());
    }

    @Test
    void shouldMatchOperationsMonitorSkill() {
        SkillDefinition skill = registry.matchSkill("Show me failed jobs in the last 24 hours");

        assertNotNull(skill);
        assertEquals("operations_monitor", skill.name());
        assertEquals("ops", skill.mcpServer());
    }

    @Test
    void shouldReturnNullForNoMatch() {
        SkillDefinition skill = registry.matchSkill("What is the weather today?");

        assertNull(skill);
    }

    @Test
    void shouldGetSkillPrompt() {
        String prompt = registry.getSkillPrompt("report_analyst");

        assertNotNull(prompt);
        assertTrue(prompt.contains("report analyst"));
    }

    @Test
    void shouldGetAllowedTools() {
        var tools = registry.getAllowedTools("operations_monitor");

        assertEquals(2, tools.size());
        assertTrue(tools.contains("list_failed_jobs"));
        assertTrue(tools.contains("list_successful_dataflows"));
    }

    @Test
    void shouldGetSkillsForServer() {
        var skills = registry.getSkillsForServer("reports");

        assertEquals(2, skills.size());
        assertTrue(skills.stream().anyMatch(s -> s.name().equals("report_analyst")));
        assertTrue(skills.stream().anyMatch(s -> s.name().equals("chart_builder")));
    }

    @Test
    void shouldReturnAllTriggers() {
        var triggers = registry.getAllTriggers();

        assertTrue(triggers.contains("revenue"));
        assertTrue(triggers.contains("failed"));
        assertTrue(triggers.contains("dataflow"));
    }

    @Test
    void shouldUseMarkdownBodyAsSystemPrompt() {
        String prompt = registry.getSkillPrompt("operations_monitor");

        assertNotNull(prompt);
        assertTrue(prompt.contains("operations monitoring assistant"));
        // Verify it's the markdown body, not the frontmatter description
        assertTrue(prompt.contains("# Operations Monitor"));
    }
}
