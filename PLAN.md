# NL→SQL Agent 项目计划

> 状态：**Day-0 四件门禁全绿，25 题评测集齐（15 道由我代笔，代价见 §13），骨架 pom/App/application.yml 已落并已提交首个 commit。W1 可以开工。**
> 本文件由 2026-09-30 的八轮设计访谈收敛而来，逐条决定的理由都附在后面 —— 因为将来一定会有人（包括你自己）问"为什么不是更简单的做法"。

## 1. 一句话与目标边界

用 **AgentScope Java 2.0.3** 做一个自然语言问数 agent：用户问一句中文业务问题 → agent 生成只读 SQL → **人审核/编辑后确认** → 执行 → 返回表格 + 总结 + 口径说明。产物是一个**陌生人 clone 下来 10 分钟能跑通**的开源仓库。

**这是一个框架先行的学习项目，不是产品赌注。** 只有三项验收目标：

| # | 学习目标 | 本项目里承载它的东西 |
|---|---|---|
| ① | ReAct 循环与工具调用可靠性 | 连续单 run + `run_sql` 工具 + 失败治理 |
| ② | 权限门 / human-in-the-loop | §6 的挂起式确认门（自研状态机）+ §11 的框架 resume 对照实验 |
| ⑥ | 评测与可观测 | 25 题断言集 + 失败归因四分类 + 准确率入库 |

**明确不做**：多 agent 编排、分层长期记忆、图表/Excel/PDF 导出、写操作、hosted demo、钉钉 channel、MCP 工具、Spring 响应式以外的架构花样。任何"再加个功能"的提议默认视为越界，除非你重开这一节。

## 2. 验收标准（可证伪）

1. `git clone` → 配一个环境变量 → `mvn spring-boot:run` → 浏览器打开就能问数。零 Docker、零手工建库。
2. README 里有一张准确率表（25 题，门槛 **75%**），并列出四类失败各自的数量。
3. 有一个 GIF 录屏，内容包含"人编辑一条 SQL 后再执行"这一帧 —— 这是本项目唯一必须存在的画面。
4. 护栏链有离线单测且进 CI（不需要 API key）。
5. License = **Apache-2.0**；中文主 README + 精简英文 README（英文版只保留 quickstart 与评测结果两节，不做全文双语同步）。

> **2026-10-01 W3-c 逐条对表**
> ① 本机实测：删掉 `data/game.db` 后 `mvn -B spring-boot:run` → `[db] 已生成 …（262 条语句，1247 ms）` → `Started App in 2.192 seconds` → `GET / 200`、`GET /api/confirm/pending → []`。**8080 被本机一个无关进程占着**（pid 5028，不动它），所以这次是在 18086 上验的，README 里给了换端口的写法。
> ② 准确率表在两份 README 里，含严格/宽松两个口径与四类失败计数。**W3-f 加到超出这条**：中文 README 另有逐题明细（每题的期望值、三轮各自跑出的单元格值、错在哪一句），英文 README 只补了"六个非数字终态一次没触发"这一条事实并指回中文表。
> ③ `docs/demo.gif` 由 `tools/record_demo.py` 生成：Playwright 驱动**系统里已有的 Edge**（`channel=msedge`，headless，不下载浏览器），四帧真截图（提问 → 确认卡片 → **人把 `> 3` 改成 `> 5`** → 答复抄执行侧那条），Pillow 拼 GIF，226 KB。**不是画出来的示意图。**
> ④ 83 条用例（82 条纯离线 + 1 条需 key 的探活自动 skip）。`.github/workflows/ci.yml` **写了但当时未被 GitHub 执行过**（仓库还没推远端）——这条在 README 的"已知问题"里照直写着，不许说成"CI 已绿"。
> **2026-10-01 W3-e 更新：这条已经翻成事实**。仓库推到 `github.com/hejian900625/game-nl2sql-agent`（公开），Actions 真跑了两次并全绿 —— run `36824220129` 的日志里是 `Tests run: 88, Failures: 0, Errors: 0, Skipped: 1`、`BUILD SUCCESS`、15 秒。README 顶部现在有 CI 徽章（实测 `badge.svg` 返回 200）。
> ⑤ `LICENSE`（apache.org 原文拉取，202 行）+ `README.md`（中文主）+ `README.en.md`（只 quickstart 与结果两节）。
> **仍未达成的一条**：①证明的是"零手工建库"，不是"陌生人 clone 后 10 分钟跑通"——后者要找真人测，目前只有我这台机器的时间线（首次在线拉依赖另计）。README 没承诺陌生人体感，别在别处替它承诺。
> **①在 2026-10-01 被真人炸出一个洞（W3-h）**：他照 README 在 cmd.exe 里跑，启动即 `DEEPSEEK_API_KEY 未设置`。根因是 quickstart 第 2 步 `cp .env.example .env` **没有任何代码读它** —— Spring Boot 不自动读 `.env`，而 `application.yml` 只写 `${DEEPSEEK_API_KEY:}`。我此前的①验证全程在一个已经拿到 key 的环境里做（要么导出过、要么 `.env` 恰好被别的方式带上），所以从没碰到这条路径：**"我这台机器能起"和"照 README 能起"是两件事**。修法与断言见 §10 W3-h。这条也说明"要找真人测"不是形式 —— 第一次真人测就中了一个。

## 3. 技术基线（已核实，别凭印象改）

| 项 | 值 | 说明 |
|---|---|---|
| JDK | **21**（框架基线是 17，无模块要求 21） | AgentScope parent/BOM `java.version=17` |
| 构建 | Maven **3.9+** | quickstart 要求 |
| Spring Boot | **4.0.4**（由**我们**的 pom 决定，不是被锁死） | ⚠️ **2026-09-30 实测修正原判断**：starter 的 POM 里**没有** `dependencyManagement`/`import spring-boot-dependencies`，只有一个 `<optional>true</optional>` 的 `spring-boot-autoconfigure`（base 4.0.1 / openai 4.0.3）——optional 依赖不传递，所以 Boot 版本完全由消费方决定。留 4.0.4 的理由改成：**≥ 框架编译期的 4.0.3 且同在 4.0.x 补丁线**（4.0.8/4.1.1 也在镜像上，故意不追新，减少变量）。已实测 4.0.4 能起 |
| Web 栈 | **WebFlux** | AG-UI starter 把 `-web`/`-webflux` 都声明成 optional，需自己加；官方 SSE 示例用的就是 webflux |
| AgentScope | **2.0.3**，三周不升级 | 唯一例外见 §10 Day-0 之后的"版本窗口" |
| 数据库 | SQLite 单文件（JDBC） | 见 §5 |
| 模型 | DeepSeek 官方 `deepseek-flash`，思考档默认关 | 个人账户 |
| 分支 | `main`（当前是 `master`，零提交，改名免费） | |

**依赖坐标（全部在 Maven Central 核实存在于 2.0.3）**

```
io.agentscope:agentscope-spring-boot-starter:2.0.3
io.agentscope:agentscope-extensions-agui:2.0.3
io.agentscope:agentscope-agui-spring-boot-starter:2.0.3
io.agentscope:agentscope-extensions-model-openai:2.0.3   ← 无独立 deepseek 模块，但**里面有 DeepSeek 适配层**（见下）
org.springframework.boot:spring-boot-starter-webflux
com.github.jsqlparser:jsqlparser                          ← 版本 Day-0 定
org.xerial:sqlite-jdbc                                    ← 必须够新，窗口函数要 SQLite 3.25+
```

**配置面（已核实）**
- SPI 路径：环境变量 `DEEPSEEK_API_KEY`，provider key `deepseek`，base 自动 `https://api.deepseek.com`；`DeepSeekModelProvider` **不给默认模型名**（剥掉 `deepseek:` 前缀原样转发）。
- **2026-09-30 反编译补全（决定 W1 用哪条路）**：`agentscope-extensions-model-openai` 内嵌 `compat/deepseek/DeepSeekModelProvider`（`META-INF/services` 注册，`providerId=deepseek`，匹配 `deepseek:.+`）。`ModelRegistry.resolve("deepseek:deepseek-flash", ctx)` 会自动做四件 starter 路径**不做**的事：① 装 `DeepSeekFormatter`（处理"末条是 assistant 时补空 user"这类 DeepSeek 特有请求修正——多轮追问 5 题会踩）；② `nativeStructuredOutput(false)`（与 Day-0 实测的 `json_schema` 被拒一致）；③ 按 `ctx.enableThinking` 发 `{"thinking":{"type":"enabled|disabled"}}`，这就是 §4"思考档默认关"的现成开关；④ key 缺省回落读 `DEEPSEEK_API_KEY`。
  ⚠️ 代价：`ModelContextWindows.DEEPSEEK` 只登记了 `deepseek-v4-flash` / `deepseek-v4-pro`（都是**旧命名**），**没有当前唯一的 flash 正式名 `deepseek-flash`** → 实测 `model.getContextWindowSize()` 返回 **0**（2026-10-01 离线复测，`agent/DeepSeekModelIdFactsTest`）。
  **0 的语义已查清（回答上一版的待查项）**：扫过全部 agentscope jar 的字节码，`getContextWindowSize()` 的调用方只有 `Model` 接口默认方法、`ChatModelBase`、`ReActAgent$2`（委托包装）和 `extensions/aistio` 的 `AgentScopeAdapter` 四处，**内核没有任何按窗口裁剪/压缩的路径读它** → 2.0.3 上 0 是纯展示值（进启动日志），不影响我们的请求。危害在下游与将来：谁按它做上下文预算，谁就把 1M 的模型当成 0。附带一个方向相反的事实：**`deepseek-v4-flash` 在 DeepSeek 官方仍被接受**（HTTP 200，服务端按 `deepseek-flash` 处理，见 §10 W3-d）——所以"名字对不上"这件事是**正确名拿 0、旧别名拿 1000000**，不是"正确名会报错"。
  **结论（W1 第一个动作）**：Model bean 走 SPI（`ModelRegistry.resolve("deepseek:" + modelId, ctx{enableThinking=false, stream=…})`），openai starter 那条路用 `agentscope.openai.enabled: false` 关掉；`agentscope-spring-boot-starter` 仍然保留（agent/toolkit/memory 三个 bean 靠它，且 agent 是 `@ConditionalOnBean(Model)`，我们自己提供的 Model 满足条件）。骨架当时用的是 starter 路径，**是错的默认值 —— 2026-09-30 W1-a 已改掉，见 §10 W1 进度**。
- Starter 路径：`agentscope.model.provider: openai` + `agentscope.openai.{enabled,api-key,model-name,base-url,endpoint-path,stream}`。
  ⚠️ **`model-name` 的默认值是 `gpt-4.1-mini`** → §9 的启动 fail-fast 专门挡它。
  ⚠️ 该 bean 只在 `provider=openai`（字符串精确匹配）时创建；`agentscope.agent.enabled=true` **必须显式写**（`@ConditionalOnProperty` 无 `matchIfMissing`），忘写则 agent bean 静默不存在——§10 门禁④ 的断言就是专门抓这个的。
- `/v1` 无需纠结：客户端会对重复的 `/v1` 去重。
- AG-UI：前缀 `agentscope.agui`，端点 `POST /agui/run`（+ `/run/{agentId}`）；关键项 `path-prefix`、`run-timeout`（**默认 10 分钟，本项目必须调大**，理由见 §6）、`emit-tool-call-args`、`enable-reasoning`、`server-side-memory`（**本项目关掉**，见 §8）。

## 4. 主架构：一次连续 run + 工具内挂起确认（决定 D）

```
用户提问（前端 SSE）
  └─ HarnessAgent / ReActAgent 单次 run
       └─ 唯一工具 run_sql(sql)
            ├─ 护栏链：解析 → 表白名单 → 注入 LIMIT → 只读连接
            ├─ 护栏不过 → 立刻返回结构化拒绝理由（不打扰人）
            └─ 护栏过 → 【挂起】把 SQL 推给前端，等 POST 决策
                         ├─ approved  → 执行，返回表格
                         ├─ edited    → 用整条替换后的 SQL 执行（人的编辑即权威，不再回炉模型）
                         ├─ denied    → 带理由回给模型，允许重试 1 次
                         └─ 超时未决  → 视为 denied，明确报错（不假装成功）
       └─ 模型总结 → 表格 + SQL 原文 + 一句总结 + 一行口径说明
```

