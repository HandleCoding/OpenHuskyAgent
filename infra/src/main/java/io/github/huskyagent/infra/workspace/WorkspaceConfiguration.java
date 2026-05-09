package io.github.huskyagent.infra.workspace;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class WorkspaceConfiguration {

    @Bean
    @ConditionalOnMissingBean(LocalWorkspace.class)
    public LocalWorkspace localWorkspace() {
        return new LocalWorkspace();
    }

    @Bean
    public LocalWorkspaceProvider localWorkspaceProvider(LocalWorkspace localWorkspace) {
        return new LocalWorkspaceProvider(localWorkspace);
    }

    @Bean
    @Primary
    public Workspace workspaceRouter(java.util.List<WorkspaceProvider> providers) {
        return new WorkspaceRouter(providers);
    }
}
