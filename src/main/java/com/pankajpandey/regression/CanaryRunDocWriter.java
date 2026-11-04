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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CanaryRunDocWriter {
    static final String FILE_NAME = "canary-run-doc.json";
    private static final Logger log = LoggerFactory.getLogger(CanaryRunDocWriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final int MAX_DIFF_ENTRIES = 200;

    private CanaryRunDocWriter() {}

    static Path write(Config cfg, List<ExecutionReport> reports, Path runDir, String runId) throws IOException {
        if (cfg == null) {
            log.warn("Skipping canary RunDoc because config is missing");
            return null;
        }
        if (!"execute".equalsIgnoreCase(cfg.mode) && !"render".equalsIgnoreCase(cfg.mode)) {
            log.debug("Skipping canary RunDoc for mode={}", cfg.mode);
            return null;
        }
        if (isBlank(runId)) {
            log.warn("Skipping canary RunDoc because runId/runFolder is missing");
            return null;
        }
        if (runDir == null) {
            log.warn("Skipping canary RunDoc because run directory is missing for runId={}", runId);
            return null;
        }
        if (!hasRequiredTargetMetadata(cfg)) {
            log.warn(
                    "Skipping canary RunDoc for runId={} because required target metadata is incomplete: repo={}, commitShaPresent={}",
                    runId,
                    blankToMarker(cfg.repo),
                    !isBlank(cfg.commitSha)
            );
            return null;
        }
        if (isBlank(cfg.previousGreenSha)) {
            log.warn(
                    "Writing canary RunDoc for runId={} without previous_green_sha; set CANARY_PREVIOUS_GREEN_SHA or pass --previousGreenSha to populate it",
                    runId
            );
        }

        List<ExecutionReport> safeReports = reports == null ? Collections.emptyList() : reports;
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> bodies = new LinkedHashMap<>();
        List<Map<String, Object>> failures = new ArrayList<>();

        for (ExecutionReport report : safeReports) {
            if (report == null) {
                continue;
            }
            for (Map<String, Object> failure : safeRows(report.failures)) {
                failures.add(toFailureRecord(report, failure, bodies));
            }
        }

        root.put("schemaVersion", "1.0");
        root.put("run", run(runId));
        root.put("targets", Collections.singletonList(target(cfg)));
        root.put("totals", totals(safeReports));
        root.put("ignore_set", new ArrayList<>(ignoreSet(safeReports)));
        root.put("infrastructure_failures", infrastructureFailures(safeReports));
        root.put("failures", failures);
        root.put("bodies", bodies);

        Files.createDirectories(runDir);
        Path output = runDir.resolve(FILE_NAME).toAbsolutePath().normalize();
        MAPPER.writeValue(output.toFile(), root);
        log.info("Canary RunDoc written runId={} failures={} output={}", runId, failures.size(), output);
        return output;
    }

    private static Map<String, Object> run(String runId) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("run_id", runId);
        return run;
    }

    private static Map<String, Object> target(Config cfg) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("repo", cfg.repo);
        target.put("commit_sha", cfg.commitSha);
        target.put("previous_green_sha", cfg.previousGreenSha);
        return target;
    }

    private static Map<String, Object> totals(List<ExecutionReport> reports) {
        Map<String, Object> totals = new LinkedHashMap<>();
        Map<String, Object> byType = new LinkedHashMap<>();
        byType.put("STATUS_CODE", 0);
        byType.put("PAYLOAD_DIFF", 0);
        byType.put("MIXED", 0);
        byType.put("INFRA", 0);

        int executed = 0;
        int passed = 0;
        int failed = 0;
        int infra = 0;
        for (ExecutionReport report : reports) {
            if (report == null) {
                continue;
            }
            executed += report.stepsExecuted;
            passed += report.stepsPassed;
            failed += report.stepsFailed;
            if (report.infrastructureFailure) {
                infra++;
            }
            for (Map<String, Object> failure : safeRows(report.failures)) {
                boolean codeFailed = codeFailed(failure);
                boolean payloadFailed = comparisonFailed(failure);
                if (codeFailed && payloadFailed) {
                    increment(byType, "MIXED");
                } else if (codeFailed) {
                    increment(byType, "STATUS_CODE");
                } else if (payloadFailed) {
                    increment(byType, "PAYLOAD_DIFF");
                }
            }
        }
        byType.put("INFRA", infra);
        totals.put("executed", executed);
        totals.put("passed", passed);
        totals.put("failed", failed);
        totals.put("by_type", byType);
        return totals;
    }

    private static List<Map<String, Object>> infrastructureFailures(List<ExecutionReport> reports) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExecutionReport report : reports) {
            if (report == null || !report.infrastructureFailure) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("manifest", str(report.manifest));
            row.put("error", str(report.infrastructureError));
            out.add(row);
        }
        return out;
    }

    private static Set<String> ignoreSet(List<ExecutionReport> reports) {
        Set<String> out = new LinkedHashSet<>();
        for (ExecutionReport report : reports) {
            if (report == null) {
                continue;
            }
            for (Map<String, Object> row : safeRows(report.passes)) {
                out.addAll(stringList(row.get("commonIgnore")));
            }
            for (Map<String, Object> row : safeRows(report.failures)) {
                out.addAll(stringList(row.get("commonIgnore")));
            }
        }
        return out;
    }

    private static Map<String, Object> toFailureRecord(
            ExecutionReport report,
            Map<String, Object> failure,
            Map<String, Object> bodies
    ) {
        String manifest = firstNonBlank(str(failure.get("manifest")), str(report.manifest));
        String api = str(failure.get("api"));
        int step = intValue(failure.get("step"), 0);
        String scenarioName = firstNonBlank(
                str(failure.get("scenarioName")),
                str(failure.get("stepApiName")),
                api + step
        );
        String failureId = failureId(manifest, api, scenarioName, step);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("failure_id", failureId);
        record.put("identity", identity(manifest, failure, api, scenarioName, step));
        record.put("request", request(failure));
        record.put("expected_code", intValue(failure.get("expectedCode"), -1));
        record.put("actual_code", intValue(failure.get("actualCode"), -1));
        record.put("diff", diff(failure));
        record.put("case_ignore", stringList(failure.get("caseIgnore")));

        Map<String, Object> body = body(failure);
        if (!body.isEmpty()) {
            bodies.put(failureId, body);
        }
        return record;
    }

    private static Map<String, Object> identity(
            String manifest,
            Map<String, Object> failure,
            String api,
            String scenarioName,
            int step
    ) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("api", api);
        identity.put("module", str(failure.get("module")));
        identity.put("scenario_name", scenarioName);
        identity.put("step", step);
        identity.put("owner", firstNonBlank(str(failure.get("owner")), "NA"));
        identity.put("manifest", manifest);
        return identity;
    }

    private static Map<String, Object> request(Map<String, Object> failure) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", safeMethod(str(failure.get("method"))));
        request.put("url", str(failure.get("url")));
        String endpointTemplate = str(failure.get("endpointTemplate"));
        if (!endpointTemplate.isBlank()) {
            request.put("endpoint_template", endpointTemplate);
        }
        return request;
    }

    private static Map<String, Object> diff(Map<String, Object> failure) {
        List<Map<String, Object>> comparison = comparisonRows(failure.get("comparison"));
        Map<String, Object> countsByKind = new LinkedHashMap<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> entry : comparison) {
            String kind = canaryKind(str(entry.get("kind")));
            increment(countsByKind, kind);
            if (index < MAX_DIFF_ENTRIES) {
                entries.add(diffEntry(entry, kind));
            }
            index++;
        }

        boolean comparisonFailed = comparisonFailed(failure);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("comparison_failed", comparisonFailed);
        diff.put("actually_effectively_empty", boolValue(failure.get("actualEffectivelyEmpty"), false));
        diff.put("truncated", comparison.size() > MAX_DIFF_ENTRIES);
        diff.put("counts_by_kind", countsByKind);
        diff.put("entries", entries);
        return diff;
    }

    private static Map<String, Object> diffEntry(Map<String, Object> entry, String kind) {
        String valueType = valueType(str(entry.get("valueType")), entry.get("expected"), entry.get("actual"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", firstNonBlank(str(entry.get("path")), "$"));
        String parentArrayPath = str(entry.get("parentArrayPath"));
        if (!parentArrayPath.isBlank()) {
            out.put("parent_array_path", parentArrayPath);
        }
        out.put("kind", kind);
        out.put("value_type", valueType);
        out.put("expected", diffValue(entry.get("expected"), valueType, kind, true));
        out.put("actual", diffValue(entry.get("actual"), valueType, kind, false));
        return out;
    }

    private static Map<String, Object> body(Map<String, Object> failure) {
        Map<String, Object> body = new LinkedHashMap<>();
        putParsedBody(body, "request_payload", failure.get("payload"));
        putParsedBody(body, "expected", failure.get("expected"));
        putParsedBody(body, "actual", failure.get("actual"));
        return body;
    }

    private static void putParsedBody(Map<String, Object> body, String key, Object value) {
        String raw = str(value);
        if (raw.isBlank()) {
            return;
        }
        body.put(key, parseBody(raw));
    }

    private static Object parseBody(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private static Object diffValue(Object value, String valueType, String kind, boolean expectedSide) {
        String raw = str(value);
        if (isMissingSide(raw, kind, expectedSide)) {
            return null;
        }
        if ("null".equals(valueType)) {
            return null;
        }
        if ("number".equals(valueType)) {
            try {
                return new BigDecimal(raw);
            } catch (Exception ignored) {
                return raw;
            }
        }
        if ("boolean".equals(valueType)) {
            if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
                return Boolean.valueOf(raw);
            }
            return raw;
        }
        if ("object".equals(valueType) || "array".equals(valueType)) {
            return parseBody(raw);
        }
        return raw;
    }

    private static boolean isMissingSide(String raw, String kind, boolean expectedSide) {
        if (!"null".equals(raw)) {
            return false;
        }
        return (expectedSide && ("unexpected_field".equals(kind) || "unexpected_item".equals(kind)))
                || (!expectedSide && ("missing_field".equals(kind) || "missing_item".equals(kind)));
    }

    private static String valueType(String explicit, Object expected, Object actual) {
        String normalized = explicit == null ? "" : explicit.trim();
        if (isAllowedValueType(normalized)) {
            return normalized;
        }
        return inferValueType(firstNonBlank(str(expected), str(actual)));
    }

    private static String inferValueType(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return "null";
        }
        if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
            return "boolean";
        }
        try {
            new BigDecimal(raw);
            return "number";
        } catch (Exception ignored) {
            // fall through
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            return "object";
        }
        if (trimmed.startsWith("[")) {
            return "array";
        }
        return "string";
    }

    private static boolean isAllowedValueType(String value) {
        return "number".equals(value)
                || "string".equals(value)
                || "boolean".equals(value)
                || "date".equals(value)
                || "object".equals(value)
                || "array".equals(value)
                || "null".equals(value);
    }

    private static String canaryKind(String kind) {
        if ("value_mismatch".equals(kind)
                || "type_mismatch".equals(kind)
                || "missing_field".equals(kind)
                || "missing_item".equals(kind)
                || "unexpected_item".equals(kind)) {
            return kind;
        }
        if ("unexpected_field".equals(kind) || "extra_field".equals(kind)) {
            return "unexpected_field";
        }
        return "value_mismatch";
    }

    private static boolean comparisonFailed(Map<String, Object> failure) {
        return boolValue(failure.get("comparisonFailed"), false) || !comparisonRows(failure.get("comparison")).isEmpty();
    }

    private static boolean codeFailed(Map<String, Object> failure) {
        int expectedCode = intValue(failure.get("expectedCode"), -1);
        int actualCode = intValue(failure.get("actualCode"), -1);
        // Negative means "no code": either a pre-request chaining failure that never issued a
        // request, or a missing/unparseable cell. Neither is a status-code failure.
        return expectedCode >= 0 && actualCode >= 0 && expectedCode != actualCode;
    }

    private static List<Map<String, Object>> comparisonRows(Object value) {
        if (!(value instanceof List<?> list)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> itemMap) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : itemMap.entrySet()) {
                    row.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                out.add(row);
            }
        }
        return out;
    }

    private static List<Map<String, Object>> safeRows(List<Map<String, Object>> rows) {
        return rows == null ? Collections.emptyList() : rows;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String text = str(item);
            if (!text.isBlank()) {
                out.add(text);
            }
        }
        return out;
    }

    private static String safeMethod(String method) {
        String trimmed = method == null ? "" : method.trim();
        if (trimmed.isBlank()) {
            return "GET";
        }
        String normalized = trimmed.toUpperCase();
        if ("GET".equals(normalized)
                || "POST".equals(normalized)
                || "PUT".equals(normalized)
                || "PATCH".equals(normalized)
                || "DELETE".equals(normalized)) {
            return normalized;
        }
        return trimmed;
    }

    private static void increment(Map<String, Object> counts, String key) {
        Object current = counts.get(key);
        counts.put(key, intValue(current, 0) + 1);
    }

    private static int intValue(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        String raw = str(value);
        if (raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static boolean boolValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean b) {
            return b;
        }
        String raw = str(value);
        if (raw.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw) || "y".equalsIgnoreCase(raw);
    }

    private static boolean hasRequiredTargetMetadata(Config cfg) {
        return !isBlank(cfg.repo) && !isBlank(cfg.commitSha);
    }

    private static String failureId(String manifest, String api, String scenarioName, int step) {
        String identity = str(manifest) + "|" + str(api) + "|" + str(scenarioName) + "|" + step;
        return sha256Hex(identity).substring(0, 16);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToMarker(String value) {
        return isBlank(value) ? "<missing>" : value;
    }
}
