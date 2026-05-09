package io.github.huskyagent.tui.markdown;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 表格渲染器（无状态，静态工具类）。
 *
 * <p>接收缓冲的原始表格行（含分隔行），输出带 Unicode 边框的渲染结果。
 * 当单元格内容超过列宽上限时自动换行，一个逻辑行可占多个物理行：
 * <pre>
 * ┌──────────┬──────────┐
 * │ 列名      │ 很长的内容 │
 * │           │ 第二行    │
 * ├──────────┼──────────┤
 * │ 值        │ 值        │
 * └──────────┴──────────┘
 * </pre>
 * </p>
 */
final class TableRenderer {

    private TableRenderer() {}

    /** 默认终端宽度（无法获取时的回退值） */
    static final int DEFAULT_TERMINAL_WIDTH = 120;

    /** 每列最小宽度 */
    private static final int MIN_COL_WIDTH = 3;

    /** 表格边框和间距占用的固定开销：每列 " │" (3字符) + 左侧 "│" (1字符) */
    private static final int BORDER_OVERHEAD_PER_COL = 3;
    private static final int BORDER_OVERHEAD_LEFT    = 1;

    // ── ANSI 常量 ────────────────────────────────────────────────────────────
    private static final String RESET = InlineRenderer.RESET;
    private static final String BOLD  = InlineRenderer.BOLD;
    private static final String GRAY  = "\033[90m";
    private static final String CYAN  = "\033[36m";

    // ── 边框字符 ─────────────────────────────────────────────────────────────
    private static final char TOP_LEFT   = '┌';
    private static final char TOP_MID    = '┬';
    private static final char TOP_RIGHT  = '┐';
    private static final char MID_LEFT   = '├';
    private static final char MID_MID    = '┼';
    private static final char MID_RIGHT  = '┤';
    private static final char BOT_LEFT   = '└';
    private static final char BOT_MID    = '┴';
    private static final char BOT_RIGHT  = '┘';
    private static final char VERTICAL   = '│';
    private static final char HORIZONTAL = '─';

    /**
     * 渲染缓冲的表格行，使用默认终端宽度。
     */
    static List<String> render(List<String> rawLines) {
        return render(rawLines, DEFAULT_TERMINAL_WIDTH);
    }

    /**
     * 渲染缓冲的表格行，按终端宽度自动限制列宽并 wrap 超长内容。
     *
     * @param rawLines      原始表格行（含 `|---|---|` 分隔行）
     * @param terminalWidth 当前终端列数（来自 terminal.getWidth()）
     * @return 渲染后的多行 ANSI 字符串，每条对应终端一行
     */
    static List<String> render(List<String> rawLines, int terminalWidth) {
        if (rawLines == null || rawLines.isEmpty()) return List.of();

        // ── 解析单元格，跳过分隔行 ───────────────────────────────────────────
        List<List<String>> rows = new ArrayList<>();
        for (String raw : rawLines) {
            List<String> cells = parseRow(raw);
            if (cells.isEmpty() || isSeparatorRow(cells)) continue;
            rows.add(cells);
        }
        if (rows.isEmpty()) return List.of();

        // ── 对齐列数（补空单元格） ────────────────────────────────────────────
        int colCount = rows.stream().mapToInt(List::size).max().orElse(0);
        for (List<String> row : rows) {
            while (row.size() < colCount) row.add("");
        }

        // ── 计算内容宽度（用渲染后字符串，确保含行内代码等标记时宽度准确） ──
        int[] contentWidths = new int[colCount];
        for (List<String> row : rows) {
            for (int c = 0; c < colCount; c++) {
                int w = InlineRenderer.displayWidth(InlineRenderer.render(row.get(c)));
                contentWidths[c] = Math.max(contentWidths[c], w);
            }
        }
        for (int c = 0; c < colCount; c++) {
            contentWidths[c] = Math.max(contentWidths[c], MIN_COL_WIDTH);
        }

        // ── 根据终端宽度计算列宽上限并按比例收缩 ─────────────────────────────
        int[] colWidths = computeColWidths(contentWidths, terminalWidth);

        // ── 绘制 ─────────────────────────────────────────────────────────────
        List<String> out = new ArrayList<>();
        out.add(buildBorder(colWidths, TOP_LEFT, TOP_MID, TOP_RIGHT));

        for (int r = 0; r < rows.size(); r++) {
            out.addAll(buildWrappedDataRows(rows.get(r), colWidths, r == 0));
            if (r == 0 && rows.size() > 1) {
                out.add(buildBorder(colWidths, MID_LEFT, MID_MID, MID_RIGHT));
            }
        }

        out.add(buildBorder(colWidths, BOT_LEFT, BOT_MID, BOT_RIGHT));
        return out;
    }

