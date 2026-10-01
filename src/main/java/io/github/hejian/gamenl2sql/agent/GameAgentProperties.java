package io.github.hejian.gamenl2sql.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;

/**
 * @param name     agent 名
 * @param maxIters 单次 run 的步数上限（§4 校准值 4 = 3 条 SQL + 1 次校验），同时也是 prompt 里写的那个上限
 * @param today    库内"今天"，评测锚点（§5）。绑定成 LocalDate 而不是 String：写错格式就让启动失败，
 *                 别让相对时间的 25 道题一起静默漂移
 */
@ConfigurationProperties(prefix = "game.agent")
public record GameAgentProperties(String name, int maxIters, LocalDate today) {
}
