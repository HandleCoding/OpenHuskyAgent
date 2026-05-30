# /stop 与 bypass 命令机制渐进式实施计划

## 背景

openHusky 当前需要一个统一的运行态控制机制：当用户通过 TUI、飞书或其他支持命令的渠道发送 `/stop` 时，系统应能绕过普通消息队列，及时停止当前 agent loop，并尽力释放正在执行的工具线程；同时后续 `/new`、`/switch`、`/reset`、只读 `/status` 等命令也可能需要在 busy 状态下立即生效。

因此，这个能力不应做成 `/stop` 的单点特判，而应抽象为通用 bypass command 机制：命令进入系统后先被分类为普通排队命令或运行态控制命令，bypass 命令可以跳过当前会话队列，先决定 active run 和 pending queue 的命运，再执行自己的命令语义。

参考 Hermes 的做法，可靠的 stop 需要同时处理：active run 标记取消、owner thread interrupt、tool future cancel、pending approval/clarify 释放、run generation 防止旧结果回流、旧 run 迟到输出抑制、会话 busy 状态释放。stop 的成功标准不是底层所有线程瞬时死亡，而是当前 run 被失效，用户可以继续操作，旧 run 不能再污染 UI、channel 输出或消息历史。

## 目标语义

- 引入通用 bypass command 框架，而不是为 `/stop`、`/new` 分别写分散特判。
- `/stop` 停止当前 session 的当前 agent turn，不销毁 session，不回滚历史，不默认清理 pending prompt。
- `/new` 在 busy 状态下也应立即生效：先让当前 active run 失效并按策略中断，再创建新 session，并让旧 pending prompt 失效。
- 首批 bypass 命令包含 `/stop` 与 `/new`/`/newsession`/`/new-session`。
- 首版不自动 kill `terminal background=true` 启动的后台任务；后台任务继续由已有 background task 管理能力处理。
- 取消后的 run 不应走正常完成路径：不保存半截 assistant，不触发正常 `SESSION_END`，不执行正常 todo clear，不发送 normal complete。
- 旧 run 即使底层稍后完成，也必须被 generation/current guard 隔离。

## 已完成的第一阶段实现

### Runtime run coordination

新增/修改：

- `application/src/main/java/io/github/huskyagent/application/runtime/SessionRunCoordinator.java`
- `application/src/main/java/io/github/huskyagent/application/runtime/RunHandle.java`
- `application/src/main/java/io/github/huskyagent/application/runtime/StopResult.java`
- `application/src/main/java/io/github/huskyagent/application/runtime/RunCancelledException.java`
- `domain/src/main/java/io/github/huskyagent/domain/runtime/RunHandle.java`
- `domain/src/main/java/io/github/huskyagent/domain/runtime/RunCancellationRegistry.java`

当前职责：

- 每次正常执行通过 `registerStart(sessionId, ownerThread)` 注册 active run。
- active run 携带 `sessionId`、`runId`、`generation`。
- `/stop` 或 `/new` 可调用 `interrupt(sessionId, reason)` / `expire(sessionId, reason)` 让 run 失效。
- coordinator 负责 `isCurrent(...)`、`isCancelled(...)`、`finishIfCurrent(...)`。
- coordinator 维护 queue generation，用于 `/new` 让 pending prompt 失效。
- coordinator 维护当前 run 的 tool futures，用于 stop 时 best-effort `Future.cancel(true)`。

### RuntimeExecutionService 接入

修改：

- `application/src/main/java/io/github/huskyagent/application/runtime/RuntimeExecutionService.java`
- `application/src/main/java/io/github/huskyagent/application/runtime/RuntimeExecutionRequest.java`
- `application/src/main/java/io/github/huskyagent/application/runtime/AgentRuntimeExecutor.java`
- `application/src/main/java/io/github/huskyagent/application/ChatResult.java`

当前行为：

