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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

public class HtmlReportWriter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter REPORT_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public static void write(Path htmlFile, List<ExecutionReport> reports) throws IOException {
        Files.createDirectories(htmlFile.getParent());
        AtomicInteger syncGroupSeq = new AtomicInteger(1);

        int testCasesExecuted = reports.stream().mapToInt(r -> r.stepsExecuted).sum();
        int testCasesPassed = reports.stream().mapToInt(r -> r.stepsPassed).sum();
        int testCasesFailed = reports.stream().mapToInt(r -> r.stepsFailed).sum();
        double passRate = testCasesExecuted > 0 ? (testCasesPassed * 100.0 / testCasesExecuted) : 0.0;

        List<Map<String, Object>> allRows = new ArrayList<>();
        Map<String, Integer> failByApi = new HashMap<>();
        Map<Integer, Integer> failByCode = new HashMap<>();
        Map<Integer, Integer> allByCode = new HashMap<>();
        Map<String, Integer> failByOwner = new HashMap<>();
        Map<String, Integer> failByModule = new HashMap<>();

        for (ExecutionReport report : reports) {
            for (Map<String, Object> p : report.passes) {
                Map<String, Object> row = new HashMap<>(p);
                row.put("__status", "pass");
                row.put("__manifest", report.manifest);
                allRows.add(row);
                int code = asInt(p.get("actualCode"));
                allByCode.put(code, allByCode.getOrDefault(code, 0) + 1);
            }
            for (Map<String, Object> f : report.failures) {
                Map<String, Object> row = new HashMap<>(f);
                row.put("__status", "fail");
                row.put("__manifest", report.manifest);
                allRows.add(row);

                String api = asStr(f.get("api"));
                String owner = asStr(f.get("owner"));
                String module = asStr(f.get("module"));
                int code = asInt(f.get("actualCode"));

                failByApi.put(api, failByApi.getOrDefault(api, 0) + 1);
                failByOwner.put(owner, failByOwner.getOrDefault(owner, 0) + 1);
                failByModule.put(module, failByModule.getOrDefault(module, 0) + 1);
                failByCode.put(code, failByCode.getOrDefault(code, 0) + 1);
                allByCode.put(code, allByCode.getOrDefault(code, 0) + 1);
            }
        }

        allRows.sort(
                Comparator
                        .comparingInt((Map<String, Object> v) -> manifestOrder(asStr(v.get("__manifest"))))
                        .thenComparing(v -> asStr(v.get("api")))
                        .thenComparingInt(v -> asInt(v.get("step")))
        );

