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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunStatusWriterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void completedSuccessMapsSuiteStatusToPass() throws Exception {
        JsonNode status = writeStatus("COMPLETED_SUCCESS", 10, 10, 0, 0);

        assertEquals("COMPLETED_SUCCESS", status.get("status").asText());
        assertEquals("PASS", status.get("suiteStatus").asText());
        assertTrue(status.get("suitePassed").asBoolean());
    }

    @Test
    void completedWithTestFailuresMapsSuiteStatusToPass() throws Exception {
        JsonNode status = writeStatus("COMPLETED_WITH_TEST_FAILURES", 10, 9, 1, 0);

        assertEquals("PASS", status.get("suiteStatus").asText());
        assertTrue(status.get("suitePassed").asBoolean());
    }

    @Test
    void infrastructureFailureMapsSuiteStatusToFail() throws Exception {
        JsonNode status = writeStatus("FAILED_INFRA", 10, 10, 0, 1);

        assertEquals("FAIL", status.get("suiteStatus").asText());
        assertFalse(status.get("suitePassed").asBoolean());
    }

    @Test
    void runningStatusKeepsSuiteStatusUnknown() throws Exception {
        JsonNode status = writeStatus("RUNNING", 0, 0, 0, 0);

        assertEquals("UNKNOWN", status.get("suiteStatus").asText());
        assertFalse(status.get("suitePassed").asBoolean());
    }

    @Test
    void writesPreferredRegressionReportHtmlPath() throws Exception {
        JsonNode status = writeStatus("COMPLETED_WITH_TEST_FAILURES", 10, 9, 1, 0);

        Path expected = tempDir.resolve("COMPLETED_WITH_TEST_FAILURES")
                .resolve("java-regression-runner")
                .resolve("results")
                .resolve("15-05-2026_1111AM")
                .resolve("regression-report.html")
                .toAbsolutePath()
                .normalize();
        assertEquals(expected.toString(), status.path("regressionReportHtml").asText());
    }

    private JsonNode writeStatus(
            String status,
            int stepsExecuted,
            int stepsPassed,
            int stepsFailed,
            int infrastructureFailures
    ) throws Exception {
        Path projectRoot = tempDir.resolve(status);
        Files.createDirectories(projectRoot.resolve("java-regression-runner"));
        String runFolder = "15-05-2026_1111AM";
        RunStatusWriter.write(
                projectRoot,
                runFolder,
                status,
                Instant.parse("2026-05-15T11:11:00Z"),
                "test",
                List.of("APIsToBeValidated_A.csv"),
                stepsExecuted,
                stepsPassed,
                stepsFailed,
                infrastructureFailures
        );
        Path statusFile = projectRoot.resolve("java-regression-runner")
                .resolve("results")
                .resolve(runFolder)
                .resolve("run-status.json");
        assertTrue(Files.exists(statusFile));
        return MAPPER.readTree(statusFile.toFile());
    }
}
