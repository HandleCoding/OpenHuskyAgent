package io.github.huskyagent.tui;

import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 MessageBoxRenderer 状态机 — 验证 reasoning/text token 渲染逻辑。
 *
 * <p>由于 JLine Terminal 难以在单元测试中 mock 输出，
 * 本测试直接反射读取内部状态（inReasoning, boxOpen, lineStarted）来验证状态转换。</p>
 */
class MessageBoxRendererTest {

    private MessageBoxRenderer renderer;
    private StringWriter stringWriter;

    @BeforeEach
    void setUp() throws Exception {
        stringWriter = new StringWriter();
        // 创建 dumb terminal
        var terminal = org.jline.terminal.TerminalBuilder.builder()
                .system(false)
                .type("dumb")
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                .build();

        renderer = new MessageBoxRenderer(terminal);
    }

    // 反射读取内部状态
    private boolean getInReasoning() throws Exception {
        var f = MessageBoxRenderer.class.getDeclaredField("inReasoning");
        f.setAccessible(true);
        return (boolean) f.get(renderer);
    }

    private boolean getBoxOpen() throws Exception {
        var f = MessageBoxRenderer.class.getDeclaredField("boxOpen");
        f.setAccessible(true);
        return (boolean) f.get(renderer);
    }

    private boolean getLineStarted() throws Exception {
        var f = MessageBoxRenderer.class.getDeclaredField("lineStarted");
        f.setAccessible(true);
        return (boolean) f.get(renderer);
    }

    private Runnable noOp = () -> {};

    // ── 基础状态转换测试 ────────────────────────────────────────────────────

    @Test
    @DisplayName("reasoning token → inReasoning=true")
    void reasoningToken_setsInReasoningTrue() throws Exception {
        renderer.handleToken("思考内容", true, noOp);
        assertTrue(getInReasoning(), "reasoning token 后 inReasoning 应为 true");
        assertTrue(getBoxOpen(), "reasoning token 应自动开框");
        assertTrue(getLineStarted(), "reasoning token 应标记 lineStarted");
    }

    @Test
    @DisplayName("连续 reasoning token → inReasoning 保持 true")
    void consecutiveReasoningTokens_stayInReasoning() throws Exception {
        renderer.handleToken("第一段", true, noOp);
        renderer.handleToken("第二段", true, noOp);
        assertTrue(getInReasoning(), "连续 reasoning 后 inReasoning 应保持 true");
    }

    @Test
    @DisplayName("text token → inReasoning=false")
    void textToken_clearsInReasoning() throws Exception {
        renderer.handleToken("思考", true, noOp);
        assertTrue(getInReasoning());

        renderer.handleToken("正文", false, noOp);
        assertFalse(getInReasoning(), "text token 后 inReasoning 应为 false");
    }

    @Test
    @DisplayName("text → reasoning 切换 → 重新进入 reasoning")
    void textToReasoning_reEntersReasoning() throws Exception {
        renderer.handleToken("正文", false, noOp);
        assertFalse(getInReasoning());

        renderer.handleToken("再思考", true, noOp);
        assertTrue(getInReasoning(), "text → reasoning 后应重新进入 reasoning 模式");
    }

    @Test
    @DisplayName("reasoning → text 切换 → 离开 reasoning")
    void reasoningToText_exitsReasoning() throws Exception {
        renderer.handleToken("思考中", true, noOp);
        assertTrue(getInReasoning());

        renderer.handleToken("正文", false, noOp);
        assertFalse(getInReasoning(), "reasoning → text 后应离开 reasoning 模式");
    }

    // ── intermediate 测试 ───────────────────────────────────────────────────

    @Test
    @DisplayName("intermediate=true → inReasoning=false（重置 reasoning 状态）")
    void intermediateTrue_resetsReasoning() throws Exception {
        renderer.handleToken("思考", true, noOp);
        assertTrue(getInReasoning());

        renderer.handleIntermediate(true);
        assertFalse(getInReasoning(), "intermediate=true 应重置 inReasoning");
    }

    @Test
    @DisplayName("intermediate=false → inReasoning=false")
    void intermediateFalse_resetsReasoning() throws Exception {
        renderer.handleToken("思考", true, noOp);
        assertTrue(getInReasoning());

        renderer.handleIntermediate(false);
        assertFalse(getInReasoning(), "intermediate=false 应重置 inReasoning");
    }

    @Test
    @DisplayName("intermediate 后继续 text → 正常显示")
    void afterIntermediate_textWorks() throws Exception {
        renderer.handleToken("正文1", false, noOp);
        renderer.handleIntermediate(true);
        renderer.handleToken("正文2", false, noOp);
        assertFalse(getInReasoning(), "intermediate 后 text 应保持 inReasoning=false");
        assertTrue(getBoxOpen(), "框应仍然打开");
    }

    // ── 框管理测试 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("首个 token 自动开框")
    void firstToken_opensBox() throws Exception {
        assertFalse(getBoxOpen(), "初始 boxOpen 应为 false");
        renderer.handleToken("hello", false, noOp);
        assertTrue(getBoxOpen(), "首个 token 后 boxOpen 应为 true");
    }

    @Test
    @DisplayName("closeBox 关闭框")
    void closeBox_closesBox() throws Exception {
        renderer.handleToken("hello", false, noOp);
        assertTrue(getBoxOpen());
        renderer.closeBox(100);
        assertFalse(getBoxOpen(), "closeBox 后 boxOpen 应为 false");
    }

    @Test
    @DisplayName("reset 后可重新开框")
    void reset_allowsReopenBox() throws Exception {
        renderer.handleToken("hello", false, noOp);
        renderer.closeBox(100);
        renderer.reset();
        assertFalse(getBoxOpen());

        renderer.handleToken("new session", false, noOp);
        assertTrue(getBoxOpen(), "reset 后应能重新开框");
    }

    // ── 完整流状态测试 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("完整流：reasoning → text → intermediate → reasoning → text")
    void fullFlow_stateTransitions() throws Exception {
        // 1. reasoning
        renderer.handleToken("分析需求...", true, noOp);
        assertTrue(getInReasoning());
        assertTrue(getBoxOpen());

        // 2. text
        renderer.handleToken("我来处理", false, noOp);
        assertFalse(getInReasoning());

        // 3. intermediate (tool calls coming)
        renderer.handleIntermediate(true);
        assertFalse(getInReasoning());

        // 4. tool 结果后继续 reasoning
        renderer.handleToken("再分析", true, noOp);
        assertTrue(getInReasoning());

        // 5. 最终 text
        renderer.handleToken("完成", false, noOp);
        assertFalse(getInReasoning());
    }

    @Test
    @DisplayName("多个 reasoning/text 循环")
    void multipleReasoningTextCycles() throws Exception {
        for (int i = 0; i < 5; i++) {
            renderer.handleToken("reasoning " + i, true, noOp);
            assertTrue(getInReasoning(), "第 " + i + " 轮 reasoning 后应为 true");
            renderer.handleToken("text " + i, false, noOp);
            assertFalse(getInReasoning(), "第 " + i + " 轮 text 后应为 false");
        }
    }
}
