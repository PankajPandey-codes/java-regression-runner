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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class JsonComparator {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ARRAY_SELECTOR_PATTERN = "\\[[^\\]]*\\]";

    private JsonComparator() {}

    static ComparisonConfig emptyConfig() {
        return new ComparisonConfig(Collections.<String>emptySet(), Collections.<String, UnorderedArraySpec>emptyMap());
    }

    static ComparisonConfig config(Set<String> ignoreFields) {
        return new ComparisonConfig(ignoreFields, Collections.<String, UnorderedArraySpec>emptyMap());
    }

    static ComparisonConfig configFromExpectedStep(JsonNode expectedStep) {
        return new ComparisonConfig(
                collectIgnoreFields(expectedStep),
                collectUnorderedArraySpecs(expectedStep == null ? null : expectedStep.get("unorderedArrays"))
        );
    }

    static Set<String> collectCommonIgnoreFields(JsonNode expectedStep) {
        Set<String> ignore = new LinkedHashSet<>();
        addTextArray(expectedStep == null ? null : expectedStep.get("commonIgnore"), ignore);
        return ignore;
    }

    static Set<String> collectCaseIgnoreFields(JsonNode expectedStep) {
        Set<String> ignore = new LinkedHashSet<>();
        addTextArray(expectedStep == null ? null : expectedStep.get("ignore"), ignore);
        return ignore;
    }

    static Set<String> collectIgnoreFields(JsonNode expectedStep) {
        Set<String> ignore = new LinkedHashSet<>();
        ignore.addAll(collectCaseIgnoreFields(expectedStep));
        ignore.addAll(collectCommonIgnoreFields(expectedStep));
        return ignore;
    }

    static List<Map<String, Object>> compare(JsonNode expected, JsonNode actual, ComparisonConfig config) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (isBodyAssertionDisabled(expected)) {
            return out;
        }
        compareNode("", expected, actual, config == null ? emptyConfig() : config, out);
        return out;
    }

    static JsonNode pruneIgnored(JsonNode node, Set<String> ignore) {
        return pruneIgnored(node, ignore, "");
    }

    private static void compareNode(String path, JsonNode expected, JsonNode actual, ComparisonConfig config, List<Map<String, Object>> out) {
        if (shouldIgnore(path, config.ignoreFields)) {
            return;
        }

        if (expected == null || expected.isNull()) {
            return;
        }

        if (expected.isObject()) {
            if (actual == null || !actual.isObject()) {
                mismatch(path, expected, actual, "type_mismatch", out);
                return;
            }
            Iterator<String> fields = expected.fieldNames();
            while (fields.hasNext()) {
                String f = fields.next();
                String child = path.isEmpty() ? f : path + "." + f;
                compareNode(child, expected.get(f), actual.get(f), config, out);
            }
            Iterator<String> actualFields = actual.fieldNames();
            while (actualFields.hasNext()) {
                String f = actualFields.next();
                if (expected.has(f)) {
                    continue;
                }
                String child = path.isEmpty() ? f : path + "." + f;
                if (shouldIgnore(child, config.ignoreFields)) {
                    continue;
                }
                mismatch(child, null, actual.get(f), "unexpected_field", out);
            }
            return;
        }

        if (expected.isArray()) {
            compareArray(path, expected, actual, config, out);
            return;
        }

        BigDecimal evNum = comparableNumber(expected);
        BigDecimal avNum = comparableNumber(actual);
        boolean compareAsNumber = shouldCompareAsNumber(expected) || shouldCompareAsNumber(actual);
        if (compareAsNumber && evNum != null && avNum != null) {
            if (evNum.compareTo(avNum) != 0) {
                mismatch(path, expected, actual, "value_mismatch", out);
            }
            return;
        }

        JsonNode embeddedExpected = parseEmbeddedJson(expected);
        JsonNode embeddedActual = parseEmbeddedJson(actual);
        if (embeddedExpected != null && embeddedActual != null) {
            compareNode(path, embeddedExpected, embeddedActual, config, out);
            return;
        }

        String ev = normalized(expected);
        String av = normalized(actual);
        if (!ev.equals(av)) {
            mismatch(path, expected, actual, "value_mismatch", out);
        }
    }

    private static void compareArray(String path, JsonNode expected, JsonNode actual, ComparisonConfig config, List<Map<String, Object>> out) {
        if (actual == null || !actual.isArray()) {
            mismatch(path, expected, actual, "type_mismatch", out);
            return;
        }

        List<Map<String, Object>> strictOut = new ArrayList<>();
        compareArrayInOrder(path, expected, actual, config, strictOut);
        if (strictOut.isEmpty()) {
            return;
        }

        UnorderedArraySpec spec = config.unorderedArray(path);
        if (spec != null) {
            List<Map<String, Object>> unorderedOut = new ArrayList<>();
            compareArrayUnordered(path, expected, actual, config, spec, unorderedOut);
            if (unorderedOut.isEmpty()) {
                return;
            }
            out.addAll(unorderedOut);
            return;
        }

        out.addAll(strictOut);
    }

    private static void compareArrayInOrder(String path, JsonNode expected, JsonNode actual, ComparisonConfig config, List<Map<String, Object>> out) {
        for (int i = 0; i < expected.size(); i++) {
            String child = path + "[" + i + "]";
            JsonNode a = i < actual.size() ? actual.get(i) : null;
            compareNode(child, expected.get(i), a, config, out);
        }
        for (int i = expected.size(); i < actual.size(); i++) {
            String child = path + "[" + i + "]";
            if (shouldIgnore(child, config.ignoreFields)) {
                continue;
            }
            mismatch(child, null, actual.get(i), "unexpected_item", out);
        }
    }

    private static void compareArrayUnordered(
            String path,
            JsonNode expected,
            JsonNode actual,
            ComparisonConfig config,
            UnorderedArraySpec spec,
            List<Map<String, Object>> out
    ) {
        ComparisonConfig elementConfig = config.withIgnoreFields(mergedIgnoreFields(config.ignoreFields, spec.ignoreFields));
        int existingMismatchCount = out.size();
        Map<List<String>, JsonNode> expectedItems = keyedItems(path, expected, spec, "expected", out);
        Map<List<String>, JsonNode> actualItems = keyedItems(path, actual, spec, "actual", out);
        if (out.size() > existingMismatchCount) {
            return;
        }

        for (Map.Entry<List<String>, JsonNode> entry : expectedItems.entrySet()) {
            String childPath = keyPath(path, spec, entry.getKey());
            JsonNode actualItem = actualItems.remove(entry.getKey());
            if (actualItem == null) {
                mismatch(childPath, entry.getValue(), null, "missing_item", out);
                continue;
            }
            compareNode(childPath, entry.getValue(), actualItem, elementConfig, out);
        }

        for (Map.Entry<List<String>, JsonNode> entry : actualItems.entrySet()) {
            mismatch(keyPath(path, spec, entry.getKey()), null, entry.getValue(), "unexpected_item", out);
        }
    }

    private static Map<List<String>, JsonNode> keyedItems(
            String path,
            JsonNode array,
            UnorderedArraySpec spec,
            String side,
            List<Map<String, Object>> out
    ) {
        Map<List<String>, JsonNode> items = new LinkedHashMap<>();
        for (JsonNode item : array) {
            List<String> key = keyTuple(item, spec);
            if (items.containsKey(key)) {
                mismatch(keyPath(path, spec, key), items.get(key), item, "duplicate_" + side + "_key", out);
                continue;
            }
            items.put(key, item);
        }
        return items;
    }

    private static List<String> keyTuple(JsonNode item, UnorderedArraySpec spec) {
        List<String> key = new ArrayList<>();
        for (String field : spec.keyFields) {
            JsonNode value = getDottedPath(item, field);
            key.add(normalized(value));
        }
        return Collections.unmodifiableList(key);
    }

    private static String keyPath(String path, UnorderedArraySpec spec, List<String> key) {
        StringBuilder label = new StringBuilder(path);
        label.append('[');
        for (int i = 0; i < spec.keyFields.size(); i++) {
            if (i > 0) {
                label.append(',');
            }
            label.append(spec.keyFields.get(i)).append('=').append(key.get(i));
        }
        label.append(']');
        return label.toString();
    }

    private static JsonNode getDottedPath(JsonNode node, String path) {
        if (node == null || path == null || path.isBlank()) {
            return null;
        }
        JsonNode current = node;
        for (String part : path.split("\\.")) {
            if (current == null || !current.isObject()) {
                return null;
            }
            current = current.get(part);
        }
        return current;
    }

    private static Set<String> mergedIgnoreFields(Set<String> base, Set<String> scoped) {
        if ((scoped == null || scoped.isEmpty()) && base != null) {
            return base;
        }
        Set<String> merged = new HashSet<>();
        if (base != null) {
            merged.addAll(base);
        }
        if (scoped != null) {
            merged.addAll(scoped);
        }
        return merged;
    }

    private static boolean isBodyAssertionDisabled(JsonNode expected) {
        if (expected == null || expected.isNull()) return true;
        if (expected.isTextual()) {
            String txt = expected.asText().trim();
            if (txt.isEmpty()) return true;
            String compact = txt.replaceAll("\\s+", "");
            if ("[]".equals(compact) || "{}".equals(compact)) return true;
        }
        if (expected.isObject() && expected.size() == 0) return true;
        if (expected.isArray() && expected.size() == 0) return true;
        return false;
    }

    private static JsonNode pruneIgnored(JsonNode node, Set<String> ignore, String path) {
        if (node == null || node.isNull()) return node;
        if (ignore == null || ignore.isEmpty()) return node.deepCopy();
        if (shouldIgnore(path, ignore)) return null;

        JsonNode embeddedJson = parseEmbeddedJson(node);
        if (embeddedJson != null) {
            return pruneIgnored(embeddedJson, ignore, path);
        }

        if (node.isObject()) {
            ObjectNode copy = MAPPER.createObjectNode();
            Iterator<String> fields = node.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                String childPath = path == null || path.isEmpty() ? field : path + "." + field;
                if (shouldIgnore(childPath, ignore)) {
                    continue;
                }
                JsonNode pruned = pruneIgnored(node.get(field), ignore, childPath);
                if (pruned != null) {
                    copy.set(field, pruned);
                }
            }
            return copy;
        }

        if (node.isArray()) {
            ArrayNode copy = MAPPER.createArrayNode();
            for (int i = 0; i < node.size(); i++) {
                String childPath = (path == null ? "" : path) + "[" + i + "]";
                if (shouldIgnore(childPath, ignore)) {
                    continue;
                }
                JsonNode pruned = pruneIgnored(node.get(i), ignore, childPath);
                if (pruned != null) {
                    copy.add(pruned);
                }
            }
            return copy;
        }

        return node.deepCopy();
    }

    private static boolean shouldIgnore(String path, Set<String> ignore) {
        if (ignore == null || ignore.isEmpty() || path == null || path.isEmpty()) return false;
        if (ignore.contains(path)) return true;

        String last = path;
        int dot = last.lastIndexOf('.');
        if (dot >= 0) last = last.substring(dot + 1);
        int bracket = last.indexOf('[');
        if (bracket >= 0) last = last.substring(0, bracket);

        return ignore.contains(last);
    }

    private static void mismatch(String path, JsonNode expected, JsonNode actual, String kind, List<Map<String, Object>> out) {
        Map<String, Object> m = new LinkedHashMap<>();
        String emittedPath = path == null || path.isEmpty() ? "$" : path;
        m.put("path", emittedPath);
        String parentArrayPath = parentArrayPath(emittedPath);
        if (!parentArrayPath.isEmpty()) {
            m.put("parentArrayPath", parentArrayPath);
        }
        m.put("kind", kind);
        m.put("valueType", valueType(expected, actual));
        m.put("expected", normalized(expected));
        m.put("actual", normalized(actual));
        out.add(m);
    }

    private static String parentArrayPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int searchFrom = path.length() - 1;
        while (searchFrom >= 0) {
            int open = path.lastIndexOf('[', searchFrom);
            if (open < 0) {
                return "";
            }
            int close = path.indexOf(']', open);
            if (close > open) {
                String selector = path.substring(open + 1, close);
                if (selector.matches("\\d+")) {
                    return path.substring(0, open);
                }
            }
            searchFrom = open - 1;
        }
        return "";
    }

    private static String valueType(JsonNode expected, JsonNode actual) {
        JsonNode node = firstPresent(expected, actual);
        if (node == null || node.isNull()) return "null";
        if (node.isObject()) return "object";
        if (node.isArray()) return "array";
        if (node.isBoolean()) return "boolean";
        if (node.isNumber()) return "number";
        if (node.isTextual()) return "string";
        return "string";
    }

    private static JsonNode firstPresent(JsonNode expected, JsonNode actual) {
        if (expected != null && !expected.isNull()) {
            return expected;
        }
        return actual;
    }

    private static String normalized(JsonNode n) {
        if (n == null || n.isNull()) return "null";
        if (n.isTextual()) return n.asText();
        return n.toString();
    }

    private static BigDecimal comparableNumber(JsonNode n) {
        if (n == null || n.isNull()) return null;
        try {
            if (n.isNumber()) return n.decimalValue();
            if (n.isTextual()) {
                String t = n.asText().trim();
                if (t.matches("[-+]?\\d+(\\.\\d+)?")) return new BigDecimal(t);
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static boolean shouldCompareAsNumber(JsonNode n) {
        if (n == null || n.isNull()) return false;
        if (n.isNumber()) return true;
        if (!n.isTextual()) return false;
        String t = n.asText().trim();
        if (!t.matches("[-+]?\\d+(\\.\\d+)?")) return false;
        return t.contains(".");
    }

    private static JsonNode parseEmbeddedJson(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        String raw = node.asText();
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return null;
        try {
            return MAPPER.readTree(trimmed);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, UnorderedArraySpec> collectUnorderedArraySpecs(JsonNode node) {
        if (node == null || node.isNull()) {
            return Collections.emptyMap();
        }

        Map<String, UnorderedArraySpec> out = new LinkedHashMap<>();
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                addUnorderedArraySpec(out, entry.getKey(), entry.getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode entry : node) {
                if (entry != null && entry.isObject()) {
                    JsonNode pathNode = entry.get("path");
                    addUnorderedArraySpec(out, pathNode == null ? "" : pathNode.asText(""), entry);
                }
            }
        }
        return out.isEmpty() ? Collections.<String, UnorderedArraySpec>emptyMap() : out;
    }

    private static void addUnorderedArraySpec(Map<String, UnorderedArraySpec> out, String rawPath, JsonNode node) {
        String path = normalizeArrayPath(rawPath == null ? "" : rawPath.trim());
        if (path.isEmpty()) {
            return;
        }

        Set<String> scopedIgnore = new LinkedHashSet<>();
        List<String> keyFields = new ArrayList<>();
        if (node != null && node.isObject()) {
            addTextArray(node.get("keyFields"), keyFields);
            addTextArray(node.get("ignore"), scopedIgnore);
        } else if (node != null && node.isArray()) {
            addTextArray(node, keyFields);
        }

        if (keyFields.isEmpty()) {
            return;
        }
        out.put(path, new UnorderedArraySpec(path, keyFields, scopedIgnore));
    }

    private static void addTextArray(JsonNode node, Set<String> target) {
        if (node == null || !node.isArray()) return;
        for (JsonNode n : node) {
            if (n != null && n.isTextual()) {
                String v = n.asText().trim();
                if (!v.isEmpty()) target.add(v);
            }
        }
    }

    private static void addTextArray(JsonNode node, List<String> target) {
        if (node == null || !node.isArray()) return;
        for (JsonNode n : node) {
            if (n != null && n.isTextual()) {
                String v = n.asText().trim();
                if (!v.isEmpty()) target.add(v);
            }
        }
    }

    private static String normalizeArrayPath(String path) {
        if (path == null) {
            return "";
        }
        return path.trim().replaceAll(ARRAY_SELECTOR_PATTERN, "");
    }

    static final class ComparisonConfig {
        final Set<String> ignoreFields;
        final Map<String, UnorderedArraySpec> unorderedArrays;

        private ComparisonConfig(Set<String> ignoreFields, Map<String, UnorderedArraySpec> unorderedArrays) {
            this.ignoreFields = ignoreFields == null ? Collections.<String>emptySet() : Collections.unmodifiableSet(new HashSet<>(ignoreFields));
            this.unorderedArrays = unorderedArrays == null ? Collections.<String, UnorderedArraySpec>emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(unorderedArrays));
        }

        Set<String> ignoreFields() {
            return ignoreFields;
        }

        ComparisonConfig withIgnoreFields(Set<String> ignoreFields) {
            return new ComparisonConfig(ignoreFields, unorderedArrays);
        }

        private UnorderedArraySpec unorderedArray(String path) {
            if (unorderedArrays.isEmpty()) {
                return null;
            }
            return unorderedArrays.get(normalizeArrayPath(path));
        }
    }

    static final class UnorderedArraySpec {
        final String path;
        final List<String> keyFields;
        final Set<String> ignoreFields;

        private UnorderedArraySpec(String path, List<String> keyFields, Set<String> ignoreFields) {
            this.path = path;
            this.keyFields = keyFields == null ? Collections.<String>emptyList() : Collections.unmodifiableList(new ArrayList<>(keyFields));
            this.ignoreFields = ignoreFields == null ? Collections.<String>emptySet() : Collections.unmodifiableSet(new HashSet<>(ignoreFields));
        }
    }
}