- `ChatResult.ErrorCode` 增加 `CANCELLED`。
- 新增 `ChatResult.cancelled(...)` 工厂方法。
- `RuntimeExecutionService.execute(...)` resolve scope 后注册 `RunHandle`。
- `RunHandle` 传给 `AgentRuntimeExecutor` 的新重载。
- callbacks 被 wrapped 成 guarded callbacks，旧 run 不再发 token/completed/failed；approval/clarify 在 stale 时自动释放。
- 执行结束后如果 run 不再 current，返回 `ChatResult.cancelled(...)`。
- finally 使用 `finishIfCurrent(...)`，避免旧 run finally 删除新 run。
- 暴露 `interruptSession(...)`、`expireSessionRun(...)`、`runCoordinator()` 给 TUI/channel 入口使用。

### ReAct graph 接入取消

修改：

- `application/src/main/java/io/github/huskyagent/application/ReActAgentApp.java`
- `domain/src/main/java/io/github/huskyagent/domain/graph/RequestToolContext.java`

当前行为：

- `ReActAgentApp.execute(...)` 支持接收 `RunHandle`。
- `RunnableConfig` metadata 放入 `RequestToolContext`，并把 domain `RunHandle` / `RunCancellationRegistry` 传给 graph tool context。
- `runWithInterruptLoop(...)` 在关键节点检查取消，并在取消时返回 `ChatResult.cancelled(...)`，避免正常 final state 处理。

### Tool future cancellation

修改：

- `domain/src/main/java/io/github/huskyagent/domain/graph/node/TimedToolTask.java`
- `domain/src/main/java/io/github/huskyagent/domain/graph/node/ParallelExecutorNode.java`

当前行为：

- `TimedToolTask.submit(...)` 支持传入 `RunHandle` 和 `RunCancellationRegistry`。
- tool worker `Future` 提交后注册到 run coordinator，完成后 unregister。
- `/stop` 时 coordinator 对已注册 future 执行 `cancel(true)`。
- `ParallelExecutorNode` 在 `allOf(...).handle(...)` 阶段检查 run 是否 cancelled；如果已取消，不聚合正常 tool response，而返回 `parallel_executor cancelled` 状态。

### Channel bypass command

新增/修改：

- `application/src/main/java/io/github/huskyagent/application/channel/BypassCommandPolicy.java`
- `application/src/main/java/io/github/huskyagent/application/channel/CommandExecutionMode.java`
- `application/src/main/java/io/github/huskyagent/application/channel/ChannelRuntimeService.java`
- `application/src/main/java/io/github/huskyagent/application/channel/ChannelCommandService.java`

当前行为：

- `BypassCommandPolicy` 将 `/stop` 映射为 `BYPASS_CANCEL_ACTIVE`。
- `/new`、`/newsession`、`/new-session` 映射为 `BYPASS_REPLACE_ACTIVE_AND_PENDING`。
- `ChannelRuntimeService.handleInboundAsync(...)` 先解析 command 和 bypass mode，再决定是否进入普通 queue。
- `/stop` bypass 当前 queue，调用 `runtimeExecutionService.interruptSession(...)` 并立即回复。
- `/new` bump queue generation，expire/interrupt 旧 active run，创建新 session 并立即回复。
- 普通消息入队时捕获 queue generation，真正执行前二次校验；如果 generation 已变，返回 `ChatResult.cancelled(...)`。

### TUI 接入

修改：

- `application/src/main/java/io/github/huskyagent/application/tui/JsonRpcMethods.java`
- `application/src/main/java/io/github/huskyagent/application/tui/TuiSessionService.java`
- `client/src/main/java/io/github/huskyagent/tui/CommandHandler.java`

当前行为：

- TUI JSON-RPC `session.interrupt` 已接入 `TuiSessionService.interruptCurrentRun(...)`。
- 客户端 `CommandHandler` 增加 `/stop`。
- `/new` 通过 `createSession()` 触发 queue generation bump、旧 run interrupt，再创建新 session。
- TUI prompt 入队时捕获 queue generation；`/new` 后旧 pending prompt 会返回 cancelled。
- `interruptCurrentRun(...)` 会释放 pending approval/clarify，并向 emitter 发送 cancelled status。

### 已补充测试

修改：

- `application/src/test/java/io/github/huskyagent/application/runtime/RuntimeExecutionServiceTest.java`
- `application/src/test/java/io/github/huskyagent/application/channel/ChannelRuntimeServiceTest.java`
- `application/src/test/java/io/github/huskyagent/application/tui/TuiSessionServiceTest.java`
- `domain/src/test/java/io/github/huskyagent/domain/graph/node/ExecuteToolNodeTest.java`
- `domain/src/test/java/io/github/huskyagent/domain/graph/node/ParallelExecutorNodeTest.java`

