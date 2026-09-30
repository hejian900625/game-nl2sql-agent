package io.github.hejian.gamenl2sql.guard;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxRows 护栏第 3 步的 LIMIT 上限，也是模型自带更大 LIMIT 时被压回的值
 */
@ConfigurationProperties(prefix = "game.guard")
public record GuardProperties(int maxRows) {
}
