package io.github.hejian.gamenl2sql.db;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 数据层：game.db 的生成、只读连接、查询超时 —— PLAN §6 护栏第 4 步与 §5 的落点。
 *
 * <p>SQLite 没有只读账号，所以"只读"由两件事拼出来：连接以 {@code SQLITE_OPEN_READONLY} 打开
 * （实测写操作报 {@code SQLITE_READONLY}），加上 {@code setQueryTimeout}。
 */
@Component
@EnableConfigurationProperties(DbProperties.class)
public class GameDatabase {

    private static final Logger log = LoggerFactory.getLogger(GameDatabase.class);

    /** schema.sql 定义的 8 张表，也是 §6 第 2 步白名单的对照清单。 */
    static final List<String> TABLES = List.of(
            "srv", "acct", "role", "goods", "pay_ord", "login_log", "item_flow", "season");

    private static final String SCHEMA_RESOURCE = "schema/schema.sql";
    private static final String SEED_RESOURCE = "schema/seed_data.sql";

    private final Path file;
    private final int queryTimeoutSeconds;

    public GameDatabase(DbProperties props) {
        if (props.path() == null || props.path().isBlank()) {
            throw new IllegalStateException("game.db.path 未配置，例如 data/game.db");
        }
        if (props.queryTimeoutSeconds() <= 0) {
            throw new IllegalStateException("game.db.query-timeout-seconds 必须是正数，当前=" + props.queryTimeoutSeconds());
        }
        this.file = Path.of(props.path()).toAbsolutePath().normalize();
        this.queryTimeoutSeconds = props.queryTimeoutSeconds();
    }

    @PostConstruct
    void initializeAtStartup() {
        initialize(false);
    }

    /** 幂等：文件在且没要求重置就什么都不做。 */
    public synchronized void initialize(boolean reset) {
        if (reset || !Files.exists(file)) {
            build();
        }
        verifyTables();
    }

    private void build() {
        try {
            deleteQuietly();
            Files.createDirectories(file.getParent());
            long start = System.currentTimeMillis();
            try (Connection connection = DriverManager.getConnection(jdbcUrl(file));
                 Statement statement = connection.createStatement()) {
                int executed = 0;
                executed += runScript(statement, SCHEMA_RESOURCE);
                executed += runScript(statement, SEED_RESOURCE);
                log.info("[db] 已生成 {}（{} 条语句，{} ms）", file, executed, System.currentTimeMillis() - start);
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("生成 " + file + " 失败：" + e.getMessage(), e);
        }
    }

    private int runScript(Statement statement, String resource) throws SQLException {
        String script = readResource(resource);
        List<String> statements = SqlScript.split(script);
        for (String single : statements) {
            statement.execute(single);
        }
        return statements.size();
    }

    /** 必须显式按 UTF-8 解码：种子数据里有中文区服名，平台默认字符集在 Windows 上是 GBK。 */
    private static String readResource(String resource) {
        try (var in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读不到 classpath:" + resource, e);
        }
    }

    private void verifyTables() {
        try (Connection connection = openReadOnly(); Statement statement = connection.createStatement()) {
            for (String table : TABLES) {
                try (ResultSet rs = statement.executeQuery(
                        "select count(*) from sqlite_master where type='table' and name='" + table + "'")) {
                    if (!rs.next() || rs.getInt(1) != 1) {
                        throw new IllegalStateException(
                                "数据库 " + file + " 里没有表 " + table + "。多半是路径指到了别处：SQLite 会凭空建一个空库而不是报错。");
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("校验 " + file + " 失败：" + e.getMessage(), e);
        }
    }

    public Connection openReadOnly() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(file) + "?open_mode=1");
    }

    /**
     * 只读执行一条查询。调用方（run_sql）负责先过护栏。
     * 连接串上不加任何 pragma 参数：实测 {@code ?journal_mode=wal} 与 {@code ?query_timeout=...}
     * 在只读连接上都会失败，超时只能走 {@link Statement#setQueryTimeout}。
     */
    public QueryResult query(String sql) throws SQLException {
        try (Connection connection = openReadOnly();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            try (ResultSet rs = statement.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int columns = meta.getColumnCount();
                List<String> names = new ArrayList<>(columns);
                for (int i = 1; i <= columns; i++) {
                    names.add(meta.getColumnLabel(i));
                }
                List<List<Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>(columns);
                    for (int i = 1; i <= columns; i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                }
                return new QueryResult(names, rows);
            }
        }
    }

    /**
     * 给 prompt 用的建表语句，原样取自 {@code sqlite_master}。
     *
     * <p>为什么绕这一趟而不是直接发 {@code schema.sql}：SQLite 会把 {@code --} 列注释一起存进
     * {@code sqlite_master.sql}（实测），所以"库里真正生效的那份定义"就是自带中文口径注释的那份 ——
     * 复述文件反而多出一条可以让 prompt 与库不一致的旁路。表名在 Java 侧筛，不拼进 SQL。
     */
    public String schemaDdl(Collection<String> allowed) {
        QueryResult tables;
        try {
            tables = query("select name, sql from sqlite_master where type='table' order by name");
        } catch (SQLException e) {
            throw new IllegalStateException("读不到表结构：" + e.getMessage(), e);
        }
        StringBuilder ddl = new StringBuilder();
        for (List<Object> row : tables.rows()) {
            if (allowed.contains(String.valueOf(row.get(0)))) {
                ddl.append(row.get(1)).append(";\n");
            }
        }
        if (ddl.isEmpty()) {
            throw new IllegalStateException("库里找不到要注入的表 " + allowed + "，prompt 会变成空 schema");
        }
        return ddl.toString().stripTrailing();
    }

    public Path file() {
        return file;
    }

    private void deleteQuietly() {
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(Path.of(file + "-wal"));
            Files.deleteIfExists(Path.of(file + "-shm"));
        } catch (IOException e) {
            log.warn("[db] 删除旧库失败，接着试着覆盖：{}", e.getMessage());
        }
    }

    /** Windows 路径必须转正斜杠，否则 JDBC URL 里的反斜杠会吃掉盘符。 */
    private static String jdbcUrl(Path path) {
        return "jdbc:sqlite:" + path.toString().replace('\\', '/');
    }

    public record QueryResult(List<String> columns, List<List<Object>> rows) {
    }
}
