package io.github.huskyagent.rpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class JsonRpcProtocol {

    public static final String JSONRPC = "2.0";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonRpcProtocol() {}


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

    public static ObjectNode response(String id, Object result) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", JSONRPC);
        node.put("id", id);
        node.set("result", MAPPER.valueToTree(result));
        return node;
    }

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

    public static ObjectNode notification(String method, Object params) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", JSONRPC);
        node.put("method", method);
        if (params != null) {
            node.set("params", MAPPER.valueToTree(params));
        }
        return node;
    }


    public static String serialize(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            log.error("JSON-RPC serialization failed", e);
            return "{}";
        }
    }

    public static JsonNode deserialize(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public static String getId(JsonNode node) {
        JsonNode id = node.get("id");
        if (id == null || id.isNull()) return null;
        return id.asText();
    }

    public static String getMethod(JsonNode node) {
        JsonNode m = node.get("method");
        return m != null && m.isTextual() ? m.asText() : null;
    }

    public static JsonNode getParams(JsonNode node) {
        JsonNode p = node.get("params");
        return p != null ? p : MAPPER.createObjectNode();
    }

    public static boolean isNotification(JsonNode node) {
        return getId(node) == null;
    }

    public static boolean isResponse(JsonNode node) {
        return node.has("result") || node.has("error");
    }


    public static final int PARSE_ERROR     = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS  = -32602;
    public static final int INTERNAL_ERROR  = -32603;

    public static final int SESSION_NOT_FOUND = 4001;
    public static final int SESSION_BUSY      = 4009;
    public static final int APPROVAL_TIMEOUT  = 4010;
}