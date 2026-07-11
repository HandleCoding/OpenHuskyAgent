package io.github.huskyagent.domain.prompt.section;

import io.github.huskyagent.domain.prompt.AbstractPromptSection;
import io.github.huskyagent.domain.prompt.PromptContext;
import io.github.huskyagent.domain.agent.AgentDefinition;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class RuntimeSection extends AbstractPromptSection {

    private final ZoneId timeZone;
    private final String modelName;
    private final String providerName;

    public RuntimeSection() {
        this(ZoneId.systemDefault(), null, null);
    }

    public RuntimeSection(ZoneId timeZone, String modelName, String providerName) {
        this.timeZone = timeZone;
        this.modelName = modelName;
        this.providerName = providerName;
    }

    @Override
    public String getName() {
        return "runtime";
    }

    @Override
    public int getPriority() {
        return 800;
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    public String build(PromptContext context) {
        LocalDateTime now = LocalDateTime.now(timeZone);
        LocalDate today = now.toLocalDate();

        StringBuilder sb = new StringBuilder();
        sb.append("## Runtime Environment\n\n");

        sb.append("- **Date**: ").append(today.format(DateTimeFormatter.ISO_LOCAL_DATE)).append("\n");
        sb.append("- **Time**: ").append(now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))).append("\n");
        sb.append("- **Timezone**: ").append(timeZone.getId()).append("\n");

        boolean isDocker = context.getBackendPolicy() == AgentDefinition.BackendPolicy.DOCKER;

        if (isDocker) {
            sb.append("- **OS**: Linux (Docker container)\n");
            sb.append("- **Arch**: ").append(System.getProperty("os.arch")).append("\n");
        } else {
            sb.append("- **OS**: ").append(System.getProperty("os.name")).append("\n");
            sb.append("- **Arch**: ").append(System.getProperty("os.arch")).append("\n");
        }

        sb.append("- **Model**: ").append(modelName != null ? modelName : "unknown").append("\n");
        if (providerName != null && !providerName.isBlank()) {
            sb.append("- **Provider**: ").append(providerName).append("\n");
        }
        sb.append("- **Session**: ").append(context.getSessionId()).append("\n");

        if (isDocker) {
            sb.append("- **Working Directory**: ").append(dockerWorkingDirectory(context)).append(" (Docker container)\n");
        } else if (context.getWorkingDirectory() != null) {
            sb.append("- **Working Directory**: ").append(context.getWorkingDirectory()).append("\n");
        }

        String platformHint = isDocker ? buildDockerPlatformHint(context) : buildPlatformHint();
        if (!platformHint.isEmpty()) {
            sb.append("\n").append(platformHint).append("\n");
        }

        sb.append("\n");
        return sb.toString();
    }

    private String buildDockerPlatformHint(PromptContext context) {
        String workdir = dockerWorkingDirectory(context);
        return "You are running inside a Docker container (Linux/Ubuntu). " +
               "Use Unix-style paths. apt is the package manager. " +
               "Working directory is " + workdir + ". Files created here persist for this session only.";
    }

    private String dockerWorkingDirectory(PromptContext context) {
        String runtimeWorkdir = context.getSessionScope()
                .map(scope -> scope.getRuntimeWorkingDirectory())
                .orElse(null);
        if (runtimeWorkdir != null && !runtimeWorkdir.isBlank()) {
            return runtimeWorkdir;
        }
        AgentDefinition.BackendSpec spec = context.getRuntimePolicy().getBackendSpec();
        if (spec != null && spec.getDockerWorkdir() != null && !spec.getDockerWorkdir().isBlank()) {
            return spec.getDockerWorkdir();
        }
        return "/workspace";
    }

    private String buildPlatformHint() {
        String os = System.getProperty("os.name", "").toLowerCase();
        StringBuilder hint = new StringBuilder();

        if (os.contains("mac") || os.contains("darwin")) {
            hint.append("You are running on macOS.");
        } else if (os.contains("linux")) {
            hint.append("You are running on Linux.");
        } else if (os.contains("win")) {
            hint.append("You are running on Windows.");
        }

        return hint.toString();
    }
}
