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
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExecutionReporter {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static void write(Path outputFile, List<ExecutionReport> reports, Path projectRoot, String runFolder) throws IOException {
        Files.createDirectories(outputFile.getParent());

        int apiScenariosExecuted = reports.stream().mapToInt(r -> r.apiScenariosExecuted).sum();
        int stepsExecuted = reports.stream().mapToInt(r -> r.stepsExecuted).sum();
        int stepsPassed = reports.stream().mapToInt(r -> r.stepsPassed).sum();
        int stepsFailed = reports.stream().mapToInt(r -> r.stepsFailed).sum();
        int infrastructureFailures = (int) reports.stream().filter(r -> r != null && r.infrastructureFailure).count();

        Map<String, Object> summary = new HashMap<>();
        summary.put("generatedAt", Instant.now().toString());
        summary.put("mode", "execute");
        summary.put("projectRoot", projectRoot.toString());
        summary.put("apiScenariosExecuted", apiScenariosExecuted);
        // Backward compatible naming: keep steps* and add testCases* aliases.
        summary.put("stepsExecuted", stepsExecuted);
        summary.put("stepsPassed", stepsPassed);
        summary.put("stepsFailed", stepsFailed);
        summary.put("testCasesExecuted", stepsExecuted);
        summary.put("testCasesPassed", stepsPassed);
        summary.put("testCasesFailed", stepsFailed);
        summary.put("infrastructureFailures", infrastructureFailures);
        summary.put("hasInfrastructureFailure", infrastructureFailures > 0);
        summary.put("reports", reportsForSummaryJson(reports));

        MAPPER.writeValue(outputFile.toFile(), summary);

        Path htmlFile = replaceExtension(outputFile, ".html");
        HtmlReportWriter.write(htmlFile, reports);
        if (runFolder != null && !runFolder.isBlank()) {
            RegressionReportWriter.write(projectRoot, runFolder);
        }

        writeOverallResultsCsv(projectRoot, runFolder, reports);
        writeAssertionResultsCsv(projectRoot, runFolder, reports);
    }

    private static List<ExecutionReport> reportsForSummaryJson(List<ExecutionReport> reports) {
        List<ExecutionReport> out = new ArrayList<>();
        if (reports == null) {
            return out;
        }
        for (ExecutionReport report : reports) {
            if (report == null) {
                continue;
            }
            ExecutionReport copy = new ExecutionReport();
            copy.manifest = report.manifest;
            copy.apiScenariosExecuted = report.apiScenariosExecuted;
            copy.stepsExecuted = report.stepsExecuted;
            copy.stepsPassed = report.stepsPassed;
            copy.stepsFailed = report.stepsFailed;
            copy.infrastructureFailure = report.infrastructureFailure;
            copy.infrastructureError = report.infrastructureError;
            copy.passes = rowsForSummaryJson(report.passes);
            copy.failures = rowsForSummaryJson(report.failures);
            out.add(copy);
        }
        return out;
    }

    private static List<Map<String, Object>> rowsForSummaryJson(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String key = entry.getKey();
                if (isCanaryOnlyRowKey(key)) {
                    continue;
                }
                Object value = entry.getValue();
                if ("comparison".equals(key)) {
                    value = comparisonForSummaryJson(value);
                }
                copy.put(key, value);
            }
            out.add(copy);
        }
        return out;
    }

    private static Object comparisonForSummaryJson(Object value) {
        if (!(value instanceof List<?> list)) {
            return value;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> itemMap)) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : itemMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if ("valueType".equals(key) || "parentArrayPath".equals(key)) {
                    continue;
                }
                copy.put(key, entry.getValue());
            }
            out.add(copy);
        }
        return out;
    }

    private static boolean isCanaryOnlyRowKey(String key) {
        return "endpointTemplate".equals(key)
                || "commonIgnore".equals(key)
                || "caseIgnore".equals(key)
                || "actualEffectivelyEmpty".equals(key);
    }

    private static Path replaceExtension(Path path, String newExt) {
        String n = path.getFileName().toString();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n;
        return path.getParent().resolve(base + newExt);
    }

    private static void writeOverallResultsCsv(Path projectRoot, String runFolder, List<ExecutionReport> reports) throws IOException {
        if (runFolder == null || runFolder.isBlank()) return;
        Path runnerRoot = PathResolver.runnerRoot(projectRoot);
        Path out = runnerRoot.resolve("results").resolve(runFolder).resolve("overall_results.csv");
        Files.createDirectories(out.getParent());

        Map<String, ApiRollup> byApi = new LinkedHashMap<>();
        for (ExecutionReport report : reports) {
            if (report == null) continue;
            aggregateRows(byApi, report.passes, true);
            aggregateRows(byApi, report.failures, false);
        }

        String[] dt = parseRunFolderDateTime(runFolder);
        int totalPass = 0;
        int totalFail = 0;

        try (BufferedWriter bw = Files.newBufferedWriter(out);
             CSVPrinter csv = new CSVPrinter(bw, CSVFormat.DEFAULT)) {
            csv.printRecord("#", "Payload Injection Date", "Payload Injection Time", "API Name", "Module Name", "Total TCs", "Pass Count", "Fail Count", "Owner");
            int index = 1;
            for (ApiRollup r : byApi.values()) {
                int total = r.pass + r.fail;
                totalPass += r.pass;
                totalFail += r.fail;
                String execDate = r.execDate.isBlank() ? dt[0] : r.execDate;
                String execTime = r.execTime.isBlank() ? dt[1] : r.execTime;
                csv.printRecord(index, execDate, execTime, r.apiName, r.moduleName, total, r.pass, r.fail, r.owner);
                index++;
            }
            csv.printRecord("", "", "", "", "Total", totalPass + totalFail, totalPass, totalFail);
        }
    }

    private static void writeAssertionResultsCsv(Path projectRoot, String runFolder, List<ExecutionReport> reports) throws IOException {
        if (runFolder == null || runFolder.isBlank()) return;
        Path runnerRoot = PathResolver.runnerRoot(projectRoot);
        Path out = runnerRoot.resolve("results").resolve(runFolder).resolve("assertion-results.csv");
        Files.createDirectories(out.getParent());

        try (BufferedWriter bw = Files.newBufferedWriter(out);
             CSVPrinter csv = new CSVPrinter(bw, CSVFormat.DEFAULT)) {
            csv.printRecord(
                    "manifest",
                    "api",
                    "module",
                    "owner",
                    "step",
                    "stepApiName",
                    "assertionName",
                    "status",
                    "passed",
                    "type",
                    "operator",
                    "actualPath",
                    "expectedPath",
                    "resolvedActual",
                    "resolvedExpected",
                    "failureKind",
                    "message",
                    "assertionResultsArtifact"
            );
            if (reports == null) {
                return;
            }
            for (ExecutionReport report : reports) {
                if (report == null) {
                    continue;
                }
                writeAssertionRows(csv, report.passes);
                writeAssertionRows(csv, report.failures);
            }
        }
    }

    private static void writeAssertionRows(CSVPrinter csv, List<Map<String, Object>> rows) throws IOException {
        if (rows == null) {
            return;
        }
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            List<Map<String, Object>> assertionResults = mapList(row.get("assertionResults"));
            if (assertionResults.isEmpty()) {
                continue;
            }
            for (Map<String, Object> assertion : assertionResults) {
                csv.printRecord(
                        str(row.get("manifest")),
                        str(row.get("api")),
                        str(row.get("module")),
                        str(row.get("owner")),
                        str(row.get("step")),
                        str(row.get("stepApiName")),
                        str(assertion.get("assertionName")),
                        str(assertion.get("status")),
                        str(assertion.get("passed")),
                        str(assertion.get("type")),
                        str(assertion.get("operator")),
                        str(assertion.get("actualPath")),
                        str(assertion.get("expectedPath")),
                        str(assertion.get("resolvedActual")),
                        str(assertion.get("resolvedExpected")),
                        str(assertion.get("failureKind")),
                        str(assertion.get("message")),
                        str(row.get("assertionResultsArtifact"))
                );
            }
        }
    }

    private static void aggregateRows(Map<String, ApiRollup> byApi, List<Map<String, Object>> rows, boolean pass) {
        if (rows == null) return;
        for (Map<String, Object> row : rows) {
            if (row == null) continue;
            String api = str(row.get("api"));
            if (api.isBlank()) api = "NA";
            String module = str(row.get("module"));
            if (module.isBlank()) module = "NA";
            String owner = str(row.get("owner"));
            if (owner.isBlank()) owner = "NA";
            final String apiFinal = api;
            final String moduleFinal = module;
            final String ownerFinal = owner;
            final String execDate = "";
            final String execTime = "";
            String key = apiFinal + "|" + moduleFinal + "|" + ownerFinal;
            ApiRollup rollup = byApi.computeIfAbsent(key, k -> new ApiRollup(apiFinal, moduleFinal, ownerFinal, execDate, execTime));
            if (pass) rollup.pass++;
            else rollup.fail++;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?>) {
                out.add((Map<String, Object>) item);
            }
        }
        return out;
    }

    private static String[] parseRunFolderDateTime(String runFolder) {
        if (runFolder == null) return new String[]{"", ""};
        int idx = runFolder.indexOf('_');
        if (idx < 0) return new String[]{runFolder, ""};
        String date = runFolder.substring(0, idx);
        String time = runFolder.substring(idx + 1).toUpperCase(Locale.ROOT);
        return new String[]{date, time};
    }

    private static final class ApiRollup {
        final String apiName;
        final String moduleName;
        final String owner;
        String execDate;
        String execTime;
        int pass;
        int fail;

        ApiRollup(String apiName, String moduleName, String owner, String execDate, String execTime) {
            this.apiName = apiName;
            this.moduleName = moduleName;
            this.owner = owner;
            this.execDate = execDate == null ? "" : execDate;
            this.execTime = execTime == null ? "" : execTime;
        }
    }
}
