package dev.mars.peegeeq.test.quality;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * CI guard test: detects the "{@code .onSuccess(... -> { ... })} exception swallowing"
 * anti-pattern that silently routes synchronous test exceptions to
 * {@code Vertx.exceptionHandler()} and causes {@code VertxTestContext} timeouts
 * with no root cause in the log.
 *
 * <p>This test scans every {@code peegeeq-*\/src/test/java/**\/*.java} file in the workspace
 * for {@code .onSuccess(... -> { body })} <b>block</b> lambdas whose body contains JUnit
 * assertion calls outside an enclosing {@code testContext.verify(...)} wrapper. Any such
 * site fails the build.</p>
 *
 * <h2>How this guard locks the regression boundary</h2>
 *
 * <p>This is a regular JUnit {@code @Test} method that runs in every CI build and fails the
 * build immediately if any new Tier&nbsp;2 or Tier&nbsp;3 violation is introduced anywhere in
 * the workspace. The mechanism is entirely self-contained:</p>
 *
 * <ol>
 *   <li><b>Runs in the default profile.</b> The class is annotated {@code @Tag(TestCategories.CORE)},
 *       so {@code mvn test} (no special profile, no Testcontainers, no database) executes it.
 *       Wall-clock cost is ~0.3&nbsp;s, so there is no incentive to skip it.</li>
 *
 *   <li><b>Discovers the workspace at run-time.</b> {@link #locateWorkspaceRoot()} walks up
 *       from the working directory until it finds a folder that contains both
 *       {@code peegeeq-test-support/} and {@code pom.xml}. From there it lists every
 *       directory whose name starts with {@code peegeeq-} that has a {@code src/test/java}
 *       sub-tree, so newly-added modules under that naming convention are automatically
 *       covered without changing this file.</li>
 *
 *   <li><b>Scans every test source file with a precise tokeniser.</b> For each
 *       {@code .onSuccess(...)} call site, {@link #scanFile(Path, List)} extracts the
 *       <em>block</em> lambda body using a balanced-paren / balanced-brace walker that is
 *       aware of Java string literals, text blocks ({@code """..."""}), character literals,
 *       line comments and block comments. Method references and expression-form lambdas
 *       (Tier&nbsp;1) are skipped — they cannot exhibit the swallowing pattern.</li>
 *
 *   <li><b>Strips safe constructs before checking.</b> Before applying the violation
 *       patterns the guard removes:
 *       <ul>
 *         <li>{@code <ident>.verify(...)} calls via {@link #stripVerifyCalls(String)} —
 *             assertions inside {@code testContext.verify(...)} are safe.</li>
 *         <li>{@code try { ... } catch (Throwable|Exception ...) { ... }} blocks whose
 *             catch body does not re-{@code throw} via {@link #stripTryCatchFailNow(String)} —
 *             any synchronous exception is contained regardless of whether it is routed to
 *             {@code failNow}, logged via {@code logger.warn}, or ignored.</li>
 *         <li>For Tier&nbsp;2 only, every balanced {@code { ... }} nested block via
 *             {@link #stripNestedBraceBlocks(String)} — {@code .close()} calls inside
 *             nested lambdas ({@code setTimer}, {@code setPeriodic}, {@code forEach},
 *             {@code runOnContext}, …) are out of scope because their exceptions are
 *             routed by their own callback machinery, not by {@code onSuccess}.</li>
 *       </ul></li>
 *
 *   <li><b>Applies the two violation rules.</b>
 *       <ul>
 *         <li><b>Tier&nbsp;3</b>: {@code assertX(} or {@code fail(} appears in the stripped
 *             body. A synchronous throw from such a call escapes the lambda into
 *             {@code Future.onSuccess}, which routes the exception to
 *             {@code Vertx.exceptionHandler()} and the test times out at 30&nbsp;s with no
 *             root cause logged.</li>
 *         <li><b>Tier&nbsp;2</b>: {@code .close(} appears at the top level of the body
 *             after nested-block stripping. If {@code close()} throws, the same routing
 *             swallows the exception, but the test typically still completes because
 *             {@code testContext.completeNow()} ran first — so the leaked resource and the
 *             swallowed error both disappear silently.</li>
 *       </ul></li>
 *
 *   <li><b>Fails the build with actionable detail.</b> On any violation, the test calls
 *       {@code fail(...)} with a per-tier classified report:
 *       {@code [Tier N] absolute-path:line — snippet}, plus the suggested fix and a
 *       reference to the audit document. The failing build cannot be merged.</li>
 * </ol>
 *
 * <p><b>How a regression triggers it.</b> A developer who writes</p>
 * <pre>{@code
 * .onSuccess(v -> {
 *     assertEquals(42, v);   // Tier 3
 *     pool.close();          // Tier 2
 * })
 * }</pre>
 * <p>and runs <i>any</i> {@code mvn test} on the affected branch will see this guard fire
 * with both violations on the new file. There is no opt-out flag, no {@code @Disabled},
 * no environment requirement. The same scan runs locally and in CI.</p>
 *
 * <h2>Out of scope (intentional)</h2>
 * <ul>
 *   <li>Other potentially-throwing synchronous calls in {@code onSuccess}
 *       ({@code .stop()}, {@code .shutdown()}, {@code .commit()}, {@code .rollback()}) —
 *       not currently in {@link #CLOSE_CALL}. Extend when needed.</li>
 *   <li>Non-{@code onSuccess} lambdas ({@code .map}, {@code .compose},
 *       {@code Future.future(p -> ...)}) — these do not exhibit the same swallowing
 *       semantics.</li>
 *   <li>Production sources under {@code src/main/java} — different problem domain.</li>
 *   <li>Tier&nbsp;1 stylistic conversion (canonical
 *       {@code .onComplete(testContext.succeeding(...))} form) — this is safety-neutral and
 *       therefore not enforced.</li>
 * </ul>
 *
 * <p>Safe patterns (not flagged):</p>
 * <ul>
 *   <li>Expression-lambda Tier 1: {@code .onSuccess(v -> testContext.verify(() -> assertEquals(...)))}</li>
 *   <li>Block lambda whose only statements are {@code testContext.verify(...)} + {@code completeNow()} / logging</li>
 *   <li>Canonical form: {@code .onComplete(testContext.succeeding(v -> testContext.verify(() -> { ... })))}</li>
 * </ul>
 *
 * <p>Documented in {@code docs-design/tasks/PEEGEEQ_ONSUCCESS_AUDIT_DEFINITIVE_2026_05_14.md}
 * (and the original {@code PEEGEEQ_REFACTOR_ONSUCESS_EXCEPTION_SWALLOWING.md}).</p>
 */
@Tag(TestCategories.CORE)
class OnSuccessExceptionSwallowingGuardTest {

    private static final Pattern ON_SUCCESS_START =
            Pattern.compile("\\.onSuccess\\s*\\(");
    private static final Pattern ASSERTION_OR_FAIL =
            Pattern.compile("\\b(?:assert[A-Z]\\w*|fail)\\s*\\(");
    /** Matches a top-level {@code .close()} or {@code .close(...)} call — Tier 2 risk. */
    private static final Pattern CLOSE_CALL =
            Pattern.compile("\\.close\\s*\\(");
    /** Matches any {@code <ident>.verify(} — variable name varies ({@code testContext}, {@code ctx}, …). */
    private static final Pattern VERIFY_CALL =
            Pattern.compile("\\b[A-Za-z_$][A-Za-z0-9_$]*\\.verify\\s*\\(");
    /** Matches {@code try {} — used to strip {@code try/catch(Throwable)/failNow} shields. */
    private static final Pattern TRY_BLOCK_START =
            Pattern.compile("\\btry\\s*\\{");

    @Test
    void noTier3OnSuccessExceptionSwallowingInTestSources() throws IOException {
        Path workspaceRoot = locateWorkspaceRoot();
        List<Violation> violations = new ArrayList<>();

        try (Stream<Path> modules = Files.list(workspaceRoot)) {
            List<Path> testRoots = modules
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("peegeeq-"))
                    .map(p -> p.resolve("src").resolve("test").resolve("java"))
                    .filter(Files::isDirectory)
                    .toList();

            for (Path testRoot : testRoots) {
                scanModule(testRoot, violations);
            }
        }

        if (!violations.isEmpty()) {
            long tier3 = violations.stream().filter(v -> v.tier == 3).count();
            long tier2 = violations.stream().filter(v -> v.tier == 2).count();
            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(violations.size())
              .append(" .onSuccess(...) block(s) with exception-swallowing risk")
              .append(" (Tier 3 = ").append(tier3)
              .append(", Tier 2 = ").append(tier2).append(").\n")
              .append("Tier 3: bare JUnit assertions outside testContext.verify(...) — sync\n")
              .append("        throw routes to Vertx.exceptionHandler and the test times out.\n")
              .append("Tier 2: synchronous .close() call outside testContext.verify(...) — if\n")
              .append("        close() throws, the exception is silently swallowed.\n")
              .append("Fix: wrap the onSuccess body in testContext.verify(() -> { ... }), or\n")
              .append("use .onComplete(testContext.succeeding(v -> testContext.verify(() -> { ... }))).\n")
              .append("See docs-design/tasks/PEEGEEQ_ONSUCCESS_AUDIT_DEFINITIVE_2026_05_14.md\n\n");
            for (Violation v : violations) {
                sb.append("  [Tier ").append(v.tier).append("] ")
                  .append(v.file).append(":").append(v.line)
                  .append(" — ").append(v.snippet).append('\n');
            }
            fail(sb.toString());
        }
    }

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

    private static void scanModule(Path testRoot, List<Violation> violations) throws IOException {
        try (Stream<Path> files = Files.walk(testRoot)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.getFileName().toString().endsWith(".java"))
                 .forEach(p -> scanFile(p, violations));
        }
    }

    private static void scanFile(Path file, List<Violation> violations) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        // Mask comments and string literals before the initial finder so this guard
        // does not match `.onSuccess(...)` examples that appear inside Javadoc or
        // string content. The masked string preserves length and line breaks, so
        // every offset returned by the matcher is still valid against `content`.
        String masked = maskNonCode(content);
        Matcher m = ON_SUCCESS_START.matcher(masked);
        while (m.find()) {
            int openParen = m.end() - 1;
            int arrow = findLambdaArrow(content, openParen);
            if (arrow < 0) continue;
            int afterArrow = skipWhitespace(content, arrow + 2);
            if (afterArrow >= content.length() || content.charAt(afterArrow) != '{') {
                // Expression lambda — Tier 1 form; not a Tier 2/3 risk for this check.
                continue;
            }
            int bodyEnd = findMatchingBrace(content, afterArrow);
            if (bodyEnd < 0) continue;
            String body = content.substring(afterArrow + 1, bodyEnd);
            String stripped = stripVerifyCalls(body);
            stripped = stripTryCatchFailNow(stripped);
            // Tier 3: bare assertion in onSuccess body
            if (ASSERTION_OR_FAIL.matcher(stripped).find()) {
                int line = lineOf(content, m.start());
                String snippet = snippet(content, m.start());
                violations.add(new Violation(file.toString(), line, snippet, 3));
                continue;
            }
            // Tier 2: synchronous .close() call at top level of onSuccess body
            // (not inside a nested lambda such as setTimer/setPeriodic/forEach callback).
            String topLevel = stripNestedBraceBlocks(stripped);
            if (CLOSE_CALL.matcher(topLevel).find()) {
                int line = lineOf(content, m.start());
                String snippet = snippet(content, m.start());
                violations.add(new Violation(file.toString(), line, snippet, 2));
            }
        }
    }

    /**
     * Remove every balanced {@code { ... }} block from {@code text}. Used to strip
     * nested lambda bodies (e.g. {@code setTimer(d, id -> { ... })}) so that Tier 2
     * detection only flags {@code .close()} calls at the top level of the onSuccess body.
     */
    private static String stripNestedBraceBlocks(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '{') {
                int close = findMatchingBrace(text, i);
                if (close < 0) break;
                i = close + 1;
                continue;
            }
            if (c == '"' || c == '\'') {
                int end = skipStringLiteral(text, i);
                sb.append(text, i, Math.min(end + 1, text.length()));
                i = end + 1;
                continue;
            }
            if (c == '/' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == '/') { i = skipLineComment(text, i) + 1; continue; }
                if (next == '*') { i = skipBlockComment(text, i) + 1; continue; }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /**
     * Find the {@code ->} that terminates the lambda parameter list whose opening
     * paren is at {@code openParen}. Returns -1 if not a lambda.
     */
    private static int findLambdaArrow(String content, int openParen) {
        int depth = 0;
        for (int i = openParen; i < content.length() - 1; i++) {
            char c = content.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) {
                    // After the param list we may see "->" — but a method-reference
                    // arg like ".onSuccess(this::handle)" has depth back to 0 here
                    // without an arrow. Look for the arrow strictly between openParen
                    // and the matching close — we already passed it. Re-scan inside.
                    return findArrowInside(content, openParen, i);
                }
            } else if (c == '"' || c == '\'') {
                i = skipStringLiteral(content, i);
            } else if (c == '/' && i + 1 < content.length()) {
                char next = content.charAt(i + 1);
                if (next == '/') i = skipLineComment(content, i);
                else if (next == '*') i = skipBlockComment(content, i);
            }
        }
        return -1;
    }

    private static int findArrowInside(String content, int openParen, int closeParen) {
        // Search for "->" at depth 1 (inside this .onSuccess(...) call but outside nested parens).
        int depth = 0;
        for (int i = openParen; i < closeParen; i++) {
            char c = content.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == '"' || c == '\'') {
                i = skipStringLiteral(content, i);
            } else if (c == '/' && i + 1 < content.length()) {
                char next = content.charAt(i + 1);
                if (next == '/') i = skipLineComment(content, i);
                else if (next == '*') i = skipBlockComment(content, i);
            } else if (depth == 1 && c == '-' && i + 1 < content.length()
                    && content.charAt(i + 1) == '>') {
                return i;
            }
        }
        return -1;
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static int findMatchingBrace(String content, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            } else if (c == '"' || c == '\'') {
                i = skipStringLiteral(content, i);
            } else if (c == '/' && i + 1 < content.length()) {
                char next = content.charAt(i + 1);
                if (next == '/') i = skipLineComment(content, i);
                else if (next == '*') i = skipBlockComment(content, i);
            }
        }
        return -1;
    }

    /**
     * Return a copy of {@code content} with every comment (line or block) and every
     * string/char literal (including text blocks) replaced by spaces, preserving length
     * and line breaks. Used to prevent the initial {@code .onSuccess(} finder from
     * matching tokens inside Javadoc examples or string content.
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
        // Java text blocks """..."""
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
            if (c == '\n') return j; // unterminated; bail
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

    /**
     * Remove every {@code <ident>.verify(...)} balanced call from {@code text}.
     * Used to mask verify bodies before scanning for naked assertions.
     */
    private static String stripVerifyCalls(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        Matcher m = VERIFY_CALL.matcher(text);
        int last = 0;
        while (m.find(last)) {
            sb.append(text, last, m.start());
            int openParenIdx = m.end() - 1;
            int closeParen = findMatchingParen(text, openParenIdx);
            if (closeParen < 0) {
                // Unbalanced — preserve from here on out.
                sb.append(text, m.start(), text.length());
                return sb.toString();
            }
            last = closeParen + 1;
        }
        sb.append(text, last, text.length());
        return sb.toString();
    }

    /**
     * Remove {@code try { ... } catch (Throwable|Exception ...) { ... }} shields from
     * {@code text} whose catch body does not re-{@code throw}. Such blocks contain any
     * synchronous exception (whether routed to {@code failNow}, logged via
     * {@code logger.warn}, or silently ignored), so calls inside them — including
     * {@code .close()} — cannot escape and are safe for Tier 2/3 purposes.
     */
    private static String stripTryCatchFailNow(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        Matcher m = TRY_BLOCK_START.matcher(text);
        int last = 0;
        while (m.find(last)) {
            int tryBraceOpen = m.end() - 1;
            int tryBraceClose = findMatchingBrace(text, tryBraceOpen);
            if (tryBraceClose < 0) break;
            // Look for `catch (...)` after the try block.
            int i = tryBraceClose + 1;
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
            if (i + 5 < text.length() && text.startsWith("catch", i)) {
                int catchParenOpen = text.indexOf('(', i);
                if (catchParenOpen < 0) { last = m.end(); continue; }
                int catchParenClose = findMatchingParen(text, catchParenOpen);
                if (catchParenClose < 0) { last = m.end(); continue; }
                String catchDecl = text.substring(catchParenOpen + 1, catchParenClose);
                int catchBraceOpen = text.indexOf('{', catchParenClose);
                if (catchBraceOpen < 0) { last = m.end(); continue; }
                int catchBraceClose = findMatchingBrace(text, catchBraceOpen);
                if (catchBraceClose < 0) { last = m.end(); continue; }
                String catchBody = text.substring(catchBraceOpen + 1, catchBraceClose);
                boolean isThrowable = catchDecl.contains("Throwable")
                        || catchDecl.contains("Exception");
                boolean rethrows = catchBody.contains("throw ")
                        || catchBody.contains("throw\n");
                if (isThrowable && !rethrows) {
                    sb.append(text, last, m.start());
                    // Skip the whole try/catch block.
                    last = catchBraceClose + 1;
                    continue;
                }
            }
            last = m.end();
        }
        sb.append(text, last, text.length());
        return sb.toString();
    }

    private static int findMatchingParen(String content, int openParen) {
        int depth = 0;
        for (int i = openParen; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            } else if (c == '"' || c == '\'') {
                i = skipStringLiteral(content, i);
            } else if (c == '/' && i + 1 < content.length()) {
                char next = content.charAt(i + 1);
                if (next == '/') i = skipLineComment(content, i);
                else if (next == '*') i = skipBlockComment(content, i);
            }
        }
        return -1;
    }

    private static int lineOf(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static String snippet(String content, int offset) {
        int start = offset;
        while (start > 0 && content.charAt(start - 1) != '\n') start--;
        int end = offset;
        while (end < content.length() && content.charAt(end) != '\n') end++;
        return content.substring(start, end).trim();
    }

    private record Violation(String file, int line, String snippet, int tier) {
    }
}
