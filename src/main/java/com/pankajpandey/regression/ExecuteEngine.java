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

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ExecuteEngine {
    private static final Logger log = LoggerFactory.getLogger(ExecuteEngine.class);
    private static final DateTimeFormatter RUN_FOLDER_TS = DateTimeFormatter.ofPattern("dd-MM-yyyy_hhmma");
    private static final DateTimeFormatter RUN_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter.ofPattern("hhmma");
    private static final Object CSV_LOCK = new Object();
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int MAX_HTTP_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 2_000L;
    private static final long FEATURE_TOGGLE_DELAY_MS = 15_000L;
    private static final long FUTURE_FEE_TOGGLE_DELAY_MS = 30_000L;
    // No HTTP request or DB query was ever issued for this step; distinct from a genuine
    // 500 raised by executeMysqlStep's catch block, and from any real HTTP status code.
    // Must stay negative: AnalysisReporter, RunFolderReportBuilder and CanaryRunDocWriter gate
    // status-code comparison on `code >= 0`, so a non-negative sentinel would be read as a real
    // status-code mismatch and let rebaseline write it back into apiInputs.
    private static final int PRE_REQUEST_FAILURE_CODE = -1;

    private final Config cfg;
    private final long tokenRefreshAfterNs;
    private final ObjectMapper mapper = new ObjectMapper();
    // Comparison and render mode use the standard mapper; only chaining snapshots preserve arbitrary precision.
    private final ObjectMapper referenceMapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final HttpClient http;
    private final DatabaseClient dbClient;
    private final String runStamp;
    private final String runDate;
    private final String runTime;
    private final Path resultsRunDir;
    private final Path payloadRunDir;
    private final Path outputRunDir;
    public ExecuteEngine(Config cfg) {
        this.cfg = cfg;
        this.tokenRefreshAfterNs = (long) cfg.tokenRefreshMinutes * 60 * 1_000_000_000L;
        HttpClient.Builder hb = HttpClient.newBuilder();
        if (cfg.connectTimeoutMs > 0) {
            hb.connectTimeout(Duration.ofMillis(cfg.connectTimeoutMs));
        }
        hb.version(HttpClient.Version.HTTP_1_1);
        this.http = hb.build();
        this.dbClient = new DatabaseClient(cfg, mapper, referenceMapper);
        LocalDateTime now = LocalDateTime.now();
        this.runStamp = RUN_FOLDER_TS.format(now).toUpperCase();
        this.runDate = RUN_DATE.format(now);
        this.runTime = RUN_TIME.format(now).toUpperCase();
        Path runnerRoot = PathResolver.runnerRoot(cfg.projectRoot);
        this.resultsRunDir = runnerRoot.resolve("results").resolve(runStamp);
        this.payloadRunDir = runnerRoot.resolve("jsonPayload").resolve(runStamp);
        this.outputRunDir = runnerRoot.resolve("jsonOutput").resolve(runStamp);
        initArtifacts();
    }

    public String runFolder() {
        return runStamp;
    }

    public ExecutionReport runForManifest(String manifestName) throws Exception {
        ExecutionReport report = new ExecutionReport();
        report.manifest = manifestName;

        List<ManifestRow> rows = ManifestLoader.load(cfg.projectRoot.resolve("apisToBeValidated").resolve(manifestName));
        if (cfg.targetedCiMode && cfg.businessCasesToTest.isEmpty()) {
            log.info("Skipping manifest={} because targeted CI mode is enabled and no impacted business function was provided", manifestName);
            return report;
        }
        List<ManifestRow> executableRows = new ArrayList<>();
        for (ManifestRow row : rows) {
            if (!shouldExecuteRow(row)) continue;
            if (!cfg.apis.isEmpty() && !cfg.apis.contains(normalizeApiName(row.apiName()))) continue;
            executableRows.add(row);
        }
        if (executableRows.isEmpty()) {
            log.info("Skipping manifest={} because no rows matched the current execution filters", manifestName);
            return report;
        }
        AuthClient authClient = new AuthClient(cfg);
        String token = authClient.fetchToken();
        long lastTokenFetchNs = System.nanoTime();

        for (ManifestRow row : executableRows) {
            if (cfg.limitApisPerManifest > 0 && report.apiScenariosExecuted >= cfg.limitApisPerManifest) break;

            if (!"json".equalsIgnoreCase(row.fileType())) {
                continue; // execute mode currently targets json flow first
            }

            if (System.nanoTime() - lastTokenFetchNs > tokenRefreshAfterNs) {
                log.info("Proactive token refresh before api={} manifest={}", row.apiName(), manifestName);
                token = authClient.fetchToken();
                lastTokenFetchNs = System.nanoTime();
            }

            Path inputsPath = cfg.projectRoot.resolve("apiInputs").resolve(row.apiInputsFile());
            Path payloadPath = cfg.projectRoot.resolve("fileFromJson/payloadJSON").resolve(row.payloadJson());
            Path expectedPath = cfg.projectRoot.resolve("fileFromJson/expectedJSON").resolve(row.expectedJson());

            String payloadFileRaw = readString(payloadPath);
            String expectedFileRaw = readString(expectedPath);
            String inputsFileRaw = cfg.responseChainingEnabled ? readString(inputsPath) : "";
            JsonNode payloadArray = mapper.readTree(payloadFileRaw);
            JsonNode expectedArray = safeReadJson(expectedFileRaw);
            if (!payloadArray.isArray()) continue;

            boolean scenarioUsesResponseChaining = cfg.responseChainingEnabled
                    && scenarioHasChainingMetadata(payloadFileRaw, expectedFileRaw, inputsFileRaw);
            ScenarioContext scenarioContext = new ScenarioContext();
            int stepIndex = 0;
            try (Reader reader = Files.newBufferedReader(inputsPath, StandardCharsets.UTF_8);
                 CSVParser parser = CSVFormat.DEFAULT.builder()
                         .setHeader()
                         .setSkipHeaderRecord(true)
                         .setAllowMissingColumnNames(true)
                         .setTrim(true)
                         .setIgnoreEmptyLines(true)
                         .build()
                         .parse(reader)) {

                for (CSVRecord rec : parser) {
                    if (cfg.limitStepsPerApi > 0 && stepIndex >= cfg.limitStepsPerApi) break;
                    if (stepIndex >= payloadArray.size()) break;

                    JsonNode step = payloadArray.get(stepIndex);
                    JsonNode expectedStep = getArrayNode(expectedArray, stepIndex);
                    JsonNode expectedBodyNode = expectedStep != null ? expectedStep.get("expected") : null;
                    JsonComparator.ComparisonConfig comparisonConfig = JsonComparator.configFromExpectedStep(expectedStep);

                    boolean mysqlCase = step.has("mysqlQuery");
                    String method = mysqlCase ? "Select Query" : text(step, "apiMethod", "GET");
                    String apiName = text(step, "apiName", row.apiName());
                    String apiPath = text(step, "apiPath", "");
                    String stepId = text(step, "stepId", "");
                    String testStresserFlg = text(step, "TestStresserFlg", "");
                    String accept = text(step, "accept", "application/json");
                    String responseType = text(step, "responseType", "text");
                    String manifestTag = manifestTag(manifestName);
                    String urlParameter = getCsv(rec, "urlParameter");
                    String expectedCodeRaw = getCsv(rec, "expectedResponseCode");
                    int expectedCode = parseIntOrDefault(expectedCodeRaw, 200);
                    String testScenarioNumber = getCsv(rec, "testScenarioNumber");
                    if (testScenarioNumber.isBlank()) {
                        testScenarioNumber = getCsv(rec, "\ufefftestScenarioNumber");
                    }
                    if (testScenarioNumber.isBlank()) {
                        testScenarioNumber = String.valueOf(stepIndex + 1);
                    }

                    int stepNo = stepIndex + 1;
                    ExecutionStepResult stepResult;
                    JsonNode payloadNode = step.get("payload");
                    String payloadText = payloadNode == null ? "" : payloadNode.toString();
                    String mysqlQueryText = mysqlCase ? text(step, "mysqlQuery", "") : "";
                    boolean hasChainingMetadata = hasChainingMetadata(stepId, apiPath, urlParameter, payloadText, mysqlQueryText, expectedStep);
                    boolean storesResponseSnapshot = cfg.responseChainingEnabled
                            && (mysqlCase
                                    ? !stepId.isBlank()
                                    : (scenarioUsesResponseChaining || hasChainingMetadata));
                    List<Map<String, Object>> preRequestErrors = validateResponseChainingPreconditions(
                            mysqlCase, hasChainingMetadata, stepId, urlParameter, expectedStep, scenarioContext
                    );
                    JsonNode resolvedPayloadNode = payloadNode;
                    String resolvedPayloadText = payloadText;
                    String resolvedApiPath = apiPath;
                    String resolvedUrlParameter = urlParameter;
                    TemplateResolver.SqlTemplate resolvedSqlTemplate = null;

                    if (preRequestErrors.isEmpty() && cfg.responseChainingEnabled && hasChainingMetadata) {
                        if (mysqlCase) {
                            Resolution<TemplateResolver.SqlTemplate> sqlResolution =
                                    TemplateResolver.resolveSqlTemplate(mysqlQueryText, scenarioContext);
                            preRequestErrors.addAll(sqlResolution.errors());
                            if (preRequestErrors.isEmpty()) {
                                resolvedSqlTemplate = sqlResolution.value();
                            }
                        } else {
                            Resolution<String> apiPathResolution = TemplateResolver.resolveString(apiPath, scenarioContext);
                            Resolution<String> urlParameterResolution = TemplateResolver.resolveString(urlParameter, scenarioContext);
                            Resolution<JsonNode> payloadResolution = payloadText.contains("{{")
                                    ? TemplateResolver.resolveNode(payloadNode, scenarioContext)
                                    : Resolution.ok(payloadNode);
                            preRequestErrors.addAll(apiPathResolution.errors());
                            preRequestErrors.addAll(urlParameterResolution.errors());
                            preRequestErrors.addAll(payloadResolution.errors());
                            if (preRequestErrors.isEmpty()) {
                                resolvedApiPath = apiPathResolution.value();
                                resolvedUrlParameter = urlParameterResolution.value();
                                resolvedPayloadNode = payloadResolution.value();
                                resolvedPayloadText = resolvedPayloadNode == null ? "" : resolvedPayloadNode.toString();
                            }
                        }
                    }

                    String finalPath = resolvedApiPath;
                    if (!resolvedUrlParameter.isBlank() && !"NA".equalsIgnoreCase(resolvedUrlParameter)) {
                        finalPath = finalPath.endsWith("/") ? finalPath + resolvedUrlParameter : finalPath + "/" + resolvedUrlParameter;
                    }

                    String url = "";
                    if (!preRequestErrors.isEmpty()) {
                        stepResult = failedPreRequestStep(
                                manifestTag,
                                row,
                                step,
                                stepNo,
                                expectedCode,
                                expectedBodyNode,
                                payloadText,
                                preRequestErrors
                        );
                    } else if (mysqlCase) {
                        stepResult = executeMysqlStep(manifestTag, row, step, expectedStep, stepNo, resolvedSqlTemplate, storesResponseSnapshot);
                    } else {
                        url = UrlResolver.buildApiUrl(cfg, finalPath);
                        HttpRequest request = buildHttpRequest(url, method, resolvedPayloadText, resolvedPayloadNode, token,
                                row.businessFunction(), testStresserFlg, row.apiName(), apiName, accept);

                        long startNs = System.nanoTime();
                        int actualCode;
                        String responseText = "";
                        Path responseFile;
                        if ("file".equalsIgnoreCase(responseType)) {
                            Path downloadPath = fileOutputArtifactPath(manifestTag, row.apiName(), stepNo);
                            HttpResponse<Path> resp = sendFileWithRetry(request, url, row.apiName(), stepIndex + 1, downloadPath);
                            if (isAuthFailure(resp.statusCode())) {
                                log.info("Got {} for api={} step={}, refreshing token and retrying",
                                        resp.statusCode(), row.apiName(), stepIndex + 1);
                                token = authClient.fetchToken();
                                lastTokenFetchNs = System.nanoTime();
                                request = buildHttpRequest(url, method, resolvedPayloadText, resolvedPayloadNode, token,
                                        row.businessFunction(), testStresserFlg, row.apiName(), apiName, accept);
                                resp = sendFileWithRetry(request, url, row.apiName(), stepIndex + 1, downloadPath);
                            }
                            actualCode = resp.statusCode();
                            responseFile = resp.body();
                        } else {
                            HttpResponse<String> resp = sendWithRetry(request, url, row.apiName(), stepIndex + 1);
                            if (isAuthFailure(resp.statusCode())) {
                                log.info("Got {} for api={} step={}, refreshing token and retrying",
                                        resp.statusCode(), row.apiName(), stepIndex + 1);
                                token = authClient.fetchToken();
                                lastTokenFetchNs = System.nanoTime();
                                request = buildHttpRequest(url, method, resolvedPayloadText, resolvedPayloadNode, token,
                                        row.businessFunction(), testStresserFlg, row.apiName(), apiName, accept);
                                resp = sendWithRetry(request, url, row.apiName(), stepIndex + 1);
                            }
                            actualCode = resp.statusCode();
                            responseText = resp.body();
                            responseFile = writeJsonOutputArtifact(manifestTag, row.apiName(), stepNo, responseText);
                        }
                        applyPostFeatureToggleDelay(resolvedApiPath, resolvedPayloadNode, actualCode, row.apiName(), stepNo);
                        long durationMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
                        stepResult = new ExecutionStepResult();
                        stepResult.expectedCode = expectedCode;
                        stepResult.actualCode = actualCode;
                        stepResult.payloadRaw = resolvedPayloadText;
                        stepResult.payloadFile = writePayloadArtifact(manifestTag, row.apiName(), stepNo, resolvedPayloadText);
                        writeMysqlPayloadArtifact(manifestTag, row.apiName(), stepNo, step);
                        stepResult.responseFile = responseFile;
                        stepResult.expectedNode = expectedBodyNode;
                        stepResult.actualNode = safeParseJson(responseText, row.apiName(), stepNo);
                        stepResult.referenceNode = storesResponseSnapshot
                                && actualCode == expectedCode
                                && stepResult.actualNode != null
                                ? safeParseReferenceJson(responseText, row.apiName(), stepNo)
                                : null;
                        stepResult.referenceable = actualCode == expectedCode
                                && stepResult.referenceNode != null
                                && !stepResult.referenceNode.isNull();
                        stepResult.actualRaw = responseText;
                        stepResult.mismatches = compareExpected(expectedBodyNode, stepResult.actualNode, comparisonConfig);
                        if (cfg.responseChainingEnabled && RuntimeAssertionEvaluator.hasAssertions(expectedStep)) {
                            RuntimeAssertionEvaluator.Evaluation evaluation = RuntimeAssertionEvaluator.evaluateWithTrace(
                                    expectedStep,
                                    stepResult.actualNode,
                                    scenarioContext
                            );
                            List<Map<String, Object>> merged = new ArrayList<>(stepResult.mismatches);
                            merged.addAll(evaluation.mismatches());
                            stepResult.mismatches = merged;
                            stepResult.assertionResults = evaluation.assertionResults();
                            stepResult.assertionResultsFile = writeAssertionResultsArtifact(
                                    manifestTag,
                                    row.apiName(),
                                    stepNo,
                                    stepResult.assertionResults
                            );
                        }
                        if (!stepResult.mismatches.isEmpty()) {
                            stepResult.comparisonFile = writeFailureComparisonArtifact(
                                    manifestTag, row.apiName(), stepNo, expectedBodyNode, stepResult.actualNode, responseText, comparisonConfig, stepResult.mismatches
                            );
                        }
                        stepResult.passed = actualCode == expectedCode && stepResult.mismatches.isEmpty();
                    }
                    if (storesResponseSnapshot) {
                        String contextStepId = hasStepIdValidationError(preRequestErrors) ? "" : stepId;
                        scenarioContext.store(
                                contextStepId,
                                stepNo,
                                stepResult.expectedCode,
                                stepResult.actualCode,
                                stepResult.passed,
                                stepResult.referenceable,
                                stepResult.referenceNode == null ? stepResult.actualNode : stepResult.referenceNode,
                                resolvedPayloadNode,
                                mysqlCase
                        );
                    }
                    report.stepsExecuted++;
                    String reasonForFailure = buildReasonForFailure(
                            stepResult.expectedCode,
                            stepResult.actualCode,
                            stepResult.mismatches,
                            stepResult.comparisonFile,
                            stepResult.actualRaw
                    );

                    appendResultRow(
                            row,
                            manifestName,
                            inputsPath,
                            testScenarioNumber,
                            method,
                            mysqlCase ? "mysqlQuery" : normalizePath(finalPath),
                            stepResult.expectedCode,
                            stepResult.actualCode,
                            stepResult.passed,
                            stepResult.payloadFile,
                            stepResult.responseFile,
                            reasonForFailure,
                            stepResult.assertionResultsFile
                    );

                    if (stepResult.passed) {
                        report.stepsPassed++;
                        Map<String, Object> pass = new HashMap<>();
                        pass.put("manifest", manifestName);
                        pass.put("api", row.apiName());
                        pass.put("module", row.moduleName());
                        pass.put("stepApiName", apiName);
                        pass.put("step", stepIndex + 1);
                        pass.put("method", method);
                        pass.put("url", mysqlCase ? "mysqlQuery" : url);
                        pass.put("expectedCode", stepResult.expectedCode);
                        pass.put("actualCode", stepResult.actualCode);
                        pass.put("payload", safeLarge(stepResult.payloadRaw));
                        pass.put("expected", safeLarge(stepResult.expectedNode == null ? "" : stepResult.expectedNode.toString()));
                        pass.put("actual", safeLarge(stepResult.actualRaw));
                        pass.put("owner", normalizeOwner(row.owner()));
                        pass.put("comparison", Collections.emptyList());
                        pass.put("comparisonFailed", false);
                        putAssertionMetadata(pass, stepResult);
                        putCanaryMetadata(pass, expectedStep, apiPath, stepResult);
                        putRebaselineMetadata(pass, row, expectedStep, stepNo, inputsPath, stepResult.payloadFile, stepResult.responseFile, stepResult.comparisonFile);
                        report.passes.add(pass);
                    } else {
                        report.stepsFailed++;
                        Map<String, Object> failure = new HashMap<>();
                        failure.put("manifest", manifestName);
                        failure.put("api", row.apiName());
                        failure.put("module", row.moduleName());
                        failure.put("stepApiName", apiName);
                        failure.put("step", stepIndex + 1);
                        failure.put("method", method);
                        failure.put("url", mysqlCase ? "mysqlQuery" : url);
                        failure.put("expectedCode", stepResult.expectedCode);
                        failure.put("actualCode", stepResult.actualCode);
                        failure.put("payload", safeLarge(stepResult.payloadRaw));
                        failure.put("expected", safeLarge(stepResult.expectedNode == null ? "" : stepResult.expectedNode.toString()));
                        failure.put("actual", safeLarge(stepResult.actualRaw));
                        failure.put("owner", normalizeOwner(row.owner()));
                        failure.put("comparison", stepResult.mismatches);
                        failure.put("comparisonFailed", !stepResult.mismatches.isEmpty());
                        putAssertionMetadata(failure, stepResult);
                        putCanaryMetadata(failure, expectedStep, apiPath, stepResult);
                        putRebaselineMetadata(failure, row, expectedStep, stepNo, inputsPath, stepResult.payloadFile, stepResult.responseFile, stepResult.comparisonFile);
                        report.failures.add(failure);
                    }
                    stepIndex++;
                }
            }

            report.apiScenariosExecuted++;
        }

        return report;
    }

    private boolean shouldExecuteRow(ManifestRow row) {
        if (cfg.targetedCiMode) {
            return row.matchesAnyBusinessFunction(cfg.businessCasesToTest);
        }
        return row.isEnabledStrict();
    }

    private void initArtifacts() {
        try {
            Files.createDirectories(resultsRunDir);
            Files.createDirectories(payloadRunDir);
            Files.createDirectories(outputRunDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize execute artifacts", e);
        }
    }

    private Path writePayloadArtifact(String manifestTag, String api, int step, String payload) {
        String fileName = "testScenario_for_API__" + manifestTag + "__" + ArtifactNames.sanitizeFlatName(api) + step + ".json";
        return writeArtifact(payloadRunDir.resolve(manifestTag).resolve(fileName), payload);
    }

    private Path writeMysqlPayloadArtifact(String manifestTag, String api, int step, String mysqlFlag, String payload) {
        String fileName = "mysqlQuery__" + manifestTag + "__" + ArtifactNames.sanitizeFlatName(api) + step + ".json";
        String content = (mysqlFlag != null && !mysqlFlag.isBlank() && !"n".equalsIgnoreCase(mysqlFlag))
                ? payload
                : "{}";
        return writeArtifact(payloadRunDir.resolve(manifestTag).resolve(fileName), content);
    }

    private Path writeMysqlPayloadArtifact(String manifestTag, String api, int stepNo, JsonNode stepNode) {
        return writeMysqlPayloadArtifact(manifestTag, api, stepNo, stepNode, null);
    }

    private Path writeMysqlPayloadArtifact(
            String manifestTag, String api, int stepNo, JsonNode stepNode, TemplateResolver.SqlTemplate resolvedSqlTemplate
    ) {
        String fileName = "mysqlQuery__" + manifestTag + "__" + ArtifactNames.sanitizeFlatName(api) + stepNo + ".json";
        String content;
        if (resolvedSqlTemplate != null) {
            ObjectNode root = mapper.createObjectNode();
            root.put("sql", resolvedSqlTemplate.sql());
            ArrayNode boundParameters = root.putArray("boundParameters");
            for (JsonNode param : resolvedSqlTemplate.params()) {
                boundParameters.add(param);
            }
            content = root.toString();
        } else {
            content = text(stepNode, "mysqlQuery", "");
        }
        return writeArtifact(payloadRunDir.resolve(manifestTag).resolve(fileName), content);
    }

    private Path writeJsonOutputArtifact(String manifestTag, String api, int step, String response) {
        String ext = looksLikeJson(response) ? ".json" : ".txt";
        String fileName = "jsonOutput" + manifestTag + "__" + ArtifactNames.sanitizeFlatName(api) + step + ext;
        return writeArtifact(outputRunDir.resolve(manifestTag).resolve(fileName), response);
    }

    private Path fileOutputArtifactPath(String manifestTag, String api, int step) throws IOException {
        Path manifestDir = outputRunDir.resolve(manifestTag);
        Files.createDirectories(manifestDir);
        String fileName = "jsonOutput" + manifestTag + "__" + ArtifactNames.sanitizeFlatName(api) + step + ".download";
        return manifestDir.resolve(fileName);
    }

    private Path writeJsonOutputArtifact(String manifestTag, String api, int step, JsonNode response) {
        String body = response == null ? "[]" : response.toPrettyString();
        String fileName = "jsonOutput" + manifestTag + "__" + ArtifactNames.sanitizeFlatName(api) + step + ".json";
        return writeArtifact(outputRunDir.resolve(manifestTag).resolve(fileName), body);
    }

    private Path writeAssertionResultsArtifact(String manifestTag, String api, int step, List<Map<String, Object>> assertionResults) {
        if (assertionResults == null || assertionResults.isEmpty()) {
            return null;
        }
        try {
            String fileName = ArtifactNames.assertionResultsFileName(manifestTag, api, step);
            String body = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(assertionResults);
            return writeArtifact(outputRunDir.resolve(manifestTag).resolve(fileName), body);
        } catch (Exception e) {
            log.warn("Failed serializing assertion results artifact api={} step={}", api, step, e);
            return null;
        }
    }

    private Path writeFailureComparisonArtifact(
            String manifestTag,
            String api,
            int step,
            JsonNode expectedBodyNode,
            JsonNode actualBodyNode,
            String actualBody,
            JsonComparator.ComparisonConfig comparisonConfig,
            List<Map<String, Object>> mismatches
    ) {
        String fileName = "jsonOutputapiActualExpectedFileName" + manifestTag + "__" + ArtifactNames.sanitizeFlatName(api) + step + ".txt";
        Set<String> ignoreFields = comparisonConfig == null ? Collections.<String>emptySet() : comparisonConfig.ignoreFields();
        JsonNode filteredExpected = JsonComparator.pruneIgnored(expectedBodyNode, ignoreFields);
        JsonNode filteredActual = JsonComparator.pruneIgnored(actualBodyNode, ignoreFields);
        JsonComparator.ComparisonConfig filteredConfig = comparisonConfig == null
                ? JsonComparator.emptyConfig()
                : comparisonConfig.withIgnoreFields(Collections.<String>emptySet());
        List<Map<String, Object>> filteredMismatches = compareExpected(filteredExpected, filteredActual, filteredConfig);
        StringBuilder sb = new StringBuilder();
        sb.append("API: ").append(api).append("\n");
        sb.append("Step: ").append(step).append("\n");
        sb.append("Mismatches: ").append(filteredMismatches.size()).append("\n\n");
        sb.append("Ignored Fields:\n").append(ignoreFields == null || ignoreFields.isEmpty() ? "[]" : ignoreFields.toString()).append("\n\n");
        sb.append("Expected (filtered):\n").append(filteredExpected == null ? "" : filteredExpected.toPrettyString()).append("\n\n");
        sb.append("Actual (filtered):\n");
        if (filteredActual != null) {
            sb.append(filteredActual.toPrettyString());
        } else {
            sb.append(actualBody == null ? "" : actualBody);
        }
        sb.append("\n\n");
        if (!filteredMismatches.isEmpty()) {
            sb.append("Body Comparison:\n");
            for (Map<String, Object> m : filteredMismatches) {
                sb.append("- path=").append(asStr(m.get("path")))
                        .append(" expected=").append(asStr(m.get("expected")))
                        .append(" actual=").append(asStr(m.get("actual")))
                        .append("\n");
            }
        }
        if (mismatches != null && !mismatches.isEmpty()) {
            sb.append(filteredMismatches.isEmpty() ? "" : "\n");
            sb.append("All Mismatches:\n");
            for (Map<String, Object> m : mismatches) {
                sb.append("- kind=").append(asStr(m.get("kind")))
                        .append(" path=").append(asStr(m.get("path")))
                        .append(" expected=").append(asStr(m.get("expected")))
                        .append(" actual=").append(asStr(m.get("actual")));
                if (m.containsKey("assertion")) {
                    sb.append(" assertion=").append(asStr(m.get("assertion")));
                }
                if (m.containsKey("type")) {
                    sb.append(" type=").append(asStr(m.get("type")));
                }
                sb.append("\n");
            }
        }
        return writeArtifact(outputRunDir.resolve(manifestTag).resolve(fileName), sb.toString());
    }

    private ExecutionStepResult executeMysqlStep(
            String manifestTag,
            ManifestRow row,
            JsonNode step,
            JsonNode expectedStep,
            int stepNo,
            TemplateResolver.SqlTemplate resolvedSqlTemplate,
            boolean wantsReferenceSnapshot
    ) {
        ExecutionStepResult result = new ExecutionStepResult();
        JsonComparator.ComparisonConfig comparisonConfig = JsonComparator.configFromExpectedStep(expectedStep);
        result.expectedCode = 200;
        result.payloadRaw = text(step, "mysqlQuery", "");
        result.payloadFile = writeMysqlPayloadArtifact(manifestTag, row.apiName(), stepNo, step, resolvedSqlTemplate);
        result.expectedNode = expectedStep == null ? null : expectedStep.get("expectedTableValue");
        try {
            JsonNode actualRows;
            if (wantsReferenceSnapshot) {
                DatabaseClient.DualJsonResult dualResult = resolvedSqlTemplate != null
                        ? dbClient.executeJsonQueryWithReference(resolvedSqlTemplate.sql(), resolvedSqlTemplate.params())
                        : dbClient.executeJsonQueryWithReference(result.payloadRaw, Collections.emptyList());
                actualRows = dualResult.actualNode();
                result.referenceNode = dualResult.referenceNode();
            } else {
                actualRows = resolvedSqlTemplate != null
                        ? dbClient.executeJsonQuery(resolvedSqlTemplate.sql(), resolvedSqlTemplate.params())
                        : dbClient.executeJsonQuery(result.payloadRaw);
            }
            result.actualCode = 200;
            result.actualNode = actualRows;
            result.actualRaw = actualRows == null ? "[]" : actualRows.toPrettyString();
            result.responseFile = writeJsonOutputArtifact(manifestTag, row.apiName(), stepNo, actualRows);
            boolean chainedEmptyDbSource = resolvedSqlTemplate != null && isEmptyDbSource(actualRows);
            result.mismatches = chainedEmptyDbSource
                    ? Collections.singletonList(ScenarioContext.error(
                            "empty_db_source",
                            "$",
                            "non-empty DB source result",
                            "[]"
                    ))
                    : compareExpected(result.expectedNode, result.actualNode, comparisonConfig);
            if (!result.mismatches.isEmpty()) {
                result.comparisonFile = writeFailureComparisonArtifact(
                        manifestTag, row.apiName(), stepNo, result.expectedNode, result.actualNode, result.actualRaw, comparisonConfig, result.mismatches
                );
            }
            result.passed = result.mismatches.isEmpty();
            result.referenceable = result.passed && result.actualNode != null;
        } catch (Exception e) {
            result.actualCode = 500;
            result.actualRaw = e.getMessage() == null ? e.toString() : e.getMessage();
            result.responseFile = writeJsonOutputArtifact(manifestTag, row.apiName(), stepNo, result.actualRaw);
            result.mismatches = Collections.emptyList();
            result.passed = false;
        }
        return result;
    }

    private boolean isEmptyDbSource(JsonNode actualRows) {
        return actualRows == null || actualRows.isNull() || (actualRows.isArray() && actualRows.isEmpty());
    }

    private boolean hasChainingMetadata(String stepId, String apiPath, String urlParameter, String payloadText, String mysqlQueryText, JsonNode expectedStep) {
        if (stepId != null && !stepId.isBlank()) {
            return true;
        }
        if (TemplateResolver.containsTemplate(apiPath) || TemplateResolver.containsTemplate(urlParameter)
                || TemplateResolver.containsTemplate(payloadText) || TemplateResolver.containsTemplate(mysqlQueryText)) {
            return true;
        }
        return expectedStep != null && (expectedStep.has("assertions") || expectedStep.has("assertProfiles"));
    }

    private boolean scenarioHasChainingMetadata(String payloadFileRaw, String expectedFileRaw, String inputsFileRaw) {
        return containsChainingText(payloadFileRaw) || containsChainingText(expectedFileRaw) || containsChainingText(inputsFileRaw);
    }

    private boolean containsChainingText(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return raw.contains("{{")
                || raw.contains("\"stepId\"")
                || raw.contains("\"assertions\"")
                || raw.contains("\"assertProfiles\"");
    }

    private List<Map<String, Object>> validateResponseChainingPreconditions(
            boolean mysqlCase,
            boolean hasChainingMetadata,
            String stepId,
            String urlParameter,
            JsonNode expectedStep,
            ScenarioContext scenarioContext
    ) {
        List<Map<String, Object>> errors = new ArrayList<>();
        if (!hasChainingMetadata) {
            return errors;
        }
        if (!cfg.responseChainingEnabled) {
            errors.add(ScenarioContext.error(
                    "response_chaining_disabled",
                    "$",
                    "responseChaining.enabled=true",
                    "responseChaining.enabled=false"
            ));
            return errors;
        }
        if (mysqlCase) {
            if (expectedStep != null && expectedStep.has("assertions")) {
                errors.add(ScenarioContext.error(
                        "unsupported_chaining_metadata",
                        "assertions",
                        "non-MySQL API row",
                        "MySQL row"
                ));
            }
            if (TemplateResolver.containsTemplate(urlParameter)) {
                errors.add(ScenarioContext.error(
                        "unsupported_chaining_metadata",
                        "urlParameter",
                        "non-MySQL API row",
                        "MySQL row"
                ));
            }
        }
        if (expectedStep != null && expectedStep.has("assertProfiles")) {
            errors.add(ScenarioContext.error(
                    "unsupported_chaining_metadata",
                    "assertProfiles",
                    "inline assertions",
                    "assertProfiles"
            ));
        }
        if (stepId != null && !stepId.isBlank()) {
            String trimmed = stepId.trim();
            if (ScenarioContext.isReservedStepId(trimmed)) {
                errors.add(ScenarioContext.error(
                        "reserved_step_id",
                        "stepId",
                        "non-reserved stepId",
                        trimmed
                ));
            } else if (scenarioContext.hasAlias(trimmed)) {
                errors.add(ScenarioContext.error(
                        "duplicate_step_id",
                        "stepId",
                        "unique stepId",
                        trimmed
                ));
            }
        }
        return errors;
    }

    private boolean hasStepIdValidationError(List<Map<String, Object>> errors) {
        if (errors == null || errors.isEmpty()) {
            return false;
        }
        for (Map<String, Object> error : errors) {
            String kind = String.valueOf(error.get("kind"));
            if ("duplicate_step_id".equals(kind) || "reserved_step_id".equals(kind)) {
                return true;
            }
        }
        return false;
    }

    private ExecutionStepResult failedPreRequestStep(
            String manifestTag,
            ManifestRow row,
            JsonNode step,
            int stepNo,
            int expectedCode,
            JsonNode expectedBodyNode,
            String payloadText,
            List<Map<String, Object>> mismatches
    ) {
        ExecutionStepResult result = new ExecutionStepResult();
        boolean mysqlCase = step.has("mysqlQuery");
        result.expectedCode = expectedCode;
        result.actualCode = PRE_REQUEST_FAILURE_CODE;
        result.expectedNode = expectedBodyNode;
        Path httpArtifact = writePayloadArtifact(manifestTag, row.apiName(), stepNo, payloadText == null ? "" : payloadText);
        Path mysqlArtifact = writeMysqlPayloadArtifact(manifestTag, row.apiName(), stepNo, step);
        if (mysqlCase) {
            result.payloadRaw = text(step, "mysqlQuery", "");
            result.payloadFile = mysqlArtifact;
        } else {
            result.payloadRaw = payloadText == null ? "" : payloadText;
            result.payloadFile = httpArtifact;
        }
        result.actualRaw = preRequestFailureBody(mismatches);
        result.actualNode = safeParseJson(result.actualRaw);
        result.responseFile = writeJsonOutputArtifact(manifestTag, row.apiName(), stepNo, result.actualRaw);
        result.mismatches = mismatches == null ? Collections.emptyList() : mismatches;
        result.comparisonFile = writeFailureComparisonArtifact(
                manifestTag,
                row.apiName(),
                stepNo,
                expectedBodyNode,
                result.actualNode,
                result.actualRaw,
                JsonComparator.emptyConfig(),
                result.mismatches
        );
        result.passed = false;
        return result;
    }

    private String preRequestFailureBody(List<Map<String, Object>> mismatches) {
        ObjectNode root = mapper.createObjectNode();
        root.put("error", "response_chaining_pre_request_failure");
        ArrayNode details = root.putArray("mismatches");
        if (mismatches != null) {
            for (Map<String, Object> mismatch : mismatches) {
                details.add(mapper.valueToTree(mismatch));
            }
        }
        return root.toString();
    }

    private JsonNode pruneIgnored(JsonNode node, Set<String> ignore, String path) {
        if (node == null || node.isNull()) return node;
        if (ignore == null || ignore.isEmpty()) return node.deepCopy();
        if (shouldIgnore(path, ignore)) return null;

        JsonNode embeddedJson = parseEmbeddedJson(node);
        if (embeddedJson != null) {
            return pruneIgnored(embeddedJson, ignore, path);
        }

        if (node.isObject()) {
            ObjectNode copy = mapper.createObjectNode();
            Iterator<String> fields = node.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                String childPath = path == null || path.isEmpty() ? field : path + "." + field;
                if (shouldIgnore(childPath, ignore)) {
                    continue;
                }
                JsonNode pruned = pruneIgnored(node.get(field), ignore, childPath);
                if (pruned != null) {
                    copy.set(field, pruned);
                }
            }
            return copy;
        }

        if (node.isArray()) {
            ArrayNode copy = mapper.createArrayNode();
            for (int i = 0; i < node.size(); i++) {
                String childPath = (path == null ? "" : path) + "[" + i + "]";
                if (shouldIgnore(childPath, ignore)) {
                    continue;
                }
                JsonNode pruned = pruneIgnored(node.get(i), ignore, childPath);
                if (pruned != null) {
                    copy.add(pruned);
                }
            }
            return copy;
        }

        return node.deepCopy();
    }

    private Path writeArtifact(Path out, String content) {
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, safeLarge(content), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return out;
        } catch (IOException e) {
            log.warn("Failed writing artifact file={}", out, e);
            return out;
        }
    }

    private void appendResultRow(
            ManifestRow row,
            String manifestName,
            Path inputsPath,
            String testScenarioNumber,
            String method,
            String apiPathWithValue,
            int expectedCode,
            int actualCode,
            boolean passed,
            Path payloadFile,
            Path responseFile,
            String reasonForFailure,
            Path assertionResultsFile
    ) {
        String status = passed ? "PASS" : "FAIL";
        String line = String.join(",",
                csv(runDate),
                csv(runTime),
                csv(row.apiName()),
                csv(row.moduleName()),
                csv(testScenarioNumber),
                csv(portable(payloadFile)),
                csv(portable(responseFile)),
                csv(status),
                csv(String.valueOf(expectedCode)),
                csv(String.valueOf(actualCode)),
                csv(portable(inputsPath)),
                csv(method),
                csv(apiPathWithValue),
                csv(reasonForFailure),
                csv(normalizeOwner(row.owner())),
                csv(portable(assertionResultsFile))
        ) + "\n";

        String manifestTag = manifestTag(manifestName);
        Path apiResultFile = resultsRunDir
                .resolve(manifestTag)
                .resolve(manifestTag + "__" + ArtifactNames.sanitizeFlatName(row.apiName()) + "_" + runStamp + "_results.csv");

        try {
            synchronized (CSV_LOCK) {
                Files.createDirectories(apiResultFile.getParent());
                if (!Files.exists(apiResultFile)) {
                    Files.writeString(
                            apiResultFile,
                            "Test Execution Date,Execution Time,API Name,Module Name,Test Scenario Number,JSON Payload,Results JSON File Name,Test Result,Expected Response Code,Actual Response Code,API Inputs File,API Method,API Path & Value,Reason For Failure,Owner,Assertion Results Artifact\n",
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING
                    );
                }
                Files.writeString(
                        apiResultFile,
                        line,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            }
        } catch (IOException e) {
            log.warn("Failed appending result row to {}", apiResultFile, e);
        }
    }

    private void putCanaryMetadata(
            Map<String, Object> target,
            JsonNode expectedStep,
            String endpointTemplate,
            ExecutionStepResult stepResult
    ) {
        if (target == null) {
            return;
        }
        Set<String> commonIgnore = JsonComparator.collectCommonIgnoreFields(expectedStep);
        Set<String> caseIgnore = JsonComparator.collectCaseIgnoreFields(expectedStep);
        target.put("endpointTemplate", endpointTemplate == null ? "" : endpointTemplate);
        target.put("commonIgnore", new ArrayList<>(commonIgnore));
        target.put("caseIgnore", new ArrayList<>(caseIgnore));
        target.put("actualEffectivelyEmpty", actualEffectivelyEmpty(stepResult));
    }

    private void putAssertionMetadata(Map<String, Object> target, ExecutionStepResult stepResult) {
        if (target == null || stepResult == null || stepResult.assertionResults == null || stepResult.assertionResults.isEmpty()) {
            return;
        }
        target.put("assertionResults", stepResult.assertionResults);
        target.put("assertionResultsArtifact", portable(stepResult.assertionResultsFile));
    }

    private boolean actualEffectivelyEmpty(ExecutionStepResult stepResult) {
        if (stepResult == null || expectedEffectivelyEmpty(stepResult.expectedNode)) {
            return false;
        }
        String actualRaw = stepResult.actualRaw == null ? "" : stepResult.actualRaw.trim();
        if (stepResult.actualNode == null || stepResult.actualNode.isNull()) {
            return actualRaw.isEmpty();
        }
        if (nodeEffectivelyEmpty(stepResult.actualNode)) {
            return true;
        }
        return actualRaw.isEmpty();
    }

    private boolean expectedEffectivelyEmpty(JsonNode expected) {
        return nodeEffectivelyEmpty(expected);
    }

    private boolean nodeEffectivelyEmpty(JsonNode node) {
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

    private void putRebaselineMetadata(
            Map<String, Object> target,
            ManifestRow row,
            JsonNode expectedStep,
            int stepNo,
            Path inputsPath,
            Path payloadFile,
            Path responseFile,
            Path comparisonFile
    ) {
        if (target == null || row == null) {
            return;
        }
        target.put("apiInputsFile", row.apiInputsFile());
        target.put("apiInputsPath", contentPath("apiInputs", row.apiInputsFile()));
        target.put("payloadJsonFile", row.payloadJson());
        target.put("payloadJsonPath", contentPath("fileFromJson/payloadJSON", row.payloadJson()));
        target.put("expectedJsonFile", row.expectedJson());
        target.put("expectedJsonPath", contentPath("fileFromJson/expectedJSON", row.expectedJson()));
        target.put("expectedValueField", expectedValueField(expectedStep));
        target.put("scenarioKey", scenarioKey(expectedStep));
        target.put("scenarioName", scenarioName(expectedStep, row.apiName(), stepNo));
        target.put("resultApiInputsPath", portable(inputsPath));
        target.put("jsonPayloadArtifact", portable(payloadFile));
        target.put("jsonOutputArtifact", portable(responseFile));
        target.put("comparisonArtifact", portable(comparisonFile));
    }

    private String contentPath(String directory, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        return directory + "/" + fileName;
    }

    private String expectedValueField(JsonNode expectedStep) {
        if (expectedStep != null && expectedStep.has("expectedTableValue")) {
            return "expectedTableValue";
        }
        return "expected";
    }

    private String scenarioKey(JsonNode expectedStep) {
        if (expectedStep != null && expectedStep.hasNonNull("queryName")) {
            return "queryName";
        }
        return "apiName";
    }

    private String scenarioName(JsonNode expectedStep, String apiName, int stepNo) {
        String key = scenarioKey(expectedStep);
        if (expectedStep != null && expectedStep.hasNonNull(key)) {
            return expectedStep.get(key).asText("");
        }
        return (apiName == null ? "" : apiName) + stepNo;
    }

    private String csv(String raw) {
        String v = raw == null ? "" : raw;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private String abs(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private String portable(Path path) {
        if (path == null) return "";
        Path absolute = path.toAbsolutePath().normalize();
        Path projectRoot = cfg.projectRoot.toAbsolutePath().normalize();
        if (absolute.startsWith(projectRoot)) {
            return projectRoot.relativize(absolute).toString();
        }
        Path runnerRoot = PathResolver.runnerRoot(cfg.projectRoot).toAbsolutePath().normalize();
        if (absolute.startsWith(runnerRoot)) {
            return runnerRoot.relativize(absolute).toString();
        }
        return absolute.toString();
    }

    private boolean looksLikeJson(String value) {
        if (value == null) return false;
        String t = value.trim();
        if (t.isEmpty()) return false;
        return t.startsWith("{") || t.startsWith("[");
    }

    private JsonNode safeReadJson(Path path) {
        try {
            return mapper.readTree(readString(path));
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode safeReadJson(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String readString(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private JsonNode safeParseJson(String raw) {
        return safeParseJson(raw, null, 0);
    }

    private JsonNode safeParseJson(String raw, String apiName, int stepNo) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            if (apiName != null) {
                logJsonParseFailure(apiName, stepNo, e);
            }
            return null;
        }
    }

    private JsonNode safeParseReferenceJson(String raw, String apiName, int stepNo) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return referenceMapper.readTree(raw);
        } catch (Exception e) {
            logJsonParseFailure(apiName, stepNo, e);
            return null;
        }
    }

    private void logJsonParseFailure(String apiName, int stepNo, Exception error) {
        if (error instanceof JsonProcessingException jsonError) {
            JsonLocation location = jsonError.getLocation();
            if (location != null) {
                log.warn(
                        "Failed parsing JSON response api={} step={} error={} line={} column={}",
                        apiName,
                        stepNo,
                        error.getClass().getSimpleName(),
                        location.getLineNr(),
                        location.getColumnNr()
                );
                return;
            }
        }
        log.warn(
                "Failed parsing JSON response api={} step={} error={}",
                apiName,
                stepNo,
                error.getClass().getSimpleName()
        );
    }

    private JsonNode getArrayNode(JsonNode arr, int idx) {
        if (arr == null || !arr.isArray() || idx < 0 || idx >= arr.size()) return null;
        return arr.get(idx);
    }

    private HttpRequest buildHttpRequest(
            String url,
            String method,
            String payloadText,
            JsonNode payloadNode,
            String token,
            String businessFunction,
            String testStresserFlg,
            String scenarioName,
            String testCaseName,
            String accept
    ) {
        String acceptHeader = accept == null || accept.isBlank() ? "application/json" : accept.trim();
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", acceptHeader)
                .header("X-Tenant-Identifier", cfg.tenant)
                .header("User", cfg.username)
                .header("Authorization", token);
        applyCiHeaders(rb, businessFunction, testStresserFlg, scenarioName, testCaseName);
        if (cfg.readTimeoutMs > 0) {
            rb.timeout(Duration.ofMillis(cfg.readTimeoutMs));
        }
        if (hasBodyPayload(payloadNode, method)) {
            return rb.method(method.toUpperCase(), HttpRequest.BodyPublishers.ofString(payloadText)).build();
        }
        return rb.method(method.toUpperCase(), HttpRequest.BodyPublishers.noBody()).build();
    }

    private void applyCiHeaders(
            HttpRequest.Builder rb,
            String businessFunction,
            String testStresserFlg,
            String scenarioName,
            String testCaseName
    ) {
        if (!cfg.ciHeadersEnabled) {
            return;
        }
        String normalizedBusinessFunction = businessFunction == null ? "" : businessFunction.trim();
        if (normalizedBusinessFunction.isEmpty() || "NA".equalsIgnoreCase(normalizedBusinessFunction)) {
            return;
        }
        if (!"Y".equalsIgnoreCase(testStresserFlg)) {
            return;
        }
        rb.header("X-TestBusinessFunction", normalizedBusinessFunction)
                .header("X-TestStresserFlg", "Y")
                .header("X-TestScenario", scenarioName == null ? "" : scenarioName)
                .header("X-TestCase", testCaseName == null ? "" : testCaseName);
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request, String url, String apiName, int stepNo) throws Exception {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_HTTP_ATTEMPTS; attempt++) {
            try {
                return http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                last = e;
                if (attempt >= MAX_HTTP_ATTEMPTS) break;
                log.warn("HTTP request failed attempt={} api={} step={} url={} error={}. Retrying",
                        attempt, apiName, stepNo, url, safeRetryMessage(e));
                Thread.sleep(RETRY_DELAY_MS);
            }
        }
        throw last == null ? new IOException("HTTP request failed without a captured cause") : last;
    }

    private HttpResponse<Path> sendFileWithRetry(
            HttpRequest request,
            String url,
            String apiName,
            int stepNo,
            Path downloadPath
    ) throws Exception {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_HTTP_ATTEMPTS; attempt++) {
            try {
                return http.send(
                        request,
                        HttpResponse.BodyHandlers.ofFile(
                                downloadPath,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE
                        )
                );
            } catch (IOException e) {
                last = e;
                if (attempt >= MAX_HTTP_ATTEMPTS) break;
                log.warn("HTTP file download failed attempt={} api={} step={} url={} error={}. Retrying",
                        attempt, apiName, stepNo, url, safeRetryMessage(e));
                Thread.sleep(RETRY_DELAY_MS);
            }
        }
        throw last == null ? new IOException("HTTP file download failed without a captured cause") : last;
    }

    private String safeRetryMessage(Exception e) {
        String msg = e == null ? "" : e.getMessage();
        return msg == null ? "" : msg;
    }

    private boolean isAuthFailure(int statusCode) {
        return statusCode == HTTP_UNAUTHORIZED || statusCode == HTTP_FORBIDDEN;
    }

    private boolean hasBodyPayload(JsonNode payloadNode, String method) {
        if (!("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method))) {
            return false;
        }
        if (payloadNode == null || payloadNode.isNull()) return false;
        return !(payloadNode.isArray() && payloadNode.size() == 0);
    }

    private Set<String> collectIgnoreFields(JsonNode expectedStep) {
        Set<String> ignore = new HashSet<>();
        addIgnore(expectedStep == null ? null : expectedStep.get("ignore"), ignore);
        addIgnore(expectedStep == null ? null : expectedStep.get("commonIgnore"), ignore);
        return ignore;
    }

    private void addIgnore(JsonNode node, Set<String> ignore) {
        if (node == null || !node.isArray()) return;
        for (JsonNode n : node) {
            if (n.isTextual()) {
                String v = n.asText().trim();
                if (!v.isEmpty()) ignore.add(v);
            }
        }
    }

    private List<Map<String, Object>> compareExpected(JsonNode expected, JsonNode actual, Set<String> ignore) {
        return JsonComparator.compare(expected, actual, JsonComparator.config(ignore));
    }

    private List<Map<String, Object>> compareExpected(JsonNode expected, JsonNode actual, JsonComparator.ComparisonConfig config) {
        return JsonComparator.compare(expected, actual, config);
    }

    private boolean isBodyAssertionDisabled(JsonNode expected) {
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

    private void compareNode(String path, JsonNode expected, JsonNode actual, Set<String> ignore, List<Map<String, Object>> out) {
        if (shouldIgnore(path, ignore)) {
            return;
        }

        if (expected == null || expected.isNull()) {
            return;
        }

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
            if (evNum.compareTo(avNum) != 0) {
                mismatch(path, expected, actual, "value_mismatch", out);
            }
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
        if (!ev.equals(av)) {
            mismatch(path, expected, actual, "value_mismatch", out);
        }
    }

    private boolean shouldIgnore(String path, Set<String> ignore) {
        if (ignore.isEmpty() || path == null || path.isEmpty()) return false;
        if (ignore.contains(path)) return true;

        String last = path;
        int dot = last.lastIndexOf('.');
        if (dot >= 0) last = last.substring(dot + 1);
        int bracket = last.indexOf('[');
        if (bracket >= 0) last = last.substring(0, bracket);

        return ignore.contains(last);
    }

    private void mismatch(String path, JsonNode expected, JsonNode actual, String kind, List<Map<String, Object>> out) {
        Map<String, Object> m = new HashMap<>();
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

    private String parentArrayPath(String path) {
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

    private String valueType(JsonNode expected, JsonNode actual) {
        JsonNode node = expected != null && !expected.isNull() ? expected : actual;
        if (node == null || node.isNull()) return "null";
        if (node.isObject()) return "object";
        if (node.isArray()) return "array";
        if (node.isBoolean()) return "boolean";
        if (node.isNumber()) return "number";
        if (node.isTextual()) return "string";
        return "string";
    }

    private String normalized(JsonNode n) {
        if (n == null || n.isNull()) return "null";
        if (n.isTextual()) return n.asText();
        return n.toString();
    }

    private BigDecimal comparableNumber(JsonNode n) {
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

    private boolean shouldCompareAsNumber(JsonNode n) {
        if (n == null || n.isNull()) return false;
        if (n.isNumber()) return true;
        if (!n.isTextual()) return false;
        String t = n.asText().trim();
        if (!t.matches("[-+]?\\d+(\\.\\d+)?")) return false;
        // For plain textual integers ("001"), preserve strict string compare behavior.
        return t.contains(".");
    }

    private JsonNode parseEmbeddedJson(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        String raw = node.asText();
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return null;
        try {
            return mapper.readTree(trimmed);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String key, String def) {
        return node != null && node.hasNonNull(key) ? node.get(key).asText() : def;
    }

    private static String getCsv(CSVRecord rec, String key) {
        return rec.isMapped(key) ? rec.get(key).trim() : "";
    }

    private static int parseIntOrDefault(String v, int d) {
        try { return Integer.parseInt(v); } catch (Exception e) { return d; }
    }

    private static String normalizePath(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String safeLarge(String s) {
        return s == null ? "" : s;
    }

    private String normalizeOwner(String owner) {
        if (owner == null || owner.isBlank()) return "NA";
        return owner.trim();
    }

    private String manifestTag(String manifestName) {
        if (manifestName == null || manifestName.isBlank()) return "UNKNOWN";
        if (manifestName.contains("_A")) return "A";
        if (manifestName.contains("_B")) return "B";
        if (manifestName.contains("_C")) return "C";
        return ArtifactNames.sanitizeFlatName(manifestName.replace(".csv", ""));
    }

    private void applyPostFeatureToggleDelay(String apiPath, JsonNode payloadNode, int statusCode, String apiName, int stepNo) {
        if (statusCode < 200 || statusCode >= 300) return;
        if (apiPath == null || !"/api/office/v1/feature/edit".equals(normalizePath(apiPath))) return;

        String featureName = payloadNode == null ? "" : text(payloadNode, "featureName", "");
        long delayMs = "FUTFEESC".equalsIgnoreCase(featureName) ? FUTURE_FEE_TOGGLE_DELAY_MS : FEATURE_TOGGLE_DELAY_MS;

        log.info("Post feature toggle pause api={} step={} featureName={} delayMs={}", apiName, stepNo, featureName, delayMs);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted during post feature toggle pause api={} step={} featureName={}", apiName, stepNo, featureName);
        }
    }

    private String buildReasonForFailure(
            int expectedCode,
            int actualCode,
            List<Map<String, Object>> mismatches,
            Path comparisonFile,
            String actualRaw
    ) {
        if (actualCode != expectedCode) {
            String detail = summarizeFailureDetail(actualRaw);
            String lead = actualCode == PRE_REQUEST_FAILURE_CODE
                    ? "Request was never issued (response chaining could not be resolved)"
                    : "Actual response code received is " + actualCode;
            String reason = detail.isEmpty() ? lead : lead + ": " + detail;
            int mismatchCount = mismatches == null ? 0 : mismatches.size();
            if (mismatchCount > 0 && comparisonFile != null) {
                return reason + "; comparison artifact: " + abs(comparisonFile);
            }
            return reason;
        }
        int mismatchCount = mismatches == null ? 0 : mismatches.size();
        if (mismatchCount > 0) {
            if (comparisonFile != null) {
                return "Expected result not match with actual result: " + abs(comparisonFile);
            }
            return "Response body mismatch count is " + mismatchCount;
        }
        return "";
    }

    private static String asStr(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String normalizeApiName(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String summarizeFailureDetail(String actualRaw) {
        if (actualRaw == null) return "";
        String trimmed = actualRaw.trim();
        if (trimmed.isEmpty()) return "";
        if (looksLikeJson(trimmed) || trimmed.startsWith("<!doctype html") || trimmed.startsWith("<html") || trimmed.startsWith("<!--")) {
            return "";
        }
        String singleLine = trimmed.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return singleLine.length() > 200 ? singleLine.substring(0, 200) : singleLine;
    }

    private static final class ExecutionStepResult {
        private int expectedCode;
        private int actualCode;
        private boolean passed;
        private boolean referenceable;
        private Path payloadFile;
        private Path responseFile;
        private Path comparisonFile;
        private String payloadRaw = "";
        private String actualRaw = "";
        private JsonNode expectedNode;
        private JsonNode actualNode;
        private JsonNode referenceNode;
        private List<Map<String, Object>> mismatches = Collections.emptyList();
        private List<Map<String, Object>> assertionResults = Collections.emptyList();
        private Path assertionResultsFile;
    }
}
