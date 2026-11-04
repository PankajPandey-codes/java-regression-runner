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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Config {
    public final Path projectRoot;
    public final List<String> manifests;
    public final Set<String> apis;
    public final Set<String> businessCasesToTest;
    public final int threads;
    public final Path outputFile;
    public final Path envFile;

    public final String mode;
    public final String protocol;
    public final String server;
    public final String tenant;
    public final String username;
    public final String password;
    public final String tokenUrl;
    public final String tokenPath;
    public final Map<String, String> serviceBaseUrls;
    public final String dbUrl;
    public final String dbUsername;
    public final String dbPassword;
    public final String dbDriver;
    public final int dbConnectTimeoutSeconds;
    public final int dbQueryTimeoutSeconds;
    public final int connectTimeoutMs;
    public final int readTimeoutMs;
    public final int limitApisPerManifest;
    public final int limitStepsPerApi;
    public final Path reportInput;
    public final Path reportHtmlOutput;
    public final String runFolder;
    public final int tokenRefreshMinutes;
    public final int partialReportIntervalSeconds;
    public final boolean failOnTestFailures;
    public final boolean autoAnalyze;
    public final boolean targetedCiMode;
    public final boolean ciHeadersEnabled;
    public final boolean responseChainingEnabled;
    public final String repo;
    public final String commitSha;
    public final String previousGreenSha;

    private Config(Builder builder) {
        this.projectRoot = builder.projectRoot;
        this.manifests = builder.manifests == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(builder.manifests));
        this.apis = builder.apis == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(builder.apis));
        this.businessCasesToTest = builder.businessCasesToTest == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(builder.businessCasesToTest));
        this.threads = builder.threads;
        this.outputFile = builder.outputFile;
        this.envFile = builder.envFile;
        this.mode = builder.mode;
        this.protocol = builder.protocol;
        this.server = builder.server;
        this.tenant = builder.tenant;
        this.username = builder.username;
        this.password = builder.password;
        this.tokenUrl = builder.tokenUrl;
        this.tokenPath = builder.tokenPath;
        this.serviceBaseUrls = builder.serviceBaseUrls == null
                ? Map.of()
                : Collections.unmodifiableMap(new HashMap<>(builder.serviceBaseUrls));
        this.dbUrl = builder.dbUrl;
        this.dbUsername = builder.dbUsername;
        this.dbPassword = builder.dbPassword;
        this.dbDriver = builder.dbDriver;
        this.dbConnectTimeoutSeconds = builder.dbConnectTimeoutSeconds;
        this.dbQueryTimeoutSeconds = builder.dbQueryTimeoutSeconds;
        this.connectTimeoutMs = builder.connectTimeoutMs;
        this.readTimeoutMs = builder.readTimeoutMs;
        this.limitApisPerManifest = builder.limitApisPerManifest;
        this.limitStepsPerApi = builder.limitStepsPerApi;
        this.reportInput = builder.reportInput;
        this.reportHtmlOutput = builder.reportHtmlOutput;
        this.runFolder = builder.runFolder;
        this.tokenRefreshMinutes = builder.tokenRefreshMinutes;
        this.partialReportIntervalSeconds = builder.partialReportIntervalSeconds;
        this.failOnTestFailures = builder.failOnTestFailures;
        this.autoAnalyze = builder.autoAnalyze;
        this.targetedCiMode = builder.targetedCiMode;
        this.ciHeadersEnabled = builder.ciHeadersEnabled;
        this.responseChainingEnabled = builder.responseChainingEnabled;
        this.repo = builder.repo == null ? "" : builder.repo.trim();
        this.commitSha = builder.commitSha == null ? "" : builder.commitSha.trim();
        this.previousGreenSha = builder.previousGreenSha == null ? "" : builder.previousGreenSha.trim();
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private Path projectRoot;
        private List<String> manifests = List.of();
        private Set<String> apis = Set.of();
        private Set<String> businessCasesToTest = Set.of();
        private int threads;
        private Path outputFile;
        private Path envFile;
        private String mode = "dry-run";
        private String protocol = "https";
        private String server = "";
        private String tenant = "";
        private String username = "";
        private String password = "";
        private String tokenUrl = "";
        private String tokenPath = "";
        private Map<String, String> serviceBaseUrls = Map.of();
        private String dbUrl = "";
        private String dbUsername = "";
        private String dbPassword = "";
        private String dbDriver = "";
        private int dbConnectTimeoutSeconds;
        private int dbQueryTimeoutSeconds;
        private int connectTimeoutMs;
        private int readTimeoutMs;
        private int limitApisPerManifest;
        private int limitStepsPerApi;
        private Path reportInput;
        private Path reportHtmlOutput;
        private String runFolder;
        private int tokenRefreshMinutes;
        private int partialReportIntervalSeconds;
        private boolean failOnTestFailures;
        private boolean autoAnalyze;
        private boolean targetedCiMode;
        private boolean ciHeadersEnabled;
        private boolean responseChainingEnabled;
        private String repo = "";
        private String commitSha = "";
        private String previousGreenSha = "";

        private Builder() {}

        Builder projectRoot(Path projectRoot) { this.projectRoot = projectRoot; return this; }
        Builder manifests(List<String> manifests) { this.manifests = manifests; return this; }
        Builder apis(Set<String> apis) { this.apis = apis; return this; }
        Builder businessCasesToTest(Set<String> businessCasesToTest) { this.businessCasesToTest = businessCasesToTest; return this; }
        Builder threads(int threads) { this.threads = threads; return this; }
        Builder outputFile(Path outputFile) { this.outputFile = outputFile; return this; }
        Builder envFile(Path envFile) { this.envFile = envFile; return this; }
        Builder mode(String mode) { this.mode = mode; return this; }
        Builder protocol(String protocol) { this.protocol = protocol; return this; }
        Builder server(String server) { this.server = server; return this; }
        Builder tenant(String tenant) { this.tenant = tenant; return this; }
        Builder username(String username) { this.username = username; return this; }
        Builder password(String password) { this.password = password; return this; }
        Builder tokenUrl(String tokenUrl) { this.tokenUrl = tokenUrl; return this; }
        Builder tokenPath(String tokenPath) { this.tokenPath = tokenPath; return this; }
        Builder serviceBaseUrls(Map<String, String> serviceBaseUrls) { this.serviceBaseUrls = serviceBaseUrls; return this; }
        Builder dbUrl(String dbUrl) { this.dbUrl = dbUrl; return this; }
        Builder dbUsername(String dbUsername) { this.dbUsername = dbUsername; return this; }
        Builder dbPassword(String dbPassword) { this.dbPassword = dbPassword; return this; }
        Builder dbDriver(String dbDriver) { this.dbDriver = dbDriver; return this; }
        Builder dbConnectTimeoutSeconds(int dbConnectTimeoutSeconds) { this.dbConnectTimeoutSeconds = dbConnectTimeoutSeconds; return this; }
        Builder dbQueryTimeoutSeconds(int dbQueryTimeoutSeconds) { this.dbQueryTimeoutSeconds = dbQueryTimeoutSeconds; return this; }
        Builder connectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; return this; }
        Builder readTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; return this; }
        Builder limitApisPerManifest(int limitApisPerManifest) { this.limitApisPerManifest = limitApisPerManifest; return this; }
        Builder limitStepsPerApi(int limitStepsPerApi) { this.limitStepsPerApi = limitStepsPerApi; return this; }
        Builder reportInput(Path reportInput) { this.reportInput = reportInput; return this; }
        Builder reportHtmlOutput(Path reportHtmlOutput) { this.reportHtmlOutput = reportHtmlOutput; return this; }
        Builder runFolder(String runFolder) { this.runFolder = runFolder; return this; }
        Builder tokenRefreshMinutes(int tokenRefreshMinutes) { this.tokenRefreshMinutes = tokenRefreshMinutes; return this; }
        Builder partialReportIntervalSeconds(int partialReportIntervalSeconds) { this.partialReportIntervalSeconds = partialReportIntervalSeconds; return this; }
        Builder failOnTestFailures(boolean failOnTestFailures) { this.failOnTestFailures = failOnTestFailures; return this; }
        Builder autoAnalyze(boolean autoAnalyze) { this.autoAnalyze = autoAnalyze; return this; }
        Builder targetedCiMode(boolean targetedCiMode) { this.targetedCiMode = targetedCiMode; return this; }
        Builder ciHeadersEnabled(boolean ciHeadersEnabled) { this.ciHeadersEnabled = ciHeadersEnabled; return this; }
        Builder responseChainingEnabled(boolean responseChainingEnabled) { this.responseChainingEnabled = responseChainingEnabled; return this; }
        Builder repo(String repo) { this.repo = repo; return this; }
        Builder commitSha(String commitSha) { this.commitSha = commitSha; return this; }
        Builder previousGreenSha(String previousGreenSha) { this.previousGreenSha = previousGreenSha; return this; }

        Config build() {
            return new Config(this);
        }
    }

    public static Config load(String[] args) {
        Map<String, String> flags = parseArgs(args);

        Path projectRoot = Paths.get(flags.getOrDefault("projectRoot", ".")).toAbsolutePath().normalize();
        Path runnerRoot = PathResolver.runnerRoot(projectRoot);
        Path envFile = resolveEnvFile(flags, runnerRoot);
        Map<String, String> fileEnv = loadEnvFile(envFile);

        String manifestsArg = flags.getOrDefault(
                "manifests",
                "APIsToBeValidated_A.csv,APIsToBeValidated_B.csv,APIsToBeValidated_C.csv"
        );

        List<String> manifests = new ArrayList<>();
        for (String m : manifestsArg.split(",")) {
            String v = m.trim();
            if (!v.isEmpty()) manifests.add(v);
        }

        Set<String> apis = new LinkedHashSet<>();
        String apisArg = firstNonBlank(
                flags.get("apis"),
                System.getenv("JR_APIS"),
                fileEnv.get("JR_APIS")
        );
        if (!apisArg.isBlank()) {
            for (String api : apisArg.split(",")) {
                String value = normalizeApiName(api);
                if (!value.isEmpty()) {
                    apis.add(value);
                }
            }
        }

        Set<String> businessCasesToTest = new LinkedHashSet<>();
        String businessCasesArg = firstNonBlank(
                flags.get("businessCasesToTest"),
                System.getenv("JR_BUSINESS_CASES_TO_TEST"),
                fileEnv.get("JR_BUSINESS_CASES_TO_TEST")
        );
        if (!businessCasesArg.isBlank()) {
            for (String raw : splitBusinessFunctions(businessCasesArg, ",")) {
                String normalized = normalizeBusinessFunction(raw);
                if (!normalized.isEmpty()) {
                    businessCasesToTest.add(normalized);
                }
            }
        }

        int threads = Integer.parseInt(firstNonBlank(
                flags.get("threads"),
                System.getenv("JR_THREADS"),
                fileEnv.get("JR_THREADS"),
                Integer.toString(Math.max(3, manifests.size()))
        ));
        if (threads < 1) threads = 1;

        String mode = flags.getOrDefault("mode", "dry-run").trim().toLowerCase();

        Path outputFile = Paths.get(flags.getOrDefault(
                "output",
                runnerRoot.resolve("output/" + ("execute".equals(mode) ? "execute-summary.json" : "dry-run-summary.json")).toString()
        ));

        String protocol = firstNonBlank(flags.get("protocol"), System.getenv("JR_PROTOCOL"), fileEnv.get("JR_PROTOCOL"), "https");
        String server = firstNonBlank(flags.get("server"), System.getenv("JR_SERVER"), fileEnv.get("JR_SERVER"), "api.example.com");
        String tenant = firstNonBlank(flags.get("tenant"), System.getenv("JR_TENANT"), fileEnv.get("JR_TENANT"), "default");
        String username = firstNonBlank(flags.get("username"), System.getenv("JR_USERNAME"), fileEnv.get("JR_USERNAME"), "user");
        String password = firstNonBlank(flags.get("password"), System.getenv("JR_PASSWORD"), fileEnv.get("JR_PASSWORD"));
        String tokenUrl = firstNonBlank(flags.get("tokenUrl"), System.getenv("JR_TOKEN_URL"), fileEnv.get("JR_TOKEN_URL"));
        String tokenPath = firstNonBlank(flags.get("tokenPath"), System.getenv("JR_TOKEN_PATH"), fileEnv.get("JR_TOKEN_PATH"), "/identity/v1/token");
        Map<String, String> serviceBaseUrls = parseServiceBaseUrls(firstNonBlank(
                flags.get("serviceBaseUrls"),
                System.getenv("JR_SERVICE_BASE_URLS"),
                fileEnv.get("JR_SERVICE_BASE_URLS")
        ));
        String dbUrl = firstNonBlank(flags.get("dbUrl"), System.getenv("JR_DB_URL"), fileEnv.get("JR_DB_URL"));
        String dbUsername = firstNonBlank(flags.get("dbUsername"), System.getenv("JR_DB_USERNAME"), fileEnv.get("JR_DB_USERNAME"));
        String dbPassword = firstNonBlank(flags.get("dbPassword"), System.getenv("JR_DB_PASSWORD"), fileEnv.get("JR_DB_PASSWORD"));
        String dbDriver = firstNonBlank(flags.get("dbDriver"), System.getenv("JR_DB_DRIVER"), fileEnv.get("JR_DB_DRIVER"), "com.mysql.cj.jdbc.Driver");
        int dbConnectTimeoutSeconds = Integer.parseInt(firstNonBlank(
                flags.get("dbConnectTimeoutSeconds"),
                System.getenv("JR_DB_CONNECT_TIMEOUT_SECONDS"),
                fileEnv.get("JR_DB_CONNECT_TIMEOUT_SECONDS"),
                "30"
        ));
        int dbQueryTimeoutSeconds = Integer.parseInt(firstNonBlank(
                flags.get("dbQueryTimeoutSeconds"),
                System.getenv("JR_DB_QUERY_TIMEOUT_SECONDS"),
                fileEnv.get("JR_DB_QUERY_TIMEOUT_SECONDS"),
                "120"
        ));

        int connectTimeoutMs = Integer.parseInt(firstNonBlank(
                flags.get("connectTimeoutMs"),
                System.getenv("JR_CONNECT_TIMEOUT_MS"),
                fileEnv.get("JR_CONNECT_TIMEOUT_MS"),
                "60000"
        ));
        int readTimeoutMs = Integer.parseInt(firstNonBlank(
                flags.get("readTimeoutMs"),
                System.getenv("JR_READ_TIMEOUT_MS"),
                fileEnv.get("JR_READ_TIMEOUT_MS"),
                "900000"
        ));

        int limitApisPerManifest = Integer.parseInt(flags.getOrDefault("limitApisPerManifest", "0"));
        int limitStepsPerApi = Integer.parseInt(flags.getOrDefault("limitStepsPerApi", "0"));

        Path reportInput = flags.containsKey("reportInput") ? Paths.get(flags.get("reportInput")) : null;
        Path reportHtmlOutput = flags.containsKey("reportHtml") ? Paths.get(flags.get("reportHtml")) : null;
        String runFolder = flags.getOrDefault("runFolder", "").trim();
        if (runFolder.isEmpty()) runFolder = null;
        int tokenRefreshMinutes = Integer.parseInt(firstNonBlank(
                flags.get("tokenRefreshMinutes"),
                System.getenv("JR_TOKEN_REFRESH_MINUTES"),
                fileEnv.get("JR_TOKEN_REFRESH_MINUTES"),
                "10"
        ));
        if (tokenRefreshMinutes < 1) tokenRefreshMinutes = 1;
        int partialReportIntervalSeconds = Integer.parseInt(firstNonBlank(
                flags.get("partialReportIntervalSeconds"),
                System.getenv("JR_PARTIAL_REPORT_INTERVAL_SECONDS"),
                fileEnv.get("JR_PARTIAL_REPORT_INTERVAL_SECONDS"),
                "300"
        ));
        if (partialReportIntervalSeconds < 15) partialReportIntervalSeconds = 15;
        boolean failOnTestFailures = parseBoolean(
                firstNonBlank(flags.get("failOnTestFailures"), System.getenv("JR_FAIL_ON_TEST_FAILURES"), fileEnv.get("JR_FAIL_ON_TEST_FAILURES")),
                false
        );
        boolean autoAnalyze = parseBoolean(
                firstNonBlank(flags.get("autoAnalyze"), System.getenv("JR_AUTO_ANALYZE"), fileEnv.get("JR_AUTO_ANALYZE")),
                false
        );
        boolean targetedCiMode = parseBoolean(
                firstNonBlank(flags.get("targetedCiMode"), System.getenv("JR_TARGETED_CI_MODE"), fileEnv.get("JR_TARGETED_CI_MODE")),
                false
        );
        boolean ciHeadersEnabled = parseBoolean(
                firstNonBlank(flags.get("ciHeadersEnabled"), System.getenv("JR_CI_HEADERS_ENABLED"), fileEnv.get("JR_CI_HEADERS_ENABLED")),
                false
        );
        boolean responseChainingEnabled = parseBoolean(
                firstNonBlank(
                        flags.get("responseChaining.enabled"),
                        flags.get("responseChainingEnabled"),
                        System.getenv("JR_RESPONSE_CHAINING_ENABLED"),
                        fileEnv.get("responseChaining.enabled"),
                        fileEnv.get("JR_RESPONSE_CHAINING_ENABLED")
                ),
                true
        );
        String repo = firstNonBlank(flags.get("repo"), System.getenv("JR_REPO"), fileEnv.get("JR_REPO"));
        String commitSha = firstNonBlank(flags.get("commitSha"), System.getenv("JR_COMMIT_SHA"), fileEnv.get("JR_COMMIT_SHA"));
        String previousGreenSha = firstNonBlank(
                flags.get("previousGreenSha"),
                System.getenv("JR_PREVIOUS_GREEN_SHA"),
                fileEnv.get("JR_PREVIOUS_GREEN_SHA")
        );

        return Config.builder()
                .projectRoot(projectRoot)
                .manifests(manifests)
                .apis(apis)
                .businessCasesToTest(businessCasesToTest)
                .threads(threads)
                .outputFile(outputFile)
                .envFile(envFile)
                .mode(mode)
                .protocol(protocol)
                .server(server)
                .tenant(tenant)
                .username(username)
                .password(password)
                .tokenUrl(tokenUrl)
                .tokenPath(tokenPath)
                .serviceBaseUrls(serviceBaseUrls)
                .dbUrl(dbUrl)
                .dbUsername(dbUsername)
                .dbPassword(dbPassword)
                .dbDriver(dbDriver)
                .dbConnectTimeoutSeconds(dbConnectTimeoutSeconds)
                .dbQueryTimeoutSeconds(dbQueryTimeoutSeconds)
                .connectTimeoutMs(connectTimeoutMs)
                .readTimeoutMs(readTimeoutMs)
                .limitApisPerManifest(limitApisPerManifest)
                .limitStepsPerApi(limitStepsPerApi)
                .reportInput(reportInput)
                .reportHtmlOutput(reportHtmlOutput)
                .runFolder(runFolder)
                .tokenRefreshMinutes(tokenRefreshMinutes)
                .partialReportIntervalSeconds(partialReportIntervalSeconds)
                .failOnTestFailures(failOnTestFailures)
                .autoAnalyze(autoAnalyze)
                .targetedCiMode(targetedCiMode)
                .ciHeadersEnabled(ciHeadersEnabled)
                .responseChainingEnabled(responseChainingEnabled)
                .repo(repo)
                .commitSha(commitSha)
                .previousGreenSha(previousGreenSha)
                .build();
    }

    private static boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        return "true".equalsIgnoreCase(value.trim())
                || "1".equals(value.trim())
                || "y".equalsIgnoreCase(value.trim())
                || "yes".equalsIgnoreCase(value.trim());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String normalizeApiName(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static List<String> splitBusinessFunctions(String value, String delimiterRegex) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return out;
        }
        for (String token : value.split(delimiterRegex)) {
            String trimmed = token == null ? "" : token.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    static String normalizeBusinessFunction(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "NoBusinessCaseTest".equalsIgnoreCase(trimmed) || "NA".equalsIgnoreCase(trimmed)) {
            return "";
        }
        return trimmed.replaceAll("[^A-Za-z0-9]+", "").toLowerCase();
    }

    static String normalizeServiceName(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("[^A-Za-z0-9]+", "").toLowerCase();
    }

    static Map<String, String> parseServiceBaseUrls(String value) {
        Map<String, String> out = new HashMap<>();
        if (value == null || value.isBlank()) {
            return out;
        }
        for (String token : value.split(",")) {
            String trimmed = token == null ? "" : token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int idx = trimmed.indexOf('=');
            if (idx <= 0 || idx >= trimmed.length() - 1) {
                continue;
            }
            String service = normalizeServiceName(trimmed.substring(0, idx));
            String baseUrl = trimmed.substring(idx + 1).trim();
            if (!service.isEmpty() && !baseUrl.isEmpty()) {
                out.put(service, baseUrl);
            }
        }
        return out;
    }

    private static Path resolveEnvFile(Map<String, String> flags, Path runnerRoot) {
        String fromCli = flags.get("envFile");
        if (fromCli != null && !fromCli.isBlank()) {
            return Paths.get(fromCli).toAbsolutePath().normalize();
        }
        String fromEnv = System.getenv("JR_ENV_FILE");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Paths.get(fromEnv).toAbsolutePath().normalize();
        }
        Path local = runnerRoot.resolve("runner.env");
        if (Files.exists(local)) {
            return local.toAbsolutePath().normalize();
        }
        return null;
    }

    private static Map<String, String> loadEnvFile(Path envFile) {
        Map<String, String> values = new HashMap<>();
        if (envFile == null || !Files.exists(envFile)) {
            return values;
        }
        try {
            for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int idx = trimmed.indexOf('=');
                if (idx <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, idx).trim();
                String value = trimmed.substring(idx + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!key.isEmpty()) {
                    values.put(key, value);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading env file: " + envFile, e);
        }
        return values;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        Arrays.stream(args).forEach(arg -> {
            String a = arg.startsWith("--") ? arg.substring(2) : arg;
            int idx = a.indexOf('=');
            if (idx > 0) {
                map.put(a.substring(0, idx), a.substring(idx + 1));
            }
        });
        return map;
    }
}
