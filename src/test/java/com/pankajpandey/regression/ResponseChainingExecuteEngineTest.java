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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseChainingExecuteEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void executeModeChainsResponseIntoPayloadAndEvaluatesRuntimeAssertion() throws Exception {
        AtomicReference<String> echoRequest = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/source", exchange -> json(exchange, 200, "{\"id\":123,\"bookedInterestUnpaid\":14.5800}"));
        server.createContext("/api/test/v1/echo", exchange -> {
            echoRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(exchange, 200, "{\"received\":123}");
        });
        server.createContext("/api/test/v1/ianp", exchange -> json(exchange, 200, "{\"interestAccruedNotDueNotPaid\":14.58}"));
        server.start();
        try {
            writeFixture();
            int port = server.getAddress().getPort();
            Config cfg = Config.load(new String[] {
                    "--projectRoot=" + tempDir,
                    "--mode=execute",
                    "--manifests=APIsToBeValidated_A.csv",
                    "--apis=ChainedApi",
                    "--protocol=http",
                    "--server=localhost:" + port,
                    "--username=user",
                    "--password=password",
                    "--tenant=tenant",
                    "--responseChaining.enabled=true"
            });

            ExecuteEngine engine = new ExecuteEngine(cfg);
            ExecutionReport report = engine.runForManifest("APIsToBeValidated_A.csv");

            assertEquals(3, report.stepsExecuted);
            assertEquals(3, report.stepsPassed);
            assertEquals(0, report.stepsFailed);
            assertTrue(echoRequest.get().contains("\"loanId\":123"), echoRequest.get());
            @SuppressWarnings("unchecked")
            java.util.List<java.util.Map<String, Object>> assertionResults =
                    (java.util.List<java.util.Map<String, Object>>) report.passes.get(2).get("assertionResults");
            assertEquals(1, assertionResults.size());
            assertEquals("PASS", assertionResults.get(0).get("status"));
            assertEquals("14.58", assertionResults.get(0).get("resolvedActual"));
            assertEquals("14.58", assertionResults.get(0).get("resolvedExpected"));
            assertTrue(Files.exists(tempDir.resolve("java-regression-runner/jsonOutput")
                    .resolve(engine.runFolder())
                    .resolve("A")
                    .resolve("assertionResults__A__ChainedApi3.json")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeModeChainsResponseIntoCsvUrlParameterUsingStepFallbackAlias() throws Exception {
        AtomicReference<String> loanRequestPath = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/source", exchange -> json(exchange, 200, "{\"id\":123}"));
        server.createContext("/api/test/v1/loans", exchange -> {
            loanRequestPath.set(exchange.getRequestURI().getPath());
            json(exchange, 200, "{\"loaded\":123}");
        });
        server.start();
        try {
            writeCsvUrlParameterFixture();
            int port = server.getAddress().getPort();
            Config cfg = Config.load(new String[] {
                    "--projectRoot=" + tempDir,
                    "--mode=execute",
                    "--manifests=APIsToBeValidated_Url.csv",
                    "--apis=UrlChainedApi",
                    "--protocol=http",
                    "--server=localhost:" + port,
                    "--username=user",
                    "--password=password",
                    "--tenant=tenant",
                    "--responseChaining.enabled=true"
            });

            ExecutionReport report = new ExecuteEngine(cfg).runForManifest("APIsToBeValidated_Url.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(2, report.stepsPassed);
            assertEquals(0, report.stepsFailed);
            assertEquals("/api/test/v1/loans/123", loanRequestPath.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeModeFailsFastOnAReservedStepIdWithHttpArtifactAndSentinelActualCode() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.start();
        try {
            writeReservedStepIdFixture();
            Config cfg = Config.load(new String[] {
                    "--projectRoot=" + tempDir,
                    "--mode=execute",
                    "--manifests=APIsToBeValidated_Reserved.csv",
                    "--apis=ReservedStepIdApi",
                    "--protocol=http",
                    "--server=localhost:" + server.getAddress().getPort(),
                    "--username=user",
                    "--password=password",
                    "--tenant=tenant",
                    "--responseChaining.enabled=true"
            });

            ExecutionReport report = new ExecuteEngine(cfg).runForManifest("APIsToBeValidated_Reserved.csv");

            assertEquals(1, report.stepsExecuted);
            assertEquals(0, report.stepsPassed);
            assertEquals(1, report.stepsFailed);

            Map<String, Object> failure = report.failures.get(0);
            assertEquals(
                    -1,
                    failure.get("actualCode"),
                    "A pre-request chaining failure must report the -1 'no code' sentinel so the"
                            + " analysis/rebaseline classifiers (which gate on code >= 0) do not treat it as a"
                            + " status-code mismatch and offer to bake it into apiInputs");
            assertEquals("{\"amount\":10}", failure.get("payload"));

            // Render mode reconstructs comparison entries from the CSV reason text when codeFailed
            // is false, which the -1 sentinel now makes the normal case — so this string is
            // load-bearing, not cosmetic.
            try (Stream<Path> results = Files.walk(tempDir)) {
                String reasons = results
                        .filter(p -> p.getFileName().toString().endsWith("_results.csv"))
                        .map(p -> {
                            try {
                                return Files.readString(p);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        })
                        .collect(Collectors.joining("\n"));
                assertTrue(
                        reasons.contains("Request was never issued (response chaining could not be resolved)"),
                        "Results CSV must carry the sentinel reason text, not a numeric code. Was:\n" + reasons);
            }
            String artifactPath = (String) failure.get("jsonPayloadArtifact");
            assertTrue(artifactPath.contains("testScenario_for_API__"));
            assertEquals("{\"amount\":10}", Files.readString(tempDir.resolve(artifactPath)));
        } finally {
            server.stop(0);
        }
    }

    private void writeReservedStepIdFixture() throws IOException {
        Files.createDirectories(tempDir.resolve("apisToBeValidated"));
        Files.createDirectories(tempDir.resolve("apiInputs"));
        Files.createDirectories(tempDir.resolve("fileFromJson/payloadJSON"));
        Files.createDirectories(tempDir.resolve("fileFromJson/expectedJSON"));
        Files.createDirectories(tempDir.resolve("java-regression-runner"));

        Files.writeString(
                tempDir.resolve("apisToBeValidated/APIsToBeValidated_Reserved.csv"),
                "#,apiName,apiMethod,apiPath,apiInputsFile,toBeValidated,Module,fileType,payloadJSON,expectedJSON,consentPerson,scenario,mysql,BusinessFunction\n"
                        + "1,ReservedStepIdApi,NA,NA,ReservedStepIdApi.csv,y,Module,json,ReservedPayload.json,ReservedExpected.json,Owner,y,n,NA\n"
        );
        Files.writeString(
                tempDir.resolve("apiInputs/ReservedStepIdApi.csv"),
                "testScenarioNumber,testObjective,urlParameter,expectedResponseCode\n"
                        + "1,create,NA,200\n"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/payloadJSON/ReservedPayload.json"),
                "["
                        + "{\"apiName\":\"Create\",\"apiMethod\":\"POST\",\"apiPath\":\"/api/test/v1/create\",\"stepId\":\"current\",\"payload\":{\"amount\":10},\"TestStresserFlg\":\"N\"}"
                        + "]"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/expectedJSON/ReservedExpected.json"),
                "["
                        + "{\"apiName\":\"Create\",\"expected\":{\"accepted\":true}}"
                        + "]"
        );
    }

    @Test
    void executeModeAllowsReferenceFromSourceStepWithComparisonFailureButUsableResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/source", exchange -> json(exchange, 200, "{\"id\":123,\"bookedInterestUnpaid\":14.5800}"));
        server.createContext("/api/test/v1/ianp", exchange -> json(exchange, 200, "{\"interestAccruedNotDueNotPaid\":14.58}"));
        server.start();
        try {
            writeSourceComparisonFailureFixture();
            int port = server.getAddress().getPort();
            Config cfg = Config.load(new String[] {
                    "--projectRoot=" + tempDir,
                    "--mode=execute",
                    "--manifests=APIsToBeValidated_SourceMismatch.csv",
                    "--apis=SourceMismatchApi",
                    "--protocol=http",
                    "--server=localhost:" + port,
                    "--username=user",
                    "--password=password",
                    "--tenant=tenant",
                    "--responseChaining.enabled=true"
            });

            ExecutionReport report = new ExecuteEngine(cfg).runForManifest("APIsToBeValidated_SourceMismatch.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(1, report.stepsPassed);
            assertEquals(1, report.stepsFailed);
            @SuppressWarnings("unchecked")
            java.util.List<java.util.Map<String, Object>> assertionResults =
                    (java.util.List<java.util.Map<String, Object>>) report.passes.get(0).get("assertionResults");
            assertEquals(1, assertionResults.size());
            assertEquals("PASS", assertionResults.get(0).get("status"));
            assertEquals("14.58", assertionResults.get(0).get("resolvedActual"));
            assertEquals("14.58", assertionResults.get(0).get("resolvedExpected"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeModeFiltersUnorderedResponseIntoTwoNumericPayloadFields() throws Exception {
        AtomicReference<String> consumerRequest = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/filtered-source", exchange -> json(exchange, 200,
                "[{\"type\":\"OTHER\",\"active\":true,\"id\":8,\"label\":\"Other\"},"
                        + "{\"type\":\"TARGET\",\"active\":true,\"id\":7,\"label\":\"Selected\"}]"));
        server.createContext("/api/test/v1/filtered-consumer", exchange -> {
            consumerRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(exchange, 200, "{\"accepted\":true}");
        });
        server.start();
        try {
            writeFilteredFixture();
            ExecutionReport report = new ExecuteEngine(filteredConfig(server)).runForManifest("APIsToBeValidated_Filtered.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(2, report.stepsPassed);
            assertEquals(0, report.stepsFailed);
            JsonNode request = new ObjectMapper().readTree(consumerRequest.get());
            assertTrue(request.path("outerId").isInt(), consumerRequest.get());
            assertEquals(7, request.path("outerId").asInt());
            assertTrue(request.path("details").path("innerId").isInt(), consumerRequest.get());
            assertEquals(7, request.path("details").path("innerId").asInt());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeModeMatchesJsonNumberBeyondDoubleRange() throws Exception {
        AtomicReference<String> consumerRequest = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/filtered-source", exchange -> json(exchange, 200,
                "[{\"rank\":1e309,\"id\":7}]"));
        server.createContext("/api/test/v1/filtered-consumer", exchange -> {
            consumerRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(exchange, 200, "{\"accepted\":true}");
        });
        server.start();
        try {
            writeFilteredFixture("{{source.response[?(@.rank == 1e309)].id}}");
            ExecutionReport report = new ExecuteEngine(filteredConfig(server)).runForManifest("APIsToBeValidated_Filtered.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(2, report.stepsPassed);
            assertEquals(0, report.stepsFailed);
            JsonNode request = new ObjectMapper().readTree(consumerRequest.get());
            assertEquals(7, request.path("outerId").asInt());
            assertEquals(7, request.path("details").path("innerId").asInt());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeModePreservesHighPrecisionSelectedValueInOutgoingRequestBody() throws Exception {
        String preciseAmount = "1234567890.12345678901234567890123456789";
        AtomicReference<String> consumerRequest = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/filtered-source", exchange -> json(
                exchange,
                200,
                "[{\"type\":\"TARGET\",\"amount\":" + preciseAmount + "}]"
        ));
        server.createContext("/api/test/v1/filtered-consumer", exchange -> {
            consumerRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(exchange, 200, "{\"accepted\":true}");
        });
        server.start();
        try {
            writeFilteredFixture("{{source.response[?(@.type == 'TARGET')].amount}}");
            ExecutionReport report = new ExecuteEngine(filteredConfig(server)).runForManifest("APIsToBeValidated_Filtered.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(2, report.stepsPassed);
            assertEquals(0, report.stepsFailed);
            assertEquals(
                    "{\"outerId\":" + preciseAmount + ",\"details\":{\"innerId\":" + preciseAmount + "}}",
                    consumerRequest.get()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeModeDoesNotSendConsumerRequestWhenFilterIsAmbiguous() throws Exception {
        assertConsumerRequestSuppressed(
                "[{\"type\":\"TARGET\",\"active\":true,\"id\":7,\"label\":\"First\"},"
                        + "{\"type\":\"TARGET\",\"active\":true,\"id\":8,\"label\":\"Second\"}]",
                "{{source.response[?(@.type == 'TARGET' && @.active == true)].id}}",
                "ambiguous_reference"
        );
    }

    @Test
    void executeModeDoesNotSendConsumerRequestWhenFilterMatchesNothing() throws Exception {
        assertConsumerRequestSuppressed(
                "[{\"type\":\"OTHER\",\"active\":true,\"id\":8}]",
                "{{source.response[?(@.type == 'TARGET' && @.active == true)].id}}",
                "missing_reference"
        );
    }

    @Test
    void executeModeDoesNotSendConsumerRequestWhenFilterIsMalformed() throws Exception {
        assertConsumerRequestSuppressed(
                "[{\"type\":\"TARGET\",\"active\":true,\"id\":7}]",
                "{{source.response[?(@.rank == +1)].id}}",
                "malformed_reference"
        );
    }

    @Test
    void executeModeDoesNotSendConsumerRequestForAnyMalformedFilterIntent() throws Exception {
        for (String reference : List.of(
                "{{source.response[?@.type == 'TARGET')].id}}",
                "{{source.response[? (@.type == 'TARGET')].id}}"
        )) {
            assertConsumerRequestSuppressed(
                    "[{\"type\":\"TARGET\",\"active\":true,\"id\":7}]",
                    reference,
                    "malformed_reference"
            );
        }
    }

    @Test
    void executeModeLogsSafeContextWhenSourceResponseIsInvalidJson() throws Exception {
        AtomicInteger consumerCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/filtered-source", exchange -> json(
                exchange,
                200,
                "{\"secret\":\"DO_NOT_LOG_RESPONSE_BODY\",\"id\":}"
        ));
        server.createContext("/api/test/v1/filtered-consumer", exchange -> {
            consumerCalls.incrementAndGet();
            json(exchange, 200, "{\"accepted\":true}");
        });
        server.start();

        ByteArrayOutputStream capturedLogs = new ByteArrayOutputStream();
        PrintStream originalError = System.err;
        ExecutionReport report;
        try {
            writeFilteredFixture("{{source.response.id}}");
            try (PrintStream capture = new PrintStream(capturedLogs, true, StandardCharsets.UTF_8)) {
                System.setErr(capture);
                try {
                    report = new ExecuteEngine(filteredConfig(server))
                            .runForManifest("APIsToBeValidated_Filtered.csv");
                } finally {
                    System.setErr(originalError);
                }
            }
        } finally {
            System.setErr(originalError);
            server.stop(0);
        }

        String logOutput = capturedLogs.toString(StandardCharsets.UTF_8);
        assertEquals(2, report.stepsExecuted);
        assertEquals(0, consumerCalls.get());
        assertTrue(logOutput.contains("Failed parsing JSON response api=FilteredApi step=1"), logOutput);
        assertTrue(logOutput.contains("error=JsonParseException line=1 column="), logOutput);
        assertFalse(logOutput.contains("DO_NOT_LOG_RESPONSE_BODY"), logOutput);
    }

    private void assertConsumerRequestSuppressed(String sourceBody, String reference, String expectedKind) throws Exception {
        AtomicInteger consumerCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/filtered-source", exchange -> json(exchange, 200, sourceBody));
        server.createContext("/api/test/v1/filtered-consumer", exchange -> {
            consumerCalls.incrementAndGet();
            json(exchange, 200, "{\"accepted\":true}");
        });
        server.start();
        try {
            writeFilteredFixture(reference);
            ExecutionReport report = new ExecuteEngine(filteredConfig(server)).runForManifest("APIsToBeValidated_Filtered.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(1, report.stepsPassed);
            assertEquals(1, report.stepsFailed);
            assertEquals(0, consumerCalls.get());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> comparison = (List<Map<String, Object>>) report.failures.get(0).get("comparison");
            assertEquals(expectedKind, comparison.get(0).get("kind"));
        } finally {
            server.stop(0);
        }
    }

    private Config filteredConfig(HttpServer server) {
        return Config.load(new String[] {
                "--projectRoot=" + tempDir,
                "--mode=execute",
                "--manifests=APIsToBeValidated_Filtered.csv",
                "--apis=FilteredApi",
                "--protocol=http",
                "--server=localhost:" + server.getAddress().getPort(),
                "--username=user",
                "--password=password",
                "--tenant=tenant",
                "--responseChaining.enabled=true"
        });
    }

    private void writeFilteredFixture() throws IOException {
        writeFilteredFixture("{{source.response[?(@.type == 'TARGET' && @.active == true)].id}}");
    }

    private void writeFilteredFixture(String reference) throws IOException {
        Files.createDirectories(tempDir.resolve("apisToBeValidated"));
        Files.createDirectories(tempDir.resolve("apiInputs"));
        Files.createDirectories(tempDir.resolve("fileFromJson/payloadJSON"));
        Files.createDirectories(tempDir.resolve("fileFromJson/expectedJSON"));
        Files.createDirectories(tempDir.resolve("java-regression-runner"));

        Files.writeString(
                tempDir.resolve("apisToBeValidated/APIsToBeValidated_Filtered.csv"),
                "#,apiName,apiMethod,apiPath,apiInputsFile,toBeValidated,Module,fileType,payloadJSON,expectedJSON,consentPerson,scenario,mysql,BusinessFunction\n"
                        + "1,FilteredApi,NA,NA,FilteredApi.csv,y,Module,json,FilteredPayload.json,FilteredExpected.json,Owner,y,n,NA\n"
        );
        Files.writeString(
                tempDir.resolve("apiInputs/FilteredApi.csv"),
                "testScenarioNumber,testObjective,urlParameter,expectedResponseCode\n"
                        + "1,source,NA,200\n"
                        + "2,consumer,NA,200\n"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/payloadJSON/FilteredPayload.json"),
                "["
                        + "{\"apiName\":\"Source\",\"apiMethod\":\"GET\",\"apiPath\":\"/api/test/v1/filtered-source\",\"stepId\":\"source\",\"payload\":[],\"TestStresserFlg\":\"N\"},"
                        + "{\"apiName\":\"Consumer\",\"apiMethod\":\"POST\",\"apiPath\":\"/api/test/v1/filtered-consumer\",\"payload\":{\"outerId\":\"" + reference + "\",\"details\":{\"innerId\":\"" + reference + "\"}},\"TestStresserFlg\":\"N\"}"
                        + "]"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/expectedJSON/FilteredExpected.json"),
                "["
                        + "{\"apiName\":\"Source\",\"expected\":{}},"
                        + "{\"apiName\":\"Consumer\",\"expected\":{\"accepted\":true}}"
                        + "]"
        );
    }

    private void writeFixture() throws IOException {
        Files.createDirectories(tempDir.resolve("apisToBeValidated"));
        Files.createDirectories(tempDir.resolve("apiInputs"));
        Files.createDirectories(tempDir.resolve("fileFromJson/payloadJSON"));
        Files.createDirectories(tempDir.resolve("fileFromJson/expectedJSON"));
        Files.createDirectories(tempDir.resolve("java-regression-runner"));

        Files.writeString(
                tempDir.resolve("apisToBeValidated/APIsToBeValidated_A.csv"),
                "#,apiName,apiMethod,apiPath,apiInputsFile,toBeValidated,Module,fileType,payloadJSON,expectedJSON,consentPerson,scenario,mysql,BusinessFunction\n"
                        + "1,ChainedApi,NA,NA,ChainedApi.csv,y,Module,json,ChainedPayload.json,ChainedExpected.json,Owner,y,n,NA\n"
        );
        Files.writeString(
                tempDir.resolve("apiInputs/ChainedApi.csv"),
                "testScenarioNumber,testObjective,urlParameter,expectedResponseCode\n"
                        + "1,source,NA,200\n"
                        + "2,echo,NA,200\n"
                        + "3,ianp,NA,200\n"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/payloadJSON/ChainedPayload.json"),
                "["
                        + "{\"apiName\":\"Source\",\"apiMethod\":\"GET\",\"apiPath\":\"/api/test/v1/source\",\"stepId\":\"source\",\"payload\":[],\"TestStresserFlg\":\"N\"},"
                        + "{\"apiName\":\"Echo\",\"apiMethod\":\"POST\",\"apiPath\":\"/api/test/v1/echo\",\"stepId\":\"echo\",\"payload\":{\"loanId\":\"{{source.response.id}}\"},\"TestStresserFlg\":\"N\"},"
                        + "{\"apiName\":\"Ianp\",\"apiMethod\":\"GET\",\"apiPath\":\"/api/test/v1/ianp\",\"stepId\":\"ianp\",\"payload\":[],\"TestStresserFlg\":\"N\"}"
                        + "]"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/expectedJSON/ChainedExpected.json"),
                "["
                        + "{\"apiName\":\"Source\",\"expected\":{\"id\":123,\"bookedInterestUnpaid\":14.58}},"
                        + "{\"apiName\":\"Echo\",\"expected\":{\"received\":123}},"
                        + "{\"apiName\":\"Ianp\",\"expected\":{},\"assertions\":[{\"name\":\"booked-equals-ianp\",\"actualPath\":\"interestAccruedNotDueNotPaid\",\"expected\":\"{{source.response.bookedInterestUnpaid}}\",\"type\":\"strictDecimal\"}]}"
                        + "]"
        );
    }

    private void writeSourceComparisonFailureFixture() throws IOException {
        Files.createDirectories(tempDir.resolve("apisToBeValidated"));
        Files.createDirectories(tempDir.resolve("apiInputs"));
        Files.createDirectories(tempDir.resolve("fileFromJson/payloadJSON"));
        Files.createDirectories(tempDir.resolve("fileFromJson/expectedJSON"));
        Files.createDirectories(tempDir.resolve("java-regression-runner"));

        Files.writeString(
                tempDir.resolve("apisToBeValidated/APIsToBeValidated_SourceMismatch.csv"),
                "#,apiName,apiMethod,apiPath,apiInputsFile,toBeValidated,Module,fileType,payloadJSON,expectedJSON,consentPerson,scenario,mysql,BusinessFunction\n"
                        + "1,SourceMismatchApi,NA,NA,SourceMismatchApi.csv,y,Module,json,SourceMismatchPayload.json,SourceMismatchExpected.json,Owner,y,n,NA\n"
        );
        Files.writeString(
                tempDir.resolve("apiInputs/SourceMismatchApi.csv"),
                "testScenarioNumber,testObjective,urlParameter,expectedResponseCode\n"
                        + "1,source,NA,200\n"
                        + "2,ianp,NA,200\n"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/payloadJSON/SourceMismatchPayload.json"),
                "["
                        + "{\"apiName\":\"Source\",\"apiMethod\":\"GET\",\"apiPath\":\"/api/test/v1/source\",\"stepId\":\"source\",\"payload\":[],\"TestStresserFlg\":\"N\"},"
                        + "{\"apiName\":\"Ianp\",\"apiMethod\":\"GET\",\"apiPath\":\"/api/test/v1/ianp\",\"stepId\":\"ianp\",\"payload\":[],\"TestStresserFlg\":\"N\"}"
                        + "]"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/expectedJSON/SourceMismatchExpected.json"),
                "["
                        + "{\"apiName\":\"Source\",\"expected\":{\"id\":999,\"bookedInterestUnpaid\":14.58}},"
                        + "{\"apiName\":\"Ianp\",\"expected\":{},\"assertions\":[{\"name\":\"booked-equals-ianp\",\"actualPath\":\"interestAccruedNotDueNotPaid\",\"expected\":\"{{source.response.bookedInterestUnpaid}}\",\"type\":\"strictDecimal\"}]}"
                        + "]"
        );
    }

    private void writeCsvUrlParameterFixture() throws IOException {
        Files.createDirectories(tempDir.resolve("apisToBeValidated"));
        Files.createDirectories(tempDir.resolve("apiInputs"));
        Files.createDirectories(tempDir.resolve("fileFromJson/payloadJSON"));
        Files.createDirectories(tempDir.resolve("fileFromJson/expectedJSON"));
        Files.createDirectories(tempDir.resolve("java-regression-runner"));

        Files.writeString(
                tempDir.resolve("apisToBeValidated/APIsToBeValidated_Url.csv"),
                "#,apiName,apiMethod,apiPath,apiInputsFile,toBeValidated,Module,fileType,payloadJSON,expectedJSON,consentPerson,scenario,mysql,BusinessFunction\n"
                        + "1,UrlChainedApi,NA,NA,UrlPayload.json,y,Module,json,UrlPayload.json,UrlExpected.json,Owner,y,n,NA\n"
        );
        Files.writeString(
                tempDir.resolve("apiInputs/UrlPayload.json"),
                "testScenarioNumber,testObjective,urlParameter,expectedResponseCode\n"
                        + "1,source,NA,200\n"
                        + "2,loan,{{step1.response.id}},200\n"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/payloadJSON/UrlPayload.json"),
                "["
                        + "{\"apiName\":\"Source\",\"apiMethod\":\"GET\",\"apiPath\":\"/api/test/v1/source\",\"payload\":[],\"TestStresserFlg\":\"N\"},"
                        + "{\"apiName\":\"Loan\",\"apiMethod\":\"GET\",\"apiPath\":\"/api/test/v1/loans\",\"payload\":[],\"TestStresserFlg\":\"N\"}"
                        + "]"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/expectedJSON/UrlExpected.json"),
                "["
                        + "{\"apiName\":\"Source\",\"expected\":{\"id\":123}},"
                        + "{\"apiName\":\"Loan\",\"expected\":{\"loaded\":123}}"
                        + "]"
        );
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