为什么是 D 而不是两段式（C）或框架原生 resume（F）：
- **F 被一个已复现的缺陷挡在门外**：#3096 —— **但丢的是给前端的事件流，不是给模型的上下文**（这个差别是 2026-10-01 的 throwaway 实验测出来的，见 `docs/hitl-pause-resume-experiment.md`；此前本节把它写成"批准的运行执行完工具后丢掉结果"，是转述失真，已纠）。2.0.3 上批准后工具会执行、结果会进第二次模型调用，可 `TOOL_CALL_END`/`TOOL_CALL_RESULT` 一条都不发（`AguiStreamContext` 每 run 新建，结果事件被 `startedToolCalls` 门禁吃掉）。我们的演示页按事件流渲染，所以这条仍然足以否掉 F。修复 PR #3100 在 2026-09-11 合入 main，**至今未发版**（`v2.0.3` 仍是最新 tag）；#3104（被拒的调用不吐 tool-result）同样未发版。加上 #3315/#3294/#3320/#2773 四个仍 open。D 完全不进 resume 这条路径。
- **实验的另一笔收益**：框架的 AG-UI resume 原生支持 `editedArgs`（整体替换工具入参，实测工具与模型看到的都是改后那条），也就是"人改写 SQL"这件事框架本来就有对等实现 —— 我们自己在确认门里做的那份不是必需品，等 #3100 发版后迁移有依据。
- **C 会掏空学习目标①**：schema 全量进 prompt 之后，两段式的第一段不需要调任何工具，"agent"退化成一发带 JSON 约束的调用加 Java 胶水。上一轮为了补救曾打算加 `list_tables`/`describe_table` 强行制造调用需求 —— 那是为架构自洽而花 token 的假需求，D 让它没必要存在。
- 代价已知并接受：D 走的是 WebFlux 上最难的一条线（等人类点击的长挂起流）。需要独立线程池 / `Sinks` 之类的机制，**SQL 执行与等人都不许跑在事件循环线程上**；`run-timeout` 必须调大。响应式是你（Q43=②"见过但没写过"）为此多学的一样东西，不在 §1 三项验收里，账记在 §11。

**工具集只有一个**：`run_sql`。**schema 不进工具、全量进 prompt**（1M 上下文面前"装不下"不存在），这样模型答错的原因能收敛到"没读懂 DDL"或"护栏/prompt 有问题"两类，而不是"它没去查表"。

**步数上限 = 4**：用你的真实例子校准（§12 那道题，3 条 SQL + 1 次校验 = 4）。

**形状约束**（Day-0 已实测，2026-09-30）：DeepSeek 官方 **不支持** `response_format: json_schema` —— 请求直接 400 `This response_format type is unavailable now`。#2548 不是"网关偶发"，是 provider 能力缺失，所以**走退化路径为唯一路径**：prompt 钉死 JSON + Jackson 严格解析 + 重试 1 次，且**"形状不对"必须是独立于"没调工具"的失败类别**。实测 `response_format:{type:"json_object"}` 可用（200），但它只保证是合法 JSON、不保证字段形状，因此 Jackson 的 schema 校验不能省。

**provider 切换的纪律**：代码里不出现 `tool_choice:"required"`。**理由已更正**（Day-0 实测）：DeepSeek 在**思考档开着**时拒绝它（400 `Thinking mode does not support this tool_choice`），但 `thinking:{type:"disabled"}` + `required` 是**能用的**（200）。不用它的真实理由与 provider 无关 —— `required` 会连"给出最终答复"那一轮也强制调工具，Agent 收不了尾。换模型只改注册串 + 环境变量，不许散落硬编码。

**D 的落地实测（2026-09-30，`tool/RunSqlToolTest` 9 条 + `AgentBeanRegistrationTest` 2 条，全绿、不联网）**

- ⚠️ **`Toolkit.callTool` 校验的是 `ToolUseBlock.content`，不是 `input`**：`ToolExecutor` 把 `toolUseBlock.getContent()`（模型侧的原始 arguments JSON）交给 `ToolValidator`（networknt JSON Schema）跑，工具只要有参数 schema 就**必须**填 content；只填 input 得到 `Parameter validation failed for tool 'run_sql': Schema validation error: argument "content" is null`。测试里手写 `ToolUseBlock` 时这是最容易漏的一个字段，`input` 只是框架解析后给你的 map。
- ⚠️ **返回值默认被 JSON 序列化一遍**：`DefaultToolResultConverter.serialize()` 无条件 `JsonUtils.getJsonCodec().toJson(result)`，所以 `Mono<String>` 到了模型/前端眼里是 `""护栏拒绝：...\n..."`（多一层引号、换行成字面量）。`@Tool(converter = PlainTextResultConverter.class)` 换掉即可（转换器由 `getDeclaredConstructor().newInstance()` 实例化 → **必须是 public 无参构造**）。这不是美化：这段文本要进 §7 的失败归因、也要人读。
- **给人看的 SQL 是护栏改写后的那条**，不是模型原文 —— 实测 `login_dt='2026-01-31'` 到了确认面板变成 `login_dt = '2026-01-31' ... LIMIT 200`（解析器重排空格 + 补 LIMIT）。README 要写明"展示的 SQL 可能与模型生成的不完全一致"，否则第一次点确认的人会以为人在审模型的原话。
- **人的编辑再进一次护栏**（`Decision.edit("drop table acct")` → 拒，且不执行）。这条是 §6"人是责任归属、不是安全机制"的直接推论，不是框架要求。
- **超时=拒绝**，`doFinally` 清理挂起条目，事后 `decide()` 返回 false（前端拿到 410）；同一条决策点两次，第二次也 false。
- ✅ **人改过 SQL 之后，模型的答复会抄回它自己的原文，不是执行的那条**（2026-10-01 浏览器实测：面板里把 `login_cnt > 3` 手改成 `> 5`，工具返回明确写着 `执行 SQL: ... login_cnt > 5 ... LIMIT 200`，返回 8；但模型正文里的"## 实际执行的 SQL"代码块仍是 `> 3`）。**修法选了 prompt 硬规则**（§6 立场：模型负责解释口径，但数字与语句的来源必须是执行侧），另一条路"前端以工具事件为准渲染、不信模型散文"没走。
  > **修复实测（2026-10-01，`tools/edit_echo_check.py`，端口 18084、真 key、`human` 模式）**：同一道题、同样把 `> 3` 改成 `> 5`，答复的代码块现在是改写后那条，且模型在口径行主动写明"我提交的条件是 > 3，工具返回显示实际执行被改写成 > 5，请确认你要的口径"。这条脚本**只能手动跑**（要打真实模型和真实确认门），prompt 侧的锚点断言进了 `SystemPromptTest.answersMustQuoteTheSqlThatActuallyRan`。
  > **注意这个缺陷不可能被 `--eval` 抓到**：评测跑在 `auto_approve` 下，没有"人编辑"这一步，所以重跑评测只能证"新加的规则没有把准确率拖下去"，不能证"缺陷已修"——两件事分别由上面两条证据承担。
- **`game.confirm.mode=auto_approve` 是 §7 评测的接缝**：25 道题不可能每题手点一次，`--eval` 必须能整条链路跑完而无人值守；`ConfirmationGate` 在该模式下不入 pending 表，`AgentBeanRegistrationTest` 用默认 `human` 模式验挂起，`RunSqlToolTest` 两种模式都验。
- **页面实测三条处置路径（2026-10-01）**：同意 → 工具结果与表格数字进答复（61）；拒绝 → 工具返回 `人拒绝执行这条 SQL：…`，模型回答"没有拿到任何数字，也不会去猜"，并且自己指出 `2026-02-01` 不在数据覆盖范围内；护栏拒绝 → **不出确认卡片**（护栏在门之前，`SqlGuard` 拒了就不会挂起等人），页面上直接是 `护栏拒绝：引用了数据库系统对象（sqlite_master）…`。⚠️ 顺带一条不好看的实情：让它写 `UPDATE` 时模型在 prompt 层就直接拒了，压根没到护栏 —— 所以"护栏挡住 DML"这条必须由 `sqlite_master`/`SqlGuardTest` 这类真会下发的语句来证，不能拿"模型拒绝了"当护栏的功劳。

**schema 注入已落地（2026-09-30 W2-a，`agent/SystemPrompt` + `agent/AgentConfig`）**

prompt 由"运行时的库"拼出来，不是抄一份 `schema.sql`：`GameDatabase.schemaDdl()` 从 `sqlite_master` 取建表语句，实测 SQLite **把 `--` 列注释原样存着**（`CREATE TABLE ...` 里那 3081 字符就是全库口径线索），所以"库里真正生效的定义"与"prompt 里的定义"不可能各说一套。表级注释（`CREATE TABLE` 之前的那行说明）**不会**进 `sqlite_master` —— 所以"本表没有 is_del"这类话必须由 prompt 正文承担，这条已经在 `SystemPrompt` 的硬规则里。**正反两半都由测试钉住，别信这份文档的措辞：`GameDatabaseTest.schemaDdlCarriesTheColumnCommentsThatEncodeCalibers`（列注释在）与 `tableLevelCommentsAreNotStoredAndThereforeMustLiveInThePrompt`（表级注释不在）。**实测规模：**整份 prompt 4027 字符 / 其中 schema 3081**（`AgentConfig` 启动日志会打这两个数，§7 的成本账以此为基准）。

三条只有读字节码才发现的框架事实，都影响后面的实现：
- ⚠️ **`ReActAgent.Builder.build()` 会 `toolkit.copy()`** —— agent 用的是副本，不是 Spring 那个 bean 本体。所以"agent 建好之后再往 bean 注册工具"模型永远看不见；要加工具必须在 `ToolkitConfig` 里加。副本实测只含 `run_sql`（`registerMetaTool()` 没塞进东西）。
- ⚠️ **2.0.3 的 `ReActAgent` 没有任何 memory 入口**：builder 无 `memory(...)`，整个类不引用 `io.agentscope.core.memory.*`，连 starter 的 `agentscopeReActAgent(Model, Memory, Toolkit, props)` 里那个 `Memory` 参数都没被方法体用到 —— starter 建的 `InMemoryMemory` bean 是 1.x 遗留的死重量。**这等于替 §8 做了决定**：自存 `{lastQuestion,lastSql,口径}` 不是"绕开框架更可控"，而是框架根本没给。
- **`agentscope.agent.enabled` 现在必须是 `false`**：agent 与 toolkit 两个 bean 都由我们自建（`@ConditionalOnMissingBean` 会让位），把 starter 那份 `sys-prompt` 留在 yml 里只会误导。`game.agent.{name,max-iters,today}` 是我们的那份配置，`max-iters` 同时进 builder 和 prompt 正文（两处不许漂移），`today` 绑成 `LocalDate` —— 格式写错就让启动失败，而不是让 25 道相对时间题一起静默漂移。
- 随之做的一个**派生决定（可推翻）**：`run_sql` 的 `@Tool description` 里原本抄了一遍口径（净付费、哪些表没 is_del、两种日期格式），现在删成只剩"单条 SELECT + 权威见 DDL"。**理由**：同一规则两处可写就会两处漂移，且漂移时无法归因（是 prompt 没用还是工具说明没用）；代价是第一次评测可能因为"规则只在 prompt 里"而变差 —— 那正是我们想要的信号，别提前用重复去掩盖。

**评测跑手的前置实测（2026-10-01 W2-b，一次性探活 `AgentProbeTest`，结论落定后该类已删）**

