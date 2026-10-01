# 框架原生 pause/resume 实验（PLAN §10 的 W2 throwaway，2026-10-01 结案）

目的只有一个：**定位"批准之后的 run 丢工具结果"这个说法到底在哪一层成立**。做法是把模型换成脚本，
等式里只剩框架；跑两层 —— core（直接 `agent.call`）和我们 app 真正走的 AG-UI（HTTP `/agui/run`）。
一次网络都不打，api-key 是占位串，所以这两条用例在 CI 里也跑得动。

承载用例（结论以断言为准，不是本文的转述）：

- `src/test/java/.../hitl/FrameworkPauseResumeProbeTest.java` —— core 层，四种组合（tool_use 是否带原始
  JSON `content` × `enablePendingToolRecovery` 开关）。
- `src/test/java/.../hitl/AguiPauseResumeProbeTest.java` —— AG-UI 层，两个场景（原样批准 / `editedArgs` 整体替换）。

## 一句话结论

**"丢工具结果"是真的，但丢的是给前端的事件流，不是给模型的上下文。** 上游
[agentscope-java#3096](https://github.com/agentscope-ai/agentscope-java/issues/3096)（标题
*"AG-UI: tool results from a resumed run are dropped because the stream context is per-run"*) 描述的就是这件事，
我们在 2.0.3 上把它复现了；修复 PR #3100 已于 2026-09-11 合入 main，**今天仍未发版**（`v2.0.3` 还是最新 tag）。
所以我们当初把这条记成"批准后的 run 连模型都收不到结果"是**转述失真**，这里纠正。

## 实测事实

### core 层：手写的 pause/resume 在 2.0.3 上是好的

| 探针形状 | 阶段 1（ASK） | 阶段 2（`ConfirmResult(true)`） |
|---|---|---|
| `ToolUseBlock` 只填 `input` 映射 | 工具执行 0 次（停住了） | 工具执行 **0** 次，模型收到 `Parameter validation failed for tool 'echo': 未找到所需属性"text"` |
| `ToolUseBlock` 同时填 `content`（原始 JSON） | 工具执行 0 次 | 工具执行 **1** 次，`MARKER` 出现在**第 2 次**模型调用里，`ToolResultBlock(state=SUCCESS)` |

`enablePendingToolRecovery` 开与关，两组行为完全一致 —— 这个开关不参与本缺陷。

**第一个形状是我们自己造的坑，值得单独记**：框架在派发工具时读的是 `ToolUseBlock.getContent()`
（provider 回来的原始 JSON 串），不是 `getInput()`（解析后的映射）。任何"自己拼一个 ToolUseBlock 去恢复"
的代码少了 `content`，看到的就是"工具没跑 + 一句参数校验错"，长得极像 #3096，其实根本不是同一件事。
真 provider 回来的块两个字段都有，所以正常链路不会踩到 —— 除非中间有人做了"映射→重建块"的往返。

### AG-UI 层：模型拿得到，事件流拿不到

第一次 run（`messages` 带用户问题）：

- 事件流：`TOOL_CALL_START` / `TOOL_CALL_ARGS` / `TOOL_CALL_END` → `RAW(generateReason=PERMISSION_ASKING)`
  → `RUN_FINISHED(outcome.type=interrupt)`，interrupt id 形如 `<replyId>:<toolCallId>`，
  metadata 里带 `toolName` / `toolInput` / `toolContent` / `agentscope.interruptKind=permission_confirm`。
- 工具执行 0 次，模型只被调用 1 次。**ASK 真的把执行停住了。**

第二次 run（`resume:[{interruptId, status:"resolved", payload:{approved:true}}]`）：

| 断言到的事实 | 结果 |
|---|---|
| HTTP | 200 |
| 工具被执行 | **1 次**，入参 `hello` |
| 模型被调用 | 第 2 次，输入里**有** `{role=TOOL, ToolResultBlock(state=SUCCESS, out=["ECHO:hello"])}` |
| SSE 里的 `TOOL_CALL_RESULT` | **一条都没有** |
| SSE 里的 `TOOL_CALL_END`（run 2） | 也没有 |

第二次 run 的整个流只剩 `RUN_STARTED` → `TEXT_MESSAGE_*` → `RUN_FINISHED`。也就是说：模型知道答案的出处，
前端永远不知道那次执行的结果 —— 按事件流渲染工具卡片的界面会停在"未 resolves"的状态。

**机制**（与上游 issue 的分析一致，我们在字节码里核对过）：`AguiStreamContext` 每个 run 新建一份，
`beginToolResult` / `endToolResult` 都以私有集合 `startedToolCalls` 做门禁
（`hasStartedToolCall(id)` 不成立就直接 return），而被暂停的那次 `TOOL_CALL_START` 属于**上一个 run**，
新上下文里没有这个 id，于是整条结果被静默丢弃。`markToolCallSuspended` 同样有这个门禁。

