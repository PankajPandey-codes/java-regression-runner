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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

public final class AnalysisReporter {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Pattern ARRAY_SELECTOR_PATTERN = Pattern.compile("\\[[^\\]]*\\]");

    private AnalysisReporter() {}

    public static AnalysisOutput write(Path projectRoot, String runFolder, List<ExecutionReport> reports) throws IOException {
        AnalysisData data = build(runFolder, reports);
        Path analysisDir = analysisDir(projectRoot, runFolder);
        Files.createDirectories(analysisDir);

        Path summaryJson = analysisDir.resolve("analysis-summary.json");
        Path htmlReport = analysisDir.resolve("analysis-report.html");

        MAPPER.writeValue(summaryJson.toFile(), data.toSummaryMap());
        AnalysisHtmlWriter.write(htmlReport, data);
        RegressionReportWriter.write(projectRoot, runFolder);
        return new AnalysisOutput(analysisDir, summaryJson, htmlReport);
    }

    public static Path analysisDir(Path projectRoot, String runFolder) {
        Path runnerRoot = PathResolver.runnerRoot(projectRoot);
        return runnerRoot.resolve("results")
                .resolve(runFolder)
                .resolve("analysis")
                .toAbsolutePath()
                .normalize();
    }

    private static AnalysisData build(String runFolder, List<ExecutionReport> reports) {
        AnalysisData data = new AnalysisData();
        data.generatedAt = Instant.now().toString();
        data.runFolder = runFolder;

        Map<String, Aggregate> byModule = new LinkedHashMap<>();
        Map<String, Aggregate> byOwner = new LinkedHashMap<>();
        Map<String, Aggregate> byApi = new LinkedHashMap<>();
        Map<String, Integer> diffPathCounts = new TreeMap<>();
        Map<String, Integer> causeCounts = new TreeMap<>();
        List<AnalyzedCase> comparedCases = new ArrayList<>();

        int totalExecuted = 0;
        int totalPassed = 0;
        int totalFailed = 0;
        int infraFailures = 0;
        int statusCodeMismatch = 0;
        int payloadDiffMismatch = 0;
        int mixedFailures = 0;

        for (ExecutionReport report : reports) {
            if (report == null) {
                continue;
            }
            totalExecuted += report.stepsExecuted;
            totalPassed += report.stepsPassed;
            totalFailed += report.stepsFailed;

            if (report.infrastructureFailure) {
                infraFailures++;
                data.infrastructureFailures.add(new InfraFailure(report.manifest, report.infrastructureError));
            }

            for (Map<String, Object> failure : report.failures) {
                AnalyzedCase analyzed = toAnalyzedCase(failure);
                boolean codeMismatch = hasCodeMismatch(analyzed);
                boolean payloadMismatch = analyzed.comparisonFailed || !analyzed.comparison.isEmpty();
                if (!analyzed.comparison.isEmpty()) {
                    comparedCases.add(analyzed);
                }

                analyzed.failureType = classify(codeMismatch, payloadMismatch);

                if (codeMismatch) {
                    statusCodeMismatch++;
                }
                if (payloadMismatch) {
                    payloadDiffMismatch++;
                }
                if (codeMismatch && payloadMismatch) {
                    mixedFailures++;
                    data.mixedFailures.add(analyzed);
                } else if (codeMismatch) {
                    data.statusCodeMismatches.add(analyzed);
                } else if (payloadMismatch) {
                    data.payloadDiffs.add(analyzed);
                } else {
                    data.otherFailures.add(analyzed);
                }

                increment(byModule, analyzed.module, codeMismatch, payloadMismatch);
                increment(byOwner, analyzed.owner, codeMismatch, payloadMismatch);
                increment(byApi, analyzed.api, codeMismatch, payloadMismatch);

                for (Map<String, Object> mismatch : analyzed.comparison) {
                    String path = summarizePath(asStr(mismatch.get("path")));
                    diffPathCounts.put(path, diffPathCounts.getOrDefault(path, 0) + 1);
                    String cause = normalizeCause(firstNonBlank(mismatch.get("cause"), mismatch.get("kind"), "Unknown"));
                    causeCounts.put(cause, causeCounts.getOrDefault(cause, 0) + 1);
                }
            }
        }

        data.totalExecuted = totalExecuted;
        data.totalPassed = totalPassed;
        data.totalFailed = totalFailed;
        data.infrastructureFailureCount = infraFailures;
        data.statusCodeMismatchCount = statusCodeMismatch;
        data.payloadDiffCount = payloadDiffMismatch;
        data.mixedFailureCount = mixedFailures;
        data.passRate = totalExecuted > 0 ? (totalPassed * 100.0 / totalExecuted) : 0.0;
        data.byModule = toBreakdownRows(byModule);
        data.byOwner = toBreakdownRows(byOwner);
        data.byApi = toBreakdownRows(byApi);
        data.causeBreakdown = toCauseCounts(causeCounts);
        data.topDiffPaths = toPathCounts(diffPathCounts);
        data.largestDiffCases = toCaseDigests(comparedCases);
        data.sortCases();
        return data;
    }

    private static void increment(Map<String, Aggregate> bucket, String key, boolean codeMismatch, boolean payloadMismatch) {
        String normalized = key == null || key.trim().isEmpty() ? "NA" : key.trim();
        Aggregate agg = bucket.get(normalized);
        if (agg == null) {
            agg = new Aggregate(normalized);
            bucket.put(normalized, agg);
        }
        agg.totalFailures++;
        if (codeMismatch) {
            agg.statusCodeMismatches++;
        }
        if (payloadMismatch) {
            agg.payloadDiffs++;
        }
        if (codeMismatch && payloadMismatch) {
            agg.mixedFailures++;
        }
    }

    private static List<BreakdownRow> toBreakdownRows(Map<String, Aggregate> bucket) {
        List<BreakdownRow> rows = new ArrayList<>();
        for (Aggregate agg : bucket.values()) {
            rows.add(new BreakdownRow(
                    agg.name,
                    agg.totalFailures,
                    agg.statusCodeMismatches,
                    agg.payloadDiffs,
                    agg.mixedFailures
            ));
        }
        rows.sort(Comparator
                .comparingInt(BreakdownRow::getTotalFailures).reversed()
                .thenComparing(BreakdownRow::getName));
        return rows;
    }

    private static List<PathCount> toPathCounts(Map<String, Integer> diffPathCounts) {
        List<PathCount> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : diffPathCounts.entrySet()) {
            if ("[]".equals(entry.getKey()) || "$".equals(entry.getKey())) {
                continue;
            }
            rows.add(new PathCount(entry.getKey(), entry.getValue()));
        }
        if (rows.isEmpty() && diffPathCounts.containsKey("[]")) {
            rows.add(new PathCount("[]", diffPathCounts.get("[]")));
        }
        rows.sort(Comparator
                .comparingInt(PathCount::getCount).reversed()
                .thenComparing(PathCount::getPath));
        return rows;
    }

    private static List<CauseCount> toCauseCounts(Map<String, Integer> causeCounts) {
        List<CauseCount> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : causeCounts.entrySet()) {
            rows.add(new CauseCount(entry.getKey(), entry.getValue()));
        }
        rows.sort(Comparator
                .comparingInt(CauseCount::getCount).reversed()
                .thenComparing(CauseCount::getCause));
        return rows;
    }

    private static List<CaseDigest> toCaseDigests(List<AnalyzedCase> cases) {
        List<CaseDigest> rows = new ArrayList<>();
        for (AnalyzedCase row : cases) {
            rows.add(new CaseDigest(
                    row.manifest,
                    row.api,
                    row.module,
                    row.owner,
                    row.step,
                    row.method,
                    row.expectedCode,
                    row.actualCode,
                    row.failureType,
                    row.comparisonCount(),
                    topMismatchPaths(row.comparison, 5)
            ));
        }
        rows.sort(Comparator
                .comparingInt(CaseDigest::getComparisonCount).reversed()
                .thenComparing(CaseDigest::getApi)
                .thenComparingInt(CaseDigest::getStep));
        return rows;
    }

    private static String classify(boolean codeMismatch, boolean payloadMismatch) {
        if (codeMismatch && payloadMismatch) {
            return "STATUS_AND_PAYLOAD";
        }
        if (codeMismatch) {
            return "STATUS_CODE";
        }
        if (payloadMismatch) {
            return "PAYLOAD_DIFF";
        }
        return "OTHER";
    }

    private static String summarizePath(String rawPath) {
        String path = asStr(rawPath).trim();
        if (path.isEmpty()) {
            return "$";
        }
        String normalized = ARRAY_SELECTOR_PATTERN.matcher(path).replaceAll("[]");
        return normalized.isEmpty() ? "$" : normalized;
    }

    private static String normalizeCause(Object rawCause) {
        String cause = asStr(rawCause).trim();
        if (cause.isEmpty()) {
            return "Unknown";
        }
        String compact = canonicalCauseKey(cause);
        switch (compact) {
            case "value_mismatch":
                return "ValueMismatch";
            case "missing_field":
                return "MissingField";
            case "extra_field":
                return "ExtraField";
            case "type_mismatch":
                return "TypeMismatch";
            case "length_mismatch":
                return "LengthMismatch";
            case "string_format":
                return "StringFormat";
            case "numeric_tolerance":
                return "NumericTolerance";
            default:
                String[] parts = compact.split("_+");
                StringBuilder sb = new StringBuilder();
                for (String part : parts) {
                    if (part == null || part.isBlank()) {
                        continue;
                    }
                    sb.append(Character.toUpperCase(part.charAt(0)));
                    if (part.length() > 1) {
                        sb.append(part.substring(1));
                    }
                }
                return sb.length() == 0 ? "Unknown" : sb.toString();
        }
    }

    private static String canonicalCauseKey(String raw) {
        String withWordBreaks = raw.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        return withWordBreaks.replace('-', '_').replace(' ', '_').toLowerCase(Locale.ROOT);
    }

    private static Object firstNonBlank(Object a, Object b, Object fallback) {
        String sa = asStr(a).trim();
        if (!sa.isEmpty()) {
            return sa;
        }
        String sb = asStr(b).trim();
        if (!sb.isEmpty()) {
            return sb;
        }
        return fallback;
    }

    private static boolean hasCodeMismatch(AnalyzedCase analyzed) {
        return analyzed.expectedCode >= 0 && analyzed.actualCode >= 0 && analyzed.expectedCode != analyzed.actualCode;
    }

    private static Map<String, Object> rebaselineMetadata(AnalyzedCase row) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean statusMismatch = hasCodeMismatch(row);
        boolean payloadMismatch = row.comparisonFailed || row.comparisonCount() > 0;
        boolean actualJsonAvailable = looksLikeJson(row.actual);
        boolean canUpdateExpectedBody = payloadMismatch
                && actualJsonAvailable
                && !isBlank(row.expectedJsonPath)
                && !isBlank(row.jsonOutputArtifact)
                && row.step > 0;
        boolean canUpdateExpectedStatusCode = statusMismatch
                && row.actualCode >= 0
                && !isBlank(row.apiInputsPath)
                && row.step > 0;

        out.put("apiInputsFile", safe(row.apiInputsFile));
        out.put("apiInputsPath", safe(row.apiInputsPath));
        out.put("payloadJsonFile", safe(row.payloadJsonFile));
        out.put("payloadJsonPath", safe(row.payloadJsonPath));
        out.put("expectedJsonFile", safe(row.expectedJsonFile));
        out.put("expectedJsonPath", safe(row.expectedJsonPath));
        out.put("expectedValueField", safe(row.expectedValueField));
        out.put("scenarioKey", safe(row.scenarioKey));
        out.put("scenarioName", safe(row.scenarioName));
        out.put("resultApiInputsPath", safe(row.resultApiInputsPath));
        out.put("jsonPayloadArtifact", safe(row.jsonPayloadArtifact));
        out.put("jsonOutputArtifact", safe(row.jsonOutputArtifact));
        out.put("comparisonArtifact", portableWorkspaceArtifact(row.comparisonArtifact));
        out.put("canUpdateExpectedBody", canUpdateExpectedBody);
        out.put("canUpdateExpectedStatusCode", canUpdateExpectedStatusCode);
        out.put("defaultAction", defaultRebaselineAction(canUpdateExpectedBody, canUpdateExpectedStatusCode));
        out.put("allowedActions", allowedRebaselineActions(canUpdateExpectedBody, canUpdateExpectedStatusCode));
        out.put("recommendation", rebaselineRecommendation(canUpdateExpectedBody, canUpdateExpectedStatusCode, actualJsonAvailable));
        return out;
    }

    private static String defaultRebaselineAction(boolean canUpdateExpectedBody, boolean canUpdateExpectedStatusCode) {
        if (canUpdateExpectedBody && !canUpdateExpectedStatusCode) {
            return "UPDATE_EXPECTED_BODY";
        }
        return "SKIP";
    }

    private static List<String> allowedRebaselineActions(boolean canUpdateExpectedBody, boolean canUpdateExpectedStatusCode) {
        List<String> actions = new ArrayList<>();
        actions.add("SKIP");
        if (canUpdateExpectedBody) {
            actions.add("UPDATE_EXPECTED_BODY");
        }
        if (canUpdateExpectedStatusCode) {
            actions.add("UPDATE_EXPECTED_STATUS_CODE");
        }
        if (canUpdateExpectedBody && canUpdateExpectedStatusCode) {
            actions.add("UPDATE_BODY_AND_STATUS_CODE");
        }
        return actions;
    }

    private static String rebaselineRecommendation(
            boolean canUpdateExpectedBody,
            boolean canUpdateExpectedStatusCode,
            boolean actualJsonAvailable
    ) {
        if (canUpdateExpectedBody && canUpdateExpectedStatusCode) {
            return "Review both expected body and expected status code before applying.";
        }
        if (canUpdateExpectedBody) {
            return "Safe body-only candidate; actual response JSON can replace the expected body.";
        }
        if (canUpdateExpectedStatusCode) {
            return "Status-code-only candidate; explicitly opt in if the expected status code is wrong.";
        }
        if (!actualJsonAvailable) {
            return "No valid JSON response artifact is available for body rebaseline.";
        }
        return "No automated rebaseline action is available.";
    }

    private static boolean looksLikeJson(String raw) {
        if (raw == null) {
            return false;
        }
        String trimmed = raw.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String portableWorkspaceArtifact(String value) {
        String path = safe(value).trim().replace('\\', '/');
        if (path.startsWith("/workspace/")) {
            return path.substring("/workspace/".length());
        }
        if (path.startsWith("workspace/")) {
            return path.substring("workspace/".length());
        }
        return path;
    }

    @SuppressWarnings("unchecked")
    private static AnalyzedCase toAnalyzedCase(Map<String, Object> failure) {
        AnalyzedCase analyzed = new AnalyzedCase();
        analyzed.manifest = asStr(failure.get("manifest"));
        analyzed.api = asStr(failure.get("api"));
        analyzed.module = asStr(failure.get("module"));
        analyzed.owner = asStr(failure.get("owner"));
        analyzed.stepApiName = asStr(failure.get("stepApiName"));
        analyzed.step = asInt(failure.get("step"));
        analyzed.method = asStr(failure.get("method"));
        analyzed.url = asStr(failure.get("url"));
        analyzed.expectedCode = asInt(failure.get("expectedCode"));
        analyzed.actualCode = asInt(failure.get("actualCode"));
        analyzed.payload = asStr(failure.get("payload"));
        analyzed.expected = asStr(failure.get("expected"));
        analyzed.actual = asStr(failure.get("actual"));
        analyzed.apiInputsFile = asStr(failure.get("apiInputsFile"));
        analyzed.apiInputsPath = asStr(failure.get("apiInputsPath"));
        analyzed.payloadJsonFile = asStr(failure.get("payloadJsonFile"));
        analyzed.payloadJsonPath = asStr(failure.get("payloadJsonPath"));
        analyzed.expectedJsonFile = asStr(failure.get("expectedJsonFile"));
        analyzed.expectedJsonPath = asStr(failure.get("expectedJsonPath"));
        analyzed.expectedValueField = asStr(failure.get("expectedValueField"));
        analyzed.scenarioKey = asStr(failure.get("scenarioKey"));
        analyzed.scenarioName = asStr(failure.get("scenarioName"));
        analyzed.resultApiInputsPath = asStr(failure.get("resultApiInputsPath"));
        analyzed.jsonPayloadArtifact = asStr(failure.get("jsonPayloadArtifact"));
        analyzed.jsonOutputArtifact = asStr(failure.get("jsonOutputArtifact"));
        analyzed.comparisonArtifact = asStr(failure.get("comparisonArtifact"));
        Object comparison = failure.get("comparison");
        if (comparison instanceof List<?> comparisonList) {
            analyzed.comparison = (List<Map<String, Object>>) comparisonList;
        } else {
            analyzed.comparison = Collections.emptyList();
        }
        Object comparisonFailed = failure.get("comparisonFailed");
        analyzed.comparisonFailed = comparisonFailed instanceof Boolean b && b;
        Object assertionResults = failure.get("assertionResults");
        if (assertionResults instanceof List<?> assertionList) {
            analyzed.assertionResults = (List<Map<String, Object>>) assertionList;
        } else {
            analyzed.assertionResults = Collections.emptyList();
        }
        return analyzed;
    }

    private static String asStr(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int asInt(Object value) {
        if (value == null) {
            return -1;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    public static final class AnalysisOutput {
        public final Path analysisDir;
        public final Path summaryJson;
        public final Path htmlReport;

        AnalysisOutput(Path analysisDir, Path summaryJson, Path htmlReport) {
            this.analysisDir = analysisDir;
            this.summaryJson = summaryJson;
            this.htmlReport = htmlReport;
        }
    }

    static final class AnalysisData {
        String generatedAt;
        String runFolder;
        int totalExecuted;
        int totalPassed;
        int totalFailed;
        int infrastructureFailureCount;
        int statusCodeMismatchCount;
        int payloadDiffCount;
        int mixedFailureCount;
        double passRate;
        List<InfraFailure> infrastructureFailures = new ArrayList<>();
        List<AnalyzedCase> statusCodeMismatches = new ArrayList<>();
        List<AnalyzedCase> payloadDiffs = new ArrayList<>();
        List<AnalyzedCase> mixedFailures = new ArrayList<>();
        List<AnalyzedCase> otherFailures = new ArrayList<>();
        List<BreakdownRow> byModule = new ArrayList<>();
        List<BreakdownRow> byOwner = new ArrayList<>();
        List<BreakdownRow> byApi = new ArrayList<>();
        List<CauseCount> causeBreakdown = new ArrayList<>();
        List<PathCount> topDiffPaths = new ArrayList<>();
        List<CaseDigest> largestDiffCases = new ArrayList<>();

        void sortCases() {
            Comparator<AnalyzedCase> cmp = Comparator
                    .comparing(AnalyzedCase::getManifest)
                    .thenComparing(AnalyzedCase::getApi)
                    .thenComparingInt(AnalyzedCase::getStep);
            statusCodeMismatches.sort(cmp);
            payloadDiffs.sort(cmp);
            mixedFailures.sort(cmp);
            otherFailures.sort(cmp);
        }

        Map<String, Object> toSummaryMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("generatedAt", generatedAt);
            out.put("runFolder", runFolder);

            Map<String, Object> totals = new LinkedHashMap<>();
            totals.put("executed", totalExecuted);
            totals.put("passed", totalPassed);
            totals.put("failed", totalFailed);
            totals.put("passRate", Double.valueOf(String.format(Locale.ROOT, "%.2f", passRate)));
            out.put("totals", totals);

            Map<String, Object> failureBreakdown = new LinkedHashMap<>();
            failureBreakdown.put("infrastructure", infrastructureFailureCount);
            failureBreakdown.put("statusCodeMismatch", statusCodeMismatchCount);
            failureBreakdown.put("payloadDiff", payloadDiffCount);
            failureBreakdown.put("mixedFailure", mixedFailureCount);
            failureBreakdown.put("otherFailure", otherFailures.size());
            out.put("failureBreakdown", failureBreakdown);

            out.put("topDiffPaths", topDiffPaths);
            out.put("causeBreakdown", causeBreakdown);
            out.put("largestDiffCases", largestDiffCases);
            out.put("byModule", byModule);
            out.put("byOwner", byOwner);
            out.put("byApi", byApi);
            out.put("infrastructureFailures", infrastructureFailures);
            out.put("statusCodeMismatches", condense(statusCodeMismatches));
            out.put("payloadDiffs", condense(payloadDiffs));
            out.put("mixedFailures", condense(mixedFailures));
            out.put("otherFailures", condense(otherFailures));
            return out;
        }

        private List<Map<String, Object>> condense(List<AnalyzedCase> rows) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (AnalyzedCase row : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("manifest", row.manifest);
                item.put("api", row.api);
                item.put("module", row.module);
                item.put("owner", row.owner);
                item.put("step", row.step);
                item.put("method", row.method);
                item.put("url", row.url);
                item.put("expectedCode", row.expectedCode);
                item.put("actualCode", row.actualCode);
                item.put("failureType", row.failureType);
                item.put("comparisonFailed", row.comparisonFailed);
                item.put("comparisonCount", row.comparison.size());
                item.put("topMismatchPaths", topMismatchPaths(row.comparison, 5));
                item.put("assertionResults", row.assertionResults);
                item.put("rebaseline", rebaselineMetadata(row));
                out.add(item);
            }
            return out;
        }

    }

    private static List<String> topMismatchPaths(List<Map<String, Object>> comparison, int limit) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (Map<String, Object> mismatch : comparison) {
            String path = summarizePath(mismatch == null ? "" : asStr(mismatch.get("path")));
            if (!"[]".equals(path) && !"$".equals(path)) {
                unique.add(path);
            }
            if (unique.size() >= limit) {
                return new ArrayList<>(unique);
            }
        }
        if (unique.isEmpty()) {
            for (Map<String, Object> mismatch : comparison) {
                unique.add(summarizePath(mismatch == null ? "" : asStr(mismatch.get("path"))));
                if (unique.size() >= limit) {
                    break;
                }
            }
        }
        return new ArrayList<>(unique);
    }

    static final class Aggregate {
        final String name;
        int totalFailures;
        int statusCodeMismatches;
        int payloadDiffs;
        int mixedFailures;

        Aggregate(String name) {
            this.name = name;
        }
    }

    static final class BreakdownRow {
        public final String name;
        public final int totalFailures;
        public final int statusCodeMismatches;
        public final int payloadDiffs;
        public final int mixedFailures;

        BreakdownRow(String name, int totalFailures, int statusCodeMismatches, int payloadDiffs, int mixedFailures) {
            this.name = name;
            this.totalFailures = totalFailures;
            this.statusCodeMismatches = statusCodeMismatches;
            this.payloadDiffs = payloadDiffs;
            this.mixedFailures = mixedFailures;
        }

        public String getName() {
            return name;
        }

        public int getTotalFailures() {
            return totalFailures;
        }
    }

    static final class PathCount {
        public final String path;
        public final int count;

        PathCount(String path, int count) {
            this.path = path;
            this.count = count;
        }

        public String getPath() {
            return path;
        }

        public int getCount() {
            return count;
        }
    }

    static final class CauseCount {
        public final String cause;
        public final int count;

        CauseCount(String cause, int count) {
            this.cause = cause;
            this.count = count;
        }

        public String getCause() {
            return cause;
        }

        public int getCount() {
            return count;
        }
    }

    static final class CaseDigest {
        public final String manifest;
        public final String api;
        public final String module;
        public final String owner;
        public final int step;
        public final String method;
        public final int expectedCode;
        public final int actualCode;
        public final String failureType;
        public final int comparisonCount;
        public final List<String> topMismatchPaths;

        CaseDigest(String manifest, String api, String module, String owner, int step, String method,
                   int expectedCode, int actualCode, String failureType, int comparisonCount, List<String> topMismatchPaths) {
            this.manifest = manifest;
            this.api = api;
            this.module = module;
            this.owner = owner;
            this.step = step;
            this.method = method;
            this.expectedCode = expectedCode;
            this.actualCode = actualCode;
            this.failureType = failureType;
            this.comparisonCount = comparisonCount;
            this.topMismatchPaths = topMismatchPaths;
        }

        public String getApi() {
            return api == null ? "" : api;
        }

        public int getStep() {
            return step;
        }

        public int getComparisonCount() {
            return comparisonCount;
        }
    }

    static final class InfraFailure {
        public final String manifest;
        public final String error;

        InfraFailure(String manifest, String error) {
            this.manifest = manifest;
            this.error = error == null ? "" : error;
        }
    }

    static final class AnalyzedCase {
        String manifest;
        String api;
        String module;
        String owner;
        String stepApiName;
        int step;
        String method;
        String url;
        int expectedCode;
        int actualCode;
        String payload;
        String expected;
        String actual;
        String apiInputsFile;
        String apiInputsPath;
        String payloadJsonFile;
        String payloadJsonPath;
        String expectedJsonFile;
        String expectedJsonPath;
        String expectedValueField;
        String scenarioKey;
        String scenarioName;
        String resultApiInputsPath;
        String jsonPayloadArtifact;
        String jsonOutputArtifact;
        String comparisonArtifact;
        boolean comparisonFailed;
        String failureType;
        List<Map<String, Object>> comparison = Collections.emptyList();
        List<Map<String, Object>> assertionResults = Collections.emptyList();

        String getManifest() {
            return manifest == null ? "" : manifest;
        }

        String getApi() {
            return api == null ? "" : api;
        }

        int getStep() {
            return step;
        }

        int comparisonCount() {
            return comparison == null ? 0 : comparison.size();
        }
    }
}
