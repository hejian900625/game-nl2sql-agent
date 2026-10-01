package io.github.hejian.gamenl2sql.hitl;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #3096 实验第二层：不在 core 里测，而在<b>我们 app 真正走的那条路</b>上测 —— HTTP 进
 * {@code /agui/run}，断流拿 interrupt，再带 {@code resume} 打第二次。
 *
 * <p>为什么必须走 HTTP：{@code AguiResumeCoordinator} 是包级私有，resume 的装配（interrupt 元数据
 * → {@code ConfirmResult} → {@code Msg.metadata[agentscope_confirm_results]}）全在
 * {@code AguiMessageConverter#toConfirmResultMsg} 里，测试代码够不着；只测 core 就只能证明
 * "手写的 ConfirmResult 能用"，那不等于证明框架自己造的能用。
 *
 * <p>模型仍然是脚本（{@link FrameworkPauseResumeProbeTest.ScriptedModel}）：要定位的是框架，
 * 不是 provider。api-key 是占位串，一次网络都不打。
 *
 * <p>2026-10-01 跑出来的结论（详见 PLAN §10）：批准后工具<b>会</b>执行、结果<b>会</b>进第二次模型调用，
 * 框架原生还支持 {@code editedArgs} 整体替换入参 —— 但那次执行的 {@code TOOL_CALL_RESULT} 事件
 * <b>不会出现在 SSE 里</b>，因为 {@code AguiStreamContext.hasStartedToolCall} 只认本次 run 起过的工具调用。
 * 丢的是事件流，不是模型上下文。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "game.model.api-key=placeholder-no-network-call")
class AguiPauseResumeProbeTest {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    @Autowired
    AguiAgentRegistry registry;

    /** 记录工具真的被调用了几次、拿到的入参是什么 —— resume 之后"批准"和"人改过"的唯一硬证据。 */
    static final class CaptureEchoTool {
        final AtomicInteger executions = new AtomicInteger();
        final List<String> receivedArgs = Collections.synchronizedList(new ArrayList<>());

        @Tool(name = "echo", description = "回显一段文本")
        public reactor.core.publisher.Mono<String> echo(@ToolParam(name = "text", description = "文本") String text) {
            executions.incrementAndGet();
            receivedArgs.add(text);
            return reactor.core.publisher.Mono.just("ECHO:" + text);
        }
    }

    /**
     * @param executionsAfterAsk ASK 那一刻的快照 —— 工具执行次数是随时间累积的，事后读必然读到第二次 run 的值
     */
    private record Dance(List<String> firstRun, String interruptId, int executionsAfterAsk,
                         int modelCallsAfterAsk, List<String> secondRun,
                         FrameworkPauseResumeProbeTest.ScriptedModel model, CaptureEchoTool tool) {
    }

    /**
     * @param resumePayload resume 条目的 payload，批准时 {@code {"approved":true}}，人改时再加 editedArgs
     */
    private Dance runDance(String scenario, String resumePayload) throws Exception {
        String agentId = "probe3096-" + scenario;
        String threadId = agentId + "-" + System.nanoTime();
        FrameworkPauseResumeProbeTest.ScriptedModel model =
                new FrameworkPauseResumeProbeTest.ScriptedModel(true);
        CaptureEchoTool tool = new CaptureEchoTool();

        // 每个 threadId 由 ThreadSessionManager 造一个实例，但它们共用同一个脚本模型和同一个工具 ——
        // 两次 run 之间模型看见什么、工具被不被调，就是这条路上唯一要读的事实。
        registry.registerFactory(agentId, () -> ReActAgent.builder()
                .name(agentId)
                .model(model)
                .toolkit(newToolkit(tool))
                .maxIters(4)
                .permissionContext(PermissionContextState.builder()
                        .mode(PermissionMode.DEFAULT)
                        .addAskRule("echo", new PermissionRule("echo", "", PermissionBehavior.ASK, "probe"))
                        .build())
                .build());

        HttpClient http = HttpClient.newHttpClient();
        List<String> firstRun = postRun(http, runBody(threadId, "run-1",
                "{\"id\":\"m1\",\"role\":\"user\",\"content\":\"请回显 hello\"}", null), agentId);
        String interruptId = firstInterruptId(firstRun);
        int executionsAfterAsk = tool.executions.get();
        int modelCallsAfterAsk = model.seen.size();
        List<String> secondRun = List.of();
        if (interruptId != null) {
            secondRun = postRun(http, runBody(threadId, "run-2", null,
                    "[{\"interruptId\":\"" + interruptId + "\",\"status\":\"resolved\",\"payload\":"
                            + resumePayload + "}]"), agentId);
        }
        return new Dance(firstRun, interruptId, executionsAfterAsk, modelCallsAfterAsk,
                secondRun, model, tool);
    }

