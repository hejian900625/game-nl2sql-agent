# game-nl2sql · 带护栏和人审的中文自然语言问数 Agent

[中文](README.md) | [English](README.en.md)

![人把 SQL 从 `> 3` 改成 `> 5` 后执行](docs/demo.gif)

用 **AgentScope Java 2.0.3 + DeepSeek** 做的只读 NL→SQL agent：中文提一句运营问题 → 模型写 SQL → **四步护栏**改写/拒绝 → 停在**人工确认门**上（人可以直接改语句）→ 执行 → 表格 + 结论 + 口径说明。

GIF 里那一帧是本项目最想让人看的画面：确认卡片里人把 `login_cnt > 3` 改成 `> 5`，执行的是改后那条，答复里贴的也是改后那条 —— 并且模型主动指出"这与题面 `>3 次` 口径不符，不能作为答案"。**审的语句和跑的语句必须是同一条**，否则人审就是装饰。

## 为什么值得往下看

三件不是"调个 API 就能吹"的东西：

1. **护栏四步**（`guard/SqlGuard`）：JSqlParser 解析成 AST → 表白名单 → 注入 `LIMIT` → 只读连接。全程不用正则；`startsWith("SELECT")` 这类判断在这里一条都没有。SQLite 没有只读账号，所以第四道是 JDBC 打开模式 + AST 检查，不是数据库权限。
2. **挂起式确认门**（`hitl/ConfirmationGate`）：确认发生在**工具内部**——`run_sql` 返回一条挂起的响应式流，等人点，超时即视为拒绝。所以一次 agent run 是连续的循环，不需要框架的 pause/resume（AgentScope 2.0.3 那条路有未发版的 bug，见"已知问题"）。
3. **25 道题的评测集**（`src/main/resources/eval/`）：每题带口径声明和一条 `truthSql`，期望值全部由 SQL 现算（`tools/verify_eval.py`），没有一个是手打的数字。判分只看数值，不比 SQL 字符串。

## 跑起来

前置只有 **JDK 21** 和 **Maven 3.9+**。

```bash
git clone <this repo> && cd java-agent
cp .env.example .env        # 填 DEEPSEEK_API_KEY
mvn spring-boot:run         # 依赖已缓存时，到 Started App 约 10 秒；data/game.db 由 schema+seed 现场生成（实测 262 条语句 / 1.2 秒）
```

打开 <http://localhost:8080> 就是上面那个页面（端口被占就加 `-Dspring-boot.run.jvmArguments="-Dserver.port=18086"`）。想换库里的"今天"（相对时间题的锚点）用 `--game.agent.today=2026-01-31`；想重建数据库用 `--game.db.reset=true`。

**没有 key 也能验证一大半**：`mvn test` 有 88 条用例，其中 87 条纯离线（护栏、建库、确认门的 approve/edit/deny/超时、prompt 内容、AG-UI 路由与会话隔离、评测判分算术、框架 pause/resume 的两层探针、DeepSeek 模型名与框架窗口表的实测锚点），剩下 1 条是需要 key 的探活、无 key 时自动 skip。key 只从环境变量进，`.env` 已 gitignore，git 历史里不会出现明文。

## 准确率

模型 `deepseek-flash`（非思考档），同一份库、同一套题，唯一变量是模型采样：

| 轮次 | prompt | 严格口径 | 宽松口径 | 判错的题 |
|---|---|---|---|---|
| `eval-20261001-102834` | v1 | 88.0% (22/25) | 92.0% (23/25) | B03、B05、B07 |
| `eval-20261001-103058` | v1 | 88.0% (22/25) | 92.0% (23/25) | B04、B05、B07 |
| `eval-20261001-113017` | v2 | 84.0% (21/25) | 88.0% (22/25) | A03、B03、B05、B07 |

- **严格口径**：期望值出现在最后一次成功 `run_sql` 结果首行的任一数值单元格里（金额题各带自己的 `tol`，多数 0.01；没写的题容差为 0）。
- **宽松口径**：再加上"单元格里没有、但答复文字里给出了期望值"的题——留存率这类题模型分两步查、最后用文字做除法（`27 / 40 = 67.50%`），商永远不在单元格里。两个数一起报，不挑好看的。
- **别拿单轮总分比改进。** v1 的两轮同为 22/25 但错的题不同（B03 vs B04），说明 ±4 个点是采样噪声；v2 那轮多错一道 A03（模型把年份写成 2025），同时 B04 又对了。三轮里唯一稳定失败的是 B07，而那道题的题面和它自己的口径声明互相矛盾——缺陷在题目侧，见 `PLAN.md` §7。
- 8 道留存/流失题单列在结果 JSON 里（`accuracyRetention` / `accuracyExcludingRetention`），它们失败不算项目失败。
- **四类失败计数**（三轮都一样，所以单独列一次）：数字错 3 / 4，**未调用工具 0、护栏拒绝 0、人拒绝 0**（`--eval` 跑在 `auto_approve` 下，人这一层本来就不参与）。也就是说模型目前不是"不干活"或"乱干活"，而是口径与年份层面的错数——这四类是分开的病，混在一个准确率里会看不出该修哪个。

