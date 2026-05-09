package io.github.huskyagent.application.tui;

import io.github.huskyagent.application.channel.ChannelInboundQueue;
import io.github.huskyagent.application.runtime.RuntimeExecutionService;
import io.github.huskyagent.application.session.SessionOperationsService;
import io.github.huskyagent.application.session.SessionResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

@Component
@RequiredArgsConstructor
public class TuiSessionServiceFactory {

    private final RuntimeExecutionService runtimeExecutionService;
    private final SessionResolver sessionResolver;
    private final SessionOperationsService sessionOperationsService;
    private final ChannelInboundQueue inboundQueue;
    @Qualifier("agentExecutor")
    private final Executor agentExecutor;

    public TuiSessionService create(String connectionId) {
        return new TuiSessionService(runtimeExecutionService, sessionResolver, sessionOperationsService,
                inboundQueue, agentExecutor, connectionId);
    }
}
