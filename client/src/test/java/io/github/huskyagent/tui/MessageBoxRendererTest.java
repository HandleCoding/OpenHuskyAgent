package io.github.huskyagent.tui;

import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MessageBoxRendererTest {

    private MessageBoxRenderer renderer;
    private StringWriter stringWriter;

    @BeforeEach
    void setUp() throws Exception {
        stringWriter = new StringWriter();
        var terminal = org.jline.terminal.TerminalBuilder.builder()
                .system(false)
                .type("dumb")
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                .build();

        renderer = new MessageBoxRenderer(terminal);
    }

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


    @Test
    @DisplayName("reasoning token → inReasoning=true")
    void reasoningToken_setsInReasoningTrue() throws Exception {
        renderer.handleToken("reasoning content", true, noOp);
        assertTrue(getInReasoning(), "inReasoning should be true after reasoning token");
        assertTrue(getBoxOpen(), "reasoning token should open the box automatically");
        assertTrue(getLineStarted(), "reasoning token should mark lineStarted");
    }

    @Test
    @DisplayName("consecutive reasoning tokens keep inReasoning true")
    void consecutiveReasoningTokens_stayInReasoning() throws Exception {
        renderer.handleToken("first chunk", true, noOp);
        renderer.handleToken("second chunk", true, noOp);
        assertTrue(getInReasoning(), "inReasoning should stay true after consecutive reasoning tokens");
    }

    @Test
    @DisplayName("text token → inReasoning=false")
    void textToken_clearsInReasoning() throws Exception {
        renderer.handleToken("reasoning", true, noOp);
        assertTrue(getInReasoning());

        renderer.handleToken("text", false, noOp);
        assertFalse(getInReasoning(), "inReasoning should be false after text token");
    }

    @Test
    @DisplayName("text to reasoning transition re-enters reasoning")
    void textToReasoning_reEntersReasoning() throws Exception {
        renderer.handleToken("text", false, noOp);
        assertFalse(getInReasoning());

        renderer.handleToken("reason again", true, noOp);
        assertTrue(getInReasoning(), "should re-enter reasoning mode after text to reasoning transition");
    }

    @Test
    @DisplayName("reasoning to text transition leaves reasoning")
    void reasoningToText_exitsReasoning() throws Exception {
        renderer.handleToken("reasoning", true, noOp);
        assertTrue(getInReasoning());

        renderer.handleToken("text", false, noOp);
        assertFalse(getInReasoning(), "should leave reasoning mode after reasoning to text transition");
    }


    @Test
    @DisplayName("intermediate=true resets inReasoning to false")
    void intermediateTrue_resetsReasoning() throws Exception {
        renderer.handleToken("reasoning", true, noOp);
        assertTrue(getInReasoning());

        renderer.handleIntermediate(true);
        assertFalse(getInReasoning(), "intermediate=true should reset inReasoning");
    }

    @Test
    @DisplayName("intermediate=false → inReasoning=false")
    void intermediateFalse_resetsReasoning() throws Exception {
        renderer.handleToken("reasoning", true, noOp);
        assertTrue(getInReasoning());

        renderer.handleIntermediate(false);
        assertFalse(getInReasoning(), "intermediate=false should reset inReasoning");
    }

    @Test
    @DisplayName("text after intermediate renders normally")
    void afterIntermediate_textWorks() throws Exception {
        renderer.handleToken("text 1", false, noOp);
        renderer.handleIntermediate(true);
        renderer.handleToken("text 2", false, noOp);
        assertFalse(getInReasoning(), "text after intermediate should keep inReasoning=false");
        assertTrue(getBoxOpen(), "box should remain open");
    }


    @Test
    @DisplayName("first token opens the box automatically")
    void firstToken_opensBox() throws Exception {
        assertFalse(getBoxOpen(), "initial boxOpen should be false");
        renderer.handleToken("hello", false, noOp);
        assertTrue(getBoxOpen(), "boxOpen should be true after first token");
    }

    @Test
    @DisplayName("closeBox closes the box")
    void closeBox_closesBox() throws Exception {
        renderer.handleToken("hello", false, noOp);
        assertTrue(getBoxOpen());
        renderer.closeBox(100);
        assertFalse(getBoxOpen(), "boxOpen should be false after closeBox");
    }

    @Test
    @DisplayName("box can reopen after reset")
    void reset_allowsReopenBox() throws Exception {
        renderer.handleToken("hello", false, noOp);
        renderer.closeBox(100);
        renderer.reset();
        assertFalse(getBoxOpen());

        renderer.handleToken("new session", false, noOp);
        assertTrue(getBoxOpen(), "should reopen after reset");
    }


    @Test
    @DisplayName("full flow: reasoning -> text -> intermediate -> reasoning -> text")
    void fullFlow_stateTransitions() throws Exception {
        // 1. reasoning
        renderer.handleToken("Analyzing request...", true, noOp);
        assertTrue(getInReasoning());
        assertTrue(getBoxOpen());

        // 2. text
        renderer.handleToken("I will handle it", false, noOp);
        assertFalse(getInReasoning());

        // 3. intermediate (tool calls coming)
        renderer.handleIntermediate(true);
        assertFalse(getInReasoning());

        renderer.handleToken("analyze again", true, noOp);
        assertTrue(getInReasoning());

        renderer.handleToken("done", false, noOp);
        assertFalse(getInReasoning());
    }

    @Test
    @DisplayName("multiple reasoning/text cycles")
    void multipleReasoningTextCycles() throws Exception {
        for (int i = 0; i < 5; i++) {
            renderer.handleToken("reasoning " + i, true, noOp);
            assertTrue(getInReasoning(), "row " + i + " should be true after reasoning");
            renderer.handleToken("text " + i, false, noOp);
            assertFalse(getInReasoning(), "row " + i + " should be false after text");
        }
    }
}
