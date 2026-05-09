package io.github.huskyagent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.huskyagent.domain.capability.CapabilityView;
import io.github.huskyagent.domain.scene.SceneConfig;
import io.github.huskyagent.infra.memory.BuiltinMemoryProvider;
import io.github.huskyagent.infra.memory.SessionMemoryProvider;
import io.github.huskyagent.infra.mcp.McpToolProvider;
import io.github.huskyagent.infra.skill.Skill;
import io.github.huskyagent.infra.skill.SkillManager;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.approval.ApprovalRequest;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityVisibilityResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private List<ToolDefinition> candidateTools = List.of();

    // --- helpers ---

    private CapabilityVisibilityResolver resolver(ToolDefinition... tools) {
        candidateTools = List.of(tools);
        return new CapabilityVisibilityResolver(new SkillManager());
    }

    private CapabilityVisibilityResolver resolver(SkillManager skillManager, ToolDefinition... tools) {
        candidateTools = List.of(tools);
        return new CapabilityVisibilityResolver(skillManager);
    }

    private CapabilityView resolve(CapabilityVisibilityResolver resolver, SceneConfig scene, ToolDefinition... tools) {
        return resolver.resolve(scene, tools.length > 0 ? List.of(tools) : candidateTools);
    }

    private ToolDefinition tool(String name, Toolset toolset) {
        return ToolDefinition.of(name, name, toolset, MAPPER.createObjectNode(), args -> null);
    }

    private ToolDefinition approvalTool(String name, Toolset toolset) {
        return ToolDefinition.withApproval(name, name, toolset, MAPPER.createObjectNode(),
                args -> null,
                args -> ApprovalRequest.of("req-id", name, args, "requires approval", "session"));
    }

    private ToolDefinition mcpTool(String serverName, String toolName) {
        return tool(McpToolProvider.prefixName(serverName, toolName), Toolset.MCP);
    }

    private SceneConfig scene() {
        SceneConfig s = new SceneConfig();
        s.setSceneId("test");
        return s;
    }

    // --- Group 1: Toolset filter ---

    @Test
    void coreToolsetReturnsOnlyCoreTools() {
        CapabilityVisibilityResolver r = resolver(tool("core1", Toolset.CORE), tool("terminal1", Toolset.TERMINAL));
        SceneConfig s = scene();
        s.setAllowedToolsets(Set.of(Toolset.CORE));

        CapabilityView view = resolve(r, s);

        assertTrue(view.getVisibleToolNames().contains("core1"));
        assertFalse(view.getVisibleToolNames().contains("terminal1"));
    }

    @Test
    void emptyAllowedToolsetsReturnsAll() {
        CapabilityVisibilityResolver r = resolver(tool("a", Toolset.CORE), tool("b", Toolset.TERMINAL));
        SceneConfig s = scene();
        s.setAllowedToolsets(null);

        CapabilityView view = resolve(r, s);

        assertTrue(view.getVisibleToolNames().containsAll(Set.of("a", "b")));
    }

    @Test
    void multipleToolsetsReturnsBoth() {
        CapabilityVisibilityResolver r = resolver(
                tool("c", Toolset.CORE), tool("s", Toolset.SEARCH), tool("t", Toolset.TERMINAL));
        SceneConfig s = scene();
        s.setAllowedToolsets(Set.of(Toolset.CORE, Toolset.SEARCH));

        CapabilityView view = resolve(r, s);

        assertTrue(view.getVisibleToolNames().containsAll(Set.of("c", "s")));
        assertFalse(view.getVisibleToolNames().contains("t"));
        assertTrue(view.getVisibleToolsets().containsAll(Set.of(Toolset.CORE, Toolset.SEARCH)));
    }

    // --- Group 2: allowedTools / deniedTools ---

    @Test
    void allowedToolsFiltersToWhitelist() {
        CapabilityVisibilityResolver r = resolver(tool("tool_a", Toolset.CORE), tool("tool_b", Toolset.CORE));
        SceneConfig s = scene();
        s.setAllowedToolsets(Set.of(Toolset.CORE));
        s.setAllowedTools(Set.of("tool_a"));

        CapabilityView view = resolve(r, s);

        assertTrue(view.getVisibleToolNames().contains("tool_a"));
        assertFalse(view.getVisibleToolNames().contains("tool_b"));
    }

    @Test
    void deniedToolsExcludesBlacklist() {
        CapabilityVisibilityResolver r = resolver(tool("tool_a", Toolset.CORE), tool("tool_b", Toolset.CORE));
        SceneConfig s = scene();
        s.setAllowedToolsets(Set.of(Toolset.CORE));
        s.setDeniedTools(Set.of("tool_b"));

        CapabilityView view = resolve(r, s);

        assertTrue(view.getVisibleToolNames().contains("tool_a"));
        assertFalse(view.getVisibleToolNames().contains("tool_b"));
    }

    @Test
    void combinedAllowedAndDenied() {
        CapabilityVisibilityResolver r = resolver(tool("a", Toolset.CORE), tool("b", Toolset.CORE));
        SceneConfig s = scene();
        s.setAllowedTools(Set.of("a", "b"));
        s.setDeniedTools(Set.of("b"));

        CapabilityView view = resolve(r, s);

        assertTrue(view.getVisibleToolNames().contains("a"));
        assertFalse(view.getVisibleToolNames().contains("b"));
    }

    // --- Group 3: MCP server filter ---

    @Test
    void mcpAllowedServersFilters() {
        CapabilityVisibilityResolver r = resolver(
                mcpTool("server1", "do_thing"), mcpTool("server2", "other_thing"));
        SceneConfig s = scene();
        s.setAllowedMcpServers(Set.of("server1"));

        CapabilityView view = resolve(r, s);

        assertTrue(view.getVisibleToolNames().contains(McpToolProvider.prefixName("server1", "do_thing")));
        assertFalse(view.getVisibleToolNames().contains(McpToolProvider.prefixName("server2", "other_thing")));
    }

    @Test
    void mcpDeniedServersFilters() {
        CapabilityVisibilityResolver r = resolver(
                mcpTool("server1", "do_thing"), mcpTool("server2", "other_thing"));
        SceneConfig s = scene();
        s.setDeniedMcpServers(Set.of("server2"));

        CapabilityView view = resolve(r, s);

        assertTrue(view.getVisibleToolNames().contains(McpToolProvider.prefixName("server1", "do_thing")));
        assertFalse(view.getVisibleToolNames().contains(McpToolProvider.prefixName("server2", "other_thing")));
    }

    @Test
    void nonMcpToolIgnoresMcpFilter() {
        CapabilityVisibilityResolver r = resolver(tool("core_tool", Toolset.CORE), mcpTool("server1", "mcp_tool"));
        SceneConfig s = scene();
        s.setAllowedMcpServers(Set.of("server2")); // server1 not allowed

        CapabilityView view = resolve(r, s);

        assertTrue(view.getVisibleToolNames().contains("core_tool"));
        assertFalse(view.getVisibleToolNames().contains(McpToolProvider.prefixName("server1", "mcp_tool")));
    }

    // --- Group 4: stripApproval ---

    @Test
    void approvalNoneStripsApprovalFlag() {
        CapabilityVisibilityResolver r = resolver(approvalTool("dangerous", Toolset.TERMINAL));
        SceneConfig s = scene();
        s.setApprovalPolicy(SceneConfig.ApprovalPolicy.NONE);

        CapabilityView view = resolve(r, s);

        assertTrue(view.isStripApproval());
        ToolDefinition tool = view.getVisibleTools().stream()
                .filter(t -> t.name().equals("dangerous")).findFirst().orElseThrow();
        assertFalse(tool.requiresApproval());
    }

    @Test
    void approvalRequiredPreservesFlag() {
        CapabilityVisibilityResolver r = resolver(approvalTool("dangerous", Toolset.TERMINAL));
        SceneConfig s = scene();
        s.setApprovalPolicy(SceneConfig.ApprovalPolicy.REQUIRED);

        CapabilityView view = resolve(r, s);

        assertFalse(view.isStripApproval());
        ToolDefinition tool = view.getVisibleTools().stream()
                .filter(t -> t.name().equals("dangerous")).findFirst().orElseThrow();
        assertTrue(tool.requiresApproval());
    }

    @Test
    void chatbotLikeSceneWithApprovalNoneDoesNotExposeTerminalTools() {
        CapabilityVisibilityResolver r = resolver(
                tool("web_search", Toolset.WEB),
                approvalTool("terminal", Toolset.TERMINAL),
                tool("process", Toolset.TERMINAL));
        SceneConfig s = scene();
        s.setAllowedToolsets(Set.of(Toolset.CORE, Toolset.SKILLS, Toolset.SEARCH, Toolset.WEB, Toolset.KNOWLEDGE, Toolset.MCP));
        s.setDeniedTools(Set.of("terminal", "process"));
        s.setApprovalPolicy(SceneConfig.ApprovalPolicy.NONE);

        CapabilityView view = resolve(r, s);

        assertTrue(view.getVisibleToolNames().contains("web_search"));
        assertFalse(view.getVisibleToolNames().contains("terminal"));
        assertFalse(view.getVisibleToolNames().contains("process"));
    }

    // --- Group 5: Skill filter ---

    @Test
    void skillIdsFilterReducesSkills() {
        SkillManager sm = new SkillManager();
        sm.setSkills(List.of(
                Skill.ofSimple("skill_a", "desc_a", Set.of(), Set.of(), "content_a"),
                Skill.ofSimple("skill_b", "desc_b", Set.of(), Set.of(), "content_b")));
        CapabilityVisibilityResolver r = resolver(sm);
        SceneConfig s = scene();
        s.setSkillIds(Set.of("skill_a"));

        CapabilityView view = resolve(r, s);

        assertTrue(view.getVisibleSkillNames().contains("skill_a"));
        assertFalse(view.getVisibleSkillNames().contains("skill_b"));
    }

    @Test
    void emptySkillIdsReturnsAllActiveSkills() {
        SkillManager sm = new SkillManager();
        sm.setSkills(List.of(
                Skill.ofSimple("skill_a", "desc", Set.of(), Set.of(), "content"),
                Skill.ofSimple("skill_b", "desc", Set.of(), Set.of(), "content")));
        CapabilityVisibilityResolver r = resolver(sm);
        SceneConfig s = scene();
        // skillIds null or empty → all active skills

        CapabilityView view = resolve(r, s);

        assertTrue(view.getVisibleSkillNames().containsAll(Set.of("skill_a", "skill_b")));
    }

    @Test
    void skillRequiresUnavailableToolsetIsExcluded() {
        SkillManager sm = new SkillManager();
        // skill_terminal requires TERMINAL toolset
        sm.setSkills(List.of(Skill.ofSimple("skill_terminal", "desc", Set.of(Toolset.TERMINAL), Set.of(), "content")));
        CapabilityVisibilityResolver r = resolver(sm, tool("core1", Toolset.CORE));
        SceneConfig s = scene();
        s.setAllowedToolsets(Set.of(Toolset.CORE)); // no TERMINAL

        CapabilityView view = resolve(r, s);

        assertFalse(view.getVisibleSkillNames().contains("skill_terminal"),
                "skill requiring TERMINAL should be inactive when TERMINAL not in visibleToolsets");
    }

    // --- Group 6: fingerprint uniqueness ---

    @Test
    void differentToolsetsProduceDifferentFingerprint() {
        CapabilityVisibilityResolver r = resolver(
                tool("a", Toolset.CORE), tool("b", Toolset.SEARCH));
        SceneConfig s1 = scene();
        s1.setAllowedToolsets(Set.of(Toolset.CORE));
        SceneConfig s2 = scene();
        s2.setAllowedToolsets(Set.of(Toolset.CORE, Toolset.SEARCH));

        assertNotEquals(resolve(r, s1).fingerprint(), resolve(r, s2).fingerprint());
    }

    @Test
    void differentDeniedToolsProduceDifferentFingerprint() {
        CapabilityVisibilityResolver r = resolver(tool("a", Toolset.CORE), tool("b", Toolset.CORE));
        SceneConfig s1 = scene();
        s1.setAllowedToolsets(Set.of(Toolset.CORE));
        SceneConfig s2 = scene();
        s2.setAllowedToolsets(Set.of(Toolset.CORE));
        s2.setDeniedTools(Set.of("b"));

        assertNotEquals(resolve(r, s1).fingerprint(), resolve(r, s2).fingerprint());
    }

    @Test
    void sameConfigProducesSameFingerprint() {
        CapabilityVisibilityResolver r = resolver(tool("a", Toolset.CORE));
        SceneConfig s1 = scene();
        s1.setAllowedToolsets(Set.of(Toolset.CORE));
        SceneConfig s2 = scene();
        s2.setAllowedToolsets(Set.of(Toolset.CORE));

        assertEquals(resolve(r, s1).fingerprint(), resolve(r, s2).fingerprint());
    }

    // --- Group 7: Memory tools visibility ---

    @Test
    void memoryDisabledHidesAllMemoryTools() {
        CapabilityVisibilityResolver r = resolver(
                tool("memory_read", Toolset.MEMORY),
                tool("session_search", Toolset.MEMORY),
                tool("core_tool", Toolset.CORE));
        SceneConfig s = scene();
        s.setAllowedToolsets(Set.of(Toolset.CORE, Toolset.MEMORY));
        SceneConfig.MemoryPolicySpec mem = new SceneConfig.MemoryPolicySpec();
        mem.setEnabled(false);
        s.setMemoryPolicyConfig(mem);

        CapabilityView view = resolve(r, s);

        assertFalse(view.getVisibleToolNames().contains("memory_read"));
        assertFalse(view.getVisibleToolNames().contains("session_search"));
        assertTrue(view.getVisibleToolNames().contains("core_tool"), "non-memory tools unaffected");
    }

    @Test
    void memoryAccessDisabledHidesAllMemoryTools() {
        CapabilityVisibilityResolver r = resolver(
                tool("memory_read", Toolset.MEMORY),
                tool("session_search", Toolset.MEMORY),
                tool("core_tool", Toolset.CORE));
        SceneConfig s = scene();
        s.setAllowedToolsets(Set.of(Toolset.CORE, Toolset.MEMORY));
        SceneConfig.MemoryPolicySpec mem = new SceneConfig.MemoryPolicySpec();
        mem.setEnabled(true);
        mem.setAccess(SceneConfig.MemoryAccess.DISABLED);
        s.setMemoryPolicyConfig(mem);

        CapabilityView view = resolve(r, s);

        assertFalse(view.getVisibleToolNames().contains("memory_read"));
        assertFalse(view.getVisibleToolNames().contains("session_search"));
        assertTrue(view.getVisibleToolNames().contains("core_tool"));
    }

    @Test
    void builtinNotInProviderWhitelistHidesBuiltinTools() {
        CapabilityVisibilityResolver r = resolver(
                tool("memory_read", Toolset.MEMORY),
                tool("user_write", Toolset.MEMORY),
                tool("session_search", Toolset.MEMORY));
        SceneConfig s = scene();
        s.setAllowedToolsets(Set.of(Toolset.MEMORY));
        SceneConfig.MemoryPolicySpec mem = new SceneConfig.MemoryPolicySpec();
        mem.setEnabled(true);
        mem.setProviders(Set.of(SessionMemoryProvider.NAME));
        s.setMemoryPolicyConfig(mem);

        CapabilityView view = resolve(r, s);

        assertFalse(view.getVisibleToolNames().contains("memory_read"), "memory_read should be hidden");
        assertFalse(view.getVisibleToolNames().contains("user_write"), "user_write should be hidden");
        assertTrue(view.getVisibleToolNames().contains("session_search"), "session_search should be visible");
    }

    @Test
    void sessionNotInProviderWhitelistHidesSessionSearch() {
        CapabilityVisibilityResolver r = resolver(
                tool("memory_read", Toolset.MEMORY),
                tool("session_search", Toolset.MEMORY));
        SceneConfig s = scene();
        s.setAllowedToolsets(Set.of(Toolset.MEMORY));
        SceneConfig.MemoryPolicySpec mem = new SceneConfig.MemoryPolicySpec();
        mem.setEnabled(true);
        mem.setProviders(Set.of(BuiltinMemoryProvider.NAME));
        s.setMemoryPolicyConfig(mem);

        CapabilityView view = resolve(r, s);

        assertTrue(view.getVisibleToolNames().contains("memory_read"), "memory_read should be visible");
        assertFalse(view.getVisibleToolNames().contains("session_search"), "session_search should be hidden");
    }

    @Test
    void emptyProviderWhitelistShowsAllMemoryTools() {
        CapabilityVisibilityResolver r = resolver(
                tool("memory_read", Toolset.MEMORY),
                tool("session_search", Toolset.MEMORY));
        SceneConfig s = scene();
        s.setAllowedToolsets(Set.of(Toolset.MEMORY));
        SceneConfig.MemoryPolicySpec mem = new SceneConfig.MemoryPolicySpec();
        mem.setEnabled(true);
        mem.setProviders(Set.of()); // empty = no restriction
        s.setMemoryPolicyConfig(mem);

        CapabilityView view = resolve(r, s);

        assertTrue(view.getVisibleToolNames().containsAll(Set.of("memory_read", "session_search")));
    }

    @Test
    void nonMemoryToolsUnaffectedByMemoryPolicy() {
        CapabilityVisibilityResolver r = resolver(
                tool("core_tool", Toolset.CORE),
                tool("memory_read", Toolset.MEMORY));
        SceneConfig s = scene();
        s.setAllowedToolsets(Set.of(Toolset.CORE, Toolset.MEMORY));
        SceneConfig.MemoryPolicySpec mem = new SceneConfig.MemoryPolicySpec();
        mem.setEnabled(false);
        s.setMemoryPolicyConfig(mem);

        CapabilityView view = resolve(r, s);

        assertTrue(view.getVisibleToolNames().contains("core_tool"), "CORE tools unaffected");
        assertFalse(view.getVisibleToolNames().contains("memory_read"));
    }

    @Test
    void memoryToolsHiddenWhenToolsetNotInAllowedToolsets() {
        CapabilityVisibilityResolver r = resolver(
                tool("core_tool", Toolset.CORE),
                tool("memory_read", Toolset.MEMORY));
        SceneConfig s = scene();
        s.setAllowedToolsets(Set.of(Toolset.CORE)); // MEMORY not in allowed toolsets

        CapabilityView view = resolve(r, s);

        assertFalse(view.getVisibleToolNames().contains("memory_read"),
                "toolset filter should exclude memory tools without needing memory policy filter");
        assertTrue(view.getVisibleToolNames().contains("core_tool"));
    }

    @Test
    void resolveSubAgentFiltersByParentAndScenePolicies() {
        ToolDefinition core = tool("core_tool", Toolset.CORE);
        ToolDefinition web = tool("web_tool", Toolset.WEB);
        ToolDefinition terminal = tool("terminal", Toolset.TERMINAL);
        ToolDefinition delegate = tool("delegate_task", Toolset.DELEGATE);
        CapabilityVisibilityResolver r = resolver(core, web, terminal, delegate);
        SceneConfig s = scene();
        s.setAllowedToolsets(Set.of(Toolset.CORE, Toolset.WEB, Toolset.DELEGATE));
        s.setDeniedTools(Set.of("web_tool"));

        CapabilityView view = r.resolveSubAgent(s, parentView(core, web, terminal, delegate));

        assertTrue(view.getVisibleToolNames().contains("core_tool"));
        assertFalse(view.getVisibleToolNames().contains("web_tool"));
        assertFalse(view.getVisibleToolNames().contains("terminal"));
        assertFalse(view.getVisibleToolNames().contains("delegate_task"));
        assertTrue(view.isStripApproval());
    }

    @Test
    void resolveSubAgentAppliesMcpAndMemoryPolicies() {
        ToolDefinition allowedMcp = mcpTool("server1", "weather");
        ToolDefinition deniedMcp = mcpTool("server2", "secret");
        ToolDefinition memoryRead = tool("memory_read", Toolset.MEMORY);
        CapabilityVisibilityResolver r = resolver(allowedMcp, deniedMcp, memoryRead);
        SceneConfig s = scene();
        s.setAllowedToolsets(Set.of(Toolset.MCP, Toolset.MEMORY));
        s.setAllowedMcpServers(Set.of("server1"));
        SceneConfig.MemoryPolicySpec mem = new SceneConfig.MemoryPolicySpec();
        mem.setEnabled(false);
        s.setMemoryPolicyConfig(mem);

        CapabilityView view = r.resolveSubAgent(s, parentView(allowedMcp, deniedMcp, memoryRead));

        assertTrue(view.getVisibleToolNames().contains(McpToolProvider.prefixName("server1", "weather")));
        assertFalse(view.getVisibleToolNames().contains(McpToolProvider.prefixName("server2", "secret")));
        assertFalse(view.getVisibleToolNames().contains("memory_read"));
    }

    @Test
    void resolveSubAgentStripsApprovalAndInheritsPromptSections() {
        ToolDefinition dangerous = approvalTool("dangerous", Toolset.TERMINAL);
        CapabilityVisibilityResolver r = resolver(dangerous);
        SceneConfig s = scene();
        s.setAllowedToolsets(Set.of(Toolset.TERMINAL));
        CapabilityView parent = CapabilityView.builder()
                .visibleTools(List.of(dangerous))
                .visibleToolNames(Set.of("dangerous"))
                .visibleToolsets(Set.of(Toolset.TERMINAL))
                .visibleSkills(List.of())
                .visibleSkillNames(Set.of())
                .visiblePromptSections(Set.of("tools", "runtime"))
                .build();

        CapabilityView view = r.resolveSubAgent(s, parent);

        ToolDefinition tool = view.getVisibleTools().stream()
                .filter(t -> t.name().equals("dangerous"))
                .findFirst()
                .orElseThrow();
        assertFalse(tool.requiresApproval());
        assertEquals(Set.of("tools", "runtime"), view.getVisiblePromptSections());
    }

    @Test
    void resolveSubAgentSkillsAreIntersectedWithParentSkills() {
        SkillManager sm = new SkillManager();
        Skill coreSkill = Skill.ofSimple("core_skill", "desc", Set.of(Toolset.CORE), Set.of(), "content");
        Skill webSkill = Skill.ofSimple("web_skill", "desc", Set.of(Toolset.WEB), Set.of(), "content");
        sm.setSkills(List.of(coreSkill, webSkill));
        ToolDefinition core = tool("core_tool", Toolset.CORE);
        ToolDefinition web = tool("web_tool", Toolset.WEB);
        CapabilityVisibilityResolver r = resolver(sm, core, web);
        SceneConfig s = scene();
        s.setAllowedToolsets(Set.of(Toolset.CORE, Toolset.WEB));
        CapabilityView parent = CapabilityView.builder()
                .visibleTools(List.of(core, web))
                .visibleToolNames(Set.of("core_tool", "web_tool"))
                .visibleToolsets(Set.of(Toolset.CORE, Toolset.WEB))
                .visibleSkills(List.of(coreSkill))
                .visibleSkillNames(Set.of("core_skill"))
                .visiblePromptSections(Set.of())
                .build();

        CapabilityView view = r.resolveSubAgent(s, parent);

        assertTrue(view.getVisibleSkillNames().contains("core_skill"));
        assertFalse(view.getVisibleSkillNames().contains("web_skill"));
    }

    private CapabilityView parentView(ToolDefinition... tools) {
        List<ToolDefinition> toolList = List.of(tools);
        return CapabilityView.builder()
                .sceneId("parent")
                .visibleTools(toolList)
                .visibleToolNames(toolList.stream().map(ToolDefinition::name).collect(java.util.stream.Collectors.toSet()))
                .visibleToolsets(toolList.stream().map(ToolDefinition::toolset).collect(java.util.stream.Collectors.toSet()))
                .visibleSkills(List.of())
                .visibleSkillNames(Set.of())
                .visiblePromptSections(Set.of())
                .build();
    }
}
