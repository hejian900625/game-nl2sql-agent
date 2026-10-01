# DeepSeek 模型 ID 与框架上下文窗口表（上游 note，2026-10-01）

一句话：**框架 2.0.3 的 `ModelContextWindows.DEEPSEEK` 表里没有 DeepSeek 现在的正式名 `deepseek-flash`，
用正式名建出来的 model，`getContextWindowSize()` 返回 0。** 附带纠正我们自己写进 PLAN 的一条错判：
表里那个 `deepseek-v4-flash` **并没有被 DeepSeek 拒掉**，它是官方保留的别名。

## 实测（2026-10-01，真 key，逐条可重跑）

```bash
# 1. 官方当前只有这两个 id
curl -s -H "Authorization: Bearer $DEEPSEEK_API_KEY" https://api.deepseek.com/models
#   deepseek-flash   | DeepSeek-V4.1-Flash | context_window 1048576
#   deepseek-v4-pro  | DeepSeek-V4-Pro     | context_window 1048576

# 2. 表里的旧名是别名，不是死名：请求成功，且响应回填的是正式名
curl -s -X POST https://api.deepseek.com/chat/completions \
  -H "Authorization: Bearer $DEEPSEEK_API_KEY" -H "Content-Type: application/json" \
  -d '{"model":"deepseek-v4-flash","messages":[{"role":"user","content":"say ok"}],"max_tokens":16}'
#   http 200，response.model == "deepseek-flash"

# 3. 真正不存在的名字会得到一条很友好的 400（对照组）
#   "The supported API model names are deepseek-flash, deepseek-v4-pro, but you passed deepseek-bogus-id"
```

框架侧（离线，不打网络）由 `src/test/java/.../agent/DeepSeekModelIdFactsTest.java` 钉住：

| 传入 `deepseek:<name>` | `ModelContextWindows.DEEPSEEK` 里有没有 | `model.getContextWindowSize()` |
|---|---|---|
| `deepseek-flash`（官方正式名，也是我们 `application.yml` 里用的） | **没有** | **0** |
| `deepseek-v4-flash`（表里有，provider 当别名收） | 有 | 1000000 |
| `deepseek-v4-pro`（表里有，也是正式名） | 有 | 1000000 |
| `deepseek-reasoner`（2026-07-24 退役） | 没有 | 0 |

另注意数值本身也不对：官方 `/models` 报的窗口是 **1048576**（2^20，即营销说的"1M"），表里硬编码 **1000000**。

## 影响有多大（也测了，不靠猜）

在 2.0.3 我们 classpath 上的全部 agentscope 构件里搜 `getContextWindowSize` 的引用，命中的只有：

- `io.agentscope.core.model.Model`（接口默认值 0）与 `ChatModelBase`（声明/存储）；
- `io.agentscope.core.ReActAgent$2`（只是把调用转发给 active model 的包装）；
- `io.agentscope.extensions.aistio.adapter.AgentScopeAdapter`（aistio 那条线，本项目不用）。

**也就是说：core 内部目前没有任何逻辑读这个值，0 在进程内是无害的**，不会触发裁剪或压缩异常。
危害在两处：① 任何下游代码（含我们将来若要做的上下文预算、以及官方 harness 侧的压缩）按 0 判断会直接算错；
② 用户照 `application.yml` 用官方正式名，得到一个静默的 0，而**照抄框架表里的旧别名反而一切正常** —— 这种"用对的名字
才踩坑"的不对称最容易被当成框架不可信。

## 修法建议

`ModelContextWindows.DEEPSEEK` 补上 provider 自己报告的正式名（`deepseek-flash`、`deepseek-v4-pro`），
旧别名保留（provider 确实还收），窗口值要么用 1048576，要么在注释里写明取的是"营销 1M"的下取整。
更稳的做法是别把窗口写死成名字表 —— `/models` 每条记录里就带 `context_window`，启动时读一次即可。

## 上游动作

**这条值得开新 issue**：在 `agentscope-ai/agentscope-java` 里按 `deepseek in:title`、
`ModelContextWindows`、`deepseek-flash` 三组关键词搜过（GitHub Search API，2026-10-01），
已有的 15 条 deepseek 标题 issue/PR 全是别的主题（thinking 模式的 `reasoning_content`、结构化输出、
dashscope 路由等），没有一条讲模型名表或窗口值。**不是重复。**

**已提交：[agentscope-ai/agentscope-java#3379](https://github.com/agentscope-ai/agentscope-java/issues/3379)**（2026-10-01，`gh issue create`，标签 `bug` 由模板自动打上）。
下面是当时那份草稿的正文（与已发内容同旨，格式按他们的 `bug_report.md` 模板重排过）：

> **[Bug]: `ModelContextWindows.DEEPSEEK` is missing DeepSeek's current canonical model id, so `getContextWindowSize()` returns 0 for the name DeepSeek itself reports**
>
> Version: agentscope-java 2.0.3 (`agentscope-core`)
>
> DeepSeek's `GET /models` currently returns exactly two ids — `deepseek-flash` (display name
> "DeepSeek-V4.1-Flash") and `deepseek-v4-pro`, each with `context_window: 1048576`. The framework's
> `ModelContextWindows.DEEPSEEK` table, however, is keyed `deepseek-v4-flash` / `deepseek-v4-pro`.
> `deepseek-v4-flash` still works because DeepSeek accepts it as an alias (a chat/completions call with
> that model returns HTTP 200 and echoes `"model": "deepseek-flash"`), but the canonical name is not in
> the table:
>
> ```java
> Model m = ModelRegistry.resolve("deepseek:deepseek-flash",
>         ModelCreationContext.builder().apiKey(key).enableThinking(false).build());
> m.getContextWindowSize(); // 0
> ModelRegistry.resolve("deepseek:deepseek-v4-flash", ...).getContextWindowSize(); // 1000000
> ```
>
> So the *correct*, provider-reported name silently yields 0 while the legacy alias looks healthy. I
> checked the blast radius on 2.0.3: nothing inside `agentscope-core` reads the value (only
> `ChatModelBase`/`Model` and a delegating wrapper in `ReActAgent`), plus `extensions-aistio`'s adapter —
> so it's currently cosmetic in-process, but it will misreport to anything that budgets context (and the
> asymmetry makes the framework look untrustworthy to users who follow DeepSeek's own docs).
>
> Two smaller points:
> 1. The table's `1000000` doesn't match the provider's `1048576`.
> 2. Rather than a hand-maintained name→window table, `/models` already carries `context_window` per
>    entry — a single startup fetch would keep this from rotting when DeepSeek renames models again
>    (`deepseek-chat`/`deepseek-reasoner` were retired 2026-07-24 and the table still doesn't cover the
>    replacement naming).
>
> Repro is offline for the framework half (constructing the model needs no valid key) and one curl for the
> provider half.

## 复现命令

```bash
mvn -o -B -Dtest=DeepSeekModelIdFactsTest test   # 框架侧两条，离线
```

**看门语义**：上游补表之后 `frameworkTableMissingTheCurrentCanonicalFlashId` 会翻红 —— 那是让我们
删掉这份 note 的信号，不是回归。

## 顺带纠正本项目自己的记录

`PLAN.md` 门禁②原来写的是"**AgentScope 文档示例里的 `deepseek-v4-flash` 不存在，照抄会 400**"。
上面第 2 组实测把它推翻了：会 200，走别名。PLAN §10 已按实测改写。教训本身值得留一句：
**"这个 id 返回 400"和"这个 id 不在 `/models` 列表里"是两件事**，我们当时只验了后者就断言了前者。
