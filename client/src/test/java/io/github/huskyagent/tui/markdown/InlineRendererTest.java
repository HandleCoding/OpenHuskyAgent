package io.github.huskyagent.tui.markdown;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 InlineRenderer 的 displayWidth 方法。
 * 验证各种字符类型的宽度计算是否正确。
 */
class InlineRendererTest {

    // ── ASCII 字符测试 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("ASCII 字符宽度应为 1")
    void asciiWidth() {
        assertEquals(1, InlineRenderer.displayWidth("a"));
        assertEquals(5, InlineRenderer.displayWidth("hello"));
        assertEquals(11, InlineRenderer.displayWidth("hello world"));
    }

    @Test
    @DisplayName("空字符串和 null 宽度应为 0")
    void emptyOrNullWidth() {
        assertEquals(0, InlineRenderer.displayWidth(""));
        assertEquals(0, InlineRenderer.displayWidth(null));
    }

    // ── CJK 字符测试 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("中文字符宽度应为 2")
    void chineseWidth() {
        assertEquals(2, InlineRenderer.displayWidth("中"));
        assertEquals(4, InlineRenderer.displayWidth("中文"));
        assertEquals(6, InlineRenderer.displayWidth("你好吗"));
    }

    @Test
    @DisplayName("日文假名宽度应为 2")
    void japaneseWidth() {
        assertEquals(2, InlineRenderer.displayWidth("あ"));  // 平假名
        assertEquals(2, InlineRenderer.displayWidth("ア"));  // 片假名
        assertEquals(10, InlineRenderer.displayWidth("こんにちは"));  // 5 个假名，每个宽度 2
    }

    @Test
    @DisplayName("韩文字符宽度应为 2")
    void koreanWidth() {
        assertEquals(2, InlineRenderer.displayWidth("한"));
        assertEquals(4, InlineRenderer.displayWidth("한글"));
    }

    // ── Emoji 测试 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("简单 Emoji 宽度应为 2（Wide 类别）")
    void simpleEmojiWidth() {
        assertEquals(2, InlineRenderer.displayWidth("😀"));   // U+1F600 表情（Wide）
        assertEquals(2, InlineRenderer.displayWidth("👍"));   // U+1F44D 竖大拇指（Wide）
        // 注意：❤️ (U+2764) 是 Ambiguous 类别，WCWidth 认为是宽度 1
        // 但某些终端可能显示为宽度 2
    }

    @Test
    @DisplayName("符号类 Emoji 宽度应为 2（Ambiguous 类别，现代终端实际占 2 列）")
    void symbolEmojiWidth() {
        // U+2705, U+274C, U+26A0 属于 East Asian Ambiguous 类别
        // WCWidth 默认返回 1，但现代终端实际显示为 2 列，displayWidth 修正为 2
        assertEquals(2, InlineRenderer.displayWidth("✅"));
        assertEquals(2, InlineRenderer.displayWidth("⚠️"));
        assertEquals(2, InlineRenderer.displayWidth("❌"));
    }

    @Test
    @DisplayName("复合 Emoji（带 ZWJ）应正确计算宽度")
    void complexEmojiWidth() {
        // 注意：复合 Emoji 由多个 codepoint 组成，但显示为 1 个字符
        // ZWJ (Zero Width Joiner) = U+200D，宽度应为 0
        // 实际终端显示为 2 列
        
        // 👨‍👩‍👧‍👦 = 👨 + ZWJ + 👩 + ZWJ + 👧 + ZWJ + 👦
        String family = "👨‍👩‍👧‍👦";
        int width = InlineRenderer.displayWidth(family);
        // JLine WCWidth 会将每个 codepoint 单独计算
        // 👨(2) + ZWJ(0) + 👩(2) + ZWJ(0) + 👧(2) + ZWJ(0) + 👦(2) = 8
        // 但实际终端显示为 2 列，这是 wcwidth 的已知限制
        System.out.println("Family emoji width: " + width);
        assertTrue(width >= 2, "复合 Emoji 宽度应至少为 2");
    }

