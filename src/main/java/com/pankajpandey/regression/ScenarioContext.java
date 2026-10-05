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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ScenarioContext {
    private final Map<String, StepSnapshot> snapshots = new LinkedHashMap<>();

    boolean hasAlias(String alias) {
        return alias != null && snapshots.containsKey(alias.trim());
    }

    void store(String stepId, int stepNumber, int expectedCode, int actualCode, boolean passed, JsonNode response, JsonNode payload) {
        store(stepId, stepNumber, expectedCode, actualCode, passed, passed, response, payload, false);
    }

    void store(
            String stepId,
            int stepNumber,
            int expectedCode,
            int actualCode,
            boolean passed,
            boolean referenceable,
            JsonNode response,
            JsonNode payload
    ) {
        store(stepId, stepNumber, expectedCode, actualCode, passed, referenceable, response, payload, false);
    }

    void store(
            String stepId,
            int stepNumber,
            int expectedCode,
            int actualCode,
            boolean passed,
            boolean referenceable,
            JsonNode response,
            JsonNode payload,
            boolean dbSource
    ) {
        StepSnapshot snapshot = new StepSnapshot(stepId, stepNumber, expectedCode, actualCode, passed, referenceable, response, payload, dbSource);
        snapshots.put(stepAlias(stepNumber), snapshot);
        if (stepId != null && !stepId.isBlank()) {
            snapshots.put(stepId.trim(), snapshot);
        }
    }

    Resolution<JsonNode> resolveReference(String reference) {
        ParsedReference parsed;
        try {
            parsed = ParsedReference.parse(reference);
        } catch (IllegalArgumentException e) {
            return Resolution.errors(List.of(error("malformed_reference", reference, e.getMessage(), "null")));
        }
        StepSnapshot snapshot = snapshots.get(parsed.stepRef);
        if (snapshot == null) {
            return Resolution.errors(List.of(error("missing_reference", reference, "Missing step: " + parsed.stepRef, "null")));
        }
        if (snapshot.response() == null || snapshot.response().isNull()) {
            return Resolution.errors(List.of(error("dependency_failed", reference, "Source step has no response: " + parsed.stepRef, "null")));
        }
        if (!snapshot.referenceable()) {
            return Resolution.errors(List.of(error("dependency_failed", reference, "Source step is not referenceable: " + parsed.stepRef, Boolean.toString(snapshot.referenceable()))));
        }
        if (snapshot.dbSource() && isRootIndexPath(parsed.path) && hasAmbiguousRootArray(snapshot.response())) {
            return Resolution.errors(List.of(error(
                    "ambiguous_db_source", reference,
                    "DB-sourced array order is not guaranteed; use a filter instead of a root index: " + parsed.path,
                    "null"
            )));
        }
        Resolution<JsonNode> value = JsonPathLite.read(snapshot.response(), parsed.path);
        if (!value.hasErrors()) {
            return value;
        }
        if (!parsed.path.contains("[?")) {
            return Resolution.errors(List.of(error("missing_reference", reference, "Missing path: " + parsed.path, "null")));
        }
        List<Map<String, Object>> remapped = new ArrayList<>();
        for (Map<String, Object> pathError : value.errors()) {
            remapped.add(error(
                    String.valueOf(pathError.get("kind")),
                    reference,
                    String.valueOf(pathError.get("expected")),
                    String.valueOf(pathError.get("actual"))
            ));
        }
        return Resolution.errors(remapped);
    }

    private static boolean isRootIndexPath(String path) {
        return path != null && path.length() > 1 && path.charAt(0) == '[' && Character.isDigit(path.charAt(1));
    }

    private static boolean hasAmbiguousRootArray(JsonNode response) {
        return response.isArray() && response.size() > 1;
    }

    static String stepAlias(int stepNumber) {
        return "step" + stepNumber;
    }

    static boolean isReservedStepId(String stepId) {
        if (stepId == null || stepId.isBlank()) {
            return false;
        }
        String normalized = stepId.trim().toLowerCase(Locale.ROOT);
        return "current".equals(normalized)
                || "response".equals(normalized)
                || normalized.matches("step\\d+");
    }

    static Map<String, Object> error(String kind, String path, String expected, String actual) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path == null || path.isBlank() ? "$" : path);
        out.put("kind", kind == null ? "runtime_error" : kind);
        out.put("expected", expected == null ? "" : expected);
        out.put("actual", actual == null ? "" : actual);
        return out;
    }

    Map<String, StepSnapshot> snapshots() {
        return Collections.unmodifiableMap(snapshots);
    }

    private static final class ParsedReference {
        private final String stepRef;
        private final String path;

        private ParsedReference(String stepRef, String path) {
            this.stepRef = stepRef;
            this.path = path;
        }

        private static ParsedReference parse(String reference) {
            if (reference == null || reference.isBlank()) {
                throw new IllegalArgumentException("Reference is blank");
            }
            String trimmed = reference.trim();
            int responseIdx = trimmed.indexOf(".response");
            if (responseIdx <= 0) {
                throw new IllegalArgumentException("Reference must use <step>.response.<path>");
            }
            String stepRef = trimmed.substring(0, responseIdx).trim();
            String remainder = trimmed.substring(responseIdx + ".response".length());
            String path = "";
            if (remainder.startsWith(".")) {
                path = remainder.substring(1);
            } else if (remainder.startsWith("[")) {
                path = remainder;
            } else if (!remainder.isEmpty()) {
                throw new IllegalArgumentException("Malformed response path: " + remainder);
            }
            if ("current".equalsIgnoreCase(stepRef)) {
                throw new IllegalArgumentException("current shorthand is not supported in v1; use actualPath or expectedPath");
            }
            return new ParsedReference(stepRef, path);
        }
    }
}
