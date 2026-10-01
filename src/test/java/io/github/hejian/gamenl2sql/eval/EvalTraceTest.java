package io.github.hejian.gamenl2sql.eval;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.github.hejian.gamenl2sql.db.DbProperties;
import io.github.hejian.gamenl2sql.db.GameDatabase;
import io.github.hejian.gamenl2sql.eval.EvalTrace.Step;
import io.github.hejian.gamenl2sql.guard.SqlGuard;
import io.github.hejian.gamenl2sql.hitl.ConfirmProperties;
import io.github.hejian.gamenl2sql.hitl.ConfirmProperties.Mode;
import io.github.hejian.gamenl2sql.hitl.ConfirmationGate;
import io.github.hejian.gamenl2sql.tool.RunSqlTool;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 判分逻辑的离线验证：{@link EvalTrace#matchCell} 解析的是 {@code RunSqlTool} 的输出文本，
 * 两边格式一改就得同步改，否则 25 道题会全部判成"数字不对"而没有任何报错。
 * 所以这里走真 Toolkit + 真护栏 + 真 SQLite（不联网、auto_approve）产文本，不手写样例字符串。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvalTraceTest {

    @TempDir
    static Path dir;

    private static GameDatabase database;
    private static Toolkit toolkit;

    @BeforeAll
    static void buildDatabase() {
        database = new GameDatabase(new DbProperties(dir.resolve("game.db").toString(), false, 10));
        database.initialize(false);
        toolkit = new Toolkit();
        toolkit.registerTool(new RunSqlTool(new SqlGuard(200),
                new ConfirmationGate(new ConfirmProperties(5, Mode.AUTO_APPROVE)), database));
    }

    @Test
    void readsBackTheRealToolOutputAsOneStepAndOneNumber() throws Exception {
        List<Step> steps = stepsViaTool("select count(*) from srv");
        int realRows = ((Number) database.query("select count(*) from srv").rows().get(0).get(0)).intValue();

        assertThat(steps).hasSize(1);
        Step step = steps.get(0);
        assertThat(step.kind()).isEqualTo(EvalTrace.Kind.EXECUTED);
        assertThat(step.modelSql()).isEqualTo("select count(*) from srv");
        // 执行文本是护栏重写后的那条（补空格 + 注入 LIMIT），不是模型原文
        assertThat(step.executedSql()).isEqualTo("SELECT count(*) FROM srv LIMIT 200");
        assertThat(EvalTrace.firstRowCells(step.text()))
                .extracting(BigDecimal::toPlainString).containsExactly(String.valueOf(realRows));
        assertThat(EvalTrace.lastExecuted(steps)).isSameAs(step);
    }

    @Test
    void guardAndHumanRejectionsAreDistinguishableBuckets() {
        assertThat(stepsViaTool("delete from acct").get(0).kind()).isEqualTo(EvalTrace.Kind.GUARD_REJECTED);
        assertThat(stepsViaTool("select count(*) from sqlite_master").get(0).kind())
                .isEqualTo(EvalTrace.Kind.GUARD_REJECTED);
        assertThat(stepsViaTool("select count(*) from srv where no_such_column = 1").get(0).kind())
                .isEqualTo(EvalTrace.Kind.EXEC_ERROR);
        assertThat(EvalTrace.lastExecuted(List.of())).isNull();
    }

    @Test
    void matchesAnyNumericCellOfTheFirstRowNotJustTheFirstOne() {
        // B10/T01 首轮实测的形态：select srv_id, round(sum(..),2) ... limit 1 —— 期望数在第二列
        assertThat(EvalTrace.matchCell("执行 SQL: x\n返回 1 行 / 2 列\nsrv_id | hours\n105 | 2980.41",
                new BigDecimal("2980.41"), new BigDecimal("0.01"))).isEqualByComparingTo("2980.41");
        assertThat(EvalTrace.matchCell("执行 SQL: x\n返回 1 行 / 1 列\ncnt\n121",
                new BigDecimal("121"), BigDecimal.ZERO)).isEqualByComparingTo("121");
        // 只取首行：按天分组的表里没有那个总数，本该判错，不许在第二行里撞对
        assertThat(EvalTrace.matchCell("执行 SQL: x\n返回 3 行 / 2 列\nd | c\n01-25 | 120\n01-26 | 118\n01-27 | 121",
                new BigDecimal("121"), BigDecimal.ZERO)).isNull();
        assertThat(EvalTrace.matchCell("执行 SQL: x\n返回 1 行 / 2 列\nn | name\nNULL | S1区-龙渊",
                new BigDecimal("121"), BigDecimal.ZERO)).isNull();
    }

    @Test
    void givesUpOnNonNumericCellsAndEmptyResults() {
        assertThat(EvalTrace.firstRowCells("执行 SQL: x\n返回 1 行 / 3 列\na | b | c\n1 | 2.5 | S1区"))
                .extracting(BigDecimal::toPlainString).containsExactly("1", "2.5");
        assertThat(EvalTrace.firstRowCells("执行 SQL: x\n返回 1 行 / 1 列\nn\nNULL")).isEmpty();
        assertThat(EvalTrace.firstRowCells("执行 SQL: x\n返回 0 行 / 1 列\ncnt")).isEmpty();
        assertThat(EvalTrace.firstRowCells("护栏拒绝：xxx")).isEmpty();
        assertThat(EvalTrace.firstRowCells(null)).isEmpty();
    }

    /** 期望值要能对上一道题的真实结果，否则 25 道题的判分是悬空的。 */
    @Test
    void matchCellReproducesAnEvalExpectationThroughTheWholeChain() {
        List<Step> steps = stepsViaTool(
                "select count(distinct acct_id) from login_log where login_dt='2026-01-31'");

        assertThat(EvalTrace.matchCell(steps.get(0).text(), new BigDecimal("121"), BigDecimal.ZERO))
                .isEqualByComparingTo("121");
    }

    /** 实测：直接 callTool 返回的 ToolResultBlock 连 id 和 name 都不填，所以配对必须能按顺序兜底。 */
    @Test
    void resultBlocksDoNotCarryTheCallId() {
        ToolUseBlock use = call("select count(*) from srv");
        ToolResultBlock result = toolkit.callTool(
                ToolCallParam.builder().toolUseBlock(use).input(use.getInput()).build()).block();

        assertThat(result.getId()).isNull();
        assertThat(result.getName()).isNull();
        assertThat(EvalTrace.firstRowCells(EvalTrace.textOf(result.getOutput()))).isNotEmpty();
    }

    private static List<Step> stepsViaTool(String sql) {
        ToolUseBlock use = call(sql);
        ToolResultBlock result = toolkit.callTool(
                ToolCallParam.builder().toolUseBlock(use).input(use.getInput()).build()).block();
        Msg assistant = Msg.builder().role(MsgRole.ASSISTANT).content(use).build();
        Msg toolMsg = Msg.builder().role(MsgRole.TOOL).content(result).build();
        return EvalTrace.steps(List.of(assistant, toolMsg));
    }

    /** 模型那一侧发出来的调用块：content 是原始 JSON 串，Toolkit 拿它校验 schema。 */
    private static ToolUseBlock call(String sql) {
        String json = io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(Map.of("sql", sql));
        return ToolUseBlock.builder().id("call-1").name("run_sql")
                .input(Map.of("sql", sql)).content(json).build();
    }
}
