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
  ⚠️ 代价：`ModelContextWindows.DEEPSEEK` 只登记了 `deepseek-v4-flash` / `deepseek-v4-pro`，**没有 `deepseek-flash`** → 实测 `model.getContextWindowSize()` 返回 **0**。框架若有按窗口做裁剪/压缩的逻辑，0 的语义要在 W1 第一天查清。
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
- **F 被已确认的缺陷挡在门外**：#3096（批准的运行执行完工具后丢掉结果）的修复 PR #3100 在 2026-09-11 才合入 main，**2.0.3 拿不到**；#3104（被拒的调用不吐 tool-result）同样未发版。加上 #3315/#3294/#3320/#2773 四个仍 open。D 完全不进 resume 这条路径。
- **C 会掏空学习目标①**：schema 全量进 prompt 之后，两段式的第一段不需要调任何工具，"agent"退化成一发带 JSON 约束的调用加 Java 胶水。上一轮为了补救曾打算加 `list_tables`/`describe_table` 强行制造调用需求 —— 那是为架构自洽而花 token 的假需求，D 让它没必要存在。
- 代价已知并接受：D 走的是 WebFlux 上最难的一条线（等人类点击的长挂起流）。需要独立线程池 / `Sinks` 之类的机制，**SQL 执行与等人都不许跑在事件循环线程上**；`run-timeout` 必须调大。响应式是你（Q43=②"见过但没写过"）为此多学的一样东西，不在 §1 三项验收里，账记在 §11。

**工具集只有一个**：`run_sql`。**schema 不进工具、全量进 prompt**（1M 上下文面前"装不下"不存在），这样模型答错的原因能收敛到"没读懂 DDL"或"护栏/prompt 有问题"两类，而不是"它没去查表"。

**步数上限 = 4**：用你的真实例子校准（§12 那道题，3 条 SQL + 1 次校验 = 4）。

**形状约束**（Day-0 已实测，2026-09-30）：DeepSeek 官方 **不支持** `response_format: json_schema` —— 请求直接 400 `This response_format type is unavailable now`。#2548 不是"网关偶发"，是 provider 能力缺失，所以**走退化路径为唯一路径**：prompt 钉死 JSON + Jackson 严格解析 + 重试 1 次，且**"形状不对"必须是独立于"没调工具"的失败类别**。实测 `response_format:{type:"json_object"}` 可用（200），但它只保证是合法 JSON、不保证字段形状，因此 Jackson 的 schema 校验不能省。

**provider 切换的纪律**：代码里不出现 `tool_choice:"required"`。**理由已更正**（Day-0 实测）：DeepSeek 在**思考档开着**时拒绝它（400 `Thinking mode does not support this tool_choice`），但 `thinking:{type:"disabled"}` + `required` 是**能用的**（200）。不用它的真实理由与 provider 无关 —— `required` 会连"给出最终答复"那一轮也强制调工具，Agent 收不了尾。换模型只改注册串 + 环境变量，不许散落硬编码。

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
3. **注入 LIMIT** → 未写 LIMIT 的强制加；模型自带更大的 LIMIT 会被压回上限。上限目前是 `SqlGuard` 的构造参数，下一步接 `game.guard.max-rows`。　✅
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

## 8. 会话状态

**自存**：按 `threadId` 维护一个 `{lastQuestion, lastSql, 口径}` 小结构，注入下一轮 prompt。**不开** `agentscope.agui.server-side-memory`。

理由：框架那个选项给你的是**全量消息历史**，而你要的是"只保留上一轮 SQL"——不是同一个东西。用框架实现你的目标，等于悄悄把会话形态换成被你否掉的长历史，代价是 token 上涨、指代歧义变多、**且那 5 道追问题的成绩会受历史长度干扰而不可比**。另外 #3291（builder 权限规则被快照进持久化 session）也让你不想把状态交给框架。

## 9. 工程结构

单模块 Maven（多模块解决的是"多个发布单元/依赖隔离"，你一个都不沾，代价是每次改包结构动 pom）。

