package io.github.hejian.gamenl2sql.tool;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.github.hejian.gamenl2sql.db.DbProperties;
import io.github.hejian.gamenl2sql.db.GameDatabase;
import io.github.hejian.gamenl2sql.guard.SqlGuard;
import io.github.hejian.gamenl2sql.hitl.ConfirmProperties;
import io.github.hejian.gamenl2sql.hitl.ConfirmProperties.Mode;
import io.github.hejian.gamenl2sql.hitl.ConfirmationGate;
import io.github.hejian.gamenl2sql.hitl.Decision;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §4 决定 D 的落地验证：护栏 → 挂起确认 → 执行。
 * 走真正的 {@link Toolkit}（注解、反射、reactive 转换都在线上），但不联网、不需要 API key。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunSqlToolTest {

    @TempDir
    static Path dir;

    private static GameDatabase database;

    @BeforeAll
    static void buildDatabase() {
        database = new GameDatabase(new DbProperties(dir.resolve("game.db").toString(), false, 10));
        database.initialize(false);
    }

    private static Toolkit toolkit(ConfirmationGate gate) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new RunSqlTool(new SqlGuard(200), gate, database));
        return toolkit;
    }

    private static ConfirmationGate gate(Mode mode, int timeoutSeconds) {
        return new ConfirmationGate(new ConfirmProperties(timeoutSeconds, mode));
    }

    @Test
    void exposesExactlyOneReadOnlyTool() {
        Toolkit toolkit = toolkit(gate(Mode.AUTO_APPROVE, 5));
        assertThat(toolkit.getToolNames()).containsExactly("run_sql");
        assertThat(toolkit.getToolSchemas()).hasSize(1);
        assertThat(toolkit.getTool("run_sql").isReadOnly()).isTrue();
    }

    @Test
    void guardrailRejectsWithoutBotheringTheHuman() throws Exception {
        ConfirmationGate gate = gate(Mode.HUMAN, 5);
        Call call = new Call(toolkit(gate), gate);
        call.run("delete from acct");

        assertThat(call.awaitText()).startsWith("护栏拒绝").contains("只允许执行 SELECT 查询");
        assertThat(gate.pendingSnapshot()).isEmpty();
    }

    @Test
    void approvedSqlExecutes() throws Exception {
        ConfirmationGate gate = gate(Mode.HUMAN, 5);
        Call call = new Call(toolkit(gate), gate);
        call.run("select count(distinct acct_id) from login_log where login_dt='2026-01-31'");

        ConfirmationGate.Request request = call.awaitRequest();
        // 推给人看的必须是护栏改写后的那条，不是模型原文：解析器会重写空格并补 LIMIT
        assertThat(request.sql()).contains("login_dt = '2026-01-31'").endsWith("LIMIT 200");
        assertThat(gate.decide(request.id(), Decision.approve())).isTrue();

        assertThat(call.awaitText()).contains("执行 SQL:").contains("121");
    }

    @Test
    void humanEditReplacesTheSqlAndStillGoesThroughGuardrail() throws Exception {
        ConfirmationGate gate = gate(Mode.HUMAN, 5);
        Call call = new Call(toolkit(gate), gate);
        call.run("select count(*) from acct");
        gate.decide(call.awaitRequest().id(), Decision.edit("select count(*) from srv"));
        // 5 个区服，不是 300 个账号
        assertThat(call.awaitText()).contains("FROM srv").doesNotContain("300");

        ConfirmationGate other = gate(Mode.HUMAN, 5);
        Call rejected = new Call(toolkit(other), other);
        rejected.run("select count(*) from acct");
        other.decide(rejected.awaitRequest().id(), Decision.edit("drop table acct"));
        assertThat(rejected.awaitText()).startsWith("人改写的 SQL 没有通过护栏");
    }

    @Test
    void denialIsReportedBackWithTheReason() throws Exception {
        ConfirmationGate gate = gate(Mode.HUMAN, 5);
        Call call = new Call(toolkit(gate), gate);
        call.run("select count(*) from acct");
        gate.decide(call.awaitRequest().id(), Decision.deny("口径不对，净付费要排退款"));
        assertThat(call.awaitText()).contains("人拒绝执行").contains("口径不对");
    }

    @Test
    void timeoutCountsAsDenial() throws Exception {
        ConfirmationGate gate = gate(Mode.HUMAN, 1);
        Call call = new Call(toolkit(gate), gate);
        call.run("select count(*) from acct");
        call.awaitRequest();
        assertThat(call.awaitText()).contains("1 秒内没有确认").contains("按拒绝处理");
        assertThat(gate.pendingSnapshot()).isEmpty();
    }

    @Test
    void decidingTwiceIsRefused() throws Exception {
        ConfirmationGate gate = gate(Mode.HUMAN, 5);
        Call call = new Call(toolkit(gate), gate);
        call.run("select count(*) from acct");
        String id = call.awaitRequest().id();
        assertThat(gate.decide(id, Decision.approve())).isTrue();
        assertThat(gate.decide(id, Decision.deny("反悔了"))).isFalse();
        call.awaitText();
    }

    @Test
    void autoApproveModeNeverSuspends() throws Exception {
        ConfirmationGate gate = gate(Mode.AUTO_APPROVE, 5);
        Call call = new Call(toolkit(gate), gate);
        call.run("select count(*) from acct");
        assertThat(call.awaitText()).contains("300");
        assertThat(gate.pendingSnapshot()).isEmpty();
    }

    @Test
    void executionErrorsComeBackAsTextNotExceptions() throws Exception {
        ConfirmationGate gate = gate(Mode.AUTO_APPROVE, 5);
        Call call = new Call(toolkit(gate), gate);
        // 过得了护栏、但 SQLite 不认这个函数
        call.run("select no_such_function(acct_id) from acct");
        assertThat(call.awaitText()).startsWith("执行失败");
    }

    /** 一次工具调用：异步发起，用 gate 的快照拿确认请求（不用多播订阅，避免时序竞态）。 */
    private static final class Call {
        private final Toolkit toolkit;
        private final ConfirmationGate gate;
        private final AtomicReference<String> text = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CountDownLatch finished = new CountDownLatch(1);

        Call(Toolkit toolkit, ConfirmationGate gate) {
            this.toolkit = toolkit;
            this.gate = gate;
        }

        void run(String sql) {
            // Toolkit 会拿 ToolUseBlock.content（模型侧的原始 JSON）去校验 schema，
            // 只填 input 不行；这里照模型发过来的形状造。
            String json = JsonUtils.getJsonCodec().toJson(Map.of("sql", sql));
            var call = ToolUseBlock.builder()
                    .id("test-call").name("run_sql")
                    .input(Map.of("sql", sql)).content(json)
                    .build();
            toolkit.callTool(ToolCallParam.builder().toolUseBlock(call).input(call.getInput()).build())
                    .map(Call::textOf)
                    .subscribe(text::set, failure::set, () -> finished.countDown());
        }

        ConfirmationGate.Request awaitRequest() throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                List<ConfirmationGate.Request> pending = gate.pendingSnapshot();
                if (!pending.isEmpty()) {
                    return pending.get(0);
                }
                assertThat(finished.await(50, TimeUnit.MILLISECONDS)).as("工具提前结束了").isFalse();
            }
            throw new IllegalStateException("没等到确认请求，结果=" + text.get() + " 异常=" + failure.get());
        }

        String awaitText() throws InterruptedException {
            assertThat(finished.await(10, TimeUnit.SECONDS)).as("工具调用没在期限内结束").isTrue();
            assertThat(failure.get()).isNull();
            return text.get();
        }

        private static String textOf(ToolResultBlock block) {
            return block.getOutput().stream()
                    .filter(TextBlock.class::isInstance)
                    .map(block2 -> ((TextBlock) block2).getText())
                    .reduce("", (a, b) -> a + b);
        }
    }
}
