package io.github.hejian.gamenl2sql.db;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param path                SQLite 文件；仓库里不带 .db（§5），缺失时由 schema+seed 现场生成
 * @param reset               启动即重建，评测前必须为 true，否则分数随调试状态漂移
 * @param queryTimeoutSeconds 单条查询超时；SQLite 没有只读账号，超时是护栏第 4 步的一半
 */
@ConfigurationProperties(prefix = "game.db")
public record DbProperties(String path, boolean reset, int queryTimeoutSeconds) {
}
