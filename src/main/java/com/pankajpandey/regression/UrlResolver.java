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

final class UrlResolver {
    private UrlResolver() {}

    static String buildTokenUrl(Config cfg) {
        if (cfg.tokenUrl != null && !cfg.tokenUrl.isBlank()) {
            return cfg.tokenUrl.trim();
        }
        return buildTokenUrl(cfg.protocol, cfg.server, cfg.tokenPath, cfg.tokenUrl);
    }

    static String buildTokenUrl(String protocol, String server, String tokenPath, String tokenUrl) {
        if (tokenUrl != null && !tokenUrl.isBlank()) {
            return tokenUrl.trim();
        }
        return protocol + "://" + server + normalizePath(tokenPath);
    }

    static String buildApiUrl(Config cfg, String apiPath) {
        return buildApiUrl(cfg.protocol, cfg.server, cfg.serviceBaseUrls, apiPath);
    }

    static String buildApiUrl(String protocol, String server, java.util.Map<String, String> serviceBaseUrls, String apiPath) {
        String normalizedPath = normalizeApiPath(apiPath);
        if (isAbsoluteUrl(normalizedPath)) {
            return normalizedPath;
        }

        String serviceName = extractServiceName(normalizedPath);
        if (!serviceName.isEmpty()) {
            String baseUrl = serviceBaseUrls.get(serviceName);
            if (baseUrl != null && !baseUrl.isBlank()) {
                return joinBaseAndPath(baseUrl, normalizedPath);
            }
        }

        return protocol + "://" + server + normalizedPath;
    }

    static String normalizeApiPath(String apiPath) {
        String normalized = apiPath == null ? "" : apiPath.trim();
        if (normalized.isEmpty()) {
            return "/";
        }
        if (isAbsoluteUrl(normalized)) {
            return normalized;
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    static String extractServiceName(String apiPath) {
        String normalized = normalizeApiPath(apiPath);
        if (!normalized.startsWith("/api/")) {
            return "";
        }
        String[] parts = normalized.split("/");
        if (parts.length < 3) {
            return "";
        }
        return Config.normalizeServiceName(parts[2]);
    }

    private static boolean isAbsoluteUrl(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String trimmed = path.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private static String joinBaseAndPath(String baseUrl, String path) {
        String base = baseUrl.trim();
        String normalizedPath = normalizeApiPath(path);
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + normalizedPath;
    }
}
