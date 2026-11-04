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

class MixedMysqlHttpExecuteEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void executeModeDispatchesHttpAndMysqlStepsFromTheSameApiInputsFile() throws Exception {
        String dbUrl = "jdbc:h2:mem:mixed_dispatch;MODE=MySQL;DB_CLOSE_DELAY=-1";
        seedDatabase(dbUrl);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/loan", exchange -> json(exchange, 200, "{\"id\":5758}"));
        server.start();
        try {
            writeMixedFixture(false);
            ExecutionReport report = new ExecuteEngine(mixedConfig(server, dbUrl, false))
                    .runForManifest("APIsToBeValidated_Mixed.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(2, report.stepsPassed);
            assertEquals(0, report.stepsFailed);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeModeAllowsABareStepIdOnAMysqlStepWithNoTemplateTokens() throws Exception {
        String dbUrl = "jdbc:h2:mem:mixed_dispatch_chaining;MODE=MySQL;DB_CLOSE_DELAY=-1";
        seedDatabase(dbUrl);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/loan", exchange -> json(exchange, 200, "{\"id\":5758}"));
        server.start();
        try {
            writeMixedFixture(true);
            ExecutionReport report = new ExecuteEngine(mixedConfig(server, dbUrl, true))
                    .runForManifest("APIsToBeValidated_Mixed.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(2, report.stepsPassed);
            assertEquals(0, report.stepsFailed);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeModeChainsHttpResponseIdIntoAMysqlWhereClause() throws Exception {
        String dbUrl = "jdbc:h2:mem:mixed_dispatch_id_chain;MODE=MySQL;DB_CLOSE_DELAY=-1";
        seedDatabaseWithDecoyRow(dbUrl);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/loan", exchange -> json(exchange, 200, "{\"id\":5758}"));
        server.start();
        try {
            writeIdChainedFixture();
            ExecutionReport report = new ExecuteEngine(mixedConfig(server, dbUrl, true))
                    .runForManifest("APIsToBeValidated_Mixed.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(2, report.stepsPassed);
            assertEquals(0, report.stepsFailed);
            assertEquals(0, report.failures.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeModeChainsHttpResponseNonIdFieldIntoAMysqlWhereClause() throws Exception {
        String dbUrl = "jdbc:h2:mem:mixed_dispatch_external_ref_chain;MODE=MySQL;DB_CLOSE_DELAY=-1";
        seedDatabaseWithExternalRef(dbUrl);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/loan",
                exchange -> json(exchange, 200, "{\"id\":5758,\"externalRef\":\"LN-5758-ABC\"}"));
        server.start();
        try {
            writeExternalRefChainedFixture();
            ExecutionReport report = new ExecuteEngine(mixedConfig(server, dbUrl, true))
                    .runForManifest("APIsToBeValidated_Mixed.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(2, report.stepsPassed);
            assertEquals(0, report.stepsFailed);
            assertEquals(0, report.failures.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeModeBlocksMysqlTemplateTokensAndIssuesNoQueryWhenResponseChainingIsDisabled() throws Exception {
        String dbUrl = "jdbc:h2:mem:mixed_dispatch_disabled_unreachable;IFEXISTS=TRUE;DB_CLOSE_DELAY=-1";

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/loan", exchange -> json(exchange, 200, "{\"id\":5758}"));
        server.start();
        try {
            writeIdChainedFixtureWithoutHttpStepId();
            ExecutionReport report = new ExecuteEngine(mixedConfig(server, dbUrl, false))
                    .runForManifest("APIsToBeValidated_Mixed.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(1, report.stepsPassed);
            assertEquals(1, report.stepsFailed);
            assertEquals(1, report.failures.size());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> comparison = (List<Map<String, Object>>) report.failures.get(0).get("comparison");
            assertEquals("response_chaining_disabled", comparison.get(0).get("kind"));
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

    private void seedDatabaseWithDecoyRow(String dbUrl) throws Exception {
        try (Connection connection = DriverManager.getConnection(dbUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table loan_fee (loan_id int primary key, fee_id int)");
            statement.execute("insert into loan_fee (loan_id, fee_id) values (5758, 108)");
            statement.execute("insert into loan_fee (loan_id, fee_id) values (9999, 999)");
        }
    }

    private void seedDatabaseWithExternalRef(String dbUrl) throws Exception {
        try (Connection connection = DriverManager.getConnection(dbUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table loan_fee (loan_id int primary key, fee_id int, external_ref varchar(64))");
            statement.execute("insert into loan_fee (loan_id, fee_id, external_ref) values (5758, 108, 'LN-5758-ABC')");
            statement.execute("insert into loan_fee (loan_id, fee_id, external_ref) values (9999, 999, 'LN-9999-XYZ')");
        }
    }

    private Config mixedConfig(HttpServer server, String dbUrl, boolean responseChainingEnabled) {
        return Config.load(new String[] {
                "--projectRoot=" + tempDir,
                "--mode=execute",
                "--manifests=APIsToBeValidated_Mixed.csv",
                "--apis=MixedApi",
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

    private void writeMixedFixture(boolean dbStepCarriesChainingMetadata) throws IOException {
        Files.createDirectories(tempDir.resolve("apisToBeValidated"));
        Files.createDirectories(tempDir.resolve("apiInputs"));
        Files.createDirectories(tempDir.resolve("fileFromJson/payloadJSON"));
        Files.createDirectories(tempDir.resolve("fileFromJson/expectedJSON"));
        Files.createDirectories(tempDir.resolve("java-regression-runner"));

        Files.writeString(
                tempDir.resolve("apisToBeValidated/APIsToBeValidated_Mixed.csv"),
                "#,apiName,apiMethod,apiPath,apiInputsFile,toBeValidated,Module,fileType,payloadJSON,expectedJSON,consentPerson,scenario,mysql,BusinessFunction\n"
                        + "1,MixedApi,NA,NA,MixedApi.csv,y,Module,json,MixedPayload.json,MixedExpected.json,Owner,n,n,NA\n"
        );
        Files.writeString(
                tempDir.resolve("apiInputs/MixedApi.csv"),
                "testScenarioNumber,testObjective,urlParameter,expectedResponseCode\n"
                        + "1,fetchLoan,NA,200\n"
                        + "2,checkLoanFee,NA,200\n"
        );
        String dbStepIdField = dbStepCarriesChainingMetadata ? ",\"stepId\":\"checkLoanFee\"" : "";
        Files.writeString(
                tempDir.resolve("fileFromJson/payloadJSON/MixedPayload.json"),
                "["
                        + "{\"apiName\":\"FetchLoan\",\"apiMethod\":\"GET\",\"apiPath\":\"/api/test/v1/loan\",\"payload\":[],\"TestStresserFlg\":\"N\"},"
                        + "{\"queryName\":\"CheckLoanFee\",\"mysqlQuery\":\"select fee_id from loan_fee where loan_id = 5758\"" + dbStepIdField + "}"
                        + "]"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/expectedJSON/MixedExpected.json"),
                "["
                        + "{\"apiName\":\"FetchLoan\",\"expected\":{\"id\":5758}},"
                        + "{\"queryName\":\"CheckLoanFee\",\"expectedTableValue\":[108]}"
                        + "]"
        );
    }

    private void writeChainedManifestAndInputs() throws IOException {
        Files.createDirectories(tempDir.resolve("apisToBeValidated"));
        Files.createDirectories(tempDir.resolve("apiInputs"));
        Files.createDirectories(tempDir.resolve("fileFromJson/payloadJSON"));
        Files.createDirectories(tempDir.resolve("fileFromJson/expectedJSON"));
        Files.createDirectories(tempDir.resolve("java-regression-runner"));

        Files.writeString(
                tempDir.resolve("apisToBeValidated/APIsToBeValidated_Mixed.csv"),
                "#,apiName,apiMethod,apiPath,apiInputsFile,toBeValidated,Module,fileType,payloadJSON,expectedJSON,consentPerson,scenario,mysql,BusinessFunction\n"
                        + "1,MixedApi,NA,NA,MixedApi.csv,y,Module,json,MixedPayload.json,MixedExpected.json,Owner,n,n,NA\n"
        );
        Files.writeString(
                tempDir.resolve("apiInputs/MixedApi.csv"),
                "testScenarioNumber,testObjective,urlParameter,expectedResponseCode\n"
                        + "1,fetchLoan,NA,200\n"
                        + "2,checkLoanFee,NA,200\n"
        );
    }

    private void writeIdChainedFixture() throws IOException {
        writeChainedManifestAndInputs();
        Files.writeString(
                tempDir.resolve("fileFromJson/payloadJSON/MixedPayload.json"),
                "["
                        + "{\"apiName\":\"FetchLoan\",\"apiMethod\":\"GET\",\"apiPath\":\"/api/test/v1/loan\",\"payload\":[],\"TestStresserFlg\":\"N\",\"stepId\":\"fetchLoan\"},"
                        + "{\"queryName\":\"CheckLoanFee\",\"mysqlQuery\":\"select fee_id from loan_fee where loan_id = {{fetchLoan.response.id}}\"}"
                        + "]"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/expectedJSON/MixedExpected.json"),
                "["
                        + "{\"apiName\":\"FetchLoan\",\"expected\":{\"id\":5758}},"
                        + "{\"queryName\":\"CheckLoanFee\",\"expectedTableValue\":[108]}"
                        + "]"
        );
    }

    private void writeIdChainedFixtureWithoutHttpStepId() throws IOException {
        writeChainedManifestAndInputs();
        Files.writeString(
                tempDir.resolve("fileFromJson/payloadJSON/MixedPayload.json"),
                "["
                        + "{\"apiName\":\"FetchLoan\",\"apiMethod\":\"GET\",\"apiPath\":\"/api/test/v1/loan\",\"payload\":[],\"TestStresserFlg\":\"N\"},"
                        + "{\"queryName\":\"CheckLoanFee\",\"mysqlQuery\":\"select fee_id from loan_fee where loan_id = {{fetchLoan.response.id}}\"}"
                        + "]"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/expectedJSON/MixedExpected.json"),
                "["
                        + "{\"apiName\":\"FetchLoan\",\"expected\":{\"id\":5758}},"
                        + "{\"queryName\":\"CheckLoanFee\",\"expectedTableValue\":[108]}"
                        + "]"
        );
    }

    private void writeExternalRefChainedFixture() throws IOException {
        writeChainedManifestAndInputs();
        Files.writeString(
                tempDir.resolve("fileFromJson/payloadJSON/MixedPayload.json"),
                "["
                        + "{\"apiName\":\"FetchLoan\",\"apiMethod\":\"GET\",\"apiPath\":\"/api/test/v1/loan\",\"payload\":[],\"TestStresserFlg\":\"N\",\"stepId\":\"fetchLoan\"},"
                        + "{\"queryName\":\"CheckLoanFee\",\"mysqlQuery\":"
                        + "\"select fee_id from loan_fee where external_ref = {{fetchLoan.response.externalRef}}\"}"
                        + "]"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/expectedJSON/MixedExpected.json"),
                "["
                        + "{\"apiName\":\"FetchLoan\",\"expected\":{\"id\":5758,\"externalRef\":\"LN-5758-ABC\"}},"
                        + "{\"queryName\":\"CheckLoanFee\",\"expectedTableValue\":[108]}"
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
