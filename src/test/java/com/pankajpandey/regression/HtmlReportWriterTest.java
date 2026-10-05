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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesExactOwnerAndModuleFiltersIntoHtml() throws Exception {
        ExecutionReport report = new ExecutionReport();
        report.manifest = "APIsToBeValidated_A.csv";
        report.apiScenariosExecuted = 2;
        report.stepsExecuted = 2;
        report.stepsPassed = 1;
        report.stepsFailed = 1;
        report.failures = List.of(Map.of(
                "api", "FeeAmortisationLoanDisbursal",
                "stepApiName", "FeeAmortisationLoanDisbursal11",
                "module", "Fee Amortization",
                "owner", "Alice",
                "step", 11,
                "method", "POST",
                "url", "/feeamortization/amortize",
                "expectedCode", 200,
                "actualCode", 400,
                "comparison", List.of(Map.of("path", "error.message", "expected", "ok", "actual", "bad"))
        ));
        report.passes = List.of(Map.of(
                "api", "LoLValidation",
                "stepApiName", "LoLValidation1",
                "module", "LoL",
                "owner", "Bob",
                "step", 1,
                "method", "GET",
                "url", "/lol/validate",
                "expectedCode", 200,
                "actualCode", 200
        ));

        Path html = tempDir.resolve("execute-summary.html");
        HtmlReportWriter.write(html, List.of(report));

        String output = Files.readString(html);
        assertTrue(output.contains("onclick=\"setModuleFilter('Fee Amortization');return false;\""));
        assertTrue(output.contains("onclick=\"setOwnerFilter('Alice');return false;\""));
        assertTrue(output.contains("<select id='moduleFilter'"));
        assertTrue(output.contains("<option value='LoL'>LoL</option>"));
        assertTrue(output.contains("data-module='LoL'"));
        assertTrue(output.contains("var okModule=(module==='all'||mo===module);"));
        assertTrue(output.contains("Regression Analysis"));
        assertTrue(output.contains("Focused execution review recommended"));
        assertTrue(output.contains("statuspill"));
        assertTrue(output.contains("Generated on "));
        assertTrue(!output.contains("API Scenarios"));
    }
}
