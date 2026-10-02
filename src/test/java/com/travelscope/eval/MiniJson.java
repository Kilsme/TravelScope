package com.travelscope.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯 Java 递归下降 JSON 解析器（EvalCase 用例库专用，零外部依赖）。
 *
 * <p>支持标准 JSON：对象（→ LinkedHashMap，保持键序）、数组（→ ArrayList）、
 * 字符串（含 {@code \" \\ \/ \b \f \n \r \t} 与四位十六进制 Unicode 转义）、数字（整数→Long，含小数或指数→Double）、
 * true/false/null。拒绝尾随内容与非法转义，异常消息带出错位置。
 */
final class MiniJson {

    private MiniJson() {
    }

    static Object parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("JSON 解析失败: 输入为 null");
        }
        Parser parser = new Parser(json);
        parser.skipWhitespace();
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.eof()) {
            throw parser.error("JSON 结束后存在多余内容");
        }
        return value;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean eof() {
            return pos >= text.length();
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("JSON 解析失败（位置 " + pos + "）: " + message);
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            if (eof()) {
                throw error("意外结束");
            }
            return text.charAt(pos);
        }

        Object parseValue() {
            return switch (peek()) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            pos++; // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("对象键必须是字符串");
                }
                String key = parseString();
                skipWhitespace();
                if (peek() != ':') {
                    throw error("对象键后应为 ':'");
                }
                pos++;
                skipWhitespace();
                map.put(key, parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return map;
                } else {
                    throw error("对象内应为 ',' 或 '}'");
                }
            }
        }

        private List<Object> parseArray() {
            pos++; // '['
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw error("数组内应为 ',' 或 ']'");
                }
            }
        }

        private String parseString() {
            pos++; // '"'
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw error("字符串未闭合");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    sb.append(parseEscape());
                } else if (c < 0x20) {
                    throw error("字符串含非法控制字符");
                } else {
                    sb.append(c);
                }
            }
        }

        private char parseEscape() {
            if (eof()) {
                throw error("转义序列不完整");
            }
            char c = text.charAt(pos++);
            return switch (c) {
                case '"' -> '"';
                case '\\' -> '\\';
                case '/' -> '/';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> parseUnicodeEscape();
                default -> throw error("非法转义字符 '\\" + c + "'");
            };
        }

        private char parseUnicodeEscape() {
            if (pos + 4 > text.length()) {
                throw error("\\u 转义不完整");
            }
            int code = 0;
            for (int i = 0; i < 4; i++) {
                char hex = text.charAt(pos++);
                int digit = Character.digit(hex, 16);
                if (digit < 0) {
                    throw error("\\u 转义含非十六进制字符 '" + hex + "'");
                }
                code = (code << 4) | digit;
            }
            return (char) code;
        }

        private Object parseLiteral(String literal, Object value) {
            if (!text.startsWith(literal, pos)) {
                throw error("非法字面量");
            }
            pos += literal.length();
            return value;
        }

        private Object parseNumber() {
            int start = pos;
            if (!eof() && text.charAt(pos) == '-') {
                pos++;
            }
            while (!eof() && isNumberChar(text.charAt(pos))) {
                pos++;
            }
            if (pos == start) {
                throw error("非法值");
            }
            String num = text.substring(start, pos);
            try {
                if (num.indexOf('.') < 0 && num.indexOf('e') < 0 && num.indexOf('E') < 0) {
                    return Long.parseLong(num);
                }
                return Double.parseDouble(num);
            } catch (NumberFormatException e) {
                throw error("非法数字 '" + num + "'");
            }
        }

        private static boolean isNumberChar(char c) {
            return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
        }
    }
}
