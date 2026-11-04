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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TemplateResolver {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");
    private static final int QUOTED_TOKEN_CONTEXT_CHARS = 12;

    private TemplateResolver() {}

    static Resolution<String> resolveString(String input, ScenarioContext context) {
        if (input == null || !input.contains("{{")) {
            return Resolution.ok(input);
        }
        Matcher matcher = TOKEN.matcher(input);
        StringBuffer out = new StringBuffer();
        List<Map<String, Object>> errors = new ArrayList<>();
        boolean found = false;
        while (matcher.find()) {
            found = true;
            String reference = matcher.group(1).trim();
            Resolution<JsonNode> resolved = context.resolveReference(reference);
            if (resolved.hasErrors()) {
                errors.addAll(resolved.errors());
                continue;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(asTemplateText(resolved.value())));
        }
        if (!found) {
            errors.add(ScenarioContext.error("malformed_template", "$", "Malformed template token", input));
        }
        if (!errors.isEmpty()) {
            return Resolution.errors(errors);
        }
        matcher.appendTail(out);
        return Resolution.ok(out.toString());
    }

    record SqlTemplate(String sql, List<JsonNode> params) {}

    static Resolution<SqlTemplate> resolveSqlTemplate(String sql, ScenarioContext context) {
        if (sql == null || !sql.contains("{{")) {
            return Resolution.ok(new SqlTemplate(sql, List.of()));
        }
        Matcher matcher = TOKEN.matcher(sql);
        StringBuffer out = new StringBuffer();
        List<JsonNode> params = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        boolean found = false;
        while (matcher.find()) {
            found = true;
            String reference = matcher.group(1).trim();
            if (isQuotedSqlToken(sql, matcher.start())) {
                errors.add(ScenarioContext.error(
                        "quoted_sql_token",
                        reference,
                        "token outside any string literal, e.g. = {{t}}, or LIKE CONCAT('%', {{t}}, '%')",
                        quotedTokenContext(sql, matcher.start(), matcher.end())));
                continue;
            }
            Resolution<JsonNode> resolved = context.resolveReference(reference);
            if (resolved.hasErrors()) {
                errors.addAll(resolved.errors());
                continue;
            }
            Resolution<List<JsonNode>> bound = bindSqlValue(reference, resolved.value());
            if (bound.hasErrors()) {
                errors.addAll(bound.errors());
                continue;
            }
            params.addAll(bound.value());
            String placeholders = String.join(",", Collections.nCopies(bound.value().size(), "?"));
            matcher.appendReplacement(out, Matcher.quoteReplacement(placeholders));
        }
        if (!found) {
            errors.add(ScenarioContext.error("malformed_template", "$", "Malformed template token", sql));
        }
        if (!errors.isEmpty()) {
            return Resolution.errors(errors);
        }
        matcher.appendTail(out);
        return Resolution.ok(new SqlTemplate(out.toString(), List.copyOf(params)));
    }

    // Reports the offending literal as written rather than a fabricated '{{token}}', so a
    // LIKE '%{{t}}%' rejection names the wildcards that are the actual problem.
    private static String quotedTokenContext(String sql, int tokenStart, int tokenEnd) {
        int from = Math.max(0, tokenStart - QUOTED_TOKEN_CONTEXT_CHARS);
        int to = Math.min(sql.length(), tokenEnd + QUOTED_TOKEN_CONTEXT_CHARS);
        return (from > 0 ? "…" : "") + sql.substring(from, to) + (to < sql.length() ? "…" : "");
    }

    private static boolean isQuotedSqlToken(String sql, int tokenStart) {
        char openQuote = 0;
        for (int i = 0; i < tokenStart; i++) {
            char c = sql.charAt(i);
            if (openQuote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == openQuote) {
                    openQuote = 0;
                }
            } else if (c == '\'' || c == '"') {
                openQuote = c;
            }
        }
        return openQuote != 0;
    }

    private static Resolution<List<JsonNode>> bindSqlValue(String reference, JsonNode value) {
        if (value == null || value.isNull()) {
            return Resolution.errors(List.of(
                    ScenarioContext.error("null_sql_value", reference, "non-null scalar or array", "null")));
        }
        if (value.isObject()) {
            return Resolution.errors(List.of(
                    ScenarioContext.error("non_scalar_sql_value", reference, "scalar or array of scalars", value.toString())));
        }
        if (value.isArray()) {
            if (value.isEmpty()) {
                return Resolution.errors(List.of(
                        ScenarioContext.error("empty_array_sql_value", reference, "non-empty array", "[]")));
            }
            List<JsonNode> items = new ArrayList<>();
            for (JsonNode item : value) {
                if (item == null || item.isNull() || item.isObject() || item.isArray()) {
                    return Resolution.errors(List.of(ScenarioContext.error(
                            "non_scalar_sql_value", reference, "array of scalars",
                            item == null || item.isNull() ? "null" : item.toString())));
                }
                items.add(item);
            }
            return Resolution.ok(items);
        }
        return Resolution.ok(List.of(value));
    }

    static Resolution<JsonNode> resolveNode(JsonNode node, ScenarioContext context) {
        if (node == null || !containsTemplate(node)) {
            return Resolution.ok(node);
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        JsonNode resolved = resolveNodeInternal(node, context, errors);
        if (!errors.isEmpty()) {
            return Resolution.errors(errors);
        }
        return Resolution.ok(resolved);
    }

    static boolean containsTemplate(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return containsTemplate(node.asText());
        }
        if (node.isObject()) {
            for (JsonNode child : node) {
                if (containsTemplate(child)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsTemplate(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean containsTemplate(String value) {
        return value != null && value.contains("{{");
    }

    private static JsonNode resolveNodeInternal(JsonNode node, ScenarioContext context, List<Map<String, Object>> errors) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isTextual()) {
            String text = node.asText();
            if (!text.contains("{{")) {
                return node;
            }
            Matcher exact = TOKEN.matcher(text.trim());
            if (exact.matches()) {
                Resolution<JsonNode> resolved = context.resolveReference(exact.group(1).trim());
                if (resolved.hasErrors()) {
                    errors.addAll(resolved.errors());
                    return null;
                }
                return resolved.value() == null ? null : resolved.value().deepCopy();
            }
            Resolution<String> resolved = resolveString(text, context);
            if (resolved.hasErrors()) {
                errors.addAll(resolved.errors());
                return null;
            }
            return TextNode.valueOf(resolved.value());
        }
        if (node.isObject()) {
            ObjectNode copy = MAPPER.createObjectNode();
            node.fields().forEachRemaining(entry -> copy.set(entry.getKey(), resolveNodeInternal(entry.getValue(), context, errors)));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = MAPPER.createArrayNode();
            for (JsonNode child : node) {
                JsonNode resolvedChild = resolveNodeInternal(child, context, errors);
                if (resolvedChild == null) {
                    copy.addNull();
                } else {
                    copy.add(resolvedChild);
                }
            }
            return copy;
        }
        return node;
    }

    private static String asTemplateText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        return node.toString();
    }
}
