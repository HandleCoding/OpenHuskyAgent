package io.github.huskyagent.infra.execute;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "execution.backend")
public class ExecutionBackendProperties {

    private long idleTtlSeconds = 300;

    private DockerProperties docker = new DockerProperties();
    private SshProperties ssh = new SshProperties();

    @Data
    public static class DockerProperties {
        private String image = "ubuntu:24.04";
        private String memory = "512m";
        private String cpus = "1.0";
        private boolean persistFilesystem = false;
        private String workspaceRoot = "/tmp/husky-sandbox";
    }

    @Data
    public static class SshProperties {
        private String host = "";
        private int port = 22;
        private String user = "";
        private String identityFile = "";
        private String workspaceRoot = "~/husky-workspace";
    }
}