        List<Map.Entry<String, Integer>> topFailApis = sortByCountDesc(failByApi);
        List<Map.Entry<String, Integer>> topFailOwners = sortByCountDesc(failByOwner);
        List<Map.Entry<String, Integer>> topFailModules = sortByCountDesc(failByModule);
        List<String> ownerOptions = distinctValues(allRows, "owner");
        List<String> moduleOptions = distinctValues(allRows, "module");
        List<String> manifestOptions = distinctManifestBuckets(allRows);

        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset='utf-8'>");
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
        sb.append("<title>Java Regression Execute Report</title>");
        sb.append("<style>");
        sb.append("@import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=Space+Grotesk:wght@500;700&display=swap');");
        sb.append(":root{--bg:#f2efe8;--surface:#fcfbf8;--surface-2:#f7f4ee;--ink:#16181d;--muted:#6f6a62;--line:#ddd5c9;--hero:#1f2421;--hero2:#35594a;--hero3:#7a8f63;--ok:#2e7d5a;--fail:#d45b4b;--warn:#c76b2a;--other:#8d867c;--shadow:0 18px 38px rgba(22,24,29,.10);--shadow-soft:0 8px 20px rgba(22,24,29,.06)}");
        sb.append("*{box-sizing:border-box} body{margin:0;font-family:'Inter',ui-sans-serif,system-ui,-apple-system,'Segoe UI',sans-serif;color:var(--ink);background:radial-gradient(circle at top right,rgba(122,143,99,.16),transparent 28%),linear-gradient(180deg,#ece7de 0%,var(--bg) 220px)}");
        sb.append(".wrap{max-width:1700px;margin:0 auto;padding:22px}");
        sb.append(".topbar{display:flex;justify-content:space-between;align-items:center;gap:14px;margin-bottom:14px}.brandline{display:flex;align-items:center;gap:10px;color:#5f5a52;font-size:12px;font-weight:900;letter-spacing:.05em;text-transform:uppercase}.branddot{width:10px;height:10px;border-radius:999px;background:linear-gradient(135deg,var(--hero2),var(--hero3))}");
        sb.append(".statuspill{display:inline-flex;align-items:center;gap:8px;padding:8px 12px;border-radius:999px;background:rgba(252,251,248,.92);border:1px solid var(--line);box-shadow:var(--shadow-soft);font-size:12px;font-weight:900;color:#3c4a43}.statuspill .dot{width:9px;height:9px;border-radius:999px;background:var(--ok)}");
        sb.append(".hero{background:linear-gradient(135deg,rgba(255,255,255,.08),rgba(255,255,255,.03)),linear-gradient(135deg,var(--hero) 0%,var(--hero2) 52%,var(--hero3) 100%);color:#f6f4ef;padding:24px 26px;border-radius:28px;box-shadow:var(--shadow);position:relative;overflow:hidden}");
        sb.append(".hero:before,.hero:after{content:'';position:absolute;border-radius:999px;background:rgba(255,255,255,.08)}.hero:before{width:320px;height:320px;right:-120px;top:-120px}.hero:after{width:180px;height:180px;right:180px;bottom:-90px}");
        sb.append(".hero h1{margin:0;font-family:'Space Grotesk',sans-serif;font-size:34px;letter-spacing:0;line-height:1.05;position:relative;z-index:1}");
        sb.append(".hero .sub{margin-top:8px;font-size:14px;line-height:1.6;opacity:.95;position:relative;z-index:1;max-width:880px}");
        sb.append(".grid{display:grid;grid-template-columns:repeat(5,minmax(160px,1fr));gap:12px;margin-top:18px}");
        sb.append(".kpi{background:var(--surface);border:1px solid var(--line);border-radius:18px;padding:14px 16px;box-shadow:var(--shadow-soft)}");
        sb.append(".kpi .k{font-size:11px;color:var(--muted);text-transform:uppercase;letter-spacing:.05em}");
        sb.append(".kpi .v{margin-top:8px;font-family:'Space Grotesk',sans-serif;font-size:28px;font-weight:700;line-height:1}");
        sb.append(".v.ok{color:var(--ok)} .v.fail{color:var(--fail)}");
        sb.append(".progress{margin-top:14px;height:10px;background:rgba(255,255,255,.16);border-radius:999px;overflow:hidden;position:relative;z-index:1}");
        sb.append(".progress span{display:block;height:100%;background:linear-gradient(90deg,#f6f4ef,var(--hero3))}");
        sb.append(".section{margin-top:18px;background:var(--surface);border:1px solid var(--line);border-radius:24px;box-shadow:var(--shadow-soft)}");
        sb.append(".section .hd{padding:16px 18px;border-bottom:1px solid var(--line);font-family:'Space Grotesk',sans-serif;font-size:16px}");
        sb.append(".section .bd{padding:12px 16px}");
        sb.append(".mini{display:grid;grid-template-columns:2fr 1fr 1fr 1fr;gap:14px}");
        sb.append(".bars .row{display:grid;grid-template-columns:220px 1fr 42px;gap:8px;align-items:center;margin:7px 0}");
        sb.append(".bars .name{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:13px}");
        sb.append(".bar{height:12px;background:#ede7de;border:1px solid var(--line);border-radius:999px;overflow:hidden}");
        sb.append(".bar > span{display:block;height:100%;background:linear-gradient(90deg,var(--hero2),var(--hero3))}");
        sb.append(".codechip{display:inline-block;margin:4px 6px 0 0;padding:6px 10px;border-radius:999px;background:#fff;border:1px solid var(--line);font-size:12px;cursor:pointer;text-decoration:none;color:#24473c;font-weight:700;transition:all .12s ease}");
        sb.append(".codechip:hover{background:#f2efe8;transform:translateY(-1px)}");
        sb.append(".clickchip{display:inline-block;margin:4px 6px 0 0;padding:6px 10px;border-radius:999px;background:#f2efe8;border:1px solid var(--line);font-size:12px;color:#3f5146;text-decoration:none;cursor:pointer;font-weight:700}");
        sb.append(".controls{display:flex;gap:10px;flex-wrap:wrap;align-items:center}");
        sb.append(".btn{padding:8px 12px;border:1px solid var(--line);background:#fff;border-radius:999px;font-size:12px;color:#2c3442;text-decoration:none;cursor:pointer;font-weight:700}");
        sb.append(".btn.active{background:var(--hero);color:#fff;border-color:var(--hero)}");
        sb.append(".btn.secondary{background:var(--surface-2)}");
        sb.append("input,select{border:1px solid var(--line);background:#fff;border-radius:12px;padding:9px 11px;font-size:13px;min-width:190px}");
        sb.append(".meta{font-size:12px;color:var(--muted)}");
        sb.append(".muted{color:var(--muted)}");
        sb.append(".activefilters{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}");
        sb.append(".filterchip{display:inline-flex;align-items:center;padding:6px 10px;border-radius:999px;background:#f2efe8;border:1px solid var(--line);font-size:12px;color:#3f5146}");
        sb.append(".tblwrap{overflow:auto;max-height:70vh}");
        sb.append("table{width:100%;border-collapse:collapse;min-width:1280px}");
        sb.append("th,td{padding:9px 8px;border-bottom:1px solid #ece4d8;vertical-align:top;font-size:12.5px}");
        sb.append("th{position:sticky;top:0;background:#f7f4ee;z-index:2;text-align:left;color:#514b43}");
        sb.append("tbody tr:hover{background:#faf6ef} tbody tr:nth-child(even){background:#fdfbf7}");
        sb.append("tbody tr.case-row{cursor:pointer}");
        sb.append("tbody tr.selected{background:#f3efe6 !important;box-shadow:inset 3px 0 0 var(--hero2)}");
        sb.append(".tag{display:inline-block;padding:3px 8px;border-radius:999px;font-size:11px;font-weight:700}");
        sb.append(".tag.pass{background:#def7e8;color:#0d7a42}.tag.fail{background:#fee2e2;color:#c62828}");
        sb.append(".code{font-family:'Space Grotesk',sans-serif;font-weight:700}");
        sb.append(".code.fail{color:var(--fail)} .code.ok{color:var(--ok)}");
        sb.append("details{margin:4px 0} summary{cursor:pointer;color:#24473c;font-size:12px;font-weight:700}");
        sb.append("pre{white-space:pre-wrap;word-break:break-word;background:#f9f7f2;border:1px solid #e8dfd3;padding:8px;border-radius:10px;max-height:260px;overflow:auto;margin:6px 0}");
        sb.append(".split{display:grid;grid-template-columns:1fr 1fr;gap:8px;min-width:0}");
        sb.append(".pane{border:1px solid #e8dfd3;border-radius:10px;padding:6px;background:#fff;min-width:0;overflow:hidden}");
        sb.append(".pane h4{margin:0 0 6px 0;font-size:11px;color:#546277;text-transform:uppercase;letter-spacing:.3px}");
        sb.append(".panetools{display:flex;gap:6px;justify-content:flex-end;margin-bottom:6px}");
        sb.append(".smallbtn{padding:5px 8px;border:1px solid var(--line);background:#fff;border-radius:8px;font-size:11px;color:#24473c;cursor:pointer;font-weight:700}");
        sb.append(".smallbtn:hover{background:#f2efe8}");
        sb.append(".codebox{display:block;width:100%;min-height:420px;max-height:55vh;resize:vertical;overflow:auto;border:1px solid #e8dfd3;border-radius:10px;background:#f9f7f2;padding:10px 12px;font-size:12px;line-height:1.6;font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;color:#182338;white-space:pre;tab-size:2;-webkit-user-select:text;user-select:text;box-sizing:border-box}");
        sb.append(".code-line{display:block;min-height:1.6em}");
        sb.append(".diff-line{display:block;min-height:1.6em;background:#fff8e1;border-left:3px solid #f59e0b;padding-left:8px}");
        sb.append(".mismatchlist{margin:0;padding-left:18px;font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;font-size:12px;line-height:1.6}");
        sb.append(".mismatchlist li{margin:6px 0}");
        sb.append(".mismatchpath{font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;font-size:12px;font-weight:700;color:#1f3550}");
        sb.append(".assertiontable{width:100%;min-width:720px;border-collapse:collapse;margin-top:8px}.assertiontable th,.assertiontable td{padding:7px 8px;border-bottom:1px solid #ece4d8;font-size:12px}.assertiontable th{position:static;background:#f7f4ee}.assertiontable .mono{font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace}.assertiontable .pass{color:var(--ok);font-weight:900}.assertiontable .fail{color:var(--fail);font-weight:900}");
        sb.append(".pill{display:inline-block;padding:2px 8px;border-radius:999px;background:#f2efe8;border:1px solid var(--line);font-size:11px}");
        sb.append(".summarybox{display:flex;flex-direction:column;gap:6px;min-width:260px}");
        sb.append(".summaryrow{display:flex;gap:8px;align-items:center;flex-wrap:wrap}");
        sb.append(".badge{display:inline-block;padding:3px 8px;border-radius:999px;font-size:11px;font-weight:700;background:#edf2f8;color:#334155}");
        sb.append(".badge.fail{background:#fee2e2;color:#c62828}");
        sb.append(".badge.pass{background:#def7e8;color:#0d7a42}");
        sb.append(".rowpreview{font-size:12px;font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;color:#334155}");
        sb.append(".nestedetails{margin-top:6px}");
        sb.append(".detailgrid{display:grid;grid-template-columns:minmax(240px,0.85fr) minmax(0,2fr);gap:12px}");
        sb.append(".detailpanel{border:1px solid var(--line);border-radius:14px;background:#fff;padding:10px;min-width:0;overflow:hidden}");
        sb.append(".detailpanel h3{margin:0 0 8px 0;font-size:13px;font-family:'Space Grotesk',sans-serif}");
        sb.append(".detailtoggle{padding:6px 10px;border:1px solid var(--line);background:#fff;border-radius:999px;font-size:12px;color:#1f3550;cursor:pointer}");
        sb.append(".detailtoggle:hover{background:#eef3fb}");
        sb.append(".reportlayout{display:grid;grid-template-columns:minmax(0,1fr);gap:14px;align-items:start}");
        sb.append(".reportlayout.has-open{grid-template-columns:minmax(0,1fr)}");
        sb.append(".tablepane{min-width:0}");
        sb.append(".drawer-backdrop{display:none;position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:998;backdrop-filter:blur(2px)}");
        sb.append(".drawer-backdrop.open{display:block}");
        sb.append(".drawer{display:none;position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);width:min(1560px,97vw);height:min(94vh,960px);z-index:999;border:1px solid var(--line);border-radius:18px;background:var(--surface);box-shadow:0 20px 60px rgba(20,30,50,.28);overflow:hidden;flex-direction:column}");
        sb.append(".drawer.open{display:flex}");
        sb.append(".drawerhead{display:flex;justify-content:space-between;gap:10px;align-items:flex-start;padding:14px 16px;border-bottom:1px solid var(--line);background:linear-gradient(180deg,#fcfbf8 0%,#f7f4ee 100%)}");
        sb.append(".drawerhead h2{margin:0;font-size:16px;font-family:'Space Grotesk',sans-serif}");
        sb.append(".drawermeta{margin-top:6px;display:flex;gap:8px;flex-wrap:wrap}");
        sb.append(".drawerbody{padding:14px 16px;flex:1;overflow:auto;min-height:0}");
        sb.append(".drawerclose{padding:7px 11px;border:1px solid var(--line);background:#fff;border-radius:999px;font-size:12px;cursor:pointer;white-space:nowrap;font-weight:700;color:#24473c}");
        sb.append(".drawerhint{font-size:13px;line-height:1.5;text-align:center;max-width:280px}");
        sb.append(".detailstack{display:flex;flex-direction:column;gap:12px}");
        sb.append(".caseheader{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-bottom:8px}");
        sb.append(".hidden{display:none}");
        sb.append(".openstate{display:inline-block;padding:2px 8px;border-radius:999px;background:#eef3fb;border:1px solid var(--line);font-size:11px;color:#1f3550}");
        sb.append(".pager{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-left:auto}");
        sb.append("@media (max-width:1200px){.mini{grid-template-columns:1fr 1fr}.grid{grid-template-columns:repeat(2,minmax(160px,1fr))}.detailgrid{grid-template-columns:1fr}.split{grid-template-columns:1fr}.drawer{width:min(99vw,1560px);height:min(96vh,960px)}}");
        sb.append("@media (max-width:760px){.mini{grid-template-columns:1fr}.split{grid-template-columns:1fr;min-width:280px}}");
        sb.append("</style></head><body><div class='wrap'>");

