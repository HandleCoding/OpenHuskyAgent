package io.github.huskyagent.domain.prompt.section;

import io.github.huskyagent.domain.prompt.AbstractPromptSection;
import io.github.huskyagent.domain.prompt.PromptContext;
import io.github.huskyagent.infra.knowledge.KnowledgeManager;

import java.util.Set;

public class KnowledgeSection extends AbstractPromptSection {
    private final KnowledgeManager knowledgeManager;

    public KnowledgeSection(KnowledgeManager knowledgeManager) {
        this.knowledgeManager = knowledgeManager;
    }

    @Override
    public String getName() {
        return "knowledge";
    }

    @Override
    public int getPriority() {
        return 350;
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    public String build(PromptContext context) {
        if (knowledgeManager == null) {
            return "";
        }
        String sources = knowledgeManager.describeSources(enabledSources(context));
        if (sources == null || sources.isBlank()) {
            return "";
        }
        return "## Knowledge Sources\n\n"
                + "External factual knowledge is available through `knowledge_search` and `knowledge_fetch`. "
                + "Search before answering questions that depend on configured project docs, business knowledge, runbooks, or other external references. "
                + "Cite the returned source/title when using knowledge results.\n\n"
                + sources + "\n";
    }

    private Set<String> enabledSources(PromptContext context) {
        return context.getKnowledgeSources();
    }
}
