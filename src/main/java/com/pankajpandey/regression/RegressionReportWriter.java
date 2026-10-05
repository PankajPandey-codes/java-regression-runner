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

final class RegressionReportWriter {
    private RegressionReportWriter() {}

    static Path write(Path projectRoot, String runFolder) throws IOException {
        if (runFolder == null || runFolder.isBlank()) {
            throw new IllegalArgumentException("runFolder is required");
        }
        Path runDir = PathResolver.runnerRoot(projectRoot)
                .resolve("results")
                .resolve(runFolder)
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(runDir);
        Path out = runDir.resolve("regression-report.html");
        Files.writeString(out, html(runDir), StandardCharsets.UTF_8);
        return out;
    }

    private static String html(Path runDir) {
        String execution = iframe("executionFrame", readSection(runDir.resolve("execute-summary.html"), "Execution Summary has not been generated yet.", ""));
        String analysis = iframe("analysisFrame", readSection(runDir.resolve("analysis/analysis-report.html"), "Analysis Summary has not been generated yet.", "analysis/"));
        String http = iframe("httpFrame", readSection(runDir.resolve("analysis/index_http.html"), "HTTP Code Diff has not been generated yet.", "analysis/"));
        String payload = iframe("payloadFrame", readSection(runDir.resolve("analysis/index_diff.html"), "Payload Diff has not been generated yet.", "analysis/"));
        return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Regression Report</title>
                <style>
                :root{--rail:#1f2421;--rail2:#28342e;--ink:#f8f5ed;--muted:#d6d0c6;--active:#fffdf8;--activeText:#24473c;--line:rgba(255,255,255,.14)}
                *{box-sizing:border-box}body{margin:0;font-family:Inter,system-ui,-apple-system,Segoe UI,sans-serif}.app{height:100vh;display:grid;grid-template-columns:250px minmax(0,1fr);overflow:hidden}.rail{background:linear-gradient(180deg,var(--rail),var(--rail2));color:var(--ink);padding:18px 14px;display:flex;flex-direction:column;gap:16px}.brand{padding:4px 6px 10px}.brand h1{margin:0;font-size:22px;line-height:1.1}.nav{display:grid;gap:8px}.nav button{width:100%;border:1px solid var(--line);background:rgba(255,255,255,.06);color:var(--ink);border-radius:14px;padding:12px 13px;text-align:left;font-weight:850;cursor:pointer}.nav button.active{background:var(--active);color:var(--activeText);border-color:var(--active)}.main{min-width:0;min-height:0}.pane{display:none;width:100%;height:100vh}.pane.active{display:block}iframe{width:100%;height:100%;border:0;background:#fff}.mobile{display:none;padding:8px;gap:8px;background:var(--rail);overflow:auto}.mobile button{white-space:nowrap;border:1px solid var(--line);background:rgba(255,255,255,.06);color:var(--ink);border-radius:999px;padding:8px 10px;font-weight:800}.mobile button.active{background:var(--active);color:var(--activeText)}@media(max-width:900px){.app{display:block}.rail{display:none}.mobile{display:flex}.pane{height:calc(100vh - 48px)}}
                </style>
                </head>
                <body>
                <div class="mobile">
                  <button class="active" data-tab="execution">Execution Summary</button>
                  <button data-tab="analysis">Analysis Summary</button>
                  <button data-tab="http">HTTP Code Diff</button>
                  <button data-tab="payload">Payload Diff</button>
                </div>
                <div class="app">
                  <aside class="rail">
                    <div class="brand"><h1>Regression<br>Report</h1></div>
                    <nav class="nav" aria-label="Report sections">
                      <button class="active" data-tab="execution">Execution Summary</button>
                      <button data-tab="analysis">Analysis Summary</button>
                      <button data-tab="http">HTTP Code Diff</button>
                      <button data-tab="payload">Payload Diff</button>
                    </nav>
                  </aside>
                  <main class="main">
                    <section id="execution" class="pane active">__EXECUTION_REPORT__</section>
                    <section id="analysis" class="pane">__ANALYSIS_REPORT__</section>
                    <section id="http" class="pane">__HTTP_REPORT__</section>
                    <section id="payload" class="pane">__PAYLOAD_REPORT__</section>
                  </main>
                </div>
                <script>
                const controls=[...document.querySelectorAll('[data-tab]')];
                function show(tab){
                  document.querySelectorAll('.pane').forEach(p=>p.classList.toggle('active',p.id===tab));
                  controls.forEach(c=>c.classList.toggle('active',c.dataset.tab===tab));
                  if(location.hash.slice(1)!==tab) history.replaceState(null,'','#'+tab);
                }
                controls.forEach(c=>c.addEventListener('click',()=>show(c.dataset.tab)));
                const initial=location.hash.slice(1); if(document.getElementById(initial)) show(initial);
                window.addEventListener('message',event=>{
                  const tab=event.data&&event.data.regressionReportTab;
                  if(tab&&document.getElementById(tab)) show(tab);
                });
                </script>
                </body>
                </html>
                """
                .replace("__EXECUTION_REPORT__", execution)
                .replace("__ANALYSIS_REPORT__", analysis)
                .replace("__HTTP_REPORT__", http)
                .replace("__PAYLOAD_REPORT__", payload);
    }

    private static String readSection(Path path, String missingMessage, String baseHref) {
        if (Files.exists(path)) {
            try {
                return withNavigationBridge(withBaseHref(Files.readString(path, StandardCharsets.UTF_8), baseHref));
            } catch (IOException ignored) {
                return missingHtml(missingMessage);
            }
        }
        return missingHtml(missingMessage);
    }

    private static String withBaseHref(String html, String baseHref) {
        if (baseHref == null || baseHref.isBlank()) {
            return html;
        }
        String base = "<base href=\"" + escapeAttribute(baseHref) + "\">";
        String lower = html.toLowerCase();
        int headStart = lower.indexOf("<head>");
        if (headStart >= 0) {
            return html.substring(0, headStart + "<head>".length()) + base + html.substring(headStart + "<head>".length());
        }
        int headStartWithAttributes = lower.indexOf("<head ");
        if (headStartWithAttributes >= 0) {
            int headEnd = html.indexOf('>', headStartWithAttributes);
            if (headEnd >= 0) {
                return html.substring(0, headEnd + 1) + base + html.substring(headEnd + 1);
            }
        }
        return base + html;
    }

    private static String withNavigationBridge(String html) {
        String bridge = """
                <script>
                document.addEventListener('click',function(event){
                  const link=event.target.closest&&event.target.closest('a[href]');
                  if(!link)return;
                  const name=(link.getAttribute('href')||'').split('#')[0].split('/').pop();
                  const tabs={'execute-summary.html':'execution','analysis-report.html':'analysis','index_http.html':'http','index_diff.html':'payload'};
                  if(tabs[name]){event.preventDefault();parent.postMessage({regressionReportTab:tabs[name]},'*');}
                });
                </script>
                """;
        int bodyEnd = html.toLowerCase().lastIndexOf("</body>");
        if (bodyEnd >= 0) {
            return html.substring(0, bodyEnd) + bridge + html.substring(bodyEnd);
        }
        return html;
    }

    private static String missingHtml(String message) {
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <style>body{margin:0;display:grid;place-items:center;min-height:100vh;font-family:Inter,system-ui,sans-serif;background:#f8fafc;color:#334155}.card{border:1px solid #cbd5e1;border-radius:16px;background:white;padding:24px;box-shadow:0 18px 50px rgba(15,23,42,.08)}</style>
                </head><body><div class="card">%s</div></body></html>
                """.formatted(escapeText(message));
    }

    private static String iframe(String title, String srcdoc) {
        return "<iframe title=\"" + escapeAttribute(title) + "\" srcdoc=\"" + escapeAttribute(srcdoc) + "\"></iframe>";
    }

    private static String escapeText(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String escapeAttribute(String value) {
        return escapeText(value).replace("\"", "&quot;");
    }
}
