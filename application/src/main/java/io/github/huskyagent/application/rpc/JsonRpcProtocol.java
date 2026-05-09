package io.github.huskyagent.application.rpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

/**
 * JSON-RPC 2.0 消息构造工具。
 *
 * <p>与传输层无关：只负责构建/解析 JSON-RPC 帧，不关心底层是 WebSocket、stdio 还是 HTTP。</p>
 */
@Slf4j
public final class JsonRpcProtocol {

    public static final String JSONRPC = "2.0";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private JsonRpcProtocol() {}

    // ── 构造消息 ─────────────────────────────────────────────────────────────

    /** 构造请求 */
    public static ObjectNode request(String id, String method, Object params) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", JSONRPC);
        node.put("id", id);
        node.put("method", method);
        if (params != null) {
            node.set("params", MAPPER.valueToTree(params));
        }
        return node;
    }

    /** 构造成功响应 */
    public static ObjectNode response(String id, Object result) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", JSONRPC);
        node.put("id", id);
        node.set("result", MAPPER.valueToTree(result));
        return node;
    }

    /** 构造错误响应 */
    public static ObjectNode error(String id, int code, String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", JSONRPC);
        node.put("id", id);
        ObjectNode err = MAPPER.createObjectNode();
        err.put("code", code);
        err.put("message", message);
        node.set("error", err);
        return node;
    }

    /** 构造通知（无 id，不需要响应） */
    public static ObjectNode notification(String method, Object params) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", JSONRPC);
        node.put("method", method);
        if (params != null) {
            node.set("params", MAPPER.valueToTree(params));
        }
        return node;
    }

    // ── 解析辅助 ────────────────────────────────────────────────────────────

    /** 序列化为 JSON 字符串 */
    public static String serialize(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            log.error("JSON-RPC 序列化失败", e);
            return "{}";
        }
    }

    /** 从字符串解析为 JsonNode */
    public static JsonNode deserialize(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 获取请求 id（可能为 null，表示通知） */
    public static String getId(JsonNode node) {
        JsonNode id = node.get("id");
        if (id == null || id.isNull()) return null;
        return id.asText();
    }

    /** 获取 method 字段 */
    public static String getMethod(JsonNode node) {
        JsonNode m = node.get("method");
        return m != null && m.isTextual() ? m.asText() : null;
    }

    /** 获取 params 字段 */
    public static JsonNode getParams(JsonNode node) {
        JsonNode p = node.get("params");
        return p != null ? p : MAPPER.createObjectNode();
    }

    /** 判断是否为通知（无 id） */
    public static boolean isNotification(JsonNode node) {
        return getId(node) == null;
    }

    /** 判断是否为响应（有 result 或 error） */
    public static boolean isResponse(JsonNode node) {
        return node.has("result") || node.has("error");
    }

    // ── 标准错误码 ──────────────────────────────────────────────────────────

    public static final int PARSE_ERROR     = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS  = -32602;
    public static final int INTERNAL_ERROR  = -32603;

    /** 业务错误码起始 */
    public static final int SESSION_NOT_FOUND = 4001;
    public static final int SESSION_BUSY      = 4009;
    public static final int APPROVAL_TIMEOUT  = 4010;
}
