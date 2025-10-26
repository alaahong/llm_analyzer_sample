/* Java 21 PR Targeted Test Analyzer with OpenRouter suggestions and rule-based fallback.
 * Spring Boot enhanced mapping:
 *  - From changed src/main/java FQN -> find tests by:
 *      * Heuristic name mapping: FooTest/TestFoo/FooTests
 *      * Same-package test discovery under src/test/java/<pkgDir>/**
 *      * Reference scanning in tests: import FQN, FQN.class, new <ClassName>(...),
 *        @WebMvcTest(<Class>.class), @MockBean(<Class>.class), @Autowired <ClassName>
 *  - De-duplicate candidate test files and class names.
 *  - Only show focused failure diagnostics when tests fail.
 *
 * Optional:
 *  - OPENROUTER_API_KEY + OPENROUTER_MODEL for AI suggestions; otherwise rule-based suggestions.
 *  - FAIL_ON_TEST_FAILURE=true to fail the job if selected tests fail.
 */
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

public class PrChangeTestAnalyzer {
    private static final int BODY_MAX_CHARS = 60_000;
    private static final int LOG_MAX_CHARS = 120_000;
    private static final int HIGHLIGHT_MAX_LINES = parseIntSafe(System.getenv().getOrDefault("ANALYZER_MAX_HIGHLIGHTS", "200"), 200);

