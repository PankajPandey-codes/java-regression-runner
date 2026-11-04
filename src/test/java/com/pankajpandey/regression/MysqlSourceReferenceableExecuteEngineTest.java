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

/**
 * Task 7: proves a mysql step can become a chaining SOURCE.
 *
 * These tests exercise the wiring end-to-end (a later step successfully resolves
 * {{someDbStep.response...}}), not the path-selection rules (Task 8) or the
 * memory-bound stepId-only gating (Task 9).
 */
class MysqlSourceReferenceableExecuteEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void dbStepWithStepIdAndNoTemplateBecomesReferenceableForALaterMysqlStep() throws Exception {
        String dbUrl = "jdbc:h2:mem:mysql_source_ref_ok;MODE=MySQL;DB_CLOSE_DELAY=-1";
        seedDatabase(dbUrl);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/loan", exchange -> json(exchange, 200, "{\"id\":5758}"));
        server.start();
        try {
            writeThreeStepFixture(
                    "select fee_id from loan_fee where loan_id = 5758",
                    "[108]",
                    "select fee_id from loan_fee where fee_id = {{dbFee.response[0]}}",
                    "[108]"
            );
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl, true, false))
                    .runForManifest("APIsToBeValidated_MysqlSourceRef.csv");

            assertEquals(3, report.stepsExecuted);
            assertEquals(3, report.stepsPassed);
            assertEquals(0, report.stepsFailed);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedDbStepIsNotReferenceableForALaterMysqlStep() throws Exception {
        String dbUrl = "jdbc:h2:mem:mysql_source_ref_failed;MODE=MySQL;DB_CLOSE_DELAY=-1";
        seedDatabase(dbUrl);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/loan", exchange -> json(exchange, 200, "{\"id\":5758}"));
        server.start();
        try {
            writeThreeStepFixture(
                    "select fee_id from loan_fee where loan_id = 5758",
                    "[999]",
                    "select fee_id from loan_fee where fee_id = {{dbFee.response[0]}}",
                    "[108]"
            );
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl, true, false))
                    .runForManifest("APIsToBeValidated_MysqlSourceRef.csv");

            assertEquals(3, report.stepsExecuted);
            assertEquals(1, report.stepsPassed);
            assertEquals(2, report.stepsFailed);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> dbFeeComparison = (List<Map<String, Object>>) report.failures.get(0).get("comparison");
            assertEquals("value_mismatch", dbFeeComparison.get(0).get("kind"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> consumerComparison = (List<Map<String, Object>>) report.failures.get(1).get("comparison");
            assertEquals("dependency_failed", consumerComparison.get(0).get("kind"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void plainMysqlStepWithNoChainingMetadataAnywhereInFileIsUnaffected() throws Exception {
        String dbUrl = "jdbc:h2:mem:mysql_source_ref_no_chaining;MODE=MySQL;DB_CLOSE_DELAY=-1";
        seedDatabase(dbUrl);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.start();
        try {
            writeMysqlOnlyFixture("select fee_id from loan_fee where loan_id = 5758", "[108]");
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl, true, true))
                    .runForManifest("APIsToBeValidated_MysqlOnly.csv");

            assertEquals(1, report.stepsExecuted);
            assertEquals(1, report.stepsPassed);
            assertEquals(0, report.stepsFailed);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chainedMysqlStepWithBoundTokenIsAlsoReferenceableForALaterStep() throws Exception {
        String dbUrl = "jdbc:h2:mem:mysql_source_ref_bound;MODE=MySQL;DB_CLOSE_DELAY=-1";
        seedDatabaseWithTwoFees(dbUrl);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/loan", exchange -> json(exchange, 200, "{\"id\":5758}"));
        server.start();
        try {
            writeThreeStepFixture(
                    "select fee_id from loan_fee where loan_id = {{source.response.id}}",
                    "[108]",
                    "select fee_id from loan_fee where fee_id = {{dbFee.response[0]}}",
                    "[108]"
            );
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl, true, false))
                    .runForManifest("APIsToBeValidated_MysqlSourceRef.csv");

            assertEquals(3, report.stepsExecuted);
            assertEquals(3, report.stepsPassed);
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

    private void seedDatabaseWithTwoFees(String dbUrl) throws Exception {
        try (Connection connection = DriverManager.getConnection(dbUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table loan_fee (loan_id int primary key, fee_id int)");
            statement.execute("insert into loan_fee (loan_id, fee_id) values (5758, 108)");
        }
    }

    private Config config(HttpServer server, String dbUrl, boolean responseChainingEnabled, boolean mysqlOnly) {
        return Config.load(new String[] {
                "--projectRoot=" + tempDir,
                "--mode=execute",
                "--manifests=" + (mysqlOnly ? "APIsToBeValidated_MysqlOnly.csv" : "APIsToBeValidated_MysqlSourceRef.csv"),
                "--apis=" + (mysqlOnly ? "MysqlOnlyApi" : "MysqlSourceRefApi"),
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

    private void writeThreeStepFixture(String dbFeeQuery, String dbFeeExpectedTableValue,
                                        String consumeQuery, String consumeExpectedTableValue) throws IOException {
        Files.createDirectories(tempDir.resolve("apisToBeValidated"));
        Files.createDirectories(tempDir.resolve("apiInputs"));
        Files.createDirectories(tempDir.resolve("fileFromJson/payloadJSON"));
        Files.createDirectories(tempDir.resolve("fileFromJson/expectedJSON"));
        Files.createDirectories(tempDir.resolve("java-regression-runner"));

        Files.writeString(
                tempDir.resolve("apisToBeValidated/APIsToBeValidated_MysqlSourceRef.csv"),
                "#,apiName,apiMethod,apiPath,apiInputsFile,toBeValidated,Module,fileType,payloadJSON,expectedJSON,consentPerson,scenario,mysql,BusinessFunction\n"
                        + "1,MysqlSourceRefApi,NA,NA,MysqlSourceRefApi.csv,y,Module,json,MysqlSourceRefPayload.json,MysqlSourceRefExpected.json,Owner,y,n,NA\n"
        );
        Files.writeString(
                tempDir.resolve("apiInputs/MysqlSourceRefApi.csv"),
                "testScenarioNumber,testObjective,urlParameter,expectedResponseCode\n"
                        + "1,fetchLoan,NA,200\n"
                        + "2,checkLoanFee,NA,200\n"
                        + "3,checkLoanFeeAgain,NA,200\n"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/payloadJSON/MysqlSourceRefPayload.json"),
                "["
                        + "{\"apiName\":\"FetchLoan\",\"apiMethod\":\"GET\",\"apiPath\":\"/api/test/v1/loan\",\"stepId\":\"source\",\"payload\":[],\"TestStresserFlg\":\"N\"},"
                        + "{\"queryName\":\"DbFee\",\"mysqlQuery\":\"" + dbFeeQuery + "\",\"stepId\":\"dbFee\"},"
                        + "{\"queryName\":\"ConsumeDbFee\",\"mysqlQuery\":\"" + consumeQuery + "\"}"
                        + "]"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/expectedJSON/MysqlSourceRefExpected.json"),
                "["
                        + "{\"apiName\":\"FetchLoan\",\"expected\":{\"id\":5758}},"
                        + "{\"queryName\":\"DbFee\",\"expectedTableValue\":" + dbFeeExpectedTableValue + "},"
                        + "{\"queryName\":\"ConsumeDbFee\",\"expectedTableValue\":" + consumeExpectedTableValue + "}"
                        + "]"
        );
    }

    private void writeMysqlOnlyFixture(String mysqlQuery, String expectedTableValue) throws IOException {
        Files.createDirectories(tempDir.resolve("apisToBeValidated"));
        Files.createDirectories(tempDir.resolve("apiInputs"));
        Files.createDirectories(tempDir.resolve("fileFromJson/payloadJSON"));
        Files.createDirectories(tempDir.resolve("fileFromJson/expectedJSON"));
        Files.createDirectories(tempDir.resolve("java-regression-runner"));

        Files.writeString(
                tempDir.resolve("apisToBeValidated/APIsToBeValidated_MysqlOnly.csv"),
                "#,apiName,apiMethod,apiPath,apiInputsFile,toBeValidated,Module,fileType,payloadJSON,expectedJSON,consentPerson,scenario,mysql,BusinessFunction\n"
                        + "1,MysqlOnlyApi,NA,NA,MysqlOnlyApi.csv,y,Module,json,MysqlOnlyPayload.json,MysqlOnlyExpected.json,Owner,y,n,NA\n"
        );
        Files.writeString(
                tempDir.resolve("apiInputs/MysqlOnlyApi.csv"),
                "testScenarioNumber,testObjective,urlParameter,expectedResponseCode\n"
                        + "1,checkLoanFee,NA,200\n"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/payloadJSON/MysqlOnlyPayload.json"),
                "["
                        + "{\"queryName\":\"CheckLoanFee\",\"mysqlQuery\":\"" + mysqlQuery + "\"}"
                        + "]"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/expectedJSON/MysqlOnlyExpected.json"),
                "["
                        + "{\"queryName\":\"CheckLoanFee\",\"expectedTableValue\":" + expectedTableValue + "}"
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