```
pom.xml
src/main/java/...
  ├─ agent/ModelConfig       ← 启动 fail-fast：model 名非空且 != gpt-4.1-mini 且 key 非空，任一不满足则 Model bean 建立失败、进程拒绝启动。**联网探活不在启动路径里**（否则 §10 的 CI 不持 key 与 @SpringBootTest 一起破），由 `DeepSeekModelSmokeTest` 承担
  ├─ agent/                  ← ReActAgent bean、system prompt、schema 注入
  ├─ tool/                   ← run_sql
  ├─ guard/                  ← 护栏四步链（纯函数，可离线测）
  ├─ hitl/                   ← 确认门状态机 + Sinks + 超时
  ├─ db/                     ← SQLite 只读连接、schema/seed 初始化、--reset-db
  ├─ eval/                   ← --eval、断言、归因分类、结果 JSON
  └─ web/                    ← AG-UI 之外自加的确认决策 POST 端点
src/main/resources/
  ├─ application.yml
  ├─ schema/  ← game DDL + seed
  ├─ eval/    ← 25 题（含口径声明）
  └─ static/  ← 抄 examples/agui 的 index.html + agui-client.js，保留原版权头
```

- `.gitignore`：`.env`、`*.db`、`*-wal`/`*-shm`、`target/`、IDE 目录。评测结果 JSON **不 ignore**。
- `.env.example` 进仓库，**key 只走环境变量**，git 历史里永不出现明文 key。
- 抄来的 demo 页保留原文件头版权声明 + README 注明来自官方 examples（你自己选了 Apache-2.0，混进来源不明的代码是给下游埋合规雷）。

## 10. 里程碑与门禁

**Day-0（约 3 小时，全是"证伪前提"）** —— 四件全绿才许进 W1。**2026-09-30 四件全绿，Day-0 关闭**：
1. ✅ DeepSeek 官方域名最小 tool call 成功（个人 key，已存 `.env`，git 已 ignore）。
2. ✅ **模型 ID 实测**：`GET /models` 只返回两个 —— `deepseek-flash`（显示名 DeepSeek-V4.1-Flash）与 `deepseek-v4-pro`。**AgentScope 文档示例里的 `deepseek-v4-flash` 不存在**，照抄会 400。两者 context 1M、max output 393216、默认 effort=high。
   > **根因（门禁④ 反编译时找到）**：`deepseek-v4-flash` 不是文档笔误，是框架 `ModelContextWindows.DEEPSEEK` 表里的硬编码键 —— 那份表停在旧命名，而 `deepseek-flash` 反而不在表里。这是上游可提的 issue（§10 W3 那条一并带上）。
3. ✅ 结构化输出：**不可用**（见 §4"形状约束"）。
4. ✅ **Boot 空壳 + agent bean 注册（2026-09-30 跑通，四件全绿，可进 W1）**：
   - `mvn -B test` → `AgentBeanRegistrationTest` 绿：`openAIChatModel`(`OpenAIChatModel`)、`agentscopeReActAgent`(`ReActAgent`)、`Toolkit`、`InMemoryMemory` 四个 bean 全部注册。
   - `mvn spring-boot:run --server.port=18080` → `Netty started on port 18080`，`Started App in 2.478 seconds`（`/` 返回 404 属正常，还没有路由）。Boot **4.0.4 实测可用**，但理由见 §3 修正：**不是 starter 锁的，是我们 pom 定的**。
   - 附加探活 `DeepSeekModelSmokeTest`（无 `DEEPSEEK_API_KEY` 自动跳过，符合 §10 CI 不持 key 的约束）：`ModelRegistry.resolve("deepseek:deepseek-flash", ctx{stream=false, enableThinking=false})` → 返回 `2`、`finishReason=stop`、`modelName=deepseek-flash`、`supportsNativeStructuredOutput()=false`、`contextWindowSize=0`。**框架→DeepSeek 这条链路通了**（此前门禁① 只证明了裸 curl）。
   - 依赖互操作实测：`reactor-core` 解析到 **3.8.4**（≥ 框架编译期 3.8.2，无降级）；Boot 4 用 **Jackson 3**（`tools.jackson.core:jackson-databind:3.1.0`）而框架用 **Jackson 2.21.1**，坐标不同故**并存不冲突**——但我的代码必须显式 import `com.fasterxml.*` 才和框架的消息/工具 schema 序列化对齐，混用会静默失效。
   - ✅ 窗口函数已实测可跑（SQLite 3.53.4，`lag() over (...)` 正常）。

