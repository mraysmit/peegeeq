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
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Repository guard that rejects millisecond-suffixed ISO-8601 duration literals.
 *
 * <p>{@link java.time.Duration#parse(CharSequence)} represents milliseconds as a
 * fractional number of seconds. A minutes-plus-seconds-looking suffix is rejected
 * and causes {@code PeeGeeQConfiguration} to fall back to its default value. This
 * guard covers Java sources, configuration resources and documentation examples.
 */
@Tag(TestCategories.CORE)
class InvalidDurationLiteralGuardTest {

    private static final Pattern INVALID_MILLISECOND_SUFFIX =
            Pattern.compile("(?i)\\bPT\\d+(?:\\.\\d+)?MS\\b");
    private static final Set<String> SCANNED_EXTENSIONS = Set.of(
            ".java", ".properties", ".yaml", ".yml", ".json", ".xml", ".conf", ".md");

    @Test
    void repositoryContainsNoMillisecondSuffixedDurationLiterals() throws IOException {
        Path workspaceRoot = locateWorkspaceRoot();
        List<String> violations = scanWorkspace(workspaceRoot);

        if (!violations.isEmpty()) {
            StringBuilder message = new StringBuilder()
                    .append("Invalid ISO-8601 duration literals found. ")
                    .append("Express milliseconds as fractional seconds, for example PT0.1S.\n\n");
            violations.forEach(violation -> message.append("  ").append(violation).append('\n'));
            fail(message.toString());
        }
    }

    @Test
    void matcherRejectsMillisecondSuffixAndAllowsFractionalSeconds() {
        String invalid = "PT" + "100" + "MS";

        assertTrue(INVALID_MILLISECOND_SUFFIX.matcher(invalid).find());
        assertFalse(INVALID_MILLISECOND_SUFFIX.matcher("PT0.1S").find());
        assertFalse(INVALID_MILLISECOND_SUFFIX.matcher("PT1M").find());
        assertFalse(INVALID_MILLISECOND_SUFFIX.matcher("PT100S").find());
    }

    private static List<String> scanWorkspace(Path workspaceRoot) throws IOException {
        List<Path> scanRoots = new ArrayList<>();
        addDirectoryIfPresent(scanRoots, workspaceRoot.resolve("docs"));
        addDirectoryIfPresent(scanRoots, workspaceRoot.resolve("docs-design"));
        addFileIfPresent(scanRoots, workspaceRoot.resolve("pom.xml"));

        try (Stream<Path> entries = Files.list(workspaceRoot)) {
            List<Path> modules = entries
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("peegeeq-"))
                    .toList();
            for (Path module : modules) {
                addDirectoryIfPresent(scanRoots, module.resolve("src"));
                addDirectoryIfPresent(scanRoots, module.resolve("docs"));
                addFileIfPresent(scanRoots, module.resolve("pom.xml"));
            }
        }

        List<String> violations = new ArrayList<>();
        for (Path scanRoot : scanRoots) {
            if (Files.isRegularFile(scanRoot)) {
                scanFile(workspaceRoot, scanRoot, violations);
                continue;
            }
            try (Stream<Path> files = Files.walk(scanRoot)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(InvalidDurationLiteralGuardTest::hasScannedExtension)
                        .toList()) {
                    scanFile(workspaceRoot, file, violations);
                }
            }
        }
        return violations;
    }

    private static void scanFile(Path workspaceRoot, Path file, List<String> violations) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            Matcher matcher = INVALID_MILLISECOND_SUFFIX.matcher(lines.get(index));
            while (matcher.find()) {
                String relative = workspaceRoot.relativize(file).toString().replace('\\', '/');
                violations.add(relative + ":" + (index + 1) + " — " + matcher.group());
            }
        }
    }

    private static boolean hasScannedExtension(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return SCANNED_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    private static void addDirectoryIfPresent(List<Path> scanRoots, Path path) {
        if (Files.isDirectory(path)) {
            scanRoots.add(path);
        }
    }

    private static void addFileIfPresent(List<Path> scanRoots, Path path) {
        if (Files.isRegularFile(path) && hasScannedExtension(path)) {
            scanRoots.add(path);
        }
    }

    private static Path locateWorkspaceRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 6; depth++) {
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
        throw new IllegalStateException("Could not locate workspace root from "
                + Paths.get("").toAbsolutePath());
    }
}
