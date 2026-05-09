package io.github.huskyagent.tui.markdown;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

/**
 * 测试 TableRenderer 的表格渲染功能。
 * 验证表格边框对齐、列宽计算、自动换行。
 */
class TableRendererTest {

    // ── 辅助 ──────────────────────────────────────────────────────────────────

    /** 断言结果中所有行的显示宽度一致 */
    private static void assertUniformWidth(List<String> result) {
        int firstWidth = InlineRenderer.displayWidth(result.get(0));
        for (int i = 1; i < result.size(); i++) {
            assertEquals(firstWidth, InlineRenderer.displayWidth(result.get(i)),
                "第 " + i + " 行宽度不一致: " + result.get(i));
        }
    }

    // ── 基础表格测试 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("简单 ASCII 表格应对齐")
    void simpleAsciiTable() {
        List<String> rawLines = Arrays.asList(
            "| Name | Age |",
            "|------|-----|",
            "| Alice | 25 |",
            "| Bob | 30 |"
        );
        List<String> result = TableRenderer.render(rawLines);
        // 顶边框 + 表头 + 分隔线 + 2个数据行 + 底边框 = 6 行
        assertEquals(6, result.size(), "应有 6 行输出");
        assertUniformWidth(result);
    }

    @Test
    @DisplayName("中文表格应对齐")
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
    @DisplayName("带 Emoji 的表格应对齐")
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
    @DisplayName("混合内容表格应对齐")
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

    // ── 边界情况测试 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("空表格应返回空列表")
    void emptyTable() {
        assertTrue(TableRenderer.render(Collections.emptyList()).isEmpty());
        assertTrue(TableRenderer.render(null).isEmpty());
    }

    @Test
    @DisplayName("单列表格应对齐")
    void singleColumnTable() {
        List<String> rawLines = Arrays.asList(
            "| 标题 |",
            "|------|",
            "| 内容 |"
        );
        List<String> result = TableRenderer.render(rawLines);
        assertUniformWidth(result);
    }

    @Test
    @DisplayName("列数不一致的表格应自动补齐")
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

    // ── 自动换行测试 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("超宽内容应在列内自动换行，逻辑行变为多个物理行")
    void longCellWraps() {
        List<String> rawLines = Arrays.asList(
            "| 名称 | 描述 |",
            "|------|------|",
            "| A | 这是一段很长的描述内容需要自动换行处理才能正常显示 |"
        );
        // 用 40 列的窄终端强制触发 wrap
        List<String> result = TableRenderer.render(rawLines, 40);

        // 数据行应超过 1 个物理行（wrap 生效）：顶 + 表头 + 分隔 + 数据行(≥2) + 底
        assertTrue(result.size() > 5, "超长内容应产生多个物理行，实际: " + result.size());
        assertUniformWidth(result);
    }

    @Test
    @DisplayName("内容未超宽时不应换行")
    void shortCellNoWrap() {
        List<String> rawLines = Arrays.asList(
            "| A | B |",
            "|---|---|",
            "| 短 | 内容 |"
        );
        // 宽终端，不应 wrap
        List<String> result = TableRenderer.render(rawLines, 200);
        // 顶 + 表头 + 分隔 + 1数据行 + 底 = 5
        assertEquals(5, result.size(), "内容未超宽不应产生额外物理行");
        assertUniformWidth(result);
    }

    @Test
    @DisplayName("多列都超宽时各列独立换行，物理行数取最多列的行数")
    void multiColWrap() {
        List<String> rawLines = Arrays.asList(
            "| 列1 | 列2 |",
            "|-----|-----|",
            "| word1 word2 word3 | aaa bbb ccc ddd |"
        );
        List<String> narrow = TableRenderer.render(rawLines, 30);
        List<String> wide   = TableRenderer.render(rawLines, 200);

        assertTrue(narrow.size() >= wide.size(), "窄终端物理行数应 >= 宽终端");
        assertUniformWidth(narrow);
        assertUniformWidth(wide);
    }

    @Test
    @DisplayName("单个超长无空格词应强制截断")
    void hardWrapLongWord() {
        List<String> result = TableRenderer.wrapText("abcdefghijklmnopqrstuvwxyz", 10);
        assertFalse(result.isEmpty());
        for (String line : result) {
            assertTrue(InlineRenderer.displayWidth(line) <= 10,
                "截断后每行宽度应 <= 10，实际: " + InlineRenderer.displayWidth(line));
        }
    }

