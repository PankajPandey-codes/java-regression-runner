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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper REFERENCE_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Test
    void executeJsonQuery_keepsAllRowsForSingleColumnResults() throws Exception {
        Config cfg = testConfig("jdbc:h2:mem:single_column_rows;MODE=MySQL;DB_CLOSE_DELAY=-1");

        try (Connection connection = DriverManager.getConnection(cfg.dbUrl, cfg.dbUsername, cfg.dbPassword);
             Statement statement = connection.createStatement()) {
            statement.execute("create table test_values (id int primary key, metric_value int)");
            statement.execute("insert into test_values (id, metric_value) values (1, 101), (2, 202)");
        }

        try (DatabaseClient client = new DatabaseClient(cfg, MAPPER, REFERENCE_MAPPER)) {
            JsonNode actual = client.executeJsonQuery("select metric_value from test_values order by id");

            assertTrue(actual.isArray());
            assertEquals("[101,202]", actual.toString());
        }
    }

    @Test
    void executeJsonQuery_preservesSingleJsonBlobBehavior() throws Exception {
        Config cfg = testConfig("jdbc:h2:mem:single_json_blob;MODE=MySQL;DB_CLOSE_DELAY=-1");

        try (Connection connection = DriverManager.getConnection(cfg.dbUrl, cfg.dbUsername, cfg.dbPassword);
             Statement statement = connection.createStatement()) {
            statement.execute("create table test_json (payload varchar(255))");
            statement.execute("insert into test_json (payload) values ('{\"status\":\"ok\",\"count\":2}')");
        }

        try (DatabaseClient client = new DatabaseClient(cfg, MAPPER, REFERENCE_MAPPER)) {
            JsonNode actual = client.executeJsonQuery("select payload from test_json");

            assertTrue(actual.isObject());
            assertEquals("ok", actual.get("status").asText());
            assertEquals(2, actual.get("count").asInt());
        }
    }

    @Test
    void executeJsonQuery_withParams_bindsNumberTextAndBooleanPositionally() throws Exception {
        Config cfg = testConfig("jdbc:h2:mem:bind_matrix;MODE=MySQL;DB_CLOSE_DELAY=-1");

        try (Connection connection = DriverManager.getConnection(cfg.dbUrl, cfg.dbUsername, cfg.dbPassword);
             Statement statement = connection.createStatement()) {
            statement.execute("create table test_bind (id int primary key, name varchar(255), active boolean)");
            statement.execute("insert into test_bind (id, name, active) values (1, 'alice', true), (2, 'bob', false)");
        }

        try (DatabaseClient client = new DatabaseClient(cfg, MAPPER, REFERENCE_MAPPER)) {
            JsonNode actual = client.executeJsonQuery(
                    "select name from test_bind where id = ? and name = ? and active = ?",
                    List.of(MAPPER.getNodeFactory().numberNode(1),
                            MAPPER.getNodeFactory().textNode("alice"),
                            MAPPER.getNodeFactory().booleanNode(true)));

            assertTrue(actual.isArray());
            assertEquals("[\"alice\"]", actual.toString());
        }
    }

    @Test
    void executeJsonQuery_withParams_bindsExpandedArrayParamsInOrder() throws Exception {
        Config cfg = testConfig("jdbc:h2:mem:bind_array;MODE=MySQL;DB_CLOSE_DELAY=-1");

        try (Connection connection = DriverManager.getConnection(cfg.dbUrl, cfg.dbUsername, cfg.dbPassword);
             Statement statement = connection.createStatement()) {
            statement.execute("create table test_in (id int primary key, name varchar(255))");
            statement.execute("insert into test_in (id, name) values (1, 'alice'), (2, 'bob'), (3, 'carol')");
        }

        try (DatabaseClient client = new DatabaseClient(cfg, MAPPER, REFERENCE_MAPPER)) {
            JsonNode actual = client.executeJsonQuery(
                    "select name from test_in where id in (?, ?) order by id",
                    List.of(MAPPER.getNodeFactory().numberNode(1),
                            MAPPER.getNodeFactory().numberNode(3)));

            assertTrue(actual.isArray());
            assertEquals("[\"alice\",\"carol\"]", actual.toString());
        }
    }

    @Test
    void executeJsonQuery_singleParamOverload_isByteIdenticalToUnparameterizedQuery() throws Exception {
        Config cfg = testConfig("jdbc:h2:mem:byte_identical;MODE=MySQL;DB_CLOSE_DELAY=-1");

        try (Connection connection = DriverManager.getConnection(cfg.dbUrl, cfg.dbUsername, cfg.dbPassword);
             Statement statement = connection.createStatement()) {
            statement.execute("create table test_plain (id int primary key, metric_value decimal(10,2))");
            statement.execute("insert into test_plain (id, metric_value) values (1, 100.5)");
        }

        try (DatabaseClient client = new DatabaseClient(cfg, MAPPER, REFERENCE_MAPPER)) {
            JsonNode noArgResult = client.executeJsonQuery("select metric_value from test_plain order by id");
            JsonNode emptyParamsResult = client.executeJsonQuery("select metric_value from test_plain order by id", List.of());

            assertEquals(noArgResult.toString(), emptyParamsResult.toString());
        }
    }

    @Test
    void normalizeScalar_routesDoubleThroughBigDecimalToAvoidScientificNotation() throws Exception {
        Config cfg = testConfig("jdbc:h2:mem:double_scalar;MODE=MySQL;DB_CLOSE_DELAY=-1");

        try (Connection connection = DriverManager.getConnection(cfg.dbUrl, cfg.dbUsername, cfg.dbPassword);
             Statement statement = connection.createStatement()) {
            statement.execute("create table test_double (id int primary key, metric_value double)");
            statement.execute("insert into test_double (id, metric_value) values (1, 100000000.123456)");
        }

        try (DatabaseClient client = new DatabaseClient(cfg, MAPPER, REFERENCE_MAPPER)) {
            JsonNode actual = client.executeJsonQuery("select metric_value from test_double order by id");

            assertTrue(actual.isArray());
            String rendered = actual.get(0).asText();
            assertFalse(rendered.toLowerCase().contains("e"), "expected plain decimal rendering, got: " + rendered);
        }
    }

    @Test
    void executeJsonQueryWithReference_producesDecimalReferenceNodeAndUnchangedActualNode() throws Exception {
        Config cfg = testConfig("jdbc:h2:mem:reference_precision;MODE=MySQL;DB_CLOSE_DELAY=-1");

        try (Connection connection = DriverManager.getConnection(cfg.dbUrl, cfg.dbUsername, cfg.dbPassword);
             Statement statement = connection.createStatement()) {
            statement.execute("create table test_decimal_blob (payload varchar(255))");
            statement.execute("insert into test_decimal_blob (payload) values ('{\"amount\":123.456789}')");
        }

        try (DatabaseClient client = new DatabaseClient(cfg, MAPPER, REFERENCE_MAPPER)) {
            DatabaseClient.DualJsonResult result = client.executeJsonQueryWithReference(
                    "select payload from test_decimal_blob", List.of());

            JsonNode plainViaSingleArg = client.executeJsonQuery("select payload from test_decimal_blob");
            assertEquals(plainViaSingleArg.toString(), result.actualNode().toString());

            JsonNode actualAmount = result.actualNode().get("amount");
            JsonNode referenceAmount = result.referenceNode().get("amount");
            assertInstanceOf(DoubleNode.class, actualAmount);
            assertInstanceOf(DecimalNode.class, referenceAmount);
            assertEquals(new BigDecimal("123.456789"), referenceAmount.decimalValue());
        }
    }

    @Test
    void concurrentThreads_eachGetTheirOwnJdbcConnection() throws Exception {
        Config cfg = testConfig("jdbc:h2:mem:distinct_connections_per_thread;MODE=MySQL;DB_CLOSE_DELAY=-1");
        int threadCount = 6;

        try (DatabaseClient client = new DatabaseClient(cfg, MAPPER, REFERENCE_MAPPER)) {
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            try {
                CountDownLatch startLatch = new CountDownLatch(1);
                List<Future<Long>> futures = new ArrayList<>();
                for (int t = 0; t < threadCount; t++) {
                    futures.add(pool.submit(() -> {
                        startLatch.await();
                        JsonNode result = client.executeJsonQuery("select session_id()");
                        return result.get(0).asLong();
                    }));
                }
                startLatch.countDown();

                Set<Long> sessionIds = new HashSet<>();
                for (Future<Long> f : futures) {
                    sessionIds.add(f.get(30, TimeUnit.SECONDS));
                }

                assertEquals(threadCount, sessionIds.size(),
                        "each thread must be backed by its own JDBC connection/session, not a shared one");
            } finally {
                pool.shutdown();
            }
        }
    }

    @Test
    void sameThread_reusesItsConnection_distinctThreadsGetDifferentOnes() throws Exception {
        Config cfg = testConfig("jdbc:h2:mem:connection_reuse;MODE=MySQL;DB_CLOSE_DELAY=-1");

        try (DatabaseClient client = new DatabaseClient(cfg, MAPPER, REFERENCE_MAPPER)) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<long[]> threadASessions = pool.submit(() -> new long[] {
                        client.executeJsonQuery("select session_id()").get(0).asLong(),
                        client.executeJsonQuery("select session_id()").get(0).asLong()
                });
                long[] sessions = threadASessions.get(30, TimeUnit.SECONDS);
                assertEquals(sessions[0], sessions[1],
                        "the same thread must reuse its own connection across queries, not reconnect every time");

                Future<Long> threadBSession = pool.submit(() ->
                        client.executeJsonQuery("select session_id()").get(0).asLong());
                long otherThreadSessionId = threadBSession.get(30, TimeUnit.SECONDS);
                assertFalse(sessions[0] == otherThreadSessionId,
                        "a different thread must not reuse another thread's connection");
            } finally {
                pool.shutdown();
            }
        }
    }

    @Test
    void concurrentQueries_doNotCrossContaminateResultsAcrossThreads() throws Exception {
        Config cfg = testConfig("jdbc:h2:mem:concurrent_no_contamination;MODE=MySQL;DB_CLOSE_DELAY=-1");
        int threadCount = 8;
        int iterationsPerThread = 50;

        try (Connection setup = DriverManager.getConnection(cfg.dbUrl, cfg.dbUsername, cfg.dbPassword);
             Statement statement = setup.createStatement()) {
            statement.execute("create table thread_rows (thread_id int primary key, row_value varchar(255))");
            for (int t = 0; t < threadCount; t++) {
                statement.execute("insert into thread_rows (thread_id, row_value) values (" + t + ", 'value-" + t + "')");
            }
        }

        try (DatabaseClient client = new DatabaseClient(cfg, MAPPER, REFERENCE_MAPPER)) {
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            try {
                CountDownLatch startLatch = new CountDownLatch(1);
                ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < threadCount; t++) {
                    int threadId = t;
                    Callable<Void> task = () -> {
                        startLatch.await();
                        String expected = "[\"value-" + threadId + "\"]";
                        for (int i = 0; i < iterationsPerThread; i++) {
                            try {
                                JsonNode actual = client.executeJsonQuery(
                                        "select row_value from thread_rows where thread_id = " + threadId);
                                if (!expected.equals(actual.toString())) {
                                    failures.add("thread " + threadId + " iteration " + i
                                            + ": expected " + expected + " but got " + actual);
                                }
                            } catch (Exception e) {
                                failures.add("thread " + threadId + " iteration " + i + " threw " + e);
                            }
                        }
                        return null;
                    };
                    futures.add(pool.submit(task));
                }
                startLatch.countDown();
                for (Future<?> f : futures) {
                    f.get(60, TimeUnit.SECONDS);
                }

                assertTrue(failures.isEmpty(), "expected no cross-thread result contamination or errors: " + failures);
            } finally {
                pool.shutdown();
            }
        }
    }

    @Test
    void concurrentFirstUse_createsExactlyOneConnectionPerThread() throws Exception {
        Config cfg = testConfig("jdbc:h2:mem:no_leak_on_first_use;MODE=MySQL;DB_CLOSE_DELAY=-1");
        int threadCount = 12;

        try (Connection setup = DriverManager.getConnection(cfg.dbUrl, cfg.dbUsername, cfg.dbPassword)) {
            // creates the named in-memory database before concurrent first use
        }

        try (DatabaseClient client = new DatabaseClient(cfg, MAPPER, REFERENCE_MAPPER)) {
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            try {
                CountDownLatch startLatch = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < threadCount; t++) {
                    futures.add(pool.submit((Callable<Void>) () -> {
                        startLatch.await();
                        client.executeJsonQuery("select 1");
                        return null;
                    }));
                }
                startLatch.countDown();
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }

                try (Connection check = DriverManager.getConnection(cfg.dbUrl, cfg.dbUsername, cfg.dbPassword);
                     Statement statement = check.createStatement();
                     ResultSet rs = statement.executeQuery(
                             "select count(*) from information_schema.sessions where session_id <> session_id()")) {
                    assertTrue(rs.next());
                    assertEquals(threadCount, rs.getInt(1),
                            "expected exactly one connection created per thread, no duplicates leaked from a check-then-act race");
                }
            } finally {
                pool.shutdown();
            }
        }
    }

    @Test
    void close_closesConnectionsFromEveryThread() throws Exception {
        Config cfg = testConfig("jdbc:h2:mem:close_all_threads;MODE=MySQL;DB_CLOSE_DELAY=-1");
        int threadCount = 5;

        try (Connection setup = DriverManager.getConnection(cfg.dbUrl, cfg.dbUsername, cfg.dbPassword)) {
            // creates the named in-memory database up front
        }

        DatabaseClient client = new DatabaseClient(cfg, MAPPER, REFERENCE_MAPPER);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                futures.add(pool.submit((Callable<Void>) () -> {
                    client.executeJsonQuery("select 1");
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
            client.close();
        }

        try (Connection check = DriverManager.getConnection(cfg.dbUrl, cfg.dbUsername, cfg.dbPassword);
             Statement statement = check.createStatement();
             ResultSet rs = statement.executeQuery(
                     "select count(*) from information_schema.sessions where session_id <> session_id()")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "expected close() to close every thread's connection, not just the caller's");
        }
    }

    @Test
    void ensureConnection_evictsStaleClosedConnectionFromRegistry() throws Exception {
        Config cfg = testConfig("jdbc:h2:mem:evict_stale_connection;MODE=MySQL;DB_CLOSE_DELAY=-1");

        try (DatabaseClient client = new DatabaseClient(cfg, MAPPER, REFERENCE_MAPPER)) {
            client.executeJsonQuery("select 1");
            assertEquals(1, client.trackedConnectionCount());

            client.activeConnectionForCurrentThread().close();
            client.executeJsonQuery("select 1");

            assertEquals(1, client.trackedConnectionCount(),
                    "a stale/closed connection must be evicted from the registry when replaced, not accumulated");
        }
    }

    @Test
    void executeJsonQuery_throwsAfterClientIsClosed() throws Exception {
        Config cfg = testConfig("jdbc:h2:mem:closed_client_guard;MODE=MySQL;DB_CLOSE_DELAY=-1");
        DatabaseClient client = new DatabaseClient(cfg, MAPPER, REFERENCE_MAPPER);

        client.executeJsonQuery("select 1");
        client.close();

        assertThrows(SQLException.class, () -> client.executeJsonQuery("select 1"),
                "queries issued after close() must fail loudly, not silently reopen a connection nothing will ever close");
    }

    private static Config testConfig(String dbUrl) {
        return Config.builder()
                .projectRoot(Paths.get(".").toAbsolutePath().normalize())
                .manifests(List.of("APIsToBeValidated_A.csv"))
                .apis(Set.of())
                .businessCasesToTest(Set.of())
                .threads(1)
                .outputFile(Paths.get("target/test-output.json"))
                .mode("execute")
                .protocol("http")
                .server("localhost")
                .tenant("tenant")
                .username("user")
                .password("password")
                .tokenUrl("")
                .tokenPath("/token")
                .serviceBaseUrls(Map.of())
                .dbUrl(dbUrl)
                .dbUsername("sa")
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
                .build();
    }
}
