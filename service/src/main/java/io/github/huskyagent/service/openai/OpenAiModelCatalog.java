package io.github.huskyagent.service.openai;

import io.github.huskyagent.application.scene.ConfigSceneResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
class OpenAiModelCatalog {

    private final ConfigSceneResolver sceneResolver;
    private final OpenAiCompatibleProperties properties;

    String resolveSceneId(String model) {
        if (model == null || model.isBlank()) {
            throw new OpenAiProtocolException("model is required", "model", "missing_model");
        }
        String agentId = stripPrefix(model.trim());
        if (!availableAgentIds().contains(agentId)) {
            throw new OpenAiProtocolException("Model not found: " + model, "model", "model_not_found");
        }
        return agentId;
    }

    OpenAiWireResponses.ModelsResponse models() {
        long created = Instant.now().getEpochSecond();
        List<OpenAiWireResponses.ModelObject> data = availableAgentIds().stream()
                .map(id -> new OpenAiWireResponses.ModelObject(toModelId(id), "model", created, "openhusky"))
                .toList();
        return new OpenAiWireResponses.ModelsResponse("list", data);
    }

    private Set<String> availableAgentIds() {
        Set<String> ids = new LinkedHashSet<>();
        if (sceneResolver.getAgents() != null) {
            ids.addAll(sceneResolver.getAgents().keySet());
        }
        return ids;
    }

    private String stripPrefix(String model) {
        String prefix = properties.getModelPrefix();
        if (prefix != null && !prefix.isBlank() && model.startsWith(prefix)) {
            return model.substring(prefix.length());
        }
        return model;
    }

    private String toModelId(String agentId) {
        String prefix = properties.getModelPrefix();
        if (prefix == null || prefix.isBlank()) {
            return agentId;
        }
        return prefix + agentId;
    }
}
