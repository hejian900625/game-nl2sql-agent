package io.github.hejian.gamenl2sql.guard;

/**
 * 护栏拒绝理由。每个枚举值都必须能直接进评测归因（PLAN §7 的"护栏拒绝"类）
 * 和前端"为什么被拦"提示，所以理由文本是给模型和人同时看的中文短句。
 */
public enum Rejection {

    EMPTY("SQL 为空"),
    PARSE_FAILED("无法解析为单条 SQL 语句"),
    NOT_SELECT("只允许执行 SELECT 查询"),
    UNKNOWN_TABLE("引用了不在授权清单内的表"),
    SYSTEM_OBJECT("引用了数据库系统对象");

    private final String reason;

    Rejection(String reason) {
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
