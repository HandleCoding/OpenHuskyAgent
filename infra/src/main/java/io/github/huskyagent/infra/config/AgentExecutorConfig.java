package io.github.huskyagent.infra.config;

import com.alibaba.ttl.threadpool.TtlExecutors;
import lombok.Data;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Data
@Configuration
@ConfigurationProperties(prefix = "agent.executor")
public class AgentExecutorConfig {

    private int cpuCores = Runtime.getRuntime().availableProcessors();

    // agentExecutor — SSE chat channel
    private int coreSize = cpuCores;
    private int maxSize = cpuCores * 2;
    private int queueCapacity = 100;
    private String threadPrefix = "agent-exec-";

    // toolExecutor — parallel tool execution (TTL-wrapped for context propagation)
    private int toolCoreSize = cpuCores;
    private int toolMaxSize = cpuCores * 2;
    private int toolQueueCapacity = 50;
    private String toolThreadPrefix = "tool-exec-";

    @Bean("agentExecutor")
    public org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor agentExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(coreSize);
        exec.setMaxPoolSize(maxSize);
        exec.setQueueCapacity(queueCapacity);
        exec.setThreadNamePrefix(threadPrefix);
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
    }

    @Bean("toolExecutor")
    public ExecutorService toolExecutor() {
        ThreadPoolExecutor raw = new ThreadPoolExecutor(
                toolCoreSize,
                toolMaxSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(toolQueueCapacity),
                r -> {
                    Thread t = new Thread(r);
                    t.setName(toolThreadPrefix + t.getId());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutorService(raw);
    }
}