    @Test
    @DisplayName("中文超长内容应强制截断")
    void hardWrapChinese() {
        List<String> result = TableRenderer.wrapText("这是一段非常非常非常长的中文文本需要被截断", 10);
        assertFalse(result.isEmpty());
        for (String line : result) {
            assertTrue(InlineRenderer.displayWidth(line) <= 10,
                "中文截断后每行宽度应 <= 10，实际: " + InlineRenderer.displayWidth(line));
        }
    }

    // ── 列宽计算测试 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("内容总宽在终端宽度内时不应收缩")
    void colWidthsNoShrink() {
        int[] content = {10, 20, 15};
        int[] result = TableRenderer.computeColWidths(content, 200);
        assertArrayEquals(content, result, "无需收缩时列宽应与内容宽度一致");
    }

    @Test
    @DisplayName("内容超出终端宽度时各列应按比例收缩")
    void colWidthsShrink() {
        int[] content = {50, 50};
        int terminalWidth = 40;
        int[] result = TableRenderer.computeColWidths(content, terminalWidth);

        // 总宽度 + 边框开销应 <= terminalWidth
        int totalWithBorder = result[0] + result[1] + 1 + 2 * 3; // left + 2*(space+│)
        assertTrue(totalWithBorder <= terminalWidth,
            "收缩后总宽度应 <= 终端宽度，实际: " + totalWithBorder);

        // 每列宽度应 >= MIN_COL_WIDTH
        for (int w : result) {
            assertTrue(w >= 3, "每列宽度应 >= 3");
        }
    }

    @Test
    @DisplayName("wrapText 空字符串应返回单元素列表")
    void wrapTextEmpty() {
        List<String> result = TableRenderer.wrapText("", 10);
        assertEquals(1, result.size());
        assertEquals("", result.get(0));
    }

    @Test
    @DisplayName("wrapText 短文本不触发换行")
    void wrapTextShort() {
        List<String> result = TableRenderer.wrapText("hello", 20);
        assertEquals(1, result.size());
        assertEquals("hello", result.get(0));
    }

    @Test
    @DisplayName("wrapText 按空格断词换行")
    void wrapTextWordBoundary() {
        List<String> result = TableRenderer.wrapText("hello world foo bar", 10);
        assertTrue(result.size() > 1, "应换行");
        for (String line : result) {
            assertTrue(InlineRenderer.displayWidth(line) <= 10,
                "每行宽度应 <= 10，实际: " + InlineRenderer.displayWidth(line));
        }
        // 拼回应等于原文（空格连接）
        assertEquals("hello world foo bar", String.join(" ", result));
    }

    // ── emoji 宽度 & 行内代码对齐回归测试 ────────────────────────────────────

    @Test
    @DisplayName("✅ U+2705 应被识别为宽度 2")
    void checkmarkEmojiWidth() {
        assertEquals(2, InlineRenderer.displayWidth("✅"), "✅ 应占 2 列");
        assertEquals(2, InlineRenderer.displayWidth("❌"), "❌ 应占 2 列");
        assertEquals(2, InlineRenderer.displayWidth("🔄"), "🔄 应占 2 列");
        assertEquals(2, InlineRenderer.displayWidth("⏳"), "⏳ 应占 2 列");
        assertEquals(2, InlineRenderer.displayWidth("🚀"), "🚀 应占 2 列");
    }

    @Test
    @DisplayName("含行内代码的单元格 padding 应正确（render 后宽度一致）")
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

    // ── 实际场景测试 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("天气预报表格应对齐")
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
    @DisplayName("任务列表表格应对齐")
    void taskListTable() {
        List<String> rawLines = Arrays.asList(
            "| 任务 | 状态 | 负责人 |",
            "|------|------|--------|",
            "| 完成报告 | ✅ | 张三 |",
            "| 代码审查 | 🔄 | 李四 |",
            "| 测试部署 | ⏳ | 王五 |"
        );
        List<String> result = TableRenderer.render(rawLines);
        assertUniformWidth(result);
    }

    @Test
    @DisplayName("窄终端下的长文本表格应换行且对齐")
    void narrowTerminalLongDesc() {
        List<String> rawLines = Arrays.asList(
            "| 功能 | 说明 |",
            "|------|------|",
            "| 自动换行 | 当单元格内容超出列宽上限时，渲染器会自动将内容切割为多个物理行，保持表格边框对齐 |",
            "| 宽度感知 | 通过 terminal.getWidth() 实时获取终端宽度，调整窗口大小后下一个 token 即生效 |"
        );
        List<String> result = TableRenderer.render(rawLines, 60);
        assertTrue(result.size() > 6, "长描述应产生多个物理行");
        assertUniformWidth(result);
    }
}
