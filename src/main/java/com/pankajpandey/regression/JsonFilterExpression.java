/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.pankajpandey.regression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class JsonFilterExpression {
    private static final Pattern JSON_NUMBER = Pattern.compile(
            "-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"
    );

    private final List<Condition> conditions;

    private JsonFilterExpression(List<Condition> conditions) {
        this.conditions = List.copyOf(conditions);
    }

    static JsonFilterExpression parse(String expression) {
        return new Parser(expression).parse();
    }

    boolean matches(JsonNode candidate) {
        if (candidate == null || !candidate.isObject()) {
            return false;
        }
        for (Condition condition : conditions) {
            JsonNode actual = candidate.get(condition.field);
            if (actual == null || !scalarEquals(condition.expected, actual)) {
                return false;
            }
        }
        return true;
    }

    private static boolean scalarEquals(JsonNode expected, JsonNode actual) {
        if (expected.isNumber() && actual.isNumber()) {
            try {
                return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
            } catch (ArithmeticException | NumberFormatException e) {
                // Programmatic non-finite numeric nodes may throw during decimal conversion.
                // They cannot represent or match a JSON numeric literal.
                return false;
            }
        }
        return expected.equals(actual);
    }

    private static final class Condition {
        private final String field;
        private final JsonNode expected;

        private Condition(String field, JsonNode expected) {
            this.field = field;
            this.expected = expected;
        }
    }

    private static final class Parser {
        private final String input;
        private int position;

        private Parser(String input) {
            this.input = input == null ? "" : input;
        }

        private JsonFilterExpression parse() {
            List<Condition> parsed = new ArrayList<>();
            skipWhitespace();
            if (atEnd()) {
                throw error("Filter expression is blank");
            }
            while (true) {
                parsed.add(parseCondition());
                skipWhitespace();
                if (atEnd()) {
                    return new JsonFilterExpression(parsed);
                }
                expect("&&");
                skipWhitespace();
                if (atEnd()) {
                    throw error("Missing condition after &&");
                }
            }
        }

        private Condition parseCondition() {
            skipWhitespace();
            expect("@.");
            String field = parseField();
            skipWhitespace();
            expect("==");
            skipWhitespace();
            JsonNode expected = parseScalar();
            return new Condition(field, expected);
        }

        private String parseField() {
            int start = position;
            if (atEnd() || !(Character.isLetter(input.charAt(position)) || input.charAt(position) == '_')) {
                throw error("Field must start with a letter or underscore");
            }
            position++;
            while (!atEnd()) {
                char ch = input.charAt(position);
                if (!(Character.isLetterOrDigit(ch) || ch == '_' || ch == '-')) {
                    break;
                }
                position++;
            }
            return input.substring(start, position);
        }

        private JsonNode parseScalar() {
            if (atEnd()) {
                throw error("Missing scalar literal");
            }
            char ch = input.charAt(position);
            if (ch == '\'' || ch == '"') {
                return TextNode.valueOf(parseQuoted(ch));
            }
            String raw = parseBareLiteral();
            if ("true".equals(raw)) {
                return BooleanNode.TRUE;
            }
            if ("false".equals(raw)) {
                return BooleanNode.FALSE;
            }
            if ("null".equals(raw)) {
                return NullNode.instance;
            }
            if (!JSON_NUMBER.matcher(raw).matches()) {
                throw error("Unsupported scalar literal: " + raw);
            }
            try {
                return DecimalNode.valueOf(new BigDecimal(raw));
            } catch (NumberFormatException e) {
                throw error("Unsupported scalar literal: " + raw);
            }
        }

        private String parseQuoted(char quote) {
            position++;
            StringBuilder value = new StringBuilder();
            while (!atEnd()) {
                char ch = input.charAt(position++);
                if (ch == quote) {
                    return value.toString();
                }
                if (ch != '\\') {
                    if (ch < 0x20) {
                        throw error("Unescaped control character in quoted string");
                    }
                    value.append(ch);
                    continue;
                }
                if (atEnd()) {
                    throw error("Unterminated escape sequence");
                }
                char escaped = input.charAt(position++);
                switch (escaped) {
                    case '\\': value.append('\\'); break;
                    case '/': value.append('/'); break;
                    case '\'':
                        if (quote != '\'') throw error("Unsupported escape sequence: \\'");
                        value.append('\'');
                        break;
                    case '"':
                        if (quote != '"') throw error("Unsupported escape sequence: \\\"");
                        value.append('"');
                        break;
                    case 'b': value.append('\b'); break;
                    case 'f': value.append('\f'); break;
                    case 'n': value.append('\n'); break;
                    case 'r': value.append('\r'); break;
                    case 't': value.append('\t'); break;
                    case 'u': value.append(parseUnicodeEscape()); break;
                    default: throw error("Unsupported escape sequence: \\" + escaped);
                }
            }
            throw error("Unterminated quoted string");
        }

        private char parseUnicodeEscape() {
            if (position + 4 > input.length()) {
                throw error("Incomplete unicode escape");
            }
            String digits = input.substring(position, position + 4);
            for (int index = 0; index < digits.length(); index++) {
                if (!isAsciiHexDigit(digits.charAt(index))) {
                    throw error("Invalid unicode escape: " + digits);
                }
            }
            position += 4;
            return (char) Integer.parseInt(digits, 16);
        }

        private static boolean isAsciiHexDigit(char value) {
            return (value >= '0' && value <= '9')
                    || (value >= 'a' && value <= 'f')
                    || (value >= 'A' && value <= 'F');
        }

        private String parseBareLiteral() {
            int start = position;
            while (!atEnd()) {
                char ch = input.charAt(position);
                if (Character.isWhitespace(ch) || ch == '&') {
                    break;
                }
                position++;
            }
            if (start == position) {
                throw error("Missing scalar literal");
            }
            return input.substring(start, position);
        }

        private void expect(String token) {
            if (!input.startsWith(token, position)) {
                throw error("Expected " + token);
            }
            position += token.length();
        }

        private void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(input.charAt(position))) {
                position++;
            }
        }

        private boolean atEnd() {
            return position >= input.length();
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + position);
        }
    }
}