> **实测附带发现（会影响 §4 的步数上限）**：一次 turn 里模型**自发返回了 2 个 `tool_calls`**（并行工具调用无文档但实际会发生）。`max_tokens` 打满时 `finish_reason` 会是 `length` 且 `reasoning_tokens` 吃掉全部输出，所以探活脚本必须给足 max_tokens，否则会把"输出被截断"误判成"模型不会调工具"。

> **为什么 Day-0 有这道门禁**：本项目的一个前提（"我手上有能调通的配置"）在本轮访谈中被证伪过一次 —— `api.agnes.ai` 域名不解析、`qwen3-coder-plus` 不在该服务商模型列表里，也就是那份配置从未成功过。所以"配置未验证就开工"是已知会复发的失败模式。

**W1**：Boot 4.0.4 骨架 → SQLite schema/seed 生成 → `run_sql` + 护栏四步（含离线单测）→ **挂起式确认门跑通 + 一条集成测试**（approve / edit / deny 三条都验）。
> **进度（2026-09-30）**：✅ **W1-a/W1-b 完成**。`ModelConfig` 用 `ModelRegistry.resolve("deepseek:" + game.model.name, ctx)` 自建 `Model` bean，`agentscope.openai.enabled: false` 已把 starter 那条自动装配关掉。
> 实测确认了三件之前只是推断的事：① 换路之后 `Model` 的实现类**仍是** `OpenAIChatModel` —— SPI 只是套壳，白赚的是 `DeepSeekFormatter` + `thinking` 参数 + `nativeStructuredOutput=false`，不是另一个客户端实现，别在 README 里写错；② base starter 的 `agentscopeReActAgent` 正常吃到了我们自建 bean（`@ConditionalOnBean(Model)` 成立），四 bean 断言仍绿；③ `contextWindow=0` 已进启动日志，观察是否有静默裁剪。
> fail-fast 三条单测（缺 key / 缺 model 名 / 名字还是 `gpt-4.1-mini`）均拒绝启动；带 key 的 smoke 测试重跑仍真调通。
> ✅ **W1-c 前半：护栏第 1–3 步落地**（`guard/SqlGuard`+`Rejection`+`GuardOutcome`，29 条离线用例，CI 可跑）。实测推翻了 §6 原本预设的"检测多语句"路线，见 §6 末的实测清单 —— **一句话：执行文本必须取 AST 重新序列化的结果，绝不能用模型原文。**
> **W1 剩余**：`run_sql` 工具本身、挂起式确认门。
> ✅ **W1-c 后半：`db/` 落地**（`GameDatabase` + `SqlScript` + `DbProperties`，12 条离线用例）。整库 1MB seed 走 JDBC 会 `SQLITE_TOOBIG`，因此有了切分器；建库后强制验 8 张表，因为 SQLite 对错误路径会静默建空库。**最有价值的一条**：`GameDatabaseTest` 把 25 道题的 `truthSql` 全跑了一遍并逐条对上 `expect.value` —— 从此 §2 的"clone 下来 10 分钟能跑通"不依赖 sqlite3 CLI 也不依赖 Python，CI 就能证。细节见 §5 末实测清单。
**W2**：`--eval` + 25 题 → 第一个真实准确率 → AG-UI 页面抄改 → 追问 5 题的状态传递 → **半天 throwaway 实验**：单独测框架原生 permission+resume，确认 #3096 到底在哪一层复现，**结论作为 issue 提给上游**。
**W3**：README（中英）+ GIF + 准确率表 → 模型 ID 那条 issue → 收尾。
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
- 缺陷：#3315/#3294/#3320/#2773/#3369/#3291（HITL 与权限）、#3096+#3100/#3104（已修未发版）、#2696/#2548/#3301（结构化输出）、#3353/#3209/#3057（工具调用）。
- 模型：`api-docs.deepseek.com`（模型枚举、定价、限流 2500/500、tool_calls 约束）。
- 已废弃并从计划中删除：Agnes 网关（`api.agnes.ai` NXDOMAIN；`qwen3-coder-plus` 不在其模型列表；免费档可能用输入做训练数据；约 20 RPM 上限）。
