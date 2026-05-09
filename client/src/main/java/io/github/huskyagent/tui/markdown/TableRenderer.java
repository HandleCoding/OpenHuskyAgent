package io.github.huskyagent.tui.markdown;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

final class TableRenderer {

    private TableRenderer() {}

    static final int DEFAULT_TERMINAL_WIDTH = 120;

    private static final int MIN_COL_WIDTH = 3;

    private static final int BORDER_OVERHEAD_PER_COL = 3;
    private static final int BORDER_OVERHEAD_LEFT    = 1;

    private static final String RESET = InlineRenderer.RESET;
    private static final String BOLD  = InlineRenderer.BOLD;
    private static final String GRAY  = "\033[90m";
    private static final String CYAN  = "\033[36m";

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

    static List<String> render(List<String> rawLines) {
        return render(rawLines, DEFAULT_TERMINAL_WIDTH);
    }

    static List<String> render(List<String> rawLines, int terminalWidth) {
        if (rawLines == null || rawLines.isEmpty()) return List.of();

        List<List<String>> rows = new ArrayList<>();
        for (String raw : rawLines) {
            List<String> cells = parseRow(raw);
            if (cells.isEmpty() || isSeparatorRow(cells)) continue;
            rows.add(cells);
        }
        if (rows.isEmpty()) return List.of();

        int colCount = rows.stream().mapToInt(List::size).max().orElse(0);
        for (List<String> row : rows) {
            while (row.size() < colCount) row.add("");
        }

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

        int[] colWidths = computeColWidths(contentWidths, terminalWidth);

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


    /**
     * Shrinks columns proportionally when the rendered table would exceed the terminal width.
     */
    static int[] computeColWidths(int[] contentWidths, int terminalWidth) {
        int colCount = contentWidths.length;
        int available = terminalWidth - BORDER_OVERHEAD_LEFT - colCount * BORDER_OVERHEAD_PER_COL;
        available = Math.max(available, colCount * MIN_COL_WIDTH);

        int totalContent = Arrays.stream(contentWidths).sum();
        if (totalContent <= available) {
            return contentWidths.clone();
        }

        int[] colWidths = new int[colCount];
        int remaining = available;
        for (int c = 0; c < colCount; c++) {
            colWidths[c] = MIN_COL_WIDTH;
            remaining -= MIN_COL_WIDTH;
        }
        if (remaining > 0 && totalContent > 0) {
            for (int c = 0; c < colCount; c++) {
                int extra = (int) Math.floor((double) contentWidths[c] / totalContent * remaining);
                colWidths[c] += extra;
            }
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


    private static List<String> buildWrappedDataRows(List<String> cells, int[] colWidths, boolean isHeader) {
        int colCount = colWidths.length;

        List<List<String>> wrappedCells = new ArrayList<>();
        int maxLines = 1;
        for (int c = 0; c < colCount; c++) {
            String cell = c < cells.size() ? cells.get(c) : "";
            List<String> lines = wrapText(cell, colWidths[c]);
            wrappedCells.add(lines);
            maxLines = Math.max(maxLines, lines.size());
        }

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
                int pad = colWidths[c] - InlineRenderer.displayWidth(rendered);
                sb.append(" ").append(rendered).append(" ".repeat(Math.max(0, pad) + 1));
                sb.append(GRAY).append(VERTICAL).append(RESET);
            }
            out.add(sb.toString());
        }
        return out;
    }

    /**
     * Wraps text on word boundaries first, then falls back to hard wrapping for
     * overlong tokens such as unbroken paths or CJK text.
     */
    static List<String> wrapText(String text, int maxWidth) {
        if (maxWidth <= 0 || text.isEmpty()) return List.of(text);
        if (InlineRenderer.displayWidth(text) <= maxWidth) return List.of(text);

        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ", -1);
        StringBuilder current = new StringBuilder();
        int currentWidth = 0;

        for (String word : words) {
            int wordWidth = InlineRenderer.displayWidth(word);

            if (currentWidth == 0) {
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
            } else {
                int spaceAndWord = 1 + wordWidth; // " word"
                if (currentWidth + spaceAndWord <= maxWidth) {
                    current.append(" ").append(word);
                    currentWidth += spaceAndWord;
                } else {
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


    private static List<String> parseRow(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|"))   trimmed = trimmed.substring(0, trimmed.length() - 1);
        return Arrays.stream(trimmed.split("\\|", -1))
                     .map(String::trim)
                     .collect(Collectors.toList());
    }

    private static boolean isSeparatorRow(List<String> cells) {
        return cells.stream().allMatch(c -> c.matches("^[-: ]+$"));
    }

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
