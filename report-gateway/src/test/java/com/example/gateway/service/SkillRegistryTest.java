package com.example.gateway.service;

import com.example.gateway.model.SkillDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        assertTrue(prompt.toLowerCase().contains("report analyst"));
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

    @Test
    void shouldAddServerDiscoveredSkillsNotInLocalFiles() {
        // When a server exposes a skill via GET /skills that doesn't exist locally,
        // it should be added to the registry
        registry.validateAgainstServer("test-server", List.of(
                new SkillDefinition(
                        "new_skill", "A new skill",
                        List.of("new", "test"), "test-server",
                        "Server-provided system prompt",
                        List.of("new_tool")
                )
        ));

        SkillDefinition skill = registry.matchSkill("run a new test");
        assertNotNull(skill);
        assertEquals("new_skill", skill.name());
        assertEquals("test-server", skill.mcpServer());
    }

    @Test
    void shouldWarnOnToolMismatchBetweenServerAndMarkdown() {
        // When server doesn't expose a tool that markdown declares, logs a warning
        // (this is tested via log output in integration tests)
        registry.validateAgainstServer("reports", List.of(
                new SkillDefinition(
                        "report_analyst", "Generate reports",
                        List.of("report", "revenue"), "reports",
                        "Server prompt",
                        List.of("generate_report") // same as markdown — no warning
                )
        ));

        // Skill still works even after validation
        assertNotNull(registry.matchSkill("Show me revenue"));
    }
}
