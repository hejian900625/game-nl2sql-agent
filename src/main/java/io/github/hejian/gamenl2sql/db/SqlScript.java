package io.github.hejian.gamenl2sql.db;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 .sql 脚本切成单条语句。
 *
 * <p>不是偷懒的便利方法，是硬约束：SQLite 默认 {@code SQLITE_MAX_SQL_LENGTH} 是 1,000,000 字节，
 * 而 {@code seed_data.sql} 有 1,015,282 字节 —— 整文件交给 {@code Statement.execute()} 会直接
 * {@code SQLITE_TOOBIG: statement too long}（2026-09-30 实测）。
 */
final class SqlScript {

    private SqlScript() {
    }

    static List<String> split(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        boolean inIdentifier = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < script.length(); i++) {
            char ch = script.charAt(i);
            char next = i + 1 < script.length() ? script.charAt(i + 1) : '\0';

            if (inLineComment) {
                current.append(ch);
                if (ch == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                current.append(ch);
                if (ch == '*' && next == '/') {
                    current.append(next);
                    i++;
                    inBlockComment = false;
                }
                continue;
            }
            if (inString) {
                current.append(ch);
                if (ch == '\'') {
                    if (next == '\'') {
                        // SQL 的 '' 是转义后的一个引号，不是字符串结束
                        current.append(next);
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            if (inIdentifier) {
                current.append(ch);
                if (ch == '"') {
                    inIdentifier = false;
                }
                continue;
            }
            if (ch == '-' && next == '-') {
                inLineComment = true;
                current.append(ch);
                continue;
            }
            if (ch == '/' && next == '*') {
                inBlockComment = true;
                current.append(ch);
                continue;
            }
            if (ch == '\'') {
                inString = true;
            } else if (ch == '"') {
                inIdentifier = true;
            } else if (ch == ';') {
                statements.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        statements.add(current.toString());
        statements.removeIf(String::isBlank);
        return statements;
    }
}
