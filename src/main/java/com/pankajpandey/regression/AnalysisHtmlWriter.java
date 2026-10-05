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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AnalysisHtmlWriter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TOKEN_PATTERN = Pattern.compile("([^.\\[\\]]+)|\\[([^\\]]*)\\]");
    private static final Pattern ARRAY_SELECTOR_PATTERN = Pattern.compile("\\[[^\\]]*\\]");
    private static final DateTimeFormatter RUN_FOLDER_INPUT = DateTimeFormatter.ofPattern("dd-MM-yyyy_hhmma", Locale.ROOT);
    private static final DateTimeFormatter DISPLAY_TS = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ROOT);

    private AnalysisHtmlWriter() {}

    static void write(Path htmlFile, AnalysisReporter.AnalysisData data) throws IOException {
        Path analysisDir = htmlFile.getParent();
        Files.createDirectories(analysisDir);

        Path httpDir = analysisDir.resolve("http_cases");
        Path diffDir = analysisDir.resolve("diff_cases");
        prepareCleanDirectory(httpDir);
        prepareCleanDirectory(diffDir);

        List<AnalysisReporter.AnalyzedCase> httpCases = new ArrayList<>();
        httpCases.addAll(data.statusCodeMismatches);
        httpCases.addAll(data.mixedFailures);
        httpCases.sort(caseComparator());

        List<AnalysisReporter.AnalyzedCase> diffCases = new ArrayList<>();
        diffCases.addAll(data.payloadDiffs);
        diffCases.sort(caseComparator());

        List<IndexRow> httpRows = writeCasePages(httpDir, httpCases, "http");
        List<IndexRow> diffRows = writeCasePages(diffDir, diffCases, "diff");

        writeLandingPage(htmlFile, data, httpRows.size(), diffRows.size());
        writeIndexPage(analysisDir.resolve("index_http.html"), "HTTP / Status Failures", httpRows, true);
        writeIndexPage(analysisDir.resolve("index_diff.html"), "Text / Payload Diffs", diffRows, false);
    }

    private static void prepareCleanDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder())
                        .filter(path -> !path.equals(dir))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
            } catch (RuntimeException e) {
                if (e.getCause() instanceof IOException ioe) {
                    throw ioe;
                }
                throw e;
            }
        }
        Files.createDirectories(dir);
    }

    private static Comparator<AnalysisReporter.AnalyzedCase> caseComparator() {
        return Comparator
                .comparing(AnalysisReporter.AnalyzedCase::getManifest)
                .thenComparing(AnalysisReporter.AnalyzedCase::getApi)
                .thenComparingInt(AnalysisReporter.AnalyzedCase::getStep);
    }

    private static List<IndexRow> writeCasePages(Path outDir, List<AnalysisReporter.AnalyzedCase> cases, String category) throws IOException {
        List<IndexRow> rows = new ArrayList<>();
        int seq = 1;
        String dirName = outDir.getFileName().toString();
        for (AnalysisReporter.AnalyzedCase row : cases) {
            String fileName = safeName(category + "_" + row.manifest + "_" + row.api + "_" + row.step + "_" + seq) + ".html";
            writeCasePage(outDir.resolve(fileName), row);
            rows.add(IndexRow.from(row, dirName + "/" + fileName));
            seq++;
        }
        return rows;
    }

    private static void writeLandingPage(Path outPath, AnalysisReporter.AnalysisData data, int httpCount, int diffCount) throws IOException {
        String topModule = data.byModule.isEmpty() ? "NA" : data.byModule.get(0).name;
        String topOwner = data.byOwner.isEmpty() ? "NA" : data.byOwner.get(0).name;
        String topApi = data.byApi.isEmpty() ? "NA" : data.byApi.get(0).name;
        int topModuleFailures = data.byModule.isEmpty() ? 0 : data.byModule.get(0).totalFailures;
        int topOwnerFailures = data.byOwner.isEmpty() ? 0 : data.byOwner.get(0).totalFailures;
        int topApiFailures = data.byApi.isEmpty() ? 0 : data.byApi.get(0).totalFailures;
        int statusOnly = data.statusCodeMismatches == null ? 0 : data.statusCodeMismatches.size();
        int payloadOnly = data.payloadDiffs == null ? 0 : data.payloadDiffs.size();
        int infra = data.infrastructureFailureCount;
        int other = data.otherFailures == null ? 0 : data.otherFailures.size();
        int splitTotal = statusOnly + payloadOnly + data.mixedFailureCount + other;
        String displayRun = formatRunFolder(data.runFolder);
        String displayGeneratedAt = formatGeneratedAt(data.generatedAt);
        List<LandingInsight> strongestSignals = strongestSignals(data);
        List<CountChip> causeChips = topCauseChips(data, 4);
        List<CountChip> pathChips = topPathChips(data, 4);
        List<AnalysisReporter.CaseDigest> topTextCases = topTextCases(data, 4);
        List<AnalysisReporter.BreakdownRow> apiBars = data.byApi.size() > 5 ? data.byApi.subList(0, 5) : data.byApi;
        double passSweep = Math.max(0.0, Math.min(360.0, data.passRate * 3.6));
        String headline = landingHeadline(data, httpCount, diffCount);
        String narrative = landingNarrative(data, topModule, topOwner, topApi);

        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset='utf-8'>");
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
        sb.append("<title>Regression Analysis Summary</title>");
        sb.append(baseStyles());
        sb.append("</head><body><div class='wrap'>");
        sb.append("<div class='topbar'><div class='brandline'><span class='branddot'></span><span>Regression Analysis</span></div>");
        sb.append("<div class='status-pill'><span class='dot'></span><span>")
                .append(data.totalFailed > 0 ? "Focused review recommended" : "Run clean")
                .append("</span></div></div>");

        sb.append("<section class='hero'><div class='hero-grid'><div>");
        sb.append("<div class='hero-label'>Analysis Start Page</div>");
        sb.append("<h1>").append(escape(headline)).append("</h1>");
        sb.append("<div class='hero-copy'>").append(escape(narrative)).append("</div>");
        sb.append("<div class='hero-meta'>");
        heroMeta(sb, "Run folder", displayRun);
        heroMeta(sb, "Generated", displayGeneratedAt);
        heroMeta(sb, "Top module", topModule + " (" + topModuleFailures + ")");
        heroMeta(sb, "Top owner", topOwner + " (" + topOwnerFailures + ")");
        sb.append("</div></div>");

        sb.append("<div class='health'><div class='health-title'>Run health</div>");
        sb.append("<div class='health-sub'>Use the two main routes below for detail. This page is for concentration, not raw comparison review.</div>");
        sb.append("<div class='ring-row'><div class='ring' style=\"background:radial-gradient(closest-side, rgba(31,36,33,.84) 71%, transparent 72% 100%),conic-gradient(#f6f4ef 0 ")
                .append(String.format(Locale.ROOT, "%.2f", passSweep))
                .append("deg, rgba(255,255,255,.16) ")
                .append(String.format(Locale.ROOT, "%.2f", passSweep))
                .append("deg 360deg)\"><div class='ring-inner'><div class='ring-num'>")
                .append(formatPercent(data.passRate))
                .append("</div><div class='ring-lbl'>Pass rate</div></div></div>");
        sb.append("<div class='mix'><div class='mix-bar'>");
        splitSegment(sb, "Status only", statusOnly, splitTotal, "status");
        splitSegment(sb, "Text only", payloadOnly, splitTotal, "diff");
        splitSegment(sb, "Mixed", data.mixedFailureCount, splitTotal, "mixed");
        splitSegment(sb, "Other", other, splitTotal, "other");
        sb.append("</div><div class='mix-list'>");
        mixItem(sb, "Status only", statusOnly, "status");
        mixItem(sb, "Text only", payloadOnly, "diff");
        mixItem(sb, "Mixed", data.mixedFailureCount, "mixed");
        if (other > 0) {
            mixItem(sb, "Other", other, "other");
        }
        if (infra > 0) {
            mixItem(sb, "Infra manifests", infra, "infra");
        }
        sb.append("</div></div></div></div></section>");

        sb.append("<div class='kpis'>");
        modernKpi(sb, "Executed", String.valueOf(data.totalExecuted), "All steps captured in this run", "");
        modernKpi(sb, "Failed", String.valueOf(data.totalFailed), "Includes status, payload, mixed, and other", "");
        modernKpi(sb, "Pass rate", formatPercent(data.passRate), "Built from executed vs passed steps", "");
        modernKpi(sb, "HTTP / Status", String.valueOf(httpCount), "Mixed failures included in this route", "http");
        modernKpi(sb, "Text / Payload", String.valueOf(diffCount), "Structured diff cases only", "text");
        sb.append("</div>");

        sb.append("<div class='main'>");
        sb.append("<div class='panel'><h2>Primary Routes</h2><div class='copy'>Open these first. The rest of the page is triage context, not a replacement for the detailed case views.</div><div class='route-stack'>");
        renderRoute(sb, "HTTP / Status Failures", httpCount,
                "Expected and actual status codes differ, or the same case also carries a structured payload mismatch.",
                topApis(httpCases(data), 3), "index_http.html", "Open HTTP / Status View", "status");
        renderRoute(sb, "Text / Payload Diffs", diffCount,
                "Status stayed aligned, but body content, DB result shape, field presence, or returned values drifted.",
                topApis(data.payloadDiffs, 3), "index_diff.html", "Open Text / Payload View", "diff");
        sb.append("</div></div>");

        sb.append("<div class='panel'><h2>Top Failure Concentration</h2><div class='copy'>These APIs carried the highest number of failed cases in this run. Start here when you want the fastest route to concentration rather than failure type.</div><div class='bars'>");
        if (apiBars.isEmpty()) {
            sb.append("<div class='copy'>No failed APIs were recorded for this run.</div>");
        } else {
            int maxFailures = apiBars.get(0).totalFailures;
            for (AnalysisReporter.BreakdownRow row : apiBars) {
                renderBarRow(sb, row.name, row.totalFailures, maxFailures);
            }
        }
        sb.append("</div></div></div>");

        sb.append("<div class='bottom'><div class='panel'><h2>Analysis Highlights</h2><div class='copy'>This layer stays deterministic. It groups repeated failure signals without replacing the detailed comparison pages.</div><div class='insights'>");
        if (strongestSignals.isEmpty()) {
            sb.append("<div class='insight'><div><div class='insight-title'>No dominant heuristic stood out</div></div><div class='insight-copy'>Failures exist, but the current rule set does not show a strong timing, application-error, baseline-drift, or calculation-shift signal. Use the primary routes directly.</div><div class='side'><span class='badge low'>Low</span><span class='small'>Confidence: Needs confirmation</span></div></div>");
        } else {
            for (LandingInsight insight : strongestSignals) {
                renderModernInsight(sb, insight);
            }
        }
        sb.append("</div></div>");

        sb.append("<div class='panel'><h2>Deep Signals</h2><div class='copy'>Compact supporting context for the text side of the run. Keep this secondary; use the dedicated diff view for the full comparison surface.</div>");
        sb.append("<div class='compact-table'><div class='table-head'><span>Signal</span><span>Count</span><span>Preview</span></div>");
        renderCompactSignal(sb, "Top API", String.valueOf(topApiFailures), topApi);
        if (!causeChips.isEmpty()) {
            renderCompactSignal(sb, "Repeated cause", String.valueOf(causeChips.get(0).count), causeChips.get(0).label);
        }
        if (!pathChips.isEmpty()) {
            renderCompactSignal(sb, "Repeated path", String.valueOf(pathChips.get(0).count), pathChips.get(0).label);
        }
        renderCompactSignal(sb, "Infra manifests", String.valueOf(infra), infra > 0 ? "Infrastructure failures were captured" : "None");
        sb.append("</div>");
        if (!causeChips.isEmpty() || !pathChips.isEmpty() || !topTextCases.isEmpty()) {
            sb.append("<details><summary><span>Open supporting text-comparison signals</span><span class='meta'>")
                    .append(causeChips.size() + pathChips.size() + topTextCases.size()).append(" items</span></summary>");
            if (!causeChips.isEmpty()) {
                sb.append("<div class='pill-row'>");
                for (CountChip chip : causeChips) {
                    sb.append("<span class='pill'>").append(escape(chip.label)).append(" ").append(chip.count).append("</span>");
                }
                sb.append("</div>");
            }
            if (!pathChips.isEmpty()) {
                sb.append("<div class='pill-row'>");
                for (CountChip chip : pathChips) {
                    sb.append("<span class='pill mono'>").append(escape(chip.label)).append(" ").append(chip.count).append("</span>");
                }
                sb.append("</div>");
            }
            if (!topTextCases.isEmpty()) {
                sb.append("<div class='compact-table'>");
                for (AnalysisReporter.CaseDigest row : topTextCases) {
                    renderCompactSignal(sb, row.api + " #" + row.step, String.valueOf(row.comparisonCount),
                            row.topMismatchPaths.isEmpty() ? row.failureType : String.join(", ", row.topMismatchPaths));
                }
                sb.append("</div>");
            }
            sb.append("</details>");
        }
        sb.append("</div></div>");

        sb.append("</div></body></html>");
        Files.write(outPath, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeIndexPage(Path outPath, String title, List<IndexRow> rows, boolean statusPage) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset='utf-8'>");
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
        sb.append("<title>").append(escape(title)).append("</title>");
        sb.append(baseStyles());
        sb.append("</head><body><div class='wrap'>");
        sb.append("<div class='hero'><h1>").append(escape(title)).append("</h1><div class='sub'>Click any row to open the dedicated detail page in a new tab.</div></div>");
        sb.append("<div class='toolbar'><input id='q' type='search' placeholder='Search API, module, owner, test case no, method, endpoint...'><span class='meta' id='count'></span></div>");
        sb.append("<div class='panel tablepanel'><table class='tbl ").append(statusPage ? "status-table" : "diff-table").append("' id='tbl'><thead><tr>");
        sb.append("<th>API</th><th>Test Case No</th><th>Module</th><th>Owner</th><th>Expected / Actual</th>");
        sb.append("<th>Endpoint</th>");
        sb.append("<th>Open</th></tr></thead><tbody></tbody></table></div>");
        sb.append("<script>");
        sb.append("const rows=").append(toJson(rows)).append(";");
        sb.append("const q=document.getElementById('q');const cnt=document.getElementById('count');const tb=document.querySelector('#tbl tbody');");
        sb.append("function esc(v){return String(v??'').replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('\"','&quot;');}");
        sb.append("function render(items){tb.innerHTML=items.map(r=>{");
        sb.append("let cols=[`<td><a class='row-primary clip-sm' href='${r.link}' target='_blank' rel='noopener' title='Open ${esc(r.api)}'>${esc(r.api)}</a></td>`,`<td>${esc(r.step)}</td>`,`<td><span class='clip-sm' title='${esc(r.module)}'>${esc(r.module)}</span></td>`,`<td><span class='clip-sm' title='${esc(r.owner)}'>${esc(r.owner)}</span></td>`,`<td class='mono'>${esc(r.expectedActual)}</td>`];");
        sb.append("cols.push(`<td class='endpoint-cell'><span class='endpoint-text' title='${esc(r.endpoint)}'>${esc(r.endpoint)}</span></td>`);");
        sb.append("cols.push(`<td class='open-cell'><a class='open-link' href='${r.link}' target='_blank' rel='noopener'>Open</a></td>`);return `<tr class='click-row' data-link='${r.link}' tabindex='0'>${cols.join('')}</tr>`;}).join('');cnt.textContent=`Showing ${items.length} of ${rows.length}`;attachRowActions();}");
        sb.append("function attachRowActions(){tb.querySelectorAll('tr.click-row').forEach(tr=>{const open=()=>window.open(tr.dataset.link,'_blank','noopener');tr.addEventListener('click',e=>{if(e.target.closest('a')) return;open();});tr.addEventListener('keydown',e=>{if(e.key==='Enter' || e.key===' '){e.preventDefault();open();}});});}");
        sb.append("function filt(){const s=q.value.toLowerCase().trim(); if(!s){render(rows);return;} render(rows.filter(r=>JSON.stringify(r).toLowerCase().includes(s)));}");
        sb.append("q.addEventListener('input',filt);render(rows);");
        sb.append("</script></div></body></html>");
        Files.write(outPath, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeCasePage(Path outPath, AnalysisReporter.AnalyzedCase row) throws IOException {
        Files.createDirectories(outPath.getParent());
        Map<String, Integer> causeCounts = new LinkedHashMap<>();
        for (Map<String, Object> diff : row.comparison) {
            String cause = normalizeCause(firstNonBlank(diff.get("cause"), diff.get("kind"), "Unknown"));
            causeCounts.put(cause, causeCounts.getOrDefault(cause, 0) + 1);
        }

        JsonNode expectedNode = parseJson(row.expected);
        JsonNode actualNode = parseJson(row.actual);
        MarkSets marks = buildMarks(expectedNode, actualNode, row.comparison);

        String expectedHtml = expectedNode == null
                ? "<pre class='json'>" + escape(pretty(row.expected)) + "</pre>"
                : "<pre class='json'>" + renderJson(expectedNode, "", marks.expectedMarks) + "</pre>";
        String actualHtml = actualNode == null
                ? "<pre class='json'>" + escape(pretty(row.actual)) + "</pre>"
                : "<pre class='json'>" + renderJson(actualNode, "", marks.actualMarks) + "</pre>";

        StringBuilder diffTable = new StringBuilder();
        if (row.comparison.isEmpty()) {
            diffTable.append("<p class='meta'><em>No structured diffs were produced for this case.</em></p>");
        } else {
            diffTable.append("<table class='tbl detail-diff-table'><thead><tr><th>#</th><th>Path</th><th>Cause</th><th>Expected</th><th>Actual</th></tr></thead><tbody>");
            int idx = 1;
            for (Map<String, Object> diff : row.comparison) {
                diffTable.append("<tr><td>").append(idx++).append("</td><td class='mono'>")
                        .append(escape(summarizePath(asStr(diff.get("path"))))).append("</td><td>")
                        .append(escape(normalizeCause(firstNonBlank(diff.get("cause"), diff.get("kind"), "Unknown")))).append("</td><td><pre class='json'>")
                        .append(escape(pretty(asStr(diff.get("expected"))))).append("</pre></td><td><pre class='json'>")
                        .append(escape(pretty(asStr(diff.get("actual"))))).append("</pre></td></tr>");
            }
            diffTable.append("</tbody></table>");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset='utf-8'>");
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
        sb.append("<title>").append(escape(row.api)).append(" test case ").append(row.step).append("</title>");
        sb.append(baseStyles());
        sb.append("</head><body><div class='wrap'>");
        sb.append("<div class='hero'><h1>").append(escape(row.api)).append(" <span class='sub-inline'>Test Case No ").append(row.step)
                .append("</span></h1><div class='sub'>").append(escape(row.manifest)).append(" | ").append(escape(row.method))
                .append(" | ").append(escape(row.url)).append("</div></div>");
        sb.append("<div class='cards'>");
        kpi(sb, "Failure Type", escape(row.failureType.replace('_', ' ')), row.failureType.contains("PAYLOAD") ? "fail" : "warn");
        kpi(sb, "Expected Code", String.valueOf(row.expectedCode), "");
        kpi(sb, "Actual Code", String.valueOf(row.actualCode), row.expectedCode != row.actualCode ? "warn" : "");
        kpi(sb, "Total Diffs", String.valueOf(row.comparison == null ? 0 : row.comparison.size()), row.comparison != null && !row.comparison.isEmpty() ? "fail" : "");
        sb.append("</div>");

        sb.append("<div class='panel'><div class='chips'>");
        chip(sb, "Module: " + row.module, "");
        chip(sb, "Owner: " + row.owner, "");
        chip(sb, "Test Case No: " + row.step, "");
        chip(sb, "Endpoint: " + endpoint(row.url), "warn");
        if (!causeCounts.isEmpty()) {
            for (Map.Entry<String, Integer> entry : causeCounts.entrySet()) {
                chip(sb, entry.getKey() + ": " + entry.getValue(), "fail");
            }
        }
        sb.append("</div></div>");

        sb.append("<div class='panel'><div class='jumpbar'>");
        sb.append("<a class='jump' href='#comparison'>Go to Comparison</a>");
        if (row.assertionResults != null && !row.assertionResults.isEmpty()) {
            sb.append("<a class='jump' href='#assertions'>Go to Assertions</a>");
        }
        if (!row.comparison.isEmpty()) {
            sb.append("<a class='jump' href='#diff-list'>Go to Full Difference List</a>");
        }
        sb.append("</div></div>");

        if (row.assertionResults != null && !row.assertionResults.isEmpty()) {
            sb.append("<div class='panel' id='assertions'><h2>Runtime Assertions</h2>");
            renderAssertionResults(sb, row.assertionResults);
            sb.append("</div>");
        }

        sb.append("<div class='panel' id='comparison'><h2>Expected vs Actual</h2><div class='meta'>Use <b>Select</b> or <b>Copy</b> for the main panes. Double-click any JSON block anywhere on the page to select its full contents.</div><div class='side'>");
        sb.append("<div class='box'><div class='box-head'><h3>Expected</h3><div class='pre-tools'><button type='button' class='mini-btn' data-select-target='expected-json'>Select</button><button type='button' class='mini-btn primary' data-copy-target='expected-json'>Copy</button></div></div>")
                .append(markComparisonPane(expectedHtml, "expected-json")).append("</div>");
        sb.append("<div class='box'><div class='box-head'><h3>Actual</h3><div class='pre-tools'><button type='button' class='mini-btn' data-select-target='actual-json'>Select</button><button type='button' class='mini-btn primary' data-copy-target='actual-json'>Copy</button></div></div>")
                .append(markComparisonPane(actualHtml, "actual-json")).append("</div>");
        sb.append("</div></div>");
        sb.append("<div class='panel' id='diff-list'><h2>All Differences</h2>");
        if (!row.comparison.isEmpty()) {
            sb.append("<details><summary>Show full difference list (").append(row.comparison.size()).append(" rows)</summary>")
                    .append("<div class='meta' style='margin:10px 0'>Comparison view above is the primary view. Expand this only when you need raw row-level detail.</div>")
                    .append(diffTable)
                    .append("</details>");
        } else {
            sb.append("<div class='meta'>No structured comparison rows were captured for this failure.</div>");
        }
        sb.append("</div>");
        sb.append("<script>")
                .append("function selectBlock(el){if(!el)return;const range=document.createRange();range.selectNodeContents(el);const sel=window.getSelection();sel.removeAllRanges();sel.addRange(range);}")
                .append("function toast(msg){let t=document.getElementById('copy-toast');if(!t){t=document.createElement('div');t.id='copy-toast';t.className='copy-toast';document.body.appendChild(t);}t.textContent=msg;t.classList.add('show');clearTimeout(window.__copyToastTimer);window.__copyToastTimer=setTimeout(()=>t.classList.remove('show'),1400);}")
                .append("async function copyBlock(el){if(!el)return;const text=el.textContent||el.innerText||'';try{if(navigator.clipboard&&navigator.clipboard.writeText){await navigator.clipboard.writeText(text);toast('Copied');return;}}catch(e){}selectBlock(el);toast('Selected. Press Ctrl/Cmd+C');}")
                .append("document.querySelectorAll('[data-select-target]').forEach(btn=>btn.addEventListener('click',()=>{const el=document.getElementById(btn.dataset.selectTarget);selectBlock(el);toast('Selected');}));")
                .append("document.querySelectorAll('[data-copy-target]').forEach(btn=>btn.addEventListener('click',()=>copyBlock(document.getElementById(btn.dataset.copyTarget))));")
                .append("document.querySelectorAll('pre.json').forEach(pre=>pre.addEventListener('dblclick',()=>{selectBlock(pre);toast('Selected');}));")
                .append("const panes=document.querySelectorAll('pre.compare-json');")
                .append("if(panes.length===2){let lock=false;function sync(src,dst){src.addEventListener('scroll',()=>{if(lock)return;lock=true;dst.scrollTop=src.scrollTop;dst.scrollLeft=src.scrollLeft;lock=false;});}")
                .append("sync(panes[0],panes[1]);sync(panes[1],panes[0]);}")
                .append("</script>");
        sb.append("</div></body></html>");
        Files.write(outPath, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void renderAssertionResults(StringBuilder sb, List<Map<String, Object>> assertionResults) {
        sb.append("<table class='tbl detail-diff-table'><thead><tr><th>#</th><th>Status</th><th>Assertion</th><th>Type</th><th>Actual Path</th><th>Actual Value</th><th>Expected Path</th><th>Expected Value</th><th>Message</th></tr></thead><tbody>");
        int idx = 1;
        for (Map<String, Object> assertion : assertionResults) {
            sb.append("<tr><td>").append(idx++).append("</td><td>")
                    .append(escape(asStr(assertion.get("status")))).append("</td><td>")
                    .append(escape(asStr(assertion.get("assertionName")))).append("</td><td>")
                    .append(escape(asStr(assertion.get("type")))).append("</td><td class='mono'>")
                    .append(escape(asStr(assertion.get("actualPath")))).append("</td><td><pre class='json'>")
                    .append(escape(pretty(asStr(assertion.get("resolvedActual"))))).append("</pre></td><td class='mono'>")
                    .append(escape(asStr(assertion.get("expectedPath")))).append("</td><td><pre class='json'>")
                    .append(escape(pretty(asStr(assertion.get("resolvedExpected"))))).append("</pre></td><td>")
                    .append(escape(asStr(assertion.get("message")))).append("</td></tr>");
        }
        sb.append("</tbody></table>");
    }

    private static MarkSets buildMarks(JsonNode expected, JsonNode actual, List<Map<String, Object>> diffs) {
        Set<String> expectedMarks = new LinkedHashSet<>();
        Set<String> actualMarks = new LinkedHashSet<>();
        if (diffs == null) {
            return new MarkSets(expectedMarks, actualMarks);
        }
        for (Map<String, Object> diff : diffs) {
            String cause = normalizeCause(firstNonBlank(diff.get("cause"), diff.get("kind"), "Unknown"));
            String path = asStr(diff.get("path"));
            List<PathToken> tokens = parsePath(path);
            if (tokens.isEmpty()) {
                continue;
            }
            ResolvedNode expectedMatch = resolvePath(expected, tokens);
            ResolvedNode actualMatch = resolvePath(actual, tokens);
            if ("UnexpectedField".equals(cause) || "UnexpectedItem".equals(cause)) {
                if (actualMatch != null) actualMarks.add(actualMatch.pointer);
            } else if ("MissingField".equals(cause) || "MissingItem".equals(cause)) {
                if (expectedMatch != null) expectedMarks.add(expectedMatch.pointer);
            } else {
                if (expectedMatch != null) expectedMarks.add(expectedMatch.pointer);
                if (actualMatch != null) actualMarks.add(actualMatch.pointer);
            }
        }
        return new MarkSets(expectedMarks, actualMarks);
    }

    private static List<PathToken> parsePath(String raw) {
        String path = asStr(raw).trim();
        if (path.isEmpty() || "$".equals(path) || "[]".equals(path)) {
            return Collections.emptyList();
        }
        List<PathToken> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(path);
        while (matcher.find()) {
            String key = matcher.group(1);
            String selector = matcher.group(2);
            if (key != null && !key.isBlank()) {
                tokens.add(PathToken.key(key));
            } else if (selector != null && !selector.isBlank()) {
                if (selector.matches("\\d+")) {
                    tokens.add(PathToken.index(Integer.parseInt(selector)));
                } else {
                    tokens.add(PathToken.selector(selector));
                }
            }
        }
        return tokens;
    }

    private static ResolvedNode resolvePath(JsonNode root, List<PathToken> tokens) {
        if (root == null) {
            return null;
        }
        JsonNode current = root;
        StringBuilder sb = new StringBuilder();
        for (PathToken token : tokens) {
            if (token.kind == PathToken.Kind.KEY) {
                if (!current.isObject()) {
                    return null;
                }
                current = current.get(token.value);
                sb.append('/').append(escapePointer(token.value));
            } else {
                if (!current.isArray()) {
                    return null;
                }
                int idx = token.kind == PathToken.Kind.INDEX
                        ? Integer.parseInt(token.value)
                        : findArrayIndex(current, token.value);
                if (idx < 0 || idx >= current.size()) {
                    return null;
                }
                current = current.get(idx);
                sb.append('/').append(idx);
            }
            if (current == null) {
                return null;
            }
        }
        return new ResolvedNode(current, sb.toString());
    }

    private static int findArrayIndex(JsonNode array, String selector) {
        List<KeySelector> selectors = parseSelectors(selector);
        if (selectors.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < array.size(); i++) {
            JsonNode item = array.get(i);
            boolean matched = true;
            for (KeySelector keySelector : selectors) {
                if (!keySelector.value.equals(normalized(getDottedPath(item, keySelector.field)))) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return i;
            }
        }
        return -1;
    }

    private static List<KeySelector> parseSelectors(String selector) {
        List<KeySelector> out = new ArrayList<>();
        if (selector == null || selector.isBlank()) {
            return out;
        }
        for (String raw : selector.split(",")) {
            int equals = raw.indexOf('=');
            if (equals <= 0) {
                return Collections.emptyList();
            }
            String field = raw.substring(0, equals).trim();
            String value = raw.substring(equals + 1).trim();
            if (field.isEmpty()) {
                return Collections.emptyList();
            }
            out.add(new KeySelector(field, value));
        }
        return out;
    }

    private static JsonNode getDottedPath(JsonNode node, String path) {
        if (node == null || path == null || path.isBlank()) {
            return null;
        }
        JsonNode current = node;
        for (String part : path.split("\\.")) {
            if (current == null || !current.isObject()) {
                return null;
            }
            current = current.get(part);
        }
        return current;
    }

    private static String normalized(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node.toString();
    }

    private static String escapePointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static String renderJson(JsonNode node, String pointer, Set<String> marks) {
        boolean highlight = marks.contains(pointer);
        if (node == null || node.isNull() || node.isValueNode()) {
            String value = node == null ? "null" : formatScalar(node);
            return highlight ? "<span class='hl-val'>" + escape(value) + "</span>" : escape(value);
        }
        String rendered;
        if (node.isObject()) {
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            for (int i = 0; i < fields.size(); i++) {
                String field = fields.get(i);
                String childPointer = pointer + "/" + field.replace("~", "~0").replace("/", "~1");
                sb.append("  \"").append(escape(field)).append("\": ").append(renderJson(node.get(field), childPointer, marks));
                if (i < fields.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("}");
            rendered = sb.toString();
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("[\n");
            for (int i = 0; i < node.size(); i++) {
                String childPointer = pointer + "/" + i;
                sb.append("  ").append(renderJson(node.get(i), childPointer, marks));
                if (i < node.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("]");
            rendered = sb.toString();
        }
        return highlight ? "<span class='hl-block'>" + rendered + "</span>" : rendered;
    }

    private static String formatScalar(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isTextual()) {
            return jsonString(node.textValue());
        }
        return node.toString();
    }

    private static String jsonString(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return "\"" + value + "\"";
        }
    }

    private static JsonNode parseJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String pretty(String raw) {
        JsonNode node = parseJson(raw);
        if (node == null) {
            return raw == null ? "" : raw;
        }
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return raw == null ? "" : raw;
        }
    }

    private static String topMismatchPathSummary(List<Map<String, Object>> comparison) {
        List<String> paths = new ArrayList<>();
        if (comparison != null) {
            for (Map<String, Object> diff : comparison) {
                String path = summarizePath(asStr(diff.get("path")));
                if (!paths.contains(path)) {
                    paths.add(path);
                }
                if (paths.size() >= 5) {
                    break;
                }
            }
        }
        return paths.isEmpty() ? "NA" : String.join(", ", paths);
    }

    private static String summarizePath(String rawPath) {
        String path = asStr(rawPath).trim();
        if (path.isEmpty()) {
            return "$";
        }
        return ARRAY_SELECTOR_PATTERN.matcher(path).replaceAll("[]");
    }

    private static String normalizeCause(Object rawCause) {
        String cause = asStr(rawCause).trim();
        if (cause.isEmpty()) {
            return "Unknown";
        }
        String compact = canonicalCauseKey(cause);
        switch (compact) {
            case "value_mismatch":
                return "ValueMismatch";
            case "missing_field":
                return "MissingField";
            case "extra_field":
            case "unexpected_field":
                return "UnexpectedField";
            case "unexpected_item":
                return "UnexpectedItem";
            case "type_mismatch":
                return "TypeMismatch";
            case "length_mismatch":
                return "LengthMismatch";
            case "string_format":
                return "StringFormat";
            case "numeric_tolerance":
                return "NumericTolerance";
            default:
                String[] parts = compact.split("_+");
                StringBuilder sb = new StringBuilder();
                for (String part : parts) {
                    if (part == null || part.isBlank()) continue;
                    sb.append(Character.toUpperCase(part.charAt(0)));
                    if (part.length() > 1) sb.append(part.substring(1));
                }
                return sb.length() == 0 ? "Unknown" : sb.toString();
        }
    }

    private static String canonicalCauseKey(String raw) {
        String withWordBreaks = raw.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        return withWordBreaks.replace('-', '_').replace(' ', '_').toLowerCase(Locale.ROOT);
    }

    private static Object firstNonBlank(Object a, Object b, Object fallback) {
        String sa = asStr(a).trim();
        if (!sa.isEmpty()) return sa;
        String sb = asStr(b).trim();
        if (!sb.isEmpty()) return sb;
        return fallback;
    }

    private static String safeName(String input) {
        String base = asStr(input).replaceAll("[^A-Za-z0-9_-]+", "_");
        if (base.length() > 120) {
            base = base.substring(0, 120);
        }
        return base.isBlank() ? "case" : base;
    }

    private static String toJson(Object value) throws IOException {
        return JSON.writeValueAsString(value);
    }

    private static String baseStyles() {
        return "<style>"
                + ":root{--bg:#f2efe8;--surface:#fcfbf8;--surface-2:#f7f4ee;--ink:#16181d;--muted:#6f6a62;--line:#ddd5c9;--hero:#1f2421;--hero-2:#35594a;--hero-3:#7a8f63;--status:#c76b2a;--diff:#d45b4b;--mixed:#7a5bd2;--other:#8d867c;--infra:#3b7b90;--ok:#2e7d5a;--shadow:0 18px 38px rgba(22,24,29,.10);--shadow-soft:0 8px 20px rgba(22,24,29,.06)}"
                + "*{box-sizing:border-box}body{margin:0;font-family:Inter,ui-sans-serif,system-ui,-apple-system,Segoe UI,Roboto,sans-serif;color:var(--ink);background:radial-gradient(circle at top right,rgba(122,143,99,.16),transparent 28%),linear-gradient(180deg,#ece7de 0%,var(--bg) 220px)}"
                + ".wrap{max-width:1380px;margin:0 auto;padding:22px}.topbar{display:flex;justify-content:space-between;align-items:center;gap:14px;margin-bottom:14px}.brandline{display:flex;align-items:center;gap:10px;color:#5f5a52;font-size:12px;font-weight:900;letter-spacing:.05em;text-transform:uppercase}.branddot{width:10px;height:10px;border-radius:999px;background:linear-gradient(135deg,#35594a,#7a8f63)}"
                + ".status-pill{display:inline-flex;align-items:center;gap:8px;padding:8px 12px;border-radius:999px;background:rgba(252,251,248,.92);border:1px solid var(--line);box-shadow:var(--shadow-soft);font-size:12px;font-weight:900;color:#3c4a43}.status-pill .dot{width:9px;height:9px;border-radius:999px;background:var(--ok)}"
                + ".hero{background:linear-gradient(135deg,rgba(255,255,255,.08),rgba(255,255,255,.03)),linear-gradient(135deg,var(--hero) 0%,var(--hero-2) 52%,var(--hero-3) 100%);color:#f6f4ef;border-radius:28px;box-shadow:var(--shadow);padding:26px;overflow:hidden;position:relative}.hero:before,.hero:after{content:'';position:absolute;border-radius:999px;background:rgba(255,255,255,.08)}.hero:before{width:320px;height:320px;right:-120px;top:-120px}.hero:after{width:180px;height:180px;right:180px;bottom:-90px}"
                + ".hero-grid{display:grid;grid-template-columns:1.15fr .85fr;gap:18px;position:relative;z-index:1}.hero-label{display:inline-flex;align-items:center;gap:8px;padding:6px 10px;border-radius:999px;background:rgba(255,255,255,.10);font-size:11px;font-weight:900;letter-spacing:.05em;text-transform:uppercase}.hero h1{margin:14px 0 10px 0;font-size:38px;line-height:1.05;max-width:760px}.hero-copy{max-width:760px;font-size:15px;line-height:1.72;opacity:.94}.hero-meta{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin-top:18px;max-width:700px}.meta-card{padding:12px 14px;border-radius:18px;background:rgba(255,255,255,.09);border:1px solid rgba(255,255,255,.10);backdrop-filter:blur(8px)}.meta-card .k{font-size:11px;opacity:.82;text-transform:uppercase;letter-spacing:.05em}.meta-card .v{margin-top:5px;font-size:15px;font-weight:900}"
                + ".health{background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.10);border-radius:24px;padding:18px;backdrop-filter:blur(8px);display:flex;flex-direction:column;min-height:100%}.health-title{font-size:14px;font-weight:900;text-transform:uppercase;letter-spacing:.05em;opacity:.9}.health-sub{font-size:12px;opacity:.76;margin-top:6px;line-height:1.5}.ring-row{display:grid;grid-template-columns:170px 1fr;gap:16px;align-items:center;margin-top:18px}.ring{width:170px;height:170px;border-radius:999px;display:flex;align-items:center;justify-content:center}.ring-inner{text-align:center}.ring-num{font-size:34px;font-weight:900;line-height:1}.ring-lbl{font-size:11px;letter-spacing:.05em;text-transform:uppercase;opacity:.8;margin-top:6px}"
                + ".mix{display:grid;gap:10px}.mix-bar{height:16px;border-radius:999px;overflow:hidden;background:rgba(255,255,255,.14);border:1px solid rgba(255,255,255,.12);display:flex}.seg{height:100%}.seg.status{background:var(--status)}.seg.diff{background:var(--diff)}.seg.mixed{background:var(--mixed)}.seg.other{background:var(--other)}.seg.infra{background:var(--infra)}"
                + ".mix-list{display:grid;gap:9px;margin-top:4px}.mix-item{display:flex;justify-content:space-between;align-items:center;gap:10px;font-size:13px}.mix-left{display:flex;align-items:center;gap:8px}.mix-dot{width:9px;height:9px;border-radius:999px}.mix-item .n{font-weight:900}"
                + ".kpis{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:12px;margin-top:18px}.kpi{background:var(--surface);border:1px solid var(--line);border-radius:18px;padding:14px 16px;box-shadow:var(--shadow-soft)}.kpi.http{background:linear-gradient(180deg,#fff7ef 0%,var(--surface) 100%)}.kpi.text{background:linear-gradient(180deg,#fff5f3 0%,var(--surface) 100%)}.kpi .k{font-size:11px;text-transform:uppercase;letter-spacing:.05em;color:var(--muted)}.kpi .v{margin-top:8px;font-size:30px;font-weight:900;line-height:1}.kpi .hint{margin-top:6px;font-size:12px;color:var(--muted)}"
                + ".main{display:grid;grid-template-columns:1.05fr .95fr;gap:18px;margin-top:18px}.bottom{display:grid;grid-template-columns:1fr .88fr;gap:18px;margin-top:18px}.panel,.box,.card{background:var(--surface);border:1px solid var(--line);border-radius:24px;box-shadow:var(--shadow-soft)}.panel{padding:20px}.card{padding:14px}.box{padding:14px}.panel h2{margin:0;font-size:22px;line-height:1.2}.copy{margin-top:10px;color:var(--muted);font-size:14px;line-height:1.68}.sub{margin-top:6px;font-size:13px;opacity:.95}.sub-inline{font-size:14px;font-weight:400;opacity:.8}"
                + ".route-stack{display:grid;gap:14px;margin-top:16px}.route{border:1px solid var(--line);border-radius:18px;padding:16px;background:linear-gradient(180deg,#fffdfa 0%,var(--surface-2) 100%)}.route-top{display:flex;justify-content:space-between;gap:12px;align-items:flex-start}.route-title{font-size:18px;font-weight:900;line-height:1.2}.route-num{font-size:30px;font-weight:900;line-height:1}.route-num.status{color:var(--status)}.route-num.diff{color:var(--diff)}.route-copy{margin-top:10px;color:var(--muted);font-size:14px;line-height:1.6}.chips{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}.chip{display:inline-flex;align-items:center;padding:6px 10px;border-radius:999px;background:#f2efe8;border:1px solid var(--line);font-size:12px;font-weight:900;color:#3f5146}.link,.cta{display:inline-flex;align-items:center;margin-top:14px;padding:10px 13px;border-radius:999px;background:var(--hero);color:#fff;text-decoration:none;font-size:12px;font-weight:900}"
                + ".bars{display:grid;gap:12px;margin-top:16px}.bar-row{display:grid;grid-template-columns:190px 1fr 42px;gap:12px;align-items:center}.bar-label{font-size:13px;font-weight:900;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.bar-track{height:12px;border-radius:999px;background:#ede7de;border:1px solid var(--line);overflow:hidden}.bar-fill{height:100%;border-radius:999px;background:linear-gradient(90deg,#35594a,#7a8f63)}.bar-n{font-size:13px;text-align:right;font-weight:900}"
                + ".insights{display:grid;gap:12px;margin-top:16px}.insight{display:grid;grid-template-columns:1.1fr 2fr auto;gap:16px;padding:16px;border-radius:18px;background:var(--surface-2);border:1px solid var(--line)}.insight-title{font-size:15px;font-weight:900;line-height:1.35}.insight-copy{font-size:14px;line-height:1.65;color:var(--muted)}.side-note{text-align:right}.badge{display:inline-flex;padding:5px 9px;border-radius:999px;font-size:11px;font-weight:900;border:1px solid transparent}.badge.high{background:#fff1ef;border-color:#efc6c0;color:#a84032}.badge.medium{background:#fff8ef;border-color:#ecd6b3;color:#9f6822}.badge.low{background:#f3f0ff;border-color:#d8cdf9;color:#5643b0}.small{display:block;margin-top:8px;font-size:12px;color:var(--muted)}"
                + ".compact-table{margin-top:14px;display:grid;gap:10px}.table-head,.row{display:grid;grid-template-columns:1.35fr .65fr 1fr;gap:12px;align-items:center}.table-head{font-size:11px;text-transform:uppercase;letter-spacing:.05em;color:var(--muted);font-weight:900;padding:0 4px}.row{padding:12px 14px;border-radius:16px;background:var(--surface-2);border:1px solid var(--line);font-size:13px}.pill-row{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}.pill{display:inline-flex;padding:6px 10px;border-radius:999px;background:#fff;border:1px solid var(--line);font-size:12px;font-weight:900;color:#3f5146}.mono{font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace}.meta{color:var(--muted);font-size:12px}"
                + "details{margin-top:14px;border:1px solid var(--line);border-radius:18px;background:var(--surface-2);padding:14px 16px}summary{list-style:none;cursor:pointer;display:flex;justify-content:space-between;align-items:center;gap:12px;font-weight:900;color:#3f5146}summary::-webkit-details-marker{display:none}"
                + ".toolbar{display:flex;gap:12px;align-items:center;margin:16px 0}.toolbar input{flex:1;max-width:620px;padding:10px 12px;border:1px solid var(--line);border-radius:12px;background:#fff}.tablepanel{overflow:auto}.tbl{width:100%;min-width:1080px;border-collapse:collapse;table-layout:auto}.tbl th,.tbl td{padding:8px 8px;border-bottom:1px solid #ece4d8;vertical-align:top;font-size:12.5px;text-align:left}.tbl th{background:#f7f4ee;color:#514b43;position:sticky;top:0}.tbl tbody tr.click-row{cursor:pointer}.tbl tbody tr.click-row:hover{background:#faf6ef}.tbl tbody tr.click-row:focus{outline:2px solid #7a8f63;outline-offset:-2px;background:#faf6ef}.row-primary{font-weight:800;color:#24473c;text-decoration:none;border-bottom:1px solid transparent}.row-primary:hover{text-decoration:underline}.open-link{display:inline-flex;align-items:center;justify-content:center;min-width:58px;padding:6px 10px;border-radius:999px;background:#24473c;color:#fff;text-decoration:none;font-weight:800}.open-link:hover{filter:brightness(.95)}.open-cell{position:sticky;right:0;background:inherit}.status-table th:last-child,.status-table td:last-child,.diff-table th:last-child,.diff-table td:last-child{width:96px;text-align:center;white-space:nowrap;position:sticky;right:0;background:#fcfbf8;box-shadow:-10px 0 12px rgba(22,24,29,.04)}"
                + ".endpoint-cell{max-width:420px}.endpoint-text{display:block;max-width:100%;white-space:normal;overflow-wrap:anywhere;word-break:break-word;line-height:1.35;padding-right:6px}"
                + ".status-table th:nth-child(1),.status-table td:nth-child(1){min-width:170px}.status-table th:nth-child(2),.status-table td:nth-child(2){min-width:96px}.status-table th:nth-child(3),.status-table td:nth-child(3){min-width:140px}.status-table th:nth-child(4),.status-table td:nth-child(4){min-width:110px}.status-table th:nth-child(5),.status-table td:nth-child(5){min-width:120px}.status-table th:nth-child(6),.status-table td:nth-child(6){min-width:360px}"
                + ".diff-table th:nth-child(1),.diff-table td:nth-child(1){min-width:170px}.diff-table th:nth-child(2),.diff-table td:nth-child(2){min-width:96px}.diff-table th:nth-child(3),.diff-table td:nth-child(3){min-width:140px}.diff-table th:nth-child(4),.diff-table td:nth-child(4){min-width:110px}.diff-table th:nth-child(5),.diff-table td:nth-child(5){min-width:120px}.diff-table th:nth-child(6),.diff-table td:nth-child(6){min-width:220px}.detail-diff-table th:nth-child(1),.detail-diff-table td:nth-child(1){width:56px;min-width:56px}.detail-diff-table th:nth-child(2),.detail-diff-table td:nth-child(2){min-width:220px}.detail-diff-table th:nth-child(3),.detail-diff-table td:nth-child(3){min-width:130px}.detail-diff-table th:nth-child(4),.detail-diff-table td:nth-child(4){width:36%;min-width:340px}.detail-diff-table th:nth-child(5),.detail-diff-table td:nth-child(5){width:36%;min-width:340px}.detail-diff-table td:nth-child(4) pre,.detail-diff-table td:nth-child(5) pre{min-width:0;margin:0}.detail-diff-table td:nth-child(4),.detail-diff-table td:nth-child(5){padding-right:12px}"
                + ".cards{display:grid;grid-template-columns:repeat(5,minmax(150px,1fr));gap:12px;margin-top:16px}.k{font-size:12px;color:var(--muted);text-transform:uppercase;letter-spacing:.04em}.v{margin-top:6px;font-size:24px;font-weight:700}.ok{color:#0f8a46}.warn{color:#b45309}.fail{color:#c62828}"
                + ".side{display:grid;grid-template-columns:1fr 1fr;gap:14px}.box-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:10px}.box h3,.box-head h3{margin:0}.pre-tools{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.mini-btn{border:1px solid var(--line);background:#fff;color:#17324d;border-radius:999px;padding:6px 10px;font-size:12px;font-weight:700;cursor:pointer}.mini-btn.primary{background:var(--hero);color:#fff;border-color:var(--hero)}.mini-btn:hover{filter:brightness(.98)}"
                + "pre.json{white-space:pre-wrap;word-break:break-word;overflow:auto;max-height:75vh;background:#f9f7f2;border:1px solid #e8dfd3;padding:10px;border-radius:10px;cursor:text}pre.compare-json{height:72vh;max-height:none}.hl-val{background:#fde2e1;border-radius:4px;padding:0 2px}.hl-block{background:#fff5d6;border-radius:4px;padding:1px 2px;display:inline-block}"
                + ".clip,.clip-sm{display:inline-block;max-width:100%;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;vertical-align:top}.jumpbar{display:flex;gap:10px;flex-wrap:wrap}.jump{display:inline-block;padding:8px 12px;border-radius:999px;background:#f2efe8;border:1px solid var(--line);text-decoration:none;color:#17324d;font-size:12px;font-weight:700}"
                + ".copy-toast{position:fixed;right:20px;bottom:20px;background:var(--hero);color:#fff;padding:10px 14px;border-radius:10px;box-shadow:0 10px 24px rgba(15,23,42,.18);font-size:12px;font-weight:700;opacity:0;transform:translateY(8px);transition:opacity .16s ease,transform .16s ease;pointer-events:none}.copy-toast.show{opacity:1;transform:translateY(0)}"
                + "@media (max-width:1120px){.hero-grid,.main,.bottom,.side{grid-template-columns:1fr}.kpis{grid-template-columns:repeat(3,minmax(0,1fr))}.insight{grid-template-columns:1fr}.side-note{text-align:left}.cards{grid-template-columns:repeat(3,minmax(150px,1fr))}}"
                + "@media (max-width:760px){.wrap{padding:16px}.kpis,.cards{grid-template-columns:repeat(2,minmax(0,1fr))}.bar-row,.table-head,.row,.ring-row{grid-template-columns:1fr}}"
                + "</style>";
    }

    private static String landingHeadline(AnalysisReporter.AnalysisData data, int httpCount, int diffCount) {
        if (data.totalFailed <= 0) {
            return "This run completed cleanly. Use the routes below only if you want to inspect the generated detail views.";
        }
        if (diffCount > httpCount) {
            return "Payload drift dominates this run. Start with the text route, then move into the concentrated APIs.";
        }
        if (httpCount > 0) {
            return "Status-driven failures are carrying this run. Start with the HTTP route before reviewing the broader concentration.";
        }
        return "This run has failed cases, but the failure surface is narrow enough to route through the detailed views directly.";
    }

    private static String landingNarrative(AnalysisReporter.AnalysisData data, String topModule, String topOwner, String topApi) {
        if (data.totalFailed <= 0) {
            return "No failed cases were recorded. The summary below is still available for quick navigation and post-run confirmation.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(data.totalFailed).append(" failed case(s) were captured across ").append(data.totalExecuted)
                .append(" executed step(s). ");
        sb.append("Top concentration sits in module ").append(topModule).append(", owner ").append(topOwner)
                .append(", and API ").append(topApi).append(". ");
        sb.append("Use this page to decide where to enter the detailed views, not to replace them.");
        return sb.toString();
    }

    private static void heroMeta(StringBuilder sb, String label, String value) {
        sb.append("<div class='meta-card'><div class='k'>").append(escape(label))
                .append("</div><div class='v'>").append(escape(value)).append("</div></div>");
    }

    private static List<AnalysisReporter.AnalyzedCase> httpCases(AnalysisReporter.AnalysisData data) {
        List<AnalysisReporter.AnalyzedCase> rows = new ArrayList<>();
        rows.addAll(data.statusCodeMismatches);
        rows.addAll(data.mixedFailures);
        return rows;
    }

    private static void mixItem(StringBuilder sb, String label, int count, String clazz) {
        sb.append("<div class='mix-item'><div class='mix-left'><span class='mix-dot ").append(clazz)
                .append("' style='background:var(--").append(clazz).append(")'></span><span>")
                .append(escape(label)).append("</span></div><span class='n'>").append(count).append("</span></div>");
    }

    private static void modernKpi(StringBuilder sb, String label, String value, String hint, String clazz) {
        sb.append("<div class='kpi").append(clazz.isBlank() ? "" : " " + clazz).append("'><div class='k'>")
                .append(escape(label)).append("</div><div class='v'>").append(escape(value))
                .append("</div><div class='hint'>").append(escape(hint)).append("</div></div>");
    }

    private static void renderRoute(StringBuilder sb, String title, int count, String copy, List<String> apis,
                                    String href, String linkLabel, String tone) {
        sb.append("<div class='route'><div class='route-top'><div><div class='route-title'>")
                .append(escape(title)).append("</div></div><div class='route-num ").append(escape(tone))
                .append("'>").append(count).append("</div></div><div class='route-copy'>")
                .append(escape(copy)).append("</div>");
        if (!apis.isEmpty()) {
            sb.append("<div class='chips'>");
            for (String api : apis) {
                chip(sb, api, "");
            }
            sb.append("</div>");
        }
        sb.append("<a class='link' href='").append(escape(href)).append("'>").append(escape(linkLabel)).append("</a></div>");
    }

    private static void renderBarRow(StringBuilder sb, String label, int count, int max) {
        double width = max <= 0 ? 0.0 : (count * 100.0) / max;
        sb.append("<div class='bar-row'><div class='bar-label' title='").append(escape(label)).append("'>")
                .append(escape(label)).append("</div><div class='bar-track'><div class='bar-fill' style='width:")
                .append(String.format(Locale.ROOT, "%.2f", width)).append("%'></div></div><div class='bar-n'>")
                .append(count).append("</div></div>");
    }

    private static void renderModernInsight(StringBuilder sb, LandingInsight insight) {
        sb.append("<div class='insight'><div><div class='insight-title'>").append(escape(insight.title))
                .append("</div><div class='small'>").append(escape(insight.actionBucket))
                .append(" route</div></div><div class='insight-copy'>").append(escape(insight.summary));
        if (!insight.apis.isEmpty()) {
            sb.append(" Top APIs: ").append(escape(String.join(", ", insight.apis))).append(".");
        }
        sb.append("</div><div class='side-note'><span class='badge ")
                .append(asStr(insight.severity).trim().toLowerCase(Locale.ROOT)).append("'>")
                .append(escape(insight.severity)).append("</span><span class='small'>Confidence: ")
                .append(escape(insight.confidence)).append("</span><a class='link' href='")
                .append(escape(insight.href)).append("'>").append(escape(insight.linkLabel))
                .append("</a></div></div>");
    }

    private static void renderCompactSignal(StringBuilder sb, String label, String count, String preview) {
        sb.append("<div class='row'><span title='").append(escape(label)).append("'>").append(escape(label))
                .append("</span><span>").append(escape(count)).append("</span><span class='mono' title='")
                .append(escape(preview)).append("'>").append(escape(displayPreview(preview))).append("</span></div>");
    }

    private static List<LandingInsight> strongestSignals(AnalysisReporter.AnalysisData data) {
        List<LandingInsight> insights = new ArrayList<>();
        LandingInsight application = applicationErrorInsight(data);
        LandingInsight timing = timingInsight(data);
        LandingInsight drift = baselineDriftInsight(data);
        LandingInsight calculation = calculationShiftInsight(data);
        if (application != null) insights.add(application);
        if (timing != null) insights.add(timing);
        if (drift != null) insights.add(drift);
        if (calculation != null) insights.add(calculation);
        insights.sort(Comparator
                .comparingInt(LandingInsight::priority)
                .thenComparing(Comparator.comparingInt(LandingInsight::count).reversed())
                .thenComparing(LandingInsight::title));
        return insights.size() > 3 ? new ArrayList<>(insights.subList(0, 3)) : insights;
    }

    private static LandingInsight applicationErrorInsight(AnalysisReporter.AnalysisData data) {
        List<AnalysisReporter.AnalyzedCase> cases = new ArrayList<>();
        cases.addAll(data.mixedFailures);
        cases.addAll(data.statusCodeMismatches);
        if (cases.isEmpty()) {
            return null;
        }
        AnalysisReporter.AnalyzedCase lead = cases.get(0);
        String preview = messagePreview(lead.actual);
        String summary = "Status-driven failures are present. Start here when the service returned the wrong code, an explicit business error, or a mixed failure with both code and payload changes.";
        if (!preview.isBlank()) {
            summary += " Lead example: " + preview;
        }
        return new LandingInsight(
                "Possible Application Error",
                "High",
                "Strong signal",
                "Investigate",
                summary,
                topApis(cases, 3),
                cases.size(),
                "index_http.html",
                "Open HTTP / Status View",
                0
        );
    }

    private static LandingInsight timingInsight(AnalysisReporter.AnalysisData data) {
        List<AnalysisReporter.AnalyzedCase> cases = new ArrayList<>();
        for (AnalysisReporter.AnalyzedCase row : data.payloadDiffs) {
            if (looksLikeLateState(row)) {
                cases.add(row);
            }
        }
        if (cases.isEmpty()) {
            return null;
        }
        return new LandingInsight(
                "Likely Timing / State Availability",
                "Medium",
                "Likely",
                "Re-run",
                "Expected content exists, but the captured actual result looks empty or not yet populated. This often points to validation running before downstream state was fully visible.",
                topApis(cases, 3),
                cases.size(),
                "index_diff.html",
                "Open Text / Payload View",
                1
        );
    }

    private static LandingInsight baselineDriftInsight(AnalysisReporter.AnalysisData data) {
        List<AnalysisReporter.AnalyzedCase> cases = new ArrayList<>();
        for (AnalysisReporter.AnalyzedCase row : data.payloadDiffs) {
            if (looksLikeBaselineDrift(row)) {
                cases.add(row);
            }
        }
        if (cases.isEmpty()) {
            return null;
        }
        return new LandingInsight(
                "Baseline / Contract Drift",
                "Medium",
                "Needs confirmation",
                "Rebaseline",
                "The response shape changed while the request still reached the expected path. This usually means extra fields, missing fields, or schema drift rather than an outright runtime error.",
                topApis(cases, 3),
                cases.size(),
                "index_diff.html",
                "Open Text / Payload View",
                2
        );
    }

    private static LandingInsight calculationShiftInsight(AnalysisReporter.AnalysisData data) {
        List<AnalysisReporter.AnalyzedCase> cases = new ArrayList<>();
        for (AnalysisReporter.AnalyzedCase row : data.payloadDiffs) {
            if (looksLikeCalculationShift(row)) {
                cases.add(row);
            }
        }
        if (cases.isEmpty()) {
            return null;
        }
        return new LandingInsight(
                "Calculation / Value Shift",
                "Medium",
                "Likely",
                "Investigate",
                "Expected and actual both contain data, but key returned values diverged. That is usually a business-rule change or a genuine calculation defect rather than a transport issue.",
                topApis(cases, 3),
                cases.size(),
                "index_diff.html",
                "Open Text / Payload View",
                3
        );
    }

    private static List<ActionBucket> actionBuckets(List<LandingInsight> strongestSignals) {
        Map<String, LandingInsight> byAction = new LinkedHashMap<>();
        for (LandingInsight insight : strongestSignals) {
            byAction.putIfAbsent(insight.actionBucket, insight);
        }

        List<ActionBucket> buckets = new ArrayList<>();
        buckets.add(bucketFor("Investigate", byAction.get("Investigate"),
                "Start with service-side failures, mixed failures, or value shifts that look like real behavior change."));
        buckets.add(bucketFor("Re-run", byAction.get("Re-run"),
                "Use this when state appears incomplete or downstream data looks empty at validation time."));
        buckets.add(bucketFor("Rebaseline", byAction.get("Rebaseline"),
                "Use this only after confirming the new response shape or field set is valid product behavior."));
        return buckets;
    }

    private static ActionBucket bucketFor(String title, LandingInsight insight, String fallback) {
        if (insight == null) {
            return new ActionBucket(title, fallback, "No strong candidate in this run.", "", "");
        }
        return new ActionBucket(
                title,
                insight.title,
                insight.summary,
                insight.href,
                insight.linkLabel
        );
    }

    private static List<PatternSnapshot> patternSnapshots(AnalysisReporter.AnalysisData data) {
        List<PatternSnapshot> out = new ArrayList<>();
        out.add(new PatternSnapshot(
                "Failure split",
                "How the failed cases divide between status issues, text/payload issues, and mixed failures.",
                List.of(
                        countChip("Status only", data.statusCodeMismatches.size(), "chip-warn"),
                        countChip("Text only", data.payloadDiffs.size(), "chip-fail"),
                        countChip("Mixed", data.mixedFailures.size(), "chip-mixed"),
                        countChip("Infra", data.infrastructureFailureCount, "chip-info")
                ),
                0
        ));
        if (!data.causeBreakdown.isEmpty()) {
            List<CountChip> causes = new ArrayList<>();
            for (int i = 0; i < Math.min(4, data.causeBreakdown.size()); i++) {
                AnalysisReporter.CauseCount row = data.causeBreakdown.get(i);
                causes.add(countChip(row.cause, row.count, "chip-neutral"));
            }
            out.add(new PatternSnapshot(
                    "Top mismatch causes",
                    "Most repeated structured diff causes across payload-driven failures.",
                    causes,
                    1
            ));
        }
        if (!data.topDiffPaths.isEmpty()) {
            List<CountChip> paths = new ArrayList<>();
            for (int i = 0; i < Math.min(4, data.topDiffPaths.size()); i++) {
                AnalysisReporter.PathCount row = data.topDiffPaths.get(i);
                paths.add(countChip(row.path, row.count, "chip-neutral"));
            }
            out.add(new PatternSnapshot(
                    "Repeated mismatch paths",
                    "Fields or collections that show up repeatedly in structured comparisons.",
                    paths,
                    2
            ));
        }
        return out;
    }

    private static List<CountChip> topCauseChips(AnalysisReporter.AnalysisData data, int limit) {
        List<CountChip> chips = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, data.causeBreakdown.size()); i++) {
            AnalysisReporter.CauseCount row = data.causeBreakdown.get(i);
            chips.add(countChip(row.cause, row.count, "chip-neutral"));
        }
        return chips;
    }

    private static List<CountChip> topPathChips(AnalysisReporter.AnalysisData data, int limit) {
        List<CountChip> chips = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, data.topDiffPaths.size()); i++) {
            AnalysisReporter.PathCount row = data.topDiffPaths.get(i);
            chips.add(countChip(row.path, row.count, "chip-neutral"));
        }
        return chips;
    }

    private static List<AnalysisReporter.CaseDigest> topTextCases(AnalysisReporter.AnalysisData data, int limit) {
        List<AnalysisReporter.CaseDigest> rows = new ArrayList<>();
        for (AnalysisReporter.CaseDigest row : data.largestDiffCases) {
            if (!"STATUS_CODE".equals(row.failureType)) {
                rows.add(row);
            }
            if (rows.size() >= limit) {
                break;
            }
        }
        return rows;
    }

    private static CountChip countChip(String label, int count, String clazz) {
        return new CountChip(label, count, clazz);
    }

    private static boolean looksLikeLateState(AnalysisReporter.AnalyzedCase row) {
        if (isBlankJson(row.expected) || !isBlankJson(row.actual)) {
            return false;
        }
        return hasCause(row, "LengthMismatch", "MissingField", "UnexpectedItem", "UnexpectedField");
    }

    private static boolean looksLikeBaselineDrift(AnalysisReporter.AnalyzedCase row) {
        return hasCause(row, "UnexpectedField", "UnexpectedItem", "MissingField", "TypeMismatch");
    }

    private static boolean looksLikeCalculationShift(AnalysisReporter.AnalyzedCase row) {
        return !isBlankJson(row.expected) && !isBlankJson(row.actual) && hasCause(row, "ValueMismatch", "NumericTolerance");
    }

    private static boolean hasCause(AnalysisReporter.AnalyzedCase row, String... causes) {
        Set<String> expected = new LinkedHashSet<>();
        Collections.addAll(expected, causes);
        for (Map<String, Object> diff : row.comparison) {
            String cause = normalizeCause(firstNonBlank(diff.get("cause"), diff.get("kind"), "Unknown"));
            if (expected.contains(cause)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlankJson(String raw) {
        JsonNode node = parseJson(raw);
        return isEffectivelyEmpty(node);
    }

    private static boolean isEffectivelyEmpty(JsonNode node) {
        if (node == null || node.isNull()) {
            return true;
        }
        if (node.isTextual()) {
            return node.asText().trim().isEmpty();
        }
        if (node.isArray()) {
            if (node.size() == 0) {
                return true;
            }
            for (JsonNode child : node) {
                if (!isEffectivelyEmpty(child)) {
                    return false;
                }
            }
            return true;
        }
        if (node.isObject()) {
            if (node.size() == 0) {
                return true;
            }
            for (JsonNode child : node) {
                if (!isEffectivelyEmpty(child)) {
                    return false;
                }
            }
            return true;
        }
        String text = node.toString().trim();
        return text.isEmpty() || "\"\"".equals(text);
    }

    private static List<String> topApis(List<AnalysisReporter.AnalyzedCase> rows, int limit) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AnalysisReporter.AnalyzedCase row : rows) {
            String api = row.api == null || row.api.isBlank() ? "NA" : row.api.trim();
            counts.put(api, counts.getOrDefault(api, 0) + 1);
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, entries.size()); i++) {
            out.add(entries.get(i).getKey());
        }
        return out;
    }

    private static void renderInsight(StringBuilder sb, LandingInsight insight) {
        sb.append("<div class='insight-card'><div class='insight-head'><div><div class='signal-kicker'>")
                .append(escape(insight.actionBucket)).append("</div><h3>").append(escape(insight.title))
                .append("</h3></div><span class='badge ").append(severityClass(insight.severity)).append("'>")
                .append(escape(insight.severity)).append("</span></div>");
        sb.append("<div class='confidence'>Confidence: ").append(escape(insight.confidence)).append("</div>");
        sb.append("<div class='body'>").append(escape(insight.summary)).append("</div>");
        if (!insight.apis.isEmpty()) {
            sb.append("<div class='api-list'>");
            for (String api : insight.apis) {
                sb.append("<span class='api-tag'><span>").append(escape(api)).append("</span></span>");
            }
            sb.append("</div>");
        }
        sb.append("<div class='insight-foot'><span class='countpill'>").append(insight.count)
                .append(" cases</span><a class='cta' href='").append(escape(insight.href)).append("'>")
                .append(escape(insight.linkLabel)).append("</a></div></div>");
    }

    private static void renderBucket(StringBuilder sb, ActionBucket bucket) {
        sb.append("<div class='bucket'><div class='bucket-head'><div><div class='signal-kicker'>")
                .append(escape(bucket.title)).append("</div><div class='bucket-title'>")
                .append(escape(bucket.headline)).append("</div></div>");
        if (!bucket.href.isBlank()) {
            sb.append("<a class='cta' href='").append(escape(bucket.href)).append("'>")
                    .append(escape(bucket.linkLabel)).append("</a>");
        }
        sb.append("</div><div class='bucket-copy'>").append(escape(bucket.copy)).append("</div></div>");
    }

    private static void renderTriageOrder(StringBuilder sb, List<LandingInsight> strongestSignals, List<ActionBucket> actionBuckets) {
        if (!strongestSignals.isEmpty()) {
            for (LandingInsight insight : strongestSignals) {
                sb.append("<li><b>").append(escape(insight.title)).append(".</b> ").append(escape(insight.summary)).append("</li>");
            }
            return;
        }
        for (ActionBucket bucket : actionBuckets) {
            sb.append("<li><b>").append(escape(bucket.title)).append(".</b> ").append(escape(bucket.copy)).append("</li>");
        }
    }

    private static void renderSnapshot(StringBuilder sb, PatternSnapshot snapshot) {
        sb.append("<div class='snapshot'><details")
                .append(snapshot.priority == 0 ? " open" : "")
                .append("><summary><span>").append(escape(snapshot.title)).append("</span><span class='meta'>")
                .append(snapshot.chips.size()).append(" signal(s)</span></summary><div class='snapshot-copy'>")
                .append(escape(snapshot.copy)).append("</div><div class='snapshot-row'>");
        for (CountChip chip : snapshot.chips) {
            splitChip(sb, chip.label, chip.count, chip.clazz);
        }
        sb.append("</div></details></div>");
    }

    private static String severityClass(String severity) {
        String normalized = asStr(severity).trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "high":
                return "badge-high";
            case "medium":
                return "badge-medium";
            default:
                return "badge-low";
        }
    }

    private static void kpi(StringBuilder sb, String label, String value, String clazz) {
        sb.append("<div class='card'><div class='k'>").append(label).append("</div><div class='v ")
                .append(clazz).append("'>").append(value).append("</div></div>");
    }

    private static void chip(StringBuilder sb, String value, String clazz) {
        sb.append("<span class='chip ").append(clazz).append("'>").append(escape(value)).append("</span>");
    }

    private static void metaItem(StringBuilder sb, String label, String value) {
        sb.append("<div class='meta-item'><div class='meta-label'>").append(escape(label))
                .append("</div><div class='meta-value'>").append(escape(value)).append("</div></div>");
    }

    private static void splitSegment(StringBuilder sb, String label, int count, int total, String clazz) {
        if (count <= 0 || total <= 0) {
            return;
        }
        double width = (count * 100.0) / total;
        sb.append("<div class='seg ").append(clazz).append("' style='width:")
                .append(String.format(Locale.ROOT, "%.2f", width)).append("%' title='")
                .append(escape(label)).append(": ").append(count).append("'></div>");
    }

    private static void spotlight(StringBuilder sb, String label, String name, int failures) {
        sb.append("<div class='spot'><div class='spot-label'>").append(escape(label)).append("</div><div class='spot-name'>")
                .append(escape(name)).append("</div><div class='spot-meta'>").append(failures)
                .append(" failure(s)</div></div>");
    }

    private static void splitChip(StringBuilder sb, String label, int count, String clazz) {
        sb.append("<span class='split-chip ").append(clazz).append("'>").append(escape(label)).append(" <b>")
                .append(count).append("</b></span>");
    }

    private static String viewCard(String href, String clazz, String title, String label, String text, int count, String ctaLabel) {
        return "<a class='card viewcard " + escape(clazz) + "' href='" + href + "'><div class='eyebrow'>" + escape(label) + "</div><h3>"
                + escape(title) + "</h3><div class='body'>" + escape(text) + "</div><div class='viewfoot'><span class='countpill'>"
                + count + " cases</span><span class='cta'>" + escape(ctaLabel) + "</span></div></a>";
    }

    private static String markComparisonPane(String html, String id) {
        return html.replaceFirst("<pre class='json'>", "<pre id='" + escape(id) + "' class='json compare-json'>");
    }

    private static String escape(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String asStr(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String formatRunFolder(String raw) {
        String value = asStr(raw).trim();
        if (value.isEmpty()) return "NA";
        try {
            return LocalDateTime.parse(value.toUpperCase(Locale.ROOT), RUN_FOLDER_INPUT).format(DISPLAY_TS);
        } catch (Exception ignored) {
            return value.replace('_', ' ');
        }
    }

    private static String formatGeneratedAt(String raw) {
        String value = asStr(raw).trim();
        if (value.isEmpty()) return "NA";
        try {
            return DISPLAY_TS.format(Instant.parse(value).atOffset(ZoneOffset.UTC)) + " UTC";
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value);
    }

    private static String endpoint(String url) {
        String raw = asStr(url).trim();
        if (raw.isEmpty()) return "NA";
        int scheme = raw.indexOf("://");
        if (scheme >= 0) {
            int slash = raw.indexOf('/', scheme + 3);
            return slash >= 0 ? raw.substring(slash) : "/";
        }
        return raw;
    }

    private static String messagePreview(String actualRaw) {
        JsonNode node = parseJson(actualRaw);
        if (node != null && node.isObject()) {
            JsonNode nestedError = node.get("errorResponse");
            String nestedMessage = firstText(nestedError, "errorMessage", "message", "detail", "description", "title");
            if (!nestedMessage.isBlank()) {
                return nestedMessage;
            }
            for (String key : new String[]{"message", "error", "detail", "title", "description"}) {
                JsonNode child = node.get(key);
                if (child != null && child.isTextual() && !child.asText().isBlank()) {
                    return child.asText();
                }
            }
        }
        String compact = asStr(actualRaw).replaceAll("\\s+", " ").trim();
        return compact.length() > 180 ? compact.substring(0, 180) + "..." : compact;
    }

    private static String firstText(JsonNode node, String... keys) {
        if (node == null || !node.isObject()) {
            return "";
        }
        for (String key : keys) {
            JsonNode child = node.get(key);
            if (child != null && child.isTextual() && !child.asText().isBlank()) {
                return child.asText();
            }
        }
        return "";
    }

    private static String displayPreview(String raw) {
        String value = asStr(raw).trim();
        if ("[]".equals(value)) {
            return "collection root";
        }
        if ("$".equals(value)) {
            return "document root";
        }
        return value;
    }

    private static final class MarkSets {
        final Set<String> expectedMarks;
        final Set<String> actualMarks;

        MarkSets(Set<String> expectedMarks, Set<String> actualMarks) {
            this.expectedMarks = expectedMarks;
            this.actualMarks = actualMarks;
        }
    }

    private static final class PathToken {
        enum Kind { KEY, INDEX, SELECTOR }

        final Kind kind;
        final String value;

        private PathToken(Kind kind, String value) {
            this.kind = kind;
            this.value = value;
        }

        static PathToken key(String value) {
            return new PathToken(Kind.KEY, value);
        }

        static PathToken index(int value) {
            return new PathToken(Kind.INDEX, String.valueOf(value));
        }

        static PathToken selector(String value) {
            return new PathToken(Kind.SELECTOR, value);
        }
    }

    private static final class KeySelector {
        final String field;
        final String value;

        private KeySelector(String field, String value) {
            this.field = field;
            this.value = value;
        }
    }

    private static final class ResolvedNode {
        final JsonNode node;
        final String pointer;

        private ResolvedNode(JsonNode node, String pointer) {
            this.node = node;
            this.pointer = pointer;
        }
    }

    private static final class IndexRow {
        public final String api;
        public final int step;
        public final String module;
        public final String owner;
        public final String expectedActual;
        public final int comparisonCount;
        public final String primaryCause;
        public final String topPaths;
        public final String endpoint;
        public final String link;

        private IndexRow(String api, int step, String module, String owner, String expectedActual,
                         int comparisonCount, String primaryCause,
                         String topPaths, String endpoint, String link) {
            this.api = api;
            this.step = step;
            this.module = module;
            this.owner = owner;
            this.expectedActual = expectedActual;
            this.comparisonCount = comparisonCount;
            this.primaryCause = primaryCause;
            this.topPaths = topPaths;
            this.endpoint = endpoint;
            this.link = link;
        }

        static IndexRow from(AnalysisReporter.AnalyzedCase row, String fileName) {
            String primaryCause = "STATUS_CODE";
            if (row.comparison != null && !row.comparison.isEmpty()) {
                Map<String, Object> first = row.comparison.get(0);
                primaryCause = normalizeCause(firstNonBlank(first.get("cause"), first.get("kind"), "Unknown"));
            }
            return new IndexRow(
                    row.api,
                    row.step,
                    row.module,
                    row.owner,
                    row.expectedCode + " / " + row.actualCode,
                    row.comparison == null ? 0 : row.comparison.size(),
                    primaryCause,
                    topMismatchPathSummary(row.comparison),
                    endpoint(row.url),
                    fileName
            );
        }
    }

    private static final class LandingInsight {
        final String title;
        final String severity;
        final String confidence;
        final String actionBucket;
        final String summary;
        final List<String> apis;
        final int count;
        final String href;
        final String linkLabel;
        final int priority;

        private LandingInsight(String title, String severity, String confidence, String actionBucket,
                               String summary, List<String> apis, int count, String href, String linkLabel, int priority) {
            this.title = title;
            this.severity = severity;
            this.confidence = confidence;
            this.actionBucket = actionBucket;
            this.summary = summary;
            this.apis = apis;
            this.count = count;
            this.href = href;
            this.linkLabel = linkLabel;
            this.priority = priority;
        }

        int priority() {
            return priority;
        }

        int count() {
            return count;
        }

        String title() {
            return title;
        }
    }

    private static final class ActionBucket {
        final String title;
        final String headline;
        final String copy;
        final String href;
        final String linkLabel;

        private ActionBucket(String title, String headline, String copy, String href, String linkLabel) {
            this.title = title;
            this.headline = headline;
            this.copy = copy;
            this.href = href;
            this.linkLabel = linkLabel;
        }
    }

    private static final class PatternSnapshot {
        final String title;
        final String copy;
        final List<CountChip> chips;
        final int priority;

        private PatternSnapshot(String title, String copy, List<CountChip> chips, int priority) {
            this.title = title;
            this.copy = copy;
            this.chips = chips;
            this.priority = priority;
        }
    }

    private static final class CountChip {
        final String label;
        final int count;
        final String clazz;

        private CountChip(String label, int count, String clazz) {
            this.label = label;
            this.count = count;
            this.clazz = clazz;
        }
    }
}
