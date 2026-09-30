package io.github.hejian.gamenl2sql.guard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §6 护栏链第 1–3 步的离线单测 —— 不联网、不打库，是 CI 唯一必须跑的一类测试（§7）。
 * 每条拒绝用例都对应 §6 点名的一种绕过手法。
 */
class SqlGuardTest {

    private final SqlGuard guard = new SqlGuard(200);

    private GuardOutcome reject(String sql) {
        GuardOutcome outcome = guard.guard(sql);
        assertThat(outcome.allowed()).as("期望被拦: %s", sql).isFalse();
        assertThat(outcome.sql()).isNull();
        return outcome;
    }

    private GuardOutcome pass(String sql) {
        GuardOutcome outcome = guard.guard(sql);
        assertThat(outcome.rejection()).as("期望放行: %s (%s)", sql, outcome.detail()).isNull();
        assertThat(outcome.allowed()).isTrue();
        return outcome;
    }

    @Test
    void rejectsEmpty() {
        assertThat(reject("   ").rejection()).isEqualTo(Rejection.EMPTY);
        assertThat(reject(null).rejection()).isEqualTo(Rejection.EMPTY);
    }

    /** §6 的头号警告：解析器只吐出第一条语句，第二条静静留在原文里。放行文本必须来自 AST。 */
    @Test
    void dropsStatementsBeyondTheFirst() {
        GuardOutcome outcome = pass("select 1; delete from acct");
        assertThat(outcome.sql()).isEqualTo("SELECT 1 LIMIT 200");

        outcome = pass("select acct_id from acct; drop table acct; -- x");
        assertThat(outcome.sql()).doesNotContainIgnoringCase("drop");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "insert into acct values (1,'yx','2026-01-01 00:00:00',null,'2026-01-31',0)",
            "update acct set is_del=1",
            "delete from acct",
            "drop table acct",
            "create table x (a int)",
            "with t as (select 1 x) insert into srv select 1,2,t.x,3,4 from t"
    })
    void rejectsAnythingThatIsNotASelect(String sql) {
        assertThat(reject(sql).rejection()).isEqualTo(Rejection.NOT_SELECT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "PRAGMA table_list",
            "ATTACH ':memory:' AS x",
            "VACUUM",
            "not sql at all",
            "select from where"
    })
    void rejectsSqlTheParserCannotHandle(String sql) {
        // PRAGMA/ATTACH 之所以落在这里：JSqlParser 不认 SQLite 方言，解析失败即 fail-closed。
        assertThat(reject(sql).rejection()).isEqualTo(Rejection.PARSE_FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select * from sqlite_master",
            "select * from main.sqlite_master",
            "select 1 where exists(select 1 from pragma_function_list)",
            "select * from sqlite_sequence"
    })
    void rejectsSystemObjects(String sql) {
        assertThat(reject(sql).rejection()).isEqualTo(Rejection.SYSTEM_OBJECT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select * from users",
            "select * from information_schema.tables",
            "select a.acct_id from acct a join orders o on 1=1",
            "select * from acct where srv_id in (select srv_id from secret_table)",
            "select * from \"users\""
    })
    void rejectsAnyTableOutsideTheAllowlistIncludingSubqueries(String sql) {
        GuardOutcome outcome = reject(sql);
        assertThat(outcome.rejection()).isEqualTo(Rejection.UNKNOWN_TABLE);
        assertThat(outcome.detail()).isNotBlank();
    }

    @Test
    void tableNamesAreCaseAndQuotingInsensitive() {
        pass("SELECT * FROM ACCT");
        pass("select * from \"acct\"");
        pass("select * from main.acct");
        // 别名不算未授权表
        pass("select a.acct_id from acct a");
    }

    /** 默认方言不认识 SQLite 的方括号引用：解析失败 → fail-closed 拒绝，不是漏放。 */
    @Test
    void rejectsBracketQuotedIdentifiers() {
        assertThat(reject("select * from [acct]").rejection()).isEqualTo(Rejection.PARSE_FAILED);
    }

    @Test
    void cteAliasesAreNotTreatedAsTables() {
        GuardOutcome outcome = pass("with t as (select acct_id from acct) select count(*) from t");
        assertThat(outcome.sql()).startsWith("WITH t AS").contains("LIMIT 200");
    }

    @Test
    void injectsLimitWhenAbsent() {
        GuardOutcome plain = pass("select * from acct");
        assertThat(plain.limitAdded()).isTrue();
        assertThat(plain.sql()).endsWith("LIMIT 200");

        // 集合运算：LIMIT 必须挂在整个 union 上，而不是最后一个分支
        GuardOutcome union = pass("select acct_id from acct union all select role_id from role");
        assertThat(union.sql()).isEqualToIgnoringCase(
                "select acct_id from acct union all select role_id from role limit 200");
    }

    @Test
    void keepsSmallerLimitAndClampsLargerOne() {
        GuardOutcome kept = pass("select * from acct limit 5");
        assertThat(kept.limitAdded()).isFalse();
        assertThat(kept.sql()).contains("LIMIT 5").doesNotContain("200");

        GuardOutcome clamped = pass("select * from acct limit 1000000");
        assertThat(clamped.sql()).contains("LIMIT 200").doesNotContain("1000000");
    }

    @Test
    void keepsOffsetAndOrderByIntact() {
        GuardOutcome outcome = pass("select srv_id, sum(pay_amt) s from pay_ord group by srv_id order by s desc limit 10 offset 20");
        assertThat(outcome.sql()).contains("OFFSET 20").contains("ORDER BY");
    }

    /** 真实评测题必须过得了护栏，否则 B/T 系列的失败会被归因成"护栏拒绝"。 */
    @Test
    void acceptsTheShapeOfRealEvalQuestions() {
        pass("""
                select round(100.0*sum(hit)/sum(cnt),2) from (select substr(a.reg_time,1,10) d, \
                count(*) cnt, sum(exists(select 1 from login_log l where l.acct_id=a.acct_id \
                and l.login_dt=date(a.reg_time,'+1 day'))) hit from acct a \
                where substr(a.reg_time,1,10) between '2026-01-02' and '2026-01-08' group by d)
                """);
        pass("""
                select round(sum(o.pay_amt),2) from pay_ord o join season s on s.srv_id=o.srv_id \
                and s.season_id=1 where o.srv_id=101 and date(o.pay_time) between s.st_dt and s.ed_dt \
                and o.ord_st=1 and o.is_del=0
                """);
        pass("""
                select round(sum(l.dur_sec)/3600.0,2) from login_log l join srv s on s.srv_id=l.srv_id \
                where l.login_dt>='2026-01-01' and l.login_dt<'2026-02-01' \
                group by l.srv_id order by sum(l.dur_sec) desc limit 1
                """);
        pass("select lag(x,1) over (order by y) from acct");
    }
}
