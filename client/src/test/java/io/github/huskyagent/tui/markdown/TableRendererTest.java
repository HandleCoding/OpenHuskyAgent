package io.github.huskyagent.tui.markdown;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

class TableRendererTest {


    private static void assertUniformWidth(List<String> result) {
        int firstWidth = InlineRenderer.displayWidth(result.get(0));
        for (int i = 1; i < result.size(); i++) {
            assertEquals(firstWidth, InlineRenderer.displayWidth(result.get(i)),
                "row " + i + " width mismatch: " + result.get(i));
        }
    }


    @Test
    @DisplayName("simple ASCII table should align")
    void simpleAsciiTable() {
        List<String> rawLines = Arrays.asList(
            "| Name | Age |",
            "|------|-----|",
            "| Alice | 25 |",
            "| Bob | 30 |"
        );
        List<String> result = TableRenderer.render(rawLines);
        assertEquals(6, result.size(), "should have 6 output lines");
        assertUniformWidth(result);
    }

    @Test
    @DisplayName("CJK table should align")
    void chineseTable() {
        List<String> rawLines = Arrays.asList(
            "| 姓名 | 年龄 |",
            "|------|------|",
            "| 张三 | 25 |",
            "| 李四 | 30 |"
        );
        List<String> result = TableRenderer.render(rawLines);
        assertUniformWidth(result);
    }

    @Test
    @DisplayName("emoji table should align")
    void emojiTable() {
        List<String> rawLines = Arrays.asList(
            "| 状态 | 描述 |",
            "|------|------|",
            "| ✅ | 成功 |",
            "| ❌ | 失败 |",
            "| ⚠️ | 警告 |"
        );
        List<String> result = TableRenderer.render(rawLines);
        assertUniformWidth(result);
    }

    @Test
    @DisplayName("mixed content table should align")
    void mixedContentTable() {
        List<String> rawLines = Arrays.asList(
            "| 项目 | 状态 | 备注 |",
            "|------|------|------|",
            "| Task 1 | ✅ | Done |",
            "| Task 2 | ❌ | Failed |",
            "| 任务三 | ⚠️ | 需要检查 |"
        );
        List<String> result = TableRenderer.render(rawLines);
        assertUniformWidth(result);
    }


    @Test
    @DisplayName("empty table should return empty list")
    void emptyTable() {
        assertTrue(TableRenderer.render(Collections.emptyList()).isEmpty());
        assertTrue(TableRenderer.render(null).isEmpty());
    }

    @Test
    @DisplayName("single-column table should align")
    void singleColumnTable() {
        List<String> rawLines = Arrays.asList(
            "| 标题 |",
            "|------|",
            "| Content |"
        );
        List<String> result = TableRenderer.render(rawLines);
        assertUniformWidth(result);
    }

    @Test
    @DisplayName("table with inconsistent column counts should auto-fill missing cells")
    void unevenColumnsTable() {
        List<String> rawLines = Arrays.asList(
            "| A | B | C |",
            "|---|---|",
            "| 1 | 2 |",
            "| x | y | z |"
        );
        List<String> result = TableRenderer.render(rawLines);
        assertUniformWidth(result);
    }


    @Test
    @DisplayName("over-wide content wraps within columns into multiple physical lines")
    void longCellWraps() {
        List<String> rawLines = Arrays.asList(
            "| 名称 | 描述 |",
            "|------|------|",
            "| A | 这是一段很长的描述Content需要自动换行handle it才能正常显示 |"
        );
        List<String> result = TableRenderer.render(rawLines, 40);

        assertTrue(result.size() > 5, "overlong content should produce multiple physical lines, actual: " + result.size());
        assertUniformWidth(result);
    }

    @Test
    @DisplayName("content should not wrap when it is not too wide")
    void shortCellNoWrap() {
        List<String> rawLines = Arrays.asList(
            "| A | B |",
            "|---|---|",
            "| 短 | Content |"
        );
        List<String> result = TableRenderer.render(rawLines, 200);
        assertEquals(5, result.size(), "content that is not too wide should not produce extra physical lines");
        assertUniformWidth(result);
    }

    @Test
    @DisplayName("over-wide columns wrap independently and physical rows use the maximum wrapped line count")
    void multiColWrap() {
        List<String> rawLines = Arrays.asList(
            "| 列1 | 列2 |",
            "|-----|-----|",
            "| word1 word2 word3 | aaa bbb ccc ddd |"
        );
        List<String> narrow = TableRenderer.render(rawLines, 30);
        List<String> wide   = TableRenderer.render(rawLines, 200);

        assertTrue(narrow.size() >= wide.size(), "narrow terminal physical rows should be >= wide terminal");
        assertUniformWidth(narrow);
        assertUniformWidth(wide);
    }

    @Test
    @DisplayName("single overlong word without spaces should be forcibly split")
    void hardWrapLongWord() {
        List<String> result = TableRenderer.wrapText("abcdefghijklmnopqrstuvwxyz", 10);
        assertFalse(result.isEmpty());
        for (String line : result) {
            assertTrue(InlineRenderer.displayWidth(line) <= 10,
                "each split line width should be <= 10, actual: " + InlineRenderer.displayWidth(line));
        }
    }

