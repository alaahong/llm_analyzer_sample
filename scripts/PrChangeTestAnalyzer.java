/* Java 21 PR Targeted Test Analyzer with OpenRouter suggestions and rule-based fallback.
 * - On PR events, list changed source files.
 * - Heuristically map them to unit test files/classes (Java/Maven/Gradle focus), verify existence, and run only those tests.
 * - Summarize results and error highlights, then post a PR comment.
 * - If OPENROUTER_API_KEY is present, call OpenRouter to propose additional tests and fixes based on highlights.
 * - If LLM is unavailable, fall back to built-in rules for quick suggestions.
 *
 * Notes:
 * - Primarily for Java projects:
 *   * Maven: maps src/main/java/...Foo.java -> src/test/java/...FooTest|TestFoo|FooTests.java, runs with -Dtest=...
 *   * Gradle: same mapping; runs with --tests SimpleClassName
 * - From forks, secrets may not be available; script still comments with rule-based suggestions.
 * - Optional failing behavior: set FAIL_ON_TEST_FAILURE=true to fail the job when targeted tests fail.
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

            String provider = "openrouter";
            String orKey = getenvOr("OPENROUTER_API_KEY", "").trim();
            String orModel = getenvOr("OPENROUTER_MODEL", "meta-llama/llama-3.3-8b-instruct:free").trim();
            int llmMax = parseIntSafe(getenvOr("LLM_MAX_TOKENS", "800"), 800);
            boolean failOnTestFailure = "true".equalsIgnoreCase(getenvOr("FAIL_ON_TEST_FAILURE", "false"));

            // 1) Gather PR file changes
            List<ChangedFile> changed = listChangedFiles(repo, prNum);
            String changedFilesBlock = changed.isEmpty()
                    ? "(no files reported by the API)"
                    : changed.stream().map(cf -> "- " + cf.status + " " + cf.filename).collect(Collectors.joining("\n"));

            // 2) Heuristic mapping and plan
            ProjectType projectType = detectProjectType();
            TestPlan testPlan = buildTestPlan(changed, projectType);

            // 3) Execute tests (targeted; if none found, skip but comment)
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
                testResult = TestResult.noTests("No targeted unit tests matched heuristics. Skipping selective run.");
            }

            if (testResult.executed && testResult.exitCode != 0 && failOnTestFailure) {
                intendedExit = 1; // mark for failing at the end
            }

            // 4) Collect highlights from console + reports
            String reportsTail = readTestReportsTail();
            String combined = trimTo(testResult.log, 40_000) + "\n" + reportsTail;
            String errorHighlights = extractErrorHighlights(combined, HIGHLIGHT_MAX_LINES);
            String summary = summarizeResults(testResult, reportsTail);

            // 5) Ask OpenRouter (optional) for analysis/suggestions based on diff+highlights
            String llmAnalysis;
            String llmContext = buildLLMPrompt(repo, prNum, baseSha, headSha, changed, testPlan, summary, errorHighlights);
            if (!orKey.isBlank()) {
                llmAnalysis = analyzeWithOpenRouter(orKey, orModel, llmContext, llmMax);
            } else {
                llmAnalysis = ruleBasedSuggestions(errorHighlights);
            }

            // 6) Compose PR comment
            String prUrl = serverUrl + "/" + repo + "/pull/" + prNum;
            StringBuilder body = new StringBuilder();
            body.append("🤖 PR Targeted Test Analyzer (OpenRouter)\n\n");
            body.append("- PR: ").append(prUrl).append("\n");
            body.append("- Build tool: ").append(projectType).append("\n");
            body.append("- Selected tests (heuristics): ").append(testPlan.testClasses.isEmpty() ? "(none)" : testPlan.testClasses).append("\n");
            body.append("- Test command: ").append(testResult.command).append("\n");
            body.append("- Test exit code: ").append(testResult.executed ? testResult.exitCode : "(n/a)").append("\n");
            if (failOnTestFailure) body.append("- Fail-on-test-failure: enabled\n");
            body.append("\n");

            body.append("Changed files:\n```\n").append(changedFilesBlock).append("\n```\n\n");
            if (!testPlan.candidateTestsListing.isBlank()) {
                body.append("Candidate test files (mapped and existing):\n```\n")
                        .append(testPlan.candidateTestsListing).append("\n```\n\n");
            }

            body.append("Test summary:\n```\n").append(summary).append("\n```\n\n");
            if (!errorHighlights.isBlank()) {
                body.append("Error highlights (first ").append(HIGHLIGHT_MAX_LINES).append(" lines):\n```txt\n")
                        .append(errorHighlights).append("\n```\n\n");
            }

            body.append("Analysis and suggestions:\n")
                    .append(llmAnalysis).append("\n");

            String finalBody = body.toString();
            if (finalBody.length() > BODY_MAX_CHARS) {
                finalBody = finalBody.substring(0, BODY_MAX_CHARS) + "\n\n…(truncated)…";
            }

            // 7) Post comment to PR
            Path tmp = Files.createTempFile("pr-test-analyzer-", ".md");
            Files.writeString(tmp, finalBody, StandardCharsets.UTF_8);
            postPrComment(repo, prNum, tmp);

            // Optional: enforce failure after commenting
            if (intendedExit != 0) System.exit(intendedExit);
            System.out.println("Posted PR analysis and test results.");
        } catch (Exception e) {
            System.err.println("Failed to run PR analyzer: " + e);
            // Do not fail the job to avoid blocking PR by default
            System.exit(0);
        }
    }

    // ---------------- Data models ----------------
    enum ProjectType { MAVEN, GRADLE, UNKNOWN }

    static class ChangedFile {
        final String filename;
        final String status;
        ChangedFile(String filename, String status) { this.filename = filename; this.status = status; }
    }

    static class TestPlan {
        final ProjectType projectType;
        final List<String> testClasses; // simple class names to pass to runner
        final String candidateTestsListing; // pretty listing
        TestPlan(ProjectType t, List<String> cls, String listing) {
            this.projectType = t; this.testClasses = cls; this.candidateTestsListing = listing == null ? "" : listing;
        }
    }

    static class TestResult {
        final boolean executed;
        final int exitCode;
        final String command;
        final String log;
        final String tool;

        TestResult(boolean executed, int exitCode, String command, String log, String tool) {
            this.executed = executed;
            this.exitCode = exitCode;
            this.command = command;
            this.log = log;
            this.tool = tool;
        }
        static TestResult noTests(String msg) { return new TestResult(false, 0, "(no tests executed)", msg, "(none)"); }
        static TestResult noProject(String msg) { return new TestResult(false, 0, "(no project detected)", msg, "(none)"); }
    }

    // ---------------- Step 1: list changed files ----------------
    private static List<ChangedFile> listChangedFiles(String repo, String prNum) throws IOException, InterruptedException {
        List<ChangedFile> out = new ArrayList<>();
        int page = 1;
        while (true) {
            String json = ghApi(String.format("repos/%s/pulls/%s/files?per_page=100&page=%d", repo, prNum, page));
            Pattern p = Pattern.compile("\"filename\"\\s*:\\s*\"([^\"]+)\".*?\"status\"\\s*:\\s*\"([^\"]+)\"", Pattern.DOTALL);
            Matcher m = p.matcher(json);
            boolean any = false;
            while (m.find()) {
                any = true;
                out.add(new ChangedFile(m.group(1), m.group(2)));
            }
            if (!any || json.length() < 10 || !json.contains("\"filename\"")) break;
            page++;
        }
        return out;
    }

    // ---------------- Step 2: build test plan ----------------
    private static ProjectType detectProjectType() {
        if (Files.exists(Path.of("pom.xml"))) return ProjectType.MAVEN;
        if (Files.exists(Path.of("gradlew"))) return ProjectType.GRADLE;
        return ProjectType.UNKNOWN;
    }

    private static TestPlan buildTestPlan(List<ChangedFile> changed, ProjectType type) throws IOException {
        if (type == ProjectType.MAVEN || type == ProjectType.GRADLE) {
            return buildJavaTestPlan(changed);
        } else {
            return new TestPlan(type, List.of(), "");
        }
    }

    private static TestPlan buildJavaTestPlan(List<ChangedFile> changed) throws IOException {
        Set<String> classNames = new LinkedHashSet<>();
        StringBuilder listing = new StringBuilder();

        for (ChangedFile cf : changed) {
            if (!cf.filename.endsWith(".java")) continue;

            // Map src/main/java/.../Foo.java -> candidate tests under src/test/java/...:
            if (cf.filename.startsWith("src/main/java/")) {
                String rel = cf.filename.substring("src/main/java/".length());
                if (rel.endsWith(".java")) rel = rel.substring(0, rel.length() - 5);
                String leaf = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
                String pkgDir = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/')) : "";

                List<String> candidates = List.of(
                        "src/test/java/" + pkgDir + "/" + leaf + "Test.java",
                        "src/test/java/" + pkgDir + "/" + "Test" + leaf + ".java",
                        "src/test/java/" + pkgDir + "/" + leaf + "Tests.java"
                );

                List<String> found = new ArrayList<>();
                for (String c : candidates) {
                    if (Files.exists(Path.of(c))) found.add(c);
                }

                if (found.isEmpty()) {
                    // Grep tests for references to leaf
                    List<String> grepHits = grepTestReferences("src/test/java", leaf);
                    found.addAll(grepHits);
                }

                for (String f : found) {
                    listing.append(f).append("\n");
                    String simple = toSimpleClassNameFromPath(f, "src/test/java/");
                    if (!simple.isBlank()) classNames.add(simple);
                }
            }

            // If a test file itself changed, include it
            if (cf.filename.startsWith("src/test/java/") && cf.filename.endsWith(".java")) {
                listing.append(cf.filename).append("\n");
                String simple = toSimpleClassNameFromPath(cf.filename, "src/test/java/");
                if (!simple.isBlank()) classNames.add(simple);
            }
        }

        return new TestPlan(ProjectType.MAVEN, new ArrayList<>(classNames), listing.toString().trim());
    }

    private static String toSimpleClassNameFromPath(String path, String testRootPrefix) {
        if (!path.startsWith(testRootPrefix)) return "";
        String leaf = path.substring(path.lastIndexOf('/') + 1);
        if (!leaf.endsWith(".java")) return "";
        return leaf.substring(0, leaf.length() - 5);
    }

    private static List<String> grepTestReferences(String root, String classLeafName) throws IOException {
        List<String> results = new ArrayList<>();
        String[] cmd = new String[]{"bash", "-lc",
                "set -o pipefail; " +
                        "if command -v grep >/dev/null 2>&1; then " +
                        "grep -RIl --include='*.java' -e '\\b" + escapeShell(classLeafName) + "\\b' " + escapeShell(root) + " || true; " +
                        "fi"
        };
        try {
            String out = runProcess(cmd);
            for (String line : out.split("\\R")) {
                if (!line.isBlank()) results.add(line.trim());
            }
        } catch (Exception ignored) {}
        return results;
    }

    // ---------------- Step 3: run tests ----------------
    private static TestResult runMavenTests(List<String> simpleClassNames) throws IOException, InterruptedException {
        if (simpleClassNames.isEmpty()) return TestResult.noTests("No Maven tests selected.");
        String testProp = String.join(",", simpleClassNames);
        String cmd = "mvn -B -DskipITs=true -DfailIfNoTests=false -Dtest=" + shellQuote(testProp) + " test";
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

        // Try to extract surefire-like summaries from console
        Pattern p = Pattern.compile("(?im)Results?:\\s*|Tests run:\\s*\\d+\\s*,.*|Failures:\\s*\\d+.*|Errors:\\s*\\d+.*|Skipped:\\s*\\d+.*");
        Matcher m = p.matcher(r.log);
        List<String> lines = new ArrayList<>();
        while (m.find()) lines.add(m.group());
        if (lines.isEmpty()) {
            // fallback: tail from reports
            String[] arr = reportsTail.split("\\R");
            int start = Math.max(0, arr.length - 80);
            for (int i = start; i < arr.length; i++) lines.add(arr[i]);
        }
        sb.append(String.join("\n", lines));
        return sb.toString();
    }

    // ---------------- Step 5: OpenRouter or rules ----------------
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
                                         List<ChangedFile> changed, TestPlan plan, String summary, String highlights) throws IOException, InterruptedException {
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
                "Error highlights:\n" + highlights + "\n\n" +
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

    // ---------------- Comment posting ----------------
    private static void postPrComment(String repo, String prNumber, Path bodyFile) throws IOException, InterruptedException {
        runProcess(new String[]{
                "gh", "pr", "comment", prNumber,
                "--repo", repo,
                "--body-file", bodyFile.toString()
        });
    }

    // ---------------- Utilities ----------------
    private static String readTestReportsTail() throws IOException {
        StringBuilder sb = new StringBuilder();
        // surefire/failsafe
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

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\"";
        int i = json.indexOf(key);
        if (i < 0) return "";
        int colon = json.indexOf(':', i + key.length());
        if (colon < 0) return "";
        int startQuote = json.indexOf('"', colon + 1);
        if (startQuote < 0) return "";
        int end = startQuote + 1;
        boolean escape = false;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '"' && !escape) break;
            escape = (c == '\\') && !escape;
            end++;
        }
        if (end >= json.length()) return "";
        String val = json.substring(startQuote + 1, end);
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

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}