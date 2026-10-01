package io.github.hejian.gamenl2sql.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.github.hejian.gamenl2sql.db.GameDatabase;
import io.github.hejian.gamenl2sql.guard.SqlGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 自建 ReActAgent 覆盖 starter 的 {@code agentscopeReActAgent}（那条是 @ConditionalOnMissingBean），
 * 因为 prompt 必须在运行时由 schema 拼出来，而 yml 里只能放静态字符串。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GameAgentProperties.class)
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    @Bean
    ReActAgent reActAgent(Model model, Toolkit toolkit, GameDatabase database, GameAgentProperties props) {
        if (props.maxIters() <= 0) {
            throw new IllegalStateException("game.agent.max-iters 必须是正数，当前=" + props.maxIters());
        }
        String ddl = database.schemaDdl(SqlGuard.ALLOWED_TABLES);
        String sysPrompt = SystemPrompt.render(ddl, props.today(), props.maxIters());
        log.info("[prompt] 系统 prompt {} 字符，其中 schema {} 字符，今天={}，maxIters={}",
                sysPrompt.length(), ddl.length(), props.today(), props.maxIters());
        return ReActAgent.builder()
                .name(props.name())
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(toolkit)
                .maxIters(props.maxIters())
                .build();
    }
}
