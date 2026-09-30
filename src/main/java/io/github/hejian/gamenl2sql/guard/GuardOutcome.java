package io.github.hejian.gamenl2sql.guard;

/**
 * @param sql       放行时：**由 AST 重新序列化出来的** SQL，不是调用方传入的原文
 * @param limitAdded 是否由护栏补了 LIMIT（给前端的"我替你加了什么"提示）
 */
public record GuardOutcome(boolean allowed, String sql, Rejection rejection, String detail, boolean limitAdded) {

    static GuardOutcome pass(String sql, boolean limitAdded) {
        return new GuardOutcome(true, sql, null, null, limitAdded);
    }

    static GuardOutcome reject(Rejection rejection, String detail) {
        return new GuardOutcome(false, null, rejection, detail, false);
    }
}
