package io.github.hejian.gamenl2sql;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.github.hejian.gamenl2sql.hitl.ConfirmationGate;
import io.github.hejian.gamenl2sql.hitl.Decision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认配置（game.confirm.mode=human）下的整条链路：Spring 装的 Toolkit → 护栏 → 挂起等人 → 执行。
 * edit / deny / 超时三种处置在 RunSqlToolTest 里逐个验，这里只走"人点了同意"这一条。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "game.model.api-key=placeholder-no-network-call")
class AgentBeanRegistrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void agentBeansAreRegistered() {
        Model model = context.getBean(Model.class);
        assertThat(model.getModelName()).isEqualTo("deepseek-flash");
        assertThat(model.supportsNativeStructuredOutput()).isFalse();
        assertThat(context.getBean(ReActAgent.class)).isNotNull();
        assertThat(context.getBean(InMemoryMemory.class)).isNotNull();

        Toolkit toolkit = context.getBean(Toolkit.class);
        assertThat(toolkit.getToolNames()).containsExactly("run_sql");

        ReActAgent agent = context.getBean(ReActAgent.class);
        System.out.println("[gate4] Model      = " + model.getClass().getName()
                + " id=" + context.getBeanNamesForType(Model.class)[0]);
        System.out.println("[gate4] ReActAgent = " + agent.getClass().getName()
                + " id=" + context.getBeanNamesForType(ReActAgent.class)[0]);
    }

    @Test
    void toolCallSuspendsUntilTheHumanDecides() throws Exception {
        Toolkit toolkit = context.getBean(Toolkit.class);
        ConfirmationGate gate = context.getBean(ConfirmationGate.class);
        String args = JsonUtils.getJsonCodec().toJson(Map.of("sql", "select count(*) from srv"));
        ToolUseBlock call = ToolUseBlock.builder()
                .id("it-call").name("run_sql")
                .input(Map.of("sql", "select count(*) from srv")).content(args)
                .build();

        AtomicReference<String> text = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        toolkit.callTool(ToolCallParam.builder().toolUseBlock(call).input(call.getInput()).build())
                .map(AgentBeanRegistrationTest::textOf)
                .subscribe(text::set, error -> finished.countDown(), () -> finished.countDown());

        List<ConfirmationGate.Request> pending = gate.pendingSnapshot();
        assertThat(pending).as("没挂起等人处置").hasSize(1);
        assertThat(gate.decide(pending.get(0).id(), Decision.approve())).isTrue();

        assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
        // 纯文本转换器生效：没有 JSON 那层引号，换行还是换行
        assertThat(text.get()).contains("执行 SQL: SELECT count(*) FROM srv LIMIT 200\n返回 1 行");
    }

    private static String textOf(ToolResultBlock block) {
        return block.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(item -> ((TextBlock) item).getText())
                .reduce("", (a, b) -> a + b);
    }
}

