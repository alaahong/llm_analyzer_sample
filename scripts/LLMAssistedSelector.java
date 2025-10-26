import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * LLM-assisted test selector: refines and expands the heuristic test set using an LLM.
 * Provider:
 *  - openai-compatible: LLM_PROVIDER=openai, LLM_BASE_URL, LLM_MODEL, LLM_API_KEY
 *  - openrouter: default fallback if OPENROUTER_API_KEY present
 *
 * Controls:
 *  - LLM_TEST_SELECTOR=true|false  (default: false)
 *  - LLM_SELECTOR_MAX_FILES (default: 60)   max changed files included
 *  - LLM_SELECTOR_MAX_DIFF_CHARS (default: 16000)
 *  - LLM_MAX_TOKENS (reuse existing; default 800)
 *
 * Output:
 *  - Returns a Selection result with refinedClasses and optional method-level filters.
 *  - Also writes the raw LLM response and parsed JSON into scripts/.llm-test-selector/ for audit.
 */
public class LLMAssistedSelector {
    public static class Selection {
        public final List<String> refinedClasses;      // simple class names
        public final Map<String, List<String>> methodsByClass; // class -> list of test methods (optional)
        public final String rationale;                 // model-provided reasons (optional)
        public Selection(List<String> c, Map<String,List<String>> m, String r) {
            this.refinedClasses = c; this.methodsByClass = m; this.rationale = r == null ? "" : r;
        }
    }

    public static boolean isEnabled() {
        return "true".equalsIgnoreCase(System.getenv().getOrDefault("LLM_TEST_SELECTOR", "true"));
    }

    public static Selection refine(List<String> heuristicClasses,
                                   List<String> changedFiles,
                                   Map<String, List<String>> packageToTests,
                                   Map<String, String> fileToDiff) {
        try {
            String provider = getenvOr("LLM_PROVIDER", "openrouter").trim().toLowerCase(Locale.ROOT);
            int maxTokens = parseIntSafe(getenvOr("LLM_MAX_TOKENS", "800"), 800);

            // Build prompt with strict schema
            String prompt = buildPrompt(heuristicClasses, changedFiles, packageToTests, fileToDiff);

            String raw;
            if ("openai".equals(provider)) {
                String base = getenvOr("LLM_BASE_URL", "").trim();
                String model = getenvOr("LLM_MODEL", "").trim();
                String key   = getenvOr("LLM_API_KEY", "").trim();
                if (isBlank(base) || isBlank(model)) {
                    return new Selection(heuristicClasses, Map.of(), "LLM openai provider not configured; keep heuristic selection.");
                }
                raw = callOpenAICompat(base, key, model, prompt, maxTokens);
            } else {
                String orKey = getenvOr("OPENROUTER_API_KEY", "").trim();
                String orModel = getenvOr("OPENROUTER_MODEL", "meta-llama/llama-3.3-8b-instruct:free").trim();
                if (isBlank(orKey)) {
                    return new Selection(heuristicClasses, Map.of(), "OpenRouter not configured; keep heuristic selection.");
                }
                raw = callOpenRouter(orKey, orModel, prompt, maxTokens);
            }

            // Persist for audit
            Path outDir = Paths.get("scripts", ".llm-test-selector");
            Files.createDirectories(outDir);
            Files.writeString(outDir.resolve("raw.txt"), raw, StandardCharsets.UTF_8);

            // Parse JSON from model output (robust extraction)
            String json = extractFirstJsonObject(raw);
            if (json.isEmpty()) {
                return new Selection(heuristicClasses, Map.of(), "No JSON parsed; keep heuristic selection.");
            }
            Files.writeString(outDir.resolve("parsed.json"), json, StandardCharsets.UTF_8);

            // Expect schema:
            // {
            //   "test_classes": ["FooTest", ...],
            //   "test_methods": [{"class":"FooTest","methods":["m1","m2"]}, ...],
            //   "reasons": "..."
            // }
            List<String> classes = parseStringArray(json, "test_classes");
            if (classes.isEmpty()) classes = heuristicClasses;
            Map<String, List<String>> methods = parseMethods(json);
            String reasons = parseString(json, "reasons");

            // Deduplicate + keep only existing heuristic or discovered package tests if you want stricter trust
            LinkedHashSet<String> merged = new LinkedHashSet<>(classes);
            // Safety: cap size to avoid accidental explosion
            if (merged.size() > 200) {
                merged = new LinkedHashSet<>(new ArrayList<>(merged).subList(0, 200));
            }
            return new Selection(new ArrayList<>(merged), methods, reasons);
        } catch (Exception e) {
            return new Selection(heuristicClasses, Map.of(), "LLM selector error: " + e.getMessage());
        }
    }

