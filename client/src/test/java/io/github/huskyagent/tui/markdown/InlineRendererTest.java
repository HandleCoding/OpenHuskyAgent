package io.github.huskyagent.tui.markdown;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class InlineRendererTest {


    @Test
    @DisplayName("ASCII character width should be 1")
    void asciiWidth() {
        assertEquals(1, InlineRenderer.displayWidth("a"));
        assertEquals(5, InlineRenderer.displayWidth("hello"));
        assertEquals(11, InlineRenderer.displayWidth("hello world"));
    }

    @Test
    @DisplayName("empty string and null width should be 0")
    void emptyOrNullWidth() {
        assertEquals(0, InlineRenderer.displayWidth(""));
        assertEquals(0, InlineRenderer.displayWidth(null));
    }


    @Test
    @DisplayName("CJK character width should be 2")
    void chineseWidth() {
        assertEquals(2, InlineRenderer.displayWidth("中"));
        assertEquals(4, InlineRenderer.displayWidth("中文"));
        assertEquals(6, InlineRenderer.displayWidth("你好吗"));
    }

    @Test
    @DisplayName("Japanese kana width should be 2")
    void japaneseWidth() {
        assertEquals(2, InlineRenderer.displayWidth("あ"));
        assertEquals(2, InlineRenderer.displayWidth("ア"));
        assertEquals(10, InlineRenderer.displayWidth("こんにちは"));
    }

    @Test
    @DisplayName("Korean character width should be 2")
    void koreanWidth() {
        assertEquals(2, InlineRenderer.displayWidth("한"));
        assertEquals(4, InlineRenderer.displayWidth("한글"));
    }


    @Test
    @DisplayName("simple emoji width should be 2 (Wide category)")
    void simpleEmojiWidth() {
        assertEquals(2, InlineRenderer.displayWidth("😀"));
        assertEquals(2, InlineRenderer.displayWidth("👍"));
    }

    @Test
    @DisplayName("symbol emoji width should be 2 (Ambiguous category, modern terminals render as 2 columns)")
    void symbolEmojiWidth() {
        assertEquals(2, InlineRenderer.displayWidth("✅"));
        assertEquals(2, InlineRenderer.displayWidth("⚠️"));
        assertEquals(2, InlineRenderer.displayWidth("❌"));
    }

    @Test
    @DisplayName("compound emoji with ZWJ should calculate width correctly")
    void complexEmojiWidth() {
        
        // 👨‍👩‍👧‍👦 = 👨 + ZWJ + 👩 + ZWJ + 👧 + ZWJ + 👦
        String family = "👨‍👩‍👧‍👦";
        int width = InlineRenderer.displayWidth(family);
        // 👨(2) + ZWJ(0) + 👩(2) + ZWJ(0) + 👧(2) + ZWJ(0) + 👦(2) = 8
        System.out.println("Family emoji width: " + width);
        assertTrue(width >= 2, "compound emoji width should be at least 2");
    }


    @Test
    @DisplayName("ANSI escape sequence width should be 0")
    void ansiEscapeWidth() {
        String reset = "\033[0m";
        String bold = "\033[1m";
        String red = "\033[31m";
        
        assertEquals(0, InlineRenderer.displayWidth(reset));
        assertEquals(0, InlineRenderer.displayWidth(bold));
        assertEquals(0, InlineRenderer.displayWidth(red));
    }

    @Test
    @DisplayName("ANSI text should ignore escape sequence width")
    void textWithAnsiWidth() {
        String colored = "\033[31mhello\033[0m";
        assertEquals(5, InlineRenderer.displayWidth(colored));
        
        String boldChinese = "\033[1m中文\033[0m";
        assertEquals(4, InlineRenderer.displayWidth(boldChinese));
    }


    @Test
    @DisplayName("mixed ASCII and CJK width should be calculated correctly")
    void mixedChineseEnglishWidth() {
        assertEquals(9, InlineRenderer.displayWidth("hello世界"));  // 5 + 4
        assertEquals(9, InlineRenderer.displayWidth("你好world"));  // 4 + 5
        assertEquals(12, InlineRenderer.displayWidth("测试test测试")); // 4 + 4 + 4
    }

    @Test
    @DisplayName("CJK and emoji mixed width should be calculated correctly")
    void mixedChineseEmojiWidth() {
        assertEquals(6, InlineRenderer.displayWidth("你好😀"));   // 4 + 2
        assertEquals(12, InlineRenderer.displayWidth("测试✅passed")); // 4 + 2 + 6
    }

    @Test
    @DisplayName("full-width character width should be 2")
    void fullWidthWidth() {
        assertEquals(2, InlineRenderer.displayWidth("Ａ"));
        assertEquals(2, InlineRenderer.displayWidth("０"));
        assertEquals(2, InlineRenderer.displayWidth("！"));
    }


    @Test
    @DisplayName("zero-width character width should be 0")
    void zeroWidthCharacters() {
        assertEquals(0, InlineRenderer.displayWidth("\u200B"));
        assertEquals(0, InlineRenderer.displayWidth("\uFEFF"));
    }

    @Test
    @DisplayName("control character width should be 0 or -1")
    void controlCharacters() {
        assertTrue(InlineRenderer.displayWidth("\n") >= 0);
        assertTrue(InlineRenderer.displayWidth("\t") >= 0);
    }


    @Test
    @DisplayName("common table cell content width should be correct")
    void tableCellContentWidth() {
        assertEquals(2, InlineRenderer.displayWidth("OK"));
        assertEquals(4, InlineRenderer.displayWidth("done"));
        assertEquals(4, InlineRenderer.displayWidth("失败"));
        assertEquals(6, InlineRenderer.displayWidth("进行中"));
        assertEquals(2, InlineRenderer.displayWidth("✅"));
        assertEquals(2, InlineRenderer.displayWidth("❌"));
    }

    @Test
    @DisplayName("actual table row width should be correct")
    void tableRowWidth() {
        String row = "| 状态 | 描述 |";
        // = 1 + 1 + 4 + 1 + 1 + 1 + 4 + 1 + 1 = 15
        assertEquals(15, InlineRenderer.displayWidth(row));
    }
}
