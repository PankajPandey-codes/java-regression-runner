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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisReporterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void summaryIncludesRebaselineMetadataAndRequiresExplicitStatusCodeOptIn() throws Exception {
        ExecutionReport report = new ExecutionReport();
        report.manifest = "APIsToBeValidated_A.csv";
        report.stepsExecuted = 2;
        report.stepsFailed = 2;
        report.failures.add(failure(
                "PayloadOnlyApi",
                1,
                200,
                200,
                "{\"status\":\"old\"}",
                "{\"status\":\"new\"}",
                List.of(Map.of("path", "status", "kind", "value_mismatch", "expected", "old", "actual", "new"))
        ));
        report.failures.add(failure(
                "StatusOnlyApi",
                2,
                200,
                404,
                "{\"status\":\"ok\"}",
                "{\"status\":\"not_found\"}",
                Collections.emptyList()
        ));

        AnalysisReporter.AnalysisOutput output = AnalysisReporter.write(tempDir, "11-05-2026_1100AM", List.of(report));
        JsonNode root = MAPPER.readTree(output.summaryJson.toFile());
        Path wrapper = PathResolver.runnerRoot(tempDir).resolve("results/11-05-2026_1100AM/regression-report.html");
        assertTrue(Files.exists(wrapper));
        assertTrue(Files.readString(wrapper).contains("Analysis Summary"));

        JsonNode bodyCandidate = root.path("payloadDiffs").get(0).path("rebaseline");
        assertEquals("PayloadOnlyApiResponse.json", bodyCandidate.path("expectedJsonFile").asText());
        assertEquals("fileFromJson/expectedJSON/PayloadOnlyApiResponse.json", bodyCandidate.path("expectedJsonPath").asText());
        assertEquals("expected", bodyCandidate.path("expectedValueField").asText());
        assertEquals("apiName", bodyCandidate.path("scenarioKey").asText());
        assertEquals("PayloadOnlyApi1", bodyCandidate.path("scenarioName").asText());
        assertEquals("jsonOutput/11-05-2026_1100AM/A/jsonOutputA__PayloadOnlyApi1.json", bodyCandidate.path("jsonOutputArtifact").asText());
        assertTrue(bodyCandidate.path("canUpdateExpectedBody").asBoolean());
        assertFalse(bodyCandidate.path("canUpdateExpectedStatusCode").asBoolean());
        assertEquals("UPDATE_EXPECTED_BODY", bodyCandidate.path("defaultAction").asText());

        JsonNode statusCandidate = root.path("statusCodeMismatches").get(0).path("rebaseline");
        assertEquals("apiInputs/StatusOnlyApi.csv", statusCandidate.path("apiInputsPath").asText());
        assertFalse(statusCandidate.path("canUpdateExpectedBody").asBoolean());
        assertTrue(statusCandidate.path("canUpdateExpectedStatusCode").asBoolean());
        assertEquals("SKIP", statusCandidate.path("defaultAction").asText());
        assertTrue(statusCandidate.path("allowedActions").toString().contains("UPDATE_EXPECTED_STATUS_CODE"));
    }

    @Test
    void summaryGroupsKeyedArrayPaths() throws Exception {
        ExecutionReport report = new ExecutionReport();
        report.manifest = "APIsToBeValidated_A.csv";
        report.stepsExecuted = 1;
        report.stepsFailed = 1;
        report.failures.add(failure(
                "KeyedArrayApi",
                1,
                200,
                200,
                "{\"data\":[{\"id\":1,\"value\":\"old\"},{\"id\":2,\"value\":\"old\"}]}",
                "{\"data\":[{\"id\":1,\"value\":\"new\"},{\"id\":2,\"value\":\"new\"}]}",
                List.of(
                        Map.of("path", "data[id=1].value", "kind", "value_mismatch", "expected", "old", "actual", "new"),
                        Map.of("path", "data[id=2].value", "kind", "value_mismatch", "expected", "old", "actual", "new")
                )
        ));

        AnalysisReporter.AnalysisOutput output = AnalysisReporter.write(tempDir, "11-05-2026_1100AM", List.of(report));
        JsonNode root = MAPPER.readTree(output.summaryJson.toFile());
        JsonNode topPath = root.path("topDiffPaths").get(0);

        assertEquals("data[].value", topPath.path("path").asText());
        assertEquals(2, topPath.path("count").asInt());
        assertEquals("data[].value", root.path("payloadDiffs").get(0).path("topMismatchPaths").get(0).asText());
        assertEquals("PASS", root.path("payloadDiffs").get(0).path("assertionResults").get(0).path("status").asText());
    }

    @Test
    void summaryUsesPortableComparisonArtifactPaths() throws Exception {
        ExecutionReport report = new ExecutionReport();
        report.manifest = "APIsToBeValidated_A.csv";
        report.stepsExecuted = 1;
        report.stepsFailed = 1;
        Map<String, Object> failure = failure(
                "PayloadOnlyApi",
                1,
                200,
                200,
                "{\"status\":\"old\"}",
                "{\"status\":\"new\"}",
                List.of(Map.of("path", "status", "kind", "value_mismatch", "expected", "old", "actual", "new"))
        );
        failure.put(
                "comparisonArtifact",
                "/workspace/jsonOutput/11-05-2026_1100AM/A/jsonOutputapiActualExpectedFileNameA__PayloadOnlyApi1.txt"
        );
        report.failures.add(failure);

        AnalysisReporter.AnalysisOutput output = AnalysisReporter.write(tempDir, "11-05-2026_1100AM", List.of(report));
        JsonNode root = MAPPER.readTree(output.summaryJson.toFile());

        assertEquals(
                "jsonOutput/11-05-2026_1100AM/A/jsonOutputapiActualExpectedFileNameA__PayloadOnlyApi1.txt",
                root.path("payloadDiffs").get(0).path("rebaseline").path("comparisonArtifact").asText()
        );
    }

    private static Map<String, Object> failure(
            String api,
            int step,
            int expectedCode,
            int actualCode,
            String expected,
            String actual,
            List<Map<String, Object>> comparison
    ) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("manifest", "APIsToBeValidated_A.csv");
        failure.put("api", api);
        failure.put("module", "Module");
        failure.put("owner", "Owner");
        failure.put("stepApiName", api + step);
        failure.put("step", step);
        failure.put("method", "GET");
        failure.put("url", "/test");
        failure.put("expectedCode", expectedCode);
        failure.put("actualCode", actualCode);
        failure.put("payload", "{}");
        failure.put("expected", expected);
        failure.put("actual", actual);
        failure.put("comparison", comparison);
        failure.put("comparisonFailed", !comparison.isEmpty());
        failure.put("apiInputsFile", api + ".csv");
        failure.put("apiInputsPath", "apiInputs/" + api + ".csv");
        failure.put("payloadJsonFile", api + ".json");
        failure.put("payloadJsonPath", "fileFromJson/payloadJSON/" + api + ".json");
        failure.put("expectedJsonFile", api + "Response.json");
        failure.put("expectedJsonPath", "fileFromJson/expectedJSON/" + api + "Response.json");
        failure.put("expectedValueField", "expected");
        failure.put("scenarioKey", "apiName");
        failure.put("scenarioName", api + step);
        failure.put("jsonPayloadArtifact", "jsonPayload/11-05-2026_1100AM/A/testScenario_for_API__A__" + api + step + ".json");
        failure.put("jsonOutputArtifact", "jsonOutput/11-05-2026_1100AM/A/jsonOutputA__" + api + step + ".json");
        failure.put("comparisonArtifact", "jsonOutput/11-05-2026_1100AM/A/jsonOutputapiActualExpectedFileNameA__" + api + step + ".txt");
        failure.put("assertionResults", List.of(assertionResult()));
        return failure;
    }

    private static Map<String, Object> assertionResult() {
        Map<String, Object> assertion = new LinkedHashMap<>();
        assertion.put("assertionName", "ianp-equals-booked-interest-unpaid");
        assertion.put("status", "PASS");
        assertion.put("passed", true);
        assertion.put("operator", "equals");
        assertion.put("type", "strictDecimal");
        assertion.put("actualPath", "interestAccruedNotDueNotPaid");
        assertion.put("expectedPath", "bookedInterestUnpaid");
        assertion.put("resolvedActual", "14.58");
        assertion.put("resolvedExpected", "14.58");
        assertion.put("failureKind", "");
        assertion.put("message", "Assertion passed");
        return assertion;
    }
}
