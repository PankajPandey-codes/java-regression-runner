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

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionReporterTest {

    @TempDir
    Path tempDir;

    @Test
    void overallResultsCsvUsesEffectiveReportStatusInsteadOfRawCsvStatus() throws Exception {
        Path repoRoot = tempDir.resolve("repo");
        Path runnerRoot = repoRoot.resolve("java-regression-runner");
        String runFolder = "15-05-2026_1111AM";
        Files.createDirectories(runnerRoot.resolve("results").resolve(runFolder).resolve("A"));

        Files.writeString(runnerRoot.resolve("results").resolve(runFolder).resolve("A/A__FlakyOrderApi_15-05-2026_1111AM_results.csv"),
                "Test Execution Date,Execution Time,API Name,Module Name,Test Scenario Number,JSON Payload,Results JSON File Name,Test Result,Expected Response Code,Actual Response Code,API Inputs File,API Method,API Path & Value,Reason For Failure,Owner\n"
                        + "\"15-05-2026\",\"1111AM\",\"FlakyOrderApi\",\"Module\",\"1\",\"payload.json\",\"actual.json\",\"FAIL\",\"200\",\"200\",\"apiInputs/FlakyOrderApi.csv\",\"GET\",\"/api/test\",\"raw comparison failure\",\"Owner\"\n");

        ExecutionReport report = new ExecutionReport();
        report.manifest = "APIsToBeValidated_A.csv";
        report.stepsExecuted = 1;
        report.stepsPassed = 1;
        report.apiScenariosExecuted = 1;
        report.passes.add(row("FlakyOrderApi", "Module", "Owner"));

        ExecutionReporter.write(
                runnerRoot.resolve("results").resolve(runFolder).resolve("execute-summary.json"),
                List.of(report),
                repoRoot,
                runFolder
        );

        Path wrapper = runnerRoot.resolve("results").resolve(runFolder).resolve("regression-report.html");
        assertTrue(Files.exists(wrapper));
        assertTrue(Files.readString(wrapper).contains("Execution Summary"));

        String overall = Files.readString(runnerRoot.resolve("results").resolve(runFolder).resolve("overall_results.csv"));
        assertTrue(overall.contains("FlakyOrderApi,Module,1,1,0,Owner"), overall);
        assertTrue(overall.contains("Total,1,1,0"), overall);

        CSVRecord assertion = firstAssertionRecord(Files.readString(runnerRoot.resolve("results").resolve(runFolder).resolve("assertion-results.csv")));
        assertTrue(assertion.isMapped("assertionName"));
        assertTrue(assertion.isMapped("resolvedActual"));
        org.junit.jupiter.api.Assertions.assertEquals("FlakyOrderApi", assertion.get("api"));
        org.junit.jupiter.api.Assertions.assertEquals("ianp-equals-booked-interest-unpaid", assertion.get("assertionName"));
        org.junit.jupiter.api.Assertions.assertEquals("PASS", assertion.get("status"));
        org.junit.jupiter.api.Assertions.assertEquals("interestAccruedNotDueNotPaid", assertion.get("actualPath"));
        org.junit.jupiter.api.Assertions.assertEquals("bookedInterestUnpaid", assertion.get("expectedPath"));
        org.junit.jupiter.api.Assertions.assertEquals("14.58", assertion.get("resolvedActual"));
        org.junit.jupiter.api.Assertions.assertEquals("14.58", assertion.get("resolvedExpected"));

        String summary = Files.readString(runnerRoot.resolve("results").resolve(runFolder).resolve("execute-summary.json"));
        assertFalse(summary.contains("endpointTemplate"), summary);
        assertFalse(summary.contains("commonIgnore"), summary);
        assertFalse(summary.contains("caseIgnore"), summary);
        assertFalse(summary.contains("actualEffectivelyEmpty"), summary);
        assertFalse(summary.contains("valueType"), summary);
        assertFalse(summary.contains("parentArrayPath"), summary);
    }

    @Test
    void overallResultsCsvStillShowsEffectiveFailures() throws Exception {
        Path repoRoot = tempDir.resolve("repo");
        Path runnerRoot = repoRoot.resolve("java-regression-runner");
        String runFolder = "15-05-2026_1111AM";
        Files.createDirectories(runnerRoot.resolve("results").resolve(runFolder).resolve("A"));

        Files.writeString(runnerRoot.resolve("results").resolve(runFolder).resolve("A/A__RealFailureApi_15-05-2026_1111AM_results.csv"),
                "Test Execution Date,Execution Time,API Name,Module Name,Test Scenario Number,JSON Payload,Results JSON File Name,Test Result,Expected Response Code,Actual Response Code,API Inputs File,API Method,API Path & Value,Reason For Failure,Owner\n"
                        + "\"15-05-2026\",\"1111AM\",\"RealFailureApi\",\"Module\",\"1\",\"payload.json\",\"actual.json\",\"PASS\",\"200\",\"200\",\"apiInputs/RealFailureApi.csv\",\"GET\",\"/api/test\",\"\",\"Owner\"\n");

        ExecutionReport report = new ExecutionReport();
        report.manifest = "APIsToBeValidated_A.csv";
        report.stepsExecuted = 1;
        report.stepsFailed = 1;
        report.apiScenariosExecuted = 1;
        report.failures.add(row("RealFailureApi", "Module", "Owner"));

        ExecutionReporter.write(
                runnerRoot.resolve("results").resolve(runFolder).resolve("execute-summary.json"),
                List.of(report),
                repoRoot,
                runFolder
        );

        String overall = Files.readString(runnerRoot.resolve("results").resolve(runFolder).resolve("overall_results.csv"));
        assertTrue(overall.contains("RealFailureApi,Module,1,0,1,Owner"), overall);
        assertTrue(overall.contains("Total,1,0,1"), overall);
    }

    @Test
    void assertionResultsCsvSerializesFailedAssertionRow() throws Exception {
        Path repoRoot = tempDir.resolve("repo2");
        Path runnerRoot = repoRoot.resolve("java-regression-runner");
        String runFolder = "15-05-2026_1200PM";
        Files.createDirectories(runnerRoot.resolve("results").resolve(runFolder).resolve("A"));

        Files.writeString(runnerRoot.resolve("results").resolve(runFolder).resolve("A/A__FailedAssertApi_15-05-2026_1200PM_results.csv"),
                "Test Execution Date,Execution Time,API Name,Module Name,Test Scenario Number,JSON Payload,Results JSON File Name,Test Result,Expected Response Code,Actual Response Code,API Inputs File,API Method,API Path & Value,Reason For Failure,Owner\n"
                        + "\"15-05-2026\",\"1200PM\",\"FailedAssertApi\",\"Module\",\"1\",\"payload.json\",\"actual.json\",\"FAIL\",\"200\",\"200\",\"apiInputs/FailedAssertApi.csv\",\"GET\",\"/api/test\",\"assertion mismatch\",\"Owner\"\n");

        ExecutionReport report = new ExecutionReport();
        report.manifest = "APIsToBeValidated_A.csv";
        report.stepsExecuted = 1;
        report.stepsFailed = 1;
        report.apiScenariosExecuted = 1;
        report.failures.add(rowWithFailedAssertion("FailedAssertApi", "Module", "Owner"));

        ExecutionReporter.write(
                runnerRoot.resolve("results").resolve(runFolder).resolve("execute-summary.json"),
                List.of(report),
                repoRoot,
                runFolder
        );

        CSVRecord assertion = firstAssertionRecord(Files.readString(runnerRoot.resolve("results").resolve(runFolder).resolve("assertion-results.csv")));
        org.junit.jupiter.api.Assertions.assertEquals("FailedAssertApi", assertion.get("api"));
        org.junit.jupiter.api.Assertions.assertEquals("ianp-mismatch", assertion.get("assertionName"));
        org.junit.jupiter.api.Assertions.assertEquals("FAIL", assertion.get("status"));
        org.junit.jupiter.api.Assertions.assertEquals("false", assertion.get("passed"));
        org.junit.jupiter.api.Assertions.assertEquals("strictDecimal", assertion.get("type"));
        org.junit.jupiter.api.Assertions.assertEquals("interestAccruedNotDueNotPaid", assertion.get("actualPath"));
        org.junit.jupiter.api.Assertions.assertEquals("bookedInterestUnpaid", assertion.get("expectedPath"));
        org.junit.jupiter.api.Assertions.assertEquals("14.99", assertion.get("resolvedActual"));
        org.junit.jupiter.api.Assertions.assertEquals("14.58", assertion.get("resolvedExpected"));
        org.junit.jupiter.api.Assertions.assertEquals("assertion_mismatch", assertion.get("failureKind"));
    }

    private static Map<String, Object> row(String api, String module, String owner) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("manifest", "APIsToBeValidated_A.csv");
        row.put("api", api);
        row.put("module", module);
        row.put("owner", owner);
        row.put("step", 1);
        row.put("stepApiName", api + "1");
        row.put("endpointTemplate", "/api/test");
        row.put("commonIgnore", Collections.singletonList("createdOn"));
        row.put("caseIgnore", Collections.singletonList("identifier"));
        row.put("actualEffectivelyEmpty", false);
        row.put("comparison", List.of(comparisonRow()));
        row.put("assertionResults", List.of(assertionRow()));
        row.put("assertionResultsArtifact", "jsonOutput/15-05-2026_1111AM/A/assertionResults__A__" + api + "1.json");
        return row;
    }

    private static Map<String, Object> rowWithFailedAssertion(String api, String module, String owner) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("manifest", "APIsToBeValidated_A.csv");
        row.put("api", api);
        row.put("module", module);
        row.put("owner", owner);
        row.put("step", 1);
        row.put("stepApiName", api + "1");
        row.put("endpointTemplate", "/api/test");
        row.put("commonIgnore", Collections.emptyList());
        row.put("caseIgnore", Collections.emptyList());
        row.put("actualEffectivelyEmpty", false);
        row.put("comparison", Collections.emptyList());
        row.put("assertionResults", List.of(failedAssertionRow()));
        row.put("assertionResultsArtifact", "jsonOutput/15-05-2026_1200PM/A/assertionResults__A__" + api + "1.json");
        return row;
    }

    private static Map<String, Object> failedAssertionRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("assertionName", "ianp-mismatch");
        row.put("status", "FAIL");
        row.put("passed", false);
        row.put("type", "strictDecimal");
        row.put("operator", "equals");
        row.put("actualPath", "interestAccruedNotDueNotPaid");
        row.put("expectedPath", "bookedInterestUnpaid");
        row.put("resolvedActual", "14.99");
        row.put("resolvedExpected", "14.58");
        row.put("failureKind", "assertion_mismatch");
        row.put("message", "Expected 14.58 but got 14.99");
        return row;
    }

    private static Map<String, Object> comparisonRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", "data.items[0].amount");
        row.put("kind", "value_mismatch");
        row.put("expected", "1");
        row.put("actual", "2");
        row.put("valueType", "number");
        row.put("parentArrayPath", "data.items");
        return row;
    }

    private static Map<String, Object> assertionRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("assertionName", "ianp-equals-booked-interest-unpaid");
        row.put("status", "PASS");
        row.put("passed", true);
        row.put("type", "strictDecimal");
        row.put("operator", "equals");
        row.put("actualPath", "interestAccruedNotDueNotPaid");
        row.put("expectedPath", "bookedInterestUnpaid");
        row.put("resolvedActual", "14.58");
        row.put("resolvedExpected", "14.58");
        row.put("failureKind", "");
        row.put("message", "Assertion passed");
        return row;
    }

    private static CSVRecord firstAssertionRecord(String csv) throws Exception {
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(new StringReader(csv))) {
            return parser.getRecords().get(0);
        }
    }
}
