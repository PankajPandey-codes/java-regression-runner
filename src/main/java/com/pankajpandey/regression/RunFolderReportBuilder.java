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
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.DirectoryStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RunFolderReportBuilder {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RunFolderReportBuilder() {}

    public static List<ExecutionReport> build(Path projectRoot, String runFolder, Config cfg) throws Exception {
        return build(projectRoot, runFolder, cfg.protocol, cfg.server, cfg.serviceBaseUrls);
    }

    public static List<ExecutionReport> build(Path projectRoot, String runFolder, String protocol, String server) throws Exception {
        return build(projectRoot, runFolder, protocol, server, Collections.emptyMap());
    }

    public static List<ExecutionReport> build(
            Path projectRoot,
            String runFolder,
            String protocol,
            String server,
            Map<String, String> serviceBaseUrls
    ) throws Exception {
        List<ExecutionReport> reports = new ArrayList<>();
        Path runnerRoot = PathResolver.runnerRoot(projectRoot);
        Path contentRoot = contentRoot(projectRoot);
        Map<String, Map<String, ManifestRow>> manifestIndex = loadManifestIndex(contentRoot);
        Map<Path, JsonNode> expectedCache = new HashMap<>();

        for (String manifestName : manifestNamesToBuild(projectRoot, runFolder, runnerRoot)) {
            String tag = manifestTag(manifestName);
            ExecutionReport report = new ExecutionReport();
            report.manifest = manifestName;

            Path manifestResultsDir = runnerRoot.resolve("results").resolve(runFolder).resolve(tag);
            if (!Files.exists(manifestResultsDir)) {
                // Backward compatibility for legacy runs that wrote to repo root.
                manifestResultsDir = projectRoot.resolve("results").resolve(runFolder).resolve(tag);
                if (!Files.exists(manifestResultsDir)) {
                    reports.add(report);
                    continue;
                }
            }

            Set<String> apisSeen = new HashSet<>();
            List<Path> files = Files.list(manifestResultsDir)
                    .filter(p -> p.getFileName().toString().endsWith(".csv"))
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());

            for (Path csvFile : files) {
                try (Reader reader = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8);
                     CSVParser parser = CSVFormat.DEFAULT.builder()
                             .setHeader()
                             .setSkipHeaderRecord(true)
                             .setTrim(true)
                             .setIgnoreEmptyLines(true)
                             .build()
                             .parse(reader)) {
                    for (CSVRecord rec : parser) {
                        String api = get(rec, "API Name");
                        if (!api.isBlank()) apisSeen.add(api);

                        report.stepsExecuted++;
                        String status = get(rec, "Test Result").toUpperCase();

                        int stepNo = parseIntOrDefault(get(rec, "Test Scenario Number"), 0);
                        int expectedCode = parseIntOrDefault(get(rec, "Expected Response Code"), -1);
                        int actualCode = parseIntOrDefault(get(rec, "Actual Response Code"), -1);
                        String apiPath = get(rec, "API Path & Value");
                        String method = get(rec, "API Method");
                        String url = buildUrl(protocol, server, serviceBaseUrls, apiPath);
                        String module = get(rec, "Module Name");
                        String owner = get(rec, "Owner");
                        String recordedPayloadArtifact = get(rec, "JSON Payload");
                        String recordedOutputArtifact = get(rec, "Results JSON File Name");
                        String recordedApiInputsPath = get(rec, "API Inputs File");
                        String comparisonArtifact = extractComparisonArtifact(get(rec, "Reason For Failure"));
                        String recordedAssertionResultsArtifact = get(rec, "Assertion Results Artifact");

                        Path artifactRoot = artifactRoot(manifestResultsDir);
                        Path comparisonArtifactPath = resolveRecordedPath(projectRoot, runnerRoot, artifactRoot, comparisonArtifact);
                        Path assertionResultsArtifactPath = recordedAssertionResultsArtifact.isBlank()
                                ? resolveAssertionResultsArtifactPath(runnerRoot, artifactRoot, runFolder, tag, api, stepNo)
                                : resolveRecordedPath(projectRoot, runnerRoot, artifactRoot, recordedAssertionResultsArtifact);
                        List<Map<String, Object>> assertionResults = readAssertionResultsArtifact(assertionResultsArtifactPath);
                        String assertionResultsArtifact = firstNonBlank(recordedAssertionResultsArtifact, portablePath(runnerRoot, assertionResultsArtifactPath));
                        String payload = readRecordedText(projectRoot, runnerRoot, artifactRoot, recordedPayloadArtifact);
                        String actual = readRecordedText(projectRoot, runnerRoot, artifactRoot, recordedOutputArtifact);
                        JsonNode actualNode = safeParseJson(actual);

                        ExpectedStepData expectedData = resolveExpectedStepData(contentRoot, manifestIndex.get(manifestName), expectedCache, api, stepNo);
                        JsonNode expectedNode = expectedData.expectedNode;
                        String expected = expectedNode == null ? "" : expectedNode.toString();
                        boolean effectiveFailed;
                        List<Map<String, Object>> comparison;
                        boolean comparisonFailed;
                        if ("PASS".equals(status)) {
                            effectiveFailed = false;
                            comparison = Collections.emptyList();
                            comparisonFailed = false;
                        } else if ("FAIL".equals(status)) {
                            comparison = compareExpected(expectedNode, actualNode, expectedData.comparisonConfig);
                            if (comparison.isEmpty()) {
                                comparison = readComparisonArtifactMismatches(comparisonArtifactPath);
                            }
                            comparisonFailed = !comparison.isEmpty();
                            boolean codeFailed = expectedCode >= 0 && actualCode >= 0 && expectedCode != actualCode;
                            if (!comparisonFailed && !codeFailed) {
                                comparison = recordedFailure(get(rec, "Reason For Failure"));
                                comparisonFailed = !comparison.isEmpty();
                            }
                            comparison = mergeAssertionFailures(comparison, assertionResults);
                            comparisonFailed = !comparison.isEmpty();
                            effectiveFailed = true;
                        } else {
                            comparison = compareExpected(expectedNode, actualNode, expectedData.comparisonConfig);
                            comparison = mergeAssertionFailures(comparison, assertionResults);
                            comparisonFailed = !comparison.isEmpty();
                            boolean codeFailed = expectedCode >= 0 && actualCode >= 0 && expectedCode != actualCode;
                            effectiveFailed = codeFailed || comparisonFailed;
                        }

                        if (effectiveFailed) report.stepsFailed++;
                        else report.stepsPassed++;
                        if (!effectiveFailed) {
                            Map<String, Object> p = new LinkedHashMap<>();
                            p.put("manifest", report.manifest);
                            p.put("api", api);
                            p.put("module", module);
                            p.put("stepApiName", api + stepNo);
                            p.put("owner", owner);
                            p.put("step", stepNo);
                            p.put("method", method);
                            p.put("url", url);
                            p.put("expectedCode", expectedCode);
                            p.put("actualCode", actualCode);
                            p.put("payload", payload);
                            p.put("expected", expected);
                            p.put("actual", actual);
                            p.put("comparison", Collections.emptyList());
                            p.put("comparisonFailed", false);
                            putAssertionMetadata(p, assertionResults, assertionResultsArtifact);
                            putCanaryMetadata(p, expectedData, apiPath, actualNode, actual);
                            putRebaselineMetadata(p, expectedData, recordedApiInputsPath, recordedPayloadArtifact, recordedOutputArtifact, comparisonArtifact);
                            report.passes.add(p);
                            continue;
                        }

                        Map<String, Object> f = new LinkedHashMap<>();
                        f.put("manifest", report.manifest);
                        f.put("api", api);
                        f.put("module", module);
                        f.put("stepApiName", api + stepNo);
                        f.put("owner", owner);
                        f.put("step", stepNo);
                        f.put("method", method);
                        f.put("url", url);
                        f.put("expectedCode", expectedCode);
                        f.put("actualCode", actualCode);
                        f.put("payload", payload);
                        f.put("expected", expected);
                        f.put("actual", actual);
                        f.put("comparison", comparison);
                        f.put("comparisonFailed", comparisonFailed);
                        putAssertionMetadata(f, assertionResults, assertionResultsArtifact);
                        putCanaryMetadata(f, expectedData, apiPath, actualNode, actual);
                        putRebaselineMetadata(f, expectedData, recordedApiInputsPath, recordedPayloadArtifact, recordedOutputArtifact, comparisonArtifact);
                        report.failures.add(f);
                    }
                }
            }

            report.apiScenariosExecuted = apisSeen.size();
            reports.add(report);
        }

        return reports;
    }

    private static Map<String, Map<String, ManifestRow>> loadManifestIndex(Path contentRoot) throws Exception {
        Map<String, Map<String, ManifestRow>> out = new HashMap<>();
        Path manifestsDir = contentRoot.resolve("apisToBeValidated");
        if (!Files.isDirectory(manifestsDir)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(manifestsDir, "*.csv")) {
            for (Path manifestPath : stream) {
                String manifestName = manifestPath.getFileName().toString();
                List<ManifestRow> rows = ManifestLoader.load(manifestPath);
                Map<String, ManifestRow> m = new HashMap<>();
                for (ManifestRow r : rows) m.putIfAbsent(r.apiName(), r);
                out.put(manifestName, m);
            }
        }
        return out;
    }

    private static List<String> manifestNamesToBuild(Path projectRoot, String runFolder, Path runnerRoot) {
        List<String> manifests = readManifestNamesFromRunStatus(projectRoot, runFolder);
        if (!manifests.isEmpty()) return manifests;

        List<String> discovered = new ArrayList<>();
        Path runRoot = runnerRoot.resolve("results").resolve(runFolder);
        if (!Files.isDirectory(runRoot)) {
            runRoot = projectRoot.resolve("results").resolve(runFolder);
        }
        if (!Files.isDirectory(runRoot)) {
            return Arrays.asList("APIsToBeValidated_A.csv", "APIsToBeValidated_B.csv", "APIsToBeValidated_C.csv");
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(runRoot)) {
            for (Path manifestResultsDir : stream) {
                if (Files.isDirectory(manifestResultsDir)) {
                    discovered.add(manifestNameFromTag(manifestResultsDir.getFileName().toString()));
                }
            }
        } catch (Exception ignored) {
        }
        return discovered.isEmpty() ? Arrays.asList("APIsToBeValidated_A.csv", "APIsToBeValidated_B.csv", "APIsToBeValidated_C.csv") : discovered;
    }

    private static List<String> readManifestNamesFromRunStatus(Path projectRoot, String runFolder) {
        Path statusPath = RunStatusWriter.statusPath(projectRoot, runFolder);
        if (!Files.exists(statusPath)) return Collections.emptyList();
        try {
            JsonNode root = MAPPER.readTree(readString(statusPath));
            JsonNode manifests = root.get("manifests");
            if (manifests == null || !manifests.isArray()) return Collections.emptyList();
            List<String> names = new ArrayList<>();
            for (JsonNode manifest : manifests) {
                String name = manifest == null ? "" : manifest.asText("");
                if (name != null) {
                    name = name.trim();
                }
                if (name != null && !name.isEmpty()) {
                    names.add(name.endsWith(".csv") ? name : name + ".csv");
                }
            }
            return names.stream().distinct().collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static String manifestNameFromTag(String tag) {
        if ("A".equals(tag) || "B".equals(tag) || "C".equals(tag)) {
            return "APIsToBeValidated_" + tag + ".csv";
        }
        return tag.endsWith(".csv") ? tag : tag + ".csv";
    }

    private static String manifestTag(String manifestName) {
        if (manifestName == null || manifestName.isBlank()) return "UNKNOWN";
        if (manifestName.contains("_A")) return "A";
        if (manifestName.contains("_B")) return "B";
        if (manifestName.contains("_C")) return "C";
        return sanitizeManifestTag(manifestName.replace(".csv", ""));
    }

    private static String sanitizeManifestTag(String value) {
        if (value == null || value.isBlank()) return "NA";
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static ExpectedStepData resolveExpectedStepData(
            Path contentRoot,
            Map<String, ManifestRow> manifestIndex,
            Map<Path, JsonNode> cache,
            String apiName,
            int stepNo
    ) {
        if (manifestIndex == null || apiName == null || apiName.isBlank() || stepNo < 1) return ExpectedStepData.empty();
        ManifestRow row = manifestIndex.get(apiName);
        if (row == null || row.expectedJson() == null || row.expectedJson().isBlank()) return ExpectedStepData.empty();

        Path expectedPath = contentRoot.resolve("fileFromJson/expectedJSON").resolve(row.expectedJson());
        JsonNode arr = cache.computeIfAbsent(expectedPath, p -> safeReadJson(p));
        if (arr == null || !arr.isArray()) return ExpectedStepData.empty();
        int idx = stepNo - 1;
        if (idx < 0 || idx >= arr.size()) return ExpectedStepData.empty();
        JsonNode step = arr.get(idx);
        if (step == null || step.isNull()) return ExpectedStepData.empty();

        JsonComparator.ComparisonConfig comparisonConfig = JsonComparator.configFromExpectedStep(step);
        String expectedValueField = "expected";
        JsonNode expectedNode = step.get(expectedValueField);
        if (expectedNode == null || expectedNode.isNull()) {
            expectedValueField = "expectedTableValue";
            expectedNode = step.get("expectedTableValue");
        }
        String scenarioKey = step.hasNonNull("queryName") ? "queryName" : "apiName";
        String scenarioName = step.hasNonNull(scenarioKey) ? step.get(scenarioKey).asText("") : apiName + stepNo;
        return new ExpectedStepData(
                expectedNode,
                comparisonConfig,
                JsonComparator.collectCommonIgnoreFields(step),
                JsonComparator.collectCaseIgnoreFields(step),
                row,
                expectedValueField,
                scenarioKey,
                scenarioName
        );
    }

    private static void putCanaryMetadata(
            Map<String, Object> target,
            ExpectedStepData expectedData,
            String endpointTemplate,
            JsonNode actualNode,
            String actualRaw
    ) {
        if (target == null) {
            return;
        }
        ExpectedStepData data = expectedData == null ? ExpectedStepData.empty() : expectedData;
        target.put("endpointTemplate", endpointTemplate == null ? "" : endpointTemplate);
        target.put("commonIgnore", new ArrayList<>(data.commonIgnore));
        target.put("caseIgnore", new ArrayList<>(data.caseIgnore));
        target.put("actualEffectivelyEmpty", actualEffectivelyEmpty(data.expectedNode, actualNode, actualRaw));
    }

    private static void putAssertionMetadata(
            Map<String, Object> target,
            List<Map<String, Object>> assertionResults,
            String assertionResultsArtifact
    ) {
        if (target == null || assertionResults == null || assertionResults.isEmpty()) {
            return;
        }
        target.put("assertionResults", assertionResults);
        target.put("assertionResultsArtifact", assertionResultsArtifact == null ? "" : assertionResultsArtifact);
    }

    private static boolean actualEffectivelyEmpty(JsonNode expectedNode, JsonNode actualNode, String actualRaw) {
        if (nodeEffectivelyEmpty(expectedNode)) {
            return false;
        }
        String raw = actualRaw == null ? "" : actualRaw.trim();
        if (actualNode == null || actualNode.isNull()) {
            return raw.isEmpty();
        }
        if (nodeEffectivelyEmpty(actualNode)) {
            return true;
        }
        return raw.isEmpty();
    }

    private static boolean nodeEffectivelyEmpty(JsonNode node) {
        if (node == null || node.isNull()) {
            return true;
        }
        if ((node.isObject() || node.isArray()) && node.size() == 0) {
            return true;
        }
        if (node.isTextual()) {
            String compact = node.asText("").replaceAll("\\s+", "");
            return compact.isEmpty() || "{}".equals(compact) || "[]".equals(compact);
        }
        return false;
    }

    private static void putRebaselineMetadata(
            Map<String, Object> target,
            ExpectedStepData expectedData,
            String recordedApiInputsPath,
            String recordedPayloadArtifact,
            String recordedOutputArtifact,
            String comparisonArtifact
    ) {
        if (target == null) {
            return;
        }
        String apiInputsFile = firstNonBlank(expectedData.apiInputsFile, fileName(recordedApiInputsPath));
        String payloadJsonFile = expectedData.payloadJsonFile;
        String expectedJsonFile = expectedData.expectedJsonFile;

        target.put("apiInputsFile", apiInputsFile);
        target.put("apiInputsPath", firstNonBlank(contentPath("apiInputs", apiInputsFile), recordedApiInputsPath));
        target.put("payloadJsonFile", payloadJsonFile);
        target.put("payloadJsonPath", contentPath("fileFromJson/payloadJSON", payloadJsonFile));
        target.put("expectedJsonFile", expectedJsonFile);
        target.put("expectedJsonPath", contentPath("fileFromJson/expectedJSON", expectedJsonFile));
        target.put("expectedValueField", expectedData.expectedValueField);
        target.put("scenarioKey", expectedData.scenarioKey);
        target.put("scenarioName", expectedData.scenarioName);
        target.put("resultApiInputsPath", recordedApiInputsPath == null ? "" : recordedApiInputsPath);
        target.put("jsonPayloadArtifact", recordedPayloadArtifact == null ? "" : recordedPayloadArtifact);
        target.put("jsonOutputArtifact", recordedOutputArtifact == null ? "" : recordedOutputArtifact);
        target.put("comparisonArtifact", comparisonArtifact == null ? "" : comparisonArtifact);
    }

    private static String contentPath(String directory, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        return directory + "/" + fileName;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private static String fileName(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            String normalized = path.replace('\\', '/');
            return Paths.get(normalized).getFileName().toString();
        } catch (Exception e) {
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            return slash >= 0 ? path.substring(slash + 1) : path;
        }
    }

    private static String extractComparisonArtifact(String reason) {
        if (reason == null || reason.isBlank()) {
            return "";
        }
        int marker = reason.indexOf("jsonOutputapiActualExpectedFileName");
        if (marker < 0) {
            return "";
        }
        int start = marker;
        while (start > 0 && !Character.isWhitespace(reason.charAt(start - 1))) {
            start--;
        }
        int end = marker;
        while (end < reason.length() && !Character.isWhitespace(reason.charAt(end))) {
            end++;
        }
        return reason.substring(start, end).replace('\\', '/').trim();
    }

    private static Path contentRoot(Path projectRoot) {
        Path root = projectRoot == null ? Paths.get(".").toAbsolutePath().normalize() : projectRoot.toAbsolutePath().normalize();
        if (hasAnalysisContent(root)) {
            return root;
        }
        Path parent = root.getParent();
        if (parent != null && hasAnalysisContent(parent)) {
            return parent.toAbsolutePath().normalize();
        }
        return root;
    }

    private static boolean hasAnalysisContent(Path root) {
        if (root == null) {
            return false;
        }
        return Files.isDirectory(root.resolve("apisToBeValidated"))
                && Files.isDirectory(root.resolve("fileFromJson/expectedJSON"));
    }

    private static JsonNode safeReadJson(Path path) {
        try {
            return MAPPER.readTree(readString(path));
        } catch (Exception e) {
            return null;
        }
    }

    private static String readString(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static JsonNode safeParseJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String readTextSafe(Path path) {
        if (path == null) return "";
        try {
            return readString(path);
        } catch (Exception e) {
            return "";
        }
    }

    private static String readRecordedText(Path projectRoot, Path runnerRoot, Path artifactRoot, String recordedPath) {
        if (recordedPath != null && recordedPath.endsWith(".download")) {
            return "";
        }
        return readTextSafe(resolveRecordedPath(projectRoot, runnerRoot, artifactRoot, recordedPath));
    }

    private static List<Map<String, Object>> readComparisonArtifactMismatches(Path comparisonArtifactPath) {
        String artifact = readTextSafe(comparisonArtifactPath);
        if (artifact.isBlank()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        boolean allMismatches = false;
        for (String line : artifact.split("\\R")) {
            String trimmed = line == null ? "" : line.trim();
            if ("All Mismatches:".equals(trimmed)) {
                allMismatches = true;
                continue;
            }
            if (!allMismatches || !trimmed.startsWith("- ")) {
                continue;
            }
            Map<String, Object> mismatch = parseMismatchLine(trimmed.substring(2));
            if (!mismatch.isEmpty()) {
                out.add(mismatch);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readAssertionResultsArtifact(Path assertionResultsArtifactPath) {
        JsonNode root = safeReadJson(assertionResultsArtifactPath);
        if (root == null || !root.isArray()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode item : root) {
            if (item != null && item.isObject()) {
                out.add(MAPPER.convertValue(item, Map.class));
            }
        }
        return out;
    }

    private static List<Map<String, Object>> mergeAssertionFailures(
            List<Map<String, Object>> comparison,
            List<Map<String, Object>> assertionResults
    ) {
        if (assertionResults == null || assertionResults.isEmpty()) {
            return comparison == null ? Collections.emptyList() : comparison;
        }
        List<Map<String, Object>> out = comparison == null ? new ArrayList<>() : new ArrayList<>(comparison);
        Set<String> existingPaths = new HashSet<>();
        for (Map<String, Object> row : out) {
            existingPaths.add(str(row.get("path")));
        }
        for (Map<String, Object> assertion : assertionResults) {
            if (assertionPassed(assertion)) {
                continue;
            }
            Map<String, Object> mismatch = assertionMismatch(assertion);
            String path = str(mismatch.get("path"));
            if (!existingPaths.contains(path)) {
                out.add(mismatch);
                existingPaths.add(path);
            }
        }
        return out;
    }

    private static boolean assertionPassed(Map<String, Object> assertion) {
        if (assertion == null) {
            return true;
        }
        Object passed = assertion.get("passed");
        if (passed instanceof Boolean b) {
            return b;
        }
        String status = str(assertion.get("status"));
        return "PASS".equalsIgnoreCase(status) || "true".equalsIgnoreCase(str(passed));
    }

    private static Map<String, Object> assertionMismatch(Map<String, Object> assertion) {
        Map<String, Object> out = new LinkedHashMap<>();
        String assertionName = str(assertion.get("assertionName"));
        String kind = firstNonBlank(str(assertion.get("failureKind")), "assertion_mismatch");
        out.put("path", "assertions." + (assertionName.isBlank() ? "unnamed" : assertionName));
        out.put("kind", kind);
        out.put("expected", str(assertion.get("resolvedExpected")));
        out.put("actual", str(assertion.get("resolvedActual")));
        out.put("assertion", assertionName);
        out.put("type", str(assertion.get("type")));
        return out;
    }

    private static Map<String, Object> parseMismatchLine(String line) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (line == null || line.isBlank()) {
            return out;
        }
        List<String> keys = Arrays.asList("kind", "path", "expected", "actual", "assertion", "type");
        String currentKey = "";
        StringBuilder currentValue = new StringBuilder();
        for (String token : line.trim().split("\\s+")) {
            int idx = token.indexOf('=');
            String candidateKey = idx > 0 ? token.substring(0, idx) : "";
            if (idx > 0 && keys.contains(candidateKey)) {
                putParsedMismatchValue(out, currentKey, currentValue);
                currentKey = candidateKey;
                currentValue.setLength(0);
                currentValue.append(token.substring(idx + 1));
            } else if (!currentKey.isEmpty()) {
                if (currentValue.length() > 0) {
                    currentValue.append(' ');
                }
                currentValue.append(token);
            }
        }
        putParsedMismatchValue(out, currentKey, currentValue);
        return out;
    }

    private static void putParsedMismatchValue(Map<String, Object> out, String key, StringBuilder value) {
        if (key == null || key.isBlank()) {
            return;
        }
        out.put(key, value == null ? "" : value.toString());
    }

    private static List<Map<String, Object>> recordedFailure(String reason) {
        if (reason == null || reason.isBlank()) {
            return Collections.emptyList();
        }
        Map<String, Object> mismatch = new LinkedHashMap<>();
        mismatch.put("path", "$");
        mismatch.put("kind", "recorded_failure");
        mismatch.put("expected", "PASS");
        mismatch.put("actual", reason);
        return Collections.singletonList(mismatch);
    }

    private static Path resolveRecordedPath(Path projectRoot, Path runnerRoot, Path artifactRoot, String recordedPath) {
        if (recordedPath == null || recordedPath.isBlank()) return null;

        List<Path> candidates = new ArrayList<>();
        tryAdd(candidates, recordedPath);

        String normalized = recordedPath.replace('\\', '/');
        String rel = normalized;
        if (normalized.startsWith("/workspace/")) {
            rel = normalized.substring("/workspace/".length());
        } else if (normalized.startsWith("workspace/")) {
            rel = normalized.substring("workspace/".length());
        } else if (normalized.startsWith("/")) {
            rel = normalized.substring(1);
        }

        addRelativeCandidates(candidates, projectRoot, rel);
        addRelativeCandidates(candidates, runnerRoot, rel);
        addRelativeCandidates(candidates, artifactRoot, rel);

        for (Path candidate : candidates) {
            if (candidate != null && Files.exists(candidate)) {
                return candidate;
            }
        }

        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static Path resolveAssertionResultsArtifactPath(
            Path runnerRoot,
            Path artifactRoot,
            String runFolder,
            String manifestTag,
            String api,
            int stepNo
    ) {
        if (runFolder == null || runFolder.isBlank() || manifestTag == null || manifestTag.isBlank() || api == null || api.isBlank() || stepNo < 1) {
            return null;
        }
        String fileName = ArtifactNames.assertionResultsFileName(manifestTag, api, stepNo);
        List<Path> candidates = new ArrayList<>();
        addAssertionArtifactCandidate(candidates, artifactRoot, "jsonOutput", runFolder, manifestTag, fileName);
        addAssertionArtifactCandidate(candidates, artifactRoot, "run-jsonOutput", runFolder, manifestTag, fileName);
        addAssertionArtifactCandidate(candidates, runnerRoot, "jsonOutput", runFolder, manifestTag, fileName);
        addAssertionArtifactCandidate(candidates, runnerRoot, "run-jsonOutput", runFolder, manifestTag, fileName);
        for (Path candidate : candidates) {
            if (candidate != null && Files.exists(candidate)) {
                return candidate;
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static void addAssertionArtifactCandidate(
            List<Path> candidates,
            Path base,
            String outputDir,
            String runFolder,
            String manifestTag,
            String fileName
    ) {
        if (base == null || outputDir == null || runFolder == null || manifestTag == null || fileName == null) {
            return;
        }
        candidates.add(base.resolve(outputDir).resolve(runFolder).resolve(manifestTag).resolve(fileName).toAbsolutePath().normalize());
    }

    private static String portablePath(Path runnerRoot, Path path) {
        // Missing artifacts are expected in partial runs or incomplete CI downloads; callers retain any recorded path separately.
        if (path == null || runnerRoot == null || !Files.exists(path)) {
            return "";
        }
        Path absolute = path.toAbsolutePath().normalize();
        Path root = runnerRoot.toAbsolutePath().normalize();
        if (absolute.startsWith(root)) {
            return root.relativize(absolute).toString();
        }
        return absolute.toString();
    }

    private static void addRelativeCandidates(List<Path> candidates, Path base, String rel) {
        if (base == null || rel == null || rel.isBlank()) return;
        tryAdd(candidates, base.resolve(rel).normalize().toString());

        int slash = rel.indexOf('/');
        String first = slash >= 0 ? rel.substring(0, slash) : rel;
        String remainder = slash >= 0 ? rel.substring(slash + 1) : "";
        if (!first.isBlank() && !first.startsWith("run-")) {
            String runPrefixed = "run-" + first + (remainder.isBlank() ? "" : "/" + remainder);
            tryAdd(candidates, base.resolve(runPrefixed).normalize().toString());
        }
    }

    private static void tryAdd(List<Path> candidates, String raw) {
        if (raw == null || raw.isBlank()) return;
        try {
            candidates.add(Paths.get(raw).toAbsolutePath().normalize());
        } catch (Exception ignored) {
        }
    }

    private static Path artifactRoot(Path manifestResultsDir) {
        if (manifestResultsDir == null) return null;
        Path runFolderDir = manifestResultsDir.getParent();
        Path runResultsDir = runFolderDir == null ? null : runFolderDir.getParent();
        return runResultsDir == null ? null : runResultsDir.getParent();
    }

    private static String buildUrl(String protocol, String server, Map<String, String> serviceBaseUrls, String apiPath) throws Exception {
        return UrlResolver.buildApiUrl(protocol, server, serviceBaseUrls, apiPath);
    }

    private static int parseIntOrDefault(String v, int d) {
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return d; }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String get(CSVRecord rec, String key) {
        return rec.isMapped(key) ? rec.get(key).trim() : "";
    }

    private static List<Map<String, Object>> compareExpected(JsonNode expected, JsonNode actual, Set<String> ignore) {
        return JsonComparator.compare(expected, actual, JsonComparator.config(ignore));
    }

    private static List<Map<String, Object>> compareExpected(JsonNode expected, JsonNode actual, JsonComparator.ComparisonConfig config) {
        return JsonComparator.compare(expected, actual, config);
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

    private static void compareNode(String path, JsonNode expected, JsonNode actual, Set<String> ignore, List<Map<String, Object>> out) {
        if (shouldIgnore(path, ignore)) return;
        if (expected == null || expected.isNull()) return;

        if (expected.isObject()) {
            if (actual == null || !actual.isObject()) {
                mismatch(path, expected, actual, "type_mismatch", out);
                return;
            }
            Iterator<String> fields = expected.fieldNames();
            while (fields.hasNext()) {
                String f = fields.next();
                String child = path.isEmpty() ? f : path + "." + f;
                compareNode(child, expected.get(f), actual.get(f), ignore, out);
            }
            Iterator<String> actualFields = actual.fieldNames();
            while (actualFields.hasNext()) {
                String f = actualFields.next();
                if (expected.has(f)) {
                    continue;
                }
                String child = path.isEmpty() ? f : path + "." + f;
                if (shouldIgnore(child, ignore)) {
                    continue;
                }
                mismatch(child, null, actual.get(f), "unexpected_field", out);
            }
            return;
        }

        if (expected.isArray()) {
            if (actual == null || !actual.isArray()) {
                mismatch(path, expected, actual, "type_mismatch", out);
                return;
            }
            for (int i = 0; i < expected.size(); i++) {
                String child = path + "[" + i + "]";
                JsonNode a = i < actual.size() ? actual.get(i) : null;
                compareNode(child, expected.get(i), a, ignore, out);
            }
            for (int i = expected.size(); i < actual.size(); i++) {
                String child = path + "[" + i + "]";
                if (shouldIgnore(child, ignore)) {
                    continue;
                }
                mismatch(child, null, actual.get(i), "unexpected_item", out);
            }
            return;
        }

        BigDecimal evNum = comparableNumber(expected);
        BigDecimal avNum = comparableNumber(actual);
        boolean compareAsNumber = shouldCompareAsNumber(expected) || shouldCompareAsNumber(actual);
        if (compareAsNumber && evNum != null && avNum != null) {
            if (evNum.compareTo(avNum) != 0) mismatch(path, expected, actual, "value_mismatch", out);
            return;
        }

        JsonNode embeddedExpected = parseEmbeddedJson(expected);
        JsonNode embeddedActual = parseEmbeddedJson(actual);
        if (embeddedExpected != null && embeddedActual != null) {
            compareNode(path, embeddedExpected, embeddedActual, ignore, out);
            return;
        }

        String ev = normalized(expected);
        String av = normalized(actual);
        if (!ev.equals(av)) mismatch(path, expected, actual, "value_mismatch", out);
    }

    private static Set<String> collectIgnoreFields(JsonNode expectedStep) {
        Set<String> ignore = new HashSet<>();
        addIgnore(expectedStep == null ? null : expectedStep.get("ignore"), ignore);
        addIgnore(expectedStep == null ? null : expectedStep.get("commonIgnore"), ignore);
        return ignore;
    }

    private static void addIgnore(JsonNode node, Set<String> ignore) {
        if (node == null || !node.isArray()) return;
        for (JsonNode n : node) {
            if (n != null && n.isTextual()) {
                String v = n.asText().trim();
                if (!v.isEmpty()) ignore.add(v);
            }
        }
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
        JsonNode node = expected != null && !expected.isNull() ? expected : actual;
        if (node == null || node.isNull()) return "null";
        if (node.isObject()) return "object";
        if (node.isArray()) return "array";
        if (node.isBoolean()) return "boolean";
        if (node.isNumber()) return "number";
        if (node.isTextual()) return "string";
        return "string";
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
        // For plain textual integers ("001"), preserve strict string compare behavior.
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

    private static final class ExpectedStepData {
        final JsonNode expectedNode;
        final JsonComparator.ComparisonConfig comparisonConfig;
        final Set<String> commonIgnore;
        final Set<String> caseIgnore;
        final String apiInputsFile;
        final String payloadJsonFile;
        final String expectedJsonFile;
        final String expectedValueField;
        final String scenarioKey;
        final String scenarioName;

        ExpectedStepData(
                JsonNode expectedNode,
                JsonComparator.ComparisonConfig comparisonConfig,
                Set<String> commonIgnore,
                Set<String> caseIgnore,
                ManifestRow row,
                String expectedValueField,
                String scenarioKey,
                String scenarioName
        ) {
            this.expectedNode = expectedNode;
            this.comparisonConfig = comparisonConfig == null ? JsonComparator.emptyConfig() : comparisonConfig;
            this.commonIgnore = commonIgnore == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(commonIgnore));
            this.caseIgnore = caseIgnore == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(caseIgnore));
            this.apiInputsFile = row == null ? "" : row.apiInputsFile();
            this.payloadJsonFile = row == null ? "" : row.payloadJson();
            this.expectedJsonFile = row == null ? "" : row.expectedJson();
            this.expectedValueField = expectedValueField == null ? "" : expectedValueField;
            this.scenarioKey = scenarioKey == null ? "" : scenarioKey;
            this.scenarioName = scenarioName == null ? "" : scenarioName;
        }

        static ExpectedStepData empty() {
            return new ExpectedStepData(
                    null,
                    JsonComparator.emptyConfig(),
                    Collections.emptySet(),
                    Collections.emptySet(),
                    null,
                    "",
                    "",
                    ""
            );
        }
    }
}
