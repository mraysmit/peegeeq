package dev.mars.peegeeq.test.quality;

/*
 * Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * CI guard test: ratchets the inventory of Vert.x async forbidden patterns in test sources
 * against a checked-in baseline. The patterns scanned here are documented as failure-swallowing
 * anti-patterns in {@code peegeeq-db/.../testpatterns/VertxAsyncTestPitfallsDemo.java}:
 *
 * <ul>
 *   <li>{@code new VertxTestContext(}                   &mdash; manual context construction (loses VertxExtension lifecycle integration)</li>
 *   <li>{@code .toCompletionStage(}                     &mdash; sync bridge to JDK futures</li>
 *   <li>{@code .toCompletableFuture(}                   &mdash; sync bridge to JDK futures</li>
 * </ul>
 *
 * <p>Note: patterns for {@code .join()} and {@code .get(N, TimeUnit)} are intentionally
 * NOT included. Vert.x {@code Future} has neither method &mdash; both only exist on
 * {@code CompletableFuture} (for {@code .join()}/{@code .get()}) or on
 * {@code java.util.concurrent.Future} from {@code ExecutorService.submit()} (for {@code .get()}).
 * Every legitimate Vert.x-bridge use is therefore preceded by
 * {@code .toCompletionStage()}/{@code .toCompletableFuture()} (already caught above), and the
 * codebase rules forbid bare {@code CompletableFuture} declarations. Scanning for {@code .join()}
 * or {@code .get(N, TimeUnit)} directly would produce false positives on
 * {@code Thread.join()} and {@code ExecutorService}'s {@code Future.get()}.</p>
 *
 * <h2>Ratchet semantics</h2>
 * <p>The baseline file {@code peegeeq-test-support/src/test/resources/quality/vertx-async-forbidden-baseline.csv}
 * lists every (file, pattern, count) currently in the workspace. The test fails on any mismatch:</p>
 * <ul>
 *   <li><b>Regression</b>: a file not in the baseline contains a forbidden pattern, OR a baselined
 *       file has a higher count than recorded.</li>
 *   <li><b>Stale baseline</b>: a baselined file has a lower count than recorded. This means the
 *       file was remediated but the CSV was not updated in the same commit. Remediator must
 *       update or delete the row.</li>
 * </ul>
 * <p>This is a one-way ratchet enforced by the build: counts can only go down, and only via
 * a CSV edit. No new offenders can be introduced.</p>
 *
 * <h2>Scope</h2>
 * <ul>
 *   <li>Scans every {@code peegeeq-*\/src/test/java/**\/*.java} file.</li>
 *   <li>Excludes {@code .history/} (IntelliJ Local History) and {@code target/}.</li>
 *   <li>Excludes {@code VertxAsyncTestPitfallsDemo.java} &mdash; intentional counter-example fixture.</li>
 *   <li>Masks comments and string literals before matching so example code in Javadoc and
 *       string content cannot trigger or hide a violation.</li>
 * </ul>
 *
 * <h2>Why this guard exists</h2>
 * <p>Past sweeps repeatedly reported "all tests conforming" while these patterns persisted &mdash;
 * because they are designed to produce green builds. Static enforcement at build time removes
 * the reliance on test-run outcomes for evidence of correctness.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-05-15
 */
@Tag(TestCategories.CORE)
class VertxAsyncForbiddenPatternsGuardTest {

    /** Baseline resource location, relative to the test classpath. */
    private static final String BASELINE_RESOURCE = "/quality/vertx-async-forbidden-baseline.csv";

    /** Files that are part of the counter-example fixture and must never be flagged. */
    private static final List<String> EXCLUDED_FILE_NAMES = List.of(
            "VertxAsyncTestPitfallsDemo.java"
    );

    /** Ordered map of pattern-key -> compiled regex. Keys appear in the baseline CSV. */
    private static final Map<String, Pattern> FORBIDDEN = new LinkedHashMap<>();
    static {
        FORBIDDEN.put("newVTC", Pattern.compile("new\\s+VertxTestContext\\s*\\("));
        FORBIDDEN.put("toCS",   Pattern.compile("\\.toCompletionStage\\s*\\("));
        FORBIDDEN.put("toCF",   Pattern.compile("\\.toCompletableFuture\\s*\\("));
    }

