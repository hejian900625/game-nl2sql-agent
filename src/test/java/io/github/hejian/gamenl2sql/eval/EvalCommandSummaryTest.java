package io.github.hejian.gamenl2sql.eval;

import io.github.hejian.gamenl2sql.eval.EvalRunner.Result;
import io.github.hejian.gamenl2sql.eval.EvalRunner.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 判分汇总的算术，离线钉住。这里之所以要有测试而不是"真跑一轮看看"：
 * {@code accuracyCountingAnswerText} 曾经把"文字命中"的题同时当分子和分母，21/25 的轮次
 * 被算成 95.5% (21/22) —— 分数虚高且分母变了含义，而它是要进 README 的第一个数字。
 */
class EvalCommandSummaryTest {

    private static Result result(String id, Verdict verdict, boolean numberInAnswer, boolean retention) {
        return new Result(id, "test", "单表", retention, List.of("问一句"), verdict, numberInAnswer,
                "1", "1", List.of("1"), "SELECT 1", "SELECT 1 LIMIT 200", 1, 100L, "答", null);
    }

    private static final List<Result> FOUR = List.of(
            result("A", Verdict.CORRECT, false, false),
            result("B", Verdict.CORRECT, true, false),
            // 只有它靠"文字命中"救回来：单元格里没有、答复里有
            result("C", Verdict.WRONG_NUMBER, true, true),
            result("D", Verdict.NO_TOOL_CALL, false, false));

    @Test
    void bothCalibersShareTheSameDenominator() {
        Map<String, Object> summary = EvalCommand.summary(FOUR);
        assertThat(summary.get("total")).isEqualTo(4);
        assertThat(summary.get("accuracy")).isEqualTo("50.0% (2/4)");
        // 宽松口径只往分子里加题，绝不许把加进来的题从分母里除掉
        assertThat(summary.get("accuracyCountingAnswerText")).isEqualTo("75.0% (3/4)");
    }

    @Test
    void subCalibersKeepTheirOwnDenominators() {
        Map<String, Object> summary = EvalCommand.summary(FOUR);
        assertThat(summary.get("accuracyExcludingRetention")).isEqualTo("66.7% (2/3)");
        assertThat(summary.get("accuracyRetention")).isEqualTo("0.0% (0/1)");
        assertThat(summary.get("accuracySingleTurn")).isEqualTo("50.0% (2/4)");
        assertThat(summary.get("accuracyMultiTurn")).isEqualTo("n/a");
    }

    @Test
    void failedQuestionsRescuedByTextAreListedById() {
        Map<String, Object> summary = EvalCommand.summary(FOUR);
        // C 是唯一"严格判错但答复里有期望值"的题，README 的宽松口径必须能追溯到具体题号
        assertThat(summary.get("expectedInAnswer")).isEqualTo(List.of("C"));
        assertThat(summary.get("byVerdict")).isInstanceOf(Map.class);
    }
}
