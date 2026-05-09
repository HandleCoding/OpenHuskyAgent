package io.github.huskyagent.application.channel;

import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.channel.binding.ChannelSceneRouter;
import io.github.huskyagent.application.channel.binding.EffectiveChannelRoute;
import io.github.huskyagent.application.runtime.RuntimeExecutionRequest;
import io.github.huskyagent.application.runtime.RuntimeExecutionResult;
import io.github.huskyagent.application.runtime.RuntimeExecutionService;
import io.github.huskyagent.infra.channel.InboundMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
@RequiredArgsConstructor
public class ChannelRuntimeService {

    private final RuntimeExecutionService runtimeExecutionService;
    private final ChannelInboundQueue inboundQueue;
    private final ChannelRuntimeQueueKeyFactory queueKeyFactory;
    private final ChannelSceneRouter sceneRouter;

    public CompletableFuture<ChatResult> handleInboundAsync(InboundMessage inbound, ChannelAdapter adapter, Executor executor) {
        EffectiveChannelRoute route = sceneRouter.resolve(inbound);
        String queueKey = queueKeyFactory.keyFor(inbound, route);
        return inboundQueue.enqueue(queueKey, () -> handleInbound(inbound, adapter, route), executor);
    }

    public ChatResult handleInbound(InboundMessage inbound, ChannelAdapter adapter) {
        EffectiveChannelRoute route = sceneRouter.resolve(inbound);
        return handleInbound(inbound, adapter, route);
    }

    private ChatResult handleInbound(InboundMessage inbound, ChannelAdapter adapter, EffectiveChannelRoute route) {
        RuntimeExecutionResult result = runtimeExecutionService.execute(RuntimeExecutionRequest.builder()
                .inbound(inbound)
                .effectiveRoute(route)
                .callbacks(new AdapterCallbacks(adapter, inbound))
                .build());
        if (result.commandHandled() && result.commandReply() != null) {
            adapter.send(result.commandReply());
        }
        return result.chatResult();
    }
}