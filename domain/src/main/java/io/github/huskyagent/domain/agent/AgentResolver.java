package io.github.huskyagent.domain.agent;

public interface AgentResolver {
    AgentDefinition resolve(String agentId);

    AgentDefinition resolveDefault();
}