        sb.append("<div class='topbar'><div class='brandline'><span class='branddot'></span><span>Regression Analysis</span></div>");
        sb.append("<div class='statuspill'><span class='dot'></span><span>")
                .append(testCasesFailed > 0 ? "Focused execution review recommended" : "Execution clean")
                .append("</span></div></div>");
        sb.append("<div class='hero'>");
        sb.append("<h1>Regression Execution Dashboard</h1>");
        sb.append("<div class='sub'>Generated on ").append(escape(formatReportDate(Instant.now())))
                .append(" | Open rows to inspect request, response, and mismatch artifacts.</div>");
        sb.append("<div class='progress'><span style='width:").append(String.format(Locale.ROOT, "%.2f", passRate)).append("%'></span></div>");
        sb.append("</div>");

        sb.append("<div class='grid'>");
        kpi(sb, "Total Test Cases", String.valueOf(testCasesExecuted), "");
        kpi(sb, "Passed Test Cases", String.valueOf(testCasesPassed), "ok");
        kpi(sb, "Failed Test Cases", String.valueOf(testCasesFailed), "fail");
        kpi(sb, "Pass Rate", String.format(Locale.ROOT, "%.2f%%", passRate), "");
        kpi(sb, "Manifests", String.valueOf(reports.size()), "");
        sb.append("</div>");

        sb.append("<div class='section'><div class='hd'>Failure Insights</div><div class='bd mini'>");
        sb.append("<div class='bars'>");
        if (topFailApis.isEmpty()) {
            sb.append("<div class='meta'>No failures yet.</div>");
        } else {
            int maxFail = topFailApis.get(0).getValue();
            int show = Math.min(10, topFailApis.size());
            for (int i = 0; i < show; i++) {
                Map.Entry<String, Integer> e = topFailApis.get(i);
                int pct = maxFail == 0 ? 0 : (e.getValue() * 100 / maxFail);
                sb.append("<div class='row'><div class='name'>").append(escape(e.getKey())).append("</div>");
                sb.append("<div class='bar'><span style='width:").append(pct).append("%'></span></div>");
                sb.append("<div>").append(e.getValue()).append("</div></div>");
            }
        }
        sb.append("</div>");

