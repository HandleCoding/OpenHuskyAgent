package io.github.huskyagent.domain.prompt.section;

import io.github.huskyagent.domain.prompt.AbstractPromptSection;
import io.github.huskyagent.domain.prompt.PromptContext;
import io.github.huskyagent.infra.skill.Skill;
import io.github.huskyagent.infra.skill.SkillManager;

import java.util.List;

public class SkillSection extends AbstractPromptSection {

    private final SkillManager skillManager;

    public SkillSection(SkillManager skillManager) {
        this.skillManager = skillManager;
    }

    @Override
    public String getName() {
        return "skills";
    }

    @Override
    public int getPriority() {
        return 300;
    }

    @Override
    public String build(PromptContext context) {
        List<Skill> skills = context.getRuntimePolicy().getCapabilityView().getVisibleSkills();
        if (skills == null || skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Skill skill : skills) {
            sb.append(skill.summary()).append("\n");
        }
        String content = SkillManager.buildPromptContent(sb.toString());

        if (content == null || content.isBlank()) {
            return "";
        }

        return buildWithTitle("Skills (mandatory)", content);
    }
}
