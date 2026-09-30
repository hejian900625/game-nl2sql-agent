package io.github.hejian.gamenl2sql.db;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 切分器存在的理由见 SqlScript 的 javadoc：seed 文件超过 SQLITE_MAX_SQL_LENGTH。 */
class SqlScriptTest {

    @Test
    void splitsOnSemicolonsOutsideStringsAndComments() {
        assertThat(SqlScript.split("select 1; select 2;;  ")).containsExactly("select 1", " select 2");
    }

    @Test
    void keepsSemicolonsInsideStringLiterals() {
        assertThat(SqlScript.split("insert into t values ('a;b'); select 1"))
                .containsExactly("insert into t values ('a;b')", " select 1");
    }

    /** '' 是一个引号的转义，不是"字符串结束又立刻开始"。判错会把后面所有语句边界整个错位。 */
    @Test
    void keepsDoubledQuoteInsideStringLiteral() {
        assertThat(SqlScript.split("insert into t values ('it''s; here'); select 1"))
                .containsExactly("insert into t values ('it''s; here')", " select 1");
    }

    @Test
    void keepsSemicolonsInsideComments() {
        assertThat(SqlScript.split("-- 说明; 这里的分号不算\nselect 1; /* 块; 注释 */ select 2"))
                .containsExactly("-- 说明; 这里的分号不算\nselect 1", " /* 块; 注释 */ select 2");
    }

    @Test
    void keepsDoubleQuotedIdentifiers() {
        assertThat(SqlScript.split("select * from \"odd;name\"; select 1"))
                .containsExactly("select * from \"odd;name\"", " select 1");
    }

    @Test
    void everyRealSeedStatementFitsSqliteLimit() {
        // SQLite 默认 SQLITE_MAX_SQL_LENGTH = 1,000,000 字节；整文件喂进去会 SQLITE_TOOBIG。
        List<String> seed = SqlScript.split(read("schema/seed_data.sql"));
        List<String> schema = SqlScript.split(read("schema/schema.sql"));

        assertThat(schema).hasSize(GameDatabase.TABLES.size() * 2);
        assertThat(seed).hasSizeGreaterThan(200);
        assertThat(biggest(seed)).isLessThan(1_000_000);
    }

    static String read(String resource) {
        try (var in = SqlScriptTest.class.getClassLoader().getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读不到 " + resource, e);
        }
    }

    private static int biggest(List<String> statements) {
        int max = 0;
        for (String statement : statements) {
            max = Math.max(max, statement.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        }
        return max;
    }
}
