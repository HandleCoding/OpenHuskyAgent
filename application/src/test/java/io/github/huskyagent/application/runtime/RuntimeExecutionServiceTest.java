package io.github.huskyagent.application.runtime;

import io.github.huskyagent.application.AgentInput;
import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.channel.ChannelCommand;
import io.github.huskyagent.application.channel.ChannelCommandParser;
import io.github.huskyagent.application.channel.ChannelCommandService;
import io.github.huskyagent.application.channel.binding.ChannelSceneRouter;
import io.github.huskyagent.application.channel.binding.EffectiveChannelRoute;
import io.github.huskyagent.application.channel.runtime.SessionRoute;
import io.github.huskyagent.application.channel.runtime.SessionRouteRegistry;
import io.github.huskyagent.application.session.RuntimeScope;
import io.github.huskyagent.application.session.ScopedRuntimeContext;
import io.github.huskyagent.application.session.SessionResolver;
import io.github.huskyagent.domain.capability.CapabilityView;
import io.github.huskyagent.domain.memory.policy.MemoryPolicyConfig;
import io.github.huskyagent.domain.runtime.RuntimePolicy;
import io.github.huskyagent.domain.scene.SceneConfig;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.ConversationType;
import io.github.huskyagent.infra.channel.InboundMessage;
import io.github.huskyagent.infra.channel.OutboundMessage;
import io.github.huskyagent.infra.channel.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeExecutionServiceTest {

    @Test
    void commandShortCircuitDoesNotResolveScope() {
        FakeSessionResolver sessionResolver = new FakeSessionResolver(completeScope());
        FakeCommandService commandService = new FakeCommandService(OutboundMessage.builder()
                .kind(OutboundMessage.Kind.TEXT)
                .sessionId("command-session")
                .text("command ok")
                .build());
        RuntimeExecutionService service = new RuntimeExecutionService(
                sessionResolver,
                null,
                inbound -> Optional.of(new ChannelCommand("help", "", "/help")),
                commandService,
                new FakeRouteRegistry(),
                new FakeSceneRouter(),
                new ScopedRuntimeContext()
        );

        RuntimeExecutionResult result = service.execute(RuntimeExecutionRequest.builder()
                .inbound(inbound("/help"))
                .build());

        assertTrue(result.commandHandled());
        assertEquals("command ok", result.chatResult().content());
        assertEquals(0, sessionResolver.resolveCalls);
    }

    @Test
    void workingDirectoryOverridePreservesResolvedScopeFacts() {
        RuntimeScope base = completeScope();
        FakeSessionResolver sessionResolver = new FakeSessionResolver(base);
        FakeRouteRegistry routeRegistry = new FakeRouteRegistry();
        RecordingCallbacks callbacks = new RecordingCallbacks();
        RuntimeExecutionService service = new RuntimeExecutionService(
                sessionResolver,
                new FakeAgentApp(),
                inbound -> Optional.empty(),
                new FakeCommandService(null),
                routeRegistry,
                new FakeSceneRouter(),
                new ScopedRuntimeContext()
        );

        RuntimeExecutionResult result = service.execute(RuntimeExecutionRequest.builder()
                .inbound(inbound("hello"))
                .workingDirectoryOverride(Path.of("/tmp/override"))
                .callbacks(callbacks)
                .build());

        RuntimeScope scope = result.scope();
        assertEquals(Path.of("/tmp/override"), scope.getWorkingDirectory());
        assertSame(base.getRuntimePolicy(), scope.getRuntimePolicy());
        assertSame(base.getPrincipal(), scope.getPrincipal());
        assertSame(base.getChannelIdentity(), scope.getChannelIdentity());
        assertEquals("session-1", callbacks.startedSessionId);
        assertTrue(routeRegistry.unregistered);
    }

    @Test
    void sessionAccessFailureReturnsSessionError() {
        FakeSessionResolver sessionResolver = new FakeSessionResolver(completeScope());
        sessionResolver.failWithSecurityException = true;
        RuntimeExecutionService service = new RuntimeExecutionService(
                sessionResolver,
                new FakeAgentApp(),
                inbound -> Optional.empty(),
                new FakeCommandService(null),
                new FakeRouteRegistry(),
                new FakeSceneRouter(),
                new ScopedRuntimeContext()
        );

        RuntimeExecutionResult result = service.execute(RuntimeExecutionRequest.builder()
                .inbound(inbound("hello"))
                .build());

        assertFalse(result.chatResult().success());
        assertEquals(ChatResult.ErrorCode.SESSION_ERROR, result.chatResult().errorCode());
        assertNull(result.scope());
    }

    @Test
    void emptyInboundReturnsParamErrorResult() {
        RuntimeExecutionService service = new RuntimeExecutionService(
                new FakeSessionResolver(completeScope()),
                new FakeAgentApp(),
                inbound -> Optional.empty(),
                new FakeCommandService(null),
                new FakeRouteRegistry(),
                new FakeSceneRouter(),
                new ScopedRuntimeContext()
        );

        RuntimeExecutionResult result = service.execute(RuntimeExecutionRequest.builder()
                .inbound(inbound(""))
                .build());

        assertFalse(result.chatResult().success());
        assertEquals(ChatResult.ErrorCode.PARAM_ERROR, result.chatResult().errorCode());
        assertNull(result.scope());
    }

    @Test
    void forceNewWithoutRequestedSessionCreatesNewSession() {
        FakeSessionResolver sessionResolver = new FakeSessionResolver(completeScope());
        RuntimeExecutionService service = new RuntimeExecutionService(
                sessionResolver,
                new FakeAgentApp(),
                inbound -> Optional.empty(),
                new FakeCommandService(null),
                new FakeRouteRegistry(),
                new FakeSceneRouter(),
                new ScopedRuntimeContext()
        );

        RuntimeExecutionResult result = service.execute(RuntimeExecutionRequest.builder()
                .inbound(inbound("hello"))
                .forceNewSession(true)
                .build());

        assertTrue(result.chatResult().success());
        assertEquals(1, sessionResolver.createCalls);
        assertEquals(0, sessionResolver.resolveCalls);
        assertNull(sessionResolver.lastRequestedSessionId);
    }

    @Test
    void explicitRequestedSessionUsesResumePath() {
        FakeSessionResolver sessionResolver = new FakeSessionResolver(completeScope());
        RuntimeExecutionService service = new RuntimeExecutionService(
                sessionResolver,
                new FakeAgentApp(),
                inbound -> Optional.empty(),
                new FakeCommandService(null),
                new FakeRouteRegistry(),
                new FakeSceneRouter(),
                new ScopedRuntimeContext()
        );

        RuntimeExecutionResult result = service.execute(RuntimeExecutionRequest.builder()
                .inbound(inbound("hello", "existing-session"))
                .forceNewSession(true)
                .build());

        assertTrue(result.chatResult().success());
        assertEquals(0, sessionResolver.createCalls);
        assertEquals(1, sessionResolver.resolveCalls);
        assertEquals("existing-session", sessionResolver.lastRequestedSessionId);
    }

    @Test
    void blankRequestedSessionWithForceNewCreatesNewSession() {
        FakeSessionResolver sessionResolver = new FakeSessionResolver(completeScope());
        RuntimeExecutionService service = new RuntimeExecutionService(
                sessionResolver,
                new FakeAgentApp(),
                inbound -> Optional.empty(),
                new FakeCommandService(null),
                new FakeRouteRegistry(),
                new FakeSceneRouter(),
                new ScopedRuntimeContext()
        );

        RuntimeExecutionResult result = service.execute(RuntimeExecutionRequest.builder()
                .inbound(inbound("hello", "  "))
                .forceNewSession(true)
                .build());

        assertTrue(result.chatResult().success());
        assertEquals(1, sessionResolver.createCalls);
        assertEquals(0, sessionResolver.resolveCalls);
        assertNull(sessionResolver.lastRequestedSessionId);
    }

    @Test
    void defaultPersistenceModeIsStateful() {
        FakeSessionResolver sessionResolver = new FakeSessionResolver(completeScope());
        FakeAgentApp agentApp = new FakeAgentApp();
        RuntimeExecutionService service = new RuntimeExecutionService(
                sessionResolver,
                agentApp,
                inbound -> Optional.empty(),
                new FakeCommandService(null),
                new FakeRouteRegistry(),
                new FakeSceneRouter(),
                new ScopedRuntimeContext()
        );

        RuntimeExecutionResult result = service.execute(RuntimeExecutionRequest.builder()
                .inbound(inbound("hello"))
                .build());

        assertTrue(result.chatResult().success());
        assertEquals(RuntimeExecutionRequest.PersistenceMode.STATEFUL, agentApp.persistenceMode);
    }

    @Test
    void explicitStatelessPersistenceModeCreatesEphemeralScope() {
        FakeSessionResolver sessionResolver = new FakeSessionResolver(completeScope());
        FakeAgentApp agentApp = new FakeAgentApp();
        RuntimeExecutionService service = new RuntimeExecutionService(
                sessionResolver,
                agentApp,
                inbound -> Optional.empty(),
                new FakeCommandService(null),
                new FakeRouteRegistry(),
                new FakeSceneRouter(),
                new ScopedRuntimeContext()
        );

        RuntimeExecutionResult result = service.execute(RuntimeExecutionRequest.builder()
                .inbound(inbound("hello"))
                .persistenceMode(RuntimeExecutionRequest.PersistenceMode.STATELESS)
                .build());

        assertTrue(result.chatResult().success());
        assertEquals(RuntimeExecutionRequest.PersistenceMode.STATELESS, agentApp.persistenceMode);
        assertEquals(1, sessionResolver.ephemeralCalls);
        assertEquals(0, sessionResolver.createCalls);
        assertEquals(1, sessionResolver.releaseCalls);
    }

    @Test
    void explicitAgentInputIsPassedToExecutor() {
        FakeAgentApp agentApp = new FakeAgentApp();
        RuntimeExecutionService service = new RuntimeExecutionService(
                new FakeSessionResolver(completeScope()),
                agentApp,
                inbound -> Optional.empty(),
                new FakeCommandService(null),
                new FakeRouteRegistry(),
                new FakeSceneRouter(),
                new ScopedRuntimeContext()
        );
        AgentInput explicitInput = AgentInput.structuredMessages(List.of(new UserMessage("structured hello")), null);

        RuntimeExecutionResult result = service.execute(RuntimeExecutionRequest.builder()
                .inbound(inbound(""))
                .agentInput(explicitInput)
                .build());

        assertTrue(result.chatResult().success());
        assertSame(explicitInput, agentApp.input);
    }

    @Test
    void disabledCommandParsingTreatsSlashTextAsChatInput() {
        FakeSessionResolver sessionResolver = new FakeSessionResolver(completeScope());
        FakeAgentApp agentApp = new FakeAgentApp();
        RuntimeExecutionService service = new RuntimeExecutionService(
                sessionResolver,
                agentApp,
                inbound -> Optional.of(new ChannelCommand("help", "", "/help")),
                new FakeCommandService(OutboundMessage.builder()
                        .kind(OutboundMessage.Kind.TEXT)
                        .sessionId("command-session")
                        .text("command ok")
                        .build()),
                new FakeRouteRegistry(),
                new FakeSceneRouter(),
                new ScopedRuntimeContext()
        );

        RuntimeExecutionResult result = service.execute(RuntimeExecutionRequest.builder()
                .inbound(inbound("/help"))
                .commandParsingEnabled(false)
                .build());

        assertFalse(result.commandHandled());
        assertTrue(result.chatResult().success());
        assertEquals(1, sessionResolver.resolveCalls);
        assertEquals("/help", agentApp.input.getText());
    }

    private static RuntimeScope completeScope() {
        SceneConfig scene = new SceneConfig();
        scene.setSceneId("assistant");
        MemoryPolicyConfig memoryPolicy = MemoryPolicyConfig.builder()
                .enabled(true)
                .strategyId("custom")
                .access(SceneConfig.MemoryAccess.READWRITE)
                .scope(SceneConfig.MemoryScopePolicy.SESSION)
                .promptMode(SceneConfig.MemoryPromptMode.FULL)
                .providers(Set.of("builtin"))
                .allowCrossSessionSearch(true)
                .build();
        RuntimePolicy runtimePolicy = RuntimePolicy.builder()
                .sceneId("assistant")
                .memoryPolicy(memoryPolicy)
                .capabilityView(CapabilityView.builder().build())
                .knowledgeSources(Set.of())
                .build();
        Principal principal = Principal.builder().id("user-1").channelType(ChannelType.TUI).build();
        ChannelIdentity identity = ChannelIdentity.builder()
                .channelType(ChannelType.TUI)
                .conversationType(ConversationType.DIRECT)
                .build();
        return RuntimeScope.builder()
                .sessionId("session-1")
                .principal(principal)
                .channelIdentity(identity)
                .runtimePolicy(runtimePolicy)
                .workingDirectory(Path.of("/tmp/work"))
                .build();
    }

    private static InboundMessage inbound(String text) {
        return inbound(text, null);
    }

    private static InboundMessage inbound(String text, String requestedSessionId) {
        return InboundMessage.builder()
                .text(text)
                .requestedSessionId(requestedSessionId)
                .principal(Principal.builder().id("user-1").channelType(ChannelType.TUI).build())
                .channelIdentity(ChannelIdentity.builder()
                        .channelType(ChannelType.TUI)
                        .conversationType(ConversationType.DIRECT)
                        .build())
                .build();
    }

    private static class FakeAgentApp implements AgentRuntimeExecutor {
        RuntimeExecutionRequest.PersistenceMode persistenceMode;
        AgentInput input;

        @Override
        public ChatResult execute(RuntimeScope scope, AgentInput input, RuntimeCallbacks callbacks) {
            this.input = input;
            return ChatResult.success("ok", scope.getSessionId(), false);
        }

        @Override
        public ChatResult execute(RuntimeScope scope, AgentInput input, RuntimeCallbacks callbacks,
                                  RuntimeExecutionRequest.PersistenceMode persistenceMode) {
            this.persistenceMode = persistenceMode;
            this.input = input;
            return ChatResult.success("ok", scope.getSessionId(), false);
        }
    }

    private static class FakeSessionResolver extends SessionResolver {
        private final RuntimeScope scope;
        int resolveCalls;
        int createCalls;
        int ephemeralCalls;
        int releaseCalls;
        String lastRequestedSessionId;
        boolean failWithSecurityException;

        FakeSessionResolver(RuntimeScope scope) {
            super(null, null, null, null, null, null, null, null, null);
            this.scope = scope;
        }

        @Override
        public RuntimeScope resolveOrCreateSession(Principal principal, ChannelIdentity channelIdentity, String sceneId, String requestedSessionId) {
            resolveCalls++;
            lastRequestedSessionId = requestedSessionId;
            if (failWithSecurityException) {
                throw new SecurityException("Session is outside current isolation scope");
            }
            return scope;
        }

        @Override
        public RuntimeScope createSession(Principal principal, ChannelIdentity channelIdentity, String sceneId) {
            createCalls++;
            return scope;
        }

        @Override
        public RuntimeScope createEphemeralScope(Principal principal, ChannelIdentity channelIdentity, String sceneId) {
            ephemeralCalls++;
            return scope;
        }

        @Override
        public void releaseEphemeralScope(RuntimeScope scope) {
            releaseCalls++;
        }
    }

    private static class FakeSceneRouter extends ChannelSceneRouter {
        FakeSceneRouter() {
            super(null, null);
        }

        @Override
        public EffectiveChannelRoute resolve(InboundMessage inbound) {
            return new EffectiveChannelRoute("assistant", null, EffectiveChannelRoute.Source.SCENE_DEFAULT);
        }
    }

    private static class FakeCommandService extends ChannelCommandService {
        private final OutboundMessage reply;

        FakeCommandService(OutboundMessage reply) {
            super(null);
            this.reply = reply;
        }

        @Override
        public boolean supports(ChannelCommand command) {
            return reply != null;
        }

        @Override
        public OutboundMessage execute(ChannelCommand command, InboundMessage inbound, String sceneId) {
            return reply;
        }
    }

    private static class FakeRouteRegistry extends SessionRouteRegistry {
        boolean unregistered;

        @Override
        public void unregister(SessionRoute route) {
            unregistered = true;
        }
    }

    private static class RecordingCallbacks implements RuntimeCallbacks {
        String startedSessionId;

        @Override
        public void started(RuntimeScope scope) {
            startedSessionId = scope.getSessionId();
        }
    }
}