        sb.append("<div>");
        sb.append("<div class='meta'>By Owner (click to filter)</div>");
        renderClickableTopList(sb, topFailOwners, 12, "owner");
        sb.append("</div>");

        sb.append("<div>");
        sb.append("<div class='meta'>By Module (click to filter)</div>");
        renderClickableTopList(sb, topFailModules, 12, "module");
        sb.append("</div>");

        sb.append("<div>");
        sb.append("<div class='meta'>By HTTP Code</div>");
        if (failByCode.isEmpty()) {
            sb.append("<div class='meta'>No failed HTTP codes.</div>");
        } else {
            List<Map.Entry<Integer, Integer>> codeEntries = new ArrayList<>(failByCode.entrySet());
            codeEntries.sort((a, b) -> Integer.compare(a.getKey(), b.getKey()));
            for (Map.Entry<Integer, Integer> e : codeEntries) {
                sb.append("<a class='codechip' title='Click to filter by HTTP ").append(e.getKey())
                        .append("' onclick=\"setCodeFilter('").append(e.getKey()).append("');return false;\">Filter HTTP ")
                        .append(e.getKey()).append(": ").append(e.getValue()).append("</a>");
            }
        }
        sb.append("</div>");
        sb.append("</div></div>");

        sb.append("<div class='section'><div class='hd'>Execution Rows</div><div class='bd'>");
        sb.append("<div class='controls'>");
        sb.append("<button class='btn active' data-filter='fail' onclick=\"setStatusFilter(this,'fail')\">Failed</button>");
        sb.append("<button class='btn' data-filter='pass' onclick=\"setStatusFilter(this,'pass')\">Passed</button>");
        sb.append("<button class='btn' data-filter='all' onclick=\"setStatusFilter(this,'all')\">All</button>");
        sb.append("<select id='manifestFilter' onchange='resetAndApply()'>");
        sb.append("<option value='all'>All Manifests</option>");
        for (String manifestOption : manifestOptions) {
            sb.append("<option value='").append(escapeAttr(manifestOption)).append("'>Manifest ")
                    .append(escape(manifestOption)).append("</option>");
        }
        sb.append("</select>");
        sb.append("<select id='ownerFilter' onchange='resetAndApply()'>");
        sb.append("<option value='all'>All Owners</option>");
        for (String ownerOption : ownerOptions) {
            sb.append("<option value='").append(escapeAttr(ownerOption)).append("'>")
                    .append(escape(ownerOption)).append("</option>");
        }
        sb.append("</select>");
        sb.append("<select id='moduleFilter' onchange='resetAndApply()'>");
        sb.append("<option value='all'>All Modules</option>");
        for (String moduleOption : moduleOptions) {
            sb.append("<option value='").append(escapeAttr(moduleOption)).append("'>")
                    .append(escape(moduleOption)).append("</option>");
        }
        sb.append("</select>");
        sb.append("<select id='codeFilter' onchange='resetAndApply()'>");
        sb.append("<option value='all'>All HTTP Codes</option>");
        List<Map.Entry<Integer, Integer>> allCodeEntries = new ArrayList<>(allByCode.entrySet());
        allCodeEntries.sort((a, b) -> Integer.compare(a.getKey(), b.getKey()));
        for (Map.Entry<Integer, Integer> e : allCodeEntries) {
            sb.append("<option value='").append(e.getKey()).append("'>HTTP ").append(e.getKey()).append(" (").append(e.getValue()).append(")</option>");
        }
        sb.append("</select>");
        sb.append("<select id='pageSize' onchange='resetAndApply()'><option value='50'>50 / page</option><option value='100' selected>100 / page</option><option value='200'>200 / page</option></select>");
        sb.append("<input id='q' type='text' placeholder='Search API / step name / owner / module / method / URL...' oninput='resetAndApply()'>");
        sb.append("<button class='btn secondary' onclick='clearFilters()'>Clear Filters</button>");
        sb.append("<span class='meta' id='visibleCount'></span>");
        sb.append("<div class='pager'>");
        sb.append("<button class='btn' onclick='changePage(-1)'>Prev</button>");
        sb.append("<span class='meta' id='pageInfo'></span>");
        sb.append("<button class='btn' onclick='changePage(1)'>Next</button>");
        sb.append("</div>");
        sb.append("</div>");
        sb.append("<div class='activefilters' id='activeFilters'></div>");
        sb.append("<div class='meta' style='margin:10px 0 8px 0'>Click any row to inspect the case. Click the same selected row again to close it.</div>");

        sb.append("<div class='reportlayout'><div class='tablepane'>");
        sb.append("<div class='tblwrap'><table><thead><tr>");
        sb.append("<th>#</th><th>Manifest</th><th>API</th><th>Module</th><th>Owner</th><th>Test Case</th><th>Method</th><th>Code</th><th>Status</th><th>Expected vs Actual</th>");
        sb.append("</tr></thead><tbody>");

        int idx = 1;
        for (Map<String, Object> row : allRows) {
            int expected = asInt(row.get("expectedCode"));
            int actual = asInt(row.get("actualCode"));
            String status = asStr(row.get("__status"));
            String manifest = asStr(row.get("__manifest"));
            String search = (asStr(row.get("api")) + " " + asStr(row.get("stepApiName")) + " " + asStr(row.get("module")) + " " + asStr(row.get("owner")) + " " + asStr(row.get("url")) + " " + asStr(row.get("method"))).toLowerCase(Locale.ROOT);
            int rowNo = idx++;
            String rowId = "row-" + rowNo;
            String detailId = "detail-" + rowNo;
            String ownerValue = normalizedFilterValue(asStr(row.get("owner")));
            String moduleValue = normalizedFilterValue(asStr(row.get("module")));

            sb.append("<tr id='").append(rowId).append("' class='case-row' onclick=\"toggleRowDetail('").append(detailId).append("','").append(rowId).append("')\" data-detail-id='").append(detailId).append("' data-status='").append(escapeAttr(status)).append("' data-manifest='").append(escapeAttr(manifestBucket(manifest)))
                    .append("' data-owner='").append(escapeAttr(ownerValue)).append("' data-module='").append(escapeAttr(moduleValue))
                    .append("' data-code='").append(actual).append("' data-search='").append(escapeAttr(search)).append("'>");
            sb.append("<td>").append(rowNo).append("</td>");
            sb.append("<td>").append(escape(manifest)).append("</td>");
            sb.append("<td>").append(escape(asStr(row.get("api")))).append("<br><span class='meta'>").append(escape(asStr(row.get("stepApiName")))).append("</span></td>");
            sb.append("<td>").append(escape(asStr(row.get("module")))).append("</td>");
            sb.append("<td>").append(escape(asStr(row.get("owner")))).append("</td>");
            sb.append("<td>").append(escape(asStr(row.get("step")))).append("</td>");
            sb.append("<td>").append(escape(asStr(row.get("method")))).append("</td>");
            sb.append("<td class='code ").append("pass".equals(status) ? "ok" : "fail").append("'>")
                    .append(expected).append(" / ").append(actual).append("</td>");
            sb.append("<td><span class='tag ").append(status).append("'>").append("pass".equals(status) ? "PASS" : "FAIL").append("</span></td>");
            sb.append("<td>").append(renderViewPreview(row)).append("</td>");
            sb.append("</tr>");
        }

