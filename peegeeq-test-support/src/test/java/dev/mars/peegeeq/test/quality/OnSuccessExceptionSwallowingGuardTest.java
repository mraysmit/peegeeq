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
 * CI guard test: detects multiple async test antipatterns that silently swallow failures,
 * deadlock tests, block event loops, or discard futures in the PeeGeeQ test suite.
 *
 * <p>This test scans every {@code peegeeq-*\/src/test/java/**\/*.java} file in the workspace.
 * All checks run in a single {@code mvn test} pass (~0.3 s, no database, no Testcontainers).
 * Any violation fails the build with a precise {@code file:line — snippet} report.</p>
 *
 * <h2>Antipattern tiers</h2>
 *
 * <h3>Original tiers (onSuccess exception swallowing)</h3>
 * <ul>
 *   <li><b>Tier 3</b>: bare JUnit assertions ({@code assertX}, {@code fail}) inside
 *       {@code .onSuccess(v -> { ... })} outside {@code testContext.verify(...)}. A synchronous
 *       throw routes to {@code Vertx.exceptionHandler}; the test times out at 30 s with no
 *       root cause logged.</li>
 *   <li><b>Tier 2</b>: synchronous {@code .close()} call at the top level of an
 *       {@code .onSuccess(v -> { ... })} body, outside {@code testContext.verify(...)}. If
 *       {@code close()} throws the exception is silently swallowed.</li>
 * </ul>
 *
 * <h3>New tiers (additional antipatterns)</h3>
 * <ul>
 *   <li><b>Tier 4 — {@code Future.await()} in test code</b>: {@code .await()} on any
 *       {@code Future} in a test file. {@code Future.await()} is a Vert.x 5 virtual-thread
 *       helper. On a JUnit platform thread it blocks until the future settles; if settling
 *       requires the Vert.x event loop to dispatch work the test hangs <em>indefinitely</em>
 *       with no timeout and no error. Legitimate {@code await*} idioms
 *       ({@code testContext.awaitCompletion}, {@code CountDownLatch.await},
 *       {@code CyclicBarrier.await}) are explicitly whitelisted.</li>
 *
 *   <li><b>Tier 5 — blocking thread delays</b>: {@code Thread.sleep(} or
 *       {@code LockSupport.parkNanos(} in any test file. Both block the calling thread for a
 *       fixed duration, defeating the reactive model, causing flakiness on slow CI, and
 *       potentially blocking the Vert.x event loop if called from an event-loop thread. The
 *       correct alternative is to chain off the {@code Future} the operation returns, or
 *       observe a side-effect (database row, checkpoint flag) directly.</li>
 *
 *   <li><b>Tier 6 — {@code .onComplete(ar -> singleCountdown())} swallowing failures</b>:
 *       {@code .onComplete()} with a single-statement lambda body that contains only a
 *       countdown/increment operation ({@code countDown()}, {@code getAndIncrement()},
 *       {@code getAndAdd()}, {@code release()}, {@code tryComplete()}) and no failure branch
 *       ({@code if (ar.failed())}, {@code ar.cause()}, {@code failNow}, {@code onFailure}).
 *       {@code onComplete} fires on both success <em>and</em> failure; a failed future
 *       decrements the latch normally and the test proceeds with preconditions unmet.</li>
 *
 *   <li><b>Tier 7 — discarded {@code Future} from {@code stop()}/{@code close()}</b>:
 *       a call to {@code .stop()} or {@code .close()} (with or without arguments) whose
 *       returned {@code Future} is immediately discarded — i.e. the call is followed by
 *       {@code ;} with no {@code .compose}, {@code .onSuccess}, {@code .onFailure},
 *       {@code .onComplete}, or {@code .eventually()} chain. Such calls return immediately
 *       while cleanup may still be in-flight, causing subsequent assertions to race against
 *       the in-progress shutdown.</li>
 * </ul>
 *
 * <h2>Safe constructs (not flagged)</h2>
 * <ul>
 *   <li>{@code testContext.awaitCompletion(timeout, unit)} — correct drain idiom.</li>
 *   <li>{@code CountDownLatch.await(timeout, unit)} / {@code CyclicBarrier.await()} —
 *       bounded blocking on a non-event-loop thread.</li>
 *   <li>{@code .onComplete(testContext.succeeding(...))} — canonical Vert.x JUnit5 form.</li>
 *   <li>Assertions inside {@code testContext.verify(...)} — exceptions are routed to
 *       {@code failNow} automatically.</li>
 *   <li>{@code try { ... } catch (Throwable|Exception e) { /* no rethrow *\/ }} shields —
 *       synchronous exceptions are contained.</li>
 *   <li>{@code .close()} / {@code .stop()} calls that are immediately chained
 *       ({@code .compose}, {@code .onSuccess}, {@code .onFailure}, {@code .onComplete},
 *       {@code .eventually()}) — the returned Future is consumed.</li>
 * </ul>
 *
 * <p>Documented in {@code docs-design/tasks/PEEGEEQ_ONSUCCESS_AUDIT_DEFINITIVE_2026_05_14.md}.</p>
 */
@Tag(TestCategories.CORE)
class OnSuccessExceptionSwallowingGuardTest {

    // -------------------------------------------------------------------------
    // Patterns — Tier 2/3 (original onSuccess swallowing)
    // -------------------------------------------------------------------------

    private static final Pattern ON_SUCCESS_START =
            Pattern.compile("\\.onSuccess\\s*\\(");
    private static final Pattern ASSERTION_OR_FAIL =
            Pattern.compile("\\b(?:assert[A-Z]\\w*|fail)\\s*\\(");
    private static final Pattern CLOSE_CALL =
            Pattern.compile("\\.close\\s*\\(");
    private static final Pattern VERIFY_CALL =
            Pattern.compile("\\b[A-Za-z_$][A-Za-z0-9_$]*\\.verify\\s*\\(");
    private static final Pattern TRY_BLOCK_START =
            Pattern.compile("\\btry\\s*\\{");
    private static final Pattern DEMONSTRATION_TAG =
            Pattern.compile("@Tag\\s*\\(\\s*\"demonstration\"\\s*\\)");

    // -------------------------------------------------------------------------
    // Patterns — Tier 4: Future.await() banned in test code
    //
    // Matches any `.await()` call that is NOT one of the three whitelisted forms:
    //   - testContext.awaitCompletion(...)
    //   - <ident>.await(timeout, unit)      CountDownLatch bounded form
    //   - <ident>.await()                   CyclicBarrier (no-arg)
    //
    // Strategy: find all `.await(` occurrences, then rule out the safe ones by
    // inspecting what precedes the dot. A bare `.await()` on a Future has no
    // recognisable safe prefix — it is flagged.
    // -------------------------------------------------------------------------

    /**
     * Matches any {@code .await(} token in source (after comment/string masking).
     * The guard then inspects the preceding identifier to whitelist safe forms.
     */
    private static final Pattern AWAIT_CALL =
            Pattern.compile("\\.await\\s*\\(");

    /**
     * Whitelisted prefixes that make {@code .await(...)} safe:
     * {@code awaitCompletion(} (VertxTestContext drain) is an entirely different method
     * name and never matches {@code .await(} so it needs no special handling.
     * What we must whitelist is {@code CountDownLatch.await(timeout,...)} and
     * {@code CyclicBarrier.await()} — both take arguments or are the well-known
     * bounded/barrier idioms.
     *
     * <p>Detection rule: flag {@code .await()} (zero-arg) unconditionally — that is
     * always {@code Future.await()}. For {@code .await(} with arguments, flag unless
     * the argument list contains a {@code TimeUnit} reference (i.e. bounded latch).</p>
     */
    // Matches TimeUnit enum values inside the argument list — signals a bounded latch await.
    private static final Pattern TIME_UNIT_ARG =
            Pattern.compile("\\bTimeUnit\\.");

    // -------------------------------------------------------------------------
    // Patterns — Tier 5: Thread.sleep / LockSupport.parkNanos banned
    // -------------------------------------------------------------------------

    private static final Pattern THREAD_SLEEP =
            Pattern.compile("\\bThread\\.sleep\\s*\\(");
    private static final Pattern LOCK_SUPPORT_PARK =
            Pattern.compile("\\bLockSupport\\.parkNanos\\s*\\(");

    // -------------------------------------------------------------------------
    // Patterns — Tier 6: .onComplete(ar -> singleCountdown()) swallowing failures
    //
    // Detection:
    //   1. Find .onComplete( calls.
    //   2. Extract the lambda body (block or expression).
    //   3. Check the body contains a countdown/signal operation.
    //   4. Check the body has NO failure-awareness tokens.
    //   5. If both true: violation.
    // -------------------------------------------------------------------------

    private static final Pattern ON_COMPLETE_START =
            Pattern.compile("\\.onComplete\\s*\\(");

    /**
     * Countdown/signal operations that appear in the swallowing pattern — these
     * operations fire regardless of success or failure, making them the signal that
     * the lambda is not failure-aware.
     */
    private static final Pattern COUNTDOWN_OP =
            Pattern.compile("\\b(?:countDown|getAndIncrement|getAndAdd|release|tryComplete)\\s*\\(");

    /**
     * Tokens that indicate the lambda IS failure-aware. If any of these appear in
     * the lambda body, the site is NOT flagged.
     */
    private static final Pattern FAILURE_AWARENESS =
            Pattern.compile(
                "\\b(?:failed|cause|failNow|onFailure|succeeded)\\s*[\\(\\)]" +
                "|ar\\.(?:failed|succeeded|cause|result)\\s*\\(" +
                "|if\\s*\\(\\s*ar"
            );

    // -------------------------------------------------------------------------
    // Patterns — Tier 7: discarded Future from stop()/close()
    //
    // Detection:
    //   Find <receiver>.stop( or <receiver>.close( calls where:
    //     (1) the receiver identifier matches the project allowlist (its name
    //         contains "job", "manager", or "group" case-insensitively), AND
    //     (2) the next non-whitespace character after the closing paren is ';'
    //         with no Future-consuming chain token between them.
    //
    //   Rationale: stop()/close() is a generic name. Most call sites are on
    //   void-returning AutoCloseable types (producers, consumers, pools, vertx,
    //   JDBC resources, streams) where discarding the call is a no-op, not a
    //   bug. The Future<Void>-returning close/stop methods in this project all
    //   live on types whose conventional variable names contain job/manager/group
    //   (PeeGeeQManager, MultiConfigurationManager, DeadConsumerDetectionJob,
    //   OutboxConsumerGroup, PgNativeConsumerGroup, etc.). Restricting the
    //   receiver to that allowlist removes the vast majority of false positives
    //   while keeping the documented bug pattern in scope.
    //
    //   Chain tokens that consume the returned Future:
    //     .compose(  .onSuccess(  .onFailure(  .onComplete(  .eventually(
    //     .map(  .recover(  .transform(  .andThen(
    // -------------------------------------------------------------------------

    /**
     * Matches {@code <ident>.stop(} or {@code <ident>.close(} where group 1 is the
     * receiver identifier. The receiver is then matched against the allowlist below.
     */
    private static final Pattern STOP_OR_CLOSE_CALL =
            Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$]*)\\.(?:stop|close)\\s*\\(");

    /**
     * Substring tokens (case-insensitive) that, when found in the receiver
     * identifier, signal that the call is on a type whose {@code stop()} /
     * {@code close()} returns {@code Future<Void>} in this project.
     */
    private static final Pattern TIER_7_RECEIVER_ALLOWLIST =
            Pattern.compile("(?i)(?:job|manager|group)");

    /**
     * After the closing paren of stop()/close(), if the next non-whitespace token
     * starts with one of these, the Future is consumed — not a violation.
     */
    private static final Pattern FUTURE_CHAIN_CONTINUATION =
            Pattern.compile("^\\.(?:compose|onSuccess|onFailure|onComplete|eventually|map|recover|transform|andThen)\\s*\\(");

    // =========================================================================
    // Test methods
    // =========================================================================

    @Test
    void noTier3OnSuccessExceptionSwallowingInTestSources() throws IOException {
        runCheck(CheckType.TIER_2_3);
    }

    @Test
    void noFutureAwaitInTestSources() throws IOException {
        runCheck(CheckType.TIER_4_AWAIT);
    }

    @Test
    void noBlockingThreadDelaysInTestSources() throws IOException {
        runCheck(CheckType.TIER_5_SLEEP);
    }

    @Test
    void noOnCompleteSwallowingInTestSources() throws IOException {
        runCheck(CheckType.TIER_6_ONCOMPLETE);
    }

    @Test
    void noDiscardedFuturesFromStopOrCloseInTestSources() throws IOException {
        runCheck(CheckType.TIER_7_DISCARD);
    }

    // =========================================================================
    // Dispatch
    // =========================================================================

    private enum CheckType {
        TIER_2_3, TIER_4_AWAIT, TIER_5_SLEEP, TIER_6_ONCOMPLETE, TIER_7_DISCARD
    }

    private static void runCheck(CheckType type) throws IOException {
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
                try (Stream<Path> files = Files.walk(testRoot)) {
                    files.filter(Files::isRegularFile)
                         .filter(p -> p.getFileName().toString().endsWith(".java"))
                         .forEach(p -> {
                             switch (type) {
                                 case TIER_2_3       -> scanOnSuccess(p, violations);
                                 case TIER_4_AWAIT   -> scanAwait(p, violations);
                                 case TIER_5_SLEEP   -> scanSleep(p, violations);
                                 case TIER_6_ONCOMPLETE -> scanOnComplete(p, violations);
                                 case TIER_7_DISCARD -> scanDiscardedFuture(p, violations);
                             }
                         });
                }
            }
        }

        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            appendHeader(sb, type, violations);
            for (Violation v : violations) {
                sb.append("  [Tier ").append(v.tier).append("] ")
                  .append(v.file).append(":").append(v.line)
                  .append(" — ").append(v.snippet).append('\n');
            }
            fail(sb.toString());
        }
    }

    private static void appendHeader(StringBuilder sb, CheckType type, List<Violation> violations) {
        switch (type) {
            case TIER_2_3 -> {
                long t3 = violations.stream().filter(v -> v.tier == 3).count();
                long t2 = violations.stream().filter(v -> v.tier == 2).count();
                sb.append("Found ").append(violations.size())
                  .append(" .onSuccess(...) block(s) with exception-swallowing risk")
                  .append(" (Tier 3 = ").append(t3).append(", Tier 2 = ").append(t2).append(").\n")
                  .append("Tier 3: bare JUnit assertions outside testContext.verify(...) —\n")
                  .append("        sync throw routes to Vertx.exceptionHandler; test times out at 30 s.\n")
                  .append("Tier 2: synchronous .close() outside testContext.verify(...) —\n")
                  .append("        if close() throws the exception is silently swallowed.\n")
                  .append("Fix: wrap the onSuccess body in testContext.verify(() -> { ... }),\n")
                  .append("     or use .onComplete(testContext.succeeding(v -> testContext.verify(() -> { ... }))).\n\n");
            }
            case TIER_4_AWAIT -> {
                sb.append("Found ").append(violations.size())
                  .append(" Future.await() call(s) in test code [Tier 4].\n")
                  .append("Future.await() is a Vert.x 5 virtual-thread helper. On a JUnit platform\n")
                  .append("thread it blocks until the future settles. If settling requires the Vert.x\n")
                  .append("event loop to dispatch work the test hangs indefinitely — no timeout, no error.\n")
                  .append("Fix: use VertxTestContext lifecycle hooks (.onSuccess/.onFailure + completeNow/failNow).\n")
                  .append("Allowed: testContext.awaitCompletion(timeout, unit), CountDownLatch.await(timeout, unit).\n\n");
            }
            case TIER_5_SLEEP -> {
                sb.append("Found ").append(violations.size())
                  .append(" blocking thread delay(s) in test code [Tier 5].\n")
                  .append("Thread.sleep() and LockSupport.parkNanos() block the calling thread for a\n")
                  .append("fixed duration. On a Vert.x event-loop thread this blocks the entire event loop.\n")
                  .append("Fixed delays are inherently flaky: too short on slow CI, wastefully long otherwise.\n")
                  .append("Fix: chain off the Future the operation returns, or observe the side-effect\n")
                  .append("     (database row, checkpoint flag) directly.\n\n");
            }
            case TIER_6_ONCOMPLETE -> {
                sb.append("Found ").append(violations.size())
                  .append(" .onComplete(ar -> countdown()) call(s) swallowing failures [Tier 6].\n")
                  .append(".onComplete fires on BOTH success and failure. A single-statement countdown\n")
                  .append("body with no failure branch means a failed future counts down the latch\n")
                  .append("normally and the test proceeds with its preconditions unmet.\n")
                  .append("Fix: replace with .onSuccess(v -> latch.countDown()).onFailure(testContext::failNow)\n")
                  .append("     or use a VertxTestContext checkpoint instead of a latch.\n\n");
            }
            case TIER_7_DISCARD -> {
                sb.append("Found ").append(violations.size())
                  .append(" discarded Future(s) from stop()/close() [Tier 7].\n")
                  .append("stop() and close() return Future<Void> that resolves only after cleanup\n")
                  .append("completes. Discarding the Future means shutdown is fire-and-forget;\n")
                  .append("subsequent assertions race against in-flight cleanup.\n")
                  .append("Fix: compose on the returned Future:\n")
                  .append("     .compose(v -> job.stop().compose(ignored -> nextStep()))\n\n");
            }
        }
    }

    // =========================================================================
    // Tier 2/3: onSuccess exception swallowing (original implementation)
    // =========================================================================

    private static void scanOnSuccess(Path file, List<Violation> violations) {
        String content = readFile(file);
        if (content == null) return;
        if (DEMONSTRATION_TAG.matcher(content).find()) return;

        String masked = maskNonCode(content);
        Matcher m = ON_SUCCESS_START.matcher(masked);
        while (m.find()) {
            int openParen = m.end() - 1;
            int arrow = findLambdaArrow(content, openParen);
            if (arrow < 0) continue;
            int afterArrow = skipWhitespace(content, arrow + 2);
            if (afterArrow >= content.length() || content.charAt(afterArrow) != '{') continue;
            int bodyEnd = findMatchingBrace(content, afterArrow);
            if (bodyEnd < 0) continue;
            String body = content.substring(afterArrow + 1, bodyEnd);
            String stripped = stripVerifyCalls(body);
            stripped = stripTryCatchFailNow(stripped);

            if (ASSERTION_OR_FAIL.matcher(stripped).find()) {
                violations.add(new Violation(file.toString(), lineOf(content, m.start()),
                        snippet(content, m.start()), 3));
                continue;
            }
            String topLevel = stripNestedBraceBlocks(stripped);
            if (CLOSE_CALL.matcher(topLevel).find()) {
                violations.add(new Violation(file.toString(), lineOf(content, m.start()),
                        snippet(content, m.start()), 2));
            }
        }
    }

    // =========================================================================
    // Tier 4: Future.await() banned in test code
    // =========================================================================

    private static void scanAwait(Path file, List<Violation> violations) {
        String content = readFile(file);
        if (content == null) return;
        if (DEMONSTRATION_TAG.matcher(content).find()) return;

        String masked = maskNonCode(content);
        Matcher m = AWAIT_CALL.matcher(masked);
        while (m.find()) {
            // Find the argument list of this .await( call
            int openParen = m.end() - 1; // points at '('
            int closeParen = findMatchingParen(content, openParen);
            if (closeParen < 0) continue;

            String args = content.substring(openParen + 1, closeParen).trim();

            // Whitelist 1: zero-arg .await() that is actually CyclicBarrier.await()
            // We identify this by looking at the identifier before the dot.
            // CyclicBarrier is the only legitimate zero-arg await — but we cannot
            // distinguish it from Future.await() by token alone, so zero-arg is always
            // flagged. CyclicBarrier tests should use .await(timeout, unit) form.

            // Whitelist 2: .await(timeout, TimeUnit.X) — bounded CountDownLatch form.
            // If args contain a TimeUnit reference, this is a bounded latch — safe.
            if (!args.isEmpty() && TIME_UNIT_ARG.matcher(args).find()) {
                continue; // bounded latch — safe
            }

            // Everything else: flagged (Future.await() zero-arg or with non-TimeUnit arg)
            violations.add(new Violation(file.toString(), lineOf(content, m.start()),
                    snippet(content, m.start()), 4));
        }
    }

    // =========================================================================
    // Tier 5: Thread.sleep / LockSupport.parkNanos banned
    // =========================================================================

    private static void scanSleep(Path file, List<Violation> violations) {
        String content = readFile(file);
        if (content == null) return;
        if (DEMONSTRATION_TAG.matcher(content).find()) return;

        String masked = maskNonCode(content);

        Matcher sleep = THREAD_SLEEP.matcher(masked);
        while (sleep.find()) {
            violations.add(new Violation(file.toString(), lineOf(content, sleep.start()),
                    snippet(content, sleep.start()), 5));
        }

        Matcher park = LOCK_SUPPORT_PARK.matcher(masked);
        while (park.find()) {
            violations.add(new Violation(file.toString(), lineOf(content, park.start()),
                    snippet(content, park.start()), 5));
        }
    }

    // =========================================================================
    // Tier 6: .onComplete(ar -> singleCountdown()) swallowing failures
    // =========================================================================

    private static void scanOnComplete(Path file, List<Violation> violations) {
        String content = readFile(file);
        if (content == null) return;
        if (DEMONSTRATION_TAG.matcher(content).find()) return;

        String masked = maskNonCode(content);
        Matcher m = ON_COMPLETE_START.matcher(masked);
        while (m.find()) {
            int openParen = m.end() - 1;

            // Find the lambda arrow inside this .onComplete( ) call
            int closeParen = findMatchingParen(content, openParen);
            if (closeParen < 0) continue;

            // Skip method references: .onComplete(testContext::failNow) etc.
            // These are always safe — we detect them by looking for '::' inside the call.
            String callContent = content.substring(openParen + 1, closeParen);
            if (callContent.contains("::")) continue;

            // Skip .onComplete(testContext.succeeding(...)) — canonical safe form
            if (callContent.contains(".succeeding(") || callContent.contains(".failing(")) continue;

            // Find the lambda arrow
            int arrow = findArrowInside(content, openParen, closeParen);
            if (arrow < 0) continue;

            int afterArrow = skipWhitespace(content, arrow + 2);
            if (afterArrow >= content.length()) continue;

            // Extract the lambda body — block or expression
            String body;
            if (content.charAt(afterArrow) == '{') {
                int bodyEnd = findMatchingBrace(content, afterArrow);
                if (bodyEnd < 0) continue;
                body = content.substring(afterArrow + 1, bodyEnd);
            } else {
                // Expression lambda: body runs to the closing paren of .onComplete(
                body = content.substring(afterArrow, closeParen);
            }

            // Must contain a countdown/signal operation to match the pattern
            if (!COUNTDOWN_OP.matcher(body).find()) continue;

            // Must NOT contain any failure-awareness token
            if (FAILURE_AWARENESS.matcher(body).find()) continue;

            violations.add(new Violation(file.toString(), lineOf(content, m.start()),
                    snippet(content, m.start()), 6));
        }
    }

    // =========================================================================
    // Tier 7: discarded Future from stop()/close()
    // =========================================================================

    private static void scanDiscardedFuture(Path file, List<Violation> violations) {
        String content = readFile(file);
        if (content == null) return;
        if (DEMONSTRATION_TAG.matcher(content).find()) return;

        String masked = maskNonCode(content);
        Matcher m = STOP_OR_CLOSE_CALL.matcher(masked);
        while (m.find()) {
            // Receiver-name allowlist: skip calls whose receiver identifier does
            // not contain job/manager/group. This filters out the dominant false-
            // positive sources (producer/consumer/pool/vertx/factory/rs/etc.).
            String receiver = m.group(1);
            if (!TIER_7_RECEIVER_ALLOWLIST.matcher(receiver).find()) continue;

            int openParen = m.end() - 1;
            int closeParen = findMatchingParen(content, openParen);
            if (closeParen < 0) continue;

            // Skip after the closing paren: find the next non-whitespace token
            int afterClose = closeParen + 1;
            afterClose = skipWhitespace(content, afterClose);
            if (afterClose >= content.length()) continue;

            // Extract a window of text after the close paren to check for chain tokens
            // (up to 60 chars is enough to catch any reasonable chain method name)
            int windowEnd = Math.min(afterClose + 60, content.length());
            String window = content.substring(afterClose, windowEnd);

            // If the next token starts a Future chain, the Future is consumed — not a violation
            if (FUTURE_CHAIN_CONTINUATION.matcher(window).find()) continue;

            // If the next non-whitespace char is ';' the Future is discarded — violation,
            // UNLESS the statement prefix shows the Future is captured (assignment) or
            // handed back to the caller (return). Both consume the Future safely.
            if (window.isEmpty()) continue;
            char next = window.charAt(0);
            if (next == ';') {
                String prefix = statementPrefix(masked, m.start());
                if (isAssignedOrReturned(prefix)) continue;
                violations.add(new Violation(file.toString(), lineOf(content, m.start()),
                        snippet(content, m.start()), 7));
            }
            // Any other character (e.g. ',' inside an argument list, ')' closing an
            // outer call) is context we cannot reliably classify — skip conservatively
            // to avoid false positives.
        }
    }

    /**
     * Walks backwards in {@code masked} from {@code matchStart} to the nearest
     * statement boundary ({@code ;}, {@code {}, or {@code }}) and returns the
     * intervening text. The result is the statement prefix in front of the
     * stop()/close() call — used to detect assignment or return.
     */
    private static String statementPrefix(String masked, int matchStart) {
        int i = matchStart - 1;
        while (i >= 0) {
            char c = masked.charAt(i);
            if (c == ';' || c == '{' || c == '}') break;
            i--;
        }
        return masked.substring(i + 1, matchStart);
    }

    /**
     * Returns true if the statement prefix shows the Future is captured by an
     * assignment ({@code =}, but not {@code ==} or compound operators like
     * {@code +=}) or handed back via {@code return}. Both forms consume the
     * Future and are not Tier 7 violations.
     */
    private static boolean isAssignedOrReturned(String prefix) {
        // 'return' keyword consumes the Future — must be a whole word, not e.g.
        // identifier "returnValue".
        int rIdx = prefix.indexOf("return");
        while (rIdx >= 0) {
            char before = rIdx == 0 ? ' ' : prefix.charAt(rIdx - 1);
            int afterIdx = rIdx + "return".length();
            char after = afterIdx >= prefix.length() ? ' ' : prefix.charAt(afterIdx);
            if (!Character.isJavaIdentifierPart(before) && !Character.isJavaIdentifierPart(after)) {
                return true;
            }
            rIdx = prefix.indexOf("return", rIdx + 1);
        }
        // Plain assignment: '=' not part of '==' or compound operator.
        for (int i = 0; i < prefix.length(); i++) {
            if (prefix.charAt(i) != '=') continue;
            char prev = i > 0 ? prefix.charAt(i - 1) : ' ';
            char next = i + 1 < prefix.length() ? prefix.charAt(i + 1) : ' ';
            if (next == '=') continue; // '=='
            switch (prev) {
                case '=': case '!': case '<': case '>':
                case '+': case '-': case '*': case '/':
                case '%': case '&': case '|': case '^':
                    continue; // compound or comparison operator
                default:
                    return true;
            }
        }
        return false;
    }

    // =========================================================================
    // Shared tokeniser utilities (unchanged from original)
    // =========================================================================

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

    private static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static int findLambdaArrow(String content, int openParen) {
        int depth = 0;
        for (int i = openParen; i < content.length() - 1; i++) {
            char c = content.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return findArrowInside(content, openParen, i);
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

    private static String stripVerifyCalls(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        Matcher m = VERIFY_CALL.matcher(text);
        int last = 0;
        while (m.find(last)) {
            sb.append(text, last, m.start());
            int openParenIdx = m.end() - 1;
            int closeParen = findMatchingParen(text, openParenIdx);
            if (closeParen < 0) {
                sb.append(text, m.start(), text.length());
                return sb.toString();
            }
            last = closeParen + 1;
        }
        sb.append(text, last, text.length());
        return sb.toString();
    }

    private static String stripTryCatchFailNow(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        Matcher m = TRY_BLOCK_START.matcher(text);
        int last = 0;
        while (m.find(last)) {
            int tryBraceOpen = m.end() - 1;
            int tryBraceClose = findMatchingBrace(text, tryBraceOpen);
            if (tryBraceClose < 0) break;
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
                    last = catchBraceClose + 1;
                    continue;
                }
            }
            last = m.end();
        }
        sb.append(text, last, text.length());
        return sb.toString();
    }

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