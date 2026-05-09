package io.github.huskyagent.infra.memory;

/**
 * CJK (Chinese, Japanese, Korean) 字符检测工具
 *
 * 用于判断查询字符串是否包含 CJK 字符，决定搜索路径：
 * FTS5 MATCH → 空结果 + CJK → LIKE fallback
 */
public final class CJKDetector {

    private CJKDetector() {}

    public static boolean containsCJK(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            int cp = text.codePointAt(i);
            if (isCJK(cp)) return true;
            if (Character.isSupplementaryCodePoint(cp)) i++;
        }
        return false;
    }

    private static boolean isCJK(int cp) {
        return (0x4E00 <= cp && cp <= 0x9FFF)   // CJK Unified Ideographs
            || (0x3400 <= cp && cp <= 0x4DBF)   // CJK Extension A
            || (0x20000 <= cp && cp <= 0x2A6DF) // CJK Extension B
            || (0x3000 <= cp && cp <= 0x303F)   // CJK Symbols and Punctuation
            || (0x3040 <= cp && cp <= 0x309F)   // Hiragana
            || (0x30A0 <= cp && cp <= 0x30FF)   // Katakana
            || (0xAC00 <= cp && cp <= 0xD7AF);  // Hangul Syllables
    }
}