    // ── 列宽计算 ─────────────────────────────────────────────────────────────

    /**
     * 根据终端宽度对各列宽度做比例收缩。
     * 若所有列内容宽度之和加上边框开销已在终端宽度内，则不收缩。
     * 否则按各列内容宽度的占比等比例压缩到可用空间。
     */
    static int[] computeColWidths(int[] contentWidths, int terminalWidth) {
        int colCount = contentWidths.length;
        // 可用于内容的总宽度 = 终端宽度 - 左侧"│" - 每列" │"
        int available = terminalWidth - BORDER_OVERHEAD_LEFT - colCount * BORDER_OVERHEAD_PER_COL;
        available = Math.max(available, colCount * MIN_COL_WIDTH);

        int totalContent = Arrays.stream(contentWidths).sum();
        if (totalContent <= available) {
            return contentWidths.clone();
        }

        // 按比例分配，确保每列至少 MIN_COL_WIDTH
        int[] colWidths = new int[colCount];
        int remaining = available;
        // 先给每列分配最小宽度
        for (int c = 0; c < colCount; c++) {
            colWidths[c] = MIN_COL_WIDTH;
            remaining -= MIN_COL_WIDTH;
        }
        // 剩余空间按原始内容宽度比例分配
        if (remaining > 0 && totalContent > 0) {
            for (int c = 0; c < colCount; c++) {
                int extra = (int) Math.floor((double) contentWidths[c] / totalContent * remaining);
                colWidths[c] += extra;
            }
            // 把因取整损失的空间补给最宽的列
            int distributed = Arrays.stream(colWidths).sum();
            int leftover = available - distributed;
            if (leftover > 0) {
                int maxIdx = 0;
                for (int c = 1; c < colCount; c++) {
                    if (contentWidths[c] > contentWidths[maxIdx]) maxIdx = c;
                }
                colWidths[maxIdx] += leftover;
            }
        }
        return colWidths;
    }

    // ── 行渲染 ───────────────────────────────────────────────────────────────

    /**
     * 将一个逻辑行渲染为一到多个物理行（单元格内容超出列宽时自动 wrap）。
     */
    private static List<String> buildWrappedDataRows(List<String> cells, int[] colWidths, boolean isHeader) {
        int colCount = colWidths.length;

        // 对每列内容做 wrap，得到各列的物理行列表
        List<List<String>> wrappedCells = new ArrayList<>();
        int maxLines = 1;
        for (int c = 0; c < colCount; c++) {
            String cell = c < cells.size() ? cells.get(c) : "";
            List<String> lines = wrapText(cell, colWidths[c]);
            wrappedCells.add(lines);
            maxLines = Math.max(maxLines, lines.size());
        }

        // 按物理行拼装输出
        List<String> out = new ArrayList<>();
        for (int line = 0; line < maxLines; line++) {
            StringBuilder sb = new StringBuilder();
            sb.append(GRAY).append(VERTICAL).append(RESET);
            for (int c = 0; c < colCount; c++) {
                List<String> lines = wrappedCells.get(c);
                String segment = line < lines.size() ? lines.get(line) : "";
                String rendered = isHeader && line == 0
                    ? (BOLD + CYAN + segment + RESET)
                    : InlineRenderer.render(segment);
                // padding 基于渲染后字符串的可见宽度，避免 ANSI/inline-code 注入导致偏差
                int pad = colWidths[c] - InlineRenderer.displayWidth(rendered);
                sb.append(" ").append(rendered).append(" ".repeat(Math.max(0, pad) + 1));
                sb.append(GRAY).append(VERTICAL).append(RESET);
            }
            out.add(sb.toString());
        }
        return out;
    }

