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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ExistingExecuteReportRenderer {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static Path render(Path inputJson, Path outputHtml) throws Exception {
        JsonNode root = MAPPER.readTree(inputJson.toFile());
        JsonNode reportsNode = root.get("reports");

        if (reportsNode == null || !reportsNode.isArray()) {
            throw new IllegalArgumentException("Input file does not contain execute 'reports' array: " + inputJson);
        }

        List<ExecutionReport> reports = new ArrayList<>();
        for (JsonNode r : reportsNode) {
            reports.add(MAPPER.treeToValue(r, ExecutionReport.class));
        }

        HtmlReportWriter.write(outputHtml, reports);
        return outputHtml;
    }
}
