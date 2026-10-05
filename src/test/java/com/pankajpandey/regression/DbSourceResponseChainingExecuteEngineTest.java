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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 end-to-end tests: DB steps as chaining sources (Tasks 3-9), proved through real
 * {@code ExecuteEngine.runForManifest(...)} runs rather than {@code ScenarioContext} unit tests.
 *
 * H2 caveat resolution: a throwaway sanity check (since discarded) proved that H2's
 * {@code JSON_ARRAYAGG}/{@code JSON_OBJECT} under {@code MODE=MySQL} produce the right SQL-level
 * text ({@code rs.getString(1)} returns e.g. {@code [{"fee_id":1,"amount":10}]}), but
 * {@code DatabaseClient.materializeRows} never calls {@code getString} -- it calls the generic
 * {@code rs.getObject(1)}, and for H2's {@code JSON} column type that returns a raw {@code byte[]},
 * not a {@code String} (confirmed via {@code rs.getMetaData().getColumnTypeName(1)} == "JSON").
 * {@code parsePotentialJson}'s {@code String.valueOf(value)} on a {@code byte[]} produces
 * {@code "[B@<hash>"}, not the JSON text, so the aggregate does NOT reproduce the production
 * shape through the real code path. Per the task brief this is treated as an H2 divergence, not a
 * production bug: every fixture below that needs a JSON-array-of-objects/scalars single-column
 * result seeds a literal {@code varchar} column containing hand-written JSON text instead of
 * calling {@code JSON_ARRAYAGG}/{@code JSON_OBJECT}, which is exactly the shape
 * {@code getObject()} returns for a real MySQL {@code JSON_ARRAYAGG(...)} column (a {@code String}).
 * The decimal-precision case (7) instead selects a bare native {@code double} column (no
 * aggregate, no JSON string at all), which exercises {@code normalizeScalar}'s JDBC-typed-value
 * fix directly.
 */
class DbSourceResponseChainingExecuteEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void dbOneRowJsonObjectFilterFlowsIntoApiPayload() throws Exception {
        String dbUrl = "jdbc:h2:mem:db_source_one_row_filter;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(dbUrl, "sa", ""); Statement st = c.createStatement()) {
            st.execute("create table fee_one_json (blob varchar(4000))");
            st.execute("insert into fee_one_json values ('[{\"fee_id\":42,\"amount\":100}]')");
        }

        AtomicReference<String> received = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/echo", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(exchange, 200, "{\"received\":true}");
        });
        server.start();
        try {
            writeFixture(
                    "DbOneRowFilterApi",
                    "select blob from fee_one_json",
                    "[{\"fee_id\":42,\"amount\":100}]",
                    "\"amount\":\"{{dbStep.response[?(@.fee_id == 42)].amount}}\""
            );
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl))
                    .runForManifest("APIsToBeValidated_DbOneRowFilterApi.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(2, report.stepsPassed);
            assertEquals(0, report.stepsFailed);
            assertTrue(received.get().contains("\"amount\":100"), received.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dbMultiRowBareColumnArrayExpandsIntoSqlInClause() throws Exception {
        String dbUrl = "jdbc:h2:mem:db_source_bare_array_in;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(dbUrl, "sa", ""); Statement st = c.createStatement()) {
            st.execute("create table fee_multi (fee_id int primary key)");
            st.execute("insert into fee_multi values (101), (102), (103)");
            st.execute("create table multi_ids_holder (ids varchar(4000))");
            st.execute("insert into multi_ids_holder values ('[101,102,103]')");
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.start();
        try {
            writeTwoDbStepFixture(
                    "DbBareArrayInApi",
                    "select ids from multi_ids_holder",
                    "[101,102,103]",
                    "select count(*) as cnt from fee_multi where fee_id in ({{multiIds.response}})",
                    "[3]"
            );
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl))
                    .runForManifest("APIsToBeValidated_DbBareArrayInApi.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(2, report.stepsPassed);
            assertEquals(0, report.stepsFailed);

            String artifactContent = Files.readString(
                    tempDir.resolve((String) report.passes.get(1).get("jsonPayloadArtifact")));
            assertTrue(artifactContent.contains("in (?,?,?)"), artifactContent);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dbMultiColumnRowSelectedByFilterFlowsOneFieldIntoApiPayload() throws Exception {
        String dbUrl = "jdbc:h2:mem:db_source_multi_col_filter;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(dbUrl, "sa", ""); Statement st = c.createStatement()) {
            st.execute("create table fee_two_json (blob varchar(4000))");
            st.execute("insert into fee_two_json values ('[{\"fee_id\":1,\"amount\":10},{\"fee_id\":2,\"amount\":20}]')");
        }

        AtomicReference<String> received = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/echo", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(exchange, 200, "{\"received\":true}");
        });
        server.start();
        try {
            writeFixture(
                    "DbMultiColFilterApi",
                    "select blob from fee_two_json",
                    "[{\"fee_id\":1,\"amount\":10},{\"fee_id\":2,\"amount\":20}]",
                    "\"amount\":\"{{dbStep.response[?(@.fee_id == 2)].amount}}\""
            );
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl))
                    .runForManifest("APIsToBeValidated_DbMultiColFilterApi.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(2, report.stepsPassed);
            assertEquals(0, report.stepsFailed);
            assertTrue(received.get().contains("\"amount\":20"), received.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rootIndexOnMultiElementDbSourceArrayIsRejectedAsAmbiguousDbSourceEndToEnd() throws Exception {
        String dbUrl = "jdbc:h2:mem:db_source_ambiguous_root_index;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(dbUrl, "sa", ""); Statement st = c.createStatement()) {
            st.execute("create table fee_two_json (blob varchar(4000))");
            st.execute("insert into fee_two_json values ('[{\"fee_id\":1,\"amount\":10},{\"fee_id\":2,\"amount\":20}]')");
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/echo", exchange -> json(exchange, 200, "{\"received\":true}"));
        server.start();
        try {
            writeFixture(
                    "DbAmbiguousRootIndexApi",
                    "select blob from fee_two_json",
                    "[{\"fee_id\":1,\"amount\":10},{\"fee_id\":2,\"amount\":20}]",
                    "\"amount\":\"{{dbStep.response[0].amount}}\""
            );
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl))
                    .runForManifest("APIsToBeValidated_DbAmbiguousRootIndexApi.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(1, report.stepsPassed);
            assertEquals(1, report.stepsFailed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> comparison = (List<Map<String, Object>>) report.failures.get(0).get("comparison");
            assertEquals("ambiguous_db_source", comparison.get(0).get("kind"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rootIndexOnSingleElementDbSourceArrayResolvesNormallyEndToEnd() throws Exception {
        String dbUrl = "jdbc:h2:mem:db_source_single_element_root_index;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(dbUrl, "sa", ""); Statement st = c.createStatement()) {
            st.execute("create table fee_single_json (blob varchar(4000))");
            st.execute("insert into fee_single_json values ('[{\"fee_id\":1,\"amount\":10}]')");
        }

        AtomicReference<String> received = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/echo", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(exchange, 200, "{\"received\":true}");
        });
        server.start();
        try {
            writeFixture(
                    "DbSingleElementRootIndexApi",
                    "select blob from fee_single_json",
                    "[{\"fee_id\":1,\"amount\":10}]",
                    "\"amount\":\"{{dbStep.response[0].amount}}\""
            );
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl))
                    .runForManifest("APIsToBeValidated_DbSingleElementRootIndexApi.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(2, report.stepsPassed);
            assertEquals(0, report.stepsFailed);
            assertTrue(received.get().contains("\"amount\":10"), received.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void indexAfterFilterOnNestedDbSourceArrayResolvesNormallyEndToEnd() throws Exception {
        String dbUrl = "jdbc:h2:mem:db_source_index_after_filter;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(dbUrl, "sa", ""); Statement st = c.createStatement()) {
            st.execute("create table fee_nested (blob varchar(4000))");
            st.execute("insert into fee_nested values ("
                    + "'[{\"fee_id\":1,\"splits\":[{\"amount\":1},{\"amount\":2},{\"amount\":3}]},"
                    + "{\"fee_id\":2,\"splits\":[{\"amount\":9}]}]')");
        }

        AtomicReference<String> received = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/echo", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(exchange, 200, "{\"received\":true}");
        });
        server.start();
        try {
            writeFixture(
                    "DbIndexAfterFilterApi",
                    "select blob from fee_nested",
                    "[{\"fee_id\":1,\"splits\":[{\"amount\":1},{\"amount\":2},{\"amount\":3}]},"
                            + "{\"fee_id\":2,\"splits\":[{\"amount\":9}]}]",
                    "\"amount\":\"{{dbStep.response[?(@.fee_id == 1)].splits[2].amount}}\""
            );
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl))
                    .runForManifest("APIsToBeValidated_DbIndexAfterFilterApi.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(2, report.stepsPassed);
            assertEquals(0, report.stepsFailed);
            assertTrue(received.get().contains("\"amount\":3"), received.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void decimalValueFromNativeColumnPreservesFullPrecisionEndToEnd() throws Exception {
        String dbUrl = "jdbc:h2:mem:db_source_decimal_precision;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(dbUrl, "sa", ""); Statement st = c.createStatement()) {
            st.execute("create table fee_decimal (fee_id int primary key, amount double)");
            st.execute("insert into fee_decimal values (1, 12345678.9)");
        }

        AtomicReference<String> received = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/echo", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(exchange, 200, "{\"received\":true}");
        });
        server.start();
        try {
            // No JSON_ARRAYAGG here: a bare scalar column drives normalizeScalar's JDBC-typed-value
            // fix directly (BigDecimal.valueOf(double)), which is the exact code path Task 2 fixed
            // -- Double.toString(12345678.9) is "1.23456789E7" and would have leaked into the
            // consumed value under the pre-fix numberNode(double) path.
            writeFixture(
                    "DbDecimalPrecisionApi",
                    "select amount from fee_decimal where fee_id = 1",
                    "[12345678.9]",
                    "\"amount\":\"{{dbStep.response[0]}}\""
            );
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl))
                    .runForManifest("APIsToBeValidated_DbDecimalPrecisionApi.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(2, report.stepsPassed);
            assertEquals(0, report.stepsFailed);
            assertTrue(received.get().contains("\"amount\":12345678.9"), received.get());
            assertTrue(!received.get().toLowerCase().contains("e7"), received.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void decimalValueFromJsonBlobColumnPreservesFullPrecisionEndToEnd() throws Exception {
        String dbUrl = "jdbc:h2:mem:db_source_decimal_precision_json_blob;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(dbUrl, "sa", ""); Statement st = c.createStatement()) {
            st.execute("create table fee_decimal_json (blob varchar(4000))");
            st.execute("insert into fee_decimal_json values ('[{\"fee_id\":1,\"amount\":12345678.9}]')");
        }

        AtomicReference<String> received = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/echo", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(exchange, 200, "{\"received\":true}");
        });
        server.start();
        try {
            // Unlike decimalValueFromNativeColumnPreservesFullPrecisionEndToEnd, this DB source is
            // a single-column JSON-string blob (mirroring the real JSON_ARRAYAGG(JSON_OBJECT(...))
            // production idiom), so its result goes through DatabaseClient.parsePotentialJson/
            // readTree rather than normalizeScalar. This is the one path where the DatabaseClient's
            // dual-mapper wiring (mapper vs. referenceMapper) actually matters: without
            // ExecuteEngine passing its own USE_BIG_DECIMAL_FOR_FLOATS-enabled referenceMapper into
            // DatabaseClient, the JSON text "12345678.9" parses to a DoubleNode whose asText() is
            // "1.23456789E7" (Double.toString flips to scientific notation at >= 1e7) and that
            // corrupted value leaks into the consuming step.
            writeFixture(
                    "DbDecimalJsonBlobApi",
                    "select blob from fee_decimal_json",
                    "[{\"fee_id\":1,\"amount\":12345678.9}]",
                    "\"amount\":\"{{dbStep.response[0].amount}}\""
            );
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl))
                    .runForManifest("APIsToBeValidated_DbDecimalJsonBlobApi.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(2, report.stepsPassed);
            assertEquals(0, report.stepsFailed);
            assertTrue(received.get().contains("\"amount\":12345678.9"), received.get());
            assertTrue(!received.get().toLowerCase().contains("e7"), received.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void emptyDbSourceOwnQueryFailsAtTheSourceStepRegardlessOfExpectedTableValue() throws Exception {
        String dbUrl = "jdbc:h2:mem:db_source_empty_own_query;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(dbUrl, "sa", ""); Statement st = c.createStatement()) {
            st.execute("create table fee_missing (fee_id int primary key)");
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/echo", exchange -> json(exchange, 200, "{\"received\":true}"));
        server.start();
        try {
            writeFixture(
                    "DbEmptySourceApi",
                    "select fee_id from fee_missing",
                    "[]",
                    "\"amount\":\"{{dbStep.response[0]}}\""
            );
            ExecutionReport report = new ExecuteEngine(config(server, dbUrl))
                    .runForManifest("APIsToBeValidated_DbEmptySourceApi.csv");

            assertEquals(2, report.stepsExecuted);
            assertEquals(0, report.stepsPassed);
            assertEquals(2, report.stepsFailed);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sourceComparison = (List<Map<String, Object>>) report.failures.get(0).get("comparison");
            assertEquals("empty_db_source", sourceComparison.get(0).get("kind"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> consumerComparison = (List<Map<String, Object>>) report.failures.get(1).get("comparison");
            assertEquals("dependency_failed", consumerComparison.get(0).get("kind"));
        } finally {
            server.stop(0);
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

    private void writeFixture(
            String apiName, String mysqlQuery, String dbExpectedTableValue, String payloadAmountField
    ) throws IOException {
        currentManifest = "APIsToBeValidated_" + apiName + ".csv";
        currentApi = apiName;
        Files.createDirectories(tempDir.resolve("apisToBeValidated"));
        Files.createDirectories(tempDir.resolve("apiInputs"));
        Files.createDirectories(tempDir.resolve("fileFromJson/payloadJSON"));
        Files.createDirectories(tempDir.resolve("fileFromJson/expectedJSON"));
        Files.createDirectories(tempDir.resolve("java-regression-runner"));

        Files.writeString(
                tempDir.resolve("apisToBeValidated/" + currentManifest),
                "#,apiName,apiMethod,apiPath,apiInputsFile,toBeValidated,Module,fileType,payloadJSON,expectedJSON,consentPerson,scenario,mysql,BusinessFunction\n"
                        + "1," + apiName + ",NA,NA," + apiName + ".csv,y,Module,json," + apiName + "Payload.json," + apiName + "Expected.json,Owner,y,n,NA\n"
        );
        Files.writeString(
                tempDir.resolve("apiInputs/" + apiName + ".csv"),
                "testScenarioNumber,testObjective,urlParameter,expectedResponseCode\n"
                        + "1,dbStep,NA,200\n"
                        + "2,echoStep,NA,200\n"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/payloadJSON/" + apiName + "Payload.json"),
                "["
                        + "{\"queryName\":\"DbStep\",\"stepId\":\"dbStep\",\"mysqlQuery\":\"" + mysqlQuery + "\"},"
                        + "{\"apiName\":\"Echo\",\"apiMethod\":\"POST\",\"apiPath\":\"/api/test/v1/echo\","
                        + "\"payload\":{" + payloadAmountField + "},\"TestStresserFlg\":\"N\"}"
                        + "]"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/expectedJSON/" + apiName + "Expected.json"),
                "["
                        + "{\"queryName\":\"DbStep\",\"expectedTableValue\":" + dbExpectedTableValue + "},"
                        + "{\"apiName\":\"Echo\",\"expected\":{\"received\":true}}"
                        + "]"
        );
    }

    private void writeTwoDbStepFixture(
            String apiName, String firstMysqlQuery, String firstExpectedTableValue,
            String secondMysqlQuery, String secondExpectedTableValue
    ) throws IOException {
        currentManifest = "APIsToBeValidated_" + apiName + ".csv";
        currentApi = apiName;
        Files.createDirectories(tempDir.resolve("apisToBeValidated"));
        Files.createDirectories(tempDir.resolve("apiInputs"));
        Files.createDirectories(tempDir.resolve("fileFromJson/payloadJSON"));
        Files.createDirectories(tempDir.resolve("fileFromJson/expectedJSON"));
        Files.createDirectories(tempDir.resolve("java-regression-runner"));

        Files.writeString(
                tempDir.resolve("apisToBeValidated/" + currentManifest),
                "#,apiName,apiMethod,apiPath,apiInputsFile,toBeValidated,Module,fileType,payloadJSON,expectedJSON,consentPerson,scenario,mysql,BusinessFunction\n"
                        + "1," + apiName + ",NA,NA," + apiName + ".csv,y,Module,json," + apiName + "Payload.json," + apiName + "Expected.json,Owner,y,n,NA\n"
        );
        Files.writeString(
                tempDir.resolve("apiInputs/" + apiName + ".csv"),
                "testScenarioNumber,testObjective,urlParameter,expectedResponseCode\n"
                        + "1,multiIdsStep,NA,200\n"
                        + "2,countStep,NA,200\n"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/payloadJSON/" + apiName + "Payload.json"),
                "["
                        + "{\"queryName\":\"MultiIds\",\"stepId\":\"multiIds\",\"mysqlQuery\":\"" + firstMysqlQuery + "\"},"
                        + "{\"queryName\":\"CountByIds\",\"mysqlQuery\":\"" + secondMysqlQuery + "\"}"
                        + "]"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/expectedJSON/" + apiName + "Expected.json"),
                "["
                        + "{\"queryName\":\"MultiIds\",\"expectedTableValue\":" + firstExpectedTableValue + "},"
                        + "{\"queryName\":\"CountByIds\",\"expectedTableValue\":" + secondExpectedTableValue + "}"
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