        sb.append("</tbody></table></div></div>");
        sb.append("<div id='drawerBackdrop' class='drawer-backdrop' onclick='closeDetail()'></div><aside id='detailDrawer' class='drawer'></aside></div></div></div>");

        sb.append("<script>");
        sb.append("var statusFilter='fail';var currentPage=1;var activeRowId=null;var activeDetailId=null;");
        sb.append("var caseDetails={");
        idx = 1;
        boolean firstDetail = true;
        for (Map<String, Object> row : allRows) {
            int rowNo = idx++;
            String detailId = "detail-" + rowNo;
            StringBuilder detail = new StringBuilder();
            if ("fail".equals(asStr(row.get("__status")))) {
                renderFailureSummary(detail, row, syncGroupSeq);
            } else {
                renderCompactPassSummary(detail, row);
            }
            if (!firstDetail) sb.append(",");
            sb.append(toJsonString(detailId)).append(":").append(toJsonString(detail.toString()));
            firstDetail = false;
        }
        sb.append("};");
        sb.append("function syncStatusButtons(){document.querySelectorAll('.btn[data-filter]').forEach(function(b){b.classList.remove('active');if(b.getAttribute('data-filter')===statusFilter)b.classList.add('active');});}");
        sb.append("function setStatusFilter(btn,status){statusFilter=status;syncStatusButtons();currentPage=1;applyFilters();}");
        sb.append("function setQuickSearch(v){var q=document.getElementById('q');q.value=v||'';currentPage=1;applyFilters();}");
        sb.append("function setOwnerFilter(v){var owner=document.getElementById('ownerFilter');if(owner){owner.value=v||'all';}currentPage=1;applyFilters();}");
        sb.append("function setModuleFilter(v){var module=document.getElementById('moduleFilter');if(module){module.value=v||'all';}currentPage=1;applyFilters();}");
        sb.append("function setCodeFilter(v){var c=document.getElementById('codeFilter');if(c){c.value=v||'all';}currentPage=1;applyFilters();}");
        sb.append("function resetAndApply(){currentPage=1;applyFilters();}");
        sb.append("function changePage(delta){currentPage+=delta;applyFilters();}");
        sb.append("function clearFilters(){statusFilter='fail';document.getElementById('manifestFilter').value='all';document.getElementById('ownerFilter').value='all';document.getElementById('moduleFilter').value='all';document.getElementById('codeFilter').value='all';document.getElementById('pageSize').value='100';document.getElementById('q').value='';currentPage=1;syncStatusButtons();applyFilters();}");
        sb.append("function setActiveFilters(status,manifest,owner,module,code,query){var chips=[];if(status!=='all')chips.push('Status: '+status.toUpperCase());if(manifest!=='all')chips.push('Manifest: '+manifest);if(owner!=='all')chips.push('Owner: '+owner);if(module!=='all')chips.push('Module: '+module);if(code!=='all')chips.push('HTTP: '+code);if(query)chips.push('Search: '+query);var el=document.getElementById('activeFilters');if(!chips.length){el.innerHTML=\"<span class='meta'>Active filters: none</span>\";return;}el.innerHTML=chips.map(function(v){return \"<span class='filterchip'>\"+v+\"</span>\";}).join('');}");
        sb.append("function applyFilters(){var q=(document.getElementById('q').value||'').toLowerCase();var m=document.getElementById('manifestFilter').value;var owner=document.getElementById('ownerFilter').value;var module=document.getElementById('moduleFilter').value;var code=document.getElementById('codeFilter').value;var pageSize=parseInt(document.getElementById('pageSize').value||'100',10);");
        sb.append("var rows=Array.prototype.slice.call(document.querySelectorAll('tr[data-status]'));var filtered=[];");
        sb.append("rows.forEach(function(r){var s=r.getAttribute('data-status');var mm=r.getAttribute('data-manifest');var oo=r.getAttribute('data-owner');var mo=r.getAttribute('data-module');var cc=r.getAttribute('data-code');var txt=r.getAttribute('data-search')||'';");
        sb.append("var okStatus=(statusFilter==='all'||s===statusFilter);var okManifest=(m==='all'||mm===m);var okOwner=(owner==='all'||oo===owner);var okModule=(module==='all'||mo===module);var okCode=(code==='all'||cc===code);var okText=(!q||txt.indexOf(q)>=0);if(okStatus&&okManifest&&okOwner&&okModule&&okCode&&okText) filtered.push(r);r.style.display='none';});");
        sb.append("var total=filtered.length;var totalPages=Math.max(1,Math.ceil(total/pageSize));if(currentPage<1) currentPage=1;if(currentPage>totalPages) currentPage=totalPages;");
        sb.append("var start=(currentPage-1)*pageSize;var end=Math.min(start+pageSize,total);for(var i=start;i<end;i++){filtered[i].style.display='';}");
        sb.append("document.getElementById('visibleCount').textContent='Visible test cases: '+total;");
        sb.append("document.getElementById('pageInfo').textContent='Page '+currentPage+' / '+totalPages;");
        sb.append("setActiveFilters(statusFilter,m,owner,module,code,document.getElementById('q').value||'');");
        sb.append("if(activeRowId){var active=document.getElementById(activeRowId);if(!active||active.style.display==='none'){closeDetail();}}");
        sb.append("}");
        sb.append("function closeDetail(){var drawer=document.getElementById('detailDrawer');var backdrop=document.getElementById('drawerBackdrop');if(activeRowId){var prev=document.getElementById(activeRowId);if(prev)prev.classList.remove('selected');}activeRowId=null;activeDetailId=null;drawer.className='drawer';drawer.innerHTML='';if(backdrop)backdrop.className='drawer-backdrop';document.body.style.overflow='';}");
        sb.append("function openDetail(detailId,rowId){var row=document.getElementById(rowId);var drawer=document.getElementById('detailDrawer');var backdrop=document.getElementById('drawerBackdrop');var html=caseDetails[detailId]||'';if(!row||!html)return;if(activeRowId&&activeRowId!==rowId){var prev=document.getElementById(activeRowId);if(prev)prev.classList.remove('selected');}activeRowId=rowId;activeDetailId=detailId;row.classList.add('selected');if(backdrop)backdrop.className='drawer-backdrop open';drawer.className='drawer open';drawer.innerHTML=html;drawer.scrollTop=0;document.body.style.overflow='hidden';applyDiff();}");
        sb.append("function toggleRowDetail(detailId,rowId){if(activeRowId===rowId){closeDetail();return;}openDetail(detailId,rowId);}");
        sb.append("function copyCode(id){var el=document.getElementById(id);if(!el)return;var txt=el.tagName==='TEXTAREA'?el.value:el.textContent;if(navigator.clipboard&&window.isSecureContext){navigator.clipboard.writeText(txt).catch(function(){});}else{var ta=document.createElement('textarea');ta.value=txt;document.body.appendChild(ta);ta.select();document.execCommand('copy');document.body.removeChild(ta);}}");
        sb.append("function selectCode(id){var el=document.getElementById(id);if(!el)return;if(el.tagName==='TEXTAREA'){el.focus();el.select();el.setSelectionRange(0,el.value.length);}else{var r=document.createRange();r.selectNodeContents(el);var s=window.getSelection();s.removeAllRanges();s.addRange(r);}}");
        sb.append("function escHtml(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}");
        sb.append("function applyDiff(){var groups={};document.querySelectorAll('pre[data-sync-group]').forEach(function(el){var g=el.getAttribute('data-sync-group');if(!groups[g])groups[g]=[];groups[g].push(el);});Object.keys(groups).forEach(function(g){var panes=groups[g];if(panes.length!==2)return;var lA=panes[0].textContent.split('\\n');var lB=panes[1].textContent.split('\\n');var mx=Math.max(lA.length,lB.length);var hA='',hB='';for(var i=0;i<mx;i++){var a=lA[i]!==undefined?lA[i]:'';var b=lB[i]!==undefined?lB[i]:'';var diff=a!==b;var cls=diff?' class=\"diff-line\"':' class=\"code-line\"';hA+='<span'+cls+'>'+escHtml(a)+'</span>';hB+='<span'+cls+'>'+escHtml(b)+'</span>';}panes[0].innerHTML=hA;panes[1].innerHTML=hB;});requestAnimationFrame(function(){document.querySelectorAll('pre[data-sync-group]').forEach(function(pane){var sw=pane.scrollWidth;pane.querySelectorAll('.diff-line,.code-line').forEach(function(s){s.style.minWidth=sw+'px';});});});}");

