package io.github.hejian.gamenl2sql.agent;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 离线断言"该进 prompt 的事实都进了"，不需要 key、不需要库。 */
class SystemPromptTest {

    private static final String DDL = "CREATE TABLE acct (\n  acct_id INTEGER PRIMARY KEY  -- 账号ID\n);";

    private final String prompt = SystemPrompt.render(DDL, LocalDate.parse("2026-01-31"), 4);

    @Test
    void embedsTheSchemaVerbatim() {
        assertThat(prompt).contains(DDL);
    }

    @Test
    void pinsTheClockAndTheStepBudget() {
        assertThat(prompt).contains("今天 = 2026-01-31").contains("2025-11-01");
        // 上限同时进 prompt 和 builder，模型才知道自己只有这么多步
        assertThat(prompt).contains("最多 4 步");
    }

    @Test
    void statesTheCalibersThatTheEvalSetAssumes() {
        assertThat(prompt)
                .contains("ord_st=1 且 is_del=0")
                .contains("退款单（ord_st=2）的 pay_amt 仍是正数")
                .contains("login_log 和 item_flow 没有 is_del")
                .contains("substr(col,1,10)")
                .contains("闭左开右 [起, 止)")
                .contains("ROUND(SUM(x),2)")
                .contains("count(distinct acct_id)");
    }

    @Test
    void forcesToolUseAndTheFourPartAnswerShape() {
        assertThat(prompt).contains("必须先调用 run_sql");
        assertThat(prompt).contains("# 输出格式").contains("口径：");
        // §4 的失败归因里"护栏拒绝 / 人拒绝"要能被模型如实转述，而不是编个数
        assertThat(prompt).contains("不许换个说法把数字猜出来");
    }

    @Test
    void answersMustQuoteTheSqlThatActuallyRan() {
        // 实测缺陷：人编辑过 SQL 后，工具返回的是改写并护栏重写的那条，模型答复里却抄自己原来那条。
        // prompt 只能要求"逐字复制工具返回的执行 SQL 行"，这里是这条要求的锚点断言。
        assertThat(prompt).contains("逐字复制").contains("执行 SQL:");
    }
}