- ⚠️ **`AgentState.context` 会跨 `call()` 累积**：同一实例第 1 轮后 4 条消息（`USER` / `ASSISTANT+ToolUseBlock` / `TOOL+ToolResultBlock` / `ASSISTANT+TextBlock`），第 2 轮后 8 条。**直接 consequence：`--eval` 不能共用那个 Spring 单例** —— 25 道题排队跑的话，第 20 题的上下文里装着前 19 题的 SQL 和数字，那个准确率是抄来的。所以 `AgentFactory` 每题 `create()` 一个新实例（探活实测第 2 轮"那1月30号呢？"在同一实例上能自己解析成 `login_dt = '2026-01-30'` 并答 109，说明**追问题的指代继承本来就靠这个累积**，每题一个新实例正好同时满足两件事：单轮题干净、多轮题在同一实例上连着问）。
- **`auto_approve` 下 `pendingSnapshot()` 全程为 0**：无人值守这条链不会挂起。
- ⚠️ **`Toolkit.callTool()` 直接返回的 `ToolResultBlock` 里 `id` 与 `name` 都是 `null`**（`EvalTraceTest.resultBlocksDoNotCarryTheCallId` 钉住）⇒ 想把结果对回那次调用只能按出现顺序，别依赖 id。
- ⚠️ **`-Dspring-boot.run.arguments="--a,--b,--c"` 的逗号不会被拆开**（实测三条里只有第一条生效，`--server.port=18081` 没进去、进程仍然占 8080 起不来）。跑评测用：程序参数只给 `--eval`，其余走 `-Dspring-boot.run.jvmArguments="-Dserver.port=18082 -Dgame.confirm.mode=auto_approve"`。

## 5. 数据层

- **自造游戏域 6–8 张表**，不用 Sakila/Chinook/Northwind：公开样例库的 schema 太出名，模型是在背答案而不是读 DDL，评测分会虚高。
- 命名带国内真实库的脏味（`srv_id`、`role_no`、`is_del`、`pay_amt`）+ **少量中文列注释** —— 注释是模型唯一能拿到"口径线索"的地方，也是最容易答错的地点。
- **`schema.sql` + seed 数据进 git，`.db` 文件 `.gitignore`**，首次启动生成；提供 `--reset-db`。评测前强制重置，否则分数会随调试状态漂移。
- **"今天"作为参数注入** prompt 与 SQL 校验。凡是"上周/本月/次留"的题都锚定固定日期，否则跨年后准确率自己掉。
- ⚠️ 隐私红线：样例数据行**会进 prompt**（进而进模型 API）。seed 里不许出现任何真实客户名、手机号、金额。
- ⚠️ 技术前置：窗口函数需要 SQLite 3.25+，取决于 `sqlite-jdbc` 版本 —— Day-0 验一次 `SELECT row_number() OVER ()` 能不能跑。**已验（3.53.4.0）。**

**实测（2026-09-30，`db/GameDatabaseTest` + `db/SqlScriptTest`，12 条离线用例全绿）**

建库路径已从"要装 sqlite3 CLI"改成纯 JDBC，四条只有跑过才知道的事：

- ⚠️ **整文件喂 `Statement.execute()` 会炸**：`SQLITE_MAX_SQL_LENGTH` 默认 1,000,000 字节，`seed_data.sql` 是 1,015,282 字节 → `SQLITE_TOOBIG: statement too long`。必须有 `SqlScript.split()`（状态机：`''` 转义、`--` 行注释、`/* */` 块注释、双引号标识符里的分号都不算边界）。**这条不是理论风险，是第一版实现直接踩到的。**
- ✅ **JDBC 建出来的库和 CLI 建的逐题同值**：262 条语句 / 约 1.3–2.4 秒，25 道题的 `truthSql` 全部复现出 `expect.value`（`GameDatabaseTest.reproducesEveryEvalExpectation` 就是 CI 版的 `verify_eval.py check`）。**含义：clone 者不需要 sqlite3、不需要 Python，光靠 Java + 仓库内容就能拿到同一份 ground truth** —— §2 的"10 分钟跑通"从假设变成有测试兜着的断言。
- 只读连接：`jdbc:sqlite:<正斜杠绝对路径>?open_mode=1` → `isReadOnly()=true`，写操作报 `SQLITE_READONLY`；**文件不存在时直接 `SQLITE_CANTOPEN`，不会凭空建库**。但 `open_mode` 写成别的值（试了 2945）会静默建出空库 → 所以建库后强制 `verifyTables()`，错误在启动期就报成"路径指到别处了"，而不是等第一次查询。
- ⚠️ **只读连接的 URL 上不许挂 pragma**：`?query_timeout=5000` 报 `CANTOPEN`、`?journal_mode=wal` 报 `SQLITE_READONLY`（pragma 是写操作）。超时只能走 `Statement.setQueryTimeout()`，实测可用。
- Jackson 双大版本落到代码层：Boot 4 自带 `tools.jackson` 3.1.0（`JsonNode.asString()` 而非 `asText()`），框架用 `com.fasterxml` 2.21.1。**自己文件的解析用 `tools.jackson`；将来 `run_sql` 的工具 schema / 消息序列化必须 import `com.fasterxml` 才和框架对齐。**


## 6. 护栏链（`onActing` 中间件，线性四步）

1. **JSqlParser 解析** → 判定：是否单条语句、是否为 SELECT、有无 DDL/DML、有无 `PRAGMA`/`ATTACH`/系统表。**禁止 `startsWith("SELECT")` 之类正则**（注释前缀、`WITH` CTE、`;DELETE` 多语句都能绕过）。　✅ `guard/SqlGuard`
2. **表白名单** → 只允许 §5 那 6–8 张表。清单在 `SqlGuard.ALLOWED_TABLES`，对照源是 `GameDatabase.TABLES`。　✅
3. **注入 LIMIT** → 未写 LIMIT 的强制加；模型自带更大的 LIMIT 会被压回上限。上限走 `game.guard.max-rows`（`guard/GuardProperties`+`GuardConfig`，`SqlGuard` 本身不带注解、保持可离线测）。　✅
4. **只读连接执行** → SQLite 以只读模式打开 + 查询超时。　✅ `db/GameDatabase.query`（`open_mode=1` + `setQueryTimeout`）

失败返回**结构化拒绝理由**，这个理由直接进评测归因（§7）和前端的"为什么被拦"提示。

> **SQLite 没有只读账号**（MySQL/PG 的 `GRANT SELECT` 在这里不存在）。这是 §3 选 SQLite 换来的代价，第 1、2 两步因此不是锦上添花而是主力。
> **人确认是责任归属机制，不是安全机制** —— 安全必须来自不依赖人注意力的 1–4 步。这个区分本身就是学习目标②的内容，README 要写。

**实测（2026-09-30，JSqlParser 5.4 + sqlite-jdbc 3.53.4.0，`SqlGuardTest` 29 条用例全绿）**

- ⚠️ **最重要的一条：`parse("select 1; delete from acct")` 不报错，只吐出第一条 `PlainSelect`**，第二条静静留在原文里。所以"检测分号"是错的机制，正确机制是**执行文本一律取 AST 重新序列化的 `statement.toString()`**（实测输出 `SELECT 1 LIMIT 200`，delete 消失）。`GuardOutcome.sql()` 的 javadoc 已经把这条钉住 —— 下游 `run_sql` 若图省事用模型原文，护栏等于没有。
- 注释前缀 `-- x\nselect ...`、`WITH` CTE、子查询里的表、`main.` 库名前缀，四种绕过全部按预期处理：CTE 别名**不出现**在 `getTables()` 里（因此白名单不用为 WITH 开例外），子查询里的未授权表**会出现**，`main.acct` 归一化后放行、`main.sqlite_master` 仍拦。
- 双引号标识符 `from "users"` 会被识别成表（拦）；**方括号 `from [acct]` 默认方言解析失败**（拒）—— 后者是 fail-closed，不是漏放，但要知道 SQLite 合法而本护栏不放过。
- `PRAGMA` / `ATTACH` / `VACUUM` 全在第一掉：解析器不认 SQLite 方言。**含义是"解析失败即拒绝"这条规则承担了对 SQLite 特有攻击面的覆盖**，而不是第 1 步之外的某一步。
- LIMIT：`Select` 基类持有 limit，union/CTE 上注入位置正确（挂整条语句末尾）；模型自带的小 LIMIT 保留，`limit 1000000` 压回上限。
- B05/B06/B10 三道真实评测题的 `truthSql`（相关子查询 + `date(...,'+1 day')` + 两段 JOIN 赛季边界 + `order by sum() desc limit 1`）和 `lag() over` 都能过护栏，不会出现"题没错、被护栏拦死"的假归因。

## 7. 评测集

- **25 题：你写 15 道（真实会问的），我生成 10 道对抗题**（歧义命名、两跳 JOIN、口径含糊）。全由你写会因"你知道答案所以问得清楚"而测不出歧义处理；全由我生成会落在模型舒适区。
  - **进度（2026-09-30 收盘）**：10 道对抗 → `src/main/resources/eval/adversarial.json`；**15 道业务题改由我代笔**（你当天说"直接生成"，见 §13 的代价与三次否决）→ `src/main/resources/eval/business.json`，`owner: qoder`。期望值一律由 `truthSql` 在 `data/game.db` 上真跑得到，`tools/verify_eval.py check` 一条命令复算 25/25。**评测集齐了，不再阻塞写码。**
  - **分层实际落点**：单表 6 / 多表 JOIN 8 / 聚合分组 5 + A10（对抗，本质是聚合）= 6 / **追问 5**（全在 business 的 T01–T05，断言的是第 2 轮答案，专测 §8 状态传递）。
  - **留存类实际 8 道**（B01/B02/B04/B05/B07/B10/T03/T05，比 §7 说的 5–6 多）：`business.json` 的 `retentionIds` 是权威清单，跑分时它们单独统计，失败不算项目失败。
  - **代笔过程中被实测否掉的三个"假区分度"**（写在这里是因为它们会随数据重生成而变味，`ifTrapMissed` 里已各自标注）：① B07 加"1月19号前注册"的老账号条件得数不变（100 → 100），这批人本来就全是窗口前注册的；② T05 的 `<=2025-12-01` 与 `<` 同值（12月1号无人注册）；③ T02 把"付过费"理解成"有成功订单"竟同为 53.33% —— `first_pay_dt` 与订单表完全一致，所以这题只考 NULL 处理与指代继承。另外 B06 的"第一赛季"边界正好等于 11+12 两个自然月，不 JOIN `season` 也会碰巧对，它实际只卡区服名。
- **先写题，再写第一行 agent 代码。** 顺序反过来你会不自觉按实现能力挑题，那个 75% 是自制的。
- **只断言返回的数字/行数，绝不断言 SQL 字符串**（等价写法无穷多）。
- **金额类期望值一律用容差比对（±0.01），不许精确相等**；标准答案 SQL 统一 `ROUND(SUM(pay_amt),2)`。这是 §12 把金额从"分 INTEGER"换成"元 REAL"之后必须补上的一条：浮点求和的尾差会让你把一道**答对的题判成错**，而这类误判不会报错、只会让你的准确率莫名偏低 3–4 个点，然后你会去调 prompt。
- **每题必须自带口径声明**，作为期望值的一部分。没有口径声明的题不许进评测集 —— 否则"正确答案"不唯一。（这条由你那道"付费掉最多"的题逼出来的：掉最多是绝对额还是百分比？上周是自然周还是滚动 7 天？新玩家是注册当日还是 7 日内？）
- 难度分层：单表 6 / 多表 JOIN 8 / 聚合分组 6 / **带指代的追问 5**（最后 5 题专测 §8 的状态传递）。
- **留存类 5–6 道**（次留/DAU/付费转化），失败不算项目失败 —— 它们是本项目最容易为凑分而过拟合 prompt 的地方。
- **失败归因四类，分开统计**：`未调用工具` / `护栏拒绝` / `执行成功但数字错` / `人拒绝`。（形状非法若走 §4 退化路径则并第 1 类）
- 回归集 **cap = 60**，满了按"已稳定通过 ≥5 次"淘汰最老的，**淘汰前把题目和它暴露的失败模式写进 README 的"已知难点"**。
- 运行形态：`--eval` 命令行模式，**绝不进 `mvn test`**（要打真实外部 API）。结果 JSON **提交进仓库**，README 的说服力靠这条曲线。
- **CI 不放 API key**：Actions 只跑离线护栏单测（固定 SQL 输入断言拒/放）+ 评测脚本自身的测试。真实评测本地手动跑。
- **thinking 对照实验**：默认非思考档钉死不变；Day-0 实测思考档会不会撞 #3299/#3209（思考 + 多轮在 OpenAI 兼容路径上 400）。能跑就跑第二组，README 报两个数；400 就把对照变量换成"给不给口径示例"。

**`--eval` 跑手已落地 + 首轮真实准确率（2026-10-01 W2-b）**

