package io.github.huskyagent.infra.runtime.watch;

import java.nio.file.Path;
import java.util.Set;

/**
 * 描述一组需要监听的运行时资源。
 */
public record RuntimeResourceDescriptor(
        RuntimeResourceType type,
        Set<Path> roots,
        boolean recursive
) {
}
