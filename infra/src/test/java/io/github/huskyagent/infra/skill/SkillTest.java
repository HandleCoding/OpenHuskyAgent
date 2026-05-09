package io.github.huskyagent.infra.skill;

import io.github.huskyagent.infra.tool.Toolset;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SkillTest {

    @Test
    void summaryFormat() {
        Skill skill = Skill.ofSimple("test", "Test description", Set.of(), Set.of(), "content");
        assertEquals("- **test**: Test description", skill.summary());
    }

    @Test
    void matchesPlatformEmpty() {
        Skill skill = Skill.ofSimple("test", "desc", Set.of(), Set.of(), "content");
        assertTrue(skill.matchesPlatform());
    }

    @Test
    void requiresToolsetsSatisfiedEmpty() {
        Skill skill = Skill.ofSimple("test", "desc", Set.of(), Set.of(), "content");
        assertTrue(skill.requiresToolsetsSatisfied(Set.of()));
        assertTrue(skill.requiresToolsetsSatisfied(null));
    }

    @Test
    void requiresToolsetsSatisfiedWithRequirements() {
        Skill skill = Skill.ofSimple("test", "desc", Set.of(Toolset.WEB, Toolset.SEARCH), Set.of(), "content");
        assertTrue(skill.requiresToolsetsSatisfied(Set.of(Toolset.WEB, Toolset.SEARCH, Toolset.CORE)));
        assertFalse(skill.requiresToolsetsSatisfied(Set.of(Toolset.WEB)));
        assertFalse(skill.requiresToolsetsSatisfied(null));
    }

    @Test
    void isActivatable() {
        Skill skill = Skill.ofSimple("test", "desc", Set.of(Toolset.WEB), Set.of(), "content");
        assertTrue(skill.isActivatable(Set.of(Toolset.WEB, Toolset.CORE)));
        assertFalse(skill.isActivatable(Set.of(Toolset.CORE)));
    }

    @Test
    void buildViewOutputSimple() {
        Skill skill = Skill.ofSimple("test", "desc", Set.of(), Set.of(), "Hello skill content");
        String output = skill.buildViewOutput();
        assertTrue(output.startsWith("# Skill: test"));
        assertTrue(output.contains("Hello skill content"));
    }

    @Test
    void buildViewOutputWithLinkedFiles() {
        Skill skill = Skill.ofDirectory("test", "desc", Set.of(), Set.of(), "content",
                Path.of("/skills/test"),
                Map.of("references", java.util.List.of("references/api.md"),
                       "scripts", java.util.List.of("scripts/helper.sh")));
        String output = skill.buildViewOutput();
        assertTrue(output.contains("## Linked Files"));
        assertTrue(output.contains("references"));
        assertTrue(output.contains("references/api.md"));
        assertTrue(output.contains("scripts/helper.sh"));
        assertTrue(output.contains("skill_view"));
    }
}