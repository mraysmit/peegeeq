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
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Repository guard that rejects unapproved JUnit {@code @Disabled} annotations.
 *
 * <p>Test execution profiles and tags define when tests run. Disabling a failing test hides
 * behavior and coverage, so every intentional exception must be recorded in the checked-in
 * allowlist with a concrete rationale. The guard also rejects stale allowlist rows after an
 * annotation is removed.</p>
 */
@Tag(TestCategories.CORE)
class DisabledTestsGuardTest {

    private static final String ALLOWLIST_RESOURCE = "/quality/disabled-tests-allowlist.csv";
    private static final Pattern DISABLED_ANNOTATION = Pattern.compile(
            "(?m)^\\s*@(?>org\\.junit\\.jupiter\\.api\\.)?Disabled\\b");

    @Test
    void disabledAnnotationsMatchApprovedAllowlist() throws IOException {
        Path workspaceRoot = locateWorkspaceRoot();
        Map<String, ApprovedDisabledTest> approved = loadAllowlist();
        Map<String, Integer> actual = scanWorkspace(workspaceRoot);

        List<String> unapproved = new ArrayList<>();
        List<String> stale = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : actual.entrySet()) {
            ApprovedDisabledTest approval = approved.get(entry.getKey());
            if (approval == null) {
                unapproved.add("  UNAPPROVED  " + entry.getKey() + "  annotations=" + entry.getValue());
            } else if (entry.getValue() > approval.count()) {
                unapproved.add("  INCREASED   " + entry.getKey() + "  actual=" + entry.getValue()
                        + "  approved=" + approval.count());
            }
        }

        for (Map.Entry<String, ApprovedDisabledTest> entry : approved.entrySet()) {
            int found = actual.getOrDefault(entry.getKey(), 0);
            if (found < entry.getValue().count()) {
                stale.add("  STALE       " + entry.getKey() + "  actual=" + found
                        + "  approved=" + entry.getValue().count());
            }
        }

        if (unapproved.isEmpty() && stale.isEmpty()) {
            return;
        }

        StringBuilder message = new StringBuilder()
                .append("Disabled-test policy guard FAILED.\n")
                .append("Use @Tag and the matching Maven profile for environment or cost classification.\n")
                .append("Fix failing tests instead of disabling them.\n")
                .append("Allowlist: peegeeq-test-support/src/test/resources/quality/disabled-tests-allowlist.csv\n\n");

        if (!unapproved.isEmpty()) {
            message.append("Unapproved or increased @Disabled usage:\n");
            unapproved.forEach(violation -> message.append(violation).append('\n'));
            message.append('\n');
        }
        if (!stale.isEmpty()) {
            message.append("Stale allowlist entries; remove or reduce them with the annotation:\n");
            stale.forEach(violation -> message.append(violation).append('\n'));
        }

