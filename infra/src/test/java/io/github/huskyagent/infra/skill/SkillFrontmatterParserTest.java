package io.github.huskyagent.infra.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkillFrontmatterParserTest {

    private final SkillFrontmatterParser parser = new SkillFrontmatterParser();

    @Test
    void parseFullFrontmatter() {
        String raw = """
            ---
            name: web_research
            description: Deep web research
            requires_toolsets: [WEB, SEARCH]
            platforms: [macos, linux]
            ---

            # Web Research

            ## Procedure
            1. Search
            """;

        Skill skill = parser.parse("web_research.md", raw);
        assertEquals("web_research", skill.name());
        assertEquals("Deep web research", skill.description());
        assertEquals(2, skill.requiresToolsets().size());
        assertTrue(skill.requiresToolsets().contains(io.github.huskyagent.infra.tool.Toolset.WEB));
        assertTrue(skill.requiresToolsets().contains(io.github.huskyagent.infra.tool.Toolset.SEARCH));
        assertEquals(2, skill.platforms().size());
        assertTrue(skill.platforms().contains("macos"));
        assertTrue(skill.platforms().contains("linux"));
        assertTrue(skill.content().startsWith("# Web Research"));
    }

    @Test
    void parseMinimalFrontmatter() {
        String raw = """
            ---
            name: simple
            description: A simple skill
            ---

            Simple content.
            """;

        Skill skill = parser.parse("simple.md", raw);
        assertEquals("simple", skill.name());
        assertEquals("A simple skill", skill.description());
        assertTrue(skill.requiresToolsets().isEmpty());
        assertTrue(skill.platforms().isEmpty());
    }

    @Test
    void parseMissingFrontmatterThrows() {
        String raw = "No frontmatter here, just plain text.";
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("bad.md", raw));
    }

    @Test
    void parseMissingRequiredFieldThrows() {
        String raw = """
            ---
            name: has_name
            ---

            Content.
            """;
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("no_desc.md", raw));
    }

    @Test
    void parseUnknownToolsetIgnored() {
        String raw = """
            ---
            name: test
            description: test skill
            requires_toolsets: [WEB, FAKE_TOOLSET]
            ---

            Content.
            """;

        Skill skill = parser.parse("test.md", raw);
        assertEquals(1, skill.requiresToolsets().size());
        assertTrue(skill.requiresToolsets().contains(io.github.huskyagent.infra.tool.Toolset.WEB));
    }
}