    public static void main(String[] args) {
        int intendedExit = 0;
        try {
            String repo = requireEnv("REPO");
            String prNum = requireEnv("PULL_NUMBER");
            String baseSha = getenvOr("BASE_SHA", "");
            String headSha = getenvOr("HEAD_SHA", "");
            String serverUrl = getenvOr("SERVER_URL", "https://github.com");
            String apiUrl = getenvOr("API_URL", "https://api.github.com");
            String workflowName = getenvOr("WORKFLOW_NAME", "PR Targeted Test Analyzer");

            String orKey = getenvOr("OPENROUTER_API_KEY", "").trim();
            String orModel = getenvOr("OPENROUTER_MODEL", "meta-llama/llama-3.3-8b-instruct:free").trim();
            int llmMax = parseIntSafe(getenvOr("LLM_MAX_TOKENS", "800"), 800);
            boolean failOnTestFailure = "true".equalsIgnoreCase(getenvOr("FAIL_ON_TEST_FAILURE", "true"));

            // 1) Gather changed files in PR
            List<ChangedFile> changed = listChangedFiles(repo, prNum);
            String changedFilesBlock = changed.isEmpty()
                    ? "(no files reported by the API)"
                    : changed.stream().map(cf -> "- " + cf.status + " " + cf.filename).collect(Collectors.joining("\n"));

            // 2) Detect project type and build enhanced Spring Boot test plan
            ProjectType projectType = detectProjectType();
            TestPlan testPlan = buildSpringBootEnhancedTestPlan(changed, projectType);

            // 3) Execute selected tests
            TestResult testResult;
            if (!testPlan.testClasses.isEmpty()) {
                if (projectType == ProjectType.MAVEN) {
                    testResult = runMavenTests(testPlan.testClasses);
                } else if (projectType == ProjectType.GRADLE) {
                    testResult = runGradleTests(testPlan.testClasses);
                } else {
                    testResult = TestResult.noProject("No supported build tool detected (no pom.xml or gradlew).");
                }
            } else {
                testResult = TestResult.noTests("No targeted unit tests matched mapping. Skipping selective run.");
            }

            if (testResult.executed && testResult.exitCode != 0 && failOnTestFailure) {
                intendedExit = 1; // defer failing until after commenting
            }

            // 4) Failure diagnostics only when tests fail
            String failureDiagnostics = "";
            if (testResult.executed && testResult.exitCode != 0) {
                failureDiagnostics = collectFailureDiagnostics();
                if (isBlank(failureDiagnostics)) {
                    String reportsTail = readTestReportsTail();
                    String combined = trimTo(testResult.log, 40_000) + "\n" + reportsTail;
                    failureDiagnostics = extractErrorHighlights(combined, HIGHLIGHT_MAX_LINES);
                }
            }

            // 5) Summary + suggestions
            String reportsTailForSummary = readTestReportsTail();
            String summary = summarizeResults(testResult, reportsTailForSummary);

            String llmAnalysis;
            String llmContext = buildLLMPrompt(repo, prNum, baseSha, headSha, changed, testPlan, summary, failureDiagnostics);
            if (!orKey.isBlank()) {
                llmAnalysis = analyzeWithOpenRouter(orKey, orModel, llmContext, llmMax);
            } else {
                llmAnalysis = ruleBasedSuggestions(failureDiagnostics);
            }

            // 6) PR comment
            String prUrl = serverUrl + "/" + repo + "/pull/" + prNum;
            StringBuilder body = new StringBuilder();
            body.append("🤖 PR Targeted Test Analyzer (Spring Boot enhanced)\n\n");
            body.append("- PR: ").append(prUrl).append("\n");
            body.append("- Build tool: ").append(projectType).append("\n");
            body.append("- Selected tests (enhanced mapping): ").append(testPlan.testClasses.isEmpty() ? "(none)" : testPlan.testClasses).append("\n");
            body.append("- Test command: ").append(testResult.command).append("\n");
            body.append("- Test exit code: ").append(testResult.executed ? testResult.exitCode : "(n/a)").append("\n");
            if (failOnTestFailure) body.append("- Fail-on-test-failure: enabled\n");
            body.append("\n");

            body.append("Changed files:\n```\n").append(changedFilesBlock).append("\n```\n\n");
            if (!testPlan.candidateTestsListing.isBlank()) {
                body.append("Candidate test files (mapped and existing, de-duplicated):\n```\n")
                        .append(testPlan.candidateTestsListing).append("\n```\n\n");
            }

            body.append("Test summary:\n```\n").append(summary).append("\n```\n\n");

            if (testResult.executed && testResult.exitCode != 0 && !isBlank(failureDiagnostics)) {
                body.append("Failure diagnostics (focused snippets):\n```txt\n")
                        .append(failureDiagnostics).append("\n```\n\n");
            }

            body.append("Analysis and suggestions:\n")
                    .append(llmAnalysis).append("\n");

            String finalBody = body.toString();
            if (finalBody.length() > BODY_MAX_CHARS) {
                finalBody = finalBody.substring(0, BODY_MAX_CHARS) + "\n\n…(truncated)…";
            }

            Path tmp = Files.createTempFile("pr-test-analyzer-", ".md");
            Files.writeString(tmp, finalBody, StandardCharsets.UTF_8);
            postPrComment(repo, prNum, tmp);

            if (intendedExit != 0) System.exit(intendedExit);
            System.out.println("Posted PR analysis and test results.");
        } catch (Exception e) {
            System.err.println("Failed to run PR analyzer: " + e);
            System.exit(0);
        }
    }

    // ========= Data models =========
    enum ProjectType { MAVEN, GRADLE, UNKNOWN }
    static class ChangedFile { final String filename; final String status; ChangedFile(String f, String s){ filename=f; status=s; } }
    static class TestPlan {
        final ProjectType projectType;
        final List<String> testClasses;          // simple class names to pass to runner
        final String candidateTestsListing;      // pretty listing (de-duplicated)
        TestPlan(ProjectType t, List<String> c, String l){ projectType=t; testClasses=c; candidateTestsListing=l==null?"":l; }
    }
    static class TestResult {
        final boolean executed; final int exitCode; final String command; final String log; final String tool;
        TestResult(boolean ex, int ec, String cmd, String lg, String tl){ executed=ex; exitCode=ec; command=cmd; log=lg; tool=tl; }
        static TestResult noTests(String msg){ return new TestResult(false,0,"(no tests executed)",msg,"(none)"); }
        static TestResult noProject(String msg){ return new TestResult(false,0,"(no project detected)",msg,"(none)"); }
    }

