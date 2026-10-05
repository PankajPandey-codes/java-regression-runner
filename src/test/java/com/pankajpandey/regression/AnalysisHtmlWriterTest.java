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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisHtmlWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsMixedFailuresOutOfTextDiffIndex() throws Exception {
        AnalysisReporter.AnalysisData data = new AnalysisReporter.AnalysisData();
        data.generatedAt = "2026-05-06T12:00:00Z";
        data.runFolder = "06-05-2026_1200PM";
        data.statusCodeMismatches.add(caseRow("StatusOnlyApi", "STATUS_CODE", 200, 500, List.of()));
        data.payloadDiffs.add(caseRow("PayloadOnlyApi", "PAYLOAD_DIFF", 200, 200, List.of(
                Map.of("path", "data.message", "cause", "ValueMismatch", "expected", "ok", "actual", "bad")
        )));
        data.mixedFailures.add(caseRow("MixedApi", "STATUS_AND_PAYLOAD", 200, 500, List.of(
                Map.of("path", "data.code", "cause", "ValueMismatch", "expected", "A", "actual", "B")
        )));

        Path htmlFile = tempDir.resolve("analysis-report.html");
        AnalysisHtmlWriter.write(htmlFile, data);

        String diffIndex = Files.readString(tempDir.resolve("index_diff.html"));
        String httpIndex = Files.readString(tempDir.resolve("index_http.html"));

        assertTrue(diffIndex.contains("PayloadOnlyApi"));
        assertTrue(diffIndex.contains("endpoint-cell"));
        assertTrue(diffIndex.contains("endpoint-text"));
        assertTrue(diffIndex.contains("overflow-wrap:anywhere"));
        assertFalse(diffIndex.contains("MixedApi"));
        assertFalse(diffIndex.contains("StatusOnlyApi"));

        assertTrue(httpIndex.contains("MixedApi"));
        assertTrue(httpIndex.contains("StatusOnlyApi"));
        assertFalse(httpIndex.contains("PayloadOnlyApi"));
    }

    @Test
    void landingPageKeepsPrimaryViewsAndAddsAnalysisHighlights() throws Exception {
        AnalysisReporter.AnalysisData data = new AnalysisReporter.AnalysisData();
        data.generatedAt = "2026-05-06T12:00:00Z";
        data.runFolder = "06-05-2026_1200PM";
        data.totalExecuted = 12;
        data.totalFailed = 4;
        data.totalPassed = 8;
        data.passRate = 66.67;
        data.byModule = List.of(new AnalysisReporter.BreakdownRow("FeeAmort", 4, 1, 3, 1));
        data.byOwner = List.of(new AnalysisReporter.BreakdownRow("Platform", 4, 1, 3, 1));
        data.byApi = List.of(new AnalysisReporter.BreakdownRow("FeeAmortLoLDisbursement", 2, 0, 2, 0));
        data.causeBreakdown = List.of(
                new AnalysisReporter.CauseCount("ValueMismatch", 3),
                new AnalysisReporter.CauseCount("MissingField", 2),
                new AnalysisReporter.CauseCount("UnexpectedField", 1)
        );
        data.topDiffPaths = List.of(
                new AnalysisReporter.PathCount("data.schedule[]", 3),
                new AnalysisReporter.PathCount("data.amount", 2)
        );
        data.largestDiffCases = List.of(
                new AnalysisReporter.CaseDigest("APIsToBeValidated_A.csv", "TimingApi", "Module", "Owner", 1, "POST", 200, 200, "PAYLOAD_DIFF", 1, List.of("data.schedule[]")),
                new AnalysisReporter.CaseDigest("APIsToBeValidated_A.csv", "DriftApi", "Module", "Owner", 2, "POST", 200, 200, "PAYLOAD_DIFF", 1, List.of("data.newField")),
                new AnalysisReporter.CaseDigest("APIsToBeValidated_A.csv", "CalcApi", "Module", "Owner", 3, "POST", 200, 200, "PAYLOAD_DIFF", 1, List.of("data.amount"))
        );
        data.statusCodeMismatches.add(caseRow("StatusOnlyApi", "STATUS_CODE", 200, 500, List.of()));
        data.payloadDiffs.add(caseRow("TimingApi", "PAYLOAD_DIFF", 200, 200, List.of(
                Map.of("path", "data.rows", "cause", "LengthMismatch", "expected", "[{\"id\":1}]", "actual", "[]")
        ), "{\"rows\":[{\"id\":1}]}", "{\"rows\":[]}"));
        data.payloadDiffs.add(caseRow("DriftApi", "PAYLOAD_DIFF", 200, 200, List.of(
                Map.of("path", "data.newField", "cause", "UnexpectedField", "expected", "", "actual", "\"x\"")
        ), "{\"id\":1}", "{\"id\":1,\"newField\":\"x\"}"));
        data.payloadDiffs.add(caseRow("CalcApi", "PAYLOAD_DIFF", 200, 200, List.of(
                Map.of("path", "data.amount", "cause", "ValueMismatch", "expected", "10", "actual", "12")
        ), "{\"amount\":10}", "{\"amount\":12}"));

        Path htmlFile = tempDir.resolve("analysis-report.html");
        AnalysisHtmlWriter.write(htmlFile, data);

        String landing = Files.readString(htmlFile);

        assertTrue(landing.contains("Analysis Start Page"));
        assertTrue(landing.contains("ring-num'>66.67%"));
        assertFalse(landing.contains("ring-num'>67%"));
        assertTrue(landing.contains("Primary Routes"));
        assertTrue(landing.contains("Open HTTP / Status View"));
        assertTrue(landing.contains("Open Text / Payload View"));
        assertTrue(landing.contains("Top Failure Concentration"));
        assertTrue(landing.contains("Analysis Highlights"));
        assertTrue(landing.contains("Deep Signals"));
        assertTrue(landing.contains("Open supporting text-comparison signals"));
        assertTrue(landing.contains("Possible Application Error"));
        assertTrue(landing.contains("Likely Timing / State Availability"));
        assertTrue(landing.contains("Baseline / Contract Drift"));
        assertTrue(landing.contains("data.schedule[]"));
        assertTrue(landing.contains("Repeated cause"));
        assertFalse(landing.contains("Where To Start"));
        assertFalse(landing.contains("Suggested Triage Order"));
        assertFalse(landing.contains("Pattern Snapshot"));
    }

    @Test
    void keyedArrayPathsHighlightMatchedItemsInCasePage() throws Exception {
        AnalysisReporter.AnalysisData data = new AnalysisReporter.AnalysisData();
        data.generatedAt = "2026-05-06T12:00:00Z";
        data.runFolder = "06-05-2026_1200PM";
        data.payloadDiffs.add(caseRow("KeyedApi", "PAYLOAD_DIFF", 200, 200, List.of(
                Map.of("path", "data[id=2].value", "kind", "value_mismatch", "expected", "old", "actual", "new")
        ), "{\"data\":[{\"id\":2,\"value\":\"old\"},{\"id\":1,\"value\":\"same\"}]}",
                "{\"data\":[{\"id\":1,\"value\":\"same\"},{\"id\":2,\"value\":\"new\"}]}"));

        Path htmlFile = tempDir.resolve("analysis-report.html");
        AnalysisHtmlWriter.write(htmlFile, data);

        Path diffCase;
        try (Stream<Path> files = Files.list(tempDir.resolve("diff_cases"))) {
            diffCase = files
                    .filter(path -> path.getFileName().toString().endsWith(".html"))
                    .findFirst()
                    .orElseThrow();
        }
        String caseHtml = Files.readString(diffCase);

        assertTrue(caseHtml.contains("<span class='hl-val'>&quot;old&quot;</span>"));
        assertTrue(caseHtml.contains("<span class='hl-val'>&quot;new&quot;</span>"));
    }

    private static AnalysisReporter.AnalyzedCase caseRow(
            String api,
            String failureType,
            int expectedCode,
            int actualCode,
            List<Map<String, Object>> comparison
    ) {
        return caseRow(api, failureType, expectedCode, actualCode, comparison, "{\"ok\":true}", "{\"ok\":false}");
    }

    private static AnalysisReporter.AnalyzedCase caseRow(
            String api,
            String failureType,
            int expectedCode,
            int actualCode,
            List<Map<String, Object>> comparison,
            String expected,
            String actual
    ) {
        AnalysisReporter.AnalyzedCase row = new AnalysisReporter.AnalyzedCase();
        row.manifest = "APIsToBeValidated_A.csv";
        row.api = api;
        row.module = "Module";
        row.owner = "Owner";
        row.step = 1;
        row.method = "POST";
        row.url = "/test";
        row.expectedCode = expectedCode;
        row.actualCode = actualCode;
        row.failureType = failureType;
        row.expected = expected;
        row.actual = actual;
        row.comparison = comparison;
        row.comparisonFailed = !comparison.isEmpty();
        return row;
    }
}
