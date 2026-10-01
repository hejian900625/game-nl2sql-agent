package io.github.hejian.gamenl2sql.eval;

import io.github.hejian.gamenl2sql.eval.EvalSet.Question;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测集的离线守卫（CI 可跑，不联网）。跑手一旦解析错题面或期望值，25 道题的准确率会
 * 静默失真，所以这里把 §7 的结构约束变成断言。
 */
class EvalSetTest {

    private final EvalSet evalSet = new EvalSet();

    @Test
    void loadsTwentyFiveQuestionsWithIdsAndNoBlanks() {
        List<Question> questions = evalSet.questions();

        assertThat(questions).hasSize(25);
        assertThat(questions).extracting(Question::id).doesNotHaveDuplicates();
        assertThat(questions).allSatisfy(q -> {
            assertThat(q.turns()).isNotEmpty();
            assertThat(q.turns()).allSatisfy(turn -> assertThat(turn).isNotBlank());
            assertThat(q.truthSql()).isNotBlank();
            assertThat(q.caliber()).as("%s 没有口径声明，无口径的题不许进评测集", q.id()).isNotBlank();
            assertThat(q.expected()).isNotNull();
        });
        assertThat(questions).filteredOn(q -> !q.id().startsWith("T")).allSatisfy(q -> {
            assertThat(q.turns()).hasSize(1);
        });
    }

    @Test
    void followUpQuestionsCarryTwoTurnsAndAreTheOnlyOnes() {
        List<Question> multi = evalSet.questions().stream().filter(q -> q.turns().size() > 1).toList();

        assertThat(multi).extracting(Question::id).containsExactly("T01", "T02", "T03", "T04", "T05");
        assertThat(multi).allSatisfy(q -> assertThat(q.turns()).hasSize(2));
    }

    @Test
    void moneyQuestionsGetToleranceAndCountsDoNot() {
        assertThat(question("A01").tolerance()).isEqualByComparingTo("0.01");
        assertThat(question("B04").tolerance()).isEqualByComparingTo("0.01");
        assertThat(question("B01").tolerance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(question("A01").kind()).isEqualTo("scalar");
        assertThat(question("B01").kind()).isEqualTo("rowcount");
    }

    @Test
    void retentionSetMatchesBusinessJsonRetentionIds() {
        assertThat(evalSet.retention()).extracting(Question::id)
                .containsExactly("B01", "B02", "B04", "B05", "B07", "B10", "T03", "T05");
        // 对抗集没有 retention 标记，全部算非留存
        assertThat(evalSet.questions()).filteredOn(q -> q.set().equals("adversarial"))
                .allSatisfy(q -> assertThat(q.retention()).isFalse());
    }

    private Question question(String id) {
        return evalSet.questions().stream().filter(q -> q.id().equals(id)).findFirst().orElseThrow();
    }
}
