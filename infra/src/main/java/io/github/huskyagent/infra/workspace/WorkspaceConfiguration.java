package io.github.huskyagent.infra.workspace;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Workspace 配置注册。
 *
 * <p>当没有其他 Workspace 实现时注册基于本地文件系统的 LocalWorkspace。</p>
 */
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
