package io.github.huskyagent.domain.prompt;

import io.github.huskyagent.domain.prompt.section.ToolUseEnforcementSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolUseEnforcementSectionTest {

    private final PromptContext context = PromptContext.of("test-session", null);

    @Test
    void autoMode_injectsForGpt() {
        ToolUseEnforcementSection section = new ToolUseEnforcementSection("gpt-4o", "auto");
        String result = section.build(context);
        assertFalse(result.isEmpty());
        assertTrue(result.contains("Tool-use Enforcement"));
        assertTrue(result.contains("Use tools when the user has asked you to act"));
    }

    @Test
    void autoMode_injectsForGemini() {
        ToolUseEnforcementSection section = new ToolUseEnforcementSection("gemini-2.0-flash", "auto");
        String result = section.build(context);
        assertFalse(result.isEmpty());
        assertTrue(result.contains("Google model operational directives"));
        assertTrue(result.contains("Absolute paths"));
    }

    @Test
    void autoMode_injectsForCodex() {
        ToolUseEnforcementSection section = new ToolUseEnforcementSection("codex-mini", "auto");
        String result = section.build(context);
        assertFalse(result.isEmpty());
        assertTrue(result.contains("Execution discipline"));
        assertTrue(result.contains("tool_persistence"));
    }

    @Test
    void autoMode_skipsForClaude() {
        ToolUseEnforcementSection section = new ToolUseEnforcementSection("claude-sonnet-4", "auto");
        String result = section.build(context);
        assertTrue(result.isEmpty());
    }

    @Test
    void trueForcesForAllModels() {
        ToolUseEnforcementSection section = new ToolUseEnforcementSection("claude-sonnet-4", true);
        String result = section.build(context);
        assertFalse(result.isEmpty());
        assertTrue(result.contains("Use tools when the user has asked you to act"));
    }

    @Test
    void falseDisablesEvenForGpt() {
        ToolUseEnforcementSection section = new ToolUseEnforcementSection("gpt-4o", false);
        String result = section.build(context);
        assertTrue(result.isEmpty());
    }

    @Test
    void customListMatches() {
        ToolUseEnforcementSection section = new ToolUseEnforcementSection("my-custom-model-v2", List.of("custom", "my-model"));
        String result = section.build(context);
        assertFalse(result.isEmpty());
    }

    @Test
    void stringAlwaysInjects() {
        ToolUseEnforcementSection section = new ToolUseEnforcementSection("claude-sonnet-4", "always");
        assertFalse(section.build(context).isEmpty());

        section = new ToolUseEnforcementSection("claude-sonnet-4", "yes");
        assertFalse(section.build(context).isEmpty());

        section = new ToolUseEnforcementSection("claude-sonnet-4", "on");
        assertFalse(section.build(context).isEmpty());
    }

    @Test
    void stringNeverDisables() {
        ToolUseEnforcementSection section = new ToolUseEnforcementSection("gpt-4o", "never");
        assertTrue(section.build(context).isEmpty());

        section = new ToolUseEnforcementSection("gpt-4o", "no");
        assertTrue(section.build(context).isEmpty());

        section = new ToolUseEnforcementSection("gpt-4o", "off");
        assertTrue(section.build(context).isEmpty());
    }

    @Test
    void openaiModelGetsExecutionDiscipline() {
        ToolUseEnforcementSection section = new ToolUseEnforcementSection("gpt-4o", "auto");
        String result = section.build(context);
        assertTrue(result.contains("<tool_persistence>"));
        assertTrue(result.contains("<mandatory_tool_use>"));
        assertTrue(result.contains("<act_dont_ask>"));
        assertTrue(result.contains("<verification>"));
        assertFalse(result.contains("Google model operational"));
    }

    @Test
    void googleModelGetsOperationalGuidance() {
        ToolUseEnforcementSection section = new ToolUseEnforcementSection("gemini-pro", "auto");
        String result = section.build(context);
        assertTrue(result.contains("Absolute paths"));
        assertTrue(result.contains("Parallel tool calls"));
        assertFalse(result.contains("<tool_persistence>"));
    }

    @Test
    void guidanceSeparatesDiscussionFromFileMutation() {
        ToolUseEnforcementSection section = new ToolUseEnforcementSection("gpt-4o", "auto");
        String result = section.build(context);

        assertTrue(result.contains("Do not turn exploratory conversation into file changes"));
        assertTrue(result.contains("Before creating, modifying, deleting, moving, overwriting"));
        assertTrue(result.contains("Reading files, searching code, checking status, and running targeted tests are low-risk actions"));
    }

    @Test
    void openaiGuidanceDoesNotMandateFileWrites() {
        ToolUseEnforcementSection section = new ToolUseEnforcementSection("gpt-4o", "auto");
        String result = section.build(context);

        assertTrue(result.contains("File writes are not mandatory just because they are possible"));
        assertTrue(result.contains("Do not apply this rule to file creation/modification"));
        assertFalse(result.contains("Small file replacements → use edit_file; multi-line or multi-file edits"));
    }

    @Test
    void priority510() {
        ToolUseEnforcementSection section = new ToolUseEnforcementSection("gpt-4o", "auto");
        assertEquals(510, section.getPriority());
        assertEquals("tool_use_enforcement", section.getName());
    }
}
