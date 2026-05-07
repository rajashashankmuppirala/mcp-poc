package com.example.ops.controller;

import com.example.ops.model.SkillDefinition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes skill metadata for this MCP server.
 */
@RestController
public class SkillsController {

    @GetMapping("/skills")
    public List<SkillDefinition> getSkills() {
        return List.of(
                new SkillDefinition(
                        "operations_monitor",
                        "Monitor system operations: failed jobs, dataflow status",
                        List.of("job", "failed", "failure", "dataflow", "pipeline", "operations", "monitor", "status"),
                        List.of("list_failed_jobs", "list_successful_dataflows")
                )
        );
    }
}
