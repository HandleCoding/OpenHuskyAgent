package io.github.huskyagent.tui.markdown;

import org.jline.utils.WCWidth;

/**
 * 行内 Markdown 元素渲染器（无状态，静态工具类）。
 *
 * <p>处理：粗体、斜体、行内代码。
 * 正则天然只匹配已闭合的标记，未闭合的标记原样输出，因此 {@link #renderPartial}
 * 可安全用于流式实时回显（token 片段可能不完整）。</p>
 */
final class InlineRenderer {

    private InlineRenderer() {}

    // ── ANSI 常量 ────────────────────────────────────────────────────────────
    static final String RESET  = "\033[0m";
    static final String BOLD   = "\033[1m";
    static final String ITALIC = "\033[3m";
    static final String YELLOW = "\033[33m";

    /**
     * 对完整行做行内渲染。
     * 顺序：先处理行内代码（防止代码内的 * 被误解析），再处理粗体/斜体。
     */
    static String render(String text) {
        if (text == null || text.isEmpty()) return text;
        // 行内代码：`code` → 黄色
        text = text.replaceAll("`([^`]+)`", YELLOW + "$1" + RESET);
        // 粗体：**text** 或 __text__（非贪婪）
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", BOLD + "$1" + RESET);
        text = text.replaceAll("__(.+?)__",          BOLD + "$1" + RESET);
        // 斜体：*text* 或 _text_（排除与粗体重叠）
        text = text.replaceAll("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)", ITALIC + "$1" + RESET);
        text = text.replaceAll("(?<!_)_(?!_)(.+?)(?<!_)_(?!_)",             ITALIC + "$1" + RESET);
        return text;
    }

    /**
     * 对片段做保守行内渲染（仅替换已闭合标记），用于流式实时回显。
     * 与 {@link #render} 逻辑相同，正则本身只匹配闭合对，未闭合的不受影响。
     */
    static String renderPartial(String fragment) {
        return render(fragment);
    }

    /**
     * 计算字符串的终端显示宽度。
     * CJK、全角、Emoji 等宽字符占 2 列，ANSI 转义序列不占宽度，其余占 1 列。
     *
     * <p>WCWidth 对 "East Asian Ambiguous" 类字符（✅❌⚠️等）返回 1，
     * 但现代终端（iTerm2、Terminal.app、VTE）几乎全部将它们渲染为 2 列宽。
     * 此方法对这些 Ambiguous 字符修正为 2，确保表格边框对齐。</p>
     */
    static int displayWidth(String s) {
        if (s == null) return 0;
        int width = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);

            // 跳过 ANSI 转义序列（ESC [ ... m）
            if (cp == 0x1B && i < s.length() && s.charAt(i) == '[') {
                i++; // skip '['
                while (i < s.length() && s.charAt(i) != 'm') i++;
                if (i < s.length()) i++; // skip 'm'
                continue;
            }

