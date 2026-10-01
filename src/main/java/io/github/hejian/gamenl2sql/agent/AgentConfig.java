package io.github.hejian.gamenl2sql.agent;

import io.agentscope.core.ReActAgent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 自建 ReActAgent 覆盖 starter 的 {@code agentscopeReActAgent}（那条是 @ConditionalOnMissingBean），
 * 因为 prompt 必须在运行时由 schema 拼出来，而 yml 里只能放静态字符串。
 *
 * <p>这个 bean 是给交互式问答（W2 的 AG-UI 页面）用的单例；评测走 {@link AgentFactory} 每道题另造一个，
 * 原因见 {@code AgentFactory} 的类注释。
 */
@Configuration(proxyBeanMethods = false)
public class AgentConfig {

    @Bean
    ReActAgent reActAgent(AgentFactory factory) {
        return factory.create();
    }
}
