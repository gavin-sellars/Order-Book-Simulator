package obs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the layering clean: obs.mem depends on nothing else in the project, and obs.core only on
 * itself and obs.mem. Both should be usable on their own. Scans the source text, so it also
 * catches fully qualified references that don't appear in an import.
 */
class ArchitectureTest {

    private static final Path SOURCES = Path.of("src", "main", "java", "obs");

    @Test
    void memDependsOnNothingElseInTheProject() throws IOException {
        assertNoReferences("mem", Pattern.compile("\\bobs\\.(?!mem\\b)\\w+"));
    }

    @Test
    void coreDependsOnlyOnCoreAndMem() throws IOException {
        assertNoReferences("core", Pattern.compile("\\bobs\\.(?!core\\b|mem\\b)\\w+"));
    }

    /** No third-party libraries (HdrHistogram, JMH, ...) in the book or its data structures: only the JDK. */
    @Test
    void coreAndMemImportOnlyTheJdkAndEachOther() throws IOException {
        Pattern nonJdkImport = Pattern.compile("^\\s*import\\s+(static\\s+)?(?!java\\.|obs\\.)\\S+");
        assertNoReferences("core", nonJdkImport);
        assertNoReferences("mem", nonJdkImport);
    }

    private static void assertNoReferences(String pkg, Pattern forbidden) throws IOException {
        Path dir = SOURCES.resolve(pkg);
        assertTrue(Files.isDirectory(dir), "source directory not found: " + dir.toAbsolutePath());

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (forbidden.matcher(lines.get(i)).find()) {
                        violations.add(file + ":" + (i + 1) + ": " + lines.get(i).trim());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "obs." + pkg + " must not reference:\n" + String.join("\n", violations));
    }
}
