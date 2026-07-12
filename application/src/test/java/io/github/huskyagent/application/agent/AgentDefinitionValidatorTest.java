package io.github.huskyagent.application.agent;

import io.github.huskyagent.domain.agent.AgentDefinition;
import io.github.huskyagent.infra.config.AgentConfig;
import io.github.huskyagent.infra.knowledge.KnowledgeConfig;
import io.github.huskyagent.infra.knowledge.KnowledgeManager;
import io.github.huskyagent.infra.knowledge.KnowledgeProvider;
import io.github.huskyagent.infra.knowledge.KnowledgeQuery;
import io.github.huskyagent.infra.knowledge.KnowledgeResult;
import io.github.huskyagent.infra.llm.LlmClientRegistry;
import io.github.huskyagent.infra.llm.LlmProperties;
import io.github.huskyagent.infra.llm.ModelSelection;
import io.github.huskyagent.infra.mcp.McpConfigLoader;
import io.github.huskyagent.infra.mcp.McpServerConfig;
import io.github.huskyagent.infra.memory.MemoryContext;
import io.github.huskyagent.infra.memory.MemoryManager;
import io.github.huskyagent.infra.memory.MemoryProvider;
import io.github.huskyagent.infra.memory.MemoryResult;
import io.github.huskyagent.infra.memory.MemoryRuntimeStrategyResolver;
import io.github.huskyagent.infra.memory.MemorySearchOptions;
import io.github.huskyagent.infra.skill.Skill;
import io.github.huskyagent.infra.skill.SkillManager;
import io.github.huskyagent.infra.tool.Toolset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDefinitionValidatorTest {

    private SkillManager skillManager;
    private KnowledgeManager knowledgeManager;
    private MemoryManager memoryManager;
    private McpConfigLoader mcpConfigLoader;
    private LlmClientRegistry llmClientRegistry;
    private AgentDefinitionValidator validator;

    @BeforeEach
    void setUp() {
        skillManager = new SkillManager();
        skillManager.setSkills(List.of(skill("research"), skill("deploy")));

        KnowledgeProvider provider = new KnowledgeProvider() {
            @Override
            public String getName() {
                return "docs";
            }

            @Override
            public String getDescription() {
                return "docs";
            }

            @Override
            public Set<String> getSourceIds() {
                return Set.of("docs", "handbook");
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<KnowledgeResult> search(KnowledgeQuery query, Set<String> allowedSourceIds) {
                return List.of();
            }

            @Override
            public java.util.Optional<io.github.huskyagent.infra.knowledge.KnowledgeDocument> fetch(
                    String id, Set<String> allowedSourceIds) {
                return java.util.Optional.empty();
            }
        };
        knowledgeManager = new KnowledgeManager(List.of(provider), new KnowledgeConfig());

        memoryManager = new MemoryManager(new MemoryRuntimeStrategyResolver(List.of()));
        memoryManager.registerProvider(new MemoryProvider() {
            @Override
            public String getName() {
                return "builtin";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public void initialize(MemoryContext context) {
            }

            @Override
            public String buildSystemPrompt() {
                return "";
            }

            @Override
            public MemoryResult prefetch(String query, MemorySearchOptions options) {
                return MemoryResult.empty("builtin");
            }
        });

        mcpConfigLoader = null;
        llmClientRegistry = null;
        rebuildValidator();
    }

    private void rebuildValidator() {
        validator = new AgentDefinitionValidator(
                skillManager,
                knowledgeManager,
                memoryManager,
                providerOf(mcpConfigLoader),
                providerOf(llmClientRegistry));
    }

    @Test
    void acceptsValidAgentWithStarsAndEmptyAllowlists() {
        AgentDefinition agent = baseAgent();
        agent.setSkillIds(Set.of("*"));
        agent.setKnowledgeSources(Set.of());
        agent.setAllowedMcpServers(Set.of("*"));

        assertDoesNotThrow(() -> validator.validate(agent));
    }

    @Test
    void rejectsUnknownSkill() {
        AgentDefinition agent = baseAgent();
        agent.setSkillIds(Set.of("research", "missing-skill"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(agent));
        assertTrue(error.getMessage().contains("unknown skill 'missing-skill'"));
    }

    @Test
    void rejectsUnknownKnowledgeSource() {
        AgentDefinition agent = baseAgent();
        agent.setKnowledgeSources(Set.of("handbook", "nope"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(agent));
        assertTrue(error.getMessage().contains("Unknown knowledge source"));
    }

    @Test
    void rejectsUnknownMemoryProvider() {
        AgentDefinition agent = baseAgent();
        AgentDefinition.MemoryPolicySpec memory = new AgentDefinition.MemoryPolicySpec();
        memory.setProviders(Set.of("builtin", "ghost"));
        agent.setMemoryPolicyConfig(memory);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(agent));
        assertTrue(error.getMessage().contains("Unknown memory provider: ghost"));
    }

    @Test
    void rejectsStarTokenForMemoryProviders() {
        AgentDefinition agent = baseAgent();
        AgentDefinition.MemoryPolicySpec memory = new AgentDefinition.MemoryPolicySpec();
        memory.setProviders(Set.of("*"));
        agent.setMemoryPolicyConfig(memory);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(agent));
        assertTrue(error.getMessage().contains("memory.providers does not support"));
    }

    @Test
    void rejectsStarTokenInDelegationToolsets() {
        AgentDefinition agent = baseAgent();
        AgentDefinition.DelegationSpec delegation = new AgentDefinition.DelegationSpec();
        delegation.setDefaultToolsets(List.of("*"));
        agent.setDelegationSpec(delegation);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(agent));
        assertTrue(error.getMessage().contains("default-toolsets does not support"));
    }

    @Test
    void rejectsModelProviderWhenRegistryMissing() {
        AgentDefinition agent = baseAgent();
        agent.setModelSelection(ModelSelection.builder().providerId("main").modelName("x").build());
        // llmClientRegistry is null after setUp

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(agent));
        assertTrue(error.getMessage().contains("LlmClientRegistry is unavailable"));
    }

    @Test
    void rejectsMcpServerWhenMcpDisabled() {
        AgentDefinition agent = baseAgent();
        agent.setAllowedMcpServers(Set.of("filesystem"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(agent));
        assertTrue(error.getMessage().contains("MCP"));
    }

    @Test
    void rejectsUnknownMcpServerWhenConfigured() {
        mcpConfigLoader = new StubMcpConfigLoader(Map.of(
                "filesystem", stubServer()));
        rebuildValidator();

        AgentDefinition agent = baseAgent();
        agent.setAllowedMcpServers(Set.of("filesystem", "missing-server"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(agent));
        assertTrue(error.getMessage().contains("unknown MCP server 'missing-server'"));
    }

    @Test
    void acceptsKnownMcpServer() {
        mcpConfigLoader = new StubMcpConfigLoader(Map.of(
                "filesystem", stubServer()));
        rebuildValidator();

        AgentDefinition agent = baseAgent();
        agent.setAllowedMcpServers(Set.of("filesystem"));

        assertDoesNotThrow(() -> validator.validate(agent));
    }

    @Test
    void rejectsUnknownLlmProvider() {
        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("main");
        LinkedHashMap<String, LlmProperties.Provider> providers = new LinkedHashMap<>();
        LlmProperties.Provider main = new LlmProperties.Provider();
        main.setType("openai-compatible");
        main.setBaseUrl("https://api.openai.com");
        main.setApiKey("test-key");
        main.setModel("gpt-default");
        providers.put("main", main);
        props.setProviders(providers);
        llmClientRegistry = new LlmClientRegistry(
                props,
                new AgentConfig(),
                "https://api.openai.com",
                "test-key",
                "/v1/chat/completions",
                "gpt-default",
                0.7);
        rebuildValidator();

        AgentDefinition agent = baseAgent();
        agent.setModelSelection(ModelSelection.builder().providerId("missing-provider").modelName("x").build());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(agent));
        assertTrue(error.getMessage().contains("Unknown LLM provider"));
    }

    @Test
    void rejectsFixedWorkingDirWithoutPath() {
        AgentDefinition agent = baseAgent();
        agent.setWorkingDirectoryPolicy(AgentDefinition.WorkingDirectoryPolicy.FIXED);
        agent.setFixedWorkingDirectory(" ");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(agent));
        assertTrue(error.getMessage().contains("fixed-working-dir"));
    }

    @Test
    void rejectsUnknownDelegationToolset() {
        AgentDefinition agent = baseAgent();
        AgentDefinition.DelegationSpec delegation = new AgentDefinition.DelegationSpec();
        delegation.setBlockedToolsets(List.of("DELEGATE", "NOT_A_TOOLSET"));
        agent.setDelegationSpec(delegation);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(agent));
        assertTrue(error.getMessage().contains("NOT_A_TOOLSET"));
    }

    @Test
    void rejectsEnabledRateLimitWithoutRpm() {
        AgentDefinition agent = baseAgent();
        AgentDefinition.RateLimitSpec rate = new AgentDefinition.RateLimitSpec();
        rate.setEnabled(true);
        agent.setRateLimitSpec(rate);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(agent));
        assertTrue(error.getMessage().contains("rate-limit-requests-per-minute"));
    }

    private AgentDefinition baseAgent() {
        AgentDefinition agent = new AgentDefinition();
        agent.setAgentId("assistant");
        agent.setAllowedToolsets(Set.of(Toolset.CORE));
        return agent;
    }

    private static Skill skill(String name) {
        return new Skill(name, "desc", Set.of(), Set.of(), "body", null, Map.of());
    }

    private static McpServerConfig stubServer() {
        return new McpServerConfig("npx", List.of(), Map.of(), null, null, Map.of(), true, 0);
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }

    private static final class StubMcpConfigLoader extends McpConfigLoader {
        private final Map<String, McpServerConfig> servers;

        StubMcpConfigLoader(Map<String, McpServerConfig> servers) {
            super(null, "");
            this.servers = servers;
        }

        @Override
        public ConfigLoadResult loadAllServersResult() {
            return ConfigLoadResult.success(servers);
        }
    }
}