覆盖点：

- runtime cancelled run 不触发 normal completed callback。
- stale approval/clarify 会被 guarded callback 自动释放。
- channel `/stop` bypass busy queue 并调用 interrupt。
- channel `/new` bypass busy queue 并让 pending prompt cancelled。
- TUI `/new` 让 pending prompt cancelled。
- TUI `/stop` 不清 pending prompt。
- `ParallelExecutorNode` cancelled run 后 suppress 正常 tool response。
- `TimedToolTask` timeout 后 worker thread 可释放。

已执行通过：

```bash
./mvnw -B -ntp test -pl application,domain -am -Dtest=RuntimeExecutionServiceTest,ChannelRuntimeServiceTest,TuiSessionServiceTest,ParallelExecutorNodeTest -Dsurefire.failIfNoSpecifiedTests=false
```

结果：`Tests run: 23, Failures: 0, Errors: 0, Skipped: 0`。

## 渐进式后续计划

### 阶段 2：修复 review 发现的高优先级正确性问题

#### 2.1 修复 TUI 旧 run 覆盖 currentSessionId

问题：

- 文件：`application/src/main/java/io/github/huskyagent/application/tui/TuiSessionService.java`
- 位置：`executePrompt(...)` 中对 `currentSessionId` 的更新。
- 当前代码在旧 prompt 返回时，只要 `executionResult.scope() != null`，就无条件执行：

```java
this.currentSessionId = executionResult.scope().getSessionId();
```

风险场景：

1. TUI 当前 session 是 `session-A`。
2. 用户发送长任务。
3. 长任务仍在执行时用户执行 `/new`。
4. `/new` 创建 `session-B`，并把 `currentSessionId` 更新为 `session-B`。
5. 旧长任务被取消后返回，其 `executionResult.scope()` 仍是 `session-A`。
6. `executePrompt(...)` 无条件把 `currentSessionId` 写回 `session-A`。
7. 用户后续输入会错误地进入旧 session。

修复建议：

- 只有当当前 session 仍等于该 prompt 准备时的 session，且结果不是 `CANCELLED` 时，才允许写回 `currentSessionId`。
- 示例策略：

```java
if (executionResult.scope() != null
        && result.errorCode() != ChatResult.ErrorCode.CANCELLED
        && Objects.equals(currentSessionId, prepared.sessionId())) {
    this.currentSessionId = executionResult.scope().getSessionId();
}
```

- 增加测试：长 prompt -> `/new` -> 旧 prompt cancelled 返回，不应把 `currentSessionId` 覆盖回旧 session。

#### 2.2 ReActAgentApp 取消检查改为权威 run 状态

问题：

- 文件：`application/src/main/java/io/github/huskyagent/application/ReActAgentApp.java`
- 当前 `throwIfCancelled(runHandle)` 主要检查 `Thread.currentThread().isInterrupted()`。

风险场景：

- `/stop` interrupt owner thread。
- LLM/graph/tool 库捕获 `InterruptedException` 并清除 interrupt flag。
- 后续 `throwIfCancelled(...)` 看不到 interrupt，旧 run 可能继续执行。
- 如果继续走到 `handleFinalState(...)`，可能保存已取消 run 的 assistant message 或触发正常收尾。

修复建议：

- `throwIfCancelled(...)` 同时检查 `runCoordinator.isCancelled(runHandle)`。
- thread interrupt 只作为唤醒机制，不作为唯一取消语义来源。
- 示例策略：

```java
private void throwIfCancelled(RunHandle runHandle) {
    if (runHandle == null) {
        return;
    }
    if (Thread.currentThread().isInterrupted() || runCoordinator.isCancelled(runHandle)) {
        throw new RunCancelledException(runHandle.sessionId());
    }
}
```

- 在 graph stream 前、node output 后、interruption metadata 前后、final state 前都继续调用该方法。
- 增加测试或轻量 fake：模拟 interrupt flag 被清除但 coordinator 已 cancelled，确认不会进入 normal final state。

#### 2.3 bypass 命令避免被 agent executor starvation

