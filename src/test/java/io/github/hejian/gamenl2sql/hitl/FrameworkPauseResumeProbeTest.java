package io.github.hejian.gamenl2sql.hitl;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PLAN §10 的 W2 throwaway 实验：**只测框架原生 permission + pause/resume，不碰我们自己的确认门。**
 *
 * <p>为什么用假模型而不是 DeepSeek：要定位的是"#3096 到底在哪一层丢工具结果"。把模型换成一份
 * 脚本，等式里就只剩框架 —— 第二次模型调用看见什么、没看见什么，是可以逐块打印的事实，
 * 不需要猜 provider 有没有吞掉消息。
 *
 * <p>读 2.0.3 字节码得到的三个入口（不是文档，文档没写）：
 * {@code PermissionRule(toolName, "", ASK, source)} 里 ruleContent 为空即匹配该工具全部调用；
 * 恢复靠把 {@code List<ConfirmResult>} 塞进 {@code Msg.metadata[Msg.METADATA_CONFIRM_RESULTS]}；
 * {@code PermissionMode.DONT_ASK} 会把 ASK 直接降级成 DENY（"User is not available to answer permission prompts"）。
 */
class FrameworkPauseResumeProbeTest {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);
    private static final String MARKER = "ECHO:hello-from-tool";

    /** 两次调用：第一次要工具，第二次给最终答复。每次收到的消息列表都记下来，事后逐块看。 */
    static final class ScriptedModel implements Model {
        final List<List<Msg>> seen = new ArrayList<>();
        final boolean withRawContent;

        ScriptedModel(boolean withRawContent) {
            this.withRawContent = withRawContent;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
            seen.add(List.copyOf(msgs));
            // 真 provider 回来的 tool_use 同时带 input 映射和原始 JSON 串（content）。只填一个是探针造出来的形状，
            // 框架若读 content 不读 input，两种形状会给出完全不同的结论 —— 所以两条都要跑。
            ToolUseBlock use = withRawContent
                    ? new ToolUseBlock("call-1", "echo", Map.of("text", "hello"), "{\"text\":\"hello\"}", Map.of())
                    : new ToolUseBlock("call-1", "echo", Map.of("text", "hello"));
            List<ContentBlock> content = seen.size() == 1
                    ? List.of(use)
                    : List.of(TextBlock.builder().text("done").build());
            return Flux.just(ChatResponse.builder().id("r" + seen.size()).content(content)
                    .finishReason(seen.size() == 1 ? "tool_calls" : "stop").build());
        }

        @Override
        public String getModelName() {
            return "scripted";
        }
    }

    public static class EchoTool {
        static final java.util.concurrent.atomic.AtomicInteger EXECUTIONS = new java.util.concurrent.atomic.AtomicInteger();

        @Tool(name = "echo", description = "回显一段文本")
        public Mono<String> echo(@ToolParam(name = "text", description = "文本") String text) {
            EXECUTIONS.incrementAndGet();
            return Mono.just(MARKER);
        }
    }

    @Test
    void recordsWhatTheFrameworkDoesAcrossAnApprovedPause() throws Exception {
        Files.deleteIfExists(Path.of("research/pause-resume-probe.txt"));
        for (boolean rawContent : List.of(false, true)) {
            for (boolean recovery : List.of(false, true)) {
                runProbe(rawContent, recovery);
            }
        }
    }

    private void runProbe(boolean withRawContent, boolean pendingToolRecovery) throws Exception {
        EchoTool.EXECUTIONS.set(0);
        ScriptedModel model = new ScriptedModel(withRawContent);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new EchoTool());

        ReActAgent.Builder builder = ReActAgent.builder()
                .name("probe")
                .model(model)
                .toolkit(toolkit)
                .maxIters(4)
                .permissionContext(PermissionContextState.builder()
                        .mode(PermissionMode.DEFAULT)
                        .addAskRule("echo", new PermissionRule("echo", "", PermissionBehavior.ASK, "probe"))
                        .build());
        ReActAgent agent = builder.enablePendingToolRecovery(pendingToolRecovery).build();

        Msg first = agent.call(List.of(Msg.builderForRole(MsgRole.USER).textContent("请回显 hello").build()))
                .block(CALL_TIMEOUT);

        List<ToolUseBlock> pending = first == null ? List.of() : first.getContentBlocks(ToolUseBlock.class);
        StringBuilder report = new StringBuilder();
        report.append("\n########## tool_use 带原始 JSON content=").append(withRawContent)
                .append(", enablePendingToolRecovery(").append(pendingToolRecovery).append(") ##########\n");
        report.append("== 阶段 1：ASK 之后的第一次 call ==\n");
        report.append("first 返回值 = ").append(describe(first)).append('\n');
        report.append("模型被调用次数 = ").append(model.seen.size()).append('\n');
        report.append("阶段 1 结束时工具被执行次数 = ").append(EchoTool.EXECUTIONS.get())
                .append("（0 才说明 ASK 真的把执行停住了）\n");

        if (!pending.isEmpty()) {
            report.append("\n交回给框架的 pending ToolUseBlock：id=").append(pending.get(0).getId())
                    .append(", state=").append(pending.get(0).getState())
                    .append(", input=").append(pending.get(0).getInput()).append('\n');
            Msg resume = Msg.builderForRole(MsgRole.USER)
                    .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, List.of(new ConfirmResult(true, pending.get(0)))))
                    .build();
            Msg second = agent.call(List.of(resume)).block(CALL_TIMEOUT);
            report.append("\n== 阶段 2：人批准（ConfirmResult(true)）之后的第二次 call ==\n");
            report.append("second 返回值 = ").append(describe(second)).append('\n');
            report.append("模型被调用次数 = ").append(model.seen.size()).append('\n');
            report.append("工具被执行次数 = ").append(EchoTool.EXECUTIONS.get()).append('\n');
            report.append("MARKER 出现在第几次模型调用里 = ").append(markerPositions(model)).append('\n');
            if (model.seen.size() > 1) {
                report.append("第二次模型调用的完整输入：\n");
                for (Msg m : model.seen.get(1)) {
                    report.append("   ").append(describe(m)).append('\n');
                }
            }
        } else {
            report.append("\n没有 pendingAsk —— 第一次 call 就没停下来，恢复路径无从测起。\n");
        }

        Files.writeString(Path.of("research/pause-resume-probe.txt"), report.toString(),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        System.out.println(report);
    }

    private static String markerPositions(ScriptedModel model) {
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < model.seen.size(); i++) {
            if (containsMarker(model.seen.get(i))) {
                hits.add(i + 1);
            }
        }
        return hits.isEmpty() ? "从未出现（工具结果没进任何一次模型调用）" : hits.toString();
    }

    static boolean containsMarker(List<Msg> msgs) {
        return msgs.stream().flatMap(m -> m.getContent().stream())
                .filter(ToolResultBlock.class::isInstance)
                .map(ToolResultBlock.class::cast)
                .anyMatch(b -> String.valueOf(b.getOutput()).contains(MARKER));
    }

    static String describe(Msg msg) {
        if (msg == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("{role=").append(msg.getRole()).append(", blocks=[");
        for (ContentBlock block : msg.getContent()) {
            sb.append(block.getClass().getSimpleName());
            if (block instanceof ToolResultBlock tr) {
                sb.append("(state=").append(tr.getState()).append(",out=").append(tr.getOutput()).append(')');
            } else if (block instanceof ToolUseBlock tu) {
                sb.append("(name=").append(tu.getName()).append(",state=").append(tu.getState())
                        .append(",input=").append(tu.getInput()).append(')');
            } else if (block instanceof TextBlock tb) {
                sb.append("('").append(tb.getText()).append("')");
            }
            sb.append(" | ");
        }
        return sb.append("]}").toString();
    }
}
