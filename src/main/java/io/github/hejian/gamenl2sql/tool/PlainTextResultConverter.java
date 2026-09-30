package io.github.hejian.gamenl2sql.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolResultConverter;

import java.lang.reflect.Type;

/**
 * 框架默认的转换器把工具返回值 JSON 序列化，String 返回值因此会多套一层引号、
 * 换行变成字面量 {@code \n}。run_sql 的返回本来就是给人和模型读的纯文本。
 */
public class PlainTextResultConverter implements ToolResultConverter {

    @Override
    public ToolResultBlock convert(Object result, Type type) {
        return ToolResultBlock.text(result == null ? "" : String.valueOf(result));
    }
}