问题：

- 文件：`application/src/main/java/io/github/huskyagent/application/channel/ChannelRuntimeService.java`
- 当前 bypass 分支仍使用：

```java
CompletableFuture.supplyAsync(() -> handleBypass(...), executor)
```

风险场景：

- agent executor 被多个长任务占满。
- 用户发送 `/stop`。
- `/stop` 自身被提交到同一个 executor，但没有空闲 worker 执行。
- active run 无法及时收到 interrupt，bypass 失去意义。

修复建议：

- bypass 分支应在当前 request/webhook 线程上快速执行并返回 `completedFuture`。
- 或引入独立 control executor，专门处理运行态控制命令。
- 首选简单策略：`handleInboundAsync(...)` 对 bypass command 直接执行：

```java
return CompletableFuture.completedFuture(handleBypass(...));
```

- 前提：`handleBypass(...)` 只能做快速内存状态更新、session 创建、短回复，不得执行长 agent run。
- 增加测试：单线程 executor 被普通消息占用时，`/stop` future 仍能立即完成。

#### 2.4 tool cancel 应主动完成对应 CompletableFuture

问题：

- 文件：`domain/src/main/java/io/github/huskyagent/domain/graph/node/TimedToolTask.java`
- 当前 coordinator cancel 的对象主要是 worker `Future`。
- worker 被 `Future.cancel(true)` 后，外层 `CompletableFuture result` 不一定立即完成。

风险场景：

- 工具任务尚未开始，worker future 被 cancel。
- 或工具吞掉 interrupt / 阻塞在不可中断 IO。
- `CompletableFuture.allOf(...)` 仍等待 result，直到 timeout scheduler 触发。
- 用户感知为 `/stop` 后 graph/tool 仍卡很久。

修复建议：

- 不只注册 worker `Future`，还要注册一个能主动完成 result 的 cancellation handle。
- 简化方案：在 `TimedToolTask` 内创建可取消包装，cancel 时同时：
  - `worker.cancel(true)`
  - `result.completeExceptionally(new CancellationException("Run cancelled"))`
- registry API 可从 `Future<?>` 演进为更语义化的 cancellable task handle。
- 短期可在 domain `RunCancellationRegistry` 增加默认方法或新增 `CancellableTask`，避免只依赖 worker future。
- 增加测试：注册后立即外部 cancel，`ParallelExecutorNode` 应快速返回 cancelled update，而不是等 timeout。

### 阶段 3：修复行为一致性与回归风险

#### 3.1 queued TUI prompt cancelled 时补发 terminal event

问题：

- 文件：`application/src/main/java/io/github/huskyagent/application/tui/TuiSessionService.java`
- 当前 queue generation 失效时直接返回 `ChatResult.cancelled(...)`，不会进入 `executePrompt(...)`，因此不会 `emitMessageComplete(...)`。

风险场景：

- prompt.submit 已排队但尚未执行。
- 用户执行 `/new`。
- queued prompt 返回 cancelled。
- JSON-RPC response 会完成，但如果 UI 状态依赖 `message.complete` 事件收敛，可能残留 loading/thinking 状态。

修复建议：

- generation stale 分支返回前，如果 emitter 不为空，应 emit `message.complete status=cancelled`。
- 或抽取统一 `completePrompt(result, emitter, startTime)`，让正常、错误、queued cancelled 都走同一 terminal event 逻辑。

#### 3.2 legacy ChannelCommandService 不应假支持 /stop

问题：

- 文件：`application/src/main/java/io/github/huskyagent/application/channel/ChannelCommandService.java`
- 当前 `/stop` 在普通 command service 中返回占位文本：“Stop is handled by the runtime bypass path.”

风险场景：

- 某些入口没有经过 `ChannelRuntimeService.handleInboundAsync(...)` 的 bypass 分支，而是直接调用 `RuntimeExecutionService.execute(...)`。
- `/stop` 被 `ChannelCommandService.supports(...)` 命中。
- 系统只回复占位文本，不会真正 stop。

修复建议：

- 短期：不要在普通 `ChannelCommandService` 中 advertise `/stop` supported，避免假成功。
- 更好的方式：把 command parsing 和 bypass dispatcher 上提为所有入口共享能力，`/stop` 在任何入口语义一致。

