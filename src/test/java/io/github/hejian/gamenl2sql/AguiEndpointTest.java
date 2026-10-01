package io.github.hejian.gamenl2sql;

import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.spring.boot.agui.common.AguiProperties;
import io.agentscope.spring.boot.agui.common.DefaultAgentResolver;
import io.agentscope.spring.boot.agui.common.ThreadSessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AG-UI 这条支线的接线检查（PLAN §2 验收项 1、3）：路由装上了、页面发得出去、配置真的生效。
 *
 * <p>必须真起一个 reactive server：{@code AgentscopeAguiWebFluxAutoConfiguration} 上有
 * {@code @ConditionalOnWebApplication(type = REACTIVE)}，用 WebEnvironment.NONE 时它根本不生效，
 * 拿 RouterFunction 断言就是自欺。
 *
 * <p>这里一次模型都不打（api-key 是占位串），只验接线。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "game.model.api-key=placeholder-no-network-call")
class AguiEndpointTest {

    @Autowired
    ApplicationContext context;

    @LocalServerPort
    int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void aguiConfigurationIsActuallyBound() {
        AguiProperties props = context.getBean(AguiProperties.class);
        // §8 的决定：会话状态归框架。这条漂移了，多轮问题就会失忆，而页面上一切正常
        assertThat(props.isServerSideMemory()).isTrue();
        assertThat(props.getDefaultAgentId()).isEqualTo("default");
        // 确认门要显示模型写的 SQL，靠的就是 args 事件
        assertThat(props.isEmitToolCallArgs()).isTrue();
        assertThat(props.getPathPrefix()).isEqualTo("/agui");
    }

    @Test
    void aguiRoutesAreRegistered() {
        assertThat(context.getBeanNamesForType(RouterFunction.class))
                .as("webflux 那条 AG-UI autoconfig 没生效")
                .isNotEmpty();
    }

    @Test
    void demoPageIsServed() {
        client().get().uri("/").exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/html")
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("/js/agui-client.js")
                        .contains("确认执行这条 SQL？"));
    }

    @Test
    void confirmEndpointsAreAlive() {
        client().get().uri("/api/confirm/pending").exchange()
                .expectStatus().isOk()
                .expectBody().json("[]");
    }

    /**
     * §8 的边界，两头都要钉住：同一个 (threadId, user) 必须拿回同一个 agent（多轮指代继承靠这个），
     * 换了 threadId 必须是另一个（否则两个浏览器共用一份 context，A 的问题会渗进 B）。
     *
     * <p>参数顺序按字节码局部变量表：{@code resolveAgent(agentId, threadId, userId)}，
     * 内部转成 {@code ThreadSessionManager.getOrCreateAgent(userId, threadId, agentId, factory)}。
     */
    @Test
    void threadSessionIsolatesThreadsAndReusesWithinOne() {
        DefaultAgentResolver resolver = new DefaultAgentResolver(
                context.getBean(AguiAgentRegistry.class),
                context.getBean(ThreadSessionManager.class),
                context.getBean(AguiProperties.class).isServerSideMemory());

        Object first = resolver.resolveAgent("default", "thread-a", "hejian");
        assertThat(resolver.resolveAgent("default", "thread-a", "hejian")).isSameAs(first);
        assertThat(resolver.resolveAgent("default", "thread-b", "hejian")).isNotSameAs(first);
    }

    /**
     * 空 body 不是合法的 RunAgentInput，但不能是 404 —— 404 意味着路径没对上（path-prefix 拼错最常见）。
     * 具体 4xx 是哪一档由框架决定，这里不锁死，只锁"路由存在"。
     */
    @Test
    void aguiRunPathIsMapped() {
        client().post().uri("/agui/run").bodyValue("{}").exchange()
                .expectStatus().is4xxClientError();
    }
}
