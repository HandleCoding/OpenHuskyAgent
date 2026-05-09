package io.github.huskyagent.infra.runtime.watch;

import io.github.huskyagent.infra.skill.SkillLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class SkillReloadHandler implements RuntimeResourceReloadHandler {

    private final SkillLoader skillLoader;

    @Override
    public RuntimeResourceDescriptor descriptor() {
        return new RuntimeResourceDescriptor(RuntimeResourceType.SKILL, skillLoader.getWatchedRoots(), true);
    }

    @Override
    public RuntimeReloadOutcome reload(Set<Path> changedPaths) {
        try {
            skillLoader.reload();
            return RuntimeReloadOutcome.success(
                    RuntimeResourceType.SKILL,
                    "reloaded skills from " + changedPaths.size() + " changed path(s)",
                    true,
                    true
            );
        } catch (Exception e) {
            return RuntimeReloadOutcome.failure(RuntimeResourceType.SKILL, e.getMessage());
        }
    }
}
