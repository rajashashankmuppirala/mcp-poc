package com.example.gateway.service;

import com.example.gateway.model.SkillDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Loads skill definitions from Markdown files with YAML frontmatter at startup.
 * Each file in the skills directory represents one agent/skill.
 *
 * Skills can optionally be validated against MCP server capabilities (GET /skills)
 * at startup to detect mismatches between declared and available tools.
 */
@Service
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final List<SkillDefinition> skills = new ArrayList<>();
    private final Map<String, SkillDefinition> skillByName = new HashMap<>();
    private final List<String> defaultFallback = List.of();

    public SkillRegistry(
            @Value("${skills.config-dir:classpath:skills/*.md}") String configDirPattern) {
        loadSkills(configDirPattern);
    }

    private void loadSkills(String pattern) {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(pattern);

            for (Resource resource : resources) {
                if (!resource.getFilename().endsWith(".md")) continue;
                try {
                    SkillDefinition skill = parseMarkdown(resource);
                    if (skill != null) {
                        skills.add(skill);
                        skillByName.put(skill.name(), skill);
                    }
                } catch (Exception e) {
                    log.warn("Failed to load skill from {}: {}", resource.getFilename(), e.getMessage());
                }
            }

            log.info("Loaded {} skills: {}", skills.size(),
                    skills.stream().map(SkillDefinition::name).toList());
        } catch (IOException e) {
            log.warn("Failed to load skills from {}: {}", pattern, e.getMessage());
        }
    }

    /**
     * Validate local skills against server-discovered capabilities.
     * Called by ToolDiscoveryInitializer after fetching GET /skills from each MCP server.
     * Logs warnings if local skill's allowed_tools are not present on the server.
     */
    public void validateAgainstServer(String serverId, List<com.example.gateway.model.SkillDefinition> serverSkills) {
        for (var serverSkill : serverSkills) {
            SkillDefinition local = skillByName.get(serverSkill.name());
            if (local == null) {
                log.info("Server '{}' exposes skill '{}' which is not defined locally — adding it",
                        serverId, serverSkill.name());
                skills.add(serverSkill);
                skillByName.put(serverSkill.name(), serverSkill);
                continue;
            }

            // Validate allowed_tools overlap
            Set<String> localTools = new HashSet<>(local.allowedTools());
            Set<String> serverTools = new HashSet<>(serverSkill.allowedTools());
            Set<String> missing = new HashSet<>(localTools);
            missing.removeAll(serverTools);

            if (!missing.isEmpty()) {
                log.warn("Skill '{}' on server '{}' does not expose tools declared in markdown: {}",
                        local.name(), serverId, missing);
            }

            Set<String> extra = new HashSet<>(serverTools);
            extra.removeAll(localTools);
            if (!extra.isEmpty()) {
                log.info("Skill '{}' on server '{}' exposes additional tools not in markdown: {}",
                        local.name(), serverId, extra);
            }
        }
    }

    /**
     * Parse a markdown file with YAML frontmatter.
     * Format:
     *   ---
     *   name: foo
     *   triggers: [a, b, c]
     *   ---
     *   Markdown body becomes the system prompt.
     */
    private SkillDefinition parseMarkdown(Resource resource) throws IOException {
        String content;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            content = reader.lines().collect(Collectors.joining("\n"));
        }

        // Must start and end with --- delimiter
        if (!content.startsWith("---")) return null;

        int endDelimiter = content.indexOf("\n---", 3);
        if (endDelimiter == -1) return null;

        String frontmatter = content.substring(3, endDelimiter).trim();
        String body = content.substring(endDelimiter + 4).trim();

        // Parse frontmatter line by line
        Map<String, String> values = new HashMap<>();
        Map<String, List<String>> listValues = new HashMap<>();
        String currentListKey = null;
        List<String> currentList = null;

        for (String rawLine : frontmatter.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            // Check if this is a list item continuation
            if (currentList != null && line.startsWith("- ")) {
                currentList.add(line.substring(2).trim());
                continue;
            }

            // Flush previous list
            if (currentList != null && currentListKey != null) {
                listValues.put(currentListKey, currentList);
                currentList = null;
                currentListKey = null;
            }

            // New key: value pair
            int colonIdx = line.indexOf(':');
            if (colonIdx == -1) continue;

            String key = line.substring(0, colonIdx).trim();
            String value = line.substring(colonIdx + 1).trim();

            // Inline list: [a, b, c]
            if (value.startsWith("[") && value.endsWith("]")) {
                List<String> items = Arrays.stream(value.substring(1, value.length() - 1).split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                listValues.put(key, items);
                continue;
            }

            // Quoted or unquoted scalar value
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(key, value);
            currentListKey = key;
            currentList = new ArrayList<>();
        }

        // Flush last list
        if (currentList != null && currentListKey != null) {
            listValues.put(currentListKey, currentList);
        }

        return new SkillDefinition(
                values.get("name"),
                values.get("description"),
                listValues.getOrDefault("triggers", List.of()),
                values.get("mcp_server"),
                body,
                listValues.getOrDefault("allowed_tools", List.of())
        );
    }

    /**
     * Match a user prompt to the best skill by keyword matching.
     * Returns null if no skill matches.
     */
    public SkillDefinition matchSkill(String prompt) {
        if (prompt == null || prompt.isBlank()) return null;

        String lower = prompt.toLowerCase();
        for (SkillDefinition skill : skills) {
            for (String trigger : skill.triggers()) {
                if (lower.contains(trigger)) {
                    log.debug("Prompt matched skill '{}' via trigger '{}'", skill.name(), trigger);
                    return skill;
                }
            }
        }
        log.debug("No skill matched for prompt: {}", prompt);
        return null;
    }

    /**
     * Get all skills mapped to a given MCP server.
     */
    public List<SkillDefinition> getSkillsForServer(String serverId) {
        return skills.stream()
                .filter(s -> serverId.equals(s.mcpServer()))
                .toList();
    }

    /**
     * Get the system prompt for a skill by name.
     */
    public String getSkillPrompt(String skillName) {
        SkillDefinition skill = skillByName.get(skillName);
        return skill != null ? skill.systemPrompt() : null;
    }

    /**
     * Get the allowed tool names for a skill by name.
     */
    public List<String> getAllowedTools(String skillName) {
        SkillDefinition skill = skillByName.get(skillName);
        return skill != null ? new ArrayList<>(skill.allowedTools()) : defaultFallback;
    }

    /**
     * Get all loaded skills.
     */
    public List<SkillDefinition> getAllSkills() {
        return List.copyOf(skills);
    }

    /**
     * Get all unique trigger keywords across all skills.
     */
    public Set<String> getAllTriggers() {
        Set<String> triggers = new LinkedHashSet<>();
        for (SkillDefinition skill : skills) {
            triggers.addAll(skill.triggers());
        }
        return triggers;
    }
}
