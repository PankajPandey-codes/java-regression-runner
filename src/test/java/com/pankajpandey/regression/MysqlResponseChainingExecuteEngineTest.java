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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlResponseChainingExecuteEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void executeModeResolvesAndBindsChainedTokenInsideMysqlQuery() throws Exception {
        String dbUrl = "jdbc:h2:mem:mysql_chain_bind;MODE=MySQL;DB_CLOSE_DELAY=-1";
        seedDatabase(dbUrl);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/loan", exchange -> json(exchange, 200, "{\"id\":5758}"));
        server.start();
        try {
            writeFixture(
                    "select fee_id from loan_fee where loan_id = {{source.response.id}}",
                    "",
                    "NA",
                    "[108]"
            );
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl, true))
                    .runForManifest("APIsToBeValidated_MysqlChain.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(2, report.stepsPassed);
            assertEquals(0, report.stepsFailed);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeModeRejectsAssertionsOnAMysqlStep() throws Exception {
        String dbUrl = "jdbc:h2:mem:mysql_chain_assertions;MODE=MySQL;DB_CLOSE_DELAY=-1";
        seedDatabase(dbUrl);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/loan", exchange -> json(exchange, 200, "{\"id\":5758}"));
        server.start();
        try {
            writeFixtureWithAssertions();
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl, true))
                    .runForManifest("APIsToBeValidated_MysqlChain.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(1, report.stepsPassed);
            assertEquals(1, report.stepsFailed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> comparison = (List<Map<String, Object>>) report.failures.get(0).get("comparison");
            assertEquals("unsupported_chaining_metadata", comparison.get(0).get("kind"));

            Map<String, Object> failure = report.failures.get(0);
            assertTrue(
                    (int) failure.get("actualCode") < 0,
                    "No query was issued, so the step must carry the negative 'no code' sentinel rather than"
                            + " a value the analysis/rebaseline classifiers read as a status-code mismatch");
            assertEquals("select fee_id from loan_fee where loan_id = 5758", failure.get("payload"));
            String artifactContent = Files.readString(tempDir.resolve((String) failure.get("jsonPayloadArtifact")));
            assertEquals("select fee_id from loan_fee where loan_id = 5758", artifactContent);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeModeRejectsAssertProfilesOnAMysqlStep() throws Exception {
        String dbUrl = "jdbc:h2:mem:mysql_chain_assertprofiles;MODE=MySQL;DB_CLOSE_DELAY=-1";
        seedDatabase(dbUrl);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/loan", exchange -> json(exchange, 200, "{\"id\":5758}"));
        server.start();
        try {
            writeFixtureWithAssertProfiles();
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl, true))
                    .runForManifest("APIsToBeValidated_MysqlChain.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(1, report.stepsPassed);
            assertEquals(1, report.stepsFailed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> comparison = (List<Map<String, Object>>) report.failures.get(0).get("comparison");
            assertEquals("unsupported_chaining_metadata", comparison.get(0).get("kind"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeModeRejectsTemplateTokenInCsvUrlParameterOnAMysqlStep() throws Exception {
        String dbUrl = "jdbc:h2:mem:mysql_chain_urlparam;MODE=MySQL;DB_CLOSE_DELAY=-1";
        seedDatabase(dbUrl);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/loan", exchange -> json(exchange, 200, "{\"id\":5758}"));
        server.start();
        try {
            writeFixture(
                    "select fee_id from loan_fee where loan_id = 5758",
                    "",
                    "{{source.response.id}}",
                    "[108]"
            );
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl, true))
                    .runForManifest("APIsToBeValidated_MysqlChain.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(1, report.stepsPassed);
            assertEquals(1, report.stepsFailed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> comparison = (List<Map<String, Object>>) report.failures.get(0).get("comparison");
            assertEquals("unsupported_chaining_metadata", comparison.get(0).get("kind"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeModeLeavesAPlainMysqlStepWithNoChainingMetadataUnchanged() throws Exception {
        String dbUrl = "jdbc:h2:mem:mysql_chain_plain;MODE=MySQL;DB_CLOSE_DELAY=-1";
        seedDatabase(dbUrl);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/loan", exchange -> json(exchange, 200, "{\"id\":5758}"));
        server.start();
        try {
            writeFixture(
                    "select fee_id from loan_fee where loan_id = 5758",
                    "",
                    "NA",
                    "[108]"
            );
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl, true))
                    .runForManifest("APIsToBeValidated_MysqlChain.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(2, report.stepsPassed);
            assertEquals(0, report.stepsFailed);

            Map<String, Object> mysqlStepPass = report.passes.get(1);
            String artifactContent = Files.readString(
                    tempDir.resolve((String) mysqlStepPass.get("jsonPayloadArtifact")));
            assertEquals("select fee_id from loan_fee where loan_id = 5758", artifactContent);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeModeFailsFastWithoutIssuingAQueryWhenChainingDisabled() throws Exception {
        String dbUrl = "jdbc:h2:mem:mysql_chain_disabled;MODE=MySQL;DB_CLOSE_DELAY=-1";
        // Deliberately do not seed this database: if a query were ever issued
        // against loan_fee, it would fail with a table-not-found error rather
        // than the response_chaining_disabled precondition error asserted below.

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/loan", exchange -> json(exchange, 200, "{\"id\":5758}"));
        server.start();
        try {
            writeFixture(
                    "select fee_id from loan_fee where loan_id = {{source.response.id}}",
                    "",
                    "NA",
                    "[108]"
            );
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl, false))
                    .runForManifest("APIsToBeValidated_MysqlChain.csv");

            // The HTTP producer step also carries a bare stepId (needed so the mysql
            // step below can reference it), so with chaining disabled it is rejected
            // pre-request too -- both steps fail fast, and critically the mysql step
            // never reaches dbClient (an unseeded DB would otherwise surface a
            // table-not-found error instead of this precondition error).
            assertEquals(2, report.stepsExecuted);
            assertEquals(0, report.stepsPassed);
            assertEquals(2, report.stepsFailed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mysqlStepComparison = (List<Map<String, Object>>) report.failures.get(1).get("comparison");
            assertEquals("response_chaining_disabled", mysqlStepComparison.get(0).get("kind"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeModeFailsChainedMysqlStepWithEmptyDbSourceEvenWhenExpectedTableValueIsEmpty() throws Exception {
        String dbUrl = "jdbc:h2:mem:mysql_chain_empty_source;MODE=MySQL;DB_CLOSE_DELAY=-1";
        seedDatabaseWithNoMatchingLoan(dbUrl);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/loan", exchange -> json(exchange, 200, "{\"id\":5758}"));
        server.start();
        try {
            writeFixture(
                    "select fee_id from loan_fee where loan_id = {{source.response.id}}",
                    "",
                    "NA",
                    "[]"
            );
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl, true))
                    .runForManifest("APIsToBeValidated_MysqlChain.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(1, report.stepsPassed);
            assertEquals(1, report.stepsFailed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> comparison = (List<Map<String, Object>>) report.failures.get(0).get("comparison");
            assertEquals("empty_db_source", comparison.get(0).get("kind"));

            Map<String, Object> failure = report.failures.get(0);
            JsonNode artifact = new ObjectMapper().readTree(
                    Files.readString(tempDir.resolve((String) failure.get("jsonPayloadArtifact"))));
            assertEquals("select fee_id from loan_fee where loan_id = ?", artifact.get("sql").asText());
            assertEquals(1, artifact.get("boundParameters").size());
            assertEquals(5758, artifact.get("boundParameters").get(0).asInt());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeModeLeavesUnchainedMysqlStepWithEmptyResultAndEmptyExpectedTableValuePassing() throws Exception {
        String dbUrl = "jdbc:h2:mem:mysql_chain_empty_unchained;MODE=MySQL;DB_CLOSE_DELAY=-1";
        seedDatabase(dbUrl);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/loan", exchange -> json(exchange, 200, "{\"id\":5758}"));
        server.start();
        try {
            writeFixture(
                    "select fee_id from loan_fee where loan_id = 9999",
                    "",
                    "NA",
                    "[]"
            );
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl, true))
                    .runForManifest("APIsToBeValidated_MysqlChain.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(2, report.stepsPassed);
            assertEquals(0, report.stepsFailed);
        } finally {
            server.stop(0);
        }
    }

    private void seedDatabase(String dbUrl) throws Exception {
        try (Connection connection = DriverManager.getConnection(dbUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table loan_fee (loan_id int primary key, fee_id int)");
            statement.execute("insert into loan_fee (loan_id, fee_id) values (5758, 108)");
        }
    }

    private void seedDatabaseWithNoMatchingLoan(String dbUrl) throws Exception {
        try (Connection connection = DriverManager.getConnection(dbUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table loan_fee (loan_id int primary key, fee_id int)");
            statement.execute("insert into loan_fee (loan_id, fee_id) values (9999, 108)");
        }
    }

    private Config config(HttpServer server, String dbUrl, boolean responseChainingEnabled) {
        return Config.load(new String[] {
                "--projectRoot=" + tempDir,
                "--mode=execute",
                "--manifests=APIsToBeValidated_MysqlChain.csv",
                "--apis=MysqlChainApi",
                "--protocol=http",
                "--server=localhost:" + server.getAddress().getPort(),
                "--username=user",
                "--password=password",
                "--tenant=tenant",
                "--dbUrl=" + dbUrl,
                "--dbUsername=sa",
                "--dbPassword=",
                "--dbDriver=org.h2.Driver",
                "--responseChaining.enabled=" + responseChainingEnabled
        });
    }

    private void writeFixture(String mysqlQuery, String extraMysqlStepFields, String urlParameterStep2, String expectedTableValue) throws IOException {
        Files.createDirectories(tempDir.resolve("apisToBeValidated"));
        Files.createDirectories(tempDir.resolve("apiInputs"));
        Files.createDirectories(tempDir.resolve("fileFromJson/payloadJSON"));
        Files.createDirectories(tempDir.resolve("fileFromJson/expectedJSON"));
        Files.createDirectories(tempDir.resolve("java-regression-runner"));

        Files.writeString(
                tempDir.resolve("apisToBeValidated/APIsToBeValidated_MysqlChain.csv"),
                "#,apiName,apiMethod,apiPath,apiInputsFile,toBeValidated,Module,fileType,payloadJSON,expectedJSON,consentPerson,scenario,mysql,BusinessFunction\n"
                        + "1,MysqlChainApi,NA,NA,MysqlChainApi.csv,y,Module,json,MysqlChainPayload.json,MysqlChainExpected.json,Owner,y,n,NA\n"
        );
        Files.writeString(
                tempDir.resolve("apiInputs/MysqlChainApi.csv"),
                "testScenarioNumber,testObjective,urlParameter,expectedResponseCode\n"
                        + "1,fetchLoan,NA,200\n"
                        + "2,checkLoanFee," + urlParameterStep2 + ",200\n"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/payloadJSON/MysqlChainPayload.json"),
                "["
                        + "{\"apiName\":\"FetchLoan\",\"apiMethod\":\"GET\",\"apiPath\":\"/api/test/v1/loan\",\"stepId\":\"source\",\"payload\":[],\"TestStresserFlg\":\"N\"},"
                        + "{\"queryName\":\"CheckLoanFee\",\"mysqlQuery\":\"" + mysqlQuery + "\"" + extraMysqlStepFields + "}"
                        + "]"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/expectedJSON/MysqlChainExpected.json"),
                "["
                        + "{\"apiName\":\"FetchLoan\",\"expected\":{\"id\":5758}},"
                        + "{\"queryName\":\"CheckLoanFee\",\"expectedTableValue\":" + expectedTableValue + "}"
                        + "]"
        );
    }

    private void writeFixtureWithAssertions() throws IOException {
        Files.createDirectories(tempDir.resolve("apisToBeValidated"));
        Files.createDirectories(tempDir.resolve("apiInputs"));
        Files.createDirectories(tempDir.resolve("fileFromJson/payloadJSON"));
        Files.createDirectories(tempDir.resolve("fileFromJson/expectedJSON"));
        Files.createDirectories(tempDir.resolve("java-regression-runner"));

        Files.writeString(
                tempDir.resolve("apisToBeValidated/APIsToBeValidated_MysqlChain.csv"),
                "#,apiName,apiMethod,apiPath,apiInputsFile,toBeValidated,Module,fileType,payloadJSON,expectedJSON,consentPerson,scenario,mysql,BusinessFunction\n"
                        + "1,MysqlChainApi,NA,NA,MysqlChainApi.csv,y,Module,json,MysqlChainPayload.json,MysqlChainExpected.json,Owner,y,n,NA\n"
        );
        Files.writeString(
                tempDir.resolve("apiInputs/MysqlChainApi.csv"),
                "testScenarioNumber,testObjective,urlParameter,expectedResponseCode\n"
                        + "1,fetchLoan,NA,200\n"
                        + "2,checkLoanFee,NA,200\n"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/payloadJSON/MysqlChainPayload.json"),
                "["
                        + "{\"apiName\":\"FetchLoan\",\"apiMethod\":\"GET\",\"apiPath\":\"/api/test/v1/loan\",\"stepId\":\"source\",\"payload\":[],\"TestStresserFlg\":\"N\"},"
                        + "{\"queryName\":\"CheckLoanFee\",\"mysqlQuery\":\"select fee_id from loan_fee where loan_id = 5758\"}"
                        + "]"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/expectedJSON/MysqlChainExpected.json"),
                "["
                        + "{\"apiName\":\"FetchLoan\",\"expected\":{\"id\":5758}},"
                        + "{\"queryName\":\"CheckLoanFee\",\"expectedTableValue\":[108],"
                        + "\"assertions\":[{\"path\":\"$\",\"operator\":\"equals\",\"expected\":\"108\"}]}"
                        + "]"
        );
    }

    private void writeFixtureWithAssertProfiles() throws IOException {
        Files.createDirectories(tempDir.resolve("apisToBeValidated"));
        Files.createDirectories(tempDir.resolve("apiInputs"));
        Files.createDirectories(tempDir.resolve("fileFromJson/payloadJSON"));
        Files.createDirectories(tempDir.resolve("fileFromJson/expectedJSON"));
        Files.createDirectories(tempDir.resolve("java-regression-runner"));

        Files.writeString(
                tempDir.resolve("apisToBeValidated/APIsToBeValidated_MysqlChain.csv"),
                "#,apiName,apiMethod,apiPath,apiInputsFile,toBeValidated,Module,fileType,payloadJSON,expectedJSON,consentPerson,scenario,mysql,BusinessFunction\n"
                        + "1,MysqlChainApi,NA,NA,MysqlChainApi.csv,y,Module,json,MysqlChainPayload.json,MysqlChainExpected.json,Owner,y,n,NA\n"
        );
        Files.writeString(
                tempDir.resolve("apiInputs/MysqlChainApi.csv"),
                "testScenarioNumber,testObjective,urlParameter,expectedResponseCode\n"
                        + "1,fetchLoan,NA,200\n"
                        + "2,checkLoanFee,NA,200\n"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/payloadJSON/MysqlChainPayload.json"),
                "["
                        + "{\"apiName\":\"FetchLoan\",\"apiMethod\":\"GET\",\"apiPath\":\"/api/test/v1/loan\",\"stepId\":\"source\",\"payload\":[],\"TestStresserFlg\":\"N\"},"
                        + "{\"queryName\":\"CheckLoanFee\",\"mysqlQuery\":\"select fee_id from loan_fee where loan_id = 5758\"}"
                        + "]"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/expectedJSON/MysqlChainExpected.json"),
                "["
                        + "{\"apiName\":\"FetchLoan\",\"expected\":{\"id\":5758}},"
                        + "{\"queryName\":\"CheckLoanFee\",\"expectedTableValue\":[108],"
                        + "\"assertProfiles\":[\"feeIdMatchesProfile\"]}"
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
