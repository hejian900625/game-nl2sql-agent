package io.github.hejian.gamenl2sql.agent;

import io.agentscope.spring.boot.agui.common.AguiAgentRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把自建的 agent 交法交给 AG-UI 注册表（PLAN §8 决定：会话状态用框架的 ThreadSessionManager，不自己存）。
 *
 * <p>必须用 {@code registerFactory} 而不是 {@code register(id, 实例)}：读 2.0.3 的
 * {@code DefaultAgentResolver} 字节码，开了 {@code server-side-memory} 之后走
 * {@code ThreadSessionManager.getOrCreateAgent(threadId, user, agentId, supplier)}，
 * 每个 threadId 用这个 supplier 造一个实例并复用。supplier 若返回同一个单例，
 * 所有浏览器就共用一份 context —— {@code AgentFactory} 类注释里记的累积问题就是这么来的。
 *
 * <p>agentId 取 "default"，和 {@code agentscope.agui.default-agent-id} 对齐：
 * {@code AguiAgentAutoRegistration} 没有 {@code @AguiAgentId} 标注时拿 bean 名当 id，
 * 这里绕开它，直接按名字注册，少一层隐式约定。
 */
@Configuration(proxyBeanMethods = false)
public class AgentConfig {

    static final String AGENT_ID = "default";

    @Bean
    AguiAgentRegistryCustomizer aguiAgentRegistryCustomizer(AgentFactory factory) {
        return registry -> registry.registerFactory(AGENT_ID, factory::create);
    }
}
