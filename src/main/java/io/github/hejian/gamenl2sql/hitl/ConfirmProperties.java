package io.github.hejian.gamenl2sql.hitl;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param timeoutSeconds 等人处置的上限，超时按拒绝
 * @param mode           {@code HUMAN}=必须有人点；{@code AUTO_APPROVE}=直接放行（评测用，
 *                       25 道题不可能每题手点一次。护栏四步照常执行，只是省掉人这一层）
 */
@ConfigurationProperties(prefix = "game.confirm")
public record ConfirmProperties(int timeoutSeconds, Mode mode) {

    public enum Mode {
        HUMAN,
        AUTO_APPROVE
    }
}