    /**
     * 将文本按 maxWidth（显示宽度）切割为多段。
     * 优先在空格处断行（word wrap）；若单个词超过宽度则强制截断。
     */
    static List<String> wrapText(String text, int maxWidth) {
        if (maxWidth <= 0 || text.isEmpty()) return List.of(text);
        if (InlineRenderer.displayWidth(text) <= maxWidth) return List.of(text);

        List<String> lines = new ArrayList<>();
        // 按空格分词
        String[] words = text.split(" ", -1);
        StringBuilder current = new StringBuilder();
        int currentWidth = 0;

        for (String word : words) {
            int wordWidth = InlineRenderer.displayWidth(word);

            if (currentWidth == 0) {
                // 行首：若单词本身超宽则强制截断
                if (wordWidth > maxWidth) {
                    List<String> chunks = hardWrap(word, maxWidth);
                    // 除最后一块外全部作为完整行
                    for (int i = 0; i < chunks.size() - 1; i++) {
                        lines.add(chunks.get(i));
                    }
                    current.append(chunks.get(chunks.size() - 1));
                    currentWidth = InlineRenderer.displayWidth(current.toString());
                } else {
                    current.append(word);
                    currentWidth = wordWidth;
                }
            } else {
                int spaceAndWord = 1 + wordWidth; // " word"
                if (currentWidth + spaceAndWord <= maxWidth) {
                    current.append(" ").append(word);
                    currentWidth += spaceAndWord;
                } else {
                    // 当前行放不下，换行
                    lines.add(current.toString());
                    current.setLength(0);
                    currentWidth = 0;
                    if (wordWidth > maxWidth) {
                        List<String> chunks = hardWrap(word, maxWidth);
                        for (int i = 0; i < chunks.size() - 1; i++) {
                            lines.add(chunks.get(i));
                        }
                        current.append(chunks.get(chunks.size() - 1));
                        currentWidth = InlineRenderer.displayWidth(current.toString());
                    } else {
                        current.append(word);
                        currentWidth = wordWidth;
                    }
                }
            }
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    /** 按显示宽度强制截断文本（不考虑单词边界） */
    private static List<String> hardWrap(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        int[] codePoints = text.codePoints().toArray();
        int start = 0;
        int lineWidth = 0;
        StringBuilder sb = new StringBuilder();

        for (int cp : codePoints) {
            int cpWidth = Math.max(0, org.jline.utils.WCWidth.wcwidth(cp));
            if (lineWidth + cpWidth > maxWidth && sb.length() > 0) {
                lines.add(sb.toString());
                sb.setLength(0);
                lineWidth = 0;
            }
            sb.appendCodePoint(cp);
            lineWidth += cpWidth;
        }
        if (sb.length() > 0) lines.add(sb.toString());
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    // ── 内部辅助 ─────────────────────────────────────────────────────────────

    /** 解析 `| cell1 | cell2 |` 格式，去掉首尾 `|` 后按 `|` 分割并 trim */
    private static List<String> parseRow(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|"))   trimmed = trimmed.substring(0, trimmed.length() - 1);
        return Arrays.stream(trimmed.split("\\|", -1))
                     .map(String::trim)
                     .collect(Collectors.toList());
    }

    /** 判断是否为分隔行（所有单元格仅含 `-`、`:` 和空格） */
    private static boolean isSeparatorRow(List<String> cells) {
        return cells.stream().allMatch(c -> c.matches("^[-: ]+$"));
    }

    /** 构建水平边框行，如 `┌───┬───┐` */
    private static String buildBorder(int[] colWidths, char left, char mid, char right) {
        StringBuilder sb = new StringBuilder();
        sb.append(GRAY).append(left);
        for (int c = 0; c < colWidths.length; c++) {
            sb.append(String.valueOf(HORIZONTAL).repeat(colWidths[c] + 2));
            sb.append(c < colWidths.length - 1 ? mid : right);
        }
        sb.append(RESET);
        return sb.toString();
    }
}
