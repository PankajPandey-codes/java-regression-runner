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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class AuthClient {
    private static final Logger log = LoggerFactory.getLogger(AuthClient.class);
    private static final int MAX_TOKEN_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 2_000L;

    private final Config cfg;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthClient(Config cfg) {
        this.cfg = cfg;
        HttpClient.Builder hb = HttpClient.newBuilder();
        if (cfg.connectTimeoutMs > 0) {
            hb.connectTimeout(Duration.ofMillis(cfg.connectTimeoutMs));
        }
        hb.version(HttpClient.Version.HTTP_1_1);
        this.http = hb.build();
    }

    public String fetchToken() throws Exception {
        validateConfig();
        String url = UrlResolver.buildTokenUrl(cfg);
        String body = String.format(
                "{\"grant_type\":\"password\",\"username\":\"%s\",\"password\":\"%s\",\"refreshTokenParam\":null}",
                escape(cfg.username),
                escape(cfg.password)
        );

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Identifier", cfg.tenant)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cfg.readTimeoutMs > 0) {
            rb.timeout(Duration.ofMillis(cfg.readTimeoutMs));
        }
        HttpRequest request = rb.build();

        HttpResponse<String> resp = sendWithRetry(request, url);
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Token API failed: status=" + resp.statusCode() + " body=" + safe(resp.body()));
        }

        JsonNode n = mapper.readTree(resp.body());
        String token = getTokenFromNode(n);
        if (token == null || token.isBlank()) {
            throw new RuntimeException("Token not found in response: " + safe(resp.body()));
        }
        return token;
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request, String url) throws Exception {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_TOKEN_ATTEMPTS; attempt++) {
            try {
                return http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                last = e;
                if (attempt >= MAX_TOKEN_ATTEMPTS) break;
                log.warn("Token request failed attempt={} url={} error={}. Retrying", attempt, url, safe(e.getMessage()));
                sleepBeforeRetry();
            }
        }
        throw last == null ? new IOException("Token request failed without a captured cause") : last;
    }

    private void sleepBeforeRetry() throws InterruptedException {
        Thread.sleep(RETRY_DELAY_MS);
    }

    private void validateConfig() {
        List<String> missing = new ArrayList<>();
        if (cfg.username == null || cfg.username.isBlank()) missing.add("JR_USERNAME");
        if (cfg.password == null || cfg.password.isBlank()) missing.add("JR_PASSWORD");
        if (cfg.tenant == null || cfg.tenant.isBlank()) missing.add("JR_TENANT");
        if (cfg.tokenUrl == null || cfg.tokenUrl.isBlank()) {
            if (cfg.server == null || cfg.server.isBlank()) missing.add("JR_SERVER");
            if (cfg.tokenPath == null || cfg.tokenPath.isBlank()) missing.add("JR_TOKEN_PATH");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing auth configuration: " + String.join(", ", missing));
        }
    }

    private static String getTokenFromNode(JsonNode n) {
        if (n.hasNonNull("accessToken")) return n.get("accessToken").asText();
        if (n.hasNonNull("access_token")) return n.get("access_token").asText();
        if (n.hasNonNull("token")) return n.get("token").asText();
        if (n.has("data") && n.get("data").hasNonNull("token")) return n.get("data").get("token").asText();
        return null;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
