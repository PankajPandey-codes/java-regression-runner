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

import java.util.ArrayList;
import java.util.List;

final class JsonPathLite {
    private JsonPathLite() {}

    static Resolution<JsonNode> read(JsonNode root, String path) {
        if (root == null || root.isNull()) {
            return Resolution.errors(List.of(error("missing_reference", path, "Source JSON is null")));
        }
        if (path == null || path.isBlank()) {
            return Resolution.ok(root);
        }
        List<Token> tokens;
        try {
            tokens = tokenize(path.trim());
        } catch (RuntimeException e) {
            return Resolution.errors(List.of(error("malformed_reference", path, e.getMessage())));
        }
        JsonNode current = root;
        for (Token token : tokens) {
            if (token.field != null) {
                if (current == null || !current.isObject() || !current.has(token.field)) {
                    return Resolution.errors(List.of(error("missing_reference", path, "Missing field: " + token.field)));
                }
                current = current.get(token.field);
            } else if (token.filter != null) {
                if (current == null || !current.isArray()) {
                    return Resolution.errors(List.of(error("missing_reference", path, "Expected array for filter")));
                }
                List<JsonNode> matches = new ArrayList<>();
                for (JsonNode candidate : current) {
                    if (token.filter.matches(candidate)) {
                        matches.add(candidate);
                    }
                }
                if (matches.isEmpty()) {
                    return Resolution.errors(List.of(error("missing_reference", path, "Filter matched no array items")));
                }
                if (matches.size() > 1) {
                    return Resolution.errors(List.of(error("ambiguous_reference", path, "Filter matched multiple array items")));
                }
                current = matches.get(0);
            } else {
                if (current == null || !current.isArray()) {
                    return Resolution.errors(List.of(error("missing_reference", path, "Expected array at index: " + token.index)));
                }
                if (token.index < 0 || token.index >= current.size()) {
                    return Resolution.errors(List.of(error("missing_reference", path, "Missing array index: " + token.index)));
                }
                current = current.get(token.index);
            }
        }
        if (current == null || current.isMissingNode()) {
            return Resolution.errors(List.of(error("missing_reference", path, "Path did not resolve")));
        }
        return Resolution.ok(current);
    }

    private static List<Token> tokenize(String path) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < path.length()) {
            char ch = path.charAt(i);
            if (ch == '.') {
                i++;
                continue;
            }
            if (ch == '[') {
                if (path.startsWith("[?", i) && !path.startsWith("[?(", i)) {
                    throw new IllegalArgumentException("Malformed filter path: " + path);
                }
                if (path.startsWith("[?(", i)) {
                    int close = findFilterClose(path, i + 3);
                    String expression = path.substring(i + 3, close);
                    tokens.add(Token.filter(JsonFilterExpression.parse(expression)));
                    i = close + 2;
                    continue;
                }
                int close = path.indexOf(']', i);
                if (close < 0) {
                    throw new IllegalArgumentException("Malformed path: " + path);
                }
                tokens.add(Token.index(Integer.parseInt(path.substring(i + 1, close).trim())));
                i = close + 1;
                continue;
            }
            int start = i;
            while (i < path.length() && path.charAt(i) != '.' && path.charAt(i) != '[') {
                i++;
            }
            String field = path.substring(start, i).trim();
            if (field.isEmpty()) {
                throw new IllegalArgumentException("Malformed path: " + path);
            }
            tokens.add(Token.field(field));
        }
        return tokens;
    }

    private static int findFilterClose(String path, int start) {
        char quote = 0;
        boolean escaped = false;
        for (int i = start; i < path.length() - 1; i++) {
            char ch = path.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                continue;
            }
            if (ch == ')' && path.charAt(i + 1) == ']') {
                return i;
            }
        }
        throw new IllegalArgumentException("Malformed filter path: " + path);
    }

    private static java.util.Map<String, Object> error(String kind, String path, String message) {
        java.util.Map<String, Object> error = new java.util.LinkedHashMap<>();
        error.put("path", path == null || path.isBlank() ? "$" : path);
        error.put("kind", kind);
        error.put("expected", message == null ? "" : message);
        error.put("actual", "null");
        return error;
    }

    private static final class Token {
        private final String field;
        private final int index;
        private final JsonFilterExpression filter;

        private Token(String field, int index, JsonFilterExpression filter) {
            this.field = field;
            this.index = index;
            this.filter = filter;
        }

        static Token field(String field) {
            return new Token(field, -1, null);
        }

        static Token index(int index) {
            return new Token(null, index, null);
        }

        static Token filter(JsonFilterExpression filter) {
            return new Token(null, -1, filter);
        }
    }
}
