package io.github.huskyagent.infra.knowledge;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix = "knowledge")
public class KnowledgeConfig {
    private boolean enabled = true;
    private int defaultTopK = 5;
    private int maxTopK = 20;
    private int maxSnippetChars = 600;
    private int maxDocumentChars = 12000;
    private int maxWalkDepth = 6;
    private List<LocalSource> localSources = new ArrayList<>();

    @Data
    public static class LocalSource {
        private String id;
        private String name;
        private String root;
        private Set<String> extensions = Set.of(".md", ".mdx", ".txt", ".adoc", ".rst");
        private boolean enabled = true;
    }
}
