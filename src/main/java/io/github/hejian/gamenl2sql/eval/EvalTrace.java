package io.github.hejian.gamenl2sql.eval;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 agent 上下文里抽出"这次 run 调了哪些 SQL、工具回了什么"。
 *
 * <p>没用 Hook：探活实测 {@code AgentState.context} 里本来就按顺序存着
 * {@code ASSISTANT(ToolUseBlock) → TOOL(ToolResultBlock)}，直接读比再挂一个监听点少一层
 * 未验证的假设（Hook 事件在 2.0.3 的触发时机我没有实测过）。
 *
 * <p>结果文本的形状由 {@code RunSqlTool.format} 决定，{@link #EvalTrace#firstCell} 的解析跟着它；
 * 两边的一致性由 {@code EvalTraceTest} 用真 Toolkit + 真 SQLite 钉住，改文案不改测试会当场红。
 */
public final class EvalTrace {

    /** 工具这一次调用的结局。归因四类（§7）就是从这个枚举映射出去的。 */
    public enum Kind {
        EXECUTED,
        GUARD_REJECTED,
        HUMAN_DENIED,
        EXEC_ERROR,
        UNKNOWN
    }

    public record Step(String callId, String modelSql, String executedSql, String text, Kind kind) {
    }

    private EvalTrace() {
    }

    /** @param newMessages 本次 call 期间新增到上下文的那一段（调用方按前后长度切出来） */
    public static List<Step> steps(List<Msg> newMessages) {
        List<ToolUseBlock> uses = new ArrayList<>();
        List<ToolResultBlock> results = new ArrayList<>();
        for (Msg msg : newMessages) {
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ToolUseBlock use && "run_sql".equals(use.getName())) {
                    uses.add(use);
                } else if (block instanceof ToolResultBlock result) {
                    results.add(result);
                }
            }
        }
        Map<String, String> sqlById = new HashMap<>();
        for (ToolUseBlock use : uses) {
            sqlById.put(use.getId(), String.valueOf(use.getInput().get("sql")));
        }
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            ToolResultBlock result = results.get(i);
            String sql = sqlById.get(result.getId());
            if (sql == null && i < uses.size()) {
                // id 配不上就按出现顺序兜底：实测 Toolkit.callTool 直接返回的 ToolResultBlock 里
                // id 与 name 都是 null（EvalTraceTest 钉住），而 run_sql 是唯一工具，顺序即对应关系。
                sql = String.valueOf(uses.get(i).getInput().get("sql"));
            }
            List<ContentBlock> output = result.getOutput() == null ? List.of() : result.getOutput();
            String text = textOf(output);
            steps.add(new Step(result.getId(), sql, executedSql(text), text, kind(text)));
        }
        return List.copyOf(steps);
    }

    private static Kind kind(String text) {
        if (text == null) {
            return Kind.UNKNOWN;
        }
        if (text.startsWith("执行 SQL:")) {
            return Kind.EXECUTED;
        }
        if (text.startsWith("护栏拒绝")) {
            return Kind.GUARD_REJECTED;
        }
        if (text.startsWith("人拒绝执行")) {
            return Kind.HUMAN_DENIED;
        }
        if (text.startsWith("执行失败")) {
            return Kind.EXEC_ERROR;
        }
        return Kind.UNKNOWN;
    }

    /** 护栏会把执行文本重写（补空格、加 LIMIT），人看到的和执行的是重写后的那条。 */
    private static String executedSql(String text) {
        if (text == null || !text.startsWith("执行 SQL:")) {
            return null;
        }
        int end = text.indexOf('\n');
        return end < 0 ? text.substring("执行 SQL:".length()).strip() : text.substring("执行 SQL:".length(), end).strip();
    }

    /**
     * 结果表格首行的数值单元格。
     *
     * <p>为什么不是"只看首格"：首轮实测 B10/T01 都答对了，但它们的 SQL 是
     * {@code select srv_id, round(sum(...),2) ... limit 1} —— 期望数在第二列，只比首格会把对的判成错的。
     * 只取<b>首行</b>是刻意的收紧：聚合题的答案就是一行，而"返回一张按天分组的表"里没有那个总数，
     * 本该算错。
     *
     * @return 解析不出的格（NULL、文本列）跳过；整行拿不到时返回空表
     */
    public static List<BigDecimal> firstRowCells(String text) {
        String row = firstDataRow(text);
        if (row == null) {
            return List.of();
        }
        List<BigDecimal> cells = new ArrayList<>();
        for (String cell : row.split(" \\| ")) {
            try {
                cells.add(new BigDecimal(cell.strip()));
            } catch (NumberFormatException ignored) {
                // NULL、区服名这类不是数字，不参与比对
            }
        }
        return cells;
    }

    private static String firstDataRow(String text) {
        if (text == null) {
            return null;
        }
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("返回 ")) {
                // i+1 是列名行，i+2 才是首行数据；空结果集时 i+2 越界
                return i + 2 < lines.length ? lines[i + 2] : null;
            }
        }
        return null;
    }

    /** 首行的数值单元格里有没有期望值（按容差，命中即返回那个格子）；都对不上返回 null。 */
    public static BigDecimal matchCell(String text, BigDecimal expected, BigDecimal tolerance) {
        for (BigDecimal cell : firstRowCells(text)) {
            if (cell.subtract(expected).abs().compareTo(tolerance) <= 0) {
                return cell;
            }
        }
        return null;
    }

    public static String textOf(List<ContentBlock> blocks) {        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock text) {
                sb.append(text.getText());
            }
        }
        return sb.toString();
    }

    /** 最后一次成功执行的步骤 —— 模型被拒后改写重跑，就以重跑的那次为准。 */
    public static Step lastExecuted(List<Step> steps) {
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i).kind() == Kind.EXECUTED) {
                return steps.get(i);
            }
        }
        return null;
    }
}