    @Test
    @DisplayName("CJK overlong content should be forcibly split")
    void hardWrapChinese() {
        List<String> result = TableRenderer.wrapText("这是一段非常非常非常长的中文文本需要被截断", 10);
        assertFalse(result.isEmpty());
        for (String line : result) {
            assertTrue(InlineRenderer.displayWidth(line) <= 10,
                "each CJK split line width should be <= 10, actual: " + InlineRenderer.displayWidth(line));
        }
    }


    @Test
    @DisplayName("content width within terminal width should not shrink")
    void colWidthsNoShrink() {
        int[] content = {10, 20, 15};
        int[] result = TableRenderer.computeColWidths(content, 200);
        assertArrayEquals(content, result, "column widths should match content widths when no shrinking is needed");
    }

    @Test
    @DisplayName("columns should shrink proportionally when content exceeds terminal width")
    void colWidthsShrink() {
        int[] content = {50, 50};
        int terminalWidth = 40;
        int[] result = TableRenderer.computeColWidths(content, terminalWidth);

        int totalWithBorder = result[0] + result[1] + 1 + 2 * 3; // left + 2*(space+│)
        assertTrue(totalWithBorder <= terminalWidth,
            "total width after shrinking should be <= terminal width, actual: " + totalWithBorder);

        for (int w : result) {
            assertTrue(w >= 3, "each column width should be >= 3");
        }
    }

    @Test
    @DisplayName("wrapText should return a single-element list for empty string")
    void wrapTextEmpty() {
        List<String> result = TableRenderer.wrapText("", 10);
        assertEquals(1, result.size());
        assertEquals("", result.get(0));
    }

    @Test
    @DisplayName("wrapText should not wrap short text")
    void wrapTextShort() {
        List<String> result = TableRenderer.wrapText("hello", 20);
        assertEquals(1, result.size());
        assertEquals("hello", result.get(0));
    }

    @Test
    @DisplayName("wrapText wraps by spaces")
    void wrapTextWordBoundary() {
        List<String> result = TableRenderer.wrapText("hello world foo bar", 10);
        assertTrue(result.size() > 1, "should wrap");
        for (String line : result) {
            assertTrue(InlineRenderer.displayWidth(line) <= 10,
                "each line width should be <= 10, actual: " + InlineRenderer.displayWidth(line));
        }
        assertEquals("hello world foo bar", String.join(" ", result));
    }


    @Test
    @DisplayName("✅ U+2705 should be recognized as width 2")
    void checkmarkEmojiWidth() {
        assertEquals(2, InlineRenderer.displayWidth("✅"), "✅ should occupy 2 columns");
        assertEquals(2, InlineRenderer.displayWidth("❌"), "❌ should occupy 2 columns");
        assertEquals(2, InlineRenderer.displayWidth("🔄"), "🔄 should occupy 2 columns");
        assertEquals(2, InlineRenderer.displayWidth("⏳"), "⏳ should occupy 2 columns");
        assertEquals(2, InlineRenderer.displayWidth("🚀"), "🚀 should occupy 2 columns");
    }

    @Test
    @DisplayName("cell padding with inline code should be correct; rendered widths match")
    void inlineCodeCellAlignment() {
        List<String> rawLines = Arrays.asList(
            "| 端点 | 方法 | 描述 | 认证 | 限流 |",
            "|------|------|------|------|------|",
            "| `/api/users` | GET | 获取用户列表 | ✅ 需要 | 100/min |",
            "| `/api/users/{id}` | GET | 获取单个用户 | ✅ 需要 | 200/min |",
            "| `/api/users` | POST | 创建新用户 | ✅ 需要 | 50/min |",
            "| `/api/auth/login` | POST | 用户登录 | ❌ 不需要 | 10/min |"
        );
        List<String> result = TableRenderer.render(rawLines, 120);
        assertUniformWidth(result);
    }


    @Test
    @DisplayName("weather forecast table should align")
    void weatherForecastTable() {
        List<String> rawLines = Arrays.asList(
            "| 城市 | 天气 | 温度 |",
            "|------|------|------|",
            "| 北京 | 晴 ☀️ | 25°C |",
            "| 上海 | 雨 🌧️ | 18°C |",
            "| 广州 | 多云 ⛅ | 28°C |"
        );
        List<String> result = TableRenderer.render(rawLines);
        assertUniformWidth(result);
    }

    @Test
    @DisplayName("task list table should align")
    void taskListTable() {
        List<String> rawLines = Arrays.asList(
            "| 任务 | 状态 | 负责人 |",
            "|------|------|--------|",
            "| done报告 | ✅ | 张三 |",
            "| 代码审查 | 🔄 | 李四 |",
            "| 测试部署 | ⏳ | 王五 |"
        );
        List<String> result = TableRenderer.render(rawLines);
        assertUniformWidth(result);
    }

    @Test
    @DisplayName("narrow-terminal long text tables should wrap and align")
    void narrowTerminalLongDesc() {
        List<String> rawLines = Arrays.asList(
            "| Feature | Description |",
            "|------|------|",
            "| Auto wrap | When cell content exceeds the column width limit, the renderer splits it into multiple physical lines while keeping table borders aligned |",
            "| Width aware | terminal.getWidth() is read live, so the next token reflects terminal resize changes |"
        );
        List<String> result = TableRenderer.render(rawLines, 60);
        assertTrue(result.size() > 6, "long description should produce multiple physical lines");
        assertUniformWidth(result);
    }
}
