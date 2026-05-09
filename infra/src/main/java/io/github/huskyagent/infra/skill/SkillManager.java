package io.github.huskyagent.infra.skill;

import io.github.huskyagent.infra.tool.Toolset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 核心管理 — 持有所有已加载 Skill，按条件过滤激活集合。
 *
 * SkillLoader 在启动时通过 setSkills() 注入全量 Skill 列表，
 * SkillSection / SkillToolProvider 通过 SkillManager 获取激活 skill。
 */
@Slf4j
@Component
public class SkillManager {

    private final Map<String, Skill> skillMap = new ConcurrentHashMap<>();

    /** 全量替换 skill 列表（由 SkillLoader 调用） */
    public void setSkills(List<Skill> skills) {
        skillMap.clear();
        for (Skill skill : skills) {
            skillMap.put(skill.name(), skill);
        }
        log.info("SkillManager loaded {} skills: {}", skills.size(),
                skills.stream().map(Skill::name).toList());
    }

    /** 按名称查找完整 skill（不做条件过滤） */
    public Skill getSkill(String name) {
        return skillMap.get(name);
    }

    /** 获取激活 skill 列表 */
    public List<Skill> getActiveSkills(Set<Toolset> availableToolsets) {
        return skillMap.values().stream()
                .filter(skill -> skill.isActivatable(availableToolsets))
                .toList();
    }

    /** 获取激活 skill 摘要列表（注入系统 prompt 用） */
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

    /** 全量 skill 列表（调试用） */
    public List<Skill> getAllSkills() {
        return List.copyOf(skillMap.values());
    }
}