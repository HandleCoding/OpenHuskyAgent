package io.github.huskyagent.infra.skill;

import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.adapter.ToolExecutionContext;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SkillToolProviderTest {

    private SkillManager skillManager;
    private SkillLoader skillLoader;
    private SkillToolProvider provider;

    @BeforeEach
    void setUp() {
        skillManager = new SkillManager();
        skillManager.setSkills(List.of(
                skill("visible"),
                skill("hidden")
        ));
        skillLoader = new SkillLoader(skillManager, new io.github.huskyagent.infra.config.HuskyDataPaths("target/test-husky-skill-tool"));
        provider = new SkillToolProvider(
                skillManager,
                skillLoader,
                new SkillHubClient(new SkillHubConfig()));
    }

    @Test
    void skillListUsesExecutionContextVisibleSkills() {
        ToolResult result = tool("skill_list").execute(Map.of(), context(Set.of("visible")));

        assertTrue(result.success());
        assertTrue(result.content().contains("visible"));
        assertFalse(result.content().contains("hidden"));
    }

    @Test
    void skillListEmptyVisibleSkillNamesReturnsNoSkills() {
        ToolResult result = tool("skill_list").execute(Map.of(), context(Set.of()));

        assertTrue(result.success());
        assertEquals("No skills available.", result.content());
    }

    @Test
    void skillViewRejectsHiddenSkill() {
        ToolResult result = tool("skill_view").execute(Map.of("name", "hidden"), context(Set.of("visible")));

        assertFalse(result.success());
        assertTrue(result.error().contains("not visible"));
    }

    @Test
    void skillViewAllowsVisibleSkill() {
        ToolResult result = tool("skill_view").execute(Map.of("name", "visible"), context(Set.of("visible")));

        assertTrue(result.success());
        assertTrue(result.content().contains("# Skill: visible"));
    }

    @Test
    void skillManagePatchRejectsHiddenSkill() {
        ToolResult result = tool("skill_manage").execute(Map.of(
                "action", "patch",
                "name", "hidden",
                "content", "patched"), context(Set.of("visible")));

        assertFalse(result.success());
        assertTrue(result.error().contains("not visible"));
    }

    @Test
    void skillManageDeleteRejectsHiddenSkill() {
        ToolResult result = tool("skill_manage").execute(Map.of(
                "action", "delete",
                "name", "hidden"), context(Set.of("visible")));

        assertFalse(result.success());
        assertTrue(result.error().contains("not visible"));
    }

    @Test
    void skillListUsesVisibleSkillNamesAsAuthority() {
        ToolExecutionContext inconsistentContext = new ToolExecutionContext(
                "session-1",
                null,
                List.of(),
                Set.of(Toolset.SKILLS),
                Set.of("visible"),
                Set.of());

        ToolResult result = tool("skill_list").execute(Map.of(), inconsistentContext);

        assertTrue(result.success());
        assertTrue(result.content().contains("visible"));
        assertFalse(result.content().contains("hidden"));
    }

    @Test
    void nullExecutionContextKeepsLegacyAllSkillsBehavior() {
        ToolResult result = tool("skill_list").execute(Map.of(), null);

        assertTrue(result.success());
        assertTrue(result.content().contains("visible"));
        assertTrue(result.content().contains("hidden"));
    }

    @Test
    void skillManagePatchFailureReportsWritableDirectories() {
        ToolResult result = tool("skill_manage").execute(Map.of(
                "action", "patch",
                "name", "visible",
                "content", "patched"), context(Set.of("visible")));

        assertFalse(result.success());
        assertTrue(result.error().contains("configured writable directories"));
        assertTrue(result.error().contains(skillLoader.managedSkillRootsDescription()));
    }

    @Test
    void skillManageDescriptionIncludesWritableDirectories() {
        assertTrue(tool("skill_manage").description().contains(skillLoader.managedSkillRootsDescription()));
    }

    private ToolDefinition tool(String name) {
        return provider.getTools().stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private ToolExecutionContext context(Set<String> visibleSkillNames) {
        return new ToolExecutionContext(
                "session-1",
                null,
                List.of(),
                Set.of(Toolset.SKILLS),
                visibleSkillNames,
                Set.of());
    }

    private Skill skill(String name) {
        return Skill.ofDirectory(name, name + " desc", Set.of(), Set.of(), name + " content",
                Path.of("/skills").resolve(name), Map.of());
    }
}
