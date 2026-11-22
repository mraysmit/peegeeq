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
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Prevents every test in the repository from regressing to hand-built PostgreSQL containers,
 * container images named in the test, or raw JDBC instead of the test-support container
 * factories, the test-support image constants, and Vert.x PgClient.
 *
 * <p>The scan covers the {@code src/test/java} tree of every top-level module, so a new module
 * is covered without changing this class. The migration tests let Flyway connect through JDBC,
 * which is the behaviour under test, but import no JDBC types themselves.</p>
 */
@Tag(TestCategories.CORE)
class SchemaInitializerTestInfrastructureGuardTest {

    private static final Pattern RAW_JDBC_IMPORT = Pattern.compile(
        "(?m)^import\\s+java\\.sql\\.(\\*|Connection|DriverManager|PreparedStatement|ResultSet|Statement)\\s*;");
    private static final Pattern HAND_BUILT_CONTAINER = Pattern.compile(
        "new\\s+PostgreSQLContainer(?:<[^>]*>)?\\s*\\(");
    /**
     * A constructor or a String constant whose first value is a string literal. {@link #inspect}
     * reports it when the class is a container or the constant is an image. Every image name lives
     * in {@code PostgreSQLTestConstants.POSTGRES_IMAGE} or in {@code TestContainerImages}, so a
     * version is changed in one place.
     */
    private static final Pattern CONSTRUCTED_FROM_LITERAL = Pattern.compile(
        "new\\s++(\\w++)(?:<[^>]*+>)?\\s*+\\(\\s*+\"");
    private static final Pattern STRING_CONSTANT_LITERAL = Pattern.compile(
        "\\bString\\s++(\\w++)\\s*+=\\s*+\"");
    private static final Pattern IMAGE_NAME_PARSED_FROM_LITERAL = Pattern.compile(
        "DockerImageName\\.parse\\(\\s*+\"");
    /** A {@code @Container} field of type PostgreSQLContainer; other container types are not checked. */
    private static final Pattern POSTGRES_CONTAINER_FIELD = Pattern.compile(
        "@Container\\s+(?:(?:private|protected|public|static|final)\\s+)*PostgreSQLContainer\\b");
    /**
     * The test-support sources of a PostgreSQL container. Each builds on
     * {@code PostgreSQLTestConstants.POSTGRES_IMAGE}; the hand-built rule checks every other class.
     */
    private static final List<String> STANDARD_CONTAINER_SOURCES = List.of(
        "PostgreSQLTestConstants.createStandardContainer()",
        "PostgreSQLTestConstants.createContainer(",
        "PostgreSQLTestConstants.createHighPerformanceContainer(",
        "PeeGeeQTestContainerFactory.createContainer(",
        "import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.createContainer;",
        "SharedTestContainers.getSharedPostgreSQLContainer()");

    /**
     * Test classes allowed to keep raw JDBC, keyed by path from the workspace root, with the
     * reason. An entry that no longer imports JDBC fails the scan, so it is removed when the
     * class is fixed.
     */
    private static final Map<String, String> RAW_JDBC_EXCEPTIONS = Map.of(
        "peegeeq-db/src/test/java/dev/mars/peegeeq/db/SharedPostgresTestExtension.java",
        "builds the peegeeq-db test schema by hand over JDBC; PEEGEEQ_DELIVERY_OUTCOME_REDESIGN_PROPOSAL.md "
            + "Task 9 moves it to the Flyway test path");

    @Test
    void everyTestTreeUsesStandardContainersAndReactiveAccess() throws IOException {
        Path workspaceRoot = locateWorkspaceRoot();
        List<String> violations = new ArrayList<>();
        int scanned = 0;

        List<Path> testTrees;
        try (Stream<Path> modules = Files.list(workspaceRoot)) {
            testTrees = modules
                .map(module -> module.resolve("src/test/java"))
                .filter(Files::isDirectory)
                .sorted()
                .toList();
        }
        for (Path testTree : testTrees) {
            try (Stream<Path> files = Files.walk(testTree)) {
                for (Path path : files
                        .filter(file -> file.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .toList()) {
                    inspect(workspaceRoot, path, violations);
                    scanned++;
                }
            }
        }

        for (String exception : RAW_JDBC_EXCEPTIONS.keySet()) {
            Path path = workspaceRoot.resolve(exception);
            if (!Files.isRegularFile(path)
                    || !RAW_JDBC_IMPORT.matcher(Files.readString(path, StandardCharsets.UTF_8)).find()) {
                violations.add(exception + " is listed as a raw JDBC exception but no longer imports JDBC;"
                    + " remove the entry");
            }
        }

        if (testTrees.size() < 2 || scanned == 0) {
            fail("The scan found " + testTrees.size() + " test trees and " + scanned
                + " test classes under " + workspaceRoot);
        }
        if (!violations.isEmpty()) {
            fail("Test infrastructure violations:\n  " + String.join("\n  ", violations));
        }
    }

    private static void inspect(Path workspaceRoot, Path path, List<String> violations) throws IOException {
        String source = Files.readString(path, StandardCharsets.UTF_8);
        String relative = workspaceRoot.relativize(path).toString().replace('\\', '/');

        if (RAW_JDBC_IMPORT.matcher(source).find() && !RAW_JDBC_EXCEPTIONS.containsKey(relative)) {
            violations.add(relative + " imports raw JDBC types");
        }
        if (HAND_BUILT_CONTAINER.matcher(source).find()) {
            violations.add(relative + " constructs a PostgreSQLContainer directly");
        }
        if (CONSTRUCTED_FROM_LITERAL.matcher(source).results()
                    .anyMatch(match -> match.group(1).endsWith("Container"))
                || STRING_CONSTANT_LITERAL.matcher(source).results()
                    .anyMatch(match -> match.group(1).contains("IMAGE"))
                || IMAGE_NAME_PARSED_FROM_LITERAL.matcher(source).find()) {
            violations.add(relative + " names a container image in the test instead of using"
                + " PostgreSQLTestConstants or TestContainerImages");
        }
        if (POSTGRES_CONTAINER_FIELD.matcher(source).find()
                && STANDARD_CONTAINER_SOURCES.stream().noneMatch(source::contains)) {
            violations.add(relative + " has a @Container PostgreSQLContainer field that does not come"
                + " from a test-support container factory");
        }
    }

    private static Path locateWorkspaceRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            if (Files.isDirectory(candidate.resolve("peegeeq-test-support"))
                    && Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate;
            }
            Path parent = candidate.getParent();
            if (parent == null) {
                break;
            }
            candidate = parent;
        }
        throw new IllegalStateException("Could not locate workspace root");
    }
}
