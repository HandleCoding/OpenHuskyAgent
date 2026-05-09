package io.github.huskyagent.tui.markdown;

/**
 * 块级 Markdown 元素渲染器（无状态，静态工具类）。
 *
 * <p>处理：标题、无序列表、有序列表、引用、代码块开闭装饰行。</p>
 */
final class BlockRenderer {

    private BlockRenderer() {}

    // ── ANSI 常量 ────────────────────────────────────────────────────────────
    private static final String RESET  = InlineRenderer.RESET;
    private static final String BOLD   = InlineRenderer.BOLD;
    private static final String CYAN   = "\033[36m";
    private static final String GRAY   = "\033[90m";
    private static final String GREEN  = "\033[32m";

    /** 标题 `# text` → 粗体青色，1-6 级用缩进区分层次 */
    static String renderHeading(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') level++;
        String text = line.substring(level).trim();
        String indent = "  ".repeat(Math.max(0, level - 1));
        // 1-2 级用加粗青色，3+ 级用普通青色
        String style = level <= 2 ? BOLD + CYAN : CYAN;
        return indent + style + text + RESET;
    }

    /** 无序列表 `- item` / `* item` / `+ item` → `  • item` */
    static String renderUnorderedList(String line) {
        String text = line.substring(2).trim();
        return "  " + GREEN + "•" + RESET + " " + InlineRenderer.render(text);
    }

    /** 有序列表 `1. item` → `  1. item`（数字加粗） */
    static String renderOrderedList(String line) {
        int dotIdx = line.indexOf('.');
        String num  = line.substring(0, dotIdx + 1);
        String text = line.substring(dotIdx + 1).trim();
        return "  " + BOLD + num + RESET + " " + InlineRenderer.render(text);
    }

    /** 引用 `> text` → 灰色 `▎` 前缀 */
    static String renderBlockquote(String line) {
        String text = line.startsWith("> ") ? line.substring(2) : "";
        return GRAY + "▎ " + InlineRenderer.render(text) + RESET;
    }

    /**
     * 代码块开闭装饰行。
     *
     * @param opening true 为开始行（显示语言标识），false 为结束行
     * @param lang    代码块语言（可为空字符串）
     */
    static String renderCodeFenceLine(boolean opening, String lang) {
        if (opening) {
            String langLabel = (lang == null || lang.isBlank()) ? "" : " " + CYAN + lang + GRAY;
            return GRAY + "  ╔═ code" + langLabel + GRAY + " ═" + RESET;
        } else {
            return GRAY + "  ╚══════════════════" + RESET;
        }
    }

    /** 代码块内容行（灰色，保留原始缩进） */
    static String renderCodeLine(String line) {
        return GRAY + "  ║ " + line + RESET;
    }
}
