package config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SimpleJsonParser {
    private final String text;
    private int index;

    private SimpleJsonParser(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        SimpleJsonParser parser = new SimpleJsonParser(text);
        Object value = parser.parseValue();
        parser.skipWhitespace();

        if (!parser.isAtEnd()) {
            throw new IllegalArgumentException("Unexpected content after JSON document");
        }

        return value;
    }

    private Object parseValue() {
        skipWhitespace();

        if (isAtEnd()) {
            throw new IllegalArgumentException("Unexpected end of JSON");
        }

        char current = text.charAt(index);

        return switch (current) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't' -> parseLiteral("true", Boolean.TRUE);
            case 'f' -> parseLiteral("false", Boolean.FALSE);
            case 'n' -> parseLiteral("null", null);
            default -> {
                if (current == '-' || Character.isDigit(current)) {
                    yield parseNumber();
                }

                throw new IllegalArgumentException("Unexpected JSON value at position " + index);
            }
        };
    }

    private Map<String, Object> parseObject() {
        expect('{');
        Map<String, Object> object = new LinkedHashMap<>();
        skipWhitespace();

        if (peek('}')) {
            index++;
            return object;
        }

        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            Object value = parseValue();
            object.put(key, value);
            skipWhitespace();

            if (peek('}')) {
                index++;
                return object;
            }

            expect(',');
        }
    }

    private List<Object> parseArray() {
        expect('[');
        List<Object> array = new ArrayList<>();
        skipWhitespace();

        if (peek(']')) {
            index++;
            return array;
        }

        while (true) {
            array.add(parseValue());
            skipWhitespace();

            if (peek(']')) {
                index++;
                return array;
            }

            expect(',');
        }
    }

    private String parseString() {
        expect('"');
        StringBuilder value = new StringBuilder();

        while (!isAtEnd()) {
            char current = text.charAt(index++);

            if (current == '"') {
                return value.toString();
            }

            if (current != '\\') {
                value.append(current);
                continue;
            }

            if (isAtEnd()) {
                throw new IllegalArgumentException("Unterminated JSON escape");
            }

            char escaped = text.charAt(index++);

            switch (escaped) {
                case '"' -> value.append('"');
                case '\\' -> value.append('\\');
                case '/' -> value.append('/');
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> value.append(parseUnicodeEscape());
                default -> throw new IllegalArgumentException("Invalid JSON escape");
            }
        }

        throw new IllegalArgumentException("Unterminated JSON string");
    }

    private char parseUnicodeEscape() {
        if (index + 4 > text.length()) {
            throw new IllegalArgumentException("Invalid unicode escape");
        }

        int value = 0;

        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(text.charAt(index++), 16);

            if (digit == -1) {
                throw new IllegalArgumentException("Invalid unicode escape");
            }

            value = (value << 4) + digit;
        }

        return (char) value;
    }

    private Object parseNumber() {
        int start = index;

        if (peek('-')) {
            index++;
        }

        consumeDigits();

        if (peek('.')) {
            index++;
            consumeDigits();
        }

        if (peek('e') || peek('E')) {
            index++;

            if (peek('+') || peek('-')) {
                index++;
            }

            consumeDigits();
        }

        String raw = text.substring(start, index);

        if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
            return Double.parseDouble(raw);
        }

        return Long.parseLong(raw);
    }

    private void consumeDigits() {
        int start = index;

        while (!isAtEnd() && Character.isDigit(text.charAt(index))) {
            index++;
        }

        if (start == index) {
            throw new IllegalArgumentException("Expected JSON number digit");
        }
    }

    private Object parseLiteral(String expected, Object value) {
        if (!text.startsWith(expected, index)) {
            throw new IllegalArgumentException("Invalid JSON literal");
        }

        index += expected.length();
        return value;
    }

    private void skipWhitespace() {
        while (!isAtEnd() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
    }

    private boolean peek(char expected) {
        return !isAtEnd() && text.charAt(index) == expected;
    }

    private void expect(char expected) {
        if (isAtEnd() || text.charAt(index) != expected) {
            throw new IllegalArgumentException("Expected '" + expected + "' at JSON position " + index);
        }

        index++;
    }

    private boolean isAtEnd() {
        return index >= text.length();
    }
}
