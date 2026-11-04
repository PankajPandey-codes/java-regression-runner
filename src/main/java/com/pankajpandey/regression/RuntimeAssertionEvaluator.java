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
import com.fasterxml.jackson.databind.node.NullNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RuntimeAssertionEvaluator {
    private RuntimeAssertionEvaluator() {}

    static List<Map<String, Object>> evaluate(JsonNode expectedStep, JsonNode currentResponse, ScenarioContext context) {
        return evaluateWithTrace(expectedStep, currentResponse, context).mismatches();
    }

    static Evaluation evaluateWithTrace(JsonNode expectedStep, JsonNode currentResponse, ScenarioContext context) {
        List<Map<String, Object>> mismatches = new ArrayList<>();
        List<Map<String, Object>> assertionResults = new ArrayList<>();
        JsonNode assertions = expectedStep == null ? null : expectedStep.get("assertions");
        if (assertions == null || assertions.isNull()) {
            return new Evaluation(mismatches, assertionResults);
        }
        if (!assertions.isArray()) {
            Map<String, Object> mismatch = mismatch("assertions", "assertion_config_error", "assertions array", typeName(assertions), "assertions", "config");
            mismatches.add(mismatch);
            assertionResults.add(assertionResult("assertions", "FAIL", "equals", "config", "", "", "", "", "", "", "assertion_config_error",
                    "Expected assertions array but found " + typeName(assertions)));
            return new Evaluation(mismatches, assertionResults);
        }
        int index = 0;
        for (JsonNode assertion : assertions) {
            index++;
            String name = text(assertion, "name", "assertion" + index);
            if (assertion == null || !assertion.isObject()) {
                Map<String, Object> mismatch = mismatch(assertionPath(name), "assertion_config_error", "object", typeName(assertion), name, "config");
                mismatches.add(mismatch);
                assertionResults.add(assertionResult(name, "FAIL", "equals", "config", "", "", "", "", "", "", "assertion_config_error",
                        "Expected assertion object but found " + typeName(assertion)));
                continue;
            }
            String operator = normalizeOperator(text(assertion, "operator", "equals"));
            String type = text(assertion, "type", "string");
            Source actualSource = source(assertion, true);
            Source expectedSource = source(assertion, false);
            if (!isSupportedOperator(operator)) {
                Map<String, Object> mismatch = mismatch(assertionPath(name), "assertion_config_error", supportedOperators(), operator, name, "config");
                mismatches.add(mismatch);
                assertionResults.add(assertionResult(name, "FAIL", operator, type, actualSource.field, expectedSource.field,
                        actualSource.path, expectedSource.path, "", "", "assertion_config_error",
                        "Unsupported operator " + operator + "; supported operators are " + supportedOperators()));
                continue;
            }
            Resolution<JsonNode> actual = resolveActualValue(assertion, currentResponse, context, name);
            // Unary operators need no expected source, and supplying one is treated as a config error.
            Resolution<JsonNode> expected = isUnaryOperator(operator)
                    ? validateNoExpectedSource(assertion, name)
                    : resolveValue(assertion, currentResponse, context, name, false);
            List<Map<String, Object>> resolutionErrors = new ArrayList<>();
            if (actual.hasErrors()) {
                resolutionErrors.addAll(wrapErrors(name, actual.errors()));
            }
            if (expected.hasErrors()) {
                resolutionErrors.addAll(wrapErrors(name, expected.errors()));
            }
            if (!resolutionErrors.isEmpty()) {
                mismatches.addAll(resolutionErrors);
                Map<String, Object> firstError = resolutionErrors.get(0);
                assertionResults.add(assertionResult(name, "FAIL", operator, type, actualSource.field, expectedSource.field,
                        actualSource.path, expectedSource.path, actual.hasErrors() ? "" : resolvedValue(type, actual.value()),
                        expected.hasErrors() ? "" : resolvedValue(type, expected.value()),
                        String.valueOf(firstError.getOrDefault("kind", "missing_reference")),
                        "Assertion reference could not be resolved"));
                continue;
            }
            ComparisonOutcome comparison = compare(name, operator, type, actual.value(), expected.value());
            if (comparison.mismatch != null) {
                mismatches.add(comparison.mismatch);
            }
            assertionResults.add(assertionResult(name, comparison.passed ? "PASS" : "FAIL", operator, comparison.type,
                    actualSource.field, expectedSource.field, actualSource.path, expectedSource.path,
                    comparison.actualValue, comparison.expectedValue, comparison.failureKind, comparison.message));
        }
        return new Evaluation(mismatches, assertionResults);
    }

    static boolean hasAssertions(JsonNode expectedStep) {
        return expectedStep != null && expectedStep.has("assertions");
    }

    private static Resolution<JsonNode> resolveValue(JsonNode assertion, JsonNode currentResponse, ScenarioContext context, String name, boolean actual) {
        String pathField = actual ? "actualPath" : "expectedPath";
        String valueField = actual ? "actual" : "expected";
        boolean hasPath = hasNonBlankText(assertion, pathField);
        boolean hasValue = assertion.has(valueField);
        boolean hasExpectedValue = !actual && assertion.has("expectedValue");
        int sources = (hasPath ? 1 : 0) + (hasValue ? 1 : 0) + (hasExpectedValue ? 1 : 0);
        if (sources != 1) {
            return Resolution.errors(List.of(ScenarioContext.error(
                    "assertion_config_error",
                    assertionPath(name),
                    "Exactly one " + (actual ? "actual" : "expected") + " source",
                    String.valueOf(sources)
            )));
        }
        if (hasPath) {
            try {
                return JsonPathLite.read(currentResponse, assertion.get(pathField).asText());
            } catch (RuntimeException e) {
                return Resolution.errors(List.of(ScenarioContext.error("malformed_reference", assertionPath(name), e.getMessage(), "null")));
            }
        }
        JsonNode source = hasExpectedValue ? assertion.get("expectedValue") : assertion.get(valueField);
        if (source != null && source.isTextual() && TemplateResolver.containsTemplate(source.asText())) {
            return TemplateResolver.resolveNode(source, context);
        }
        return Resolution.ok(source == null ? NullNode.getInstance() : source);
    }

    private static ComparisonOutcome compare(String name, String operator, String type, JsonNode actual, JsonNode expected) {
        switch (operator) {
            case "equals":
                return compareEquals(name, type, actual, expected);
            case "notEquals":
                return compareNotEquals(name, type, actual, expected);
            case "greaterThanOrEqual":
                return compareNumeric(name, operator, type, actual, expected, ">=");
            case "lessThanOrEqual":
                return compareNumeric(name, operator, type, actual, expected, "<=");
            case "greaterThan":
                return compareNumeric(name, operator, type, actual, expected, ">");
            case "lessThan":
                return compareNumeric(name, operator, type, actual, expected, "<");
            case "notNull":
                return compareNotNull(name, type, actual);
            case "notEmpty":
                return compareNotEmpty(name, type, actual);
            default:
                return ComparisonOutcome.fail(mismatch(assertionPath(name), "assertion_config_error", supportedOperators(), operator, name, "config"),
                        "config", normalized(actual), normalized(expected), "assertion_config_error",
                        "Unsupported operator " + operator + "; supported operators are " + supportedOperators());
        }
    }

    private static ComparisonOutcome compareEquals(String name, String type, JsonNode actual, JsonNode expected) {
        String normalizedType = type == null ? "string" : type.trim();
        switch (normalizedType) {
            case "strictDecimal":
                BigDecimal av = decimal(actual);
                BigDecimal ev = decimal(expected);
                if (av == null || ev == null) {
                    return ComparisonOutcome.fail(mismatch(assertionPath(name), "assertion_type_mismatch", normalized(expected), normalized(actual), name, normalizedType),
                            normalizedType, normalized(actual), normalized(expected), "assertion_type_mismatch",
                            "Expected strictDecimal-compatible values");
                }
                return av.compareTo(ev) == 0
                        ? ComparisonOutcome.pass(normalizedType, av.toPlainString(), ev.toPlainString())
                        : ComparisonOutcome.fail(mismatch(assertionPath(name), "assertion_mismatch", ev.toPlainString(), av.toPlainString(), name, normalizedType),
                                normalizedType, av.toPlainString(), ev.toPlainString(), "assertion_mismatch",
                                "Expected " + ev.toPlainString() + " but actual was " + av.toPlainString());
            case "integer":
                BigInteger ai = integer(actual);
                BigInteger ei = integer(expected);
                if (ai == null || ei == null) {
                    return ComparisonOutcome.fail(mismatch(assertionPath(name), "assertion_type_mismatch", normalized(expected), normalized(actual), name, normalizedType),
                            normalizedType, normalized(actual), normalized(expected), "assertion_type_mismatch",
                            "Expected integer-compatible values");
                }
                return ai.compareTo(ei) == 0
                        ? ComparisonOutcome.pass(normalizedType, ai.toString(), ei.toString())
                        : ComparisonOutcome.fail(mismatch(assertionPath(name), "assertion_mismatch", ei.toString(), ai.toString(), name, normalizedType),
                                normalizedType, ai.toString(), ei.toString(), "assertion_mismatch",
                                "Expected " + ei + " but actual was " + ai);
            case "boolean":
                Boolean ab = bool(actual);
                Boolean eb = bool(expected);
                if (ab == null || eb == null) {
                    return ComparisonOutcome.fail(mismatch(assertionPath(name), "assertion_type_mismatch", normalized(expected), normalized(actual), name, normalizedType),
                            normalizedType, normalized(actual), normalized(expected), "assertion_type_mismatch",
                            "Expected boolean-compatible values");
                }
                return ab.equals(eb)
                        ? ComparisonOutcome.pass(normalizedType, ab.toString(), eb.toString())
                        : ComparisonOutcome.fail(mismatch(assertionPath(name), "assertion_mismatch", eb.toString(), ab.toString(), name, normalizedType),
                                normalizedType, ab.toString(), eb.toString(), "assertion_mismatch",
                                "Expected " + eb + " but actual was " + ab);
            case "string":
                String as = stringValue(actual);
                String es = stringValue(expected);
                return as.equals(es)
                        ? ComparisonOutcome.pass(normalizedType, as, es)
                        : ComparisonOutcome.fail(mismatch(assertionPath(name), "assertion_mismatch", es, as, name, normalizedType),
                                normalizedType, as, es, "assertion_mismatch",
                                "Expected " + es + " but actual was " + as);
            default:
                return ComparisonOutcome.fail(mismatch(assertionPath(name), "assertion_config_error", "supported type", normalizedType, name, "config"),
                        "config", normalized(actual), normalized(expected), "assertion_config_error",
                        "Unsupported assertion type " + normalizedType);
        }
    }

    private static ComparisonOutcome compareNotEquals(String name, String type, JsonNode actual, JsonNode expected) {
        ComparisonOutcome equals = compareEquals(name, type, actual, expected);
        if ("assertion_type_mismatch".equals(equals.failureKind) || "assertion_config_error".equals(equals.failureKind)) {
            return equals;
        }
        return equals.passed
                ? ComparisonOutcome.fail(mismatch(assertionPath(name), "assertion_mismatch", "not " + equals.expectedValue, equals.actualValue, name, equals.type),
                        equals.type, equals.actualValue, equals.expectedValue, "assertion_mismatch",
                        "Expected value to differ from " + equals.expectedValue + " but was equal")
                : ComparisonOutcome.pass(equals.type, equals.actualValue, equals.expectedValue);
    }

    private static ComparisonOutcome compareNumeric(String name, String operator, String type, JsonNode actual, JsonNode expected, String symbol) {
        String normalizedType = type == null ? "string" : type.trim();
        if (!"strictDecimal".equals(normalizedType) && !"integer".equals(normalizedType)) {
            return ComparisonOutcome.fail(mismatch(assertionPath(name), "assertion_config_error", "strictDecimal or integer type", normalizedType, name, "config"),
                    "config", normalized(actual), normalized(expected), "assertion_config_error",
                    "Numeric operator " + operator + " requires type strictDecimal or integer");
        }
        BigDecimal av = numericValue(normalizedType, actual);
        BigDecimal ev = numericValue(normalizedType, expected);
        if (av == null || ev == null) {
            return ComparisonOutcome.fail(mismatch(assertionPath(name), "assertion_type_mismatch", normalized(expected), normalized(actual), name, normalizedType),
                    normalizedType, normalized(actual), normalized(expected), "assertion_type_mismatch",
                    "Expected numeric values for " + operator + " comparison");
        }
        int comparison = av.compareTo(ev);
        boolean passed;
        switch (operator) {
            case "greaterThanOrEqual":
                passed = comparison >= 0;
                break;
            case "lessThanOrEqual":
                passed = comparison <= 0;
                break;
            case "greaterThan":
                passed = comparison > 0;
                break;
            case "lessThan":
                passed = comparison < 0;
                break;
            default:
                passed = false;
        }
        return passed
                ? ComparisonOutcome.pass(normalizedType, av.toPlainString(), ev.toPlainString())
                : ComparisonOutcome.fail(mismatch(assertionPath(name), "assertion_mismatch", symbol + " " + ev.toPlainString(), av.toPlainString(), name, normalizedType),
                        normalizedType, av.toPlainString(), ev.toPlainString(), "assertion_mismatch",
                        "Expected " + symbol + " " + ev.toPlainString() + " but actual was " + av.toPlainString());
    }

    private static ComparisonOutcome compareNotNull(String name, String type, JsonNode actual) {
        String normalizedType = type == null ? "string" : type.trim();
        boolean isNull = actual == null || actual.isNull();
        return !isNull
                ? ComparisonOutcome.pass(normalizedType, normalized(actual), "notNull")
                : ComparisonOutcome.fail(mismatch(assertionPath(name), "assertion_mismatch", "notNull", "null", name, normalizedType),
                        normalizedType, "null", "notNull", "assertion_mismatch", "Expected non-null value but got null");
    }

    private static ComparisonOutcome compareNotEmpty(String name, String type, JsonNode actual) {
        String normalizedType = type == null ? "string" : type.trim();
        boolean empty = isNullOrEmpty(actual);
        return !empty
                ? ComparisonOutcome.pass(normalizedType, normalized(actual), "notEmpty")
                : ComparisonOutcome.fail(mismatch(assertionPath(name), "assertion_mismatch", "notEmpty", normalized(actual), name, normalizedType),
                        normalizedType, normalized(actual), "notEmpty", "assertion_mismatch", "Expected non-empty value but got null/empty");
    }

    private static boolean isUnaryOperator(String operator) {
        return "notNull".equals(operator) || "notEmpty".equals(operator);
    }

    private static boolean isNullOrEmpty(JsonNode node) {
        if (node == null || node.isNull()) return true;
        if (node.isTextual()) return node.asText().isBlank();
        if (node.isArray()) return node.size() == 0;
        if (node.isObject()) return node.size() == 0;
        return false;
    }

    private static Resolution<JsonNode> resolveActualValue(JsonNode assertion, JsonNode currentResponse, ScenarioContext context, String name) {
        int sources = 0;
        if (hasNonBlankText(assertion, "actualPath")) sources++;
        if (assertion != null && assertion.has("actual")) sources++;
        if (assertion != null && assertion.has("actualPaths")) sources++;
        if (sources != 1) {
            return Resolution.errors(List.of(ScenarioContext.error(
                    "assertion_config_error",
                    assertionPath(name),
                    "Exactly one actual source: actualPath, actual, or actualPaths",
                    String.valueOf(sources)
            )));
        }
        if (assertion != null && assertion.has("actualPaths")) {
            return resolveSummedPaths(assertion, currentResponse, name);
        }
        return resolveValue(assertion, currentResponse, context, name, true);
    }

    private static Resolution<JsonNode> validateNoExpectedSource(JsonNode assertion, String name) {
        int sources = expectedSourceCount(assertion);
        if (sources == 0) {
            return Resolution.ok(NullNode.getInstance());
        }
        return Resolution.errors(List.of(ScenarioContext.error(
                "assertion_config_error",
                assertionPath(name),
                "No expected source for unary operators notNull/notEmpty",
                String.valueOf(sources)
        )));
    }

    private static int expectedSourceCount(JsonNode assertion) {
        int sources = 0;
        if (hasNonBlankText(assertion, "expectedPath")) sources++;
        if (assertion != null && assertion.has("expected")) sources++;
        if (assertion != null && assertion.has("expectedValue")) sources++;
        return sources;
    }

    private static Resolution<JsonNode> resolveSummedPaths(JsonNode assertion, JsonNode currentResponse, String name) {
        String aggregation = text(assertion, "actualAggregation", "");
        if (!"sum".equalsIgnoreCase(aggregation)) {
            return Resolution.errors(List.of(ScenarioContext.error("assertion_config_error",
                    assertionPath(name), "actualAggregation=sum when actualPaths is used", aggregation)));
        }
        JsonNode paths = assertion.get("actualPaths");
        if (paths == null || !paths.isArray() || paths.size() == 0) {
            return Resolution.errors(List.of(ScenarioContext.error("assertion_config_error",
                    assertionPath(name), "non-empty actualPaths array", typeName(paths))));
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode pathNode : paths) {
            if (pathNode == null || !pathNode.isTextual()) {
                return Resolution.errors(List.of(ScenarioContext.error("assertion_config_error",
                        assertionPath(name), "actualPaths entries must be strings", "null")));
            }
            Resolution<JsonNode> res;
            try {
                res = JsonPathLite.read(currentResponse, pathNode.asText());
            } catch (RuntimeException e) {
                return Resolution.errors(List.of(ScenarioContext.error("malformed_reference",
                        assertionPath(name), e.getMessage(), "null")));
            }
            if (res.hasErrors()) return res;
            BigDecimal val = decimal(res.value());
            if (val == null) {
                return Resolution.errors(List.of(ScenarioContext.error("assertion_type_mismatch",
                        assertionPath(name), "Numeric value required for actualPaths sum", pathNode.asText())));
            }
            sum = sum.add(val);
        }
        return Resolution.ok(new com.fasterxml.jackson.databind.node.DecimalNode(sum));
    }

    private static BigDecimal numericValue(String type, JsonNode node) {
        if ("integer".equals(type)) {
            BigInteger value = integer(node);
            return value == null ? null : new BigDecimal(value);
        }
        return decimal(node);
    }

    private static Map<String, Object> assertionResult(
            String name,
            String status,
            String operator,
            String type,
            String actualSource,
            String expectedSource,
            String actualPath,
            String expectedPath,
            String resolvedActual,
            String resolvedExpected,
            String failureKind,
            String message
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assertionName", name == null ? "" : name);
        out.put("status", status == null ? "" : status);
        out.put("passed", "PASS".equalsIgnoreCase(status));
        out.put("operator", operator == null ? "" : operator);
        out.put("type", type == null ? "" : type);
        out.put("actualSource", actualSource == null ? "" : actualSource);
        out.put("expectedSource", expectedSource == null ? "" : expectedSource);
        out.put("actualPath", actualPath == null ? "" : actualPath);
        out.put("expectedPath", expectedPath == null ? "" : expectedPath);
        out.put("resolvedActual", resolvedActual == null ? "" : resolvedActual);
        out.put("resolvedExpected", resolvedExpected == null ? "" : resolvedExpected);
        out.put("failureKind", failureKind == null ? "" : failureKind);
        out.put("message", message == null ? "" : message);
        return out;
    }

    private static Source source(JsonNode assertion, boolean actual) {
        String pathField = actual ? "actualPath" : "expectedPath";
        String valueField = actual ? "actual" : "expected";
        if (actual && assertion != null && assertion.has("actualPaths")) {
            return new Source("actualPaths", actualPathsExpression(assertion));
        }
        if (hasNonBlankText(assertion, pathField)) {
            return new Source(pathField, assertion.get(pathField).asText(""));
        }
        if (!actual && assertion != null && assertion.has("expectedValue")) {
            return new Source("expectedValue", "");
        }
        if (assertion != null && assertion.has(valueField)) {
            return new Source(valueField, "");
        }
        return new Source("", "");
    }

    private static String actualPathsExpression(JsonNode assertion) {
        JsonNode paths = assertion == null ? null : assertion.get("actualPaths");
        if (paths == null || !paths.isArray() || paths.size() == 0) {
            return "sum()";
        }
        List<String> values = new ArrayList<>();
        for (JsonNode path : paths) {
            values.add(path == null ? "null" : path.asText(""));
        }
        return "sum(" + String.join(", ", values) + ")";
    }

    private static String resolvedValue(String type, JsonNode node) {
        String normalizedType = type == null ? "string" : type.trim();
        if ("strictDecimal".equals(normalizedType)) {
            BigDecimal decimal = decimal(node);
            return decimal == null ? normalized(node) : decimal.toPlainString();
        }
        if ("integer".equals(normalizedType)) {
            BigInteger integer = integer(node);
            return integer == null ? normalized(node) : integer.toString();
        }
        if ("boolean".equals(normalizedType)) {
            Boolean bool = bool(node);
            return bool == null ? normalized(node) : bool.toString();
        }
        return normalized(node);
    }

    private static List<Map<String, Object>> wrapErrors(String name, List<Map<String, Object>> errors) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> error : errors) {
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("path", assertionPath(name));
            wrapped.put("kind", String.valueOf(error.getOrDefault("kind", "missing_reference")));
            wrapped.put("expected", String.valueOf(error.getOrDefault("expected", "")));
            wrapped.put("actual", String.valueOf(error.getOrDefault("actual", "")));
            wrapped.put("assertion", name);
            wrapped.put("type", "runtime");
            out.add(wrapped);
        }
        return out;
    }

    private static Map<String, Object> mismatch(String path, String kind, String expected, String actual, String assertion, String type) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("kind", kind);
        out.put("expected", expected == null ? "" : expected);
        out.put("actual", actual == null ? "" : actual);
        out.put("assertion", assertion == null ? "" : assertion);
        out.put("type", type == null ? "" : type);
        return out;
    }

    private static String assertionPath(String name) {
        return "assertions." + (name == null || name.isBlank() ? "unnamed" : name);
    }

    private static boolean hasNonBlankText(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) && node.get(field).isTextual() && !node.get(field).asText().isBlank();
    }

    private static String normalizeOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            return "equals";
        }
        String trimmed = operator.trim();
        switch (trimmed.toLowerCase(java.util.Locale.ROOT)) {
            case "equals":
                return "equals";
            case "notequals":
                return "notEquals";
            case "greaterthanorequal":
                return "greaterThanOrEqual";
            case "lessthanorequal":
                return "lessThanOrEqual";
            case "greaterthan":
                return "greaterThan";
            case "lessthan":
                return "lessThan";
            case "notnull":
                return "notNull";
            case "notempty":
                return "notEmpty";
            default:
                return trimmed;
        }
    }

    private static boolean isSupportedOperator(String operator) {
        switch (operator) {
            case "equals":
            case "notEquals":
            case "greaterThanOrEqual":
            case "lessThanOrEqual":
            case "greaterThan":
            case "lessThan":
            case "notNull":
            case "notEmpty":
                return true;
            default:
                return false;
        }
    }

    private static String supportedOperators() {
        return "equals, notEquals, greaterThanOrEqual, lessThanOrEqual, greaterThan, lessThan, notNull, notEmpty";
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || !node.hasNonNull(field)) {
            return fallback;
        }
        String value = node.get(field).asText("").trim();
        return value.isEmpty() ? fallback : value;
    }

    private static BigDecimal decimal(JsonNode node) {
        try {
            if (node == null || node.isNull()) {
                return null;
            }
            if (node.isNumber()) {
                return node.decimalValue();
            }
            if (node.isTextual()) {
                return new BigDecimal(node.asText().trim());
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BigInteger integer(JsonNode node) {
        try {
            if (node == null || node.isNull()) {
                return null;
            }
            if (node.isIntegralNumber()) {
                return node.bigIntegerValue();
            }
            if (node.isTextual()) {
                return new BigInteger(node.asText().trim());
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Boolean bool(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            String text = node.asText().trim();
            if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
                return Boolean.parseBoolean(text);
            }
        }
        return null;
    }

    private static String stringValue(JsonNode node) {
        // JSON null is intentionally serialised as "null" so QA can distinguish a successfully
        // resolved null value from a resolution failure (which emits "") or a missing field.
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

    private static String normalized(JsonNode node) {
        return stringValue(node);
    }

    private static String typeName(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        if (node.isObject()) return "object";
        if (node.isArray()) return "array";
        if (node.isTextual()) return "string";
        if (node.isNumber()) return "number";
        if (node.isBoolean()) return "boolean";
        return "json";
    }

    static final class Evaluation {
        private final List<Map<String, Object>> mismatches;
        private final List<Map<String, Object>> assertionResults;

        Evaluation(List<Map<String, Object>> mismatches, List<Map<String, Object>> assertionResults) {
            this.mismatches = mismatches == null ? List.of() : mismatches;
            this.assertionResults = assertionResults == null ? List.of() : assertionResults;
        }

        List<Map<String, Object>> mismatches() {
            return mismatches;
        }

        List<Map<String, Object>> assertionResults() {
            return assertionResults;
        }
    }

    private static final class ComparisonOutcome {
        final boolean passed;
        final Map<String, Object> mismatch;
        final String type;
        final String actualValue;
        final String expectedValue;
        final String failureKind;
        final String message;

        private ComparisonOutcome(boolean passed, Map<String, Object> mismatch, String type, String actualValue,
                                  String expectedValue, String failureKind, String message) {
            this.passed = passed;
            this.mismatch = mismatch;
            this.type = type == null ? "" : type;
            this.actualValue = actualValue == null ? "" : actualValue;
            this.expectedValue = expectedValue == null ? "" : expectedValue;
            this.failureKind = failureKind == null ? "" : failureKind;
            this.message = message == null ? "" : message;
        }

        static ComparisonOutcome pass(String type, String actualValue, String expectedValue) {
            return new ComparisonOutcome(true, null, type, actualValue, expectedValue, "", "Assertion passed");
        }

        static ComparisonOutcome fail(Map<String, Object> mismatch, String type, String actualValue,
                                      String expectedValue, String failureKind, String message) {
            return new ComparisonOutcome(false, mismatch, type, actualValue, expectedValue, failureKind, message);
        }
    }

    private static final class Source {
        final String field;
        final String path;

        Source(String field, String path) {
            this.field = field == null ? "" : field;
            this.path = path == null ? "" : path;
        }
    }
}
