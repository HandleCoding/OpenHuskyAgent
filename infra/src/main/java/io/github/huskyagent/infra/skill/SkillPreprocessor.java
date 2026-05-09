package io.github.huskyagent.infra.skill;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Skill 预处理 — 替换 SKILL.md 内容中的模板变量。
 *
 * 支持的变量：
 * - ${HUSKY_SKILL_DIR} → skill 目录的绝对路径
 * - ${CURRENT_DATE} → 当前日期 yyyy-MM-dd
 */
public class SkillPreprocessor {

    private static final Pattern TEMPLATE_VAR_PATTERN =
            Pattern.compile("\\$\\{(HUSKY_SKILL_DIR|CURRENT_DATE)}");

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public String preprocess(String content, Path skillDir) {
        if (content == null || skillDir == null) return content;

        return TEMPLATE_VAR_PATTERN.matcher(content).replaceAll(match -> {
            String var = match.group(1);
            if ("HUSKY_SKILL_DIR".equals(var)) return skillDir.toAbsolutePath().toString();
            if ("CURRENT_DATE".equals(var)) return LocalDate.now().format(DATE_FMT);
            return match.group(0);
        });
    }
}