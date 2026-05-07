package com.example.ops.controller;

import com.example.ops.model.SkillDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillsControllerTest {

    private final SkillsController controller = new SkillsController();

    @Test
    void shouldReturnSkills() {
        List<SkillDefinition> skills = controller.getSkills();

        assertEquals(1, skills.size());
        assertEquals("operations_monitor", skills.get(0).name());
    }

    @Test
    void shouldReturnCorrectTools() {
        List<SkillDefinition> skills = controller.getSkills();
        SkillDefinition opsSkill = skills.get(0);

        assertEquals("Monitor system operations: failed jobs, dataflow status", opsSkill.description());
        assertTrue(opsSkill.triggers().contains("failed"));
        assertTrue(opsSkill.triggers().contains("dataflow"));
        assertEquals(List.of("list_failed_jobs", "list_successful_dataflows"), opsSkill.allowedTools());
    }
}
