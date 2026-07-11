package io.github.huskyagent.infra.execute;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable config snapshot for creating one ExecutionBackend instance.
 * Derived from SceneConfig + ExecutionBackendProperties.
 */
@Value
@Builder
public class BackendConfig {

    String type; // "local" | "docker" | "ssh"
    String initialWorkDir;

    // Docker
    String dockerImage;
    String dockerMemory;
    String dockerCpus;
    boolean dockerPersistFilesystem;
    String dockerWorkspaceRoot;
    String dockerHostWorkspaceDir;

    // SSH (Phase 3)
    String sshHost;
    int sshPort;
    String sshUser;
    String sshIdentityFile;
    String sshWorkspaceRoot;
}
