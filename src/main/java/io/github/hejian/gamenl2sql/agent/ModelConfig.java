package io.github.hejian.gamenl2sql.agent;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Model bean 走 DeepSeek SPI，不用 agentscope-openai-spring-boot-starter 的自动装配。
 * 理由见 PLAN §3：只有 ModelRegistry 的 "deepseek:" 前缀路径会装 DeepSeekFormatter、
 * 置 nativeStructuredOutput(false)、并按 enableThinking 发 thinking 参数。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeepSeekModelProperties.class)
public class ModelConfig {

    private static final Logger log = LoggerFactory.getLogger(ModelConfig.class);

    private static final String STARTER_DEFAULT = "gpt-4.1-mini";

    @Bean
    Model model(DeepSeekModelProperties props) {
        if (props.name() == null || props.name().isBlank()) {
            throw new IllegalStateException(
                    "game.model.name 未配置。填 DeepSeek 的模型 id（不带 deepseek: 前缀），例如 deepseek-flash。");
        }
        if (STARTER_DEFAULT.equals(props.name())) {
            throw new IllegalStateException(
                    "game.model.name 仍是 openai starter 的默认值 " + STARTER_DEFAULT + "，启动即拒绝。");
        }
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY 未设置。key 只走环境变量，见 .env.example。");
        }

        Model model = ModelRegistry.resolve("deepseek:" + props.name(),
                ModelCreationContext.builder()
                        .apiKey(props.apiKey())
                        .stream(props.stream())
                        .enableThinking(props.enableThinking())
                        .build());

        log.info("[model] resolved name={} stream={} thinking={} nativeStructuredOutput={} contextWindow={}",
                model.getModelName(), props.stream(), props.enableThinking(),
                model.supportsNativeStructuredOutput(), model.getContextWindowSize());
        return model;
    }
}
