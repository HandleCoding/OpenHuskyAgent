package io.github.huskyagent.infra.runtime.watch;

import java.nio.file.Path;
import java.util.Set;

public record RuntimeResourceDescriptor(
        RuntimeResourceType type,
        Set<Path> roots,
        boolean recursive
) {
}
