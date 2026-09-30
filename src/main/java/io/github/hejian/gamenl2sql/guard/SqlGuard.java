package io.github.hejian.gamenl2sql.guard;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * PLAN §6 护栏链的第 1–3 步：解析 → 表白名单 → 注入 LIMIT。纯函数，不碰数据库，
 * 所以是 CI 唯一能跑的那部分（§7"Actions 只跑离线护栏单测"）。
 *
 * <p>多语句的防线不在"检测分号"上，而在<b>返回值是 AST 重新序列化的结果</b>：
 * 实测 JSqlParser 5.4 的 {@code parse("select 1; delete from acct")} 会静默只解析出
 * 第一条语句而不报错，若把原文交给驱动执行就等于放行第二条。执行方必须用本类返回的
 * {@link GuardOutcome#sql()}，绝不能用调用方原文。
 */
public final class SqlGuard {

    /** 授权清单 = schema.sql 里那 8 张表，字段的权威定义见 PLAN §12。 */
    static final Set<String> ALLOWED_TABLES = Set.of(
            "srv", "acct", "role", "goods", "pay_ord", "login_log", "item_flow", "season");

    private static final Set<String> SYSTEM_PREFIXES = Set.of("sqlite_", "pragma_");

    private final int maxRows;

    public SqlGuard(int maxRows) {
        this.maxRows = maxRows;
    }

    public GuardOutcome guard(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            return GuardOutcome.reject(Rejection.EMPTY, null);
        }

        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(rawSql);
        } catch (JSQLParserException e) {
            // PRAGMA / ATTACH / VACUUM 这些 SQLite 方言在这里就掉下去了：解析器不认识 = 不放行。
            return GuardOutcome.reject(Rejection.PARSE_FAILED, rootMessage(e));
        }

        if (!(statement instanceof Select select)) {
            return GuardOutcome.reject(Rejection.NOT_SELECT,
                    "实际语句类型 " + statement.getClass().getSimpleName());
        }

        GuardOutcome tables = checkTables(statement);
        if (tables != null) {
            return tables;
        }

        boolean limitAdded = applyRowCap(select);
        return GuardOutcome.pass(statement.toString(), limitAdded);
    }

    private GuardOutcome checkTables(Statement statement) {
        Set<String> found = new TablesNamesFinder().getTables(statement);
        Set<String> unauthorized = new TreeSet<>();
        Set<String> system = new TreeSet<>();
        for (String table : found) {
            String name = normalize(table);
            // CTE 别名不会出现在 getTables() 结果里（实测 with t as (...) select from t → [acct]），
            // 所以这里不需要为 WITH 开例外。
            if (isSystem(name)) {
                system.add(name);
            } else if (!ALLOWED_TABLES.contains(name)) {
                unauthorized.add(name);
            }
        }
        if (!system.isEmpty()) {
            return GuardOutcome.reject(Rejection.SYSTEM_OBJECT, String.join(", ", system));
        }
        if (!unauthorized.isEmpty()) {
            return GuardOutcome.reject(Rejection.UNKNOWN_TABLE, String.join(", ", unauthorized));
        }
        return null;
    }

    private boolean applyRowCap(Select select) {
        Limit limit = select.getLimit();
        if (limit == null) {
            select.setLimit(new Limit().withRowCount(new LongValue(maxRows)));
            return true;
        }
        // 模型自己写了 LIMIT 也要压到上限之内，否则 limit 1000000 让"防全表大结果集"形同虚设。
        if (limit.getRowCount() instanceof LongValue longValue && longValue.getValue() > maxRows) {
            longValue.setValue(maxRows);
        }
        return false;
    }

    private static String normalize(String table) {
        String name = table.strip().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            // main.acct / temp.acct —— 去掉库名前缀再判，否则白名单能被 main 前缀绕过。
            name = name.substring(dot + 1);
        }
        return name.replace("`", "").replace("\"", "")
                .replace("[", "").replace("]", "");
    }

    private static boolean isSystem(String name) {
        return name.startsWith("sqlite_") || name.startsWith("pragma_");
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null) {
            return root.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
