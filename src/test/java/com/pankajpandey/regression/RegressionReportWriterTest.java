package com.pankajpandey.regression;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegressionReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesSelfContainedRegressionReportWithEmbeddedSections() throws Exception {
        Path projectRoot = tempDir.resolve("repo");
        Path runDir = projectRoot.resolve("java-regression-runner/results/17-07-2026_1015AM");
        Files.createDirectories(runDir.resolve("analysis"));
        Files.writeString(runDir.resolve("execute-summary.html"), "<html>execution</html>");
        Files.writeString(runDir.resolve("analysis/analysis-report.html"), "<html>analysis</html>");
        Files.writeString(runDir.resolve("analysis/index_http.html"), "<html>http</html>");
        Files.writeString(runDir.resolve("analysis/index_diff.html"), "<html>payload</html>");

        Path written = RegressionReportWriter.write(projectRoot, "17-07-2026_1015AM");

        assertEquals(runDir.resolve("regression-report.html").toAbsolutePath().normalize(), written);
        String html = Files.readString(written);
        assertTrue(html.contains("Execution Summary"), html);
        assertTrue(html.contains("Analysis Summary"), html);
        assertTrue(html.contains("HTTP Code Diff"), html);
        assertTrue(html.contains("Payload Diff"), html);
        assertTrue(html.contains("srcdoc=\"&lt;html&gt;execution&lt;/html&gt;\""), html);
        assertTrue(html.contains("&lt;html&gt;analysis&lt;/html&gt;"), html);
        assertTrue(html.contains("&lt;html&gt;http&lt;/html&gt;"), html);
        assertTrue(html.contains("&lt;html&gt;payload&lt;/html&gt;"), html);
        assertFalse(html.contains("src=\"execute-summary.html\""), html);
        assertFalse(html.contains("src=\"analysis/analysis-report.html\""), html);
        assertFalse(html.contains("Execution summary opens by default"), html);
        assertFalse(html.contains("Navigation only"), html);
        assertTrue(html.contains("id=\"execution\" class=\"pane active\""), html);
        assertTrue(html.contains("location.hash.slice(1)"), html);
    }

    @Test
    void embedsAnalysisSectionsWithAnalysisBaseHrefForRelativeDrillDownLinks() throws Exception {
        Path projectRoot = tempDir.resolve("repo");
        Path runDir = projectRoot.resolve("java-regression-runner/results/17-07-2026_1015AM");
        Files.createDirectories(runDir.resolve("analysis"));
        Files.writeString(runDir.resolve("execute-summary.html"), "<html><head></head><body>execution</body></html>");
        Files.writeString(runDir.resolve("analysis/analysis-report.html"), "<html><head></head><body><a href=\"index_diff.html\">diff</a></body></html>");
        Files.writeString(runDir.resolve("analysis/index_http.html"), "<html><head></head><body><a href=\"http_cases/case.html\">case</a></body></html>");
        Files.writeString(runDir.resolve("analysis/index_diff.html"), "<html><head></head><body><a href=\"diff_cases/case.html\">case</a></body></html>");

        Path written = RegressionReportWriter.write(projectRoot, "17-07-2026_1015AM");

        String html = Files.readString(written);
        assertTrue(html.contains("&lt;base href=&quot;analysis/&quot;&gt;"), html);
        assertTrue(html.contains("diff_cases/case.html"), html);
        assertTrue(html.contains("http_cases/case.html"), html);
        assertFalse(html.contains("&lt;base href=&quot;./&quot;&gt;"), html);
    }

    @Test
    void writesSelfContainedReportEvenWhenAnalysisFilesAreNotPresentYet() throws Exception {
        Path projectRoot = tempDir.resolve("repo");
        Path runDir = projectRoot.resolve("java-regression-runner/results/17-07-2026_1015AM");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("execute-summary.html"), "<html>execution</html>");

        Path written = RegressionReportWriter.write(projectRoot, "17-07-2026_1015AM");

        String html = Files.readString(written);
        assertTrue(html.contains("srcdoc=\"&lt;html&gt;execution&lt;/html&gt;\""), html);
        assertTrue(html.contains("Analysis Summary has not been generated yet."), html);
        assertFalse(html.contains("src=\"execute-summary.html\""), html);
        assertFalse(html.contains("src=\"analysis/analysis-report.html\""), html);
    }
}
