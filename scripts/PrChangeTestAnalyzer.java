/* Java 21 PR Targeted Test Analyzer with OpenRouter suggestions and rule-based fallback.
 * Spring Boot enhanced mapping + affected API endpoints + optional LLM-assisted test selection (via reflection):
 *  - From changed src/main/java FQN -> find tests and affected controllers by:
 *      * Heuristic name mapping: FooTest/TestFoo/FooTests
 *      * Same-package test discovery under src/test/java/<pkgDir>/**
 *      * Reference scanning in tests and controllers
 *  - Parse Spring MVC annotations to build endpoints (class + method level).
 *  - De-duplicate candidate test files and endpoints; only show focused failure diagnostics on failures.
 *
 * Defaults:
 *  - OpenRouter model default: meta-llama/llama-3.3-8b-instruct:free
 *  - FAIL_ON_TEST_FAILURE default: true
 *  - WORKFLOW_NAME default: "PR Targeted Test Analyzer"
 *  - Maven test command disables JUnit @Disabled via:
 *      -Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition
 *
 * Optional local/self-hosted LLM (OpenAI-compatible):
 *  - LLM_PROVIDER=openai to call any OpenAI-compatible /v1/chat/completions endpoint (e.g., Ollama/vLLM/LM Studio/llama.cpp).
 *  - LLM_BASE_URL, LLM_MODEL, LLM_API_KEY control the local provider call.
 *
 * Optional LLM-assisted test selection (two-stage scheme):
 *  - LLM_TEST_SELECTOR=true to enable LLMAssistedSelector (if present) via reflection.
 *  - If scripts/LLMAssistedSelector.java is not present/compiled, selection is skipped silently.
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

            // LLM config: OpenRouter (default) and optional OpenAI-compatible local provider
            String orKey = getenvOr("OPENROUTER_API_KEY", "").trim();
            String orModel = getenvOr("OPENROUTER_MODEL", "meta-llama/llama-3.3-8b-instruct:free").trim();
            int llmMax = parseIntSafe(getenvOr("LLM_MAX_TOKENS", "800"), 800);
            boolean failOnTestFailure = "true".equalsIgnoreCase(getenvOr("FAIL_ON_TEST_FAILURE", "true"));

            String llmProvider = getenvOr("LLM_PROVIDER", "openrouter").trim().toLowerCase(Locale.ROOT);
            String llmBaseUrl = getenvOr("LLM_BASE_URL", "").trim(); // e.g. http://127.0.0.1:11434/v1
            String llmApiKey  = getenvOr("LLM_API_KEY", "").trim();
            String llmModel   = getenvOr("LLM_MODEL", "").trim();    // e.g. llama3.1:8b-instruct

            // 1) PR changed files
            List<ChangedFile> changed = listChangedFiles(repo, prNum);
            String changedFilesBlock = changed.isEmpty()
                    ? "(no files reported by the API)"
                    : changed.stream().map(cf -> "- " + cf.status + " " + cf.filename).collect(Collectors.joining("\n"));

            // 2) Project type and Spring Boot enhanced mapping for tests
            ProjectType projectType = detectProjectType();
            TestPlan testPlan = buildSpringBootEnhancedTestPlan(changed, projectType);

            // 2b) Optional LLM-assisted test selection via reflection (no compile-time dependency)
            boolean llmSelectorApplied = false;
            String llmSelectorRationale = "";
            if (isTruth(getenvOr("LLM_TEST_SELECTOR", "TRUE")) && !testPlan.testClasses.isEmpty()) {
                try {
                    // Check class presence and isEnabled flag
                    Class<?> selClass = Class.forName("LLMAssistedSelector");
                    boolean enabled = false;
                    try {
                        enabled = (Boolean) selClass.getMethod("isEnabled").invoke(null);
                    } catch (Throwable ignored) {}
                    if (enabled) {
                        // Build selector inputs
                        List<String> changedFilesForSelector = changed.stream()
                                .map(cf -> cf.status + " " + cf.filename)
                                .collect(Collectors.toList());
                        Map<String, List<String>> pkgIndex = new LinkedHashMap<>();
                        if (testPlan.candidateTestsListing != null && !testPlan.candidateTestsListing.isBlank()) {
                            for (String line : testPlan.candidateTestsListing.split("\\R")) {
                                if (line == null || line.isBlank()) continue;
                                String norm = line.replace('\\', '/');
                                String rel = norm.replaceFirst("^src/test/java/", "");
                                int slash = rel.lastIndexOf('/');
                                String pkg = slash > 0 ? rel.substring(0, slash).replace('/', '.') : "(default)";
                                String cls = rel.substring(rel.lastIndexOf('/') + 1).replaceAll("\\.java$", "");
                                pkgIndex.computeIfAbsent(pkg, k -> new ArrayList<>()).add(cls);
                            }
                        }
                        Map<String, String> fileToDiff = new LinkedHashMap<>();
                        for (ChangedFile cf : changed) {
                            String safe = shellQuote(cf.filename);
                            String diff = runProcess(new String[]{"bash", "-lc", "git --no-pager diff --unified=0 -- " + safe + " | sed -n '1,200p' || true"});
                            fileToDiff.put(cf.filename, diff == null ? "" : diff);
                            if (fileToDiff.size() >= 80) break;
                        }

                        // Call LLMAssistedSelector.refine via reflection
                        Object selObj = selClass
                                .getMethod("refine", List.class, List.class, Map.class, Map.class)
                                .invoke(null, testPlan.testClasses, changedFilesForSelector, pkgIndex, fileToDiff);

                        if (selObj != null) {
                            // Read fields from LLMAssistedSelector.Selection via reflection
                            // Try public fields refinedClasses and rationale (per provided class)
                            List<String> refined = null;
                            String rationale = "";
                            try {
                                refined = (List<String>) selObj.getClass().getField("refinedClasses").get(selObj);
                            } catch (Throwable ignored) {}
                            try {
                                Object r = selObj.getClass().getField("rationale").get(selObj);
                                rationale = r == null ? "" : String.valueOf(r);
                            } catch (Throwable ignored) {}
                            if (refined != null && !refined.isEmpty()) {
                                testPlan = new TestPlan(projectType, refined, testPlan.candidateTestsListing);
                                llmSelectorApplied = true;
                                llmSelectorRationale = rationale;
                            }
                        }
                    }
                } catch (ClassNotFoundException cnf) {
                    // LLMAssistedSelector not present: skip silently
                } catch (Throwable t) {
                    // Any reflection error: skip selector
                }
            }

            // 3) Affected API endpoints (Spring MVC)
            List<Endpoint> endpoints = detectAffectedEndpoints(changed);

            // 4) Execute selected tests
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
            if (testResult.executed && testResult.exitCode != 0 && failOnTestFailure) intendedExit = 1;

            // 5) Failure diagnostics only when tests fail
            String failureDiagnostics = "";
            if (testResult.executed && testResult.exitCode != 0) {
                failureDiagnostics = collectFailureDiagnostics();
                if (isBlank(failureDiagnostics)) {
                    String reportsTail = readTestReportsTail();
                    String combined = trimTo(testResult.log, 40_000) + "\n" + reportsTail;
                    failureDiagnostics = extractErrorHighlights(combined, HIGHLIGHT_MAX_LINES);
                }
            }

            // 6) Summary + suggestions
            String reportsTailForSummary = readTestReportsTail();
            String summary = summarizeResults(testResult, reportsTailForSummary);

            String llmAnalysis;
            String llmContext = buildLLMPrompt(repo, prNum, baseSha, headSha, changed, testPlan, summary, failureDiagnostics);

            if ("openai".equals(llmProvider)) {
                if (isBlank(llmBaseUrl) || isBlank(llmModel)) {
                    llmAnalysis = "OpenAI-compatible provider is selected but LLM_BASE_URL or LLM_MODEL is missing.\n\n" +
                            ruleBasedSuggestions(failureDiagnostics);
                } else {
                    llmAnalysis = analyzeWithOpenAICompatibleOrFallback(llmBaseUrl, llmApiKey, llmModel, llmContext, llmMax, failureDiagnostics);
                }
            } else {
                if (!orKey.isBlank()) {
                    llmAnalysis = analyzeWithOpenRouter(orKey, orModel, llmContext, llmMax);
                } else {
                    llmAnalysis = ruleBasedSuggestions(failureDiagnostics);
                }
            }

            // 7) Compose PR comment
            String prUrl = serverUrl + "/" + repo + "/pull/" + prNum;
            StringBuilder body = new StringBuilder();
            body.append("🤖 PR Targeted Test Analyzer (Spring Boot enhanced + API endpoints");
            if (llmSelectorApplied) body.append(" + LLM-assisted selection");
            body.append(")\n\n");
            body.append("- PR: ").append(prUrl).append("\n");
            body.append("- Build tool: ").append(projectType).append("\n");
            body.append("- Selected tests: ").append(testPlan.testClasses.isEmpty() ? "(none)" : testPlan.testClasses).append("\n");
            body.append("- Test command: ").append(testResult.command).append("\n");
            body.append("- Test exit code: ").append(testResult.executed ? testResult.exitCode : "(n/a)").append("\n");
            body.append("- Fail-on-test-failure: ").append(failOnTestFailure ? "enabled" : "disabled").append("\n\n");

            body.append("Changed files:\n```\n").append(changedFilesBlock).append("\n```\n\n");
            if (!isBlank(testPlan.candidateTestsListing)) {
                body.append("Candidate test files (mapped and existing, de-duplicated):\n```\n")
                        .append(testPlan.candidateTestsListing).append("\n```\n\n");
            }

            if (llmSelectorApplied && !isBlank(llmSelectorRationale)) {
                body.append("LLM-selected tests rationale:\n```\n")
                        .append(trimTo(llmSelectorRationale, 4000)).append("\n```\n\n");
            }

            if (!endpoints.isEmpty()) {
                body.append("Affected API endpoints (detected):\n```\n");
                LinkedHashSet<String> lines = new LinkedHashSet<>();
                for (Endpoint ep : endpoints) {
                    String line = String.format("- [%s] %s -> %s#%s (%s)",
                            ep.httpMethod, ep.path, ep.controllerSimple, isBlank(ep.methodName) ? "<unknown>" : ep.methodName, ep.reason);
                    lines.add(line);
                }
                body.append(String.join("\n", lines)).append("\n```\n\n");
            } else {
                body.append("Affected API endpoints (detected):\n```\n(none)\n```\n\n");
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
            System.out.println("Posted PR analysis, endpoints, and test results.");
        } catch (Exception e) {
            System.err.println("Failed to run PR analyzer: " + e);
            System.exit(0);
        }
    }

    // ========= Data models =========
    enum ProjectType { MAVEN, GRADLE, UNKNOWN }

    static class ChangedFile {
        final String filename; final String status;
        ChangedFile(String f, String s){ filename=f; status=s; }
    }

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

    static class Endpoint {
        final String httpMethod; final String path; final String controllerSimple; final String methodName; final String reason;
        Endpoint(String m, String p, String ctrl, String meth, String r){ httpMethod=m; path=p; controllerSimple=ctrl; methodName=meth; reason=r; }
        @Override public int hashCode(){ return Objects.hash(httpMethod, path, controllerSimple, methodName); }
        @Override public boolean equals(Object o){
            if (!(o instanceof Endpoint e)) return false;
            return Objects.equals(httpMethod,e.httpMethod) && Objects.equals(path,e.path) &&
                    Objects.equals(controllerSimple,e.controllerSimple) && Objects.equals(methodName,e.methodName);
        }
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

    // ========= Step 2: enhanced Spring Boot mapping for tests =========
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

            // Include changed test files directly
            if (cf.filename.startsWith("src/test/java/")) {
                listingPaths.add(cf.filename.replace('\\','/'));
                addSimpleNameFromTestPath(simpleNames, cf.filename);
                continue;
            }

            // Map main code changes to tests
            if (cf.filename.startsWith("src/main/java/")) {
                String rel = cf.filename.substring("src/main/java/".length());
                if (!rel.endsWith(".java")) continue;
                String relNoExt = rel.substring(0, rel.length() - 5);
                String leaf = relNoExt.contains("/") ? relNoExt.substring(relNoExt.lastIndexOf('/') + 1) : relNoExt;
                String pkgDir = relNoExt.contains("/") ? relNoExt.substring(0, relNoExt.lastIndexOf('/')) : "";
                String fqn = relNoExt.replace('/', '.');

                // Heuristic name mapping
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

                // Same-package tests
                addAllTestsUnderPackage(listingPaths, simpleNames, pkgDir);

                // Reference scanning in tests
                addRefScannedTestsInTests(listingPaths, simpleNames, fqn, leaf);
            }
        }

        String listing = listingPaths.stream().collect(Collectors.joining("\n"));
        return new TestPlan(ProjectType.MAVEN, new ArrayList<>(simpleNames), listing);
    }

    private static void addSimpleNameFromTestPath(Set<String> simpleNames, String testPath) {
        String leaf = testPath.substring(testPath.lastIndexOf('/') + 1).replace('\\','/');
        if (leaf.endsWith(".java")) simpleNames.add(leaf.substring(0, leaf.length() - 5));
    }

    private static void addAllTestsUnderPackage(Set<String> listingPaths, Set<String> simpleNames, String pkgDir) throws IOException {
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

    private static void addRefScannedTestsInTests(Set<String> listingPaths, Set<String> simpleNames, String fqn, String leaf) throws IOException, InterruptedException {
        String pattern = String.join("|", List.of(
                Pattern.quote("import " + fqn),
                Pattern.quote(fqn + ".class"),
                "@WebMvcTest\\s*\\(.*\\b" + Pattern.quote(leaf) + "\\s*\\.\\s*class\\b.*\\)",
                "@MockBean\\s*\\(.*\\b" + Pattern.quote(leaf) + "\\s*\\.\\s*class\\b.*\\)",
                "@Autowired\\s+[^;\\n]*\\b" + Pattern.quote(leaf) + "\\b",
                "\\bnew\\s+" + Pattern.quote(leaf) + "\\s*\\("
        ));
        String out = runProcess(new String[]{"bash", "-lc",
                "set -o pipefail; if command -v grep >/dev/null 2>&1; then " +
                        "grep -RIl --include='*.java' -E '" + pattern + "' src/test/java || true; fi"});
        for (String line : out.split("\\R")) {
            String p = line.trim(); if (p.isEmpty()) continue;
            String norm = p.replace('\\','/');
            if (norm.endsWith(".java")) {
                listingPaths.add(norm);
                addSimpleNameFromTestPath(simpleNames, norm);
            }
        }
    }

    // ========= Step 3: affected API endpoints detection =========
    private static List<Endpoint> detectAffectedEndpoints(List<ChangedFile> changed) throws IOException, InterruptedException {
        LinkedHashSet<Endpoint> set = new LinkedHashSet<>();

        // 1) Controllers directly modified
        for (ChangedFile cf : changed) {
            if (cf.filename.startsWith("src/main/java/") && cf.filename.endsWith(".java")) {
                Path p = Path.of(cf.filename);
                if (Files.exists(p)) {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    if (isControllerFile(p, content)) {
                        String controllerSimple = simpleClassNameFromContent(content, p);
                        List<Endpoint> eps = parseControllerEndpoints(p, content, "controller modified: " + cf.filename, controllerSimple);
                        set.addAll(eps);
                    }
                }
            }
        }

        // 2) For non-controller changed classes, find referencing controllers
        List<ChangedMainClass> changedClasses = new ArrayList<>();
        for (ChangedFile cf : changed) {
            if (cf.filename.startsWith("src/main/java/") && cf.filename.endsWith(".java")) {
                String rel = cf.filename.substring("src/main/java/".length());
                String relNoExt = rel.substring(0, rel.length() - 5);
                String fqn = relNoExt.replace('/', '.').replace('\\','.');
                String leaf = relNoExt.contains("/") ? relNoExt.substring(relNoExt.lastIndexOf('/') + 1) : relNoExt;
                changedClasses.add(new ChangedMainClass(fqn, leaf, cf.filename));
            }
        }
        if (!changedClasses.isEmpty()) {
            List<Path> controllers = listAllControllerFiles();
            for (Path ctrl : controllers) {
                String content = Files.readString(ctrl, StandardCharsets.UTF_8);
                String reasonRef = null;
                for (ChangedMainClass c : changedClasses) {
                    if (referencesClass(content, c)) {
                        reasonRef = "references changed class " + c.fqn;
                        break;
                    }
                }
                if (reasonRef != null) {
                    String controllerSimple = simpleClassNameFromContent(content, ctrl);
                    List<Endpoint> eps = parseControllerEndpoints(ctrl, content, reasonRef, controllerSimple);
                    set.addAll(eps);
                }
            }
        }

        return new ArrayList<>(set);
    }

    private record ChangedMainClass(String fqn, String leaf, String file) {}

    private static boolean referencesClass(String content, ChangedMainClass c) {
        String pattern = String.join("|", List.of(
                "\\bimport\\s+" + Pattern.quote(c.fqn) + "\\s*;",
                Pattern.quote(c.fqn) + "\\s*\\.\\s*class\\b",
                "\\bnew\\s+" + Pattern.quote(c.leaf) + "\\s*\\(",
                "@Autowired\\s+[^;\\n]*\\b" + Pattern.quote(c.leaf) + "\\b"
        ));
        return Pattern.compile(pattern, Pattern.DOTALL).matcher(content).find();
    }

    private static List<Path> listAllControllerFiles() throws IOException {
        List<Path> list = new ArrayList<>();
        Path root = Path.of("src", "main", "java");
        if (!Files.exists(root)) return list;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).filter(pp -> pp.toString().endsWith(".java")).toList()) {
                try {
                    String s = Files.readString(p, StandardCharsets.UTF_8);
                    if (isControllerFile(p, s)) list.add(p);
                } catch (Exception ignored) {}
            }
        }
        return list;
    }

    private static boolean isControllerFile(Path p, String content) {
        String norm = p.toString().replace('\\','/').toLowerCase(Locale.ROOT);
        if (norm.contains("/controller/")) return true;
        return content.contains("@RestController") || content.contains("@Controller");
    }

    private static String simpleClassNameFromContent(String content, Path p) {
        Matcher m = Pattern.compile("\\bclass\\s+(\\w+)\\b").matcher(content);
        if (m.find()) return m.group(1);
        String file = p.getFileName().toString();
        return file.endsWith(".java") ? file.substring(0, file.length()-5) : file;
    }

    private static List<Endpoint> parseControllerEndpoints(Path ctrlFile, String content, String reason, String controllerSimple) {
        int classPos = indexOfClassDecl(content);
        String header = classPos > 0 ? content.substring(0, classPos) : content;
        String body = classPos > 0 ? content.substring(classPos) : content;

        List<String> classBases = extractRequestMappingPaths(header);
        if (classBases.isEmpty()) classBases = List.of("");

        List<Endpoint> out = new ArrayList<>();

        Matcher ann = Pattern.compile("@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)\\s*(\\(([^)]*)\\))?", Pattern.MULTILINE).matcher(body);
        while (ann.find()) {
            String type = ann.group(1);
            String params = ann.group(3) == null ? "" : ann.group(3);

            List<String> methodPaths = extractPathsFromParams(params);
            if (methodPaths.isEmpty()) methodPaths = List.of("");

            List<String> httpMethods = new ArrayList<>();
            if (!"RequestMapping".equals(type)) {
                httpMethods = List.of(type.replace("Mapping","").toUpperCase(Locale.ROOT));
            } else {
                httpMethods = extractRequestMethods(params);
                if (httpMethods.isEmpty()) httpMethods = List.of("ANY");
            }

            String methodName = findFollowingMethodName(body, ann.end());
            for (String base : classBases) {
                for (String mp : methodPaths) {
                    String full = normalizePath(base, mp);
                    for (String hm : httpMethods) {
                        out.add(new Endpoint(hm, full, controllerSimple, methodName, reason));
                    }
                }
            }
        }

        if (out.isEmpty() && !classBases.equals(List.of(""))) {
            for (String base : classBases) {
                out.add(new Endpoint("ANY", normalizePath(base, ""), controllerSimple, "", reason));
            }
        }
        return out;
    }

    private static int indexOfClassDecl(String content) {
        Matcher m = Pattern.compile("\\bclass\\s+\\w+\\b").matcher(content);
        return m.find() ? m.start() : -1;
    }

    private static List<String> extractRequestMappingPaths(String text) {
        List<String> paths = new ArrayList<>();
        Matcher m = Pattern.compile("@RequestMapping\\s*\\(([^)]*)\\)").matcher(text);
        while (m.find()) {
            String params = m.group(1);
            paths.addAll(extractPathsFromParams(params));
            if (paths.isEmpty()) paths.addAll(extractStringLiterals(params));
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String p : paths) out.add(normalizePath(p, ""));
        return new ArrayList<>(out);
    }

    private static List<String> extractPathsFromParams(String params) {
        List<String> paths = new ArrayList<>();
        if (params == null) return paths;
        Matcher named = Pattern.compile("\\b(value|path)\\s*=\\s*(\\{[^}]*\\}|\"[^\"]*\"|'[^']*')").matcher(params);
        while (named.find()) {
            String val = named.group(2);
            paths.addAll(extractStringLiterals(val));
        }
        if (paths.isEmpty()) paths.addAll(extractStringLiterals(params));
        return paths;
    }

    private static List<String> extractRequestMethods(String params) {
        List<String> methods = new ArrayList<>();
        if (params == null) return methods;
        Matcher mm = Pattern.compile("\\bmethod\\s*=\\s*(\\{[^}]*\\}|[^,\\)]+)").matcher(params);
        if (mm.find()) {
            String group = mm.group(1);
            Matcher each = Pattern.compile("RequestMethod\\.(GET|POST|PUT|DELETE|PATCH|OPTIONS|HEAD)", Pattern.CASE_INSENSITIVE).matcher(group);
            while (each.find()) methods.add(each.group(1).toUpperCase(Locale.ROOT));
        }
        return methods;
    }

    private static List<String> extractStringLiterals(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = Pattern.compile("\"([^\"]*)\"|'([^']*)'").matcher(text);
        while (m.find()) {
            String s = m.group(1) != null ? m.group(1) : m.group(2);
            if (s != null) out.add(s);
        }
        return out;
    }

    private static String findFollowingMethodName(String body, int startIdx) {
        String tail = body.substring(startIdx);
        Matcher m = Pattern.compile("(public|protected|private)\\s+(static\\s+)?[\\w<>,\\[\\]\\s]+\\s+(\\w+)\\s*\\(", Pattern.MULTILINE).matcher(tail);
        if (m.find()) return m.group(3);
        m = Pattern.compile("\\b(\\w+)\\s*\\(").matcher(tail);
        return m.find() ? m.group(1) : "";
    }

    private static String normalizePath(String base, String methodPath) {
        String a = (base == null ? "" : base.trim());
        String b = (methodPath == null ? "" : methodPath.trim());
        String s = (a + "/" + b).replaceAll("/{2,}", "/");
        if (!s.startsWith("/")) s = "/" + s;
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length()-1);
        return s;
    }

    // ========= Step 3b: run tests =========
    private static TestResult runMavenTests(List<String> simpleClassNames) throws IOException, InterruptedException {
        if (simpleClassNames.isEmpty()) return TestResult.noTests("No Maven tests selected.");
        String testProp = String.join(",", simpleClassNames);
        // Include deactivation of JUnit @Disabled via conditions
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

    // ========= LLM callers =========
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

    private static String analyzeWithOpenAICompatibleOrFallback(String baseUrl, String apiKey, String model, String prompt, int maxTokens, String fallbackContext) {
        try {
            String url;
            if (baseUrl.endsWith("/chat/completions")) {
                url = baseUrl;
            } else if (baseUrl.endsWith("/v1/")) {
                url = baseUrl + "chat/completions";
            } else if (baseUrl.endsWith("/v1")) {
                url = baseUrl + "/chat/completions";
            } else {
                url = baseUrl + "/v1/chat/completions";
            }

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

            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json");
            if (!isBlank(apiKey)) b.header("Authorization", "Bearer " + apiKey);

            HttpRequest request = b.POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = resp.statusCode();
            if (status >= 200 && status < 300) {
                String content = extractJsonField(resp.body(), "content");
                if (isBlank(content)) content = resp.body();
                return "```\n" + content.trim() + "\n```";
            } else if (status == 401 || status == 403) {
                return "OpenAI-compatible " + status + " (invalid key or not provided). Falling back to rule-based suggestions.\n\n" + ruleBasedSuggestions(fallbackContext);
            } else if (status == 404) {
                return "OpenAI-compatible 404 (route/model not found). Check LLM_BASE_URL and LLM_MODEL.\n\n" + ruleBasedSuggestions(fallbackContext);
            } else if (status == 429) {
                return "OpenAI-compatible 429 (rate limit). Please retry later.\n\n" + ruleBasedSuggestions(fallbackContext);
            } else {
                return "OpenAI-compatible call failed: " + status + " " + resp.body() + "\n\n" + ruleBasedSuggestions(fallbackContext);
            }
        } catch (Exception e) {
            return "OpenAI-compatible call error: " + e.getMessage() + "\n\n" + ruleBasedSuggestions(fallbackContext);
        }
    }

    // ========= Prompt & rule-based fallback =========
    private static String buildLLMPrompt(String repo, String prNum, String baseSha, String headSha,
                                         List<ChangedFile> changed, TestPlan plan, String summary, String diagnostics) throws IOException, InterruptedException {
        String changedList = changed.stream().map(cf -> cf.status + " " + cf.filename).collect(Collectors.joining("\n"));
        StringBuilder diffs = new StringBuilder();
        for (ChangedFile cf : changed) {
            if (diffs.length() > 14_000) break;
            String safe = shellQuote(cf.filename);
            String out = runProcess(new String[]{"bash", "-lc", "git --no-pager diff --unified=0 -- " + safe + " | sed -n '1,200p' || true"});
            if (!isBlank(out)) {
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
    private static int parseIntSafe(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; } }
    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private static boolean isTruth(String s) { return "true".equalsIgnoreCase(String.valueOf(s).trim()); }
}