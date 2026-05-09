package io.github.huskyagent.tui.markdown;

final class BlockRenderer {

    private BlockRenderer() {}

    private static final String RESET  = InlineRenderer.RESET;
    private static final String BOLD   = InlineRenderer.BOLD;
    private static final String CYAN   = "\033[36m";
    private static final String GRAY   = "\033[90m";
    private static final String GREEN  = "\033[32m";

    static String renderHeading(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') level++;
        String text = line.substring(level).trim();
        String indent = "  ".repeat(Math.max(0, level - 1));
        String style = level <= 2 ? BOLD + CYAN : CYAN;
        return indent + style + text + RESET;
    }

    static String renderUnorderedList(String line) {
        String text = line.substring(2).trim();
        return "  " + GREEN + "•" + RESET + " " + InlineRenderer.render(text);
    }

    static String renderOrderedList(String line) {
        int dotIdx = line.indexOf('.');
        String num  = line.substring(0, dotIdx + 1);
        String text = line.substring(dotIdx + 1).trim();
        return "  " + BOLD + num + RESET + " " + InlineRenderer.render(text);
    }

    static String renderBlockquote(String line) {
        String text = line.startsWith("> ") ? line.substring(2) : "";
        return GRAY + "▎ " + InlineRenderer.render(text) + RESET;
    }

    static String renderCodeFenceLine(boolean opening, String lang) {
        if (opening) {
            String langLabel = (lang == null || lang.isBlank()) ? "" : " " + CYAN + lang + GRAY;
            return GRAY + "  ╔═ code" + langLabel + GRAY + " ═" + RESET;
        } else {
            return GRAY + "  ╚══════════════════" + RESET;
        }
    }

    static String renderCodeLine(String line) {
        return GRAY + "  ║ " + line + RESET;
    }
}
