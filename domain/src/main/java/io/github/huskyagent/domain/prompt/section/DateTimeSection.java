package io.github.huskyagent.domain.prompt.section;

import io.github.huskyagent.domain.prompt.AbstractPromptSection;
import io.github.huskyagent.domain.prompt.PromptContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 日期时间 Section
 *
 * 提供当前日期时间信息，帮助 Agent 理解时间上下文
 */
public class DateTimeSection extends AbstractPromptSection {

    private final ZoneId timeZone;

    public DateTimeSection() {
        this(ZoneId.systemDefault());
    }

    public DateTimeSection(ZoneId timeZone) {
        this.timeZone = timeZone;
    }

    @Override
    public String getName() {
        return "datetime";
    }

    @Override
    public int getPriority() {
        return 900;  // 最后加载
    }

    @Override
    public boolean isDynamic() {
        return true;  // 每次对话都更新
    }

    @Override
    public String build(PromptContext context) {
        LocalDateTime now = LocalDateTime.now(timeZone);
        LocalDate today = now.toLocalDate();

        StringBuilder sb = new StringBuilder();
        sb.append("## Current Information\n\n");
        sb.append("- **Date**: ").append(today.format(DateTimeFormatter.ISO_LOCAL_DATE)).append("\n");
        sb.append("- **Time**: ").append(now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))).append("\n");
        sb.append("- **Timezone**: ").append(timeZone.getId()).append("\n\n");

        return sb.toString();
    }
}