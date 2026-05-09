package io.github.huskyagent.infra.ai;

import io.github.huskyagent.infra.config.AgentConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;

@Slf4j
@Component
public class LlmRetryPolicy {

    private final AgentConfig.LlmConfig llmConfig;

    public LlmRetryPolicy(AgentConfig agentConfig) {
        this.llmConfig = agentConfig.getLlm();
    }

    public boolean isRetryable(Throwable e) {
        if (e instanceof SocketTimeoutException || e instanceof ConnectException) {
            return true;
        }
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("429")
            || msg.contains("rate limit")
            || msg.contains("too many requests")
            || msg.contains("timeout")
            || msg.contains("connection refused")
            || msg.contains("connection reset");
    }

    public RetryBackoffSpec retrySpec() {
        return Retry.backoff(llmConfig.getMaxRetries(), Duration.ofMillis(llmConfig.getInitialBackoffMs()))
                .filter(this::isRetryable)
                .doBeforeRetry(signal -> log.warn(
                        "[LLM] retry attempt {}, reason: {}",
                        signal.totalRetries() + 1,
                        signal.failure().getMessage()))
                .onRetryExhaustedThrow((spec, signal) -> {
                    log.error("[LLM] still failed after {} retries", llmConfig.getMaxRetries());
                    return signal.failure();
                });
    }

    public int getMaxRetries() {
        return llmConfig.getMaxRetries();
    }

    public long getInitialBackoffMs() {
        return llmConfig.getInitialBackoffMs();
    }
}