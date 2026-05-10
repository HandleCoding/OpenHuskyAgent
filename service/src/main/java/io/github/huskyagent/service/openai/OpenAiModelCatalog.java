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
        String sceneId = stripPrefix(model.trim());
        if (!availableSceneIds().contains(sceneId)) {
            throw new OpenAiProtocolException("Model not found: " + model, "model", "model_not_found");
        }
        return sceneId;
    }

    OpenAiWireResponses.ModelsResponse models() {
        long created = Instant.now().getEpochSecond();
        List<OpenAiWireResponses.ModelObject> data = availableSceneIds().stream()
                .map(id -> new OpenAiWireResponses.ModelObject(toModelId(id), "model", created, "openhusky"))
                .toList();
        return new OpenAiWireResponses.ModelsResponse("list", data);
    }

    private Set<String> availableSceneIds() {
        Set<String> ids = new LinkedHashSet<>();
        if (sceneResolver.getConfigs() != null) {
            ids.addAll(sceneResolver.getConfigs().keySet());
        }
        if (sceneResolver.getDefaultScene() != null && !sceneResolver.getDefaultScene().isBlank()) {
            ids.add(sceneResolver.getDefaultScene());
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

    private String toModelId(String sceneId) {
        String prefix = properties.getModelPrefix();
        if (prefix == null || prefix.isBlank()) {
            return sceneId;
        }
        return prefix + sceneId;
    }
}