#### 3.3 SSE / OpenAI-compatible 入口补 queue generation 校验

潜在问题：

- 文件：
  - `service/src/main/java/io/github/huskyagent/service/controller/SseChatController.java`
  - `service/src/main/java/io/github/huskyagent/service/openai/OpenAiCompatibleRuntimeService.java`
- review 认为这些入口可能直接使用 `ChannelInboundQueue.enqueue(...)`，没有像 `ChannelRuntimeService` / `TuiSessionService` 一样做 generation snapshot 和二次校验。

风险场景：

- HTTP/SSE 或 OpenAI-compatible 请求排在长任务后面。
- 用户通过另一个支持 bypass 的渠道执行 `/new`。
- queue generation 被 bump，但这些入口的 pending request 没有校验 generation。
- 旧 pending request 之后仍执行，写入被替换的旧 session。

修复建议：

- 核实这些入口实际 queue key 是否与 channel/TUI queue generation 共享。
- 如果共享，应在 enqueue 时捕获 generation，执行前校验。
- 更彻底：把 generation check 下沉到统一 queue wrapper，而不是每个入口手写。

#### 3.4 TUI 客户端生成中是否真的能输入 /stop 需要验证

潜在问题：

- 文件：`client/src/main/java/io/github/huskyagent/tui/CommandHandler.java`
- `/stop` 命令已经存在，但如果主输入循环在 `prompt.submit` future 完成前不再读取用户输入，则用户在生成中输入 `/stop` 不会被处理。

修复建议：

- 检查 `AgentTUI` 的 prompt submit / input loop 模型。
- 如果当前生成中无法继续读命令，应增加：
  - Ctrl-C -> `session.interrupt`
  - 或让 prompt submit 后台化，主输入循环继续可接收 `/stop`
  - 或为 TUI 增加专门 interrupt keybinding。

#### 3.5 stateless / ephemeral scope 的 run key 校验

潜在问题：

- 文件：`application/src/main/java/io/github/huskyagent/application/runtime/RuntimeExecutionService.java`
- review 提到如果 stateless 请求没有有效 session id，多个并发请求可能都落入 `session:unknown` cancellation key。

修复建议：

- 核实 `SessionResolver.createEphemeralScope(...)` 是否总是生成非空 session id。
- 如果任何入口可能传入空 session，应在 `registerStart(...)` 前强制生成唯一 run scope id，或拒绝空 session。
- `normalizeSessionId("session:unknown")` 只适合作为防御兜底，不应成为正常并发路径。

### 阶段 4：统一 command dispatcher，提升架构简洁性和扩展性

当前架构问题：

- `BypassCommandPolicy` 只返回 enum，真实行为在 `ChannelRuntimeService` switch 中。
- `ChannelCommandService` 仍处理部分相同命令。
- TUI 通过 `session.create` / `session.interrupt` 自己实现一套 bypass 语义。
- RuntimeExecutionService 仍内置 command parsing，导致 command 责任分散。

建议演进目标：

- 引入统一 command definition / dispatcher。
- 每个 command 定义包含：
  - `name`
  - `aliases`
  - `queuePolicy`
  - `cancellationPolicy`
  - `pendingPolicy`
  - `sessionPolicy`
  - `handler`
  - `replyPolicy`
- transport adapter 只负责把 TUI JSON-RPC、channel slash command、未来 API command 转为统一 command request。
- dispatcher 根据 policy 决定是否 bypass queue、是否 interrupt active run、是否 bump queue generation、是否创建/切换 session。

可能的模型：

```java
enum QueuePolicy {
    NORMAL_QUEUED,
    BYPASS_CONTROL,
    BYPASS_READONLY
}

enum ActiveRunPolicy {
    KEEP,
    INTERRUPT_CURRENT,
    EXPIRE_CURRENT,
    DETACH_CURRENT
}

enum PendingPolicy {
    KEEP_PENDING,
    DROP_PENDING_FOR_QUEUE,
    DROP_PENDING_FOR_SESSION
}
```

示例语义：

