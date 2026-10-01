package io.github.hejian.gamenl2sql.eval;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 评测集装载（PLAN §7）。题面、口径、期望值全部来自 {@code src/main/resources/eval/*.json}，
 * 跑手不许在这里之外的地方改期望值 —— 期望值由 truthSql 在生成库上真跑得到，改一个就得重算 25 条。
 */
@Component
public class EvalSet {

    /** §7 要求的两个集合：10 道对抗 + 15 道业务（其中 T01–T05 是两轮追问，断言第 2 轮）。 */
    public static final List<String> RESOURCES = List.of("eval/adversarial.json", "eval/business.json");

    /**
     * @param turns 依次喂给同一个 agent 的话；追问题有 2 条，单轮题只有 1 条
     */
    public record Question(String id, String set, String layer, boolean retention, List<String> turns,
                           String caliber, String truthSql, String kind, BigDecimal expected,
                           BigDecimal tolerance, String trap) {
    }

    private final List<Question> questions;

    public EvalSet() {
        this.questions = load();
    }

    public List<Question> questions() {
        return questions;
    }

    public List<Question> retention() {
        return questions.stream().filter(Question::retention).toList();
    }

    private static List<Question> load() {
        ObjectMapper mapper = new ObjectMapper();
        List<Question> all = new ArrayList<>();
        for (String resource : RESOURCES) {
            JsonNode doc = read(mapper, resource);
            String set = doc.path("set").asString();
            for (JsonNode node : doc.path("questions")) {
                all.add(parse(set, node, resource));
            }
        }
        if (all.size() != 25) {
            throw new IllegalStateException("评测集应当是 25 题（§7），实际读到 " + all.size() + " 题");
        }
        return List.copyOf(all);
    }

    private static Question parse(String set, JsonNode node, String resource) {
        String id = node.path("id").asString();
        List<String> turns = new ArrayList<>();
        if (node.path("turns").isArray()) {
            node.path("turns").forEach(t -> turns.add(t.asString()));
        } else {
            turns.add(node.path("question").asString());
        }
        turns.removeIf(String::isBlank);
        if (turns.isEmpty()) {
            throw new IllegalStateException(resource + " 的 " + id + " 没有题面");
        }
        JsonNode expect = node.path("expect");
        if (expect.isMissingNode()) {
            throw new IllegalStateException(resource + " 的 " + id + " 没有 expect，无期望值的题不许进评测集（§7）");
        }
        return new Question(id, set, node.path("layer").asString(), node.path("retention").asBoolean(false),
                List.copyOf(turns), node.path("caliber").asString(), node.path("truthSql").asString(),
                expect.path("kind").asString(), expect.path("value").decimalValue(),
                expect.path("tol").decimalValueOpt().orElse(BigDecimal.ZERO), node.path("trap").asString());
    }

    private static JsonNode read(ObjectMapper mapper, String resource) {
        try (var in = new ClassPathResource(resource).getInputStream()) {
            return mapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("读不到 classpath:" + resource, e);
        }
    }
}
