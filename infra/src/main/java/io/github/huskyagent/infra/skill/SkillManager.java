package io.github.huskyagent.infra.skill;

import io.github.huskyagent.infra.tool.Toolset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class SkillManager {

    private final Map<String, Skill> skillMap = new ConcurrentHashMap<>();

    public void setSkills(List<Skill> skills) {
        skillMap.clear();
        for (Skill skill : skills) {
            skillMap.put(skill.name(), skill);
        }
        log.info("SkillManager loaded {} skills: {}", skills.size(),
                skills.stream().map(Skill::name).toList());
    }

    public Skill getSkill(String name) {
        return skillMap.get(name);
    }

    public List<Skill> getActiveSkills(Set<Toolset> availableToolsets) {
        return skillMap.values().stream()
                .filter(skill -> skill.isActivatable(availableToolsets))
                .toList();
    }

    public String listSummaries(Set<Toolset> availableToolsets) {
        List<Skill> active = getActiveSkills(availableToolsets);
        if (active.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (Skill skill : active) {
            sb.append(skill.summary()).append("\n");
        }
        return buildPromptContent(sb.toString());
    }

    public static String buildPromptContent(String skillSummaries) {
        return "Before using a skill, compare the user's request with the skills below. "
                + "Load a skill with `skill_view(name)` only when the request clearly matches the skill's purpose "
                + "or when the user explicitly asks for that workflow. Do not load skills for greetings, small talk, "
                + "simple Q&A, or tasks that can be answered directly without specialized instructions. "
                + "When a skill is clearly relevant, follow its instructions after loading it.\n"
                + "If a loaded skill has issues, fix it with `skill_manage(action='patch')`.\n"
                + "After difficult/iterative tasks, offer to save as a skill. "
                + "If a skill you loaded was missing steps, had wrong commands, or needed "
                + "pitfalls you discovered, update it before finishing.\n\n"
                + "<available_skills>\n"
                + skillSummaries.strip()
                + "\n</available_skills>\n\n"
                + "If no skill clearly applies, proceed without loading one.";
    }

    public List<Skill> getAllSkills() {
        return List.copyOf(skillMap.values());
    }
}