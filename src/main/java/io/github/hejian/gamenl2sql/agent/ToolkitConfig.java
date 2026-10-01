package io.github.hejian.gamenl2sql.agent;

import io.agentscope.core.tool.Toolkit;
import io.github.hejian.gamenl2sql.tool.RunSqlTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 自建 Toolkit 覆盖 starter 的 {@code agentscopeToolkit()}（那条是 @ConditionalOnMissingBean）。
 * 消费者是 {@link AgentFactory}：agent 由它按 threadId 现造，Toolkit 从这里注入。
 */
@Configuration(proxyBeanMethods = false)
public class ToolkitConfig {

    @Bean
    Toolkit toolkit(RunSqlTool runSqlTool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(runSqlTool);
        return toolkit;
    }
}
