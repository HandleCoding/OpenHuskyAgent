package io.github.huskyagent.tui.markdown;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown → ANSI 渲染器（有状态，行缓冲状态机）。
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 每轮对话开始前重置
 * renderer.reset();
 *
 * // 每次获得完整行时处理（调用方负责在每条结果前加框线前缀再 println）
 * List<String> rendered = renderer.processLine(completedLine);
 * rendered.forEach(r -> println("│  " + r));
 *
 * // 对话结束前冲刷残留（未关闭的代码块/表格）
 * renderer.flush().forEach(r -> println("│  " + r));
 * }</pre>
 *
 * <h2>状态机</h2>
 * <ul>
 *   <li>{@link RenderState#NORMAL}：逐行识别结构化元素并渲染</li>
 *   <li>{@link RenderState#CODE_BLOCK}：在 ``` 之间，原样输出（灰色）</li>
 *   <li>{@link RenderState#TABLE}：缓冲表格行，遇到非表格行时批量渲染</li>
 * </ul>
 */
public class MarkdownRenderer {

    /** 渲染器状态 */
    public enum RenderState { NORMAL, CODE_BLOCK, TABLE }

    private RenderState state = RenderState.NORMAL;
    private String codeBlockLang = "";
    private final List<String> tableBuffer = new ArrayList<>();

    /** 当前终端宽度，用于表格列宽计算；由外部调用方更新（每次 processLine 前或对话开始时） */
    private int terminalWidth = TableRenderer.DEFAULT_TERMINAL_WIDTH;

    // ── 公开 API ─────────────────────────────────────────────────────────────

    /** 更新终端宽度（在 terminal.getWidth() 拿到新值时调用，支持动态 resize） */
    public void setTerminalWidth(int width) {
        if (width > 0) this.terminalWidth = width;
    }

    /** 重置状态（每轮对话开始时调用） */
    public void reset() {
        state = RenderState.NORMAL;
        codeBlockLang = "";
        tableBuffer.clear();
    }

    /**
     * 处理一行完整的 Markdown 内容。
     *
     * @param line 已去除尾部换行符的完整行（可以是空字符串）
     * @return 0 到多条 ANSI 渲染字符串，每条对应终端上打印一行
     */
    public List<String> processLine(String line) {
        return switch (state) {
            case NORMAL     -> processNormalLine(line);
            case CODE_BLOCK -> processCodeBlockLine(line);
            case TABLE      -> processTableLine(line);
        };
    }

    /**
     * 冲刷未关闭的状态残留（对话结束时调用）。
     * CODE_BLOCK：输出关闭装饰行；TABLE：渲染已积累行；NORMAL：无操作。
     */
    public List<String> flush() {
        List<String> result = new ArrayList<>();
        switch (state) {
            case CODE_BLOCK -> {
                result.add(BlockRenderer.renderCodeFenceLine(false, codeBlockLang));
                state = RenderState.NORMAL;
                codeBlockLang = "";
            }
            case TABLE -> {
                result.addAll(TableRenderer.render(tableBuffer, terminalWidth));
                tableBuffer.clear();
                state = RenderState.NORMAL;
            }
            case NORMAL -> { /* 无需处理 */ }
        }
        return result;
    }

    /** 供 AgentTUI 判断是否暂停实时回显 */
    public RenderState getState() {
        return state;
    }

    /** 对片段做行内渲染（委托 InlineRenderer），用于流式实时回显 */
    public String renderInlinePartial(String fragment) {
        return InlineRenderer.renderPartial(fragment);
    }

    // ── 状态处理 ─────────────────────────────────────────────────────────────

    private List<String> processNormalLine(String line) {
        // 空行：保留视觉空行
        if (line.isBlank()) return List.of("");

        // 代码块开始
        if (line.startsWith("```")) {
            codeBlockLang = line.substring(3).trim();
            state = RenderState.CODE_BLOCK;
            return List.of(BlockRenderer.renderCodeFenceLine(true, codeBlockLang));
        }

        // 表格行（以 | 开头且以 | 结尾，或包含 | 且以 | 开头）
        if (isTableRow(line)) {
            state = RenderState.TABLE;
            tableBuffer.clear();
            tableBuffer.add(line);
            return List.of();
        }

        // 标题
        if (line.startsWith("#")) return List.of(BlockRenderer.renderHeading(line));

        // 无序列表
        if (line.matches("^[\\-\\*\\+] .+")) return List.of(BlockRenderer.renderUnorderedList(line));

        // 有序列表
        if (line.matches("^\\d+\\. .+")) return List.of(BlockRenderer.renderOrderedList(line));

        // 引用
        if (line.startsWith("> ") || line.equals(">")) return List.of(BlockRenderer.renderBlockquote(line));

        // 普通段落
        return List.of(InlineRenderer.render(line));
    }

    private List<String> processCodeBlockLine(String line) {
        if (line.startsWith("```")) {
            // 代码块结束
            String lang = codeBlockLang;
            state = RenderState.NORMAL;
            codeBlockLang = "";
            return List.of(BlockRenderer.renderCodeFenceLine(false, lang));
        }
        return List.of(BlockRenderer.renderCodeLine(line));
    }

    private List<String> processTableLine(String line) {
        if (isTableRow(line)) {
            tableBuffer.add(line);
            return List.of();
        }
        // 非表格行：渲染已缓冲的表格，再处理当前行
        List<String> result = new ArrayList<>(TableRenderer.render(tableBuffer, terminalWidth));
        tableBuffer.clear();
        state = RenderState.NORMAL;
        result.addAll(processNormalLine(line));
        return result;
    }

    // ── 辅助 ─────────────────────────────────────────────────────────────────

    /** 判断是否为表格行：以 | 开头，或以 | 结尾（含分隔行 `|---|---|`） */
    private static boolean isTableRow(String line) {
        String t = line.trim();
        return t.startsWith("|") && (t.endsWith("|") || t.contains("|"));
    }
}
