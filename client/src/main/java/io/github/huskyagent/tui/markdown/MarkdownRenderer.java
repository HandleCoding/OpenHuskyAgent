package io.github.huskyagent.tui.markdown;

import java.util.ArrayList;
import java.util.List;

public class MarkdownRenderer {

    /** Tracks whether the next line should be rendered as prose, fenced code, or a buffered table row. */
    public enum RenderState { NORMAL, CODE_BLOCK, TABLE }

    private RenderState state = RenderState.NORMAL;
    private String codeBlockLang = "";
    private final List<String> tableBuffer = new ArrayList<>();

    private int terminalWidth = TableRenderer.DEFAULT_TERMINAL_WIDTH;


    public void setTerminalWidth(int width) {
        if (width > 0) this.terminalWidth = width;
    }

    public void reset() {
        state = RenderState.NORMAL;
        codeBlockLang = "";
        tableBuffer.clear();
    }

    public List<String> processLine(String line) {
        return switch (state) {
            case NORMAL     -> processNormalLine(line);
            case CODE_BLOCK -> processCodeBlockLine(line);
            case TABLE      -> processTableLine(line);
        };
    }

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
            case NORMAL -> { /* no action */ }
        }
        return result;
    }

    public RenderState getState() {
        return state;
    }

    public String renderInlinePartial(String fragment) {
        return InlineRenderer.renderPartial(fragment);
    }


    private List<String> processNormalLine(String line) {
        if (line.isBlank()) return List.of("");

        if (line.startsWith("```")) {
            codeBlockLang = line.substring(3).trim();
            state = RenderState.CODE_BLOCK;
            return List.of(BlockRenderer.renderCodeFenceLine(true, codeBlockLang));
        }

        if (isTableRow(line)) {
            state = RenderState.TABLE;
            tableBuffer.clear();
            tableBuffer.add(line);
            return List.of();
        }

        if (line.startsWith("#")) return List.of(BlockRenderer.renderHeading(line));

        if (line.matches("^[\\-\\*\\+] .+")) return List.of(BlockRenderer.renderUnorderedList(line));

        if (line.matches("^\\d+\\. .+")) return List.of(BlockRenderer.renderOrderedList(line));

        if (line.startsWith("> ") || line.equals(">")) return List.of(BlockRenderer.renderBlockquote(line));

        return List.of(InlineRenderer.render(line));
    }

    private List<String> processCodeBlockLine(String line) {
        if (line.startsWith("```")) {
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
        List<String> result = new ArrayList<>(TableRenderer.render(tableBuffer, terminalWidth));
        tableBuffer.clear();
        state = RenderState.NORMAL;
        result.addAll(processNormalLine(line));
        return result;
    }


    private static boolean isTableRow(String line) {
        String t = line.trim();
        return t.startsWith("|") && (t.endsWith("|") || t.contains("|"));
    }
}
