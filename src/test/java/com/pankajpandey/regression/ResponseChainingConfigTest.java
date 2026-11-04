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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseChainingConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void responseChainingDefaultsToEnabled() {
        Config cfg = Config.load(new String[] { "--projectRoot=" + tempDir });

        assertTrue(cfg.responseChainingEnabled);
    }

    @Test
    void responseChainingCanBeEnabledByCliFlag() {
        Config cfg = Config.load(new String[] { "--projectRoot=" + tempDir, "--responseChaining.enabled=true" });

        assertTrue(cfg.responseChainingEnabled);
    }

    @Test
    void responseChainingCanBeDisabledByCliFlag() {
        Config cfg = Config.load(new String[] { "--projectRoot=" + tempDir, "--responseChaining.enabled=false" });

        assertFalse(cfg.responseChainingEnabled);
    }

    @Test
    void responseChainingCanBeEnabledFromRunnerEnv() throws Exception {
        Files.writeString(tempDir.resolve("runner.env"), "responseChaining.enabled=true\n");

        Config cfg = Config.load(new String[] { "--projectRoot=" + tempDir });

        assertTrue(cfg.responseChainingEnabled);
    }

    @Test
    void responseChainingCanBeDisabledFromRunnerEnv() throws Exception {
        Files.writeString(tempDir.resolve("runner.env"), "JR_RESPONSE_CHAINING_ENABLED=false\n");

        Config cfg = Config.load(new String[] { "--projectRoot=" + tempDir });

        assertFalse(cfg.responseChainingEnabled);
    }
}
