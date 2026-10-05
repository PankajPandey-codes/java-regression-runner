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

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DryRunEngine {
    private final Path projectRoot;
    private final Set<String> apiFilters;

    public DryRunEngine(Path projectRoot, Set<String> apiFilters) {
        this.projectRoot = projectRoot;
        this.apiFilters = apiFilters;
    }

    public ManifestReport runForManifest(String manifestName) throws Exception {
        Path manifestPath = projectRoot.resolve("apisToBeValidated").resolve(manifestName);
        List<ManifestRow> rows = ManifestLoader.load(manifestPath);

        ManifestReport report = new ManifestReport();
        report.manifest = manifestName;
        report.totalRows = rows.size();

        for (ManifestRow row : rows) {
            if (!apiFilters.isEmpty() && !apiFilters.contains(normalizeApiName(row.apiName()))) {
                continue;
            }
            if (row.isEnabledStrict()) {
                report.enabledRowsStrict++;
            } else {
                report.disabledRows++;
            }

            if (row.isEnabledCaseInsensitive()) {
                report.enabledRowsCaseInsensitive++;
            }

            if (row.isEnabledCaseInsensitive() && !row.isEnabledStrict()) {
                Map<String, Object> warning = new HashMap<>();
                warning.put("type", "toBeValidated_case_mismatch");
                warning.put("line", row.lineNumber());
                warning.put("apiName", row.apiName());
                warning.put("value", row.toBeValidated());
                warning.put("message", "JMeter currently runs only when toBeValidated is exactly 'y'.");
                report.warnings.add(warning);
            }

            String module = row.moduleName() == null || row.moduleName().isBlank() ? "NA" : row.moduleName();
            report.moduleCounts.put(module, report.moduleCounts.getOrDefault(module, 0) + 1);

            String key = (row.fileType() == null ? "" : row.fileType().toLowerCase()) + "|mysql=" +
                    (row.mysql() == null ? "" : row.mysql().toLowerCase());
            report.executionTypeCounts.put(key, report.executionTypeCounts.getOrDefault(key, 0) + 1);

            if (!row.isEnabledStrict()) {
                continue;
            }

            Path input = projectRoot.resolve("apiInputs").resolve(row.apiInputsFile());
            Path payload = projectRoot.resolve("fileFromJson/payloadJSON").resolve(row.payloadJson());
            Path expected = projectRoot.resolve("fileFromJson/expectedJSON").resolve(row.expectedJson());

            addMissingIfAny(report, row, "input", input);
            addMissingIfAny(report, row, "payload", payload);
            addMissingIfAny(report, row, "expected", expected);
        }

        return report;
    }

    private static void addMissingIfAny(ManifestReport report, ManifestRow row, String kind, Path path) {
        if (path.toFile().exists()) {
            return;
        }

        if ("input".equals(kind)) report.missingInputs++;
        if ("payload".equals(kind)) report.missingPayload++;
        if ("expected".equals(kind)) report.missingExpected++;

        Map<String, Object> detail = new HashMap<>();
        detail.put("line", row.lineNumber());
        detail.put("apiName", row.apiName());
        detail.put("kind", kind);
        detail.put("path", path.toString());
        report.missingDetails.add(detail);
    }

    private static String normalizeApiName(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
