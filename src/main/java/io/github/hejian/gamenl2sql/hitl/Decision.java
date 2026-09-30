package io.github.hejian.gamenl2sql.hitl;

/**
 * 人对一条 SQL 的处置。
 *
 * @param approved  false = 拒绝执行
 * @param sql       非 null 表示"人改过的整条 SQL"，直接替换执行（不再回炉模型）
 * @param reason    拒绝理由 / 编辑说明，会原样回给模型
 */
public record Decision(boolean approved, String sql, String reason) {

    public static Decision approve() {
        return new Decision(true, null, null);
    }

    public static Decision edit(String sql) {
        return new Decision(true, sql, null);
    }

    public static Decision deny(String reason) {
        return new Decision(false, null, reason == null || reason.isBlank() ? "人未说明理由" : reason);
    }

    /** 超时未决按拒绝算（§4：不假装成功）。 */
    public static Decision timeout(int seconds) {
        return new Decision(false, null, "人在 " + seconds + " 秒内没有确认，按拒绝处理");
    }

    public boolean edited() {
        return approved && sql != null && !sql.isBlank();
    }
}
