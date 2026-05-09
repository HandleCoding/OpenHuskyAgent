package io.github.huskyagent.tui;

import io.github.huskyagent.tui.markdown.MarkdownRenderer;
import org.jline.terminal.Terminal;

import java.util.List;

/**
 * 消息框状态机：管理 boxOpen / inReasoning / lineStarted 状态，
 * 处理 token 流渲染（reasoning + text + Markdown），以及框的开/关/分隔。
 */
class MessageBoxRenderer {

    private static final String RESET  = "\033[0m";
    private static final String BOLD   = "\033[1m";
    private static final String BLUE   = "\033[34m";
    private static final String GRAY   = "\033[90m";

    private static final String BOX_TOP    = BOLD + BLUE + "╭─ Agent " + GRAY + "─────────────────────────────────────────────────────" + RESET;
    static final         String BOX_DIV    = BOLD + BLUE + "├" + GRAY  + "╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌" + RESET;
    private static final String BOX_BOTTOM = BOLD + BLUE + "╰─────────────────────────────────────────────────────────────" + RESET;
    private static final String CLEAR_LINE = "\r\033[2K";

    private final Terminal terminal;

    // ── 消息框状态 ───────────────────────────────────────────────────────────────
    private boolean boxOpen      = false;
    private boolean inReasoning  = false;
    private boolean lineStarted  = false;

    // ── Markdown 渲染器 ──────────────────────────────────────────────────────────
    private final MarkdownRenderer markdownRenderer = new MarkdownRenderer();
    private final StringBuilder    currentLineBuffer = new StringBuilder();
    private int lineEchoedLength = 0;

    MessageBoxRenderer(Terminal terminal) {
        this.terminal = terminal;
    }

    // ── 状态重置（每轮对话开始前调用） ──────────────────────────────────────────

    void reset() {
        boxOpen = false;
        inReasoning = false;
        lineStarted = false;
        markdownRenderer.reset();
        currentLineBuffer.setLength(0);
        lineEchoedLength = 0;
    }

    boolean isBoxOpen() { return boxOpen; }

    // ── token 事件处理 ───────────────────────────────────────────────────────────

    /** 处理 reasoning 或 text token，必要时自动开框 */
    void handleToken(String token, boolean reasoning, Runnable clearThinking) {
        // 每个 token 同步终端宽度，支持用户拖拽调整窗口大小后立即生效
        markdownRenderer.setTerminalWidth(terminal.getWidth());
        if (!boxOpen) {
            clearThinking.run();
            println("");
            println(BOX_TOP);
            boxOpen = true;
            inReasoning = false;
            lineStarted = false;
        }

        if (reasoning) {
            handleReasoningToken(token);
        } else {
            handleTextToken(token);
        }
    }

    /** 处理中间/最终消息（intermediate=true 打分隔线，false 重置状态） */
    void handleIntermediate(boolean intermediate) {
        if (intermediate) {
            flushLineBuffer();
            if (lineStarted) { println(RESET + ""); lineStarted = false; }
            inReasoning = false;
            markdownRenderer.reset();
            println(BOX_DIV);
        } else {
            inReasoning = false;
        }
    }

    // ── 框管理 ───────────────────────────────────────────────────────────────────

    /** 关闭框并追加耗时 */
    void closeBox(long elapsedMs) {
        if (!boxOpen) return;
        flushLineBuffer();
        if (lineStarted) { println(RESET + ""); lineStarted = false; }
        inReasoning = false;
        println(BOLD + BLUE + "╰─ " + RESET + GRAY + "⏱ " + elapsedMs + "ms" + RESET);
        println("");
        boxOpen = false;
    }

    /** 若框打开则关闭（无耗时，用于异常/审批场景） */
    void closeBoxIfOpen() {
        if (!boxOpen) return;
        flushLineBuffer();
        if (lineStarted) { println(RESET + ""); lineStarted = false; }
        inReasoning = false;
        println(BOX_BOTTOM);
        println("");
        boxOpen = false;
    }

    /** 整体打印（不走流式时使用）：打开框、写内容、带耗时关闭 */
    void printMessageInBox(String content, long elapsedMs) {
        println("");
        println(BOX_TOP);
        printBoxContent(content);
        println(BOLD + BLUE + "╰─ " + RESET + GRAY + "⏱ " + elapsedMs + "ms" + RESET);
        println("");
    }

    // ── 私有实现 ─────────────────────────────────────────────────────────────────

    private void handleReasoningToken(String token) {
        if (!inReasoning) {
            if (lineStarted) { println(""); lineStarted = false; }
            print(BLUE + "│" + RESET + "  " + GRAY + "💭 ");
            inReasoning = true;
            lineStarted = true;
        }
        String[] lines = token.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                println("");
                print(BLUE + "│" + RESET + "  " + GRAY);
                lineStarted = true;
            }
            print(lines[i]);
        }
    }

    private void handleTextToken(String token) {
        if (inReasoning) {
            if (lineStarted) { println(RESET + ""); lineStarted = false; }
            inReasoning = false;
            lineEchoedLength = 0;
        }

        String[] parts = token.split("\n", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                String completedLine = currentLineBuffer.toString();
                currentLineBuffer.setLength(0);
                lineEchoedLength = 0;

                if (lineStarted) {
                    clearActiveLine();
                    lineStarted = false;
                }

                List<String> rendered = markdownRenderer.processLine(completedLine);
                for (String r : rendered) {
                    println(BLUE + "│" + RESET + "  " + r);
                }
            }
            currentLineBuffer.append(parts[i]);
        }

        String buf = currentLineBuffer.toString();
        boolean canEcho = markdownRenderer.getState() == MarkdownRenderer.RenderState.NORMAL
                          && !buf.startsWith("|")
                          && !buf.startsWith("```");
        if (canEcho && buf.length() > lineEchoedLength) {
            if (!lineStarted) {
                print(BLUE + "│" + RESET + "  ");
                lineStarted = true;
            }
            print(markdownRenderer.renderInlinePartial(buf.substring(lineEchoedLength)));
            lineEchoedLength = buf.length();
        }
    }

    private void printBoxContent(String content) {
        markdownRenderer.reset();
        for (String line : content.split("\n", -1)) {
            List<String> rendered = markdownRenderer.processLine(line);
            for (String r : rendered) {
                println(BLUE + "│" + RESET + "  " + r);
            }
        }
        markdownRenderer.flush().forEach(r -> println(BLUE + "│" + RESET + "  " + r));
    }

    private void flushLineBuffer() {
        if (currentLineBuffer.length() > 0) {
            String lastLine = currentLineBuffer.toString();
            currentLineBuffer.setLength(0);
            lineEchoedLength = 0;
            if (lineStarted) {
                clearActiveLine();
                lineStarted = false;
            }
            List<String> rendered = markdownRenderer.processLine(lastLine);
            for (String r : rendered) {
                println(BLUE + "│" + RESET + "  " + r);
            }
        }
        markdownRenderer.flush().forEach(r -> println(BLUE + "│" + RESET + "  " + r));
    }

    private void clearActiveLine() {
        print(CLEAR_LINE);
    }

    private void println(String msg) { terminal.writer().println(msg); terminal.flush(); }
    private void print(String msg)   { terminal.writer().print(msg);   terminal.flush(); }
}