    private static Toolkit newToolkit(CaptureEchoTool tool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);
        return toolkit;
    }

    @Test
    void approvedResumeExecutesTheToolButNeverEmitsItsResultEvent() throws Exception {
        Dance dance = runDance("approve", "{\"approved\":true}");
        write("approve", new StringBuilder("== 场景：resume approved=true（原样批准）==\n")
                .append(describe(dance)));

        // 阶段一：ASK 必须真的把执行停住
        assertThat(dance.interruptId()).as("第一次 run 必须产出 interrupt").isNotNull();
        assertThat(dance.firstRun()).anyMatch(line -> line.contains("PERMISSION_ASKING"));
        assertThat(dance.executionsAfterAsk()).as("ASK 之后工具不能被执行").isZero();
        assertThat(dance.modelCallsAfterAsk()).as("ASK 之后模型只被调用一次").isEqualTo(1);

        // 阶段二：批准之后工具必须执行，结果必须进模型
        assertThat(dance.secondRun().get(0)).as("第二次 run 的 HTTP 状态").isEqualTo("HTTP 200");
        assertThat(dance.tool().executions.get()).as("批准后工具执行一次").isEqualTo(1);
        assertThat(dance.tool().receivedArgs).containsExactly("hello");
        assertThat(dance.model().seen).as("批准后模型被第二次调用").hasSize(2);
        assertThat(toolResults(dance.model().seen.get(1))).as("工具结果必须进第二次模型调用")
                .containsExactly("ECHO:hello");

        // 缺陷：结果对模型可见，对前端不可见
        assertThat(dance.secondRun()).noneMatch(line -> line.contains("\"TOOL_CALL_RESULT\""));
        assertThat(dance.secondRun()).noneMatch(line -> line.contains("\"TOOL_CALL_END\""));
    }

    @Test
    void editedArgsReplaceTheToolInputWholesale() throws Exception {
        Dance dance = runDance("edit",
                "{\"approved\":true,\"editedArgs\":{\"text\":\"changed-by-human\"}}");
        write("edit", new StringBuilder("== 场景：resume editedArgs（人改入参）==\n")
                .append(describe(dance)));

        assertThat(dance.executionsAfterAsk()).as("ASK 之后工具不能被执行").isZero();
        assertThat(dance.tool().executions.get()).as("批准后工具执行一次").isEqualTo(1);
        assertThat(dance.tool().receivedArgs).as("editedArgs 是整体替换，不是合并")
                .containsExactly("changed-by-human");
        assertThat(toolResults(dance.model().seen.get(1))).containsExactly("ECHO:changed-by-human");
    }

    /** 把两次 run 的事件流和模型输入原样落盘（research/ 是 gitignore 的草稿区，事实以本类的断言为准）。 */
    private static String describe(Dance dance) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n-- 第一次 /agui/run --\n");
        dance.firstRun().forEach(line -> sb.append("   ").append(line).append('\n'));
        sb.append("interrupt id = ").append(dance.interruptId()).append('\n');
        sb.append("ASK 那一刻工具被执行次数 = ").append(dance.executionsAfterAsk())
                .append("，模型调用次数 = ").append(dance.modelCallsAfterAsk()).append('\n');
        sb.append("\n-- 第二次 /agui/run（resume）--\n");
        dance.secondRun().forEach(line -> sb.append("   ").append(line).append('\n'));
        sb.append("工具被执行次数 = ").append(dance.tool().executions.get())
                .append("，实际入参 = ").append(dance.tool().receivedArgs).append('\n');
        for (int i = 0; i < dance.model().seen.size(); i++) {
            sb.append("\n第 ").append(i + 1).append(" 次模型调用的完整输入：\n");
            dance.model().seen.get(i).forEach(m -> sb.append("   ")
                    .append(FrameworkPauseResumeProbeTest.describe(m)).append('\n'));
        }
        return sb.toString();
    }

    private static void write(String scenario, StringBuilder report) throws Exception {
        Files.createDirectories(Path.of("research"));
        Files.writeString(Path.of("research/agui-pause-resume-" + scenario + ".txt"), report.toString());
        System.out.println(report);
    }

    private static List<String> toolResults(List<Msg> msgs) {
        List<String> out = new ArrayList<>();
        for (Msg m : msgs) {
            for (var block : m.getContent()) {
                if (block instanceof ToolResultBlock tr) {
                    tr.getOutput().stream()
                            .filter(io.agentscope.core.message.TextBlock.class::isInstance)
                            .map(b -> ((io.agentscope.core.message.TextBlock) b).getText())
                            // 工具返回的是 String，框架把它按 JSON 编码过一次，所以 text 带着引号
                            .map(t -> t.strip().replaceAll("^\"|\"$", ""))
                            .forEach(out::add);
                }
            }
        }
        return out;
    }

    /**
     * @param messageOrNull null 表示这次 run 不带用户消息（纯 resume）
     * @param resumeJson    null 表示不带 resume 字段
     */
    private String runBody(String threadId, String runId, String messageOrNull, String resumeJson) {
        StringBuilder sb = new StringBuilder("{")
                .append("\"threadId\":\"").append(threadId).append("\",")
                .append("\"runId\":\"").append(runId).append("\",")
                .append("\"messages\":").append(messageOrNull == null ? "[]," : "[" + messageOrNull + "],")
                .append("\"tools\":[],\"context\":[],\"state\":{},\"forwardedProps\":{}");
        if (resumeJson != null) {
            sb.append(",\"resume\":").append(resumeJson);
        }
        return sb.append("}").toString();
    }

    /** SSE 读成 data: 行的列表（首行是 HTTP 状态）；run 以 interrupt 结束，服务端会关连接，所以不会挂住。 */
    private List<String> postRun(HttpClient http, String body, String agentId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/agui/run"))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("X-Agent-Id", agentId)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<java.util.stream.Stream<String>> response =
                http.send(request, HttpResponse.BodyHandlers.ofLines());
        List<String> data = new ArrayList<>();
        data.add("HTTP " + response.statusCode());
        response.body()
                .filter(l -> l.startsWith("data:"))
                .map(l -> l.substring("data:".length()).trim())
                .forEach(data::add);
        return data;
    }

    private static String firstInterruptId(List<String> sseLines) {
        for (String line : sseLines) {
            if (!line.startsWith("{")) {
                continue;
            }
            JsonNode interrupts = JSON.readTree(line).path("outcome").path("interrupts");
            if (interrupts.isArray() && !interrupts.isEmpty()) {
                return interrupts.get(0).path("id").asString(null);
            }
        }
        return null;
    }
}