    @Test
    void forbiddenAsyncPatternsMatchBaseline() throws IOException {
        Path workspaceRoot = locateWorkspaceRoot();
        Map<String, Integer> baseline = loadBaseline();
        Map<String, Integer> actual = scanWorkspace(workspaceRoot);

        List<String> regressions = new ArrayList<>();
        List<String> stale = new ArrayList<>();

        // Regressions: actual entries with count > baseline (or absent from baseline).
        for (Map.Entry<String, Integer> e : actual.entrySet()) {
            int allowed = baseline.getOrDefault(e.getKey(), 0);
            if (e.getValue() > allowed) {
                regressions.add("  REGRESSION  " + e.getKey() + "  actual=" + e.getValue() + "  allowed=" + allowed);
            }
        }
        // Stale: baseline entries with count > actual (or absent from actual).
        for (Map.Entry<String, Integer> e : baseline.entrySet()) {
            int found = actual.getOrDefault(e.getKey(), 0);
            if (e.getValue() > found) {
                stale.add("  STALE       " + e.getKey() + "  actual=" + found + "  baselined=" + e.getValue()
                        + "  -> update or delete the row in vertx-async-forbidden-baseline.csv");
            }
        }

        if (regressions.isEmpty() && stale.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Vert.x async forbidden-pattern guard FAILED.\n")
          .append("Baseline: peegeeq-test-support/src/test/resources/quality/vertx-async-forbidden-baseline.csv\n")
          .append("Reference: peegeeq-db/src/test/java/dev/mars/peegeeq/db/testpatterns/VertxAsyncTestPitfallsDemo.java\n\n");
        if (!regressions.isEmpty()) {
            sb.append("Regressions (new or increased forbidden-pattern usage; not permitted):\n");
            regressions.forEach(r -> sb.append(r).append('\n'));
            sb.append('\n');
        }
        if (!stale.isEmpty()) {
            sb.append("Stale baseline entries (file was remediated but CSV was not updated):\n")
              .append("Update each row to the new count, or delete the row if count is now 0.\n");
            stale.forEach(r -> sb.append(r).append('\n'));
        }
        fail(sb.toString());
    }

    // ---------------------------------------------------------------------
    // Scan
    // ---------------------------------------------------------------------

    /**
     * Scan every {@code peegeeq-*\/src/test/java/**\/*.java} file in the workspace and
     * return a map keyed by {@code "<relative-path>|<pattern-key>"} -> non-zero count.
     * Excludes {@code .history/}, {@code target/}, and files listed in {@link #EXCLUDED_FILE_NAMES}.
     */
    private static Map<String, Integer> scanWorkspace(Path workspaceRoot) throws IOException {
        Map<String, Integer> result = new TreeMap<>();
        try (Stream<Path> modules = Files.list(workspaceRoot)) {
            List<Path> testRoots = modules
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("peegeeq-"))
                    .map(p -> p.resolve("src").resolve("test").resolve("java"))
                    .filter(Files::isDirectory)
                    .toList();
            for (Path testRoot : testRoots) {
                try (Stream<Path> files = Files.walk(testRoot)) {
                    files.filter(Files::isRegularFile)
                         .filter(p -> p.getFileName().toString().endsWith(".java"))
                         .filter(p -> !EXCLUDED_FILE_NAMES.contains(p.getFileName().toString()))
                         .filter(p -> !p.toString().replace('\\', '/').contains("/.history/"))
                         .filter(p -> !p.toString().replace('\\', '/').contains("/target/"))
                         .forEach(p -> countFile(workspaceRoot, p, result));
                }
            }
        }
        return result;
    }

