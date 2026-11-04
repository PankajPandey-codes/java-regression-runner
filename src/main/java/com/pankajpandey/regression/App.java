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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        Config cfg = Config.load(args);

        log.info("Starting java-regression-runner mode={}", cfg.mode);
        log.info("projectRoot={} manifests={} apis={} threads={} output={}",
                cfg.projectRoot, cfg.manifests, cfg.apis, cfg.threads, cfg.outputFile);

        switch (cfg.mode.toLowerCase()) {
            case "render" -> runRender(cfg);
            case "analyze" -> runAnalyze(cfg);
            case "execute", "dry-run" -> {
                int poolSize = Math.min(Math.max(1, cfg.threads), Math.max(1, cfg.manifests.size()));
                ExecutorService pool = Executors.newFixedThreadPool(poolSize);
                try {
                    if ("execute".equalsIgnoreCase(cfg.mode)) {
                        var outcome = runExecute(cfg, pool);
                        if (outcome.infrastructureFailures() > 0) {
                            throw new IllegalStateException("Execution completed with " + outcome.infrastructureFailures() + " infrastructure failure(s)");
                        }
                        if (cfg.failOnTestFailures && outcome.stepsFailed() > 0) {
                            throw new IllegalStateException("Execution completed with " + outcome.stepsFailed() + " test failure(s)");
                        }
                    } else {
                        runDry(cfg, pool);
                    }
                } finally {
                    pool.shutdown();
                }
            }
            default -> throw new IllegalArgumentException("Unsupported mode: " + cfg.mode);
        }
    }

    private static void runRender(Config cfg) throws Exception {
        Path runnerRoot = PathResolver.runnerRoot(cfg.projectRoot);
        if (cfg.runFolder != null && !cfg.runFolder.isBlank()) {
            runRenderFromRunFolder(cfg);
            return;
        }

        Path input = cfg.reportInput != null
                ? cfg.reportInput.toAbsolutePath().normalize()
                : runnerRoot.resolve("output/execute-summary.json").toAbsolutePath().normalize();

        Path output = cfg.reportHtmlOutput != null
                ? cfg.reportHtmlOutput.toAbsolutePath().normalize()
                : replaceExtension(input, ".html");

        Path written = ExistingExecuteReportRenderer.render(input, output);
        log.info("Render complete input={} output={}", input, written);
    }

    private static void runRenderFromRunFolder(Config cfg) throws Exception {
        List<ExecutionReport> reports = RunFolderReportBuilder.build(cfg.projectRoot, cfg.runFolder, cfg);
        Path jsonOutput = runFolderSummaryJson(cfg.projectRoot, cfg.runFolder);

        ExecutionReporter.write(jsonOutput, reports, cfg.projectRoot, cfg.runFolder);
        writeCanaryRunDoc(cfg, reports, cfg.runFolder);
        maybeRunAnalysis(cfg, reports, cfg.runFolder);
        writeStatusFromReports(cfg, cfg.runFolder, reports);
        Path htmlOutput = replaceExtension(jsonOutput, ".html");
        log.info("Render-from-run complete runFolder={} output={}", cfg.runFolder, htmlOutput);
    }

    private static void runAnalyze(Config cfg) throws Exception {
        String runFolder = requireRunFolder(cfg.runFolder, "analyze");
        List<ExecutionReport> reports = RunFolderReportBuilder.build(cfg.projectRoot, runFolder, cfg);
        AnalysisReporter.AnalysisOutput output = AnalysisReporter.write(cfg.projectRoot, runFolder, reports);
        log.info("Analysis complete runFolder={} summary={} html={}", runFolder, output.summaryJson, output.htmlReport);
    }

    private static Path replaceExtension(Path path, String newExt) {
        String n = path.getFileName().toString();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n;
        return path.getParent().resolve(base + newExt);
    }

    private static void runDry(Config cfg, ExecutorService pool) throws Exception {
        DryRunEngine engine = new DryRunEngine(cfg.projectRoot, cfg.apis);
        List<CompletableFuture<ManifestReport>> futures = new ArrayList<>();

        for (String manifest : cfg.manifests) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    log.info("Processing manifest={}", manifest);
                    ManifestReport report = engine.runForManifest(manifest);
                    log.info("Done manifest={} total={} enabledStrict={} missing={}",
                            report.manifest,
                            report.totalRows,
                            report.enabledRowsStrict,
                            report.missingInputs + report.missingPayload + report.missingExpected);
                    return report;
                } catch (Exception e) {
                    throw new RuntimeException("Failed manifest " + manifest, e);
                }
            }, pool));
        }

        List<ManifestReport> reports = new ArrayList<>();
        for (CompletableFuture<ManifestReport> f : futures) reports.add(f.join());

        Path output = cfg.outputFile.toAbsolutePath().normalize();
        DryRunReporter.write(output, reports, cfg.projectRoot);

        int totalRows = reports.stream().mapToInt(r -> r.totalRows).sum();
        int enabled = reports.stream().mapToInt(r -> r.enabledRowsStrict).sum();
        int missing = reports.stream().mapToInt(r -> r.missingInputs + r.missingPayload + r.missingExpected).sum();
        log.info("Dry-run complete totalRows={} enabledStrict={} missing={} output={}", totalRows, enabled, missing, output);
    }

    private static ExecuteOutcome runExecute(Config cfg, ExecutorService pool) throws Exception {
        ExecuteEngine engine = new ExecuteEngine(cfg);
        AtomicBoolean executeCompleted = new AtomicBoolean(false);
        Instant startedAt = Instant.now();
        ScheduledExecutorService partialReportScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "execute-partial-report");
            t.setDaemon(true);
            return t;
        });

        RunStatusWriter.write(
                cfg.projectRoot,
                engine.runFolder(),
                "RUNNING",
                startedAt,
                "Execution started",
                cfg.manifests,
                0,
                0,
                0,
                0
        );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (executeCompleted.get()) return;
            try {
                log.warn("Execute interrupted. Building partial report from runFolder={}", engine.runFolder());
                List<ExecutionReport> partialReports = writeExecuteReportFromRunFolder(cfg, engine.runFolder());
                RunStatusWriter.write(
                        cfg.projectRoot,
                        engine.runFolder(),
                        "INTERRUPTED",
                        startedAt,
                        "Execution interrupted before completion",
                        cfg.manifests,
                        sumStepsExecuted(partialReports),
                        sumStepsPassed(partialReports),
                        sumStepsFailed(partialReports),
                        countInfrastructureFailures(partialReports)
                );
            } catch (Exception e) {
                log.error("Failed to build partial execute report on shutdown", e);
            }
        }, "execute-shutdown-render"));

        partialReportScheduler.scheduleWithFixedDelay(() -> {
            if (executeCompleted.get()) return;
            try {
                List<ExecutionReport> partialReports = writeExecuteReportFromRunFolder(cfg, engine.runFolder());
                RunStatusWriter.write(
                        cfg.projectRoot,
                        engine.runFolder(),
                        "RUNNING",
                        startedAt,
                        "Partial report refreshed",
                        cfg.manifests,
                        sumStepsExecuted(partialReports),
                        sumStepsPassed(partialReports),
                        sumStepsFailed(partialReports),
                        countInfrastructureFailures(partialReports)
                );
            } catch (Exception e) {
                log.warn("Failed periodic partial execute report generation for runFolder={}", engine.runFolder(), e);
            }
        }, cfg.partialReportIntervalSeconds, cfg.partialReportIntervalSeconds, TimeUnit.SECONDS);

        List<CompletableFuture<ExecutionReport>> futures = new ArrayList<>();

        for (String manifest : cfg.manifests) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    log.info("Executing manifest={}", manifest);
                    ExecutionReport r = engine.runForManifest(manifest);
                    log.info("Done manifest={} apisExecuted={} stepsExecuted={} failed={}",
                            r.manifest, r.apiScenariosExecuted, r.stepsExecuted, r.stepsFailed);
                    return r;
                } catch (Exception e) {
                    log.error("Execution failed for manifest={}", manifest, e);
                    ExecutionReport r = new ExecutionReport();
                    r.manifest = manifest;
                    r.infrastructureFailure = true;
                    r.infrastructureError = e.getClass().getSimpleName() + ": " + safeMessage(e);
                    return r;
                }
            }, pool));
        }

        try {
            List<ExecutionReport> reports = new ArrayList<>();
            for (CompletableFuture<ExecutionReport> f : futures) reports.add(f.join());

            writeExecuteReportFromRunFolder(cfg, engine.runFolder());
            writeCanaryRunDoc(cfg, reports, engine.runFolder());
            executeCompleted.set(true);

            int stepsExecuted = sumStepsExecuted(reports);
            int stepsPassed = sumStepsPassed(reports);
            int stepsFailed = sumStepsFailed(reports);
            int infrastructureFailures = countInfrastructureFailures(reports);
            Path output = runFolderSummaryJson(cfg.projectRoot, engine.runFolder());
            maybeRunAnalysis(cfg, reports, engine.runFolder());
            String finalStatus = infrastructureFailures > 0
                    ? "FAILED_INFRA"
                    : (stepsFailed > 0 ? "COMPLETED_WITH_TEST_FAILURES" : "COMPLETED_SUCCESS");
            String finalMessage = infrastructureFailures > 0
                    ? "Execution completed with infrastructure failures"
                    : (stepsFailed > 0 ? "Execution completed with test failures" : "Execution completed successfully");
            RunStatusWriter.write(
                    cfg.projectRoot,
                    engine.runFolder(),
                    finalStatus,
                    startedAt,
                    finalMessage,
                    cfg.manifests,
                    stepsExecuted,
                    stepsPassed,
                    stepsFailed,
                    infrastructureFailures
            );
            log.info("Execute complete stepsExecuted={} stepsFailed={} output={}", stepsExecuted, stepsFailed, output);
            return new ExecuteOutcome(stepsExecuted, stepsPassed, stepsFailed, infrastructureFailures);

        } finally {
            executeCompleted.set(true);
            partialReportScheduler.shutdownNow();
        }
    }

    private static List<ExecutionReport> writeExecuteReportFromRunFolder(Config cfg, String runFolder) throws Exception {
        if (runFolder == null || runFolder.isBlank()) return Collections.emptyList();
        List<ExecutionReport> reports = RunFolderReportBuilder.build(cfg.projectRoot, runFolder, cfg);
        Path output = runFolderSummaryJson(cfg.projectRoot, runFolder);
        ExecutionReporter.write(output, reports, cfg.projectRoot, runFolder);
        log.info("Partial execute report generated from runFolder={} output={}", runFolder, output);
        return reports;
    }

    private static void writeCanaryRunDoc(Config cfg, List<ExecutionReport> reports, String runFolder) {
        if (runFolder == null || runFolder.isBlank()) {
            return;
        }
        try {
            CanaryRunDocWriter.write(cfg, reports, runFolderDir(cfg.projectRoot, runFolder), runFolder);
        } catch (Exception e) {
            log.warn("Failed to write canary RunDoc for runFolder={}", runFolder, e);
        }
    }

    private static void maybeRunAnalysis(Config cfg, List<ExecutionReport> reports, String runFolder) {
        if (!cfg.autoAnalyze || runFolder == null || runFolder.isBlank()) {
            return;
        }
        try {
            AnalysisReporter.AnalysisOutput output = AnalysisReporter.write(cfg.projectRoot, runFolder, reports);
            log.info("Auto analysis complete runFolder={} summary={} html={}", runFolder, output.summaryJson, output.htmlReport);
        } catch (Exception e) {
            log.warn("Auto analysis failed for runFolder={}", runFolder, e);
        }
    }

    private static void writeStatusFromReports(Config cfg, String runFolder, List<ExecutionReport> reports) {
        if (runFolder == null || runFolder.isBlank()) {
            return;
        }
        try {
            int stepsExecuted = sumStepsExecuted(reports);
            int stepsPassed = sumStepsPassed(reports);
            int stepsFailed = sumStepsFailed(reports);
            int infrastructureFailures = countInfrastructureFailures(reports);
            String finalStatus = infrastructureFailures > 0
                    ? "FAILED_INFRA"
                    : (stepsFailed > 0 ? "COMPLETED_WITH_TEST_FAILURES" : "COMPLETED_SUCCESS");
            String finalMessage = infrastructureFailures > 0
                    ? "Render completed with infrastructure failures"
                    : (stepsFailed > 0 ? "Render completed with test failures" : "Render completed successfully");
            RunStatusWriter.write(
                    cfg.projectRoot,
                    runFolder,
                    finalStatus,
                    Instant.now(),
                    finalMessage,
                    manifestNames(reports, cfg.manifests),
                    stepsExecuted,
                    stepsPassed,
                    stepsFailed,
                    infrastructureFailures
            );
        } catch (Exception e) {
            log.warn("Failed to refresh run status for runFolder={}", runFolder, e);
        }
    }

    private static List<String> manifestNames(List<ExecutionReport> reports, List<String> fallback) {
        List<String> names = new ArrayList<>();
        if (reports != null) {
            for (ExecutionReport report : reports) {
                if (report != null && report.manifest != null && !report.manifest.isBlank() && !names.contains(report.manifest)) {
                    names.add(report.manifest);
                }
            }
        }
        return names.isEmpty() ? fallback : names;
    }

    private static Path runFolderSummaryJson(Path projectRoot, String runFolder) {
        return runFolderDir(projectRoot, runFolder)
                .resolve("execute-summary.json")
                .toAbsolutePath()
                .normalize();
    }

    private static Path runFolderDir(Path projectRoot, String runFolder) {
        Path runnerRoot = PathResolver.runnerRoot(projectRoot);
        return runnerRoot.resolve("results")
                .resolve(runFolder)
                .toAbsolutePath()
                .normalize();
    }

    private static String safeMessage(Exception e) {
        String msg = e == null ? "" : e.getMessage();
        return msg == null ? "" : msg;
    }

    private static String requireRunFolder(String runFolder, String mode) {
        if (runFolder == null || runFolder.isBlank()) {
            throw new IllegalArgumentException("mode=" + mode + " requires --runFolder=<results folder>");
        }
        return runFolder;
    }

    private static int sumStepsExecuted(List<ExecutionReport> reports) {
        return reports == null ? 0 : reports.stream().mapToInt(r -> r == null ? 0 : r.stepsExecuted).sum();
    }

    private static int sumStepsPassed(List<ExecutionReport> reports) {
        return reports == null ? 0 : reports.stream().mapToInt(r -> r == null ? 0 : r.stepsPassed).sum();
    }

    private static int sumStepsFailed(List<ExecutionReport> reports) {
        return reports == null ? 0 : reports.stream().mapToInt(r -> r == null ? 0 : r.stepsFailed).sum();
    }

    private static int countInfrastructureFailures(List<ExecutionReport> reports) {
        return reports == null ? 0 : (int) reports.stream().filter(r -> r != null && r.infrastructureFailure).count();
    }

    private record ExecuteOutcome(int stepsExecuted, int stepsPassed, int stepsFailed, int infrastructureFailures) {}
}