### 顺带测出来的一件好事：框架原生支持"人改入参"

`payload` 除了 `approved` 还接受 `editedArgs`，语义是**整体替换**（schema 原文：
*"Full replacement of the tool args. Not merged."*）。实测：

- 工具实际收到的入参 = `changed-by-human`（不是 `hello`，也不是合并结果）；
- 进模型的那条 `ToolUseBlock.input` 也已经是改后的 `{text=changed-by-human}`，`ToolResultBlock` 是 `ECHO:changed-by-human`。

这正是我们 §4 决定 D 里"人可以直接改写 SQL"那一步 —— 框架在 AG-UI 层本来就有对等能力。

## 对项目决定的影响

**§4 的决定 D 不变，但理由要换一条。**

- 不变：v1 的确认门继续做在 `run_sql` 工具内部（一条挂起的响应式流），一次 agent run 保持连续循环。
- 换掉的理由：原先写的是"#3096 会让批准后模型收不到工具结果" —— 这条**不成立**（上表实测：模型收得到）。
- 成立的理由：在 2.0.3 上走原生 pause/resume，**前端拿不到被批准那次执行的结果事件**，而我们的演示页正是
  按事件流渲染的；等 #3100 发版是外部依赖，绕开它是当时唯一能自己控制的选项。
- 我们的 app 现在**不受这个缺陷影响**：确认门在工具内部，工具调用与其结果发生在同一个 run 里，
  `startedToolCalls` 有那个 id，`TOOL_CALL_RESULT` 正常发出（真人浏览器验收时看到过结果）。

可以迁移的条件（记在这里，别靠记忆）：`agentscope-extensions-agui` 出现带 #3100 的正式版本，
且把本文件的两个用例原样跑一遍 —— `AguiPauseResumeProbeTest` 里那两条 `noneMatch` 断言应当翻红，
届时把它们改成 `anyMatch` 才算验证过修复。

## 上游动作：不发新 issue，只发一条复现确认

#3096 已于 2026-09-11 closed、修复 #3100 已合入 main 且未发版，再开一个是重复。有价值的增量是
"另一个 provider/环境下的独立复现 + 一条容易踩的相邻坑"。

**已发出（2026-10-01）**：<https://github.com/agentscope-ai/agentscope-java/issues/3096#issuecomment-5925939634>
（`gh issue comment`，用他账号 `hejian900625` 登录；正文与下面这段草稿同旨，只是去掉了引用块前缀以适配
普通评论排版 —— **留在这份文档里的草稿不是最终发出的字，发出的是 `research/comment-3096.md`，那是
gitignored 的临时件，所以本段是唯一长期可查的记录，别把两者当成同一份文本。**）

> Confirmed on 2.0.3 (`agentscope-extensions-agui`) with a scripted model — no network involved, so the
> repro is fully deterministic. Run 1 emits `TOOL_CALL_START/ARGS/END` and finishes with
> `outcome.type=interrupt`; run 2 (`resume: [{status: "resolved", payload: {approved: true}}]`) **does**
> execute the tool and **does** put `ToolResultBlock(state=SUCCESS)` into the second model call, but the
> SSE stream for run 2 contains neither `TOOL_CALL_END` nor `TOOL_CALL_RESULT`. Exactly the mechanism
> described in the issue: `beginToolResult`/`endToolResult` gate on `hasStartedToolCall(toolCallId)` and
> the context is per-run.
>
> Two things worth adding for anyone hitting this from the other direction:
>
> 1. If you reconstruct the pending `ToolUseBlock` yourself when resuming, note that tool dispatch
>    validates `ToolUseBlock.getContent()` (the raw JSON args string), not `getInput()`. A block built
>    with only the input map fails with `Parameter validation failed for tool 'x': missing required
>    property "y"` and the tool never runs — which looks identical to "the approved run dropped the
>    result" but is a different bug, in the caller.
> 2. `editedArgs` in the resume payload works as documented (whole replacement, and the model then sees
>    the edited input), so the fix only needs to restore the *event*, not the argument plumbing.
>
> Would be great to get a release containing #3100 — the workaround in the issue body is our only option
> on 2.0.3 and it leans on internal `beginEvent()`/`drainEvents()` ordering.

## 复现命令

```bash
mvn -o -B -Dtest='FrameworkPauseResumeProbeTest,AguiPauseResumeProbeTest' test
```

两个类都是纯离线（脚本模型 + 占位 key）。原始事件流会落在 `research/agui-pause-resume-{approve,edit}.txt`
和 `research/pause-resume-probe.txt`（`research/` 是草稿区，不进仓库；要留的东西都已经写成断言）。
