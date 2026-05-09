package io.github.huskyagent.domain.prompt.section;

import io.github.huskyagent.domain.prompt.AbstractPromptSection;
import io.github.huskyagent.domain.prompt.PromptContext;
import io.github.huskyagent.domain.scene.SceneConfig;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Runtime 环境 Section
 *
 * 注入当前运行时信息：日期时间、OS/架构、模型名、provider、session ID、工作目录、Java 版本
 * 让 Agent 理解自身运行环境，从而做出正确的行为判断（路径风格、命令选择等）
 *
 * 对标 Hermes 的 Timestamp/Session ID/Model/Provider 段 + Environment Hints
 */
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

        // 时间信息
        sb.append("- **Date**: ").append(today.format(DateTimeFormatter.ISO_LOCAL_DATE)).append("\n");
        sb.append("- **Time**: ").append(now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))).append("\n");
        sb.append("- **Timezone**: ").append(timeZone.getId()).append("\n");

        // 判断是否 docker backend
        boolean isDocker = context.getBackendPolicy() == SceneConfig.BackendPolicy.DOCKER;

        // 运行环境
        if (isDocker) {
            sb.append("- **OS**: Linux (Docker container)\n");
            sb.append("- **Arch**: ").append(System.getProperty("os.arch")).append("\n");
        } else {
            sb.append("- **OS**: ").append(System.getProperty("os.name")).append("\n");
            sb.append("- **Arch**: ").append(System.getProperty("os.arch")).append("\n");
        }

        // Agent 元信息
        sb.append("- **Model**: ").append(modelName != null ? modelName : "unknown").append("\n");
        if (providerName != null && !providerName.isBlank()) {
            sb.append("- **Provider**: ").append(providerName).append("\n");
        }
        sb.append("- **Session**: ").append(context.getSessionId()).append("\n");

        // 工作目录
        if (isDocker) {
            sb.append("- **Working Directory**: /workspace (Docker container)\n");
        } else if (context.getWorkingDirectory() != null) {
            sb.append("- **Working Directory**: ").append(context.getWorkingDirectory()).append("\n");
        }

        // 平台特征提示
        String platformHint = isDocker ? buildDockerPlatformHint() : buildPlatformHint();
        if (!platformHint.isEmpty()) {
            sb.append("\n").append(platformHint).append("\n");
        }

        sb.append("\n");
        return sb.toString();
    }

    private String buildDockerPlatformHint() {
        return "You are running inside a Docker container (Linux/Ubuntu). " +
               "Use Unix-style paths. apt is the package manager. " +
               "Working directory is /workspace. Files created here persist for this session only.";
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