- `/stop`：`BYPASS_CONTROL + INTERRUPT_CURRENT + KEEP_PENDING`
- `/new`：`BYPASS_CONTROL + EXPIRE_CURRENT + DROP_PENDING_FOR_QUEUE + CREATE_SESSION`
- `/switch`：需要明确是 `KEEP/DETACH/INTERRUPT` 当前 run，以及是否 drop pending。
- `/status`：`BYPASS_READONLY + KEEP + KEEP_PENDING`
- `/reset`：`BYPASS_CONTROL + INTERRUPT_CURRENT + DROP_PENDING_FOR_SESSION + RESET_SESSION_STATE`

## 长期架构优化建议

### 拆分 SessionRunCoordinator 职责

当前 `SessionRunCoordinator` 同时负责：

- active run registry
- run cancellation state
- owner thread interrupt
- tool future registry
- queue generation

短期可接受，但长期建议拆分：

- `RunRegistry` / `CancellationTokenSource`：维护 active run、generation、cancel token。
- `QueueInvalidator`：维护 queue key generation，或直接内聚到 `ChannelInboundQueue`。
- `ToolTaskRegistry`：管理当前 run 的前台 tool task cancel handle。
- `RuntimeControlService`：组合这些能力，对外提供 `stop`、`new`、`switch` 等 control API。

### 用 CancellationToken 替代 Future 泄漏

当前 domain runtime 接口：

```java
void registerToolFuture(RunHandle handle, Future<?> future);
```

问题：

- domain 层暴露 Java executor `Future<?>`，抽象偏底层。
- application/domain 各有一个 `RunHandle`，需要转换。
- future cancel 不能表达“同时 complete result future”这类语义。

长期建议：

```java
interface CancellationToken {
    boolean isCancelled();
    void throwIfCancelled();
    CancellationRegistration onCancel(Runnable action);
}
```

Tool 层只关心 token，不关心 coordinator、session generation 或 Future。

### RuntimeExecutionService 退出 command parsing 职责

当前 `RuntimeExecutionService.execute(...)` 内部仍解析 command。长期看，它应更聚焦于：

- 已解析 runtime scope
- agent input
- callbacks
- persistence mode
- run lifecycle

Command parsing/dispatch 应位于 channel/TUI/API adapter 与 runtime execute 之间的 command control 层，避免内部调用方误把普通 slash text 当 command short-circuit。

## 推荐下一步执行顺序

1. 先修 `TuiSessionService` 旧 run 覆盖 `currentSessionId`。
2. 修 `ReActAgentApp.throwIfCancelled(...)`，同时检查 coordinator 的 run cancellation 状态。
3. 修 `ChannelRuntimeService` bypass 分支，不再提交到同一个 agent executor。
4. 修 `TimedToolTask` 外部 cancel 不主动 complete result 的问题。
5. 补 queued TUI prompt cancelled 的 `message.complete` 事件。
6. 清理 `ChannelCommandService` 中 `/stop` 的假支持，或统一接入 shared command dispatcher。
7. 核查 SSE/OpenAI-compatible queue generation 风险。
8. 验证 TUI 客户端生成中是否能触发 `/stop`，必要时增加 Ctrl-C interrupt。
9. 在这些 correctness 修复后，再开始统一 command dispatcher 的架构重构。

## 验证建议

每轮修复后至少执行：

```bash
./mvnw -B -ntp test -pl application,domain -am -Dtest=RuntimeExecutionServiceTest,ChannelRuntimeServiceTest,TuiSessionServiceTest,ParallelExecutorNodeTest -Dsurefire.failIfNoSpecifiedTests=false
```

关键修复完成后执行：

```bash
./mvnw -B -ntp test
```

人工验证：

- TUI 长任务运行中 `/stop`，确认能及时停止，且后续 prompt 正常进入同一 session。
- TUI 长任务运行中 `/new`，确认立即切到新 session，旧 run cancelled 后不会把当前 session 切回旧 session。
- TUI pending prompt + `/new`，确认 pending prompt 被 cancelled，UI 收到 terminal event。
- 飞书/其他 channel 长任务运行中 `/stop`，确认 stop 回复不被长任务阻塞。
- 飞书/其他 channel 长任务 + pending prompt + `/new`，确认 pending prompt 不再执行。
- 长工具调用中 `/stop`，确认 graph 不等到工具原 timeout 才结束。
