package com.im.oldchat.lua;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/** 将 Lua 值严格编码为 RFC 8259 JSON；不依赖不同 Android 版本的 JSONObject 实现。 */
public final class CipJsonEncoder {
    private static final int MAX_DEPTH = 16;
    private static final int MAX_ITEMS = 4096;

    private CipJsonEncoder() { }

    public static String encode(LuaValue value) throws Exception {
        StringBuilder output = new StringBuilder(256);
        append(value, output, 0);
        return output.toString();
    }

    private static void append(LuaValue value, StringBuilder output, int depth) throws Exception {
        if (depth > MAX_DEPTH) throw new Exception("Lua table 嵌套过深");
        if (value == null || value.isnil()) {
            output.append("null");
        } else if (value.isboolean()) {
            output.append(value.toboolean() ? "true" : "false");
        } else if (value.isstring()) {
            // 必须优先于 isnumber：LuaJ 中字符串 "1" 的 isnumber() 也为 true，
            // 若按数字编码会导致服务端 string 字段（如 app_version）解码失败。
            appendString(value.tojstring(), output);
        } else if (value.isnumber()) {
            double number = value.todouble();
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                throw new Exception("JSON 不支持 NaN 或 Infinity");
            }
            if (value.isint()) output.append(value.toint());
            else {
                String text = Double.toString(number);
                if (text.endsWith(".0")) text = text.substring(0, text.length() - 2);
                output.append(text);
            }
        } else if (value.istable()) {
            appendTable(value.checktable(), output, depth + 1);
        } else {
            throw new Exception("JSON 不支持 Lua 类型：" + value.typename());
        }
    }

    private static void appendTable(LuaTable table, StringBuilder output, int depth) throws Exception {
        LuaValue[] keys = table.keys();
        if (keys.length > MAX_ITEMS) throw new Exception("Lua table 过大");
        int length = table.length();
        boolean array = keys.length == length;
        if (array) {
            boolean[] seen = new boolean[length + 1];
            for (int i = 0; i < keys.length; i++) {
                LuaValue key = keys[i];
                if (!key.isint()) { array = false; break; }
                int index = key.toint();
                if (index < 1 || index > length || seen[index]) { array = false; break; }
                seen[index] = true;
            }
        }
        if (array) {
            output.append('[');
            for (int i = 1; i <= length; i++) {
                if (i > 1) output.append(',');
                append(table.get(i), output, depth);
            }
            output.append(']');
            return;
        }
        output.append('{');
        boolean first = true;
        for (int i = 0; i < keys.length; i++) {
            LuaValue key = keys[i];
            if (!key.isstring() && !key.isnumber()) {
                throw new Exception("JSON 对象键必须是字符串或数字");
            }
            if (!first) output.append(',');
            first = false;
            appendString(key.tojstring(), output);
            output.append(':');
            append(table.get(key), output, depth);
        }
        output.append('}');
    }

    private static void appendString(String value, StringBuilder output) {
        output.append('"');
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"': output.append("\\\""); break;
                    case '\\': output.append("\\\\"); break;
                    case '\b': output.append("\\b"); break;
                    case '\f': output.append("\\f"); break;
                    case '\n': output.append("\\n"); break;
                    case '\r': output.append("\\r"); break;
                    case '\t': output.append("\\t"); break;
                    default:
                        if (c < 0x20 || (c >= 0xD800 && c <= 0xDFFF
                                && !validSurrogate(value, i))) {
                            appendUnicode(c, output);
                        } else {
                            output.append(c);
                        }
                }
            }
        }
        output.append('"');
    }

    private static boolean validSurrogate(String value, int index) {
        char c = value.charAt(index);
        if (Character.isHighSurrogate(c)) {
            return index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1));
        }
        return Character.isLowSurrogate(c) && index > 0 && Character.isHighSurrogate(value.charAt(index - 1));
    }

    private static void appendUnicode(char value, StringBuilder output) {
        final char[] hex = "0123456789abcdef".toCharArray();
        output.append("\\u");
        output.append(hex[(value >> 12) & 15]);
        output.append(hex[(value >> 8) & 15]);
        output.append(hex[(value >> 4) & 15]);
        output.append(hex[value & 15]);
    }
}
