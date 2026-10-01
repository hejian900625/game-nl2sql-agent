package io.github.hejian.gamenl2sql.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.github.hejian.gamenl2sql.db.GameDatabase;
import io.github.hejian.gamenl2sql.guard.SqlGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 造 agent 的地方。为什么不是一个 bean 就完事：探活实测 {@code AgentState.context} 会跨
 * {@code call()} 累积（第 1 轮后 4 条、第 2 轮后 8 条），评测若共用同一个实例，25 道题会互相
 * 看见上一题的 SQL 和数字，那个准确率就是假的。所以每道题要 new 一个。
 *
 * <p>prompt 里的 schema 只在启动时算一次：库内容在一次进程里不变，重算 25 次只是白付开销。
 */
@Component
@EnableConfigurationProperties(GameAgentProperties.class)
public class AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

    private final Model model;
    private final Toolkit toolkit;
    private final GameAgentProperties props;
    private final String sysPrompt;

    public AgentFactory(Model model, Toolkit toolkit, GameDatabase database, GameAgentProperties props) {
        if (props.maxIters() <= 0) {
            throw new IllegalStateException("game.agent.max-iters 必须是正数，当前=" + props.maxIters());
        }
        this.model = model;
        this.toolkit = toolkit;
        this.props = props;
        String ddl = database.schemaDdl(SqlGuard.ALLOWED_TABLES);
        this.sysPrompt = SystemPrompt.render(ddl, props.today(), props.maxIters());
        log.info("[prompt] 系统 prompt {} 字符，其中 schema {} 字符，今天={}，maxIters={}",
                sysPrompt.length(), ddl.length(), props.today(), props.maxIters());
    }

    /**
     * 一个新实例。注意 {@code build()} 内部会 {@code toolkit.copy()}，所以这里传的是共享的
     * Toolkit bean 引用也没关系 —— agent 拿到的已经是副本，不会互相污染状态。
     */
    public ReActAgent create() {
        return ReActAgent.builder()
                .name(props.name())
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(toolkit)
                .maxIters(props.maxIters())
                .build();
    }

    public String sysPrompt() {
        return sysPrompt;
    }

    public GameAgentProperties properties() {
        return props;
    }
}