            int w = WCWidth.wcwidth(cp);
            if (w < 0) continue;          // 不可打印 → 0 宽度
            if (w == 1 && isAmbiguous(cp)) {
                w = 2;                    // Ambiguous 在现代终端实际占 2 列
            }
            width += w;
        }
        return width;
    }

    /**
     * 判断 Unicode code point 是否属于 "East Asian Ambiguous Width" 类别
     * 且在现代终端中实际占据 2 列宽度。
     *
     * <p>Box Drawing 和 Block Elements（U+2500–U+259F）虽然是 Ambiguous 类别，
     * 但在所有终端中都显示为 1 列，不应修正。只有符号类 Ambiguous 字符
     * （✅❌⚠️☀★→ etc）在现代终端中实际占 2 列，需要修正。</p>
     *
     * 参考: Unicode Standard Annex #11 (East Asian Width), category 'A'.
     */
    private static boolean isAmbiguous(int cp) {
        // U+2000–U+206F: General Punctuation (dashes, quotes — many ambiguous)
        // U+2100–U+214F: Letterlike Symbols (℠™℃ etc)
        // U+2150–U+218F: Number Forms (½⅓ etc)
        // U+2190–U+21FF: Arrows (←→↑↓ etc)
        // U+2200–U+22FF: Mathematical Operators (√∞ etc)
        // U+2300–U+23FF: Miscellaneous Technical (⌘⌥ etc)
        // U+2400–U+245F: Control Pictures + OCR
        // U+2460–U+24FF: Enclosed Alphanumerics (①② etc)
        // U+25A0–U+25FF: Geometric Shapes (◆○●■△▼▸◀ etc)
        // U+2600–U+26FF: Miscellaneous Symbols (✅❌⚠☀★ etc)
        // U+2700–U+27BF: Dingbats (✂✈✉✓✗ etc)
        // U+2A00–U+2AFF: Supplementary Mathematical Operators
        // U+2B00–U+2BFF: Miscellaneous Symbols and Arrows
        // U+1F000–U+1F02F: Mahjong Tiles
        // U+1F0A0–U+1F0FF: Playing Cards
        // U+1F100–U+1F1FF: Enclosed Alphanumeric Supp / Regional
        // U+3000:        Ideographic Space
        //
        // NOT included (display as 1 column in all terminals):
        // U+2500–U+257F: Box Drawing (─│┌┐ etc) — 1 column
        // U+2580–U+259F: Block Elements — 1 column
        return (cp >= 0x2000 && cp <= 0x206F)    // General Punctuation
            || (cp >= 0x2100 && cp <= 0x214F)    // Letterlike Symbols
            || (cp >= 0x2150 && cp <= 0x218F)    // Number Forms
            || (cp >= 0x2190 && cp <= 0x21FF)    // Arrows
            || (cp >= 0x2200 && cp <= 0x22FF)    // Mathematical Operators
            || (cp >= 0x2300 && cp <= 0x23FF)    // Miscellaneous Technical
            || (cp >= 0x2400 && cp <= 0x245F)    // Control Pictures + OCR
            || (cp >= 0x2460 && cp <= 0x24FF)    // Enclosed Alphanumerics
            || (cp >= 0x25A0 && cp <= 0x25FF)    // Geometric Shapes (NOT Box Drawing/Block Elements)
            || (cp >= 0x2600 && cp <= 0x26FF)    // Miscellaneous Symbols
            || (cp >= 0x2700 && cp <= 0x27BF)    // Dingbats
            || (cp >= 0x2A00 && cp <= 0x2AFF)    // Supp. Mathematical Operators
            || (cp >= 0x2B00 && cp <= 0x2BFF)    // Misc Symbols and Arrows
            || (cp >= 0x1F000 && cp <= 0x1F02F)  // Mahjong Tiles
            || (cp >= 0x1F0A0 && cp <= 0x1F0FF)  // Playing Cards
            || (cp >= 0x1F100 && cp <= 0x1F1FF)  // Enclosed Alphanumeric Supp / Regional
            || (cp >= 0x1F300 && cp <= 0x1F5FF)  // Misc Symbols and Pictographs (✅U+2705, 🔄 etc)
            || (cp >= 0x1F600 && cp <= 0x1F64F)  // Emoticons (😀 etc)
            || (cp >= 0x1F680 && cp <= 0x1F6FF)  // Transport and Map Symbols (🚀 etc)
            || (cp >= 0x1F700 && cp <= 0x1F77F)  // Alchemical Symbols
            || (cp >= 0x1F780 && cp <= 0x1F7FF)  // Geometric Shapes Extended
            || (cp >= 0x1F800 && cp <= 0x1F8FF)  // Supplemental Arrows-C
            || (cp >= 0x1F900 && cp <= 0x1F9FF)  // Supplemental Symbols and Pictographs (⏳🔍 etc)
            || (cp >= 0x1FA00 && cp <= 0x1FA6F)  // Chess Symbols
            || (cp >= 0x1FA70 && cp <= 0x1FAFF)  // Symbols and Pictographs Extended-A
            || (cp == 0x3000);                    // Ideographic Space
    }
}
