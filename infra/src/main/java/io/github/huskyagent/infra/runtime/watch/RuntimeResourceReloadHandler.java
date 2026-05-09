package io.github.huskyagent.infra.runtime.watch;

import java.nio.file.Path;
import java.util.Set;

/**
 * 资源变更后的 reload 处理器。
 */
public interface RuntimeResourceReloadHandler {

    RuntimeResourceDescriptor descriptor();

    RuntimeReloadOutcome reload(Set<Path> changedPaths);
}