    private static String buildPrompt(List<String> heuristicClasses,
                                      List<String> changedFiles,
                                      Map<String, List<String>> packageToTests,
                                      Map<String, String> fileToDiff) {
        String filesList = changedFiles.stream().limit(parseIntSafe(getenvOr("LLM_SELECTOR_MAX_FILES", "60"), 60))
                .collect(Collectors.joining("\n"));

        StringBuilder diffs = new StringBuilder();
        int maxDiff = parseIntSafe(getenvOr("LLM_SELECTOR_MAX_DIFF_CHARS", "16000"), 16000);
        int used = 0;
        for (Map.Entry<String,String> e : fileToDiff.entrySet()) {
            if (used > maxDiff) break;
            String chunk = "\n=== DIFF: " + e.getKey() + " ===\n" + e.getValue();
            int add = Math.min(chunk.length(), maxDiff - used);
            diffs.append(chunk, 0, add);
            used += add;
        }

        StringBuilder pkgIdx = new StringBuilder();
        for (var kv : packageToTests.entrySet()) {
            pkgIdx.append(kv.getKey()).append(" -> ").append(kv.getValue()).append("\n");
            if (pkgIdx.length() > 8000) break;
        }

        return """
        You are a senior Java/Spring testing expert. Determine the minimal-but-sufficient unit tests to run for this change list.
        Input:
        - Changed files (with status): 
        %s

        - Heuristic candidate test classes (initial): %s

        - Test index (package -> tests): 
        %s

        - Diffs (truncated):
        %s

        Task:
        - Return a strict JSON object with keys:
          {
            "test_classes": ["SimpleTestName1", "SimpleTestName2", ...],   // minimal set to run now
            "test_methods": [                                              // optional, when only specific tests are needed
                {"class": "SimpleTestName1", "methods": ["methodA", "methodB"]},
                ...
            ],
            "reasons": "brief rationale for your selections and exclusions"
          }
        Rules:
        - Only include existing tests (by simple class name, e.g. FooTest), avoid duplicates, keep under ~50 unless necessary.
        - Prefer tests that directly cover modified code paths, controllers/services touched, and their slices.
        - If initial set is already minimal, you may return the same list with reasons.
        """.formatted(filesList, heuristicClasses, pkgIdx, diffs);
    }

    private static String callOpenRouter(String key, String model, String prompt, int maxTokens) throws IOException, InterruptedException {
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
                .header("X-Title", "LLM Test Selector")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return resp.body();
    }

    private static String callOpenAICompat(String baseUrl, String apiKey, String model, String prompt, int maxTokens) throws IOException, InterruptedException {
        String url;
        if (baseUrl.endsWith("/chat/completions")) url = baseUrl;
        else if (baseUrl.endsWith("/v1/")) url = baseUrl + "chat/completions";
        else if (baseUrl.endsWith("/v1")) url = baseUrl + "/chat/completions";
        else url = baseUrl + "/v1/chat/completions";

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
        return resp.body();
    }

    // -------- JSON helpers (tolerant) --------

    private static String extractFirstJsonObject(String s) {
        int start = s.indexOf('{');
        while (start >= 0) {
            int depth = 0;
            for (int i = start; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return s.substring(start, i + 1);
                }
            }
            start = s.indexOf('{', start + 1);
        }
        return "";
    }

    private static List<String> parseStringArray(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
        Matcher m = p.matcher(json);
        if (!m.find()) return List.of();
        String arr = m.group(1);
        Matcher sm = Pattern.compile("\"([^\"]+)\"").matcher(arr);
        List<String> out = new ArrayList<>();
        while (sm.find()) out.add(sm.group(1).trim());
        // dedupe, keep order
        return new ArrayList<>(new LinkedHashSet<>(out));
    }

    private static Map<String, List<String>> parseMethods(String json) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Pattern item = Pattern.compile("\\{\\s*\"class\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"methods\"\\s*:\\s*\\[(.*?)\\]\\s*\\}");
        Matcher m = item.matcher(json);
        while (m.find()) {
            String cls = m.group(1).trim();
            String arr = m.group(2);
            Matcher sm = Pattern.compile("\"([^\"]+)\"").matcher(arr);
            List<String> methods = new ArrayList<>();
            while (sm.find()) methods.add(sm.group(1).trim());
            out.put(cls, methods);
        }
        return out;
    }

    private static String parseString(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"", Pattern.DOTALL);
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : "";
    }

    // -------- utils --------
    private static String getenvOr(String k, String d){ String v = System.getenv(k); return v==null||v.isBlank()?d:v; }
    private static boolean isBlank(String s){ return s==null || s.trim().isEmpty(); }
    private static int parseIntSafe(String s, int def){ try { return Integer.parseInt(s.trim()); } catch(Exception e){ return def; } }
    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}