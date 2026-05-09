package io.github.huskyagent.domain.prompt;

import io.github.huskyagent.domain.runtime.RuntimePolicy;
import io.github.huskyagent.domain.scene.SceneConfig;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.Principal;
import io.github.huskyagent.infra.session.SessionScope;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Prompt 组装上下文
 *
 * 包含构建系统提示所需的所有信息
 */
public class PromptContext {

    private final String sessionId;
    private final Path workingDirectory;
    private RuntimePolicy runtimePolicy;
    private SessionScope sessionScope;

    // 可选的额外信息
    private String gatewaySystemPrompt;
    private String memoryContent;
    private String userContent;
    private ChannelIdentity channelIdentity;
    private Principal principal;
    private String sceneId;

    public PromptContext(String sessionId, Path workingDirectory) {
        this.sessionId = sessionId;
        this.workingDirectory = workingDirectory;
    }

    // === Getters ===

    public String getSessionId() {
        return sessionId;
    }

    public Path getWorkingDirectory() {
        return workingDirectory;
    }

    public Optional<String> getGatewaySystemPrompt() {
        return Optional.ofNullable(gatewaySystemPrompt);
    }

    public Optional<String> getMemoryContent() {
        return Optional.ofNullable(memoryContent);
    }

    public Optional<String> getUserContent() {
        return Optional.ofNullable(userContent);
    }

    public Optional<ChannelIdentity> getChannelIdentity() {
        return Optional.ofNullable(channelIdentity);
    }

    public Optional<Principal> getPrincipal() {
        return Optional.ofNullable(principal);
    }

    public Optional<String> getSceneId() {
        return Optional.ofNullable(sceneId);
    }

    public RuntimePolicy getRuntimePolicy() {
        return Objects.requireNonNull(runtimePolicy, "runtimePolicy is required for prompt building");
    }

    public Optional<SessionScope> getSessionScope() {
        return Optional.ofNullable(sessionScope);
    }

    public String getSceneSystemPrompt() {
        return getRuntimePolicy().getSystemPrompt();
    }

    public List<String> getPromptFiles() {
        List<String> promptFiles = getRuntimePolicy().getPromptFiles();
        return promptFiles != null ? promptFiles : List.of();
    }

    public SceneConfig.PromptFilePolicy getPromptFilePolicy() {
        SceneConfig.PromptFilePolicy promptFilePolicy = getRuntimePolicy().getPromptFilePolicy();
        return promptFilePolicy != null ? promptFilePolicy : SceneConfig.PromptFilePolicy.APPEND;
    }

    public SceneConfig.BackendPolicy getBackendPolicy() {
        return getRuntimePolicy().getBackendPolicy();
    }

    public Set<String> getKnowledgeSources() {
        Set<String> knowledgeSources = getRuntimePolicy().getKnowledgeSources();
        return knowledgeSources != null ? knowledgeSources : Set.of();
    }

    // === Setters ===

    public PromptContext gatewaySystemPrompt(String prompt) {
        this.gatewaySystemPrompt = prompt;
        return this;
    }

    public PromptContext memoryContent(String content) {
        this.memoryContent = content;
        return this;
    }

    public PromptContext userContent(String content) {
        this.userContent = content;
        return this;
    }

    public PromptContext runtimePolicy(RuntimePolicy runtimePolicy) {
        this.runtimePolicy = runtimePolicy;
        return this;
    }

    public PromptContext sessionScope(SessionScope sessionScope) {
        this.sessionScope = sessionScope;
        return this;
    }

    public PromptContext channelIdentity(ChannelIdentity channelIdentity) {
        this.channelIdentity = channelIdentity;
        return this;
    }

    public PromptContext principal(Principal principal) {
        this.principal = principal;
        return this;
    }

    public PromptContext sceneId(String sceneId) {
        this.sceneId = sceneId;
        return this;
    }

    public PromptContext withSessionId(String sessionId) {
        PromptContext copy = new PromptContext(sessionId, workingDirectory)
                .gatewaySystemPrompt(gatewaySystemPrompt)
                .memoryContent(memoryContent)
                .userContent(userContent)
                .runtimePolicy(runtimePolicy)
                .sessionScope(sessionScope)
                .channelIdentity(channelIdentity)
                .principal(principal)
                .sceneId(sceneId);
        return copy;
    }

    public PromptContext withChannelContext(String sessionId, ChannelIdentity identity, Principal principal) {
        PromptContext copy = new PromptContext(sessionId, workingDirectory)
                .gatewaySystemPrompt(gatewaySystemPrompt)
                .memoryContent(memoryContent)
                .userContent(userContent)
                .runtimePolicy(runtimePolicy)
                .sessionScope(sessionScope)
                .channelIdentity(identity != null ? identity : this.channelIdentity)
                .principal(principal != null ? principal : this.principal)
                .sceneId(sceneId);
        return copy;
    }

    // === Factory ===

    public static PromptContext of(String sessionId, Path workingDirectory) {
        return new PromptContext(sessionId, workingDirectory);
    }
}