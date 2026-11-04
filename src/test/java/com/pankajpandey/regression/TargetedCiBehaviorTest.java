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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetedCiBehaviorTest {

    @TempDir
    Path tempDir;

    @Test
    void manifestRowMatchesAnyBusinessFunctionAcrossAmpersandSeparatedValues() {
        ManifestRow row = new ManifestRow(
                2L,
                "SampleApi",
                "Sample.csv",
                "n",
                "json",
                "payload.json",
                "expected.json",
                "n",
                "Module",
                "Owner",
                "Penalty & Restructure & AdhocFee"
        );

        Set<String> targets = new LinkedHashSet<>(Arrays.asList(
                Config.normalizeBusinessFunction("Restructure"),
                Config.normalizeBusinessFunction("Adhoc Fee")
        ));

        assertTrue(row.matchesAnyBusinessFunction(targets));
        assertFalse(row.matchesAnyBusinessFunction(Set.of(Config.normalizeBusinessFunction("Balance Transfer"))));
    }

    @Test
    void targetedCiModeUsesBusinessFunctionInsteadOfToBeValidated() throws Exception {
        ExecuteEngine engine = new ExecuteEngine(testConfig(tempDir, Set.of("restructure"), true, false));
        Method method = ExecuteEngine.class.getDeclaredMethod("shouldExecuteRow", ManifestRow.class);
        method.setAccessible(true);

        ManifestRow matchingDisabledRow = new ManifestRow(2L, "ApiOne", "in.csv", "n", "json", "p.json", "e.json", "n", "Module", "Owner", "Restructure");
        ManifestRow nonMatchingEnabledRow = new ManifestRow(3L, "ApiTwo", "in.csv", "y", "json", "p.json", "e.json", "n", "Module", "Owner", "AdhocFee");

        assertTrue((Boolean) method.invoke(engine, matchingDisabledRow));
        assertFalse((Boolean) method.invoke(engine, nonMatchingEnabledRow));
    }

    @Test
    void buildHttpRequestAddsCiHeadersForStresserSteps() throws Exception {
        ExecuteEngine engine = new ExecuteEngine(testConfig(tempDir, Set.of("restructure"), true, true));
        HttpRequest request = invokeBuildHttpRequest(engine, "Restructure", "Y", "RestructureScenario", "RestructureCase");

        assertEquals("Restructure", request.headers().firstValue("X-TestBusinessFunction").orElse(""));
        assertEquals("Y", request.headers().firstValue("X-TestStresserFlg").orElse(""));
        assertEquals("RestructureScenario", request.headers().firstValue("X-TestScenario").orElse(""));
        assertEquals("RestructureCase", request.headers().firstValue("X-TestCase").orElse(""));
    }

    @Test
    void buildHttpRequestOmitsCiHeadersWhenStresserFlagIsNotEnabled() throws Exception {
        ExecuteEngine engine = new ExecuteEngine(testConfig(tempDir, Set.of("restructure"), true, true));
        HttpRequest request = invokeBuildHttpRequest(engine, "Restructure", "N", "Scenario", "Case");

        assertFalse(request.headers().firstValue("X-TestBusinessFunction").isPresent());
        assertFalse(request.headers().firstValue("X-TestStresserFlg").isPresent());
        assertFalse(request.headers().firstValue("X-TestScenario").isPresent());
        assertFalse(request.headers().firstValue("X-TestCase").isPresent());
    }

    @Test
    void buildHttpRequestUsesStepAcceptHeaderForFileDownloads() throws Exception {
        ExecuteEngine engine = new ExecuteEngine(testConfig(tempDir, Set.of(), false, false));
        HttpRequest request = invokeBuildHttpRequest(
                engine,
                "NA",
                "N",
                "FinancialReportsDownload",
                "FinancialReportsDownload1",
                "application/vnd.ms-excel"
        );

        assertEquals("application/vnd.ms-excel", request.headers().firstValue("Accept").orElse(""));
    }

    private static HttpRequest invokeBuildHttpRequest(
            ExecuteEngine engine,
            String businessFunction,
            String stresserFlag,
            String scenarioName,
            String testCaseName
    ) throws Exception {
        return invokeBuildHttpRequest(
                engine,
                businessFunction,
                stresserFlag,
                scenarioName,
                testCaseName,
                "application/json"
        );
    }

    private static HttpRequest invokeBuildHttpRequest(
            ExecuteEngine engine,
            String businessFunction,
            String stresserFlag,
            String scenarioName,
            String testCaseName,
            String accept
    ) throws Exception {
        Method method = ExecuteEngine.class.getDeclaredMethod(
                "buildHttpRequest",
                String.class,
                String.class,
                String.class,
                com.fasterxml.jackson.databind.JsonNode.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class
        );
        method.setAccessible(true);
        ObjectMapper mapper = new ObjectMapper();
        return (HttpRequest) method.invoke(
                engine,
                "https://localhost/example",
                "POST",
                "{\"hello\":\"world\"}",
                mapper.readTree("{\"hello\":\"world\"}"),
                "Bearer test",
                businessFunction,
                stresserFlag,
                scenarioName,
                testCaseName,
                accept
        );
    }

    private static Config testConfig(Path projectRoot, Set<String> businessCases, boolean targetedCiMode, boolean ciHeadersEnabled) {
        return Config.builder()
                .projectRoot(projectRoot.toAbsolutePath().normalize())
                .manifests(List.of("APIsToBeValidated_A.csv"))
                .apis(Set.of())
                .businessCasesToTest(businessCases)
                .threads(1)
                .outputFile(projectRoot.resolve("output/test-output.json"))
                .mode("execute")
                .protocol("https")
                .server("localhost")
                .tenant("tenant")
                .username("user")
                .password("password")
                .tokenUrl("")
                .tokenPath("/token")
                .serviceBaseUrls(Map.of())
                .dbUrl("")
                .dbUsername("")
                .dbPassword("")
                .dbDriver("org.h2.Driver")
                .dbConnectTimeoutSeconds(5)
                .dbQueryTimeoutSeconds(30)
                .connectTimeoutMs(1_000)
                .readTimeoutMs(1_000)
                .limitApisPerManifest(0)
                .limitStepsPerApi(0)
                .tokenRefreshMinutes(10)
                .partialReportIntervalSeconds(300)
                .targetedCiMode(targetedCiMode)
                .ciHeadersEnabled(ciHeadersEnabled)
                .build();
    }
}
