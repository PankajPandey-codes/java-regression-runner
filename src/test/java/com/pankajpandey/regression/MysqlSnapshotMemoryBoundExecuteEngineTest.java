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
 * Task 9: a DB step is only snapshotted when it carries an explicit stepId,
 * never merely because the file-wide scenarioUsesResponseChaining flag is set
 * and never merely because the step's own query consumes a {{...}} token.
 */
class MysqlSnapshotMemoryBoundExecuteEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void mysqlStepWithoutStepIdIsNotSnapshottedEvenWhenFileWideFlagIsTrue() throws Exception {
        String dbUrl = "jdbc:h2:mem:mysql_no_stepid_no_snapshot;MODE=MySQL;DB_CLOSE_DELAY=-1";
        seedDatabase(dbUrl);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/loan", exchange -> json(exchange, 200, "{\"id\":5758}"));
        server.start();
        try {
            writeThreeStepFixtureNoStepIdOnConsumerSource();
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl))
                    .runForManifest("APIsToBeValidated_MysqlNoSnapshot.csv");

            assertEquals(3, report.stepsExecuted);
            assertEquals(2, report.stepsPassed);
            assertEquals(1, report.stepsFailed);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> laterStepComparison = (List<Map<String, Object>>) report.failures.get(0).get("comparison");
            String kind = String.valueOf(laterStepComparison.get(0).get("kind"));
            assertEquals(true, kind.equals("missing_reference") || kind.equals("dependency_failed"),
                    "expected missing_reference/dependency_failed but was: " + kind);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mysqlStepWithExplicitStepIdIsStillSnapshottedAndReferenceable() throws Exception {
        String dbUrl = "jdbc:h2:mem:mysql_with_stepid_snapshot;MODE=MySQL;DB_CLOSE_DELAY=-1";
        seedDatabase(dbUrl);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/loan", exchange -> json(exchange, 200, "{\"id\":5758}"));
        server.start();
        try {
            writeThreeStepFixtureWithStepIdOnConsumerSource();
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl))
                    .runForManifest("APIsToBeValidated_MysqlWithSnapshot.csv");

            assertEquals(3, report.stepsExecuted);
            assertEquals(3, report.stepsPassed);
            assertEquals(0, report.stepsFailed);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpStepIsStillSnapshottedViaFileWideFlagUnchanged() throws Exception {
        String dbUrl = "jdbc:h2:mem:http_still_snapshotted;MODE=MySQL;DB_CLOSE_DELAY=-1";
        seedDatabase(dbUrl);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/loan", exchange -> json(exchange, 200, "{\"id\":5758}"));
        server.createContext("/api/test/v1/loanAgain", exchange -> json(exchange, 200, "{\"id\":5758}"));
        server.start();
        try {
            writeHttpStepReferencedByFallbackAliasFixture();
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl))
                    .runForManifest("APIsToBeValidated_HttpSnapshotUnchanged.csv");

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

    private Config config(HttpServer server, String dbUrl) {
        return Config.load(new String[] {
                "--projectRoot=" + tempDir,
                "--mode=execute",
                "--manifests=" + currentManifest,
                "--apis=" + currentApi,
                "--protocol=http",
                "--server=localhost:" + server.getAddress().getPort(),
                "--username=user",
                "--password=password",
                "--tenant=tenant",
                "--dbUrl=" + dbUrl,
                "--dbUsername=sa",
                "--dbPassword=",
                "--dbDriver=org.h2.Driver",
                "--responseChaining.enabled=true"
        });
    }

    private String currentManifest;
    private String currentApi;

    private void writeThreeStepFixtureNoStepIdOnConsumerSource() throws IOException {
        currentManifest = "APIsToBeValidated_MysqlNoSnapshot.csv";
        currentApi = "MysqlNoSnapshotApi";
        writeThreeStepFixture(currentManifest, currentApi,
                "MysqlNoSnapshotPayload.json", "MysqlNoSnapshotExpected.json",
                false);
    }

    private void writeThreeStepFixtureWithStepIdOnConsumerSource() throws IOException {
        currentManifest = "APIsToBeValidated_MysqlWithSnapshot.csv";
        currentApi = "MysqlWithSnapshotApi";
        writeThreeStepFixture(currentManifest, currentApi,
                "MysqlWithSnapshotPayload.json", "MysqlWithSnapshotExpected.json",
                true);
    }

    private void writeThreeStepFixture(
            String manifestName,
            String apiName,
            String payloadFile,
            String expectedFile,
            boolean secondStepHasStepId
    ) throws IOException {
        Files.createDirectories(tempDir.resolve("apisToBeValidated"));
        Files.createDirectories(tempDir.resolve("apiInputs"));
        Files.createDirectories(tempDir.resolve("fileFromJson/payloadJSON"));
        Files.createDirectories(tempDir.resolve("fileFromJson/expectedJSON"));
        Files.createDirectories(tempDir.resolve("java-regression-runner"));

        Files.writeString(
                tempDir.resolve("apisToBeValidated/" + manifestName),
                "#,apiName,apiMethod,apiPath,apiInputsFile,toBeValidated,Module,fileType,payloadJSON,expectedJSON,consentPerson,scenario,mysql,BusinessFunction\n"
                        + "1," + apiName + ",NA,NA," + apiName + ".csv,y,Module,json," + payloadFile + "," + expectedFile + ",Owner,y,n,NA\n"
        );
        Files.writeString(
                tempDir.resolve("apiInputs/" + apiName + ".csv"),
                "testScenarioNumber,testObjective,urlParameter,expectedResponseCode\n"
                        + "1,fetchLoan,NA,200\n"
                        + "2,checkLoanFeeConsumer,NA,200\n"
                        + "3,checkLoanFeeByFallbackAlias,NA,200\n"
        );

        String secondStepIdField = secondStepHasStepId ? "\"stepId\":\"dbFeeConsumer\"," : "";
        String secondStepAliasRef = secondStepHasStepId ? "dbFeeConsumer" : "step2";

        Files.writeString(
                tempDir.resolve("fileFromJson/payloadJSON/" + payloadFile),
                "["
                        + "{\"apiName\":\"FetchLoan\",\"apiMethod\":\"GET\",\"apiPath\":\"/api/test/v1/loan\",\"stepId\":\"source\",\"payload\":[],\"TestStresserFlg\":\"N\"},"
                        + "{\"queryName\":\"DbFeeConsumer\","
                        + secondStepIdField
                        + "\"mysqlQuery\":\"select fee_id from loan_fee where loan_id = {{source.response.id}}\"},"
                        + "{\"queryName\":\"CheckByFallbackAlias\",\"mysqlQuery\":\"select fee_id from loan_fee where fee_id = {{" + secondStepAliasRef + ".response[0]}}\"}"
                        + "]"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/expectedJSON/" + expectedFile),
                "["
                        + "{\"apiName\":\"FetchLoan\",\"expected\":{\"id\":5758}},"
                        + "{\"queryName\":\"DbFeeConsumer\",\"expectedTableValue\":[108]},"
                        + "{\"queryName\":\"CheckByFallbackAlias\",\"expectedTableValue\":[108]}"
                        + "]"
        );
    }

    private void writeHttpStepReferencedByFallbackAliasFixture() throws IOException {
        currentManifest = "APIsToBeValidated_HttpSnapshotUnchanged.csv";
        currentApi = "HttpSnapshotUnchangedApi";
        Files.createDirectories(tempDir.resolve("apisToBeValidated"));
        Files.createDirectories(tempDir.resolve("apiInputs"));
        Files.createDirectories(tempDir.resolve("fileFromJson/payloadJSON"));
        Files.createDirectories(tempDir.resolve("fileFromJson/expectedJSON"));
        Files.createDirectories(tempDir.resolve("java-regression-runner"));

        Files.writeString(
                tempDir.resolve("apisToBeValidated/" + currentManifest),
                "#,apiName,apiMethod,apiPath,apiInputsFile,toBeValidated,Module,fileType,payloadJSON,expectedJSON,consentPerson,scenario,mysql,BusinessFunction\n"
                        + "1," + currentApi + ",NA,NA," + currentApi + ".csv,y,Module,json,HttpSnapshotUnchangedPayload.json,HttpSnapshotUnchangedExpected.json,Owner,y,n,NA\n"
        );
        // First step has no explicit stepId; the second step's urlParameter references the
        // first step purely by its stepN fallback alias ({{step1...}}), which drives the
        // file-wide scenarioUsesResponseChaining=true flag via the raw "{{" text scan and
        // proves HTTP steps are still snapshotted via that broad flag, unchanged by this task.
        Files.writeString(
                tempDir.resolve("apiInputs/" + currentApi + ".csv"),
                "testScenarioNumber,testObjective,urlParameter,expectedResponseCode\n"
                        + "1,fetchLoan,NA,200\n"
                        + "2,fetchLoanAgainByFallbackAlias,{{step1.response.id}},200\n"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/payloadJSON/HttpSnapshotUnchangedPayload.json"),
                "["
                        + "{\"apiName\":\"FetchLoan\",\"apiMethod\":\"GET\",\"apiPath\":\"/api/test/v1/loan\",\"payload\":[],\"TestStresserFlg\":\"N\"},"
                        + "{\"apiName\":\"FetchLoanAgain\",\"apiMethod\":\"GET\",\"apiPath\":\"/api/test/v1/loanAgain\",\"payload\":[],\"TestStresserFlg\":\"N\"}"
                        + "]"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/expectedJSON/HttpSnapshotUnchangedExpected.json"),
                "["
                        + "{\"apiName\":\"FetchLoan\",\"expected\":{\"id\":5758}},"
                        + "{\"apiName\":\"FetchLoanAgain\",\"expected\":{\"id\":5758}}"
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
