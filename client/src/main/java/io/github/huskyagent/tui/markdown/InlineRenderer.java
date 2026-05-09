package io.github.huskyagent.tui.markdown;

import org.jline.utils.WCWidth;

final class InlineRenderer {

    private InlineRenderer() {}

    static final String RESET  = "\033[0m";
    static final String BOLD   = "\033[1m";
    static final String ITALIC = "\033[3m";
    static final String YELLOW = "\033[33m";

    static String render(String text) {
        if (text == null || text.isEmpty()) return text;
        text = text.replaceAll("`([^`]+)`", YELLOW + "$1" + RESET);
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", BOLD + "$1" + RESET);
        text = text.replaceAll("__(.+?)__",          BOLD + "$1" + RESET);
        text = text.replaceAll("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)", ITALIC + "$1" + RESET);
        text = text.replaceAll("(?<!_)_(?!_)(.+?)(?<!_)_(?!_)",             ITALIC + "$1" + RESET);
        return text;
    }

    static String renderPartial(String fragment) {
        return render(fragment);
    }

    /** Measures rendered width after stripping ANSI sequences and widening ambiguous glyphs for CJK terminals. */
    static int displayWidth(String s) {
        if (s == null) return 0;
        int width = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);

            if (cp == 0x1B && i < s.length() && s.charAt(i) == '[') {
                i++; // skip '['
                while (i < s.length() && s.charAt(i) != 'm') i++;
                if (i < s.length()) i++; // skip 'm'
                continue;
            }

            int w = WCWidth.wcwidth(cp);
            if (w < 0) continue;
            if (w == 1 && isAmbiguous(cp)) {
                w = 2;
            }
            width += w;
        }
        return width;
    }

    private static boolean isAmbiguous(int cp) {
        // Treat emoji-like and East Asian ambiguous symbols as double-width, but leave box drawing characters alone.
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