        fail(message.toString());
    }

    @Test
    void matcherRecognizesAnnotationsWithoutMatchingDocumentation() {
        String source = """
                // @Disabled("comment")
                class Example {
                    String documentation = "@Disabled in a string";
                    @Disabled("short name")
                    void first() {}
                    @org.junit.jupiter.api.Disabled("qualified name")
                    void second() {}
                }
                """;

        assertEquals(2, countAnnotations(maskNonCode(source)));
    }

    private static Map<String, Integer> scanWorkspace(Path workspaceRoot) throws IOException {
        Map<String, Integer> result = new TreeMap<>();
        try (Stream<Path> modules = Files.list(workspaceRoot)) {
            List<Path> testRoots = modules
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("peegeeq-"))
                    .map(path -> path.resolve("src").resolve("test").resolve("java"))
                    .filter(Files::isDirectory)
                    .toList();

            for (Path testRoot : testRoots) {
                try (Stream<Path> files = Files.walk(testRoot)) {
                    List<Path> javaFiles = files
                            .filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".java"))
                            .filter(path -> !path.toString().replace('\\', '/').contains("/target/"))
                            .filter(path -> !path.toString().replace('\\', '/').contains("/.history/"))
                            .toList();
                    for (Path javaFile : javaFiles) {
                        String content = Files.readString(javaFile, StandardCharsets.UTF_8);
                        int count = countAnnotations(maskNonCode(content));
                        if (count > 0) {
                            String relative = workspaceRoot.relativize(javaFile)
                                    .toString().replace('\\', '/');
                            result.put(relative, count);
                        }
                    }
                }
            }
        }
        return result;
    }

    private static int countAnnotations(String content) {
        int count = 0;
        Matcher matcher = DISABLED_ANNOTATION.matcher(content);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static Map<String, ApprovedDisabledTest> loadAllowlist() throws IOException {
        Map<String, ApprovedDisabledTest> approved = new TreeMap<>();
        try (var input = DisabledTestsGuardTest.class.getResourceAsStream(ALLOWLIST_RESOURCE)) {
            if (input == null) {
                throw new IOException("Disabled-test allowlist not found: " + ALLOWLIST_RESOURCE);
            }
            String csv = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : csv.split("\\r?\\n")) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("path,")) {
                    continue;
                }
                String[] parts = line.split(",", 3);
                if (parts.length != 3) {
                    throw new IOException("Malformed disabled-test allowlist row: '" + line + "'");
                }
                String path = parts[0].trim();
                int count;
                try {
                    count = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException exception) {
                    throw new IOException("Invalid annotation count in allowlist row: '" + line + "'", exception);
                }
                String rationale = parts[2].trim();
                if (path.isBlank() || count < 1 || rationale.isBlank()) {
                    throw new IOException("Incomplete disabled-test allowlist row: '" + line + "'");
                }
                ApprovedDisabledTest previous = approved.put(path,
                        new ApprovedDisabledTest(count, rationale));
                if (previous != null) {
                    throw new IOException("Duplicate disabled-test allowlist path: " + path);
                }
            }
        }
        return approved;
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
        throw new IllegalStateException("Could not locate workspace root from "
                + Paths.get("").toAbsolutePath());
    }

    private static String maskNonCode(String content) {
        char[] masked = content.toCharArray();
        int index = 0;
        while (index < content.length()) {
            char current = content.charAt(index);
            if (current == '/' && index + 1 < content.length()) {
                char next = content.charAt(index + 1);
                if (next == '/') {
                    index = maskRange(masked, index, skipLineComment(content, index));
                    continue;
                }
                if (next == '*') {
                    index = maskRange(masked, index, skipBlockComment(content, index));
                    continue;
                }
            }
            if (current == '"' || current == '\'') {
                index = maskRange(masked, index, skipStringLiteral(content, index));
                continue;
            }
            index++;
        }
        return new String(masked);
    }

    private static int maskRange(char[] content, int start, int end) {
        for (int index = start; index <= end && index < content.length; index++) {
            if (content[index] != '\n' && content[index] != '\r') {
                content[index] = ' ';
            }
        }
        return end + 1;
    }

    private static int skipStringLiteral(String content, int start) {
        char quote = content.charAt(start);
        if (quote == '"' && start + 2 < content.length()
                && content.charAt(start + 1) == '"' && content.charAt(start + 2) == '"') {
            int index = start + 3;
            while (index + 2 < content.length()) {
                if (content.charAt(index) == '"' && content.charAt(index + 1) == '"'
                        && content.charAt(index + 2) == '"') {
                    return index + 2;
                }
                index++;
            }
            return content.length() - 1;
        }

        int index = start + 1;
        while (index < content.length()) {
            char current = content.charAt(index);
            if (current == '\\') {
                index += 2;
                continue;
            }
            if (current == quote || current == '\n') {
                return index;
            }
            index++;
        }
        return content.length() - 1;
    }

    private static int skipLineComment(String content, int start) {
        int index = start;
        while (index < content.length() && content.charAt(index) != '\n') {
            index++;
        }
        return index;
    }

    private static int skipBlockComment(String content, int start) {
        int index = start + 2;
        while (index + 1 < content.length()) {
            if (content.charAt(index) == '*' && content.charAt(index + 1) == '/') {
                return index + 1;
            }
            index++;
        }
        return content.length() - 1;
    }

    private record ApprovedDisabledTest(int count, String rationale) {
    }
}