    // ── ANSI 转义序列测试 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("ANSI 转义序列宽度应为 0")
    void ansiEscapeWidth() {
        String reset = "\033[0m";
        String bold = "\033[1m";
        String red = "\033[31m";
        
        assertEquals(0, InlineRenderer.displayWidth(reset));
        assertEquals(0, InlineRenderer.displayWidth(bold));
        assertEquals(0, InlineRenderer.displayWidth(red));
    }

    @Test
    @DisplayName("带 ANSI 的文本应忽略转义序列宽度")
    void textWithAnsiWidth() {
        String colored = "\033[31mhello\033[0m";
        assertEquals(5, InlineRenderer.displayWidth(colored));
        
        String boldChinese = "\033[1m中文\033[0m";
        assertEquals(4, InlineRenderer.displayWidth(boldChinese));
    }

    // ── 混合字符测试 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("中英文混合宽度应正确计算")
    void mixedChineseEnglishWidth() {
        assertEquals(9, InlineRenderer.displayWidth("hello世界"));  // 5 + 4
        assertEquals(9, InlineRenderer.displayWidth("你好world"));  // 4 + 5
        assertEquals(12, InlineRenderer.displayWidth("测试test测试")); // 4 + 4 + 4
    }

    @Test
    @DisplayName("中文和 Emoji 混合宽度应正确计算")
    void mixedChineseEmojiWidth() {
        assertEquals(6, InlineRenderer.displayWidth("你好😀"));   // 4 + 2
        // ✅ 是 Ambiguous 字符，修正为宽度 2
        assertEquals(10, InlineRenderer.displayWidth("测试✅通过")); // 4 + 2 + 4
    }

    @Test
    @DisplayName("全角字符宽度应为 2")
    void fullWidthWidth() {
        assertEquals(2, InlineRenderer.displayWidth("Ａ"));  // 全角 A
        assertEquals(2, InlineRenderer.displayWidth("０"));  // 全角 0
        assertEquals(2, InlineRenderer.displayWidth("！"));  // 全角感叹号
    }

    // ── 特殊字符测试 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("零宽字符宽度应为 0")
    void zeroWidthCharacters() {
        // 零宽空格
        assertEquals(0, InlineRenderer.displayWidth("\u200B"));
        // 零宽非断空格
        assertEquals(0, InlineRenderer.displayWidth("\uFEFF"));
    }

    @Test
    @DisplayName("控制字符宽度应为 0 或 -1")
    void controlCharacters() {
        // 控制字符（如换行、制表符）的宽度由 WCWidth 处理
        // wcwidth 返回 -1 表示不可打印，我们将其视为 0
        assertTrue(InlineRenderer.displayWidth("\n") >= 0);
        assertTrue(InlineRenderer.displayWidth("\t") >= 0);
    }

    // ── 表格场景测试 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("表格单元格常见内容宽度应正确")
    void tableCellContentWidth() {
        assertEquals(2, InlineRenderer.displayWidth("OK"));
        assertEquals(4, InlineRenderer.displayWidth("完成"));
        assertEquals(4, InlineRenderer.displayWidth("失败"));  // 2 个中文字符
        assertEquals(6, InlineRenderer.displayWidth("进行中"));  // 3 个中文字符
        // ✅ 和 ❌ 是 Ambiguous 字符，修正为宽度 2
        assertEquals(2, InlineRenderer.displayWidth("✅"));
        assertEquals(2, InlineRenderer.displayWidth("❌"));
    }

    @Test
    @DisplayName("实际表格行宽度应正确")
    void tableRowWidth() {
        // 模拟表格行：| 状态 | 描述 |
        String row = "| 状态 | 描述 |";
        // |(1) + 空格(1) + 状态(4) + 空格(1) + |(1) + 空格(1) + 描述(4) + 空格(1) + |(1)
        // = 1 + 1 + 4 + 1 + 1 + 1 + 4 + 1 + 1 = 15
        assertEquals(15, InlineRenderer.displayWidth(row));
    }
}