    // ========= Step 1: changed files =========
    private static List<ChangedFile> listChangedFiles(String repo, String prNum) throws IOException, InterruptedException {
        List<ChangedFile> out = new ArrayList<>();
        int page = 1;
        while (true) {
            String json = ghApi(String.format("repos/%s/pulls/%s/files?per_page=100&page=%d", repo, prNum, page));
            Pattern p = Pattern.compile("\"filename\"\\s*:\\s*\"([^\"]+)\".*?\"status\"\\s*:\\s*\"([^\"]+)\"", Pattern.DOTALL);
            Matcher m = p.matcher(json);
            boolean any = false;
            while (m.find()) { any = true; out.add(new ChangedFile(m.group(1), m.group(2))); }
            if (!any || json.length() < 10 || !json.contains("\"filename\"")) break;
            page++;
        }
        return out;
    }

    // ========= Step 2: enhanced Spring Boot mapping =========
    private static ProjectType detectProjectType() {
        if (Files.exists(Path.of("pom.xml"))) return ProjectType.MAVEN;
        if (Files.exists(Path.of("gradlew"))) return ProjectType.GRADLE;
        return ProjectType.UNKNOWN;
    }

    private static TestPlan buildSpringBootEnhancedTestPlan(List<ChangedFile> changed, ProjectType type) throws IOException, InterruptedException {
        if (type == ProjectType.UNKNOWN) return new TestPlan(type, List.of(), "");

        LinkedHashSet<String> listingPaths = new LinkedHashSet<>();   // unique test file paths
        LinkedHashSet<String> simpleNames = new LinkedHashSet<>();    // unique simple class names

        for (ChangedFile cf : changed) {
            if (!cf.filename.endsWith(".java")) continue;

            // 2.1 If test file changed, include it directly
            if (cf.filename.startsWith("src/test/java/")) {
                listingPaths.add(cf.filename);
                addSimpleNameFromTestPath(simpleNames, cf.filename);
                continue;
            }

            // 2.2 If main code changed, build FQN and mapping
            if (cf.filename.startsWith("src/main/java/")) {
                String rel = cf.filename.substring("src/main/java/".length());
                if (!rel.endsWith(".java")) continue;
                String relNoExt = rel.substring(0, rel.length() - 5);
                String leaf = relNoExt.contains("/") ? relNoExt.substring(relNoExt.lastIndexOf('/') + 1) : relNoExt;
                String pkgDir = relNoExt.contains("/") ? relNoExt.substring(0, relNoExt.lastIndexOf('/')) : "";
                String fqn = relNoExt.replace('/', '.');

                // A) Heuristic test names
                List<String> heuristics = List.of(
                        "src/test/java/" + pkgDir + "/" + leaf + "Test.java",
                        "src/test/java/" + pkgDir + "/" + "Test" + leaf + ".java",
                        "src/test/java/" + pkgDir + "/" + leaf + "Tests.java"
                );
                for (String h : heuristics) {
                    if (Files.exists(Path.of(h))) {
                        listingPaths.add(h);
                        addSimpleNameFromTestPath(simpleNames, h);
                    }
                }

                // B) Same-package tests (include subpackages) for Spring slices/integration
                addAllTestsUnderPackage(listingPaths, simpleNames, pkgDir);

                // C) Reference scanning in test sources (import/FQN.class/new/@WebMvcTest/@MockBean/@Autowired)
                addRefScannedTests(listingPaths, simpleNames, fqn, leaf);
            }
        }

        String listing = listingPaths.stream().collect(Collectors.joining("\n"));
        return new TestPlan(ProjectType.MAVEN, new ArrayList<>(simpleNames), listing);
    }

    private static void addSimpleNameFromTestPath(Set<String> simpleNames, String testPath) {
        String leaf = testPath.substring(testPath.lastIndexOf('/') + 1);
        if (leaf.endsWith(".java")) {
            simpleNames.add(leaf.substring(0, leaf.length() - 5));
        }
    }