复现：

```bash
mvn spring-boot:run \
  -Dspring-boot.run.arguments=--eval \
  -Dspring-boot.run.jvmArguments="-Dgame.confirm.mode=auto_approve"
```

`--eval` 在 `game.confirm.mode != auto_approve` 时**拒绝启动**（25 道题不可能每题手点一次）。结果 JSON 直接提交进 `eval-results/`。注意 `-Dspring-boot.run.arguments` 里的逗号不会被拆开，多个程序参数请分开传。

## 结构

```
src/main/java/.../
  ├─ agent/    ModelConfig（走 DeepSeek SPI，启动 fail-fast）· SystemPrompt（运行时注入 schema）· AgentFactory · AG-UI 注册 factory
  ├─ tool/     run_sql（唯一工具）
  ├─ guard/    SqlGuard 四步链，纯函数
  ├─ hitl/     ConfirmationGate（Sinks.one 挂起 + 超时即拒）
  ├─ db/       GameDatabase：由 classpath 的 schema+seed 建库，只读连接
  ├─ eval/     EvalSet / EvalRunner / EvalCommand（--eval）
  └─ web/      /api/confirm/stream · /pending · /{id}（AG-UI 之外自加的人审端点）
```

会话状态用框架的 `server-side-memory`：AG-UI 注册的是 **factory**，`ThreadSessionManager` 按 `(userId, threadId)` 造并复用 agent 实例——所以同一个标签页能追问指代，不同标签页互不污染。评测不走这条路（每题 `create()` 一个新实例），否则 25 道题会互相看见上一题的 SQL。

数据是**造的游戏域**（8 张表、故意保留国内业务系统的列名风格和中文注释），金额单位是元（REAL），没有任何真实客户数据。

## 已知问题（照直说）

- **AgentScope 2.0.3 的原生 HITL resume 在 AG-UI 层丢事件**（上游 #3096，已在本地复现）：批准之后工具**会**执行、结果**会**进第二次模型调用，但 `TOOL_CALL_RESULT`/`TOOL_CALL_END` 一条都不发到 SSE 流上（stream context 每个 run 新建），按事件流渲染的前端会一直等不到结果。修复 PR #3100 已合入 main，至今未发版。实测、机制与可迁移条件写在 [`docs/hitl-pause-resume-experiment.md`](docs/hitl-pause-resume-experiment.md)；本项目把确认门做在工具内部，工具调用与结果发生在同一个 run 里，所以不受这条影响。
- **DeepSeek 不支持 `response_format: json_schema`**（官方端点实测 400），所以本项目不依赖框架的结构化输出。
- **AgentScope 2.0.3 的 DeepSeek 模型名表是旧的**：`ModelContextWindows.DEEPSEEK` 里只有 `deepseek-v4-flash` / `deepseek-v4-pro`，没有官方现在的正式名 `deepseek-flash`，于是用正式名建出来的 model `getContextWindowSize()` 返回 **0**（旧别名反而看着正常，因为 DeepSeek 服务端把它当别名收）。2.0.3 内核没有任何路径读这个值，所以今天不影响功能，但别照框架文档里的模型名写配置。实测与上游 note 见 [`docs/upstream-deepseek-model-ids.md`](docs/upstream-deepseek-model-ids.md)。
- **页面不渲染 Markdown**（GIF 里能看到 `**` 和 ``` 原样），因为演示页抄的是官方 `examples/agui` 的极简实现。
- **`.github/workflows/ci.yml` 还没被真正执行过**——本仓库尚未推到远端。它按"CI 不持 key"的约束写，只该跑离线用例。
- 评测的 25 道题里 15 道业务题的口径是**代笔**的（`owner: qoder`），不是真实业务方声明；题面与口径冲突时以口径为准，这类题（B07）模型没人能稳过。

## 许可与来源

Apache-2.0，见 [LICENSE](LICENSE)。

`src/main/resources/static/index.html` 与 `static/js/agui-client.js` 改编自 AgentScope 官方 `examples/agui`，保留原文件版权头；改动是删掉官方那套前端工具 `request_approval`，换成上面那个自加的确认卡片。

`PLAN.md` 是设计决策记录（14 节，含每个决定的理由与被实测推翻的过程）。想挑口径或改题，从 §13 开始。
