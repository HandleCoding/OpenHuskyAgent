package io.github.huskyagent.application.agent;

import io.github.huskyagent.domain.agent.AgentDefinition;
import io.github.huskyagent.infra.knowledge.KnowledgeConfig;
import io.github.huskyagent.infra.knowledge.KnowledgeManager;
import io.github.huskyagent.infra.memory.MemoryManager;
import io.github.huskyagent.infra.memory.MemoryRuntimeStrategyResolver;
import io.github.huskyagent.infra.skill.SkillManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigValidationRunnerTest {

    @Test
    void startupFailsWhenAnyAgentIsInvalid() {
        ConfigAgentResolver resolver = new ConfigAgentResolver();
        ConfigAgentResolver.AgentProperties props = new ConfigAgentResolver.AgentProperties();
        props.setSkills(java.util.Set.of("does-not-exist"));
        LinkedHashMap<String, ConfigAgentResolver.AgentProperties> agents = new LinkedHashMap<>();
        agents.put("assistant", props);
        resolver.setAgents(agents);

        AgentDefinitionValidator validator = emptyCatalogValidator();
        AgentConfigValidationRunner runner = new AgentConfigValidationRunner(resolver, validator);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runner.run(new DefaultApplicationArguments()));
        assertTrue(error.getMessage().contains("Agent configuration validation failed"));
        assertTrue(error.getMessage().contains("unknown skill"));
    }

    @Test
    void startupPassesForValidEmptyAllowlists() {
        ConfigAgentResolver resolver = new ConfigAgentResolver();
        LinkedHashMap<String, ConfigAgentResolver.AgentProperties> agents = new LinkedHashMap<>();
        agents.put("assistant", new ConfigAgentResolver.AgentProperties());
        resolver.setAgents(agents);

        AgentConfigValidationRunner runner = new AgentConfigValidationRunner(resolver, emptyCatalogValidator());
        assertDoesNotThrow(() -> runner.run(new DefaultApplicationArguments()));
    }

    private static AgentDefinitionValidator emptyCatalogValidator() {
        return new AgentDefinitionValidator(
                new SkillManager(),
                new KnowledgeManager(List.of(), new KnowledgeConfig()),
                new MemoryManager(new MemoryRuntimeStrategyResolver(List.of())),
                emptyProvider(),
                emptyProvider());
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return null;
            }

            @Override
            public T getIfAvailable() {
                return null;
            }

            @Override
            public T getIfUnique() {
                return null;
            }

            @Override
            public T getObject() {
                return null;
            }
        };
    }
}
