package io.github.hejian.gamenl2sql.agent;

import io.agentscope.core.tool.Toolkit;
import io.github.hejian.gamenl2sql.tool.RunSqlTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 自建 Toolkit 覆盖 starter 的 {@code agentscopeToolkit()}（那条是 @ConditionalOnMissingBean），
 * 而 {@code agentscopeReActAgent} 以 Toolkit 为入参，所以它会自动拿到注册了 run_sql 的这一个。
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