跑手四件：`eval/EvalSet`（读题，25 题的结构约束由 `EvalSetTest` 钉）、`eval/EvalTrace`（从上下文抽"调了哪条 SQL、工具回了什么"）、`eval/EvalRunner`（每题一个新实例、串行、归因分桶）、`eval/EvalCommand`（`--eval` 才跑，写 UTF-8 JSON 到 `eval-results/`）。归因分桶实测：**三轮里"未调工具 / 护栏拒绝 / 执行报错 / 人拒绝"全为 0，失败全是"跑出来了但数不对"** —— 护栏与工具链不是瓶颈，口径理解才是。

判分规则被实测纠了两次，两次都是**评分器把对的判成错的**：
1. 第一版"只比最后一次成功 `run_sql` 结果的**首格**" → B10 的 `select srv_id, round(sum(dur_sec)/3600.0,2) ... limit 1` 期望数在**第二列**，答复 2980.41 明明对，被判成"got=105"（区服号）；T01 同形（1000.00 对，被判 101）。规则改成**首行的任一数值单元格**。仍然只取**首行**是刻意的收紧：按天分组那张表里没有"总数"，本该判错，不许它在第二行撞对。
2. 另加一条宽松口径 `accuracyCountingAnswerText`：B05 次留率**三轮都"错"**，因为模型分两步查（注册数、次日登录数），最后 `27 / 40 = 67.50%` 是用文字做的除法，商永远不在单元格里。这种题严格口径判它错等于用评分器的格式判模型错，所以两个数一起报，**README 只许两个都写，不许挑好看的那个**。

三轮真实数字（同 prompt、同库、同模型，唯一变量是模型采样）：

| 结果文件 | 严格 | 宽松 | 判错的题 |
|---|---|---|---|
| `archive/eval-20261001-102121-first-cell-rule.json` | 84% (21/25) | 92% | A06、B07 + **评分器误判** B10、T01（旧 first-cell 规则，已挪出 `eval-results/` 根目录，画曲线时不要用它） |
| `eval-20261001-102834` | 88% (22/25) | 92% | B03、B05、B07 |
| `eval-20261001-103058` | 88% (22/25) | 92% | B04、B05、B07 |

> **第四轮不进这张表**：`eval-20261001-113017` 用的是**加了"逐字抄执行 SQL"硬规则的新 prompt**（4171 字符 vs 4027，§4），变量不同，不能和上面三行画在同一条曲线上。它的数字是严格 84% (21/25) / 宽松 88.0% (22/25)，判错：A03、B03、B05、B07。
> **读法（就按上面那条纪律读）**：相对 run2/3 翻脸的题是 **A03 ↓、B04 ↑**，B03/B05/B07 三道原地不动。一题之差 = ±4 个点，正在前三轮自己抖出来的噪声里，所以这一轮**既不能写成"prompt 改坏了"，也不需要写成"没改坏"** —— 它唯一能证明的是：新增那条规则没有把 25 题的取数行为整体推倒。而它真正要修的那个缺陷（人编辑后答复抄原文）在 `auto_approve` 下**根本不会被触发**，评测对这项修复既证不了真也证不了伪，证据在 §4 的 `tools/edit_echo_check.py`。
> **顺带揪出评分器第三个 bug**：`accuracyCountingAnswerText` 原先把"靠文字救回来"的题**同时当分子和分母**，于是第四轮的 21/25 被它写成 `95.5% (21/22)` —— 分数虚高，而且分母含义一变就不叫"同一套题的两个口径"了。修后分母恒为 25，第四轮宽松口径 88.0% (22/25)；上面三行的 92% 是按同一算法手算的（它们的 JSON 里还没有这个字段）。算术由 `EvalCommandSummaryTest` 三条离线用例钉住，不再靠肉眼。

> **结论：首轮真实准确率 88%（严格）/ 92%（宽松），§2 的 75% 门槛达成。但单轮数字不可比** —— 三轮里会翻脸的题是 A06 / B03 / B04 / B05 这四道，稳定失败的只有 B07 一道。以后任何"我改好了，涨了几个点"的说法，必须先证明它不是这 ±4 个点的采样噪声（做法：同一份 prompt 连跑 3 轮，比翻脸集合，别比总分）。

逐题归因（记在这里，**不许现在就回去改 prompt 凑分**）：
- **B07 三轮全错，而且三种错法**：① 把时间窗口套在 `reg_time` 上（题面"上周…注册的账号里"字面就是这个意思，而口径声明说的是登录窗口）；② 年份写成 2025，查到 0 之后自己发现年份错了、停下来反问而不重跑；③ 第 3 轮跑了 4 步答 5。**这是 §7"代笔题面与自身口径打架"的第一个实证**：题面与口径冲突时模型跟题面、判分跟口径，那道题的 75% 门槛下没人能稳过。修法在题目侧（改成"1月19到25号登录过的联运渠道账号有多少个"），但**本轮不改题再跑** —— 看见结果之后改题面是挪门柱。
- **A06 翻脸**：run1 加 `is_del=0` 得 294，run2/3 不加得 313。prompt 只说了"role 有 is_del 这一列"，没说"角色计数默认要不要排除已删号"—— §12 那条坑没有默认口径，模型就自己掷骰子。
- **B03 命中 `ifTrapMissed` 预言的 65**（跨服 `SUM(login_cnt)` 合并后再判 >3）。对抗题的设计有效，这条要写进 README 当卖点。
- **B04**：run3 日均 DAU 答成 39.0，run1/2 对。聚合语义不稳，与 A06 同属"口径默认值缺失"一类。
- **A03（第四轮新错，换 prompt 之后）**：`substr(f.create_time,1,10) >= '2025-01-19'` —— **年份写成 2025**，窗口从一周放大到约一年，答 12881 而期望 1561。与 B07 的第②种错法同源（年份），区别是这次模型没自查出来。prompt 里"今天 = 2026-01-31、库内数据覆盖 2025-11-01 至今天"这条锚点，对"上周"这类相对窗口的约束显然不够硬。**本轮不动 prompt 去治它**：同一轮里既加规则又追分就是挪门柱，而且它是上面说的 ±4 个点噪声的候选。

复跑命令（key 只在环境变量里；控制台是 ASCII，中文在 GBK 控制台下会打烂，看 JSON 别看日志）：
```
export JAVA_HOME=<你的 21> && set -a && . ./.env && set +a
D:/tools/apache-maven-3.9.16/bin/mvn -o -B spring-boot:run \
  -Dspring-boot.run.arguments=--eval \
  -Dspring-boot.run.jvmArguments="-Dserver.port=18082 -Dgame.confirm.mode=auto_approve"
```
`--eval` 在 `game.confirm.mode != auto_approve` 时**拒绝启动**（人确认模式下 25 道题会各自挂起等点击），不是静默等超时。

## 8. 会话状态

**2026-10-01 改：用框架的 threadId 会话，"自存 `{lastQuestion, lastSql, 口径}`"作废。** 配置 `agentscope.agui.server-side-memory=true`，`AgentConfig` 向 AG-UI 注册表交一个 **factory**（`registry.registerFactory("default", factory::create)`），每个 threadId 由框架造一个 agent 实例并复用。

三条只有读 2.0.3 字节码才知道的事实（都已转成 `AguiEndpointTest` / `AgentBeanRegistrationTest` 的断言，不靠本文措辞）：

- `DefaultAgentResolver.resolveAgent(agentId, threadId, userId)` → `ThreadSessionManager.getOrCreateAgent(userId, threadId, agentId, factory)`：session key 是 **(userId, threadId)**，value 是**整个 agent 实例**。`threadSessionIsolatesThreadsAndReusesWithinOne` 钉住两面：同 threadId 两次拿到同一实例（多轮指代继承的前提），换 threadId 就是新实例（浏览器之间不串）。
- `AguiAgentRegistry.getAgent(id)` 先查 `agentFactories`，命中就 `supplier.get()` —— 所以"每 thread 一个实例"是框架默认行为，前提是我们交出去的是 factory。**交实例（`register(id, obj)`）就等于把所有标签页接到同一份 context 上**，正是 §4 W2-b 实测到的累积 bug；`aguiRegistryHoldsAFactoryNotASharedInstance` 断言同一 id 两次 `getAgent` 必须是不同对象。
- 注册 id 的来源：`AguiAgentAutoRegistration` 在没有 `@AguiAgentId` 时**拿 bean 名当 agentId**，而 `default-agent-id` 默认是 `default` —— 走 customizer 显式按名注册，少一层隐式约定。

**浏览器实测（2026-10-01，端口 18081，真 key、human 确认模式）**：第 1 轮"1月31日登录次数超过3次的账号有多少个？"→ 确认卡片 → 同意 → 工具返回 61；同一页面追问"这些账号里渠道是 yx 的有几个？"→ 模型自己带上 `login_dt = '2026-01-31' AND login_cnt > 3` 并 JOIN `acct`。**指代继承一行代码都没写。**

原决定（2026-09-30）的两条理由没有被推翻，是被**接受**：token 随历史上涨、追问题成绩受历史长度干扰。可以接受的理由是 §7 的评测不经过这条路径（`--eval` 每题走 `AgentFactory.create()`，见 §4），页面会话与评测互不污染。#3291（权限规则被快照进持久化 session）在这个形态下不构成风险：状态只在进程内存里，重启即清，我们不做持久化。

> **2026-09-30 W2-a 的原结论留档**：当时认定"2.0.3 的 `ReActAgent` 没有 memory 入口，自存是唯一出路"。那句对 `ReActAgent` 本身仍然成立（它确实不接受 `memory(...)`），但结论错在把"没有 memory 入口"当成"没有会话机制"——AG-UI 这层的 `ThreadSessionManager` 是按 **agent 实例**而不是按 Memory 对象来做会话的，所以根本不需要 builder 有 memory 入口。

## 9. 工程结构

单模块 Maven（多模块解决的是"多个发布单元/依赖隔离"，你一个都不沾，代价是每次改包结构动 pom）。

```
pom.xml
LICENSE            ← Apache-2.0 原文（apache.org 拉取）
README.md / README.en.md   ← 中文主文档 + 精简英文（§2 第 5 条：英文只留 quickstart 与结果，不做双语同步）
docs/demo.gif      ← 由 tools/record_demo.py 真截图生成，进仓库（README 首屏）
docs/hitl-pause-resume-experiment.md ← #3096 的 throwaway 结论文档（两层实测 + 机制 + 上游动作），对应两个 probe 测试类
docs/upstream-deepseek-model-ids.md  ← DeepSeek 模型 ID 的上游 note（可复跑 curl + `ModelContextWindows` 实测表 + 英文 issue 草稿），对应 `agent/DeepSeekModelIdFactsTest` 的守夜断言
.github/workflows/ci.yml   ← 只跑离线用例、不注入 key（**2026-10-01 起真跑并全绿**，见 §2 对表④ 与 §10 W3-e）
src/main/java/...
  ├─ agent/ModelConfig       ← 启动 fail-fast：model 名非空且 != gpt-4.1-mini 且 key 非空，任一不满足则 Model bean 建立失败、进程拒绝启动。**联网探活不在启动路径里**（否则 §10 的 CI 不持 key 与 @SpringBootTest 一起破），由 `DeepSeekModelSmokeTest` 承担
  ├─ agent/                  ← AG-UI 注册 factory（AgentConfig）、每题现造的 AgentFactory、系统 prompt（SystemPrompt，运行时拼 schema）
  ├─ tool/                   ← run_sql
  ├─ guard/                  ← 护栏四步链（纯函数，可离线测）
  ├─ hitl/                   ← 确认门状态机 + Sinks + 超时
  ├─ db/                     ← SQLite 只读连接、schema/seed 初始化、--reset-db
  ├─ eval/                   ← --eval、断言、归因分类、结果 JSON
  └─ web/                    ← AG-UI 之外自加的确认端点：GET /api/confirm/stream（SSE）、GET /api/confirm/pending、POST /api/confirm/{id}
src/main/resources/
  ├─ application.yml
  ├─ schema/  ← game DDL + seed
  ├─ eval/    ← 25 题（含口径声明）
  └─ static/  ← 抄 examples/agui 的 index.html + agui-client.js，保留原版权头；index.html 里删掉官方那套前端工具 request_approval，换成我们自己的确认卡片（#confirm-panel）
tools/
  ├─ SeedGen.java       ← 固定 seed 造 seed_data.sql（纯 JDK，`java tools/SeedGen.java`）
  ├─ verify_eval.py     ← build/check：把 25 题的 truthSql 全跑一遍生成期望值，禁止手打数字
  ├─ edit_echo_check.py ← 手动复验"人编辑 SQL 后答复抄执行的那条"（§4）：起 human 模式的服务再跑，要打真 key，所以不进 CI
  └─ record_demo.py     ← 录 docs/demo.gif：Playwright 驱动系统里的 Edge（channel=msedge，headless，不下载浏览器）截四帧，Pillow 拼
```

