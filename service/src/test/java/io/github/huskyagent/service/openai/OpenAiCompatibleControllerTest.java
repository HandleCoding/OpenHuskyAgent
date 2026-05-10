package io.github.huskyagent.service.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.channel.ChannelInboundQueue;
import io.github.huskyagent.application.channel.ChannelRuntimeQueueKeyFactory;
import io.github.huskyagent.application.channel.binding.ChannelSceneRouter;
import io.github.huskyagent.application.channel.binding.EffectiveChannelRoute;
import io.github.huskyagent.application.runtime.RuntimeExecutionRequest;
import io.github.huskyagent.application.runtime.RuntimeExecutionResult;
import io.github.huskyagent.application.runtime.RuntimeExecutionService;
import io.github.huskyagent.application.scene.ConfigSceneResolver;
import io.github.huskyagent.domain.event.ChannelEvent;
import io.github.huskyagent.domain.event.ChannelEventBus;
import io.github.huskyagent.domain.event.ChannelSubscriber;
import io.github.huskyagent.domain.event.TokenSubscriber;
import io.github.huskyagent.domain.hook.HookEvent;
import io.github.huskyagent.infra.auth.PrincipalContext;
import io.github.huskyagent.infra.channel.InboundMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OpenAiCompatibleControllerTest {

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void httpJsonBindingAcceptsTextContentParts() throws Exception {
        RecordingRuntimeExecutionService runtime = new RecordingRuntimeExecutionService();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(newController(runtime)).build();

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model":"assistant",
                                  "messages":[{
                                    "role":"user",
                                    "content":[
                                      {"type":"text","text":"hello"},
                                      {"type":"text","text":"world"}
                                    ]
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.choices[0].message.content").value("command-ok"));

        RuntimeExecutionRequest captured = runtime.captured.get();
        assertNotNull(captured);
        assertEquals("User: hello\nworld", captured.getInbound().getText());
    }

    @Test
    void noSessionHintDoesNotReturnSessionHeader() throws Exception {
        RecordingRuntimeExecutionService runtime = new RecordingRuntimeExecutionService();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(newController(runtime)).build();

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"assistant","messages":[{"role":"user","content":"hello"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Husky-Session-Id"));

        RuntimeExecutionRequest captured = runtime.captured.get();
        assertNotNull(captured);
        assertEquals(RuntimeExecutionRequest.PersistenceMode.STATELESS, captured.persistenceModeOrDefault());
    }

    @Test
    void headerSessionHintReturnsResolvedSessionHeader() throws Exception {
        RecordingRuntimeExecutionService runtime = new RecordingRuntimeExecutionService();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(newController(runtime)).build();

        mockMvc.perform(post("/v1/chat/completions")
                        .header("X-Husky-Session-Id", "session-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"assistant","messages":[{"role":"user","content":"hello"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Husky-Session-Id", "session-result"));

        RuntimeExecutionRequest captured = runtime.captured.get();
        assertNotNull(captured);
        assertEquals("session-1", captured.getInbound().getRequestedSessionId());
        assertEquals(RuntimeExecutionRequest.PersistenceMode.STATEFUL, captured.persistenceModeOrDefault());
    }

    @Test
    void metadataSessionHintReturnsResolvedSessionHeader() throws Exception {
        RecordingRuntimeExecutionService runtime = new RecordingRuntimeExecutionService();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(newController(runtime)).build();

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model":"assistant",
                                  "metadata":{"session_id":"session-from-metadata"},
                                  "messages":[{"role":"user","content":"hello"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Husky-Session-Id", "session-result"));

        RuntimeExecutionRequest captured = runtime.captured.get();
        assertNotNull(captured);
        assertEquals("session-from-metadata", captured.getInbound().getRequestedSessionId());
        assertEquals(RuntimeExecutionRequest.PersistenceMode.STATEFUL, captured.persistenceModeOrDefault());
    }

    private OpenAiCompatibleController newController(RecordingRuntimeExecutionService runtime) {
        OpenAiCompatibleProperties properties = new OpenAiCompatibleProperties();
        properties.setEnabled(true);
        OpenAiResponseMapper responseMapper = new OpenAiResponseMapper();
        return new OpenAiCompatibleController(
                properties,
                newRuntimeService(runtime, properties),
                responseMapper,
                new ObjectMapper()
        );
    }

    private OpenAiCompatibleRuntimeService newRuntimeService(RecordingRuntimeExecutionService runtime,
                                                             OpenAiCompatibleProperties properties) {
        ConfigSceneResolver sceneResolver = new ConfigSceneResolver();
        LinkedHashMap<String, ConfigSceneResolver.SceneProperties> configs = new LinkedHashMap<>();
        configs.put("assistant", new ConfigSceneResolver.SceneProperties());
        sceneResolver.setConfigs(configs);
        OpenAiModelCatalog modelCatalog = new OpenAiModelCatalog(sceneResolver, properties);
        return new OpenAiCompatibleRuntimeService(
                runtime,
                new ChannelInboundQueue(),
                new FixedQueueKeyFactory(),
                new EchoSceneRouter(),
                properties,
                modelCatalog,
                new OpenAiPromptMapper(),
                new OpenAiChannelAdapter(new RecordingChannelEventBus()),
                Runnable::run
        );
    }

    private static class RecordingRuntimeExecutionService extends RuntimeExecutionService {
        private final AtomicReference<RuntimeExecutionRequest> captured = new AtomicReference<>();

        RecordingRuntimeExecutionService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public RuntimeExecutionResult execute(RuntimeExecutionRequest request) {
            captured.set(request);
            return RuntimeExecutionResult.rejected(ChatResult.success("command-ok", "session-result", false));
        }
    }

    private static class EchoSceneRouter extends ChannelSceneRouter {
        EchoSceneRouter() {
            super(null, null);
        }

        @Override
        public EffectiveChannelRoute resolve(InboundMessage inbound) {
            return new EffectiveChannelRoute(inbound.getSceneId(), null, EffectiveChannelRoute.Source.EXPLICIT);
        }
    }

    private static class FixedQueueKeyFactory extends ChannelRuntimeQueueKeyFactory {
        FixedQueueKeyFactory() {
            super(null, null);
        }

        @Override
        public String keyFor(InboundMessage inbound, EffectiveChannelRoute route) {
            return "test-openai-compatible-controller";
        }
    }

    private static class RecordingChannelEventBus implements ChannelEventBus {
        @Override
        public void publish(ChannelEvent event) {}

        @Override
        public void subscribe(String channelName, Set<HookEvent> eventFilter, ChannelSubscriber subscriber) {}

        @Override
        public void unsubscribe(String channelName) {}

        @Override
        public void streamToken(String sessionId, String token, boolean reasoning) {}

        @Override
        public void subscribeTokens(String channelName, TokenSubscriber subscriber) {}

        @Override
        public void unsubscribeTokens(String channelName) {}
    }
}
