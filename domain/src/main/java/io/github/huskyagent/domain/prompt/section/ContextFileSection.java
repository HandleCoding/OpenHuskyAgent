package io.github.huskyagent.domain.prompt.section;

import io.github.huskyagent.domain.prompt.AbstractPromptSection;
import io.github.huskyagent.domain.prompt.PromptContext;
import io.github.huskyagent.domain.prompt.ContextFileLoader;
import io.github.huskyagent.domain.agent.AgentDefinition;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ContextFileSection extends AbstractPromptSection {

    private final ContextFileLoader fileLoader;

    public ContextFileSection(ContextFileLoader fileLoader) {
        this.fileLoader = fileLoader;
    }

    @Override
    public String getName() {
        return "context-files";
    }

    @Override
    public int getPriority() {
        return 400;
    }

    @Override
    public String build(PromptContext context) {
        if (fileLoader == null) {
            return "";
        }

        Path workingDir = context.getWorkingDirectory();
        if (workingDir == null) {
            return "";
        }

        List<ContextFileLoader.LoadedFile> loadedFiles = new ArrayList<>();
        boolean overrideDefaults = context.getPromptFilePolicy() == AgentDefinition.PromptFilePolicy.OVERRIDE;
        if (!overrideDefaults) {
            loadedFiles.addAll(fileLoader.loadContextFiles(workingDir));
        }
        List<String> promptFiles = context.getPromptFiles();
        if (!promptFiles.isEmpty()) {
            loadedFiles.addAll(fileLoader.loadExplicitFiles(workingDir, promptFiles));
        }

        if (loadedFiles.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Project Context\n\n");
        sb.append("[System note: Loaded from project context files]\n\n");

        for (ContextFileLoader.LoadedFile file : loadedFiles) {
            sb.append("### ").append(file.fileName()).append("\n\n");
            sb.append(file.content());
            sb.append("\n\n---\n\n");
        }

        return sb.toString();
    }
}