- `.gitignore`：`.env`、`*.db`、`*-wal`/`*-shm`、`target/`、IDE 目录。评测结果 JSON **不 ignore**。
- `.env.example` 进仓库，**key 只走环境变量**，git 历史里永不出现明文 key。
- 抄来的 demo 页保留原文件头版权声明 + README 注明来自官方 examples（你自己选了 Apache-2.0，混进来源不明的代码是给下游埋合规雷）。

## 10. 里程碑与门禁

**Day-0（约 3 小时，全是"证伪前提"）** —— 四件全绿才许进 W1。**2026-09-30 四件全绿，Day-0 关闭**：
1. ✅ DeepSeek 官方域名最小 tool call 成功（个人 key，已存 `.env`，git 已 ignore）。
2. ✅ **模型 ID 实测（2026-10-01 复测，并纠了本条原先的一句话）**：`GET /models` 只返回两个正式名 —— `deepseek-flash`（显示名 DeepSeek-V4.1-Flash）与 `deepseek-v4-pro`（显示名 DeepSeek-V4-Pro），两者 `context_window` **1048576**、max output 393216、默认 effort=high；传不认识的 id 会得到信息充足的 400（"The supported API model names are deepseek-flash, deepseek-v4-pro, but you passed …"）。
   **本条原先写的"`deepseek-v4-flash` 不存在、照抄会 400"已被证伪**：2026-10-01 用纯 ASCII 请求体复测，它返回 **HTTP 200**，服务端把它当 `deepseek-flash` 的**别名**。误判的直接原因是我自己的探针：Git Bash 把 curl 体内的中文按 GBK 写坏，DeepSeek 回的是坏字节类的 400，被我读成了"模型名不存在"。
   > **根因（门禁④ 反编译时找到，2026-10-01 补上后果）**：框架 `ModelContextWindows.DEEPSEEK` 表停在旧命名（只登记 `deepseek-v4-flash` / `deepseek-v4-pro`），`deepseek-flash` 反而不在表里 → **正式名 `getContextWindowSize()` 得 0，旧别名得 1000000**。0 的影响面见 §3；上游 note + 英文 issue 草稿见 `docs/upstream-deepseek-model-ids.md`。
3. ✅ 结构化输出：**不可用**（见 §4"形状约束"）。
4. ✅ **Boot 空壳 + agent bean 注册（2026-09-30 跑通，四件全绿，可进 W1）**：
   - `mvn -B test` → `AgentBeanRegistrationTest` 绿：`openAIChatModel`(`OpenAIChatModel`)、`agentscopeReActAgent`(`ReActAgent`)、`Toolkit`、`InMemoryMemory` 四个 bean 全部注册。
   - `mvn spring-boot:run --server.port=18080` → `Netty started on port 18080`，`Started App in 2.478 seconds`（`/` 返回 404 属正常，还没有路由）。Boot **4.0.4 实测可用**，但理由见 §3 修正：**不是 starter 锁的，是我们 pom 定的**。
   - 附加探活 `DeepSeekModelSmokeTest`（无 `DEEPSEEK_API_KEY` 自动跳过，符合 §10 CI 不持 key 的约束）：`ModelRegistry.resolve("deepseek:deepseek-flash", ctx{stream=false, enableThinking=false})` → 返回 `2`、`finishReason=stop`、`modelName=deepseek-flash`、`supportsNativeStructuredOutput()=false`、`contextWindowSize=0`。**框架→DeepSeek 这条链路通了**（此前门禁① 只证明了裸 curl）。
   - 依赖互操作实测：`reactor-core` 解析到 **3.8.4**（≥ 框架编译期 3.8.2，无降级）；Boot 4 用 **Jackson 3**（`tools.jackson.core:jackson-databind:3.1.0`）而框架用 **Jackson 2.21.1**，坐标不同故**并存不冲突**——但我的代码必须显式 import `com.fasterxml.*` 才和框架的消息/工具 schema 序列化对齐，混用会静默失效。
   - ✅ 窗口函数已实测可跑（SQLite 3.53.4，`lag() over (...)` 正常）。

> **实测附带发现（会影响 §4 的步数上限）**：一次 turn 里模型**自发返回了 2 个 `tool_calls`**（并行工具调用无文档但实际会发生）。`max_tokens` 打满时 `finish_reason` 会是 `length` 且 `reasoning_tokens` 吃掉全部输出，所以探活脚本必须给足 max_tokens，否则会把"输出被截断"误判成"模型不会调工具"。

> **为什么 Day-0 有这道门禁**：本项目的一个前提（"我手上有能调通的配置"）在本轮访谈中被证伪过一次 —— `api.agnes.ai` 域名不解析、`qwen3-coder-plus` 不在该服务商模型列表里，也就是那份配置从未成功过。所以"配置未验证就开工"是已知会复发的失败模式。

**W1**：Boot 4.0.4 骨架 → SQLite schema/seed 生成 → `run_sql` + 护栏四步（含离线单测）→ **挂起式确认门跑通 + 一条集成测试**（approve / edit / deny 三条都验）。　✅ **2026-09-30 门禁达成**：56 条用例全绿（1 条 skip = 无 key 的 smoke）。approve/edit/deny/超时四条处置由 `RunSqlToolTest`（真 `Toolkit`、真 SQLite、不联网）逐条验，approve 另在 Spring 上下文里端到端跑一遍（`AgentBeanRegistrationTest`，含 `Toolkit.getToolNames()==[run_sql]` 断言）。细节见 §4 末实测清单。
> **进度（2026-09-30）**：✅ **W1-a/W1-b 完成**。`ModelConfig` 用 `ModelRegistry.resolve("deepseek:" + game.model.name, ctx)` 自建 `Model` bean，`agentscope.openai.enabled: false` 已把 starter 那条自动装配关掉。
> 实测确认了三件之前只是推断的事：① 换路之后 `Model` 的实现类**仍是** `OpenAIChatModel` —— SPI 只是套壳，白赚的是 `DeepSeekFormatter` + `thinking` 参数 + `nativeStructuredOutput=false`，不是另一个客户端实现，别在 README 里写错；② base starter 的 `agentscopeReActAgent` 正常吃到了我们自建 bean（`@ConditionalOnBean(Model)` 成立），四 bean 断言仍绿；③ `contextWindow=0` 已进启动日志，观察是否有静默裁剪。
> fail-fast 三条单测（缺 key / 缺 model 名 / 名字还是 `gpt-4.1-mini`）均拒绝启动；带 key 的 smoke 测试重跑仍真调通。
> ✅ **W1-c 前半：护栏第 1–3 步落地**（`guard/SqlGuard`+`Rejection`+`GuardOutcome`，29 条离线用例，CI 可跑）。实测推翻了 §6 原本预设的"检测多语句"路线，见 §6 末的实测清单 —— **一句话：执行文本必须取 AST 重新序列化的结果，绝不能用模型原文。**
> **W1 剩余**：无 —— 四段全部完成（骨架 / W1-a 模型 / W1-b fail-fast / W1-c 护栏+db+工具+确认门）。下一步是 §10 W2 的 `--eval` 与 prompt 的 schema 注入。
> ✅ **W1-c 后半：`db/` 落地**（`GameDatabase` + `SqlScript` + `DbProperties`，12 条离线用例）。整库 1MB seed 走 JDBC 会 `SQLITE_TOOBIG`，因此有了切分器；建库后强制验 8 张表，因为 SQLite 对错误路径会静默建空库。**最有价值的一条**：`GameDatabaseTest` 把 25 道题的 `truthSql` 全跑了一遍并逐条对上 `expect.value` —— 从此 §2 的"clone 下来 10 分钟能跑通"不依赖 sqlite3 CLI 也不依赖 Python，CI 就能证。细节见 §5 末实测清单。
**W2**：`--eval` + 25 题 → 第一个真实准确率 → AG-UI 页面抄改 → 追问 5 题的状态传递 → **半天 throwaway 实验**：单独测框架原生 permission+resume，确认 #3096 到底在哪一层复现，**结论作为 issue 提给上游**。
> **进度（2026-09-30 W2-a）**：✅ 带 schema 注入的正式 prompt 与自建 `ReActAgent` bean 完成（细节见 §4 末）。61 条用例全绿（1 skip = 无 key 的 smoke）。W2 剩余：`--eval` 跑手 + 结果 JSON、AG-UI 页面、追问 5 题的状态传递、#3096 的 throwaway 实验。
> **进度（2026-10-01 W2-b）**：✅ `--eval` 跑手落地并**真跑三轮**（DeepSeek flash，`auto_approve`）。**严格口径 88% / 宽松口径 92%，§2 的 75% 门槛达成**；判分规则被实测纠了两次（first-cell 误判 B10/T01、文字做除法误判 B05），三轮唯一稳定失败的题只有 B07 一道 —— 全部细节、逐题归因与"别拿单轮总分比改进"的纪律写在 §7 末。72 条离线用例全绿（1 skip = 无 key 的 smoke），`eval-results/*.json` 三份进仓库。W2 剩余：AG-UI 页面、追问 5 题的 §8 自存状态（探活已证"同一实例跨轮"这条路本身能答对指代，剩下的是它与 §8 结构的边界以及换 threadId 怎么办）、#3096 的 throwaway 实验。
> **进度（2026-10-01 W3-a 顺带把上面两笔清掉）**：AG-UI 页面完成；"§8 自存状态"这个任务**取消**——§8 已翻成"用框架的 ThreadSessionManager"，追问 5 题的状态传递由 threadId 会话直接提供（浏览器实测见 §8）。W2 只剩 #3096 的 throwaway 实验。
**W3**：README（中英）+ GIF + 准确率表 → 模型 ID 那条 issue → 收尾。
> **进度（2026-10-01 W3-0）**：✅ 本地仓库补全。离线构建此前炸在 `PluginResolutionException`，**根因不是 jar 没下过**：`~/.m2` 里的 plugin pom 打着旧仓库 id `aliyunmaven`，而 settings.xml 的 mirror id 是 `aliyun-public`，离线模式就不敢信本地已有文件；联网跑一遍重新盖章即解决。同时把 `agentscope-agui-spring-boot-starter` / `agentscope-extensions-agui` 2.0.3 及它们在 Boot 4.0.4 版本管理下的完整传递闭包灌进本地仓库（做法：仓库外放一个与本项目 pom 同构 + 这两个依赖的探针 pom，`mvn dependency:go-offline`，验完删）。验证：`mvn -o -B clean test` 72 绿、`mvn -o -B clean package` 含 `spring-boot:repackage` 成功。
> **进度（2026-10-01 W3-a）**：✅ AG-UI 页面接线并在浏览器里真跑通（端口 18081、真 key、`human` 确认模式）。抄官方 `examples/agui` 的 `index.html` + `js/agui-client.js`（Apache 头原样保留），删掉官方那套前端工具 `request_approval` + interrupt/resume（§4 选 D 的理由不变），换成我们自己的确认卡片走 `GET /api/confirm/stream`；页面加载先拉 `GET /api/confirm/pending` 补"比页面早出现的待确认项"。`AgentConfig` 不再暴露 `ReActAgent` 单例 bean，改注册 factory —— 于是 §8 从"自存"翻成"用框架"（推导与实测见 §8）。浏览器验到的是：工具事件流、确认卡片里可编辑的 SQL、同意→61、拒绝→模型明说没拿到数、人改 `>3`→`>5`→执行返回 8（同时暴露"答复抄回原 SQL"的缺陷，见 §4）、追问指代继承、`sqlite_master` 的护栏拒绝不出卡片。79 条离线用例全绿（新增 7 条：AG-UI 配置绑定、路由存在、页面可发、pending 端点、`resolveAgent` 同 threadId 同实例/异 threadId 异实例、registry 持有 factory、无共享 `ReActAgent` bean）。**§2 验收项 3（GIF 里"人编辑 SQL 后执行"那一帧）仍未做，README 未写。**
> **进度（2026-10-01 W3-b）**：✅ "人改过 SQL、答复抄原文"缺陷修掉了，走的是 prompt 硬规则（§4 末有修复实测）。同轮**顺带修了评分器第三个 bug**（宽松口径分母被 subset 顶掉，见 §7）—— 它不影响 88%/92% 那三行的结论，但影响任何要进 README 的第二个数字。第四轮 `--eval`（新 prompt）严格 84% / 宽松 88%，翻脸集合 A03↓ B04↑，按 §7 纪律不作为变差的证据。83 条离线用例全绿（新增 4 条：prompt 复述规则锚点 1 条 + `EvalCommandSummaryTest` 3 条），1 条 skip = 无 key 的 smoke。W2/W3 剩余：GIF、中英 README、#3096 的 throwaway 实验、§13 你那三票（B07 在排队）。
> **进度（2026-10-01 W3-c）**：✅ §2 五条验收**逐条对表完成**（对表结果与两条诚实缺口写在 §2 末）。产出：`docs/demo.gif`（真截图四帧，含"人改 `> 3` → `> 5`"那一帧，由 `tools/record_demo.py` 生成）、`README.md`（中文主）+ `README.en.md`（只 quickstart 与结果两节）、`LICENSE`（Apache-2.0 原文）、`.github/workflows/ci.yml`（**从未被 GitHub 执行过**，仓库无 remote）。清掉了本机一份陈旧的 `data/game.db`（9-30 生成的，已被 gitignore）来实测"删库→重建→起服务→`GET / 200`"，实测数字进 §2 对表。W3 剩余：模型 ID 那条 issue、#3096 实验、README 的 GIF 若你要换真人录屏。
> **进度（2026-10-01 W2-x，#3096 的半天 throwaway 结案）**：✅ 两层都测完了，承载在两条**离线的、进仓库的**断言上（`hitl/FrameworkPauseResumeProbeTest`、`hitl/AguiPauseResumeProbeTest`，脚本模型 + 占位 key，一次网络不打），结论文档 `docs/hitl-pause-resume-experiment.md`。三件事实：① **core 层手写的 pause/resume 在 2.0.3 是好的** —— 批准后工具执行 1 次、结果进第二次模型调用（`enablePendingToolRecovery` 开关不影响）；② 之前"丢结果"的说法**对象搞错了**：AG-UI 层丢的是 `TOOL_CALL_RESULT`/`TOOL_CALL_END` **事件**（`AguiStreamContext` 每 run 新建 + `startedToolCalls` 门禁），模型上下文不缺 —— 与上游 #3096 正文完全一致，我们在 2.0.3 复现了它；③ 框架原生 resume 支持 `editedArgs` 整体替换入参（实测工具与模型都只看见改后的值）。**不发新 issue**（#3096 已 closed、修复 PR #3100 已合 main、`v2.0.3` 仍是最新 tag = 未发版），改为准备了一条复现确认评论 + 一条相邻坑（自建 `ToolUseBlock` 恢复时框架校验的是 `getContent()` 原始 JSON，不是 `getInput()` 映射，缺它会长得一模一样像 #3096）。**§4 决定 D 维持，但理由换成"事件流丢、演示页按事件流渲染"**。86 条离线用例全绿（1 skip = 无 key 的 smoke）。
> **进度（2026-10-01 W3-d，模型 ID 那条上游 note）**：✅ 测完并写好 note，**issue 没发**（要他点头，且本仓库无 remote、`gh` 未登录）。两个实测事实：① 服务商侧 —— 官方 `/models` 只有 `deepseek-flash` / `deepseek-v4-pro`（`context_window` 各 1048576），但 `deepseek-v4-flash` **也在 200**（服务端当别名），所以 §10 门禁② 那句"照抄会 400"是错的，已按实测改写（错因见那条）；② 框架侧 —— `ModelContextWindows.DEEPSEEK` 缺 `deepseek-flash`，`ModelRegistry.resolve("deepseek:deepseek-flash", …).getContextWindowSize()` 实测 **0**（旧别名 1000000、已退役的 `deepseek-reasoner` 也是 0）——那份表就是他们文档示例里出现 `deepseek-v4-flash` 的最可能来源（**推断，未证实**：没有提交记录或说明能证明文档是从这张表抄的，两者只是同源于一批旧命名）。影响面扫了全部 jar 的字节码：内核无人读这个值，所以 0 今天只是启动日志里的一个展示值（§3）。产出：`docs/upstream-deepseek-model-ids.md`（可复跑的 curl + 四行实测表 + 修复建议 + 英文 issue 草稿 + 去重证据）和 `agent/DeepSeekModelIdFactsTest`（2 条**离线守夜断言**：上游补表后 `window("deepseek-flash")` 会自己变红，等于把"何时可以删掉这段 workaround"编进了测试）。88 条离线用例全绿（1 skip = 无 key 的 smoke）。**W2/W3 我这边能单推的工程项至此全部做完**，剩下的都不是我一个人能推进的 —— 待办与缺口统一收在 §15。
> **进度（2026-10-01 W3-e，推远端）**：✅ 建仓 + 推送 + **CI 第一次真跑并全绿**。远端 `github.com/hejian900625/game-nl2sql-agent`（公开，`gh repo create --public --source=. --push`）。推之前做了秘密审计（`.env` 从未进历史、真 key 在 `git log --all -p` 里命中 0、tracked 只有占位）。两次 push 各触发一次 Actions：run `36824220129` = `Tests run: 88, Failures: 0, Errors: 0, Skipped: 1`、`BUILD SUCCESS`、15 秒 —— **§2 对表④ 与 §15.4 的第 2 条缺口由此关闭**，README 顶部加了 CI 徽章（实测 200）。README 的 `<this repo>` 占位换成真 URL，英文 README 的 quickstart 补上 `git clone` 一行（原来只有 `cp .env.example .env`，等于假设读者已经有仓库了）。
> 一条必须记下的**自我纠正**：我先前判断"github.com 是被 DNS 污染到一个不可达 IP，需要本地 IP 钉定代理"。代理真写出来了（`research/pin-github-proxy.mjs`，gitignored，留着以防再抖），但**这次推送没用上它**：实测把"污染"这个说法打掉了 —— `140.82.112.3 / 113.3 / 116.3` 与 `20.205.243.166` 的可用性**在几分钟内互换**（同一批测试里 113.3 从 200 变 000、20.205 从 000 变 200），而 `git ls-remote` 裸跑连两次都拿到 `7fd1a60b…`。**真实情况是这台机器到 GitHub 的可达性在抖，不是一条可以钉死的坏路由。** 证据还有：本次 README 那次推送连续失败 3 次（`Failed to connect to github.com:443 after 21s`），期间我先量的 git 端点（`/info/refs?service=git-upload-pack` 返回 200）再重试就通了。结论：**网络判断要按"具体端点 + 多次采样"说，单次 `000` 不足以定机制，也别急着写代理。**
> **这条判断当天晚上又要修一次（W3-g，推 `8abbd0d` 时）**：裸 `git push` 连败 4 次（同一句 `Failed to connect to github.com:443 after 21s`）。我先按上面的方法论把四个候选 IP 的**同一个 git 端点**逐个量：`140.82.112.3` / `113.3` / `116.3` 全 200，只有 `20.205.243.166`（Azure 中国方向）000，而 `api.github.com` 同时 200。然后用 `research/pin-github-proxy.mjs` 把 `github.com` 钉到 112.3/113.3，`git -c http.proxy=http://127.0.0.1:8765 push` **第一次尝试就通了**（`2ddde83..8abbd0d`）。所以正确的说法不是"代理没用上"，而是：**这台机器的解析器在好坏 IP 之间轮换，撞上坏的那一个时钉定确实救得回来**。"先别急着写代理"仍然成立（要先按端点多采样），但把"这次没用上"写成"代理没用"是越过证据了。再往后一次（推 `e7458fc`）把机制说得更死一点：裸 push 连败 4 次（约 2 分钟），期间`curl https://github.com/` 是 000，而 `curl --resolve github.com:443:140.82.112.3 https://github.com/` 是 **200**，`api.github.com`/`codeload.github.com` 同时 200 —— **坏的不是 IP 段，是这台机器对 `github.com` 这个主机名的解析结果**。所以钉定代理不是"最后手段"，而是这台机器上推 git 的正路；用完即关（两次分别是 pid 19116、19744，关后实测 8765 无监听，不留后台进程）。CI 对 `8abbd0d` 重跑并全绿：run `36827713731` = `Tests run: 88, Failures: 0, Errors: 0, Skipped: 1` + `BUILD SUCCESS`（日志行核过）。
> **同一条结论的第三次修正（推完 `c1b52f0` 之后现测）**：那次裸 `git push` **第一次尝试就通了**（`e7458fc..c1b52f0`），于是按上一条自己定的方法论重量一遍，结果把上一条推翻。`dns.resolve4('github.com')` 连测五次全是 `20.205.243.166` —— 正是推 `e7458fc` 那次量到 000、被我写成"坏的那一个"的 IP —— 而它现在对同一个 git 端点两次都 **200**；反过来用 `--resolve` 逐个钉 `140.82.112.3 / 113.3 / 116.3`（代理当时钉的三个、我据此说"IP 段是好的"），**六次采样全 000**。所以"**坏的不是 IP 段，是主机名的解析结果**"同样越过了证据：**真正在漂的是每个 IP 自身的可达性**，而且可以整批互换 —— 上一轮"能用的三个"这轮一个都不用，上一轮"坏的"那个这轮是唯一通的。**这条的实操后果比结论重要**：`research/pin-github-proxy.mjs` 把 IP 写死在代码里，等于把"某一时刻的好 IP"当常量，而此刻它钉的三个全不通 —— 这个代理在这台机器上现在会把本来能通的裸 push 变成不通，它不是"正路"，只是一次撞上好运的手段。正确顺序是**先量再选**：① 裸跑一次 git 端点；② 不通就把"当前解析到的 IP + 候选 IP"对同一端点各测两次，钉**当下返回 200 的那个**，不钉固定名单；③ 推完立刻关代理并实测 8765 无监听。CI 对 `c1b52f0` 全绿：run `36831764383`（success，34s）。**紧接着的一条实测把"先量再选"补完了**：推这条修正时 `github.com:443` 四个候选 IP（含当前解析到的 `20.205.243.166`）八次采样**全 000**，`api.github.com` 仍 200 —— 也就是存在"整体不可达的窗口"，此时钉定也救不了，能做的只有等。于是一个每 45 秒重试的裸 push 循环**第 5 次通了**（`5532afa`，前 4 次 `Failed to connect to github.com:443 after 21s`），CI run `36832983423` success。**规则至此收敛成三句：先裸跑；不通就量端点并按当下 200 的 IP 决定是否钉；全都 000 时不要动代理，隔一分钟重试裸 push —— 这台机器的窗口几十秒到几分钟就换。**
> 同一轮把**两条上游动作也发了**（你点头之后）：#3096 的复现确认评论 + 新 issue **#3379**（`ModelContextWindows.DEEPSEEK` 缺正式名，按他们的 `bug_report.md` 模板写，含离线复现代码）。落点 URL 记在 §14 末与 §15.1 第 3 条。**注意一个记录上的坑**：真正发出的正文放在 `research/`（gitignored），仓库文档里是草稿，两者不逐字相同 —— 以后要引用以 issue 页面为准。
> **进度（2026-10-01 W3-f，README 逐题明细）**：✅ 关掉 §15.3 里唯一我能独立做的那条。中文 README 的"四类失败"从一个计数扩成**计数 + 逐题表**：5 道失败题（B03/B04/B05/B07/A03）各列出期望值、三轮分别跑出的首行单元格、以及错在哪一句；英文 README 保持精简，只加"六个非数字终态一次没触发"这条事实并指回中文表。全部数字是从 `eval-results/*.json` 现读的（`python` 读 `verdict`/`expected`/`firstRowCells`/`toolCalls` + `business.json` 的题面/口径/`truthSql` 对照），不是从记忆里抄的 —— 中途就抓出一处记忆错：我原本要写"v2 那轮 B05 按天分组后没相除"，读 JSON 才发现它按天分组仍算了 67.50%，只是商不在单元格里（`9, 6`）。顺带把一条更硬的反证写进表前：**四份**结果 JSON（含 `eval-results/archive/` 那份换判分规则前的）的 `summary.byVerdict` 只有 `CORRECT` 与 `WRONG_NUMBER` 两个键，`NO_TOOL_CALL`/`GUARD_REJECTED`/`EXEC_ERROR`/`HUMAN_DENIED`/`UNKNOWN_RESULT`/`AGENT_ERROR` 六个终态一次没出现，所以"三轮唯一稳定失败的是 B07，且它是题目缺陷"这个说法有了分类学支撑。同轮修掉 §14 一处陈旧标记（模型 ID 那条还写着"未发"，实为 #3379 已发）。
> **进度（2026-10-01 W3-h，他真人跑 README 炸出来的启动缺陷）**：✅ 修完并锁住。现象：照 README `cp .env.example .env` → `mvn spring-boot:run`，cmd.exe 里拿到 `Error creating bean with name 'model' … DEEPSEEK_API_KEY 未设置`。**根因不是 key 错，是没人读 `.env`**：Spring Boot 不自动读它，`application.yml` 只有 `${DEEPSEEK_API_KEY:}`，pom 里也没有 spring-dotenv 之类的东西（grep 过 `dotenv`/`.env` 零命中）。我此前所有启动验证都在能拿到 key 的环境里做，所以 §2 对表① 那条"能起"从来没覆盖过这条路径。修法用框架原生的一条：`spring.config.import: optional:file:./.env[.properties]`（`[.properties]` 是 ConfigData 的后缀技巧，把任意文件按 KEY=VALUE 解析；`optional:` 保证没有该文件也能起），不引新依赖。三条离线断言锁住（`agent/EnvFileImportTest`）：① `.env` 里的键确实解析成属性；② **属性源顺序实测**：`systemEnvironment` 排在所有 `Config resource` 之前，所以导出的环境变量压过 `.env`（优先级不是猜的 —— Boot 4 里 `ConfigDataApplicationContextInitializer` 已不存在，入口换成 `ConfigDataEnvironmentPostProcessor.applyTo(env)`，探针跑出来的属性源列表是：`configurationProperties`/`test`/`systemProperties`/`systemEnvironment`/临时 .env/项目根 .env/classpath application.yml）；③ 文件不存在时不炸。占位符解析按**字面名**查、不做 relaxed binding，所以测试里必须问 `GAME_DOTENV_PROBE_KEY` 而不是 `game.dotenv.probe.key`（第一版就问错了，红过一次）。验收照 §2① 重做一遍，这次 shell 里 `DEEPSEEK_API_KEY` 确认不存在：`Started App in 2.049 seconds` + `GET / 200`，key 只来自 `.env`。91 条用例全绿（1 skip）。**过程中自己制造又修掉的一个秘密泄漏**：第一版第三条断言写的是 `getProperty("DEEPSEEK_API_KEY")` 断 null，而它非 null（值来自项目根那份真 `.env`，经 classpath `application.yml` 的 import 一起进来了），失败信息把真 key 打进了 surefire 报告两份文件 —— 已删那两个文件并复查 `target/` 无残留，测试改成只断"没有那个属性源"、永不打印值。README 侧：quickstart 补了 `.env` 与环境变量的优先级说明，"已知问题"里加了这条缺陷的来龙去脉（含"我这台机器能起 ≠ 照 README 能起"）。另外发现一个**孤儿进程**：pid 3440 是本项目早前验证留下的 `java … -Dserver.port=18086 … gamenl2sql.App`，它让一次新启动以 "Port 18086 was already in use" 失败（差点被误读成 key 又坏了）。没动它，报给他处置。
> **同一轮记下的两个"不动"（他的决定，别再问）**：① `~/.qoder-cn/gh-token/code.txt` 那枚明文 PAT **保留**、暂不吊销（GCM 已缓存 `git:https://github.com`，文件是冗余的第二份拷贝）；② commit author email `181013051@qq.com` 随公开仓库可被抓取，**不改**（改需重写历史 + force-push）。这两条从 §15.1 的"等他点头"里划掉，收尾时 DeepSeek key 的轮换（§15.1 #4）仍在。
**每周留一天不发功能**（W1 因 Boot 4 额外吃半天，这条从可选变成必须）。

**版本窗口**：锁 2.0.3；**唯一例外** —— 若 2.0.4 在 W1 结束前**正式打 tag**（不是 README 提到），给半天跑全绿回归后升一次，此后不再升。理由：你需要的那两个修复只在 2.0.4，而 pin SNAPSHOT 期间的任意提交会直接毁掉 §2 的"10 分钟复现"。

## 11. 风险登记

| 风险 | 状态 | 预案 |
|---|---|---|
| 框架 HITL resume 有未发版修复 | **已绕开**（选 D） | W2 的 throwaway 实验专门探它的边界并回报上游 |
| 长挂起响应式流（你没有响应式经验） | 已知并计入预算 | SQL 执行/等人不落事件循环；`run-timeout` 调大；实在不行退回两段式 C 并重开 §4 |
| #2548：`response_format` 被 OpenAI 兼容网关拒 | **已证实**：DeepSeek 官方就拒绝 `json_schema`（Day-0 实测 400） | 退化路径成为唯一路径（Jackson 严格解析 + 重试 1 次），不依赖框架结构化输出 |
| #3299/#3209：思考档 + 多轮 → 400 | Day-0 证伪 | 默认非思考档；对照实验变量改换 |
| 无公开 text-to-SQL 成绩证明 DeepSeek V4 擅长 SQL | 无外部证据 | 你的 25 题是唯一事实来源。**若首测只有 45%，我们讨论降门槛或改 prompt，不许改简单题** |
| Boot 4.0.4 是额外学习成本 | 接受 | W1 加半天，挤占 §10 那一天空白 |
| SQLite 无只读账号 | 已补偿 | 护栏 1–2 步为主力 + 只读连接 |

## 12. 游戏域 schema 设计（我设计，待你确认）

> 更正：Q42② 你答的是"你设计实体、我确认"，所以这块是我的产出；上一版误写成你的活。

设计原则：**每张表都要至少埋一个"只能靠读 DDL 和中文注释才能避开的坑"**。否则模型在背常识，不在读你的库，你的准确率数字没有意义。但坑要可枚举、可解释，不许靠混乱。

### 12.1 八张表

| 表 | 用途 | 关键列（含刻意设计的脏点） |
|---|---|---|
| `srv` | 区服字典 | `srv_id` PK、`srv_name`（中文，含"X区-龙渊"式，**与 id 不同序**）、`open_dt`（开服日，`YYYY-MM-DD`）、`srv_st`（1 开 0 关，**不是布尔**）、`plat`（`and`/`ios`/`pc`） |
| `acct` | 账号 | `acct_id` PK、`chan_id`（买量渠道：`yx`/`tt`/`gw`/`ios`，**含义只在注释里给映射**）、`reg_time`（**`YYYY-MM-DD HH:mm:ss`**）、`first_pay_dt`（可空，**空=从未付费**）、`last_login_dt`（`YYYY-MM-DD`）、`is_del` |
| `role` | 角色 | `role_id` PK、`acct_id`、`srv_id`、`role_no`（区服内序号，**命名与语义都不显眼**）、`create_time`、`lv`、`vip_lv`（**0=非会员，不是 NULL**）、`is_del` |
| `goods` | 商品字典 | `goods_id` PK、`goods_name`、`price_yuan`（**单位:元，REAL**）、`goods_type`（1 月卡 2 战令 3 直购 6 首充）、`is_on` |
| `pay_ord` | 付费订单 | `ord_no` PK、`acct_id`、`srv_id`、`goods_id`、`pay_amt`（**单位:元，REAL**）、`pay_time`（`YYYY-MM-DD HH:mm:ss`）、`ord_st`（1 成功 **2 退款** 3 失败 —— **退款不是负数**）、`is_del` |
| `login_log` | 日粒度活跃 | (`acct_id`,`srv_id`,`login_dt`) 复合 PK、`login_cnt`、`dur_sec`。**本表没有 `is_del`** |
| `item_flow` | 道具变更流水 | `flow_id` PK、`role_id`（**要经 `role` 才到 `acct`，两跳 JOIN**）、`item_id`、`chg_type`（1 获得 2 消耗 3 系统补发 4 交易）、`chg_num`（**有正有负**）、`create_time`。**本表没有 `srv_id`** |
| `season` | 赛季 | `season_id` PK、`srv_id`、`st_dt`/`ed_dt`（**字段名风格与全库不一致**），用于"赛季内"类口径 |

### 12.2 坑清单 → 必须被某道评测题覆盖

| 坑 | 模型会怎么错 | 归到哪类题 |
|---|---|---|
| 金额是 REAL，求和有尾差 | **不是模型的坑，是评测的坑** —— 见 §7 的容差规则 | 聚合组 |
| 退款用 `ord_st=2` 表示 | 算"净付费"时不减，或用 `SUM` 一把梭 | 多表 JOIN |
| `login_log` / `item_flow` **无 `is_del`** | 幻觉出 `WHERE is_del=0` → SQL 直接报错 | 单表 |
| `item_flow.chg_num` 有正负 | 对付费也套 `ABS()`，或对流水做 `SUM` 时把消耗当收益 | 对抗题 |
| 时间字段两种格式 | 用 `date()` 直接切 `reg_time` 的日，或跨格式比较 | 聚合/追问 |
| `chan_id` 是拼音缩写 | 靠猜渠道含义 → 分组维度和期望值对不上 | 对抗题 |
| `srv_name` 与 `srv_id` 不同序 | 输出时把区服名字配错 | 多表 JOIN |
| `vip_lv=0` | `IS NOT NULL` 判会员，把全部用户算进去 | 单表 |
| `first_pay_dt` 为 NULL | `IS NOT NULL` 当"老玩家"，与"注册 7 日内"定义冲突 | 追问（口径类） |
| `item_flow` 要两跳到账号 | 用 `srv_id` 直接筛（该列不存在） | 多表 JOIN |

### 12.3 数据与确定性

- 规模（够小以便你手验、够大以便模型不能蒙）：区服 5、账号 **300**、角色 450、商品 20、付费订单 **~2500**、活跃日志 **~9000**、道具流水 **~12000**、赛季 10。
- **时间锚定 `2026-01-31` 为"今天"**，数据覆盖 `2025-11-01 ~ 2026-01-31`。`2026-01-31` 是周六 —— "上周"因此有自然周（周一起）与滚动 7 天两种答案，**这正是必须写口径声明的原因，不是巧合**。
- seed 必须由固定种子的生成脚本产出（**不用随机数**：同一 seed 跑两次得同一批期望值，否则你的回归集会无故变红）。
- 数据里必须**人工埋 3 处反例**：某区服上周付费跌幅最大但绝对额不是最大（区分"百分比 vs 绝对额"）；某账号只有退款单（净付费为 0 而非负）；某角色区服与账号注册渠道冲突。这三处直接对应你的招牌题。
- 表名/列名全部单数缩写风格，注释一律中文，字段级 `--` 注释写进 DDL（模型唯一的口径线索来源）。

## 13. 待你产出（2026-09-30 状态变更：不再是阻塞项）

**原计划由你写的 15 道题 + 每题口径声明，你当天改口"直接生成"，已由我代笔** → `src/main/resources/eval/business.json`，`owner: qoder`，生成与复算工具 `tools/verify_eval.py`（`build` 生成、`check` 把两个集合共 25 条 `truthSql` 重跑并与期望值比对，当前 25/25 一致）。

代价必须写在脸上：**这 15 题的口径是我对这份数据集的设定，不是业务真实口径**，所以 §1 的"评测可信度"打了折 —— 它现在测的是"模型能否在一个定义良好的库里正确取数"，不再测"口径含糊时人会不会被误导"。README 的准确率表要按这个措辞。

