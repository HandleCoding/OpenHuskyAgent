package io.github.huskyagent.infra.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface KnowledgeProvider {
    String getName();

    String getDescription();

    default Set<String> getSourceIds() {
        return Set.of(getName());
    }

    default boolean supportsSource(String sourceId) {
        return getName().equals(sourceId) || getSourceIds().contains(sourceId);
    }

    boolean isAvailable();

    List<KnowledgeResult> search(KnowledgeQuery query, Set<String> allowedSourceIds);

    Optional<KnowledgeDocument> fetch(String id, Set<String> allowedSourceIds);
}