        sb.append("function syncCodeScroll(source){var group=source.getAttribute('data-sync-group');if(!group||source.dataset.syncing==='1')return;var peers=document.querySelectorAll('.codebox[data-sync-group=\"'+group+'\"]');var maxTop=Math.max(1,source.scrollHeight-source.clientHeight);var ratioTop=source.scrollTop/maxTop;var maxLeft=Math.max(1,source.scrollWidth-source.clientWidth);var ratioLeft=source.scrollLeft/maxLeft;peers.forEach(function(peer){if(peer===source)return;peer.dataset.syncing='1';peer.scrollTop=ratioTop*Math.max(0,peer.scrollHeight-peer.clientHeight);peer.scrollLeft=ratioLeft*Math.max(0,peer.scrollWidth-peer.clientWidth);peer.dataset.syncing='0';});}");
        sb.append("document.addEventListener('keydown',function(e){if(e.key==='Escape')closeDetail();});");
        sb.append("syncStatusButtons();applyFilters();");
        sb.append("</script>");
        sb.append("</div></body></html>");

        Files.writeString(htmlFile, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void kpi(StringBuilder sb, String key, String value, String cls) {
        sb.append("<div class='kpi'><div class='k'>").append(escape(key)).append("</div><div class='v ")
                .append(escape(cls)).append("'>").append(escape(value)).append("</div></div>");
    }

    private static String formatReportDate(Instant instant) {
        return REPORT_DATE.format(instant.atZone(ZoneId.systemDefault()));
    }

    private static void renderClickableTopList(StringBuilder sb, List<Map.Entry<String, Integer>> entries, int limit, String filterType) {
        if (entries.isEmpty()) {
            sb.append("<div class='meta'>No failures.</div>");
            return;
        }
        int n = Math.min(limit, entries.size());
        for (int i = 0; i < n; i++) {
            Map.Entry<String, Integer> e = entries.get(i);
            String value = normalizedFilterValue(e.getKey());
            String fn = "owner".equals(filterType) ? "setOwnerFilter" : "setModuleFilter";
            sb.append("<a class='clickchip' onclick=\"").append(fn).append("('").append(escapeJs(value)).append("');return false;\">")
                    .append(escape(value)).append(" (").append(e.getValue()).append(")</a>");
        }
    }

    private static void details(StringBuilder sb, String title, String body, AtomicInteger syncGroupSeq) {
        String preId = "codebox-" + syncGroupSeq.getAndIncrement();
        sb.append("<details><summary>").append(escape(title)).append("</summary>");
        sb.append("<div class='panetools'><button type='button' class='smallbtn' onclick=\"copyCode('").append(preId).append("')\">Copy</button>");
        sb.append("<button type='button' class='smallbtn' onclick=\"selectCode('").append(preId).append("')\">Select All</button></div>");
        sb.append("<pre id='").append(preId).append("' class='codebox'>")
                .append(escape(body)).append("</pre></details>");
    }

    private static void renderSideBySide(StringBuilder sb, String expectedBody, String actualBody, AtomicInteger syncGroupSeq) {
        int groupId = syncGroupSeq.getAndIncrement();
        String expectedId = "codebox-" + syncGroupSeq.getAndIncrement();
        String actualId = "codebox-" + syncGroupSeq.getAndIncrement();
        sb.append("<div class='split'>");
        renderCodePane(sb, "Expected", prettyJson(expectedBody), expectedId, groupId);
        renderCodePane(sb, "Actual", prettyJson(actualBody), actualId, groupId);
        sb.append("</div>");
    }

    private static void renderCodePane(StringBuilder sb, String title, String body, String id, int groupId) {
        sb.append("<div class='pane'><h4>").append(escape(title)).append("</h4>");
        sb.append("<div class='panetools'><button type='button' class='smallbtn' onclick=\"copyCode('").append(id).append("')\">Copy</button>");
        sb.append("<button type='button' class='smallbtn' onclick=\"selectCode('").append(id).append("')\">Select All</button></div>");
        sb.append("<pre id='").append(id).append("' class='codebox' data-sync-group='").append(groupId)
                .append("' onscroll='syncCodeScroll(this)'>")
                .append(escape(body)).append("</pre></div>");
    }

    private static void renderFailureSummary(StringBuilder sb, Map<String, Object> row, AtomicInteger syncGroupSeq) {
        String status = asStr(row.get("__status"));
        String manifest = asStr(row.get("__manifest"));
        List<Map<String, Object>> comparison = castComparison(row.get("comparison"));
        boolean comparisonFailed = Boolean.TRUE.equals(row.get("comparisonFailed"));
        String preview = comparisonFailed && !comparison.isEmpty()
                ? asStr(comparison.get(0).get("path"))
                : ("pass".equals(status) ? "No mismatches" : "Code mismatch");

        sb.append("<div class='drawerhead'>");
        sb.append("<div>");
        sb.append("<h2>").append(escape(asStr(row.get("api")))).append("</h2>");
        sb.append("<div class='drawermeta'>");
        sb.append("<span class='badge ").append(escape(status)).append("'>").append("pass".equals(status) ? "PASS" : "FAIL").append("</span>");
        sb.append("<span class='pill'>").append(escape(manifestBucket(manifest))).append("</span>");
        sb.append("<span class='pill'>Step ").append(escape(asStr(row.get("step")))).append("</span>");
        if (!asStr(row.get("stepApiName")).isBlank()) {
            sb.append("<span class='pill'>").append(escape(asStr(row.get("stepApiName")))).append("</span>");
        }
        if (!asStr(row.get("module")).isBlank()) {
            sb.append("<span class='pill'>").append(escape(asStr(row.get("module")))).append("</span>");
        }
        if (!asStr(row.get("owner")).isBlank()) {
            sb.append("<span class='pill'>").append(escape(asStr(row.get("owner")))).append("</span>");
        }
        sb.append("</div></div>");
        sb.append("<button class='drawerclose' onclick='closeDetail()'>Close</button>");
        sb.append("</div>");
        sb.append("<div class='drawerbody'><div class='detailstack'><div class='detailgrid'>");
        sb.append("<div class='detailpanel'><h3>").append("pass".equals(status) ? "Summary" : "Failure Summary").append("</h3>");
        sb.append("<div class='summarybox'>");
        sb.append("<div class='summaryrow'>");
        sb.append("<span class='pill'>Expected ").append(asInt(row.get("expectedCode"))).append("</span>");
        sb.append("<span class='pill'>Actual ").append(asInt(row.get("actualCode"))).append("</span>");
        if (comparisonFailed) {
            sb.append("<span class='pill'>Mismatches ").append(comparison.size()).append("</span>");
        }
        sb.append("</div>");
        sb.append("<div class='rowpreview'>Preview: ").append(escape(shorten(preview, 140))).append("</div>");

        if (comparisonFailed && !comparison.isEmpty()) {
            sb.append("<details class='nestedetails'><summary>Mismatch Preview</summary>");
            sb.append("<ol class='mismatchlist'>");
            int shown = Math.min(5, comparison.size());
            for (int i = 0; i < shown; i++) {
                Map<String, Object> m = comparison.get(i);
                sb.append("<li><span class='mismatchpath'>").append(escape(asStr(m.get("path")))).append("</span>")
                        .append("<br><span class='muted'>Expected:</span> ").append(escape(shorten(asStr(m.get("expected")), 160)))
                        .append("<br><span class='muted'>Actual:</span> ").append(escape(shorten(asStr(m.get("actual")), 160)))
                        .append("</li>");
            }
            if (comparison.size() > shown) {
                sb.append("<li class='muted'>").append(comparison.size() - shown).append(" more mismatches not shown here</li>");
            }
            sb.append("</ol></details>");

            if (comparison.size() > 5) {
                sb.append("<details class='nestedetails'><summary>All Mismatches (").append(comparison.size()).append(")</summary>");
                sb.append("<ol class='mismatchlist'>");
                for (Map<String, Object> m : comparison) {
                    sb.append("<li><span class='mismatchpath'>").append(escape(asStr(m.get("path")))).append("</span>")
                            .append("<br><span class='muted'>Expected:</span> ").append(escape(shorten(asStr(m.get("expected")), 240)))
                            .append("<br><span class='muted'>Actual:</span> ").append(escape(shorten(asStr(m.get("actual")), 240)))
                            .append("</li>");
                }
                sb.append("</ol></details>");
            }
        }
        renderAssertionResults(sb, row);
        sb.append("</div></div>");
        sb.append("<div class='detailpanel'><h3>Artifacts</h3>");
        sb.append("<details class='nestedetails'><summary>View JSON</summary>");
        renderSideBySide(sb, asStr(row.get("expected")), asStr(row.get("actual")), syncGroupSeq);
        sb.append("</details>");
        details(sb, "Payload", prettyJson(asStr(row.get("payload"))), syncGroupSeq);
        sb.append("<details class='nestedetails'><summary>URL</summary><pre>").append(escape(asStr(row.get("url")))).append("</pre></details>");
        sb.append("</div>");
        sb.append("</div></div></div>");
    }

    private static void renderCompactPassSummary(StringBuilder sb, Map<String, Object> row) {
        String manifest = asStr(row.get("__manifest"));
        sb.append("<div class='drawerhead'>");
        sb.append("<div>");
        sb.append("<h2>").append(escape(asStr(row.get("api")))).append("</h2>");
        sb.append("<div class='drawermeta'>");
        sb.append("<span class='badge pass'>PASS</span>");
        sb.append("<span class='pill'>").append(escape(manifestBucket(manifest))).append("</span>");
        sb.append("<span class='pill'>Step ").append(escape(asStr(row.get("step")))).append("</span>");
        if (!asStr(row.get("stepApiName")).isBlank()) {
            sb.append("<span class='pill'>").append(escape(asStr(row.get("stepApiName")))).append("</span>");
        }
        if (!asStr(row.get("module")).isBlank()) {
            sb.append("<span class='pill'>").append(escape(asStr(row.get("module")))).append("</span>");
        }
        if (!asStr(row.get("owner")).isBlank()) {
            sb.append("<span class='pill'>").append(escape(asStr(row.get("owner")))).append("</span>");
        }
        sb.append("</div></div>");
        sb.append("<button class='drawerclose' onclick='closeDetail()'>Close</button>");
        sb.append("</div>");
        sb.append("<div class='drawerbody'><div class='detailstack'>");
        sb.append("<div class='detailpanel'><h3>Summary</h3>");
        sb.append("<div class='summarybox'>");
        sb.append("<div class='summaryrow'><span class='pill'>Expected ").append(asInt(row.get("expectedCode"))).append("</span>");
        sb.append("<span class='pill'>Actual ").append(asInt(row.get("actualCode"))).append("</span></div>");
        sb.append("<div class='rowpreview'>Passed successfully. Heavy payload/JSON is omitted here to keep the report fast.</div>");
        renderAssertionResults(sb, row);
        sb.append("</div></div>");
        sb.append("<div class='detailpanel'><h3>URL</h3><pre>").append(escape(asStr(row.get("url")))).append("</pre></div>");
        sb.append("</div></div>");
    }

    private static String renderViewPreview(Map<String, Object> row) {
        String status = asStr(row.get("__status"));
        if ("pass".equals(status)) {
            List<Map<String, Object>> assertions = castAssertionResults(row.get("assertionResults"));
            if (!assertions.isEmpty()) {
                return "<span class='mismatchpath'>Assertions " + assertions.size() + "</span>";
            }
            return "<span class='muted'>Open details</span>";
        }
        List<Map<String, Object>> comparison = castComparison(row.get("comparison"));
        if (comparison.isEmpty()) {
            List<Map<String, Object>> assertions = castAssertionResults(row.get("assertionResults"));
            if (!assertions.isEmpty()) {
                return "<span class='mismatchpath'>" + escape(shorten(asStr(assertions.get(0).get("assertionName")), 80)) + "</span>";
            }
            return "<span class='muted'>Open details</span>";
        }
        return "<span class='mismatchpath'>" + escape(shorten(asStr(comparison.get(0).get("path")), 80)) + "</span>";
    }

    private static void renderAssertionResults(StringBuilder sb, Map<String, Object> row) {
        List<Map<String, Object>> assertions = castAssertionResults(row.get("assertionResults"));
        if (assertions.isEmpty()) {
            return;
        }
        sb.append("<details class='nestedetails' open><summary>Assertion Results (").append(assertions.size()).append(")</summary>");
        sb.append("<div style='overflow:auto'><table class='assertiontable'><thead><tr>");
        sb.append("<th>Status</th><th>Assertion</th><th>Type</th><th>Actual Path</th><th>Actual Value</th><th>Expected Path</th><th>Expected Value</th><th>Message</th>");
        sb.append("</tr></thead><tbody>");
        for (Map<String, Object> assertion : assertions) {
            String status = asStr(assertion.get("status"));
            String statusClass = "PASS".equalsIgnoreCase(status) ? "pass" : "fail";
            sb.append("<tr>");
            sb.append("<td class='").append(statusClass).append("'>").append(escape(status)).append("</td>");
            sb.append("<td>").append(escape(asStr(assertion.get("assertionName")))).append("</td>");
            sb.append("<td>").append(escape(asStr(assertion.get("type")))).append("</td>");
            sb.append("<td class='mono'>").append(escape(asStr(assertion.get("actualPath")))).append("</td>");
            sb.append("<td class='mono'>").append(escape(shorten(asStr(assertion.get("resolvedActual")), 160))).append("</td>");
            sb.append("<td class='mono'>").append(escape(asStr(assertion.get("expectedPath")))).append("</td>");
            sb.append("<td class='mono'>").append(escape(shorten(asStr(assertion.get("resolvedExpected")), 160))).append("</td>");
            sb.append("<td>").append(escape(shorten(asStr(assertion.get("message")), 220))).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div></details>");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castComparison(Object raw) {
        if (!(raw instanceof List<?> list)) return Collections.emptyList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?>) {
                out.add((Map<String, Object>) item);
            }
        }
        return out;
    }