    private static void countFile(Path workspaceRoot, Path file, Map<String, Integer> sink) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        String masked = maskNonCode(content);
        String rel = workspaceRoot.relativize(file).toString().replace('\\', '/');
        for (Map.Entry<String, Pattern> e : FORBIDDEN.entrySet()) {
            int count = 0;
            Matcher m = e.getValue().matcher(masked);
            while (m.find()) count++;
            if (count > 0) {
                sink.put(rel + "|" + e.getKey(), count);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Baseline
    // ---------------------------------------------------------------------

    private static Map<String, Integer> loadBaseline() throws IOException {
        Map<String, Integer> baseline = new TreeMap<>();
        try (var in = VertxAsyncForbiddenPatternsGuardTest.class.getResourceAsStream(BASELINE_RESOURCE)) {
            if (in == null) {
                throw new IOException("Baseline resource not found on classpath: " + BASELINE_RESOURCE);
            }
            String csv = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : csv.split("\\r?\\n")) {
                if (line.isBlank()) continue;
                if (line.startsWith("#")) continue;
                if (line.startsWith("path,")) continue; // header
                String[] parts = line.split(",");
                if (parts.length != 3) {
                    throw new IOException("Malformed baseline line: '" + line + "'");
                }
                String path = parts[0].trim();
                String pat = parts[1].trim();
                int count;
                try {
                    count = Integer.parseInt(parts[2].trim());
                } catch (NumberFormatException nfe) {
                    throw new IOException("Malformed baseline count on line: '" + line + "'");
                }
                if (!FORBIDDEN.containsKey(pat)) {
                    throw new IOException("Unknown pattern key '" + pat + "' in baseline. "
                            + "Expected one of " + FORBIDDEN.keySet());
                }
                baseline.put(path + "|" + pat, count);
            }
        }
        return baseline;
    }

    // ---------------------------------------------------------------------
    // Workspace root discovery (same approach as OnSuccessExceptionSwallowingGuardTest)
    // ---------------------------------------------------------------------

    private static Path locateWorkspaceRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path candidate = cwd;
        for (int i = 0; i < 6; i++) {
            if (Files.isDirectory(candidate.resolve("peegeeq-test-support"))
                    && Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate;
            }
            Path parent = candidate.getParent();
            if (parent == null) break;
            candidate = parent;
        }
        throw new IllegalStateException("Could not locate workspace root from " + cwd);
    }

    // ---------------------------------------------------------------------
    // Comment / string literal masking (length-preserving). Mirrors the helper in
    // OnSuccessExceptionSwallowingGuardTest so behaviour is consistent across guards.
    // ---------------------------------------------------------------------

    private static String maskNonCode(String content) {
        char[] out = content.toCharArray();
        int i = 0;
        int n = content.length();
        while (i < n) {
            char c = content.charAt(i);
            if (c == '/' && i + 1 < n) {
                char next = content.charAt(i + 1);
                if (next == '/') {
                    int end = skipLineComment(content, i);
                    for (int k = i; k <= end && k < n; k++) {
                        if (out[k] != '\n' && out[k] != '\r') out[k] = ' ';
                    }
                    i = end + 1;
                    continue;
                }
                if (next == '*') {
                    int end = skipBlockComment(content, i);
                    for (int k = i; k <= end && k < n; k++) {
                        if (out[k] != '\n' && out[k] != '\r') out[k] = ' ';
                    }
                    i = end + 1;
                    continue;
                }
            }
            if (c == '"' || c == '\'') {
                int end = skipStringLiteral(content, i);
                for (int k = i; k <= end && k < n; k++) {
                    if (out[k] != '\n' && out[k] != '\r') out[k] = ' ';
                }
                i = end + 1;
                continue;
            }
            i++;
        }
        return new String(out);
    }

    private static int skipStringLiteral(String s, int i) {
        char quote = s.charAt(i);
        if (quote == '"' && i + 2 < s.length()
                && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"') {
            int j = i + 3;
            while (j + 2 < s.length()) {
                if (s.charAt(j) == '"' && s.charAt(j + 1) == '"' && s.charAt(j + 2) == '"') {
                    return j + 2;
                }
                j++;
            }
            return s.length() - 1;
        }
        int j = i + 1;
        while (j < s.length()) {
            char c = s.charAt(j);
            if (c == '\\') { j += 2; continue; }
            if (c == quote) return j;
            if (c == '\n') return j;
            j++;
        }
        return s.length() - 1;
    }

    private static int skipLineComment(String s, int i) {
        int j = i;
        while (j < s.length() && s.charAt(j) != '\n') j++;
        return j;
    }

    private static int skipBlockComment(String s, int i) {
        int j = i + 2;
        while (j + 1 < s.length()) {
            if (s.charAt(j) == '*' && s.charAt(j + 1) == '/') return j + 1;
            j++;
        }
        return s.length() - 1;
    }
}
