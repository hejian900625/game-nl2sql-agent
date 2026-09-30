package io.github.hejian.gamenl2sql.tool;

import io.github.hejian.gamenl2sql.db.GameDatabase;
import io.github.hejian.gamenl2sql.guard.GuardOutcome;
import io.github.hejian.gamenl2sql.guard.SqlGuard;
import io.github.hejian.gamenl2sql.hitl.ConfirmationGate;
import io.github.hejian.gamenl2sql.hitl.Decision;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 唯一暴露给模型的工具（PLAN §4）。顺序是护栏 → 确认 → 执行：
 * 护栏不过当场拒，不去打扰人；人的编辑也要再过一次护栏，因为安全不能依赖人注意。
 */
@Component
public class RunSqlTool {

    private static final Logger log = LoggerFactory.getLogger(RunSqlTool.class);

    private final SqlGuard guard;
    private final ConfirmationGate gate;
    private final GameDatabase database;

    public RunSqlTool(SqlGuard guard, ConfirmationGate gate, GameDatabase database) {
        this.guard = guard;
        this.gate = gate;
        this.database = database;
    }

    @Tool(name = "run_sql",
            description = """
                    执行一条只读 SELECT，返回表格。一次调用只允许一条语句。
                    只能用这些表：srv, acct, role, goods, pay_ord, login_log, item_flow, season。
                    算净付费必须同时满足 ord_st=1 且 is_del=0（退款单的 pay_amt 仍是正数，靠 ord_st=2 区分）。
                    pay_ord / login_log 有 is_del，但 login_log 和 item_flow 没有 is_del 这一列，别加这个条件。
                    时间列有两种格式：reg_time / create_time / pay_time 带时分秒，日期列是 YYYY-MM-DD。
                    """,
            readOnly = true,
            converter = PlainTextResultConverter.class)
    public Mono<String> runSql(
            @ToolParam(name = "sql", description = "要执行的单条 SELECT 语句") String sql) {
        GuardOutcome outcome = guard.guard(sql);
        if (!outcome.allowed()) {
            return Mono.just(guardRejection(outcome));
        }
        return gate.await(outcome.sql()).flatMap(decision -> execute(outcome, decision));
    }

    private Mono<String> execute(GuardOutcome guarded, Decision decision) {
        if (!decision.approved()) {
            return Mono.just("人拒绝执行这条 SQL：" + decision.reason() + "。请换一条查询，或直接向用户说明无法取得数据。");
        }
        String sql = guarded.sql();
        if (decision.edited()) {
            GuardOutcome edited = guard.guard(decision.sql());
            if (!edited.allowed()) {
                return Mono.just("人改写的 SQL 没有通过护栏（" + edited.rejection().reason()
                        + "：" + edited.detail() + "），本次不执行。");
            }
            sql = edited.sql();
        }
        final String executable = sql;
        return Mono.fromCallable(() -> database.query(executable))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> {
                    log.info("[run_sql] {} -> {} 行", executable, result.rows().size());
                    return format(executable, result);
                })
                .onErrorResume(SQLException.class, e -> Mono.just("执行失败：" + e.getMessage()));
    }

    private String guardRejection(GuardOutcome outcome) {
        return "护栏拒绝：" + outcome.rejection().reason()
                + (outcome.detail() == null ? "" : "（" + outcome.detail() + "）")
                + "。只允许对 " + SqlGuard.ALLOWED_TABLES + " 的单条 SELECT。";
    }

    private static String format(String sql, GameDatabase.QueryResult result) {
        StringBuilder text = new StringBuilder();
        text.append("执行 SQL: ").append(sql).append('\n');
        text.append("返回 ").append(result.rows().size()).append(" 行 / ")
                .append(result.columns().size()).append(" 列\n");
        text.append(String.join(" | ", result.columns())).append('\n');
        for (List<Object> row : result.rows()) {
            text.append(row.stream().map(v -> v == null ? "NULL" : String.valueOf(v))
                    .collect(Collectors.joining(" | "))).append('\n');
        }
        return text.toString().stripTrailing();
    }
}
