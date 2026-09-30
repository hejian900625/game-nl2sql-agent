package io.github.hejian.gamenl2sql.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param name DeepSeek 模型 ID，不含 {@code deepseek:} 前缀（前缀由 ModelConfig 拼）
 */
@ConfigurationProperties(prefix = "game.model")
public record DeepSeekModelProperties(String name, String apiKey, boolean stream, boolean enableThinking) {
}
