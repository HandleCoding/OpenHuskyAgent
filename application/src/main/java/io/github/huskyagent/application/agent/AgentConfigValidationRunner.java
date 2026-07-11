package io.github.huskyagent.application.agent;

import io.github.huskyagent.domain.agent.AgentDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Fail-closed startup check: every configured {@code agents.*} definition must pass
 * {@link AgentDefinitionValidator} after catalogs (skills, MCP, knowledge, …) are loaded.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentConfigValidationRunner implements ApplicationRunner {

    private final ConfigAgentResolver agentResolver;
    private final AgentDefinitionValidator validator;

    @Override
    public void run(ApplicationArguments args) {
        Set<String> agentIds = agentResolver.agentIds();
        if (agentIds.isEmpty()) {
            log.warn("No agents configured under agents.*; skipping agent definition validation");
            return;
        }

        List<String> failures = new ArrayList<>();
        for (String agentId : agentIds) {
            try {
                AgentDefinition definition = agentResolver.resolve(agentId);
                validator.validate(definition);
            } catch (RuntimeException e) {
                failures.add(agentId + ": " + e.getMessage());
            }
        }

        if (!failures.isEmpty()) {
            String message = "Agent configuration validation failed (" + failures.size() + "):\n - "
                    + String.join("\n - ", failures);
            throw new IllegalStateException(message);
        }

        log.info("Validated {} agent definition(s): {}", agentIds.size(), agentIds);
    }
}
