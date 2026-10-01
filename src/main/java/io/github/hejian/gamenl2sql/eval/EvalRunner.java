package io.github.hejian.gamenl2sql.eval;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.github.hejian.gamenl2sql.agent.AgentFactory;
import io.github.hejian.gamenl2sql.eval.EvalSet.Question;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code --eval} 的跑手（§7）。串行、每题一个新 agent、只比数字不比 SQL。
 *
 * <p>归因分桶（§7 要求的四类）不看"答案文本像不像"，只看工具调用实际发生了什么：
 * 未调工具 / 护栏或执行报错 / 人拒绝 / 跑出来了但数不对。
 */
@Component
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    /** 单轮上限。DeepSeek flash 一次 ReAct（最多 4 步）实测几十秒内，180 秒是留的重试余量。 */
    private static final Duration PER_CALL_TIMEOUT = Duration.ofSeconds(180);

    private static final Pattern NUMBER = Pattern.compile("-?\\d[\\d,]*(?:\\.\\d+)?");

    /** §7 的四类 + 两类跑不出结果的意外。 */
    public enum Verdict {
        CORRECT,
        WRONG_NUMBER,
        NO_TOOL_CALL,
        GUARD_REJECTED,
        EXEC_ERROR,
        HUMAN_DENIED,
        UNKNOWN_RESULT,
        AGENT_ERROR
    }

    public record Result(String id, String set, String layer, boolean retention, List<String> turns,
                         Verdict verdict, boolean expectedInAnswer, String expected, String measured,
                         List<String> firstRowCells, String modelSql, String executedSql,
                         int toolCalls, long elapsedMs, String answer, String error) {

        /** 给控制台看的行，必须纯 ASCII：Windows 控制台是 GBK，中文会打烂。 */
        public String line() {
            return String.format("%-4s %-16s exp=%-12s got=%-12s row=%-20s %6dms calls=%d",
                    id, verdict, expected, measured == null ? "-" : measured,
                    firstRowCells == null ? "-" : String.join(",", firstRowCells), elapsedMs, toolCalls);
        }
    }

    private final AgentFactory factory;

    public EvalRunner(AgentFactory factory) {
        this.factory = factory;
    }

    public List<Result> run(List<Question> questions) {
        List<Result> results = new ArrayList<>(questions.size());
        for (Question question : questions) {
            results.add(runOne(question));
        }
        return List.copyOf(results);
    }

    public Result runOne(Question question) {
        long start = System.currentTimeMillis();
        ReActAgent agent = factory.create();
        List<EvalTrace.Step> steps = new ArrayList<>();
        String answer = null;
        String error = null;
        try {
            for (String turn : question.turns()) {
                int before = agent.getAgentState().getContext().size();
                Msg reply = agent.call(List.of(Msg.builder().role(MsgRole.USER).textContent(turn).build()))
                        .block(PER_CALL_TIMEOUT);
                List<Msg> context = agent.getAgentState().getContext();
                steps.addAll(EvalTrace.steps(context.subList(before, context.size())));
                answer = EvalTrace.textOf(reply.getContent());
            }
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("[eval] {} 这一轮抛异常：{}", question.id(), error);
        }
        return grade(question, steps, answer, error, System.currentTimeMillis() - start);
    }

    private static Result grade(Question q, List<EvalTrace.Step> steps, String answer, String error, long elapsed) {
        EvalTrace.Step last = EvalTrace.lastExecuted(steps);
        Verdict verdict;
        BigDecimal measured = null;
        List<String> cells = List.of();
        if (error != null) {
            verdict = Verdict.AGENT_ERROR;
        } else if (last != null) {
            cells = EvalTrace.firstRowCells(last.text()).stream().map(BigDecimal::toPlainString).toList();
            measured = EvalTrace.matchCell(last.text(), q.expected(), q.tolerance());
            verdict = measured == null ? Verdict.WRONG_NUMBER : Verdict.CORRECT;
        } else if (steps.isEmpty()) {
            verdict = Verdict.NO_TOOL_CALL;
        } else {
            verdict = switch (steps.get(steps.size() - 1).kind()) {
                case GUARD_REJECTED -> Verdict.GUARD_REJECTED;
                case EXEC_ERROR -> Verdict.EXEC_ERROR;
                case HUMAN_DENIED -> Verdict.HUMAN_DENIED;
                default -> Verdict.UNKNOWN_RESULT;
            };
        }
        return new Result(q.id(), q.set(), q.layer(), q.retention(), q.turns(), verdict,
                answerContainsNumber(answer, q.expected(), q.tolerance()),
                q.expected().toPlainString(), measured == null ? null : measured.toPlainString(), cells,
                last == null ? null : last.modelSql(), last == null ? null : last.executedSql(),
                steps.size(), elapsed, answer, error);
    }

    /** 诊断用：数字有没有出现在答复里。它不参与判分，只用来区分"数对了但没写进结论"和"根本没算出来"。 */
    private static boolean answerContainsNumber(String answer, BigDecimal expected, BigDecimal tolerance) {
        if (answer == null) {
            return false;
        }
        Matcher matcher = NUMBER.matcher(answer);
        while (matcher.find()) {
            try {
                BigDecimal found = new BigDecimal(matcher.group().replace(",", ""));
                if (found.subtract(expected).abs().compareTo(tolerance) <= 0) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // 千分位、科学计数法这类残片不是数字，跳过即可
            }
        }
        return false;
    }
}