**留给你的不是写题，是三次否决**（每条都够便宜，5 分钟内能做完）：
1. 挑出任何一条你觉得"现实里不会这么问"的题，我换成你那句 —— 换完必须重跑 `verify_eval.py check` 前先在 `build` 里改 `truthSql`，期望值仍由脚本产生。
2. 挑出任何一条口径与你的业务直觉相反的（例如 B05 我把次留定成**加权**而不是各日留存率的算术平均；T02 我把注销账号**留在分母**里）。这两个选择在真实运营里都有人持相反意见，你的一句话就能把它变回"需求方口径"。
3. 你原来那句招牌题"上周哪个区服的付费金额掉了最多，是新玩家还是老玩家掉的"**还没进评测集** —— 它要求三件口径（绝对额/百分比、自然周/滚动 7 天、新玩家分界），也正是 §12.3 三处反例的用途。我的 B06/B07/T01 只是邻近区域，替不了它。建议你亲手把它写进来当第 25+1 题：这题模型十有八九会答错，而它对不对直接决定 README 第一屏那个数字有没有说服力。


## 14. 已核实事实来源（供你复核）

- 框架：`github.com/agentscope-ai/agentscope-java`（tag `v2.0.3`）、`java.agentscope.io` v2 en/zh 文档、repo1.maven.org 目录列举。
- 关键结论：2.0.3 是真实 GA 版本线（2.0.0 GA 2026-07-10 → 2.0.3 2026-09-07）；`agentscope-extensions-judge` **不存在**（评测得自己写）；**不存在** `agentscope-extensions-model-deepseek`（DeepSeek 藏在 model-openai 里，SPI 注册）；AG-UI 两个 artifact **不含任何前端资源**；无独立 deepseek 模块；`mcp-nacos`/`a2a-nacos-spring-boot-starter` 只到 1.0.3（2.0 断档）。
- 缺陷：#3315/#3294/#3320/#2773/#3369/#3291（HITL 与权限）、**#3096 已在 2.0.3 本地复现**（丢的是 resume run 的 `TOOL_CALL_RESULT`/`TOOL_CALL_END` 事件，不是模型上下文；复现断言 + 机制见 `docs/hitl-pause-resume-experiment.md`），修复 PR #3100 已合 main 但**未发版**（`v2.0.3` 仍是最新 tag，2026-10-01 用 GitHub API 核过）、#3104 同样未发版、#2696/#2548/#3301（结构化输出）、#3353/#3209/#3057（工具调用）。
- 模型：`api-docs.deepseek.com`（模型枚举、定价、限流 2500/500、tool_calls 约束）。
- 模型 ID 与框架窗口表（2026-10-01 复测，全部可重跑）：官方 `GET /models` = `deepseek-flash` / `deepseek-v4-pro`（`context_window` 1048576）；`deepseek-v4-flash` 是**别名**而非死名（200，回填 `deepseek-flash`）；`ModelContextWindows.DEEPSEEK` = `{deepseek-v4-flash, deepseek-v4-pro}`，正式名 `getContextWindowSize()` 实测 0；扫全部 `io.agentscope` 2.0.3 jar：`getContextWindowSize` 的引用方只有 `Model`/`ChatModelBase`/`ReActAgent$2`/`extensions-aistio` 四处。承载：`agent/DeepSeekModelIdFactsTest`（离线守夜）+ `docs/upstream-deepseek-model-ids.md`（含英文正文；**2026-10-01 已作为 agentscope-ai/agentscope-java#3379 发出**，文档里那份是草稿，逐字以 issue 页面为准）。
- Spring Boot **4.0.4 读 `.env` 的机制**（2026-10-01 W3-h 实测，全部锁在 `agent/EnvFileImportTest`）：`spring.config.import: optional:file:./.env[.properties]` —— `[.properties]` 是 ConfigData 的后缀技巧，让任意文件按 `KEY=VALUE` 解析；`optional:` = 文件不存在照常启动；不引第三方 dotenv 依赖。属性源顺序（探针打 `getPropertySources()` 的名字，不打值）：`configurationProperties` → `test` → `systemProperties` → **`systemEnvironment`** → 各 `Config resource`（含 `.env` 与 classpath `application.yml`），即**导出的环境变量压过 `.env`**。两个坑：① Boot 4 里 `ConfigDataApplicationContextInitializer` 已不存在，测试里入口是 `ConfigDataEnvironmentPostProcessor.applyTo(env)`；② `${}` 占位符按**字面名**查，不做 `@ConfigurationProperties` 那套 relaxed binding，所以 `.env` 里的 `DEEPSEEK_API_KEY` 只能被 `${DEEPSEEK_API_KEY}` 读到。另：`applyTo` 在测试里会连带读 classpath 的 `application.yml`，于是项目根的真 `.env` 也会进属性源 —— 断言里**永远不要打印这些键的值**。
- 已废弃并从计划中删除：Agnes 网关（`api.agnes.ai` NXDOMAIN；`qwen3-coder-plus` 不在其模型列表；免费档可能用输入做训练数据；约 20 RPM 上限）。
- 对外动作的落点（2026-10-01，全部以你账号 `hejian900625` 发出，可点开复核）：仓库 <https://github.com/hejian900625/game-nl2sql-agent>（公开，`main`）；CI run `36824220129`（88 tests / 0 fail / 1 skip / `BUILD SUCCESS`）；#3096 评论 <https://github.com/agentscope-ai/agentscope-java/issues/3096#issuecomment-5925939634>；新建 issue [#3379](https://github.com/agentscope-ai/agentscope-java/issues/3379)。

## 15. 当前待办与缺口（2026-10-01 收口）

这一节是**唯一汇总**。§10 各条进度里写的"剩余"只是当时的快照，与本节冲突时以本节为准。

### 15.1 需要你点头或你亲手做的（四件里两件已于 2026-10-01 结掉）

1. **§13 的三次否决**（换题 / 改口径 / 招牌题进门）。当前状态：**B07 在排队** —— 它三轮换了三种错法，而根子在题目本身：题面与我代笔的口径互相冲突，这种题模型没人能稳过。你一句话就能把口径变成"需求方口径"。招牌题"上周哪个区服的付费金额掉了最多，是新玩家还是老玩家掉的"**仍不在评测集里**（现有 B06/B07/T01 只是邻近，替不了它）。任一改动之后强制走 `tools/verify_eval.py build` → `check` → 重跑 `--eval`（§7 的纪律：期望值只能由脚本产生）。
2. **~~推远端~~ 已完成（2026-10-01）**：公开仓 `https://github.com/hejian900625/game-nl2sql-agent`，`main` 已推，CI 真跑全绿（见 §2 对表④）。**推之前做的秘密审计**：`.env` 从未进过任何提交；把 `.env` 里的真 key 拿去 `git log --all -p` 里数，命中 0 次；tracked 文件里只有 `${DEEPSEEK_API_KEY:}` 与 `placeholder-no-network-call` 两类占位；`research/`、`data/*.db` 未被 track。**2026-10-01 他明确决定"不动"的两笔，别再问**：① 那枚 gh classic token（`repo+read:org+workflow`，30 天期）不吊销，明文文件 `~/.qoder-cn/gh-token/code.txt` 也不删（GCM 已缓存 `git:https://github.com`，它是冗余的第二份拷贝）；② 每个 commit 的 author email `181013051@qq.com` 随公开仓库可被抓取，不改（改需重写历史 + force-push）。收尾时只剩第 4 条的 DeepSeek key 轮换。
3. **~~点头才发的两条上游动作~~ 已发（2026-10-01 W3-e，用你的 GitHub 账号 `hejian900625`）**：
   - #3096 的复现确认评论 → <https://github.com/agentscope-ai/agentscope-java/issues/3096#issuecomment-5925939634>（未开新 issue，理由同上；正文另带一条相邻坑：自己重建 `ToolUseBlock` 时框架校验的是 `getContent()` 原始 JSON）。
   - `ModelContextWindows.DEEPSEEK` 缺正式名的新 issue → **agentscope-ai/agentscope-java#3379**（open，标签 `bug`）：<https://github.com/agentscope-ai/agentscope-java/issues/3379>。
   两条的可读记录分别写在 `docs/hitl-pause-resume-experiment.md` 与 `docs/upstream-deepseek-model-ids.md`。**注意**：实际发出的正文存在 `research/`（gitignored），两份文档里的是草稿与摘要，不逐字等同 —— 以后要引用以 issue 页面为准。
4. **轮换 DeepSeek key —— 优先级已在 2026-10-01 提高，且不再只是"收尾再做"**：它进过两个我不控制的地方 —— ① 早先被贴进聊天窗口；② W3-h 我自己制造的那次：一条写错的断言把真 key 打进了 surefire 的两份报告文件（`target/surefire-reports/…EnvFileImportTest.txt` 与同名 XML）。那两份已删、全仓复查只剩 `.env` 本身含它，但**磁盘上写过就等于泄露过**，删文件不算补救。仓库侧干净这件事不变：key 从未进过任何提交，`.env` 一直 gitignore。动作在你那边：DeepSeek 控制台删掉这枚 key、建一枚新的，然后只改 `.env` 一行 —— 代码、README、CI 都不含 key，不用动。换完如果想验，`mvn -o -B -Dtest=DeepSeekModelSmokeTest test` 是唯一真打网络的那条（无 key 时它自己 skip）。

### 15.2 等发版才能做的 —— 两件都编成了会自己变红的断言，不靠我记得

| 触发条件 | 届时动作 | 哨兵 |
|---|---|---|
| 出现含 PR #3100 的新 tag（`v2.0.3` 之后第一个） | 把"第二次 run 不含 `TOOL_CALL_RESULT`/`TOOL_CALL_END`"两条 `noneMatch` 翻成 `anyMatch`，并重新评估 §4 决定 D 还成不成立 | `hitl/AguiPauseResumeProbeTest` |
| 上游把 `deepseek-flash` 补进 `ModelContextWindows.DEEPSEEK` | 删掉本项目的 workaround 说明，`docs/upstream-deepseek-model-ids.md` 归档 | `agent/DeepSeekModelIdFactsTest`（现在断言"缺这个键"和"窗口为 0"，被修好即红） |

### 15.3 可选（不做也不影响 §2 五条达标）

- GIF 换真人录屏。现在的首屏是 `tools/record_demo.py` 用系统 Edge 截的四帧真截图拼的，§2 第 3 条已经满足，换真人录屏只是观感。
- ~~README 首屏补四类失败的逐题明细（现在只有计数）~~ **已做（2026-10-01 W3-f）**：中文 README 的准确率一节有 5 题 × 3 轮的明细表。

### 15.4 三条诚实缺口（写在这里，别在别处替项目承诺）

1. **§2 第 1 条的陌生人体感：有一个真人跑过了，而且当场炸（2026-10-01 W3-h）**。他照 README 在 cmd.exe 里走 `cp .env.example .env` → `mvn spring-boot:run`，拿到的是 `DEEPSEEK_API_KEY 未设置` —— 缺陷已修（见 §10 W3-h），且这是"我这台机器能起"永远覆盖不到的一条路径。所以现在的状态是：**首步已有一次真实外部验证 + 一次修复**，但样本量 1、且那个人是本项目的作者，**"新人 clone 后 10 分钟跑通"仍然没有数据**。首次在线拉依赖的耗时、以及你没有响应式经验时会卡在哪一步，都没数据。README 未承诺陌生人体感，别在别处替它承诺。
2. **~~CI 从未被 GitHub 执行过~~ 已消除（2026-10-01 W3-e）**：仓库推到 GitHub 之后 Actions 真跑了，run `36824220129` 日志 `Tests run: 88, Failures: 0, Errors: 0, Skipped: 1` + `BUILD SUCCESS`（15 秒），与本机 `mvn -o -B test` 数字一致。（2026-10-01 W3-h 之后是 run `36829504759` = `Tests run: 91, Failures: 0, Errors: 0, Skipped: 1`，本机同一数字。）这条缺口换成了一条**新的、更小的**：CI 只证明离线用例，评测分数依然只来自本机 `--eval` 真跑（CI 不持 key 的约束没变），所以 README 表里那三个数不在 CI 的保护范围内。
3. **15 道业务题的口径是代笔的**（`business.json` 里 `owner: qoder`）。所以 88%/92% 测的是"模型能否在一个定义良好的库里正确取数"，**不测**"口径含糊时人会不会被误导"。题面与我写的口径冲突时以口径为准 —— B07 就是这么一道没人能稳过的题。README 的准确率表按这个措辞。