    private static List<Map<String, Object>> castAssertionResults(Object raw) {
        return castComparison(raw);
    }

    private static String shorten(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        return value.substring(0, Math.max(0, max - 3)) + "...";
    }

    private static String prettyJson(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";
        try {
            JsonNode node = JSON.readTree(trimmed);
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return raw;
        }
    }

    private static List<Map.Entry<String, Integer>> sortByCountDesc(Map<String, Integer> in) {
        List<Map.Entry<String, Integer>> out = new ArrayList<>(in.entrySet());
        out.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return out;
    }

    private static List<String> distinctValues(List<Map<String, Object>> rows, String key) {
        Set<String> values = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Map<String, Object> row : rows) {
            values.add(normalizedFilterValue(asStr(row.get(key))));
        }
        return new ArrayList<>(values);
    }

    private static List<String> distinctManifestBuckets(List<Map<String, Object>> rows) {
        Set<String> values = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Map<String, Object> row : rows) {
            values.add(manifestBucket(asStr(row.get("__manifest"))));
        }
        return new ArrayList<>(values);
    }

    private static String normalizedFilterValue(String value) {
        return value == null || value.isBlank() ? "NA" : value.trim();
    }

    private static int asInt(Object o) {
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return -1;
        }
    }

    private static String asStr(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escapeAttr(String s) {
        return escape(s).replace("'", "&#39;");
    }

    private static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String toJsonString(String value) {
        try {
            return JSON.writeValueAsString(value == null ? "" : value);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private static int manifestOrder(String manifest) {
        if (manifest == null) return 99;
        if (manifest.contains("_A")) return 1;
        if (manifest.contains("_B")) return 2;
        if (manifest.contains("_C")) return 3;
        return 99;
    }

    private static String manifestBucket(String manifest) {
        if (manifest == null) return "OTHER";
        if (manifest.contains("_A")) return "A";
        if (manifest.contains("_B")) return "B";
        if (manifest.contains("_C")) return "C";
        return "OTHER";
    }
}
