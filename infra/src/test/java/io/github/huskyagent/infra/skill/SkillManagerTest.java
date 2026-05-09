package io.github.huskyagent.infra.skill;

import io.github.huskyagent.infra.tool.Toolset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SkillManagerTest {

    private SkillManager skillManager;

    @BeforeEach
    void setUp() {
        skillManager = new SkillManager();
        skillManager.setSkills(List.of(
                Skill.ofDirectory("web_research", "Deep web research",
                        Set.of(Toolset.WEB, Toolset.SEARCH), Set.of("macos", "linux"),
                        "content1", Path.of("/skills/web_research"),
                        Map.of("references", List.of("references/report-template.md"))),
                Skill.ofDirectory("code_review", "Code review",
                        Set.of(Toolset.CORE, Toolset.SEARCH), Set.of(),
                        "content2", Path.of("/skills/code_review"),
                        Map.of("references", List.of("references/owasp-quickref.md"))),
                Skill.ofDirectory("debugging", "Systematic debugging",
                        Set.of(Toolset.CORE, Toolset.TERMINAL, Toolset.SEARCH), Set.of("macos", "linux"),
                        "content3", Path.of("/skills/debugging"),
                        Map.of("scripts", List.of("scripts/env-diagnose.sh"),
                               "references", List.of("references/java-exceptions.md"))),
                Skill.ofSimple("simple", "Unconditional skill", Set.of(), Set.of(), "content4")
        ));
    }

    @Test
    void getSkillByName() {
        Skill skill = skillManager.getSkill("web_research");
        assertNotNull(skill);
        assertEquals("Deep web research", skill.description());
    }

    @Test
    void getSkillNotFound() {
        assertNull(skillManager.getSkill("nonexistent"));
    }

    @Test
    void getActiveSkillsWithFullToolsets() {
        Set<Toolset> all = Set.of(Toolset.CORE, Toolset.SEARCH, Toolset.WEB, Toolset.TERMINAL);
        List<Skill> active = skillManager.getActiveSkills(all);
        assertEquals(4, active.size());
    }

    @Test
    void getActiveSkillsWithPartialToolsets() {
        Set<Toolset> chatbot = Set.of(Toolset.CORE, Toolset.SEARCH, Toolset.WEB, Toolset.MCP);
        List<Skill> active = skillManager.getActiveSkills(chatbot);
        assertEquals(3, active.size());
        assertTrue(active.stream().anyMatch(s -> s.name().equals("web_research")));
        assertTrue(active.stream().anyMatch(s -> s.name().equals("code_review")));
        assertTrue(active.stream().anyMatch(s -> s.name().equals("simple")));
        assertFalse(active.stream().anyMatch(s -> s.name().equals("debugging")));
    }

    @Test
    void getActiveSkillsWithEmptyToolsets() {
        Set<Toolset> empty = Set.of();
        List<Skill> active = skillManager.getActiveSkills(empty);
        assertEquals(1, active.size());
        assertEquals("simple", active.get(0).name());
    }

    @Test
    void listSummaries() {
        Set<Toolset> toolsets = Set.of(Toolset.CORE, Toolset.SEARCH, Toolset.WEB);
        String summaries = skillManager.listSummaries(toolsets);
        assertTrue(summaries.contains("web_research"));
        assertTrue(summaries.contains("code_review"));
        assertTrue(summaries.contains("simple"));
        assertFalse(summaries.contains("debugging"));
        assertTrue(summaries.contains("skill_view"));
    }

    @Test
    void listSummariesEmpty() {
        Set<Toolset> toolsets = Set.of();
        String summaries = skillManager.listSummaries(toolsets);
        assertTrue(summaries.contains("simple"));
    }
}