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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RunStatusWriter {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private RunStatusWriter() {}

    static void write(
            Path projectRoot,
            String runFolder,
            String status,
            Instant startedAt,
            String message,
            List<String> manifests,
            Integer stepsExecuted,
            Integer stepsPassed,
            Integer stepsFailed,
            Integer infrastructureFailures
    ) throws IOException {
        if (runFolder == null || runFolder.isBlank()) return;
        Path out = statusPath(projectRoot, runFolder);
        Files.createDirectories(out.getParent());

        Map<String, Object> payload = new LinkedHashMap<>();
        String suiteStatus = suiteStatus(status);
        payload.put("status", status);
        payload.put("suiteStatus", suiteStatus);
        payload.put("suitePassed", "PASS".equals(suiteStatus));
        payload.put("runFolder", runFolder);
        payload.put("projectRoot", projectRoot.toAbsolutePath().normalize().toString());
        payload.put("startedAt", startedAt == null ? null : startedAt.toString());
        payload.put("updatedAt", Instant.now().toString());
        payload.put("message", message == null ? "" : message);
        payload.put("manifests", manifests);
        payload.put("stepsExecuted", stepsExecuted);
        payload.put("stepsPassed", stepsPassed);
        payload.put("stepsFailed", stepsFailed);
        payload.put("infrastructureFailures", infrastructureFailures);
        payload.put("executeSummaryJson", summaryPath(projectRoot, runFolder).toString());
        payload.put("executeSummaryHtml", replaceExtension(summaryPath(projectRoot, runFolder), ".html").toString());
        payload.put("regressionReportHtml", regressionReportPath(projectRoot, runFolder).toString());
        payload.put("overallResultsCsv", resultsRoot(projectRoot, runFolder).resolve("overall_results.csv").toString());

        MAPPER.writeValue(out.toFile(), payload);
    }

    private static String suiteStatus(String status) {
        if ("COMPLETED_SUCCESS".equals(status)
                || "COMPLETED_WITH_TEST_FAILURES".equals(status)) {
            return "PASS";
        }
        if ("FAILED_INFRA".equals(status)
                || "INTERRUPTED".equals(status)) {
            return "FAIL";
        }
        return "UNKNOWN";
    }

    static Path statusPath(Path projectRoot, String runFolder) {
        return resultsRoot(projectRoot, runFolder).resolve("run-status.json").toAbsolutePath().normalize();
    }

    private static Path summaryPath(Path projectRoot, String runFolder) {
        return resultsRoot(projectRoot, runFolder).resolve("execute-summary.json").toAbsolutePath().normalize();
    }

    private static Path regressionReportPath(Path projectRoot, String runFolder) {
        return resultsRoot(projectRoot, runFolder).resolve("regression-report.html").toAbsolutePath().normalize();
    }

    private static Path resultsRoot(Path projectRoot, String runFolder) {
        Path runnerRoot = PathResolver.runnerRoot(projectRoot);
        return runnerRoot.resolve("results").resolve(runFolder);
    }

    private static Path replaceExtension(Path path, String newExt) {
        String n = path.getFileName().toString();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n;
        return path.getParent().resolve(base + newExt);
    }
}
