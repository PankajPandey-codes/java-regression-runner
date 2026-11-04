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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonComparisonBehaviorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void executeComparison_flagsUnexpectedObjectField() throws Exception {
        ExecuteEngine engine = new ExecuteEngine(testConfig(tempDir));

        List<Map<String, Object>> mismatches = executeCompare(engine, "{\"status\":\"ok\"}", "{\"status\":\"ok\",\"extra\":1}");

        assertEquals(1, mismatches.size());
        assertEquals("extra", mismatches.get(0).get("path"));
        assertEquals("unexpected_field", mismatches.get(0).get("kind"));
    }

    @Test
    void executeComparison_flagsUnexpectedArrayItem() throws Exception {
        ExecuteEngine engine = new ExecuteEngine(testConfig(tempDir));

        List<Map<String, Object>> mismatches = executeCompare(engine, "[1,2]", "[1,2,3]");

        assertEquals(1, mismatches.size());
        assertEquals("[2]", mismatches.get(0).get("path"));
        assertEquals("unexpected_item", mismatches.get(0).get("kind"));
    }

    @Test
    void renderComparison_flagsUnexpectedObjectField() throws Exception {
        List<Map<String, Object>> mismatches = runFolderCompare("{\"status\":\"ok\"}", "{\"status\":\"ok\",\"extra\":1}");

        assertEquals(1, mismatches.size());
        assertEquals("extra", mismatches.get(0).get("path"));
        assertEquals("unexpected_field", mismatches.get(0).get("kind"));
    }

    @Test
    void renderComparison_flagsUnexpectedArrayItem() throws Exception {
        List<Map<String, Object>> mismatches = runFolderCompare("[1,2]", "[1,2,3]");

        assertEquals(1, mismatches.size());
        assertEquals("[2]", mismatches.get(0).get("path"));
        assertEquals("unexpected_item", mismatches.get(0).get("kind"));
    }

    @Test
    void executeAndRenderParseHighPrecisionArtifactsConsistentlyForComparison() throws Exception {
        ExecuteEngine engine = new ExecuteEngine(testConfig(tempDir));
        String artifact = "{\"amount\":0.10000000000000001}";
        JsonNode expected = MAPPER.readTree(artifact);

        List<Map<String, Object>> executeMismatches = executeCompare(
                engine,
                expected,
                executeParseResponse(engine, artifact)
        );
        List<Map<String, Object>> renderMismatches = runFolderCompare(
                expected,
                runFolderParseResponse(artifact)
        );

        assertEquals(renderMismatches, executeMismatches);
        assertTrue(executeMismatches.isEmpty());
    }

    @Test
    void unorderedArrayByKey_passesSwappedRows() throws Exception {
        List<Map<String, Object>> mismatches = compareWithExpectedStep(
                "{\"data\":[{\"id\":1,\"value\":\"a\"},{\"id\":2,\"value\":\"b\"}]}",
                "{\"data\":[{\"id\":2,\"value\":\"b\"},{\"id\":1,\"value\":\"a\"}]}",
                "{\"unorderedArrays\":{\"data\":{\"keyFields\":[\"id\"]}}}"
        );

        assertEquals(0, mismatches.size());
    }

    @Test
    void unorderedArrayByNestedKey_passesSwappedRows() throws Exception {
        List<Map<String, Object>> mismatches = compareWithExpectedStep(
                "{\"data\":[{\"batchDetails\":{\"entity\":\"fee\"},\"amount\":1},{\"batchDetails\":{\"entity\":\"rate\"},\"amount\":2}]}",
                "{\"data\":[{\"batchDetails\":{\"entity\":\"rate\"},\"amount\":2},{\"batchDetails\":{\"entity\":\"fee\"},\"amount\":1}]}",
                "{\"unorderedArrays\":{\"data\":{\"keyFields\":[\"batchDetails.entity\"]}}}"
        );

        assertEquals(0, mismatches.size());
    }

    @Test
    void unorderedNestedFeeDetails_passesSwappedRows() throws Exception {
        List<Map<String, Object>> mismatches = compareWithExpectedStep(
                "{\"data\":{\"feeDetails\":[{\"accountFeeId\":7538,\"loanId\":7771,\"loanAccountNumber\":\"BH38352GH\",\"paidAmount\":3000.0},{\"accountFeeId\":7539,\"loanId\":7772,\"loanAccountNumber\":\"BH38353GH\",\"paidAmount\":1000.0}]}}",
                "{\"data\":{\"feeDetails\":[{\"accountFeeId\":7539,\"loanId\":7772,\"loanAccountNumber\":\"BH38353GH\",\"paidAmount\":1000.0},{\"accountFeeId\":7538,\"loanId\":7771,\"loanAccountNumber\":\"BH38352GH\",\"paidAmount\":3000.0}]}}",
                "{\"unorderedArrays\":{\"data.feeDetails\":{\"keyFields\":[\"accountFeeId\",\"loanId\",\"loanAccountNumber\"]}}}"
        );

        assertEquals(0, mismatches.size());
    }

    @Test
    void unorderedArrayByKey_stillFailsChangedItem() throws Exception {
        List<Map<String, Object>> mismatches = compareWithExpectedStep(
                "{\"data\":[{\"id\":1,\"value\":\"a\"},{\"id\":2,\"value\":\"b\"}]}",
                "{\"data\":[{\"id\":2,\"value\":\"b\"},{\"id\":1,\"value\":\"changed\"}]}",
                "{\"unorderedArrays\":{\"data\":{\"keyFields\":[\"id\"]}}}"
        );

        assertEquals(1, mismatches.size());
        assertEquals("data[id=1].value", mismatches.get(0).get("path"));
        assertEquals("value_mismatch", mismatches.get(0).get("kind"));
    }

    @Test
    void unorderedArrayByKey_stillFailsUnexpectedItem() throws Exception {
        List<Map<String, Object>> mismatches = compareWithExpectedStep(
                "{\"data\":[{\"id\":1,\"value\":\"a\"}]}",
                "{\"data\":[{\"id\":1,\"value\":\"a\"},{\"id\":2,\"value\":\"b\"}]}",
                "{\"unorderedArrays\":{\"data\":{\"keyFields\":[\"id\"]}}}"
        );

        assertEquals(1, mismatches.size());
        assertEquals("data[id=2]", mismatches.get(0).get("path"));
        assertEquals("unexpected_item", mismatches.get(0).get("kind"));
    }

    @Test
    void unorderedArrayByKey_reportsMissingItemByKey() throws Exception {
        List<Map<String, Object>> mismatches = compareWithExpectedStep(
                "{\"data\":[{\"id\":1,\"value\":\"a\"},{\"id\":2,\"value\":\"b\"}]}",
                "{\"data\":[{\"id\":2,\"value\":\"b\"}]}",
                "{\"unorderedArrays\":{\"data\":{\"keyFields\":[\"id\"]}}}"
        );

        assertEquals(1, mismatches.size());
        assertEquals("data[id=1]", mismatches.get(0).get("path"));
        assertEquals("missing_item", mismatches.get(0).get("kind"));
    }

    @Test
    void unorderedArrayByKey_reportsDuplicateActualKey() throws Exception {
        List<Map<String, Object>> mismatches = compareWithExpectedStep(
                "{\"data\":[{\"id\":1,\"value\":\"a\"}]}",
                "{\"data\":[{\"id\":1,\"value\":\"a\"},{\"id\":1,\"value\":\"b\"}]}",
                "{\"unorderedArrays\":{\"data\":{\"keyFields\":[\"id\"]}}}"
        );

        assertEquals(1, mismatches.size());
        assertEquals("data[id=1]", mismatches.get(0).get("path"));
        assertEquals("duplicate_actual_key", mismatches.get(0).get("kind"));
    }

    @Test
    void unorderedArrayByKey_reportsDuplicateExpectedKey() throws Exception {
        List<Map<String, Object>> mismatches = compareWithExpectedStep(
                "{\"data\":[{\"id\":1,\"value\":\"a\"},{\"id\":1,\"value\":\"b\"}]}",
                "{\"data\":[{\"id\":1,\"value\":\"a\"}]}",
                "{\"unorderedArrays\":{\"data\":{\"keyFields\":[\"id\"]}}}"
        );

        assertEquals(1, mismatches.size());
        assertEquals("data[id=1]", mismatches.get(0).get("path"));
        assertEquals("duplicate_expected_key", mismatches.get(0).get("kind"));
    }

    @Test
    void unorderedArrayByKey_keepsNestedUnorderedArrayConfigActive() throws Exception {
        List<Map<String, Object>> mismatches = compareWithExpectedStep(
                "{\"parents\":[{\"id\":1,\"children\":[{\"cid\":1,\"value\":\"a\"},{\"cid\":2,\"value\":\"b\"}]},{\"id\":2,\"children\":[{\"cid\":3,\"value\":\"c\"}]}]}",
                "{\"parents\":[{\"id\":2,\"children\":[{\"cid\":3,\"value\":\"c\"}]},{\"id\":1,\"children\":[{\"cid\":2,\"value\":\"b\"},{\"cid\":1,\"value\":\"a\"}]}]}",
                "{\"unorderedArrays\":{\"parents\":{\"keyFields\":[\"id\"]},\"parents.children\":{\"keyFields\":[\"cid\"]}}}"
        );

        assertEquals(0, mismatches.size());
    }

    @Test
    void unorderedArrayScopedIgnore_doesNotLeakOutsideArray() throws Exception {
        List<Map<String, Object>> mismatches = compareWithExpectedStep(
                "{\"entities\":[{\"id\":1,\"runningTotal\":10},{\"id\":2,\"runningTotal\":20}],\"runningTotal\":20}",
                "{\"entities\":[{\"id\":2,\"runningTotal\":99},{\"id\":1,\"runningTotal\":88}],\"runningTotal\":21}",
                "{\"unorderedArrays\":{\"entities\":{\"keyFields\":[\"id\"],\"ignore\":[\"runningTotal\"]}}}"
        );

        assertEquals(1, mismatches.size());
        assertEquals("runningTotal", mismatches.get(0).get("path"));
    }

    @Test
    void unorderedArrayScopedIgnore_passesInsideArrayOnly() throws Exception {
        List<Map<String, Object>> mismatches = compareWithExpectedStep(
                "{\"entities\":[{\"id\":1,\"runningTotal\":10},{\"id\":2,\"runningTotal\":20}],\"runningTotal\":20}",
                "{\"entities\":[{\"id\":2,\"runningTotal\":99},{\"id\":1,\"runningTotal\":88}],\"runningTotal\":20}",
                "{\"unorderedArrays\":{\"entities\":{\"keyFields\":[\"id\"],\"ignore\":[\"runningTotal\"]}}}"
        );

        assertEquals(0, mismatches.size());
    }

    @Test
    void arraysStayStrictWithoutUnorderedConfig() throws Exception {
        List<Map<String, Object>> mismatches = compareWithExpectedStep(
                "{\"data\":[{\"id\":1,\"value\":\"a\"},{\"id\":2,\"value\":\"b\"}]}",
                "{\"data\":[{\"id\":2,\"value\":\"b\"},{\"id\":1,\"value\":\"a\"}]}",
                "{}"
        );

        assertFalse(mismatches.isEmpty());
    }

    @Test
    void unorderedArrayDoesNotUseLastSegmentPathMatching() throws Exception {
        List<Map<String, Object>> mismatches = compareWithExpectedStep(
                "{\"outer\":{\"data\":[{\"id\":1},{\"id\":2}]}}",
                "{\"outer\":{\"data\":[{\"id\":2},{\"id\":1}]}}",
                "{\"unorderedArrays\":{\"data\":{\"keyFields\":[\"id\"]}}}"
        );

        assertFalse(mismatches.isEmpty());
    }

    private static List<Map<String, Object>> executeCompare(ExecuteEngine engine, String expected, String actual) throws Exception {
        return executeCompare(engine, MAPPER.readTree(expected), MAPPER.readTree(actual));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> executeCompare(ExecuteEngine engine, JsonNode expected, JsonNode actual) throws Exception {
        Method method = ExecuteEngine.class.getDeclaredMethod("compareExpected", JsonNode.class, JsonNode.class, Set.class);
        method.setAccessible(true);
        return (List<Map<String, Object>>) method.invoke(
                engine,
                expected,
                actual,
                Collections.emptySet()
        );
    }

    private static List<Map<String, Object>> runFolderCompare(String expected, String actual) throws Exception {
        return runFolderCompare(MAPPER.readTree(expected), MAPPER.readTree(actual));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> runFolderCompare(JsonNode expected, JsonNode actual) throws Exception {
        Method method = RunFolderReportBuilder.class.getDeclaredMethod("compareExpected", JsonNode.class, JsonNode.class, Set.class);
        method.setAccessible(true);
        return (List<Map<String, Object>>) method.invoke(
                null,
                expected,
                actual,
                Collections.emptySet()
        );
    }

    private static JsonNode executeParseResponse(ExecuteEngine engine, String actual) throws Exception {
        Method method = ExecuteEngine.class.getDeclaredMethod("safeParseJson", String.class, String.class, int.class);
        method.setAccessible(true);
        return (JsonNode) method.invoke(engine, actual, "PrecisionApi", 1);
    }

    private static JsonNode runFolderParseResponse(String actual) throws Exception {
        Method method = RunFolderReportBuilder.class.getDeclaredMethod("safeParseJson", String.class);
        method.setAccessible(true);
        return (JsonNode) method.invoke(null, actual);
    }

    private static List<Map<String, Object>> compareWithExpectedStep(String expected, String actual, String expectedStep) throws Exception {
        return JsonComparator.compare(
                MAPPER.readTree(expected),
                MAPPER.readTree(actual),
                JsonComparator.configFromExpectedStep(MAPPER.readTree(expectedStep))
        );
    }

    private static Config testConfig(Path projectRoot) {
        return Config.builder()
                .projectRoot(projectRoot.toAbsolutePath().normalize())
                .manifests(List.of("APIsToBeValidated_A.csv"))
                .apis(Set.of())
                .businessCasesToTest(Set.of())
                .threads(1)
                .outputFile(projectRoot.resolve("output/test-output.json"))
                .mode("execute")
                .protocol("http")
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
                .build();
    }
}