    private static void addAllTestsUnderPackage(Set<String> listingPaths, Set<String> simpleNames, String pkgDir) throws IOException {
        // src/test/java/<pkgDir>/**  where filename matches *Test.java or *Tests.java
        Path root = Path.of("src", "test", "java").resolve(pkgDir);
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String s = p.toString().replace('\\', '/');
                if (s.endsWith("Test.java") || s.endsWith("Tests.java")) {
                    listingPaths.add(s);
                    addSimpleNameFromTestPath(simpleNames, s);
                }
            }
        }
    }

    private static void addRefScannedTests(Set<String> listingPaths, Set<String> simpleNames, String fqn, String leaf) throws IOException, InterruptedException {
        // Build grep patterns
        // - import <fqn>
        // - <fqn>.class
        // - @WebMvcTest(<leaf>.class) / @MockBean(<leaf>.class)
        // - @Autowired <leaf>
        // - new <leaf>(
        String pattern = String.join("|", List.of(
                Pattern.quote("import " + fqn),
                Pattern.quote(fqn + ".class"),
                "@WebMvcTest\\s*\\(.*\\b" + Pattern.quote(leaf) + "\\s*\\.\\s*class\\b.*\\)",
                "@MockBean\\s*\\(.*\\b" + Pattern.quote(leaf) + "\\s*\\.\\s*class\\b.*\\)",
                "@Autowired\\s+[^;\\n]*\\b" + Pattern.quote(leaf) + "\\b",
                "\\bnew\\s+" + Pattern.quote(leaf) + "\\s*\\("
        ));

        String cmd = "bash -lc " + shellQuote(
                "set -o pipefail; " +
                        "if command -v grep >/dev/null 2>&1; then " +
                        "grep -RIl --include='*.java' -E '" + pattern + "' src/test/java || true; " +
                        "fi"
        );
        String out = runProcess(new String[]{"bash", "-lc", cmd});
        for (String line : out.split("\\R")) {
            String p = line.trim();
            if (p.isEmpty()) continue;
            String norm = p.replace('\\', '/');
            if (norm.endsWith(".java")) {
                listingPaths.add(norm);
                addSimpleNameFromTestPath(simpleNames, norm);
            }
        }
    }

    // ========= Step 3: run tests =========
    private static TestResult runMavenTests(List<String> simpleClassNames) throws IOException, InterruptedException {
        if (simpleClassNames.isEmpty()) return TestResult.noTests("No Maven tests selected.");
        String testProp = String.join(",", simpleClassNames);
        String cmd = "mvn -B -DskipITs=true -DfailIfNoTests=false -Dtest=" + shellQuote(testProp) + "  -Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition test";
        ExecResult er = execute(new String[]{"bash", "-lc", cmd});
        return new TestResult(true, er.exitCode, cmd, er.stdout, "maven");
    }

    private static TestResult runGradleTests(List<String> simpleClassNames) throws IOException, InterruptedException {
        if (!Files.exists(Path.of("gradlew"))) return TestResult.noProject("gradlew not found.");
        if (simpleClassNames.isEmpty()) return TestResult.noTests("No Gradle tests selected.");
        String patterns = simpleClassNames.stream().map(n -> "--tests " + shellQuote(n)).collect(Collectors.joining(" "));
        String cmd = "chmod +x gradlew && ./gradlew test " + patterns + " --no-daemon --console=plain";
        ExecResult er = execute(new String[]{"bash", "-lc", cmd});
        return new TestResult(true, er.exitCode, "./gradlew test " + patterns, er.stdout, "gradle");
    }

    private static String summarizeResults(TestResult r, String reportsTail) {
        StringBuilder sb = new StringBuilder();
        sb.append("tool: ").append(r.tool).append("\n");
        sb.append("executed: ").append(r.executed).append("\n");
        sb.append("exitCode: ").append(r.executed ? r.exitCode : "(n/a)").append("\n");
        sb.append("command: ").append(r.command).append("\n\n");

        Pattern p = Pattern.compile("(?im)Results?:\\s*|Tests run:\\s*\\d+\\s*,.*|Failures:\\s*\\d+.*|Errors:\\s*\\d+.*|Skipped:\\s*\\d+.*");
        Matcher m = p.matcher(r.log);
        List<String> lines = new ArrayList<>();
        while (m.find()) lines.add(m.group());
        if (lines.isEmpty()) {
            String[] arr = reportsTail.split("\\R");
            int start = Math.max(0, arr.length - 80);
            for (int i = start; i < arr.length; i++) lines.add(arr[i]);
        }
        sb.append(String.join("\n", lines));
        return sb.toString();
    }

    // ========= Failure diagnostics =========
    private static String collectFailureDiagnostics() throws IOException {
        List<String> snippets = new ArrayList<>();
        collectJUnitXmlFailures(snippets, Path.of("target", "surefire-reports"));
        collectJUnitXmlFailures(snippets, Path.of("target", "failsafe-reports"));
        collectJUnitXmlFailures(snippets, Path.of("build", "test-results", "test"));

        if (snippets.isEmpty()) {
            collectSurefireTxtFailures(snippets, Path.of("target", "surefire-reports"));
            collectSurefireTxtFailures(snippets, Path.of("target", "failsafe-reports"));
        }

        if (snippets.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(3, snippets.size());
        for (int i = 0; i < limit; i++) {
            String s = snippets.get(i);
            sb.append(s.length() > 3500 ? s.substring(0, 3500) + "\n...[truncated]...\n" : s);
            if (i < limit - 1) sb.append("\n\n");
        }
        return sb.toString();
    }

    private static void collectJUnitXmlFailures(List<String> out, Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).filter(x -> x.toString().endsWith(".xml")).toList()) {
                String xml = Files.readString(p, StandardCharsets.UTF_8);
                Pattern tc = Pattern.compile(
                        "<testcase\\b[^>]*?classname=\"([^\"]*)\"[^>]*?name=\"([^\"]*)\"[^>]*?>([\\s\\S]*?)</testcase>",
                        Pattern.CASE_INSENSITIVE);
                Matcher mt = tc.matcher(xml);
                while (mt.find()) {
                    String cls = mt.group(1);
                    String name = mt.group(2);
                    String inner = mt.group(3);
                    Matcher mf = Pattern.compile("<(failure|error)\\b[^>]*?(?:message=\"([^\"]*)\")?[^>]*?>([\\s\\S]*?)</\\1>",
                            Pattern.CASE_INSENSITIVE).matcher(inner);
                    if (mf.find()) {
                        String kind = mf.group(1).toUpperCase(Locale.ROOT);
                        String msg = Optional.ofNullable(mf.group(2)).orElse("").trim();
                        String body = mf.group(3).trim();
                        String snippet = "Test: " + cls + "#" + name + " [" + kind + "]\n"
                                + (msg.isEmpty() ? "" : ("Message: " + msg + "\n"))
                                + "Stack:\n" + trimTo(body, 2500);
                        out.add(snippet);
                    }
                }
            }
        }
    }

    private static void collectSurefireTxtFailures(List<String> out, Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).filter(x -> x.toString().endsWith(".txt")).toList()) {
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                Pattern marker = Pattern.compile("<<<\\s*(FAILURE|ERROR)\\s*!\\s*");
                for (int i = 0; i < lines.size(); i++) {
                    String ln = lines.get(i);
                    if (marker.matcher(ln).find()) {
                        int start = Math.max(0, i - 5);
                        int end = Math.min(lines.size(), i + 60);
                        String block = String.join("\n", lines.subList(start, end));
                        String snippet = p + ":\n" + block;
                        out.add(snippet);
                    }
                }
            }
        }
    }

    // ========= LLM or rule-based =========
    private static String analyzeWithOpenRouter(String key, String model, String prompt, int maxTokens) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
            String body = """
            {
              "model": %s,
              "messages": [
                {"role": "system", "content": "You are a senior CI/CD debugging and testing assistant."},
                {"role": "user", "content": %s}
              ],
              "max_tokens": %d,
              "temperature": 0.2
            }
            """.formatted(jsonString(model), jsonString(prompt), maxTokens);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://openrouter.ai/api/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .header("HTTP-Referer", "https://github.com")
                    .header("X-Title", "PR Targeted Test Analyzer")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = resp.statusCode();
            if (status >= 200 && status < 300) {
                String content = extractJsonField(resp.body(), "content");
                if (isBlank(content)) content = resp.body();
                return "```\n" + content.trim() + "\n```";
            } else if (status == 401 || status == 403) {
                return "OpenRouter " + status + " (key invalid or insufficient scope). Falling back to rule-based suggestions.\n\n" + ruleBasedSuggestions(prompt);
            } else if (status == 404) {
                return "OpenRouter 404 (model not found: " + model + "). Falling back to rule-based suggestions.\n\n" + ruleBasedSuggestions(prompt);
            } else if (status == 429) {
                return "OpenRouter 429 (rate limit). Please retry later.\n\n" + ruleBasedSuggestions(prompt);
            } else {
                return "OpenRouter call failed: " + status + " " + resp.body() + "\n\n" + ruleBasedSuggestions(prompt);
            }
        } catch (Exception e) {
            return "OpenRouter call error: " + e.getMessage() + "\n\n" + ruleBasedSuggestions(prompt);
        }
    }

    private static String buildLLMPrompt(String repo, String prNum, String baseSha, String headSha,
                                         List<ChangedFile> changed, TestPlan plan, String summary, String diagnostics) throws IOException, InterruptedException {
        String changedList = changed.stream().map(cf -> cf.status + " " + cf.filename).collect(Collectors.joining("\n"));
        StringBuilder diffs = new StringBuilder();
        for (ChangedFile cf : changed) {
            if (diffs.length() > 14_000) break;
            String safe = shellQuote(cf.filename);
            String out = runProcess(new String[]{"bash", "-lc", "git --no-pager diff --unified=0 -- " + safe + " | sed -n '1,200p' || true"});
            if (!out.isBlank()) {
                diffs.append("\n=== DIFF: ").append(cf.filename).append(" ===\n")
                        .append(trimTo(out, 2000)).append("\n");
            }
        }

        String prompt = "Repository: " + repo + "\n" +
                "PR: #" + prNum + "\n" +
                "Base SHA: " + baseSha + "\n" +
                "Head SHA: " + headSha + "\n\n" +
                "Changed files:\n" + changedList + "\n\n" +
                "Heuristic selected test classes: " + plan.testClasses + "\n\n" +
                "Test summary:\n" + summary + "\n\n" +
                "Failure diagnostics:\n" + (isBlank(diagnostics) ? "(none)" : diagnostics) + "\n\n" +
                "Relevant diffs (truncated):\n" + diffs + "\n\n" +
                "Task:\n" +
                "1) Identify the most relevant unit tests to run (class names) and any that are missing but should exist.\n" +
                "2) Suggest additional minimal tests (class#method) that cover the changed code paths and edge cases.\n" +
                "3) If failures are present, give likely root causes and minimal fixes.\n" +
                "4) Provide the exact Maven or Gradle commands to run those tests.\n" +
                "Keep the answer concise and structured.";
        return trimTo(prompt, 16_000);
    }

    private static String ruleBasedSuggestions(String context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Fallback suggestions (rule-based):\n");
        if (Pattern.compile("ClassNotFoundException|NoClassDefFoundError|cannot find symbol", Pattern.CASE_INSENSITIVE).matcher(context).find()) {
            sb.append("- Java classpath/compile issue: verify dependency provides missing class, and package/class names match.\n");
            sb.append("- Try: mvn -U -e -X clean test\n");
        }
        if (Pattern.compile("AssertionError|expected:.*but was:", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(context).find()) {
            sb.append("- Test assertion mismatch: adjust expected values or fix business logic to align with contract.\n");
        }
        if (Pattern.compile("Could not (find|resolve) (artifact|dependencies)", Pattern.CASE_INSENSITIVE).matcher(context).find()) {
            sb.append("- Maven dependency resolution issue: verify coordinates and repo availability; clear ~/.m2 cache.\n");
        }
        if (sb.toString().equals("Fallback suggestions (rule-based):\n")) {
            sb.append("- No specific signature detected. Re-run selected tests with verbose logs and inspect diffs for edge cases.\n");
        }
        return "```\n" + sb.toString().trim() + "\n```";
    }

    // ========= PR comment & helpers =========
    private static void postPrComment(String repo, String prNumber, Path bodyFile) throws IOException, InterruptedException {
        runProcess(new String[]{
                "gh", "pr", "comment", prNumber,
                "--repo", repo,
                "--body-file", bodyFile.toString()
        });
    }

    private static String readTestReportsTail() throws IOException {
        StringBuilder sb = new StringBuilder();
        try (var walk = Files.walk(Path.of("."))) {
            for (Path p : walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".txt") &&
                            (p.toString().contains("target/surefire-reports") ||
                                    p.toString().contains("target/failsafe-reports") ||
                                    p.toString().contains("build/test-results/test"))).toList()) {
                sb.append("\n==== ").append(p.toString()).append(" ====\n");
                String txt = Files.readString(p, StandardCharsets.UTF_8);
                sb.append(trimTo(txt, 2000));
            }
        } catch (Exception ignored) {}
        String out = sb.toString();
        if (out.length() > LOG_MAX_CHARS) {
            out = "...[truncated to last " + LOG_MAX_CHARS + " chars]...\n" + out.substring(out.length() - LOG_MAX_CHARS);
        }
        return out;
    }

    private static String extractErrorHighlights(String text, int maxLines) {
        if (text == null) text = "";
        String[] lines = text.split("\\R");
        Pattern re = Pattern.compile(
                "\\b(error|failed|failure|exception|traceback|no classdef|classnotfound|assertion(?:error)?|build failed|maven|gradle|test failed|cannot find symbol|undefined reference|stack trace|fatal:)\\b",
                Pattern.CASE_INSENSITIVE
        );
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String ln : lines) {
            if (re.matcher(ln).find()) {
                sb.append(ln).append("\n");
                if (++count >= maxLines) break;
            }
        }
        if (count == 0) {
            int start = Math.max(0, lines.length - Math.min(200, maxLines));
            for (int i = start; i < lines.length; i++) sb.append(lines[i]).append("\n");
        }
        return sb.toString().trim();
    }

    private static String trimTo(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return "...[truncated]...\n" + s.substring(s.length() - max);
    }

    private static String ghApi(String endpoint) throws IOException, InterruptedException {
        return runProcess(new String[]{"gh", "api", endpoint});
    }

    private static String runProcess(String[] cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        if (isBlank(env.get("GH_TOKEN"))) {
            String gt = env.getOrDefault("GITHUB_TOKEN", "");
            if (!isBlank(gt)) env.put("GH_TOKEN", gt);
        }
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(); // ignore code here
        return out;
    }

    private static class ExecResult { final int exitCode; final String stdout; ExecResult(int c, String s){ exitCode=c; stdout=s; } }
    private static ExecResult execute(String[] cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        if (isBlank(env.get("GH_TOKEN"))) {
            String gt = env.getOrDefault("GITHUB_TOKEN", "");
            if (!isBlank(gt)) env.put("GH_TOKEN", gt);
        }
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        return new ExecResult(code, out);
    }

    private static String shellQuote(String s) { return "'" + s.replace("'", "'\"'\"'") + "'"; }
    private static String escapeShell(String s) { return s.replace("'", "'\"'\"'"); }

    private static String jsonString(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }
    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\"";
        int i = json.indexOf(key); if (i < 0) return "";
        int colon = json.indexOf(':', i + key.length()); if (colon < 0) return "";
        int startQuote = json.indexOf('"', colon + 1); if (startQuote < 0) return "";
        int end = startQuote + 1; boolean escape = false;
        while (end < json.length()) { char c = json.charAt(end); if (c == '"' && !escape) break; escape = (c == '\\') && !escape; end++; }
        if (end >= json.length()) return ""; String val = json.substring(startQuote + 1, end);
        return val.replace("\\n", "\n").replace("\\\"", "\"");
    }

    private static String requireEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) throw new IllegalStateException("Missing environment variable: " + key);
        return v;
    }
    private static String getenvOr(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }
    private static int parseIntSafe(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; } }
    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}