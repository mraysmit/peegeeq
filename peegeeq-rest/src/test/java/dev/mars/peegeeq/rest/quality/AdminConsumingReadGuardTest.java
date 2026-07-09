package dev.mars.peegeeq.rest.quality;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CI guard test (Phase 13.3 — destructive-read safeguards): the PeeGeeQ admin/observability
 * surface must never grow a new queue-<em>consuming</em> read. An admin view that
 * {@code createConsumer(...).subscribe(...)} on a queue acks/removes the very messages it
 * displays — "a SELECT that also deletes the rows it read" — stealing them from real consumers.
 *
 * <p>This test scans every {@code peegeeq-rest/src/main/java/**\/*.java} file (comment- and
 * string-aware) and counts {@code createConsumer(} and {@code .subscribe(} call sites per file,
 * then ratchets each file against {@code /quality/rest-consuming-read-baseline.csv}. It runs in
 * the default Maven profile ({@code @Tag(CORE)}, ~0.1 s, no database, no Testcontainers).</p>
 *
 * <h2>Why a ratchet, not zero-tolerance</h2>
 * A handful of REST handlers legitimately consume — but only via explicitly-named, gated
 * destructive paths the admin UI never reaches as a read: consumer-group CRUD
 * ({@code ConsumerGroupHandler}, {@code ManagementApiHandler.createConsumerGroup}), webhook
 * delivery ({@code WebhookSubscriptionHandler}), subscription management
 * ({@code SubscriptionHandler}), and immutable event-store subscribe ({@code EventStoreHandler}).
 * Those existing sites are captured in the baseline CSV. Any <em>increase</em> in a baselined
 * file, or <em>any</em> consume in a currently-clean admin read/view/stream handler
 * ({@code QueueHandler}, {@code ServerSentEventsHandler}, and the queue-read methods of
 * {@code ManagementApiHandler}, all at baseline 0), fails the build.
 *
 * <p>Non-destructive queue reads use {@code QueueBrowser.browse()} (point-in-time) or
 * {@code QueueBrowser.tail()} (live observe) and never appear here. See §7.13 of
 * {@code peegeeq-management-ui/docs/tasks/MANAGEMENT_UI_ENHANCEMENTS-14-Jun-2026.md}.</p>
 *
 * <h3>Updating the baseline</h3>
 * If you legitimately add or remove a gated destructive call site, update the matching row in
 * {@code peegeeq-rest/src/test/resources/quality/rest-consuming-read-baseline.csv} to the new
 * count (or delete the row when the count reaches 0). Never raise a baseline to silence a new
 * consuming read in an admin read/view/stream handler — fix the code to browse/tail instead.
 */
@Tag(TestCategories.CORE)
class AdminConsumingReadGuardTest {

    /** {@code createConsumer(} — matches {@code queueFactory.createConsumer(} but not {@code createConsumerGroup(}. */
    private static final Pattern CREATE_CONSUMER =
            Pattern.compile("\\bcreateConsumer\\s*\\(");
    /** {@code .subscribe(} on any receiver. */
    private static final Pattern SUBSCRIBE =
            Pattern.compile("\\.subscribe\\s*\\(");

    private static final String BASELINE_RESOURCE = "/quality/rest-consuming-read-baseline.csv";
    private static final String BASELINE_CSV_PATH =
            "peegeeq-rest/src/test/resources/quality/rest-consuming-read-baseline.csv";

    // =========================================================================
    // Test
    // =========================================================================

    @Test
    void noNewConsumingReadsInRestMainSources() throws IOException {
        Path workspaceRoot = locateWorkspaceRoot();
        Path mainRoot = workspaceRoot.resolve(Paths.get("peegeeq-rest", "src", "main", "java"));
        if (!Files.isDirectory(mainRoot)) {
            fail("Could not locate peegeeq-rest main sources at " + mainRoot);
        }

        Map<String, Integer> baseline = loadBaseline();
        Map<String, Integer> actual = scanPerFile(workspaceRoot, mainRoot);

        List<String> regressions = new ArrayList<>();
        List<String> stale = new ArrayList<>();

        for (Map.Entry<String, Integer> e : actual.entrySet()) {
            int allowed = baseline.getOrDefault(e.getKey(), 0);
            if (e.getValue() > allowed) {
                regressions.add("  REGRESSION  " + e.getKey()
                        + "  actual=" + e.getValue() + "  allowed=" + allowed);
            }
        }
        for (Map.Entry<String, Integer> e : baseline.entrySet()) {
            int found = actual.getOrDefault(e.getKey(), 0);
            if (e.getValue() > found) {
                stale.add("  STALE       " + e.getKey()
                        + "  actual=" + found + "  baselined=" + e.getValue()
                        + "  -> lower or delete the row in the baseline CSV");
            }
        }

        if (regressions.isEmpty() && stale.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        sb.append("Admin consuming-read guard [Phase 13.3] FAILED.\n")
          .append("An admin read/view/stream path must never createConsumer(/.subscribe( on a queue —\n")
          .append("that consumes (acks/removes) the messages it displays. Use QueueBrowser.browse()/tail().\n")
          .append("Baseline: ").append(BASELINE_CSV_PATH).append("\n\n");
        if (!regressions.isEmpty()) {
            sb.append("Regressions (new or increased consuming reads; not permitted):\n");
            regressions.forEach(r -> sb.append(r).append('\n'));
            sb.append('\n');
        }
        if (!stale.isEmpty()) {
            sb.append("Stale baseline entries (file was remediated but CSV was not updated):\n")
              .append("Lower each row to the new count, or delete the row if the count is now 0.\n");
            stale.forEach(r -> sb.append(r).append('\n'));
        }
        fail(sb.toString());
    }

    /**
     * Detector self-test: proves the guard actually fires on a consuming read and ignores the
     * safe shapes — so a green {@link #noNewConsumingReadsInRestMainSources()} means "no
     * violations", never "the matcher is broken and always passes".
     */
    @Test
    void guardActuallyDetectsConsumingReads() {
        // Real consuming calls are counted.
        assertEquals(2, countConsumingReadsIn(
                "queueFactory.createConsumer(topic);\n"
                        + "consumer.subscribe(msg -> Future.succeededFuture());"),
                "createConsumer( and .subscribe( must both be counted");

        // Comments and string literals are masked out — not counted.
        assertEquals(0, countConsumingReadsIn(
                "// createConsumer( in a line comment\n"
                        + "/* .subscribe( in a block comment */\n"
                        + "String s = \".subscribe(\";"),
                "occurrences inside comments/strings must be ignored");

        // Non-destructive reads and consumer-group creation are never matched.
        assertEquals(0, countConsumingReadsIn(
                "createBrowser().browse();\n"
                        + "browser.tail(handler);\n"
                        + "queueFactory.createConsumerGroup(name, topic, Object.class);"),
                "browse()/tail()/createConsumerGroup( must not be flagged as consuming reads");
    }

    // =========================================================================
    // Scan
    // =========================================================================

    private static Map<String, Integer> scanPerFile(Path workspaceRoot, Path mainRoot) throws IOException {
        Map<String, Integer> result = new TreeMap<>();
        try (Stream<Path> files = Files.walk(mainRoot)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.getFileName().toString().endsWith(".java"))
                 .forEach(p -> {
                     int count = countConsumingReads(p);
                     if (count > 0) {
                         String rel = workspaceRoot.relativize(p).toString().replace('\\', '/');
                         result.put(rel, count);
                     }
                 });
        }
        return result;
    }

    private static int countConsumingReads(Path file) {
        String content = readFile(file);
        if (content == null) return 0;
        return countConsumingReadsIn(content);
    }

    /**
     * Counts {@code createConsumer(} / {@code .subscribe(} call sites in a source string, after
     * masking comments and string/char literals. Package-private so the detector can be
     * self-tested against synthetic snippets (see {@link #guardActuallyDetectsConsumingReads()}).
     */
    static int countConsumingReadsIn(String content) {
        String masked = maskNonCode(content);
        int count = 0;
        Matcher c = CREATE_CONSUMER.matcher(masked);
        while (c.find()) count++;
        Matcher s = SUBSCRIBE.matcher(masked);
        while (s.find()) count++;
        return count;
    }

    // =========================================================================
    // Baseline loading
    // =========================================================================

    /**
     * Loads the {@code path,count} baseline from the classpath. Returns an empty map if the
     * resource is absent, which makes every existing call site a regression (fails loudly).
     */
    private static Map<String, Integer> loadBaseline() throws IOException {
        Map<String, Integer> baseline = new TreeMap<>();
        try (var in = AdminConsumingReadGuardTest.class.getResourceAsStream(BASELINE_RESOURCE)) {
            if (in == null) return baseline;
            String csv = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : csv.split("\\r?\\n")) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("path,")) continue;
                int comma = line.lastIndexOf(',');
                if (comma < 0) continue;
                String path = line.substring(0, comma).trim();
                int count;
                try {
                    count = Integer.parseInt(line.substring(comma + 1).trim());
                } catch (NumberFormatException nfe) {
                    throw new IOException("Malformed baseline count on line: '" + line + "'");
                }
                if (count > 0) baseline.put(path, count);
            }
        }
        return baseline;
    }

    // =========================================================================
    // Workspace location + comment/string-aware masking (mirrors the peegeeq-test-support guards)
    // =========================================================================

    private static Path locateWorkspaceRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path candidate = cwd;
        for (int i = 0; i < 6; i++) {
            if (Files.isDirectory(candidate.resolve("peegeeq-rest"))
                    && Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate;
            }
            Path parent = candidate.getParent();
            if (parent == null) break;
            candidate = parent;
        }
        throw new IllegalStateException("Could not locate workspace root from " + cwd);
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Replaces the contents of comments, string/char/text-block literals with spaces (preserving
     * newlines) so pattern matching only sees real code. Copied from the peegeeq-test-support
     * quality guards to keep detection behaviour identical.
     */
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
