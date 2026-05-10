package io.github.huskyagent.service.openai;

class OpenAiProtocolException extends RuntimeException {

    private final String param;

    private final String code;

    OpenAiProtocolException(String message, String param, String code) {
        super(message);
        this.param = param;
        this.code = code;
    }

    String getParam() {
        return param;
    }

    String getCode() {
        return code;
    }
}
