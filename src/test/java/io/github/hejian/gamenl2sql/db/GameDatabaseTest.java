package io.github.hejian.gamenl2sql.db;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 这份测试是 CI 侧的 verify_eval.py check：仓库里不含 .db（§5），
 * 评测期望值必须由"clone 者本机现场生成的库"复现，否则 25 道题的分数在别人机器上是悬空的。
 * 不联网、不需要 API key。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GameDatabaseTest {

    @TempDir
    static Path dir;

    private GameDatabase database;

    @BeforeAll
    void buildDatabaseFromResources() {
        database = new GameDatabase(new DbProperties(dir.resolve("game.db").toString(), false, 10));
        database.initialize(false);
    }

    @Test
    void reproducesEveryEvalExpectation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        int checked = 0;
        for (String resource : List.of("eval/adversarial.json", "eval/business.json")) {
            JsonNode doc = mapper.readTree(SqlScriptTest.read(resource));
            for (JsonNode question : doc.path("questions")) {
                String id = question.path("id").asString();
                String truthSql = question.path("truthSql").asString();
                JsonNode expect = question.path("expect");
                BigDecimal want = new BigDecimal(expect.path("value").asString());

                BigDecimal got = firstValue(truthSql);
                BigDecimal tol = expect.hasNonNull("tol")
                        ? new BigDecimal(expect.path("tol").asString()) : BigDecimal.ZERO;
                assertThat(got.subtract(want).abs())
                        .as("%s 的期望值在生成库里复现不出来（题面口径变了或 seed 变了）", id)
                        .isLessThanOrEqualTo(tol);
                checked++;
            }
        }
        assertThat(checked).isEqualTo(25);
    }

    @Test
    void exposesAllEightTables() throws Exception {
        for (String table : GameDatabase.TABLES) {
            assertThat(firstValue("select count(*) from " + table))
                    .as("表 %s 应有数据", table).isGreaterThan(BigDecimal.ZERO);
        }
    }

    /** 护栏第 4 步的地基：只读连接挡得住写。 */
    @Test
    void readOnlyConnectionRefusesWrites() throws Exception {
        try (var connection = database.openReadOnly(); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("delete from acct"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("readonly");
        }
    }

    @Test
    void doesNotRebuildWhenFileIsThere() throws Exception {
        long before = Files.getLastModifiedTime(database.file()).toMillis();
        Thread.sleep(20);
        database.initialize(false);
        assertThat(Files.getLastModifiedTime(database.file()).toMillis()).isEqualTo(before);

        database.initialize(true);
        assertThat(Files.getLastModifiedTime(database.file()).toMillis()).isGreaterThan(before);
    }

    /** SQLite 对不存在的路径会凭空建空库，所以建库后必须验表，否则错误要到第一次查询才冒出来。 */
    @Test
    void failsFastWhenTheFileIsNotOurSchema() throws Exception {
        Path stray = dir.resolve("stray.db");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + stray);
             Statement statement = connection.createStatement()) {
            statement.execute("create table something_else (a int)");
        }
        GameDatabase wrong = new GameDatabase(new DbProperties(stray.toString(), false, 10));
        assertThatThrownBy(() -> wrong.initialize(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("凭空建一个空库");
    }

    @Test
    void rejectsUselessConfig() {
        assertThatThrownBy(() -> new GameDatabase(new DbProperties(" ", false, 10)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("game.db.path");
        assertThatThrownBy(() -> new GameDatabase(new DbProperties("data/game.db", false, 0)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("query-timeout-seconds");
    }

    private BigDecimal firstValue(String sql) throws SQLException {
        GameDatabase.QueryResult result = database.query(sql);
        assertThat(result.rows()).as("查询没有返回行: %s", sql).hasSize(1);
        Object value = result.rows().get(0).get(0);
        assertThat(value).as("期望值是标量: %s", sql).isNotNull();
        return new BigDecimal(String.valueOf(value));
    }
}
