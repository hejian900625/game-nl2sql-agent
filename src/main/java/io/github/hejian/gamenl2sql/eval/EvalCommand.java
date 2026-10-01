package io.github.hejian.gamenl2sql.eval;

import io.agentscope.core.model.Model;
import io.github.hejian.gamenl2sql.agent.AgentFactory;
import io.github.hejian.gamenl2sql.eval.EvalRunner.Result;
import io.github.hejian.gamenl2sql.eval.EvalRunner.Verdict;
import io.github.hejian.gamenl2sql.eval.EvalSet.Question;
import io.github.hejian.gamenl2sql.hitl.ConfirmationGate;
import io.github.hejian.gamenl2sql.hitl.ConfirmProperties.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code --eval} 命令行模式（§7：绝不进 {@code mvn test}，因为要打真实外部 API）。
 *
 * <p>结果 JSON 提交进仓库，README 的准确率曲线就靠这些文件；控制台只打 ASCII，
 * Windows 控制台是 GBK，中文答复打出来是乱码，别拿它当输出。
 */
@Component
public class EvalCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalCommand.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String DEFAULT_DIR = "eval-results";

    private final EvalSet evalSet;
    private final EvalRunner runner;
    private final AgentFactory agentFactory;
    private final ConfirmationGate gate;
    private final Model model;
    private final ConfigurableApplicationContext context;

    public EvalCommand(EvalSet evalSet, EvalRunner runner, AgentFactory agentFactory, ConfirmationGate gate,
                       Model model, ConfigurableApplicationContext context) {
        this.evalSet = evalSet;
        this.runner = runner;
        this.agentFactory = agentFactory;
        this.gate = gate;
        this.model = model;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!requested(args)) {
            return;
        }
        if (gate.mode() != Mode.AUTO_APPROVE) {
            throw new IllegalStateException(
                    "--eval 要求 game.confirm.mode=auto_approve：人确认模式下 25 道题会各自挂起等点击。"
                            + "用 --game.confirm.mode=auto_approve 覆盖即可（护栏四步照常执行，只是省掉人这一层）。");
        }
        List<Question> questions = evalSet.questions();
        log.info("[eval] 开始跑 {} 道题，model={}，确认模式={}", questions.size(), model.getModelName(), gate.mode());

        List<Result> results = new ArrayList<>(questions.size());
        for (Question question : questions) {
            Result result = runner.runOne(question);
            results.add(result);
            System.out.println(result.line());
        }

        Path output = outputPath(args);
        Files.createDirectories(output.toAbsolutePath().getParent());
        String json = new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(document(results));
        Files.writeString(output, json, StandardCharsets.UTF_8);

        System.out.println(summaryLine(results));
        log.info("[eval] 结果写入 {}", output.toAbsolutePath());
        context.close();
    }

    private Map<String, Object> document(List<Result> results) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("runAt", LocalDateTime.now().toString());
        doc.put("model", model.getModelName());
        doc.put("confirmMode", gate.mode().name());
        doc.put("today", agentFactory.properties().today().toString());
        doc.put("maxIters", agentFactory.properties().maxIters());
        doc.put("promptChars", agentFactory.sysPrompt().length());
        doc.put("scorerRule", "命中最后一次成功 run_sql 结果首行的任一数值单元格即算对；金额按 tol 容差；SQL 字符串不参与判分");
        doc.put("summary", summary(results));
        doc.put("results", results);
        return doc;
    }

    // 包可见：那几个口径的算法要有离线断言（EvalCommandSummaryTest），不能只在真跑一轮 25 题时肉眼看。
    static Map<String, Object> summary(List<Result> results) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("total", results.size());
        map.put("byVerdict", countBy(results, r -> r.verdict().name()));
        map.put("accuracy", percent(results, r -> true));
        // 宽松口径：表格里没有、但答复文本给出了期望值也算对。留存率这类题模型分两步查、最后用文字做除法，
        // 商永远不在单元格里（B05 两轮都是这种），拿严格口径判它等于用评分器格式判模型错。
        map.put("accuracyCountingAnswerText", lenientPercent(results));
        map.put("accuracyExcludingRetention", percent(results, r -> !r.retention()));
        map.put("accuracyRetention", percent(results, r -> r.retention()));
        map.put("accuracySingleTurn", percent(results, r -> r.turns().size() == 1));
        map.put("accuracyMultiTurn", percent(results, r -> r.turns().size() > 1));
        map.put("byLayer", countBy(results, r -> r.layer()));
        map.put("expectedInAnswer", results.stream().filter(r -> r.verdict() != Verdict.CORRECT)
                .filter(Result::expectedInAnswer).map(Result::id).toList());
        return map;
    }

    private static Map<String, Long> countBy(List<Result> results, java.util.function.Function<Result, String> key) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Result r : results) {
            counts.merge(key.apply(r), 1L, Long::sum);
        }
        return counts;
    }

    /** 分母是全部题目，不是"能判分的题目"——跳过和超时同样是失败。 */
    private static String percent(List<Result> results, java.util.function.Predicate<Result> subset) {
        List<Result> in = results.stream().filter(subset).toList();
        if (in.isEmpty()) {
            return "n/a";
        }
        long correct = in.stream().filter(r -> r.verdict() == Verdict.CORRECT).count();
        return String.format(java.util.Locale.ROOT, "%.1f%% (%d/%d)", 100.0 * correct / in.size(), correct, in.size());
    }

    /**
     * 宽松口径：分子 = 严格命中 ∪ 期望值出现在答复文字里，分母仍是全部题目。
     * 不能写成 {@code percent(results, 严格命中 ∪ 文字命中)} —— 那样 subset 同时当了分母，
     * 把"靠文字救回来"的那几题从分母里除掉，21/25 的轮次会被报成 95.5% (21/22)。实测踩过。
     */
    private static String lenientPercent(List<Result> results) {
        long ok = results.stream()
                .filter(r -> r.verdict() == Verdict.CORRECT || r.expectedInAnswer())
                .count();
        return String.format(java.util.Locale.ROOT, "%.1f%% (%d/%d)",
                100.0 * ok / results.size(), ok, results.size());
    }

    private static String summaryLine(List<Result> results) {
        long correct = results.stream().filter(r -> r.verdict() == Verdict.CORRECT).count();
        return "[eval] accuracy " + correct + "/" + results.size()
                + String.format(java.util.Locale.ROOT, " = %.1f%%", 100.0 * correct / results.size());
    }

    private static boolean requested(ApplicationArguments args) {
        return args.containsOption("eval") || args.getNonOptionArgs().contains("eval")
                || args.getNonOptionArgs().contains("--eval");
    }

    private static Path outputPath(ApplicationArguments args) {
        if (args.containsOption("eval-out")) {
            return Path.of(args.getOptionValues("eval-out").get(0));
        }
        return Path.of(DEFAULT_DIR, "eval-" + LocalDateTime.now().format(STAMP) + ".json");
    }
}
