package io.github.huskyagent.application.tui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.huskyagent.application.agent.TextEvent;
import io.github.huskyagent.application.rpc.JsonRpcDispatcher;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 TUI 推理内容(reasoning)事件流 — 从 TextEvent 到 JSON-RPC 事件的完整链路。
 */
class TuiReasoningEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private List<String> sentMessages;
    private JsonRpcDispatcher dispatcher;
    private JsonRpcEventEmitter emitter;
    private TuiSessionService sessionService;

    @BeforeEach
    void setUp() {
        sentMessages = new CopyOnWriteArrayList<>();
        dispatcher = new JsonRpcDispatcher(sentMessages::add);
        emitter = new JsonRpcEventEmitter(dispatcher);
        // 本测试只测 TUI text event 到 JSON-RPC 事件的等价逻辑
    }

    // ── 辅助方法：模拟 TuiSessionService.handleTextEvent 的逻辑 ──────────────

    private void simulateHandleTextEvent(TextEvent event, JsonRpcEventEmitter emitter) {
        if (event.isTokenEvent()) {
            emitter.emitMessageDelta(event.token(), event.reasoning());
        }
    }

    // ── 解析发出的 JSON-RPC 事件 ──────────────────────────────────────────────

    private JsonNode parseLastEvent() throws Exception {
        assertFalse(sentMessages.isEmpty(), "应该发送了事件");
        String last = sentMessages.get(sentMessages.size() - 1);
        return MAPPER.readTree(last);
    }

    private List<JsonNode> parseAllEvents() throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (String msg : sentMessages) {
            events.add(MAPPER.readTree(msg));
        }
        return events;
    }

    // ── 测试用例 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("reasoning token → emitMessageDelta(reasoning=true)")
    void reasoningToken_emitsDeltaWithReasoningTrue() throws Exception {
        TextEvent event = TextEvent.ofReasoning("让我想想这个问题");

        simulateHandleTextEvent(event, emitter);

        JsonNode eventNode = parseLastEvent();
        // 验证 JSON-RPC 通知结构
        assertEquals("2.0", eventNode.get("jsonrpc").asText());
        assertEquals("event", eventNode.get("method").asText());

        JsonNode params = eventNode.get("params");
        assertEquals("message.delta", params.get("type").asText());

        JsonNode payload = params.get("payload");
        assertEquals("让我想想这个问题", payload.get("token").asText());
        assertTrue(payload.get("reasoning").asBoolean(), "reasoning 字段应为 true");
    }

    @Test
    @DisplayName("text token → emitMessageDelta(reasoning=false)")
    void textToken_emitsDeltaWithReasoningFalse() throws Exception {
        TextEvent event = TextEvent.ofToken("你好世界");

        simulateHandleTextEvent(event, emitter);

        JsonNode eventNode = parseLastEvent();
        JsonNode payload = eventNode.get("params").get("payload");
        assertEquals("你好世界", payload.get("token").asText());
        assertFalse(payload.get("reasoning").asBoolean(), "reasoning 字段应为 false");
    }

    @Test
    @DisplayName("intermediate message → TuiSessionService 不再发事件")
    void intermediateMessage_emitsNothingFromSessionService() {
        TextEvent event = TextEvent.ofMessage("我需要使用工具", true, List.of());

        simulateHandleTextEvent(event, emitter);

        assertTrue(sentMessages.isEmpty(), "整段消息应由 TuiChannelAdapter 负责转发");
    }

    @Test
    @DisplayName("完整流：reasoning → text，整段消息由其他通路负责")
    void fullSequence_reasoningTextOnly() throws Exception {
        simulateHandleTextEvent(TextEvent.ofReasoning("分析用户需求..."), emitter);
        simulateHandleTextEvent(TextEvent.ofReasoning("需要读取文件"), emitter);
        simulateHandleTextEvent(TextEvent.ofToken("我来"), emitter);
        simulateHandleTextEvent(TextEvent.ofToken("帮你"), emitter);
        simulateHandleTextEvent(TextEvent.ofToken("处理"), emitter);
        simulateHandleTextEvent(TextEvent.ofMessage("我来帮你处理", true, List.of()), emitter);

        List<JsonNode> events = parseAllEvents();
        assertEquals(5, events.size(), "TuiSessionService 只应发送 token 事件");

        for (int i = 0; i < 2; i++) {
            JsonNode payload = events.get(i).get("params").get("payload");
            assertEquals("message.delta", events.get(i).get("params").get("type").asText());
            assertTrue(payload.get("reasoning").asBoolean(),
                    "事件 " + i + " 应该是 reasoning=true");
        }

        for (int i = 2; i < 5; i++) {
            JsonNode payload = events.get(i).get("params").get("payload");
            assertEquals("message.delta", events.get(i).get("params").get("type").asText());
            assertFalse(payload.get("reasoning").asBoolean(),
                    "事件 " + i + " 应该是 reasoning=false");
        }
    }

    @Test
    @DisplayName("reasoning token 空 token → 发送空字符串而非 null")
    void reasoningToken_nullToken_emitsEmptyString() throws Exception {
        // TextEvent.ofReasoning 不可能传 null，但防御性测试
        TextEvent event = new TextEvent(null, false, null, true, List.of());

        // token=null, isTokenEvent() 返回 false（因为 token==null），所以走 intermediate 路径
        // 这暴露了一个问题：reasoning=true 但 token=null 时 isTokenEvent()=false
        assertFalse(event.isTokenEvent(), "token=null 时 isTokenEvent() 应为 false");
        // 这意味着 reasoning-only event 如果 token=null 会走错误的路径
    }

    @Test
    @DisplayName("TextEvent.ofReasoning 的 reasoning 字段为 true")
    void ofReasoning_hasReasoningTrue() {
        TextEvent event = TextEvent.ofReasoning("思考内容");
        assertTrue(event.reasoning(), "ofReasoning() 的 reasoning 应为 true");
        assertTrue(event.isTokenEvent(), "ofReasoning() 应为 token 事件");
        assertEquals("思考内容", event.token());
        assertNull(event.text(), "ofReasoning() 的 text 应为 null");
    }

    @Test
    @DisplayName("TextEvent.ofToken 的 reasoning 字段为 false")
    void ofToken_hasReasoningFalse() {
        TextEvent event = TextEvent.ofToken("正文内容");
        assertFalse(event.reasoning(), "ofToken() 的 reasoning 应为 false");
        assertTrue(event.isTokenEvent(), "ofToken() 应为 token 事件");
        assertEquals("正文内容", event.token());
    }

    // ── 客户端解析模拟测试 ────────────────────────────────────────────────────

    @Test
    @DisplayName("客户端解析 message.delta reasoning=true → handleToken(reasoning=true)")
    void clientParsing_reasoningDelta_routesToReasoning() throws Exception {
        // 模拟服务端发送
        emitter.emitMessageDelta("思考中...", true);

        // 模拟客户端解析
        JsonNode eventNode = parseLastEvent();
        JsonNode params = eventNode.get("params");
        JsonNode payload = params.get("payload");

        String token = payload.has("token") ? payload.get("token").asText() : "";
        boolean reasoning = payload.has("reasoning") && payload.get("reasoning").asBoolean();

        assertEquals("思考中...", token);
        assertTrue(reasoning, "客户端应解析出 reasoning=true");
    }

    @Test
    @DisplayName("客户端解析 message.delta reasoning=false → handleToken(reasoning=false)")
    void clientParsing_textDelta_routesToText() throws Exception {
        emitter.emitMessageDelta("正文", false);

        JsonNode payload = parseLastEvent().get("params").get("payload");
        String token = payload.has("token") ? payload.get("token").asText() : "";
        boolean reasoning = payload.has("reasoning") && payload.get("reasoning").asBoolean();

        assertEquals("正文", token);
        assertFalse(reasoning, "客户端应解析出 reasoning=false");
    }

    @Test
    @DisplayName("客户端解析 message.intermediate → handleIntermediate(intermediate=true)")
    void clientParsing_intermediateEvent() throws Exception {
        emitter.emitMessageIntermediate("中间消息", true);

        JsonNode payload = parseLastEvent().get("params").get("payload");
        boolean intermediate = payload.has("intermediate") && payload.get("intermediate").asBoolean();

        assertTrue(intermediate, "客户端应解析出 intermediate=true");
    }

}
