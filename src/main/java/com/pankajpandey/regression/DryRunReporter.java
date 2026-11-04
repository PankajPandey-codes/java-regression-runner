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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DryRunReporter {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static void write(Path outputFile, List<ManifestReport> reports, Path projectRoot) throws IOException {
        Files.createDirectories(outputFile.getParent());

        int totalRows = 0;
        int enabledStrict = 0;
        int enabledCaseInsensitive = 0;
        int missingInputs = 0;
        int missingPayload = 0;
        int missingExpected = 0;
        int warnings = 0;

        for (ManifestReport r : reports) {
            totalRows += r.totalRows;
            enabledStrict += r.enabledRowsStrict;
            enabledCaseInsensitive += r.enabledRowsCaseInsensitive;
            missingInputs += r.missingInputs;
            missingPayload += r.missingPayload;
            missingExpected += r.missingExpected;
            warnings += r.warnings.size();
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("generatedAt", Instant.now().toString());
        summary.put("mode", "dry-run");
        summary.put("projectRoot", projectRoot.toString());
        summary.put("totalRows", totalRows);
        summary.put("enabledRowsStrict", enabledStrict);
        summary.put("enabledRowsCaseInsensitive", enabledCaseInsensitive);
        summary.put("missingInputs", missingInputs);
        summary.put("missingPayload", missingPayload);
        summary.put("missingExpected", missingExpected);
        summary.put("warnings", warnings);
        summary.put("reports", reports);

        MAPPER.writeValue(outputFile.toFile(), summary);
    }
}
