package io.github.huskyagent.infra.runtime.watch;

import java.nio.file.Path;
import java.util.Set;

public interface RuntimeResourceReloadHandler {

    RuntimeResourceDescriptor descriptor();

    RuntimeReloadOutcome reload(Set<Path> changedPaths);
}
