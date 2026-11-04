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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RunFolderReportBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void build_resolvesManifestAndExpectedJsonFromRepoRootWhenStartedInRunnerRoot() throws Exception {
        Path repoRoot = tempDir.resolve("repo");
        Path runnerRoot = repoRoot.resolve("java-regression-runner");
        Files.createDirectories(repoRoot.resolve("apisToBeValidated"));
        Files.createDirectories(repoRoot.resolve("fileFromJson/expectedJSON"));
        Files.createDirectories(runnerRoot.resolve("results/06-05-2026_0758PM/A"));
        Files.createDirectories(runnerRoot.resolve("run-jsonOutput/06-05-2026_0758PM/A"));
        Files.createDirectories(runnerRoot.resolve("run-jsonPayload/06-05-2026_0758PM/A"));

        Files.writeString(repoRoot.resolve("apisToBeValidated/APIsToBeValidated_A.csv"),
                "#,apiName,apiMethod,apiPath,apiInputsFile,toBeValidated,Module,fileType,payloadJSON,expectedJSON,consentPerson,scenario,mysql,BusinessFunction\n"
                        + "1,FeeAmortLoLDisbursement,NA,NA,FeeAmortLoLDisbursement.csv,y,Fee Amortization,json,FeeAmortLoLDisbursement.json,FeeAmortLoLDisbursement.json,Sandeep,n,y,FeeAmortisation_LoL\n");
        Files.writeString(repoRoot.resolve("fileFromJson/expectedJSON/FeeAmortLoLDisbursement.json"),
                "[{\"expectedTableValue\":[{\"fee_id\":77,\"fee_group\":\"EXTF\"}]}]");

        Files.writeString(runnerRoot.resolve("run-jsonOutput/06-05-2026_0758PM/A/jsonOutputA__FeeAmortLoLDisbursement1.json"), "[ ]");
        Files.writeString(runnerRoot.resolve("run-jsonPayload/06-05-2026_0758PM/A/mysqlQuery__A__FeeAmortLoLDisbursement1.json"), "select 1");
        Files.writeString(runnerRoot.resolve("results/06-05-2026_0758PM/A/A__FeeAmortLoLDisbursement_06-05-2026_0758PM_results.csv"),
                "Test Execution Date,Execution Time,API Name,Module Name,Test Scenario Number,JSON Payload,Results JSON File Name,Test Result,Expected Response Code,Actual Response Code,API Inputs File,API Method,API Path & Value,Reason For Failure,Owner\n"
                        + "\"06-05-2026\",\"0758PM\",\"FeeAmortLoLDisbursement\",\"Fee Amortization\",\"1\",\"jsonPayload/06-05-2026_0758PM/A/mysqlQuery__A__FeeAmortLoLDisbursement1.json\",\"jsonOutput/06-05-2026_0758PM/A/jsonOutputA__FeeAmortLoLDisbursement1.json\",\"FAIL\",\"200\",\"200\",\"apiInputs/FeeAmortLoLDisbursement.csv\",\"Select Query\",\"mysqlQuery\",\"Expected result not match with actual result: /workspace/jsonOutput/06-05-2026_0758PM/A/jsonOutputapiActualExpectedFileNameA__FeeAmortLoLDisbursement1.txt\",\"Sandeep\"\n");

        List<ExecutionReport> reports = RunFolderReportBuilder.build(runnerRoot, "06-05-2026_0758PM", "https", "api.example.com");

        Map<String, Object> failure = reports.get(0).failures.get(0);
        assertEquals("[{\"fee_id\":77,\"fee_group\":\"EXTF\"}]", failure.get("expected"));
        assertEquals("[ ]", failure.get("actual"));
        assertEquals(Boolean.TRUE, failure.get("comparisonFailed"));
        assertEquals("FeeAmortLoLDisbursement.csv", failure.get("apiInputsFile"));
        assertEquals("apiInputs/FeeAmortLoLDisbursement.csv", failure.get("apiInputsPath"));
        assertEquals("FeeAmortLoLDisbursement.json", failure.get("payloadJsonFile"));
        assertEquals("fileFromJson/payloadJSON/FeeAmortLoLDisbursement.json", failure.get("payloadJsonPath"));
        assertEquals("FeeAmortLoLDisbursement.json", failure.get("expectedJsonFile"));
        assertEquals("fileFromJson/expectedJSON/FeeAmortLoLDisbursement.json", failure.get("expectedJsonPath"));
        assertEquals("expectedTableValue", failure.get("expectedValueField"));
        assertEquals("apiName", failure.get("scenarioKey"));
        assertEquals("FeeAmortLoLDisbursement1", failure.get("scenarioName"));
        assertEquals("jsonPayload/06-05-2026_0758PM/A/mysqlQuery__A__FeeAmortLoLDisbursement1.json", failure.get("jsonPayloadArtifact"));
        assertEquals("jsonOutput/06-05-2026_0758PM/A/jsonOutputA__FeeAmortLoLDisbursement1.json", failure.get("jsonOutputArtifact"));
        assertEquals("/workspace/jsonOutput/06-05-2026_0758PM/A/jsonOutputapiActualExpectedFileNameA__FeeAmortLoLDisbursement1.txt", failure.get("comparisonArtifact"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> comparison = (List<Map<String, Object>>) failure.get("comparison");
        assertFalse(comparison.isEmpty());
        assertEquals("[0]", comparison.get(0).get("path"));
    }

    @Test
    void build_usesServiceBaseUrlOverridesWhenReconstructingUrls() throws Exception {
        Path repoRoot = tempDir.resolve("repo-service-routing");
        Path runnerRoot = repoRoot.resolve("java-regression-runner");
        Files.createDirectories(repoRoot.resolve("apisToBeValidated"));
        Files.createDirectories(repoRoot.resolve("fileFromJson/expectedJSON"));
        Files.createDirectories(runnerRoot.resolve("results/06-05-2026_0758PM/A"));
        Files.createDirectories(runnerRoot.resolve("run-jsonOutput/06-05-2026_0758PM/A"));
        Files.createDirectories(runnerRoot.resolve("run-jsonPayload/06-05-2026_0758PM/A"));

        Files.writeString(repoRoot.resolve("apisToBeValidated/APIsToBeValidated_A.csv"),
                "#,apiName,apiMethod,apiPath,apiInputsFile,toBeValidated,Module,fileType,payloadJSON,expectedJSON,consentPerson,scenario,mysql,BusinessFunction\n"
                        + "1,SettlementApi,POST,/api/portfolio/v1/payments/settlements,Settlement.csv,y,Payments,json,Settlement.json,Settlement.json,Owner,n,n,Payments\n");
        Files.writeString(repoRoot.resolve("fileFromJson/expectedJSON/Settlement.json"),
                "[{\"expected\":{\"status\":\"ok\"}}]");

        Files.writeString(runnerRoot.resolve("run-jsonOutput/06-05-2026_0758PM/A/jsonOutputA__SettlementApi1.json"), "{\"status\":\"failed\"}");
        Files.writeString(runnerRoot.resolve("run-jsonPayload/06-05-2026_0758PM/A/testScenario_for_API__A__SettlementApi1.json"), "{\"apiPath\":\"/api/portfolio/v1/payments/settlements\"}");
        Files.writeString(runnerRoot.resolve("results/06-05-2026_0758PM/A/A__SettlementApi_06-05-2026_0758PM_results.csv"),
                "Test Execution Date,Execution Time,API Name,Module Name,Test Scenario Number,JSON Payload,Results JSON File Name,Test Result,Expected Response Code,Actual Response Code,API Inputs File,API Method,API Path & Value,Reason For Failure,Owner\n"
                        + "\"06-05-2026\",\"0758PM\",\"SettlementApi\",\"Payments\",\"1\",\"jsonPayload/06-05-2026_0758PM/A/testScenario_for_API__A__SettlementApi1.json\",\"jsonOutput/06-05-2026_0758PM/A/jsonOutputA__SettlementApi1.json\",\"FAIL\",\"200\",\"500\",\"apiInputs/Settlement.csv\",\"POST\",\"/api/portfolio/v1/payments/settlements\",\"status mismatch\",\"Owner\"\n");

        List<ExecutionReport> reports = RunFolderReportBuilder.build(
                runnerRoot,
                "06-05-2026_0758PM",
                "https",
                "api.example.com",
                Map.of("portfolio", "http://localhost:5200")
        );

        Map<String, Object> failure = reports.get(0).failures.get(0);
        assertEquals("http://localhost:5200/api/portfolio/v1/payments/settlements", failure.get("url"));
        assertEquals("Settlement.csv", failure.get("apiInputsFile"));
        assertEquals("apiInputs/Settlement.csv", failure.get("apiInputsPath"));
        assertEquals("Settlement.json", failure.get("expectedJsonFile"));
        assertEquals("fileFromJson/expectedJSON/Settlement.json", failure.get("expectedJsonPath"));
        assertEquals("expected", failure.get("expectedValueField"));
        assertEquals("apiName", failure.get("scenarioKey"));
        assertEquals("SettlementApi1", failure.get("scenarioName"));
        assertEquals("jsonOutput/06-05-2026_0758PM/A/jsonOutputA__SettlementApi1.json", failure.get("jsonOutputArtifact"));
        assertEquals("", failure.get("comparisonArtifact"));
    }

    @Test
    void build_keepsRuntimeAssertionFailuresFailedWhenBodyComparisonIsEmpty() throws Exception {
        Path repoRoot = tempDir.resolve("repo-runtime-assertion");
        Path runnerRoot = repoRoot.resolve("java-regression-runner");
        Path runDir = runnerRoot.resolve("results/06-05-2026_0758PM/A");
        Path jsonOutputDir = runnerRoot.resolve("jsonOutput/06-05-2026_0758PM/A");
        Path jsonPayloadDir = runnerRoot.resolve("jsonPayload/06-05-2026_0758PM/A");
        Files.createDirectories(repoRoot.resolve("apisToBeValidated"));
        Files.createDirectories(repoRoot.resolve("fileFromJson/expectedJSON"));
        Files.createDirectories(runDir);
        Files.createDirectories(jsonOutputDir);
        Files.createDirectories(jsonPayloadDir);

        Files.writeString(repoRoot.resolve("apisToBeValidated/APIsToBeValidated_A.csv"),
                "#,apiName,apiMethod,apiPath,apiInputsFile,toBeValidated,Module,fileType,payloadJSON,expectedJSON,consentPerson,scenario,mysql,BusinessFunction\n"
                        + "1,ResponseChaining_IANP,NA,NA,ResponseChaining_IANP.csv,y,Portfolio,json,ResponseChaining_IANP_Payload.json,ResponseChaining_IANP_Expected.json,Regression,y,n,NA\n");
        Files.writeString(repoRoot.resolve("fileFromJson/expectedJSON/ResponseChaining_IANP_Expected.json"),
                "[{\"apiName\":\"GetLoan3706AfterAccrual\",\"expected\":{},\"assertions\":[{\"name\":\"ianp-equals-booked-interest-unpaid-loan-3706\",\"actualPath\":\"interestAccruedNotDueNotPaid\",\"expectedPath\":\"bookedInterestUnpaid\",\"type\":\"strictDecimal\"}]}]");

        Files.writeString(jsonPayloadDir.resolve("testScenario_for_API__A__ResponseChaining_IANP1.json"), "[]");
        Files.writeString(jsonOutputDir.resolve("jsonOutputA__ResponseChaining_IANP1.json"),
                "{\"bookedInterestUnpaid\":2152.78,\"interestAccruedNotDueNotPaid\":188263.89}");
        Path comparisonArtifact = jsonOutputDir.resolve("jsonOutputapiActualExpectedFileNameA__ResponseChaining_IANP1.txt");
        Files.writeString(comparisonArtifact,
                "API: ResponseChaining_IANP\n"
                        + "Step: 1\n"
                        + "Mismatches: 0\n\n"
                        + "Expected (filtered):\n{}\n\n"
                        + "Actual (filtered):\n{}\n\n"
                        + "All Mismatches:\n"
                        + "- kind=assertion_mismatch path=assertions.ianp-equals-booked-interest-unpaid-loan-3706 expected=2152.78 actual=188263.89 assertion=ianp-equals-booked-interest-unpaid-loan-3706 type=strictDecimal\n");
        Files.writeString(jsonOutputDir.resolve("assertionResults__A__ResponseChaining_IANP1.json"),
                "[{\"assertionName\":\"ianp-equals-booked-interest-unpaid-loan-3706\",\"status\":\"FAIL\",\"passed\":false,\"operator\":\"equals\",\"type\":\"strictDecimal\",\"actualSource\":\"actualPath\",\"expectedSource\":\"expectedPath\",\"actualPath\":\"interestAccruedNotDueNotPaid\",\"expectedPath\":\"bookedInterestUnpaid\",\"resolvedActual\":\"188263.89\",\"resolvedExpected\":\"2152.78\",\"failureKind\":\"assertion_mismatch\",\"message\":\"Expected 2152.78 but actual was 188263.89\"}]");
        Files.writeString(runDir.resolve("A__ResponseChaining_IANP_06-05-2026_0758PM_results.csv"),
                "Test Execution Date,Execution Time,API Name,Module Name,Test Scenario Number,JSON Payload,Results JSON File Name,Test Result,Expected Response Code,Actual Response Code,API Inputs File,API Method,API Path & Value,Reason For Failure,Owner\n"
                        + "\"06-05-2026\",\"0758PM\",\"ResponseChaining_IANP\",\"Portfolio\",\"1\",\"jsonPayload/06-05-2026_0758PM/A/testScenario_for_API__A__ResponseChaining_IANP1.json\",\"jsonOutput/06-05-2026_0758PM/A/jsonOutputA__ResponseChaining_IANP1.json\",\"FAIL\",\"200\",\"200\",\"apiInputs/ResponseChaining_IANP.csv\",\"GET\",\"/api/portfolio/v1/loanaccount/3706\",\"Expected result not match with actual result: "
                        + comparisonArtifact
                        + "\",\"Regression\"\n");

        List<ExecutionReport> reports = RunFolderReportBuilder.build(runnerRoot, "06-05-2026_0758PM", "https", "api.example.com");

        assertEquals(1, reports.get(0).stepsExecuted);
        assertEquals(0, reports.get(0).stepsPassed);
        assertEquals(1, reports.get(0).stepsFailed);
        assertEquals(0, reports.get(0).passes.size());
        assertEquals(1, reports.get(0).failures.size());
        Map<String, Object> failure = reports.get(0).failures.get(0);
        assertEquals(Boolean.TRUE, failure.get("comparisonFailed"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> comparison = (List<Map<String, Object>>) failure.get("comparison");
        assertEquals(1, comparison.size());
        assertEquals("assertion_mismatch", comparison.get(0).get("kind"));
        assertEquals("assertions.ianp-equals-booked-interest-unpaid-loan-3706", comparison.get(0).get("path"));
        assertEquals("2152.78", comparison.get(0).get("expected"));
        assertEquals("188263.89", comparison.get(0).get("actual"));
        assertEquals("strictDecimal", comparison.get(0).get("type"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assertionResults = (List<Map<String, Object>>) failure.get("assertionResults");
        assertEquals(1, assertionResults.size());
        assertEquals("FAIL", assertionResults.get(0).get("status"));
        assertEquals("188263.89", assertionResults.get(0).get("resolvedActual"));
        assertEquals("2152.78", assertionResults.get(0).get("resolvedExpected"));
        assertEquals("jsonOutput/06-05-2026_0758PM/A/assertionResults__A__ResponseChaining_IANP1.json", failure.get("assertionResultsArtifact"));
    }

    @Test
    void build_loadsAssertionArtifactFromRecordedCsvPathAndMergesFailedAssertions() throws Exception {
        Path repoRoot = tempDir.resolve("repo-runtime-assertion-mixed");
        Path runnerRoot = repoRoot.resolve("java-regression-runner");
        Path runDir = runnerRoot.resolve("results/06-05-2026_0758PM/A");
        Path jsonOutputDir = runnerRoot.resolve("jsonOutput/06-05-2026_0758PM/A");
        Path jsonPayloadDir = runnerRoot.resolve("jsonPayload/06-05-2026_0758PM/A");
        Files.createDirectories(repoRoot.resolve("apisToBeValidated"));
        Files.createDirectories(repoRoot.resolve("fileFromJson/expectedJSON"));
        Files.createDirectories(runDir);
        Files.createDirectories(jsonOutputDir);
        Files.createDirectories(jsonPayloadDir);

        Files.writeString(repoRoot.resolve("apisToBeValidated/APIsToBeValidated_A.csv"),
                "#,apiName,apiMethod,apiPath,apiInputsFile,toBeValidated,Module,fileType,payloadJSON,expectedJSON,consentPerson,scenario,mysql,BusinessFunction\n"
                        + "1,MixedAssertionApi,NA,NA,MixedAssertionApi.csv,y,Portfolio,json,MixedAssertionApi_Payload.json,MixedAssertionApi_Expected.json,Regression,y,n,NA\n");
        Files.writeString(repoRoot.resolve("fileFromJson/expectedJSON/MixedAssertionApi_Expected.json"),
                "[{\"apiName\":\"MixedAssertionApiStep\",\"expected\":{}}]");

        Files.writeString(jsonPayloadDir.resolve("testScenario_for_API__A__MixedAssertionApi1.json"), "[]");
        Files.writeString(jsonOutputDir.resolve("jsonOutputA__MixedAssertionApi1.json"),
                "{\"bookedInterestUnpaid\":2152.78,\"interestAccruedNotDueNotPaid\":188263.89}");
        Files.writeString(jsonOutputDir.resolve("assertionResults__A__MixedAssertionApi1.json"),
                "[{\"assertionName\":\"ianp-equals-booked-interest-unpaid\",\"status\":\"FAIL\",\"passed\":false,\"operator\":\"equals\",\"type\":\"strictDecimal\",\"actualPath\":\"interestAccruedNotDueNotPaid\",\"expectedPath\":\"bookedInterestUnpaid\",\"resolvedActual\":\"188263.89\",\"resolvedExpected\":\"2152.78\",\"failureKind\":\"assertion_mismatch\",\"message\":\"Expected 2152.78 but actual was 188263.89\"}]");
        Files.writeString(runDir.resolve("A__MixedAssertionApi_06-05-2026_0758PM_results.csv"),
                "Test Execution Date,Execution Time,API Name,Module Name,Test Scenario Number,JSON Payload,Results JSON File Name,Test Result,Expected Response Code,Actual Response Code,API Inputs File,API Method,API Path & Value,Reason For Failure,Owner,Assertion Results Artifact\n"
                        + "\"06-05-2026\",\"0758PM\",\"MixedAssertionApi\",\"Portfolio\",\"99\",\"jsonPayload/06-05-2026_0758PM/A/testScenario_for_API__A__MixedAssertionApi1.json\",\"jsonOutput/06-05-2026_0758PM/A/jsonOutputA__MixedAssertionApi1.json\",\"FAIL\",\"200\",\"500\",\"apiInputs/MixedAssertionApi.csv\",\"GET\",\"/api/portfolio/v1/loanaccount/3706\",\"Actual response code received is 500\",\"Regression\",\"jsonOutput/06-05-2026_0758PM/A/assertionResults__A__MixedAssertionApi1.json\"\n");

        List<ExecutionReport> reports = RunFolderReportBuilder.build(runnerRoot, "06-05-2026_0758PM", "https", "api.example.com");

        Map<String, Object> failure = reports.get(0).failures.get(0);
        assertEquals(99, failure.get("step"));
        assertEquals(Boolean.TRUE, failure.get("comparisonFailed"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> comparison = (List<Map<String, Object>>) failure.get("comparison");
        assertEquals(1, comparison.size());
        assertEquals("assertions.ianp-equals-booked-interest-unpaid", comparison.get(0).get("path"));
        assertEquals("2152.78", comparison.get(0).get("expected"));
        assertEquals("188263.89", comparison.get(0).get("actual"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assertionResults = (List<Map<String, Object>>) failure.get("assertionResults");
        assertEquals(1, assertionResults.size());
        assertEquals("jsonOutput/06-05-2026_0758PM/A/assertionResults__A__MixedAssertionApi1.json", failure.get("assertionResultsArtifact"));
    }
}
