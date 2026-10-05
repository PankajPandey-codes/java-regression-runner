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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FileDownloadExecuteEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void executeModeStreamsFileResponseWithoutDecodingOrEmbeddingIt() throws Exception {
        byte[] download = new byte[] {0x1f, (byte) 0x8b, 0x08, 0x00, (byte) 0xff, 0x00, (byte) 0x80};
        AtomicReference<String> accept = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/identity/v1/token", exchange -> json(exchange, 200, "{\"token\":\"Bearer test\"}"));
        server.createContext("/api/test/v1/download", exchange -> {
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            exchange.getResponseHeaders().set("Content-Type", "application/gzip");
            exchange.sendResponseHeaders(200, download.length);
            exchange.getResponseBody().write(download);
            exchange.close();
        });
        server.start();
        try {
            writeFixture();
            int port = server.getAddress().getPort();
            Config cfg = Config.load(new String[] {
                    "--projectRoot=" + tempDir,
                    "--mode=execute",
                    "--manifests=APIsToBeValidated_A.csv",
                    "--apis=FileDownload",
                    "--protocol=http",
                    "--server=localhost:" + port,
                    "--username=user",
                    "--password=password",
                    "--tenant=tenant"
            });

            ExecuteEngine engine = new ExecuteEngine(cfg);
            ExecutionReport report = engine.runForManifest("APIsToBeValidated_A.csv");

            assertEquals(1, report.stepsPassed);
            assertEquals("application/gzip", accept.get());
            assertEquals("", report.passes.get(0).get("actual"));
            Path artifact = tempDir.resolve(String.valueOf(report.passes.get(0).get("jsonOutputArtifact")));
            assertArrayEquals(download, Files.readAllBytes(artifact));

            List<ExecutionReport> rebuilt = RunFolderReportBuilder.build(
                    tempDir,
                    engine.runFolder(),
                    "http",
                    "localhost:" + port
            );
            assertEquals("", rebuilt.get(0).passes.get(0).get("actual"));
            assertArrayEquals(download, Files.readAllBytes(artifact));
        } finally {
            server.stop(0);
        }
    }

    private void writeFixture() throws IOException {
        Files.createDirectories(tempDir.resolve("apisToBeValidated"));
        Files.createDirectories(tempDir.resolve("apiInputs"));
        Files.createDirectories(tempDir.resolve("fileFromJson/payloadJSON"));
        Files.createDirectories(tempDir.resolve("fileFromJson/expectedJSON"));
        Files.createDirectories(tempDir.resolve("java-regression-runner"));

        Files.writeString(
                tempDir.resolve("apisToBeValidated/APIsToBeValidated_A.csv"),
                "#,apiName,apiMethod,apiPath,apiInputsFile,toBeValidated,Module,fileType,payloadJSON,expectedJSON,consentPerson,scenario,mysql,BusinessFunction\n"
                        + "1,FileDownload,NA,NA,FileDownload.csv,y,Module,json,FileDownload.json,FileDownload.json,Owner,y,n,NA\n"
        );
        Files.writeString(
                tempDir.resolve("apiInputs/FileDownload.csv"),
                "testScenarioNumber,testObjective,urlParameter,expectedResponseCode\n"
                        + "1,download,NA,200\n"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/payloadJSON/FileDownload.json"),
                "[{\"apiName\":\"FileDownload1\",\"apiMethod\":\"GET\",\"apiPath\":\"/api/test/v1/download\","
                        + "\"accept\":\"application/gzip\",\"responseType\":\"file\",\"payload\":{}}]"
        );
        Files.writeString(
                tempDir.resolve("fileFromJson/expectedJSON/FileDownload.json"),
                "[{\"apiName\":\"FileDownload1\",\"expected\":\"\"}]"
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
