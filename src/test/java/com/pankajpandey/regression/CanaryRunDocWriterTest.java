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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanaryRunDocWriterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void writesValidMinimalFileFromSyntheticFailure() throws Exception {
        ExecutionReport report = reportWithFailure(Collections.emptyList(), 200, 500, false);

        JsonNode doc = writeAndRead(config("execute", "def4567"), Collections.singletonList(report), "run-one");

        assertEquals("1.0", doc.get("schemaVersion").asText());
        assertEquals("run-one", doc.get("run").get("run_id").asText());
        assertEquals("examplecorp/sample-portfolio-service", doc.get("targets").get(0).get("repo").asText());
        assertEquals("abc1234", doc.get("targets").get(0).get("commit_sha").asText());
        assertEquals("def4567", doc.get("targets").get(0).get("previous_green_sha").asText());
        assertEquals(1, doc.get("totals").get("executed").asInt());
        assertEquals(1, doc.get("totals").get("failed").asInt());
        assertEquals(1, doc.get("totals").get("by_type").get("STATUS_CODE").asInt());
        assertEquals("createdOn", doc.get("ignore_set").get(0).asText());

        JsonNode failure = doc.get("failures").get(0);
        assertEquals(16, failure.get("failure_id").asText().length());
        assertEquals("LoanRepaymentAndVerify", failure.get("identity").get("scenario_name").asText());
        assertEquals("/api/portfolio/v1/loans/{loanId}/repayments", failure.get("request").get("endpoint_template").asText());
        assertEquals(200, failure.get("expected_code").asInt());
        assertEquals(500, failure.get("actual_code").asInt());
        assertEquals("identifier", failure.get("case_ignore").get(0).asText());

        JsonNode body = doc.get("bodies").get(failure.get("failure_id").asText());
        assertNotNull(body);
        assertEquals("loan-1", body.get("request_payload").get("loanId").asText());
        assertEquals("OK", body.get("expected").get("status").asText());
        assertEquals("ERROR", body.get("actual").get("status").asText());
    }

    @Test
    void failureIdIsStableAcrossRunFolders() throws Exception {
        ExecutionReport report = reportWithFailure(Collections.emptyList(), 200, 500, false);

        JsonNode first = writeAndRead(config("execute", "def4567"), Collections.singletonList(report), "run-one");
        JsonNode second = writeAndRead(config("execute", "def4567"), Collections.singletonList(report), "run-two");

        assertEquals(
                first.get("failures").get(0).get("failure_id").asText(),
                second.get("failures").get(0).get("failure_id").asText()
        );
    }

    @Test
    void mapsDiffEntriesAndCountsByKind() throws Exception {
        List<Map<String, Object>> comparison = Arrays.asList(
                mismatch("data.fees[0].amount", "extra_field", "null", "12.30", "number", "data.fees"),
                mismatch("data.status", "type_mismatch", "{}", "[]", "object", "")
        );
        ExecutionReport report = reportWithFailure(comparison, 200, 200, false);

        JsonNode doc = writeAndRead(config("execute", "def4567"), Collections.singletonList(report), "run-one");
        JsonNode diff = doc.get("failures").get(0).get("diff");

        assertTrue(diff.get("comparison_failed").asBoolean());
        assertEquals(1, diff.get("counts_by_kind").get("unexpected_field").asInt());
        assertEquals(1, diff.get("counts_by_kind").get("type_mismatch").asInt());
        assertEquals("unexpected_field", diff.get("entries").get(0).get("kind").asText());
        assertEquals("number", diff.get("entries").get(0).get("value_type").asText());
        assertEquals("data.fees", diff.get("entries").get(0).get("parent_array_path").asText());
        assertTrue(diff.get("entries").get(0).get("expected").isNull());
        assertEquals("object", diff.get("entries").get(1).get("value_type").asText());
    }

    @Test
    void preservesNonHttpMethodsFromRegressionRows() throws Exception {
        ExecutionReport report = reportWithFailure(Collections.emptyList(), 200, 500, false);
        report.failures.get(0).put("method", "Select Query");

        JsonNode doc = writeAndRead(config("execute", "def4567"), Collections.singletonList(report), "run-one");

        assertEquals("Select Query", doc.get("failures").get(0).get("request").get("method").asText());
    }

    @Test
    void recordsActualEffectivelyEmptyEvenWithoutPayloadDiff() throws Exception {
        ExecutionReport report = reportWithFailure(Collections.emptyList(), 200, 500, true);

        JsonNode doc = writeAndRead(config("execute", "def4567"), Collections.singletonList(report), "run-one");
        JsonNode diff = doc.get("failures").get(0).get("diff");

        assertFalse(diff.get("comparison_failed").asBoolean());
        assertTrue(diff.get("actually_effectively_empty").asBoolean());
    }

    @Test
    void truncatesDiffEntriesButCountsAllRows() throws Exception {
        List<Map<String, Object>> comparison = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            comparison.add(mismatch("data.items[" + i + "].amount", "value_mismatch", "1", "2", "number", "data.items"));
        }
        ExecutionReport report = reportWithFailure(comparison, 200, 200, false);

        JsonNode doc = writeAndRead(config("execute", "def4567"), Collections.singletonList(report), "run-one");
        JsonNode diff = doc.get("failures").get(0).get("diff");

        assertTrue(diff.get("truncated").asBoolean());
        assertEquals(201, diff.get("counts_by_kind").get("value_mismatch").asInt());
        assertEquals(200, diff.get("entries").size());
    }

    @Test
    void recordsInfrastructureFailuresAndInfraTotals() throws Exception {
        ExecutionReport report = new ExecutionReport();
        report.manifest = "APIsToBeValidated_B.csv";
        report.infrastructureFailure = true;
        report.infrastructureError = "ConnectException: service unavailable";

        JsonNode doc = writeAndRead(config("execute", "def4567"), Collections.singletonList(report), "run-one");

        assertEquals(1, doc.get("infrastructure_failures").size());
        assertEquals("APIsToBeValidated_B.csv", doc.get("infrastructure_failures").get(0).get("manifest").asText());
        assertEquals(1, doc.get("totals").get("by_type").get("INFRA").asInt());
    }

    @Test
    void writesWhenPreviousGreenShaIsMissing() throws Exception {
        Config cfg = config("execute", "");
        JsonNode doc = writeAndRead(cfg, Collections.singletonList(reportWithFailure(Collections.emptyList(), 200, 500, false)), "run-one");

        assertEquals("examplecorp/sample-portfolio-service", doc.get("targets").get(0).get("repo").asText());
        assertEquals("abc1234", doc.get("targets").get(0).get("commit_sha").asText());
        assertEquals("", doc.get("targets").get(0).get("previous_green_sha").asText());
    }

    @Test
    void skipsWhenRequiredTargetMetadataIsMissing() throws Exception {
        Config cfg = configWithoutTargetMetadata("execute");
        Path runDir = tempDir.resolve("results").resolve("run-one");

        Path output = CanaryRunDocWriter.write(cfg, Collections.singletonList(reportWithFailure(Collections.emptyList(), 200, 500, false)), runDir, "run-one");

        assertNull(output);
        assertFalse(Files.exists(runDir.resolve(CanaryRunDocWriter.FILE_NAME)));
    }

    @Test
    void dryRunModeDoesNotWriteCanaryRunDoc() throws Exception {
        Config cfg = config("dry-run", "def4567");
        Path runDir = tempDir.resolve("results").resolve("run-one");

        Path output = CanaryRunDocWriter.write(cfg, Collections.singletonList(reportWithFailure(Collections.emptyList(), 200, 500, false)), runDir, "run-one");

        assertNull(output);
        assertFalse(Files.exists(runDir.resolve(CanaryRunDocWriter.FILE_NAME)));
    }

    private JsonNode writeAndRead(Config cfg, List<ExecutionReport> reports, String runId) throws Exception {
        Path output = CanaryRunDocWriter.write(cfg, reports, tempDir.resolve("results").resolve(runId), runId);
        assertNotNull(output);
        assertTrue(Files.exists(output));
        return MAPPER.readTree(output.toFile());
    }

    private Config config(String mode, String previousGreenSha) {
        return Config.load(new String[] {
                "--projectRoot=" + tempDir,
                "--mode=" + mode,
                "--repo=examplecorp/sample-portfolio-service",
                "--commitSha=abc1234",
                "--previousGreenSha=" + previousGreenSha
        });
    }

    private Config configWithoutTargetMetadata(String mode) {
        return Config.load(new String[] {
                "--projectRoot=" + tempDir,
                "--mode=" + mode
        });
    }

    private static ExecutionReport reportWithFailure(
            List<Map<String, Object>> comparison,
            int expectedCode,
            int actualCode,
            boolean actualEffectivelyEmpty
    ) {
        ExecutionReport report = new ExecutionReport();
        report.manifest = "APIsToBeValidated_A.csv";
        report.stepsExecuted = 1;
        report.stepsFailed = 1;

        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("manifest", report.manifest);
        failure.put("api", "PostLoanRepayment");
        failure.put("module", "Portfolio");
        failure.put("stepApiName", "PostLoanRepayment1");
        failure.put("owner", "portfolio-team");
        failure.put("step", 1);
        failure.put("method", "POST");
        failure.put("url", "http://dev-api/api/portfolio/v1/loans/loan-1/repayments");
        failure.put("endpointTemplate", "/api/portfolio/v1/loans/{loanId}/repayments");
        failure.put("expectedCode", expectedCode);
        failure.put("actualCode", actualCode);
        failure.put("payload", "{\"loanId\":\"loan-1\"}");
        failure.put("expected", "{\"status\":\"OK\"}");
        failure.put("actual", "{\"status\":\"ERROR\"}");
        failure.put("comparison", comparison);
        failure.put("comparisonFailed", !comparison.isEmpty());
        failure.put("commonIgnore", Collections.singletonList("createdOn"));
        failure.put("caseIgnore", Collections.singletonList("identifier"));
        failure.put("actualEffectivelyEmpty", actualEffectivelyEmpty);
        failure.put("scenarioName", "LoanRepaymentAndVerify");
        report.failures.add(failure);
        return report;
    }

    private static Map<String, Object> mismatch(
            String path,
            String kind,
            String expected,
            String actual,
            String valueType,
            String parentArrayPath
    ) {
        Map<String, Object> mismatch = new LinkedHashMap<>();
        mismatch.put("path", path);
        mismatch.put("kind", kind);
        mismatch.put("expected", expected);
        mismatch.put("actual", actual);
        mismatch.put("valueType", valueType);
        if (parentArrayPath != null && !parentArrayPath.isBlank()) {
            mismatch.put("parentArrayPath", parentArrayPath);
        }
        return mismatch;
    }
}
