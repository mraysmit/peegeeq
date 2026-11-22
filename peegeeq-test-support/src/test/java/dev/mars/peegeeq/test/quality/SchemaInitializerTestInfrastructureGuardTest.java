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
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Prevents schema-initializer tests, migration tests and example tests from regressing to
 * hand-built PostgreSQL containers or raw JDBC verification instead of the standard container
 * and Vert.x PgClient.
 */
@Tag(TestCategories.CORE)
class SchemaInitializerTestInfrastructureGuardTest {

    private static final Pattern RAW_JDBC_IMPORT = Pattern.compile(
        "(?m)^import\\s+java\\.sql\\.(\\*|Connection|DriverManager|PreparedStatement|ResultSet|Statement)\\s*;");
    private static final Pattern HAND_BUILT_CONTAINER = Pattern.compile(
        "new\\s+PostgreSQLContainer(?:<[^>]*>)?\\s*\\(");
    /** A {@code @Container} field of type PostgreSQLContainer; other container types are not checked. */
    private static final Pattern POSTGRES_CONTAINER_FIELD = Pattern.compile(
        "@Container\\s+(?:(?:private|protected|public|static|final)\\s+)*PostgreSQLContainer\\b");

    /**
     * The example tests, in {@code peegeeq-examples} and in the standalone
     * {@code peegeeq-examples-spring} project, take their PostgreSQL container from the
     * standard factory, directly or through their SharedTestContainers, and never build one
     * by hand.
     */
    @Test
    void exampleTestsUseStandardContainers() throws IOException {
        Path workspaceRoot = locateWorkspaceRoot();
        List<String> violations = new ArrayList<>();

        for (String root : List.of("peegeeq-examples/src/test/java", "peegeeq-examples-spring/src/test/java")) {
            try (Stream<Path> files = Files.walk(workspaceRoot.resolve(root))) {
                for (Path path : files
                        .filter(file -> file.getFileName().toString().endsWith(".java"))
                        .toList()) {
                    inspect(path, violations);
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("Example test infrastructure violations:\n  "
                + String.join("\n  ", violations));
        }
    }

    @Test
    void schemaInitializerTestsUseStandardReactiveInfrastructure() throws IOException {
        Path workspaceRoot = locateWorkspaceRoot();
        Path schemaTests = workspaceRoot.resolve(
            "peegeeq-test-support/src/test/java/dev/mars/peegeeq/test/schema");
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.list(schemaTests)) {
            for (Path path : files
                    .filter(file -> file.getFileName().toString().endsWith("Test.java"))
                    .toList()) {
                inspect(path, violations);
            }
        }

        if (!violations.isEmpty()) {
            fail("Schema-initializer test infrastructure violations:\n  "
                + String.join("\n  ", violations));
        }
    }

    /**
     * The migration tests let Flyway connect through JDBC, which is the behaviour under test,
     * but verify the resulting schema through the standard container and a Vert.x pool.
     */
    @Test
    void migrationTestsUseStandardReactiveInfrastructure() throws IOException {
        Path workspaceRoot = locateWorkspaceRoot();
        Path migrationTests = workspaceRoot.resolve(
            "peegeeq-migrations/src/test/java/dev/mars/peegeeq/migrations");
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.list(migrationTests)) {
            for (Path path : files
                    .filter(file -> file.getFileName().toString().endsWith("Test.java"))
                    .toList()) {
                inspect(path, violations);
            }
        }

        if (!violations.isEmpty()) {
            fail("Migration test infrastructure violations:\n  "
                + String.join("\n  ", violations));
        }
    }

    private static void inspect(Path path, List<String> violations) throws IOException {
        String source = Files.readString(path, StandardCharsets.UTF_8);

        String fileName = path.getFileName().toString();
        if (RAW_JDBC_IMPORT.matcher(source).find()) {
            violations.add(fileName + " imports raw JDBC verification types");
        }
        if (HAND_BUILT_CONTAINER.matcher(source).find()) {
            violations.add(fileName + " constructs a PostgreSQLContainer directly");
        }
        // SharedTestContainers builds on createStandardContainer(); the hand-built rule above
        // checks that class itself.
        if (POSTGRES_CONTAINER_FIELD.matcher(source).find()
                && !source.contains("PostgreSQLTestConstants.createStandardContainer()")
                && !source.contains("SharedTestContainers.getSharedPostgreSQLContainer()")) {
            violations.add(fileName + " does not use createStandardContainer()");
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
