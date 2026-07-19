package com.sismics.docs.architecture;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Line-count ratchet for the resource layer. It reads a committed ceilings file whose per-class
 * entries form an append-only, strictly-DECREASING history: a migrated "giant" resource can only
 * shrink, and every new {@code rest.document} class stays under a hard cap. No such gate existed
 * before Phase G; it exists so the extraction's headroom cannot silently regrow.
 */
public class ResourceSizeGateTest {

    private static final Path CEILINGS = Paths.get("src/test/resources/size-ceilings.tsv");
    private static final Path REST_DOCUMENT_DIR = Paths.get("src/main/java/com/sismics/docs/rest/document");
    private static final int NEW_RESOURCE_MAX = 300;

    @Test
    public void frozenGiantsRatchetDown() throws IOException {
        for (String line : Files.readAllLines(CEILINGS)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\t");
            Assertions.assertEquals(2, parts.length, "Malformed ceilings row: " + line);
            Path source = Paths.get(parts[0].trim());
            int[] history = Arrays.stream(parts[1].trim().split("\\s+")).mapToInt(Integer::parseInt).toArray();

            // (c) The whole history strictly decreases: a deliberate shrink APPENDS a smaller value;
            // any other reshaping is diff-visible history deletion.
            for (int i = 1; i < history.length; i++) {
                Assertions.assertTrue(history[i] < history[i - 1],
                        parts[0] + " ceiling history must strictly decrease (a shrink appends a smaller "
                                + "value; raising requires deleting history): " + parts[1]);
            }

            // (b) Append-only downward: the effective cap can never exceed ANY prior recorded value,
            // so raising the tail (e.g. 1050 -> 1200 under a 1463 head) fails even though the row
            // would still be strictly decreasing.
            int cap = history[history.length - 1];
            int priorMin = Integer.MAX_VALUE;
            for (int i = 0; i < history.length - 1; i++) {
                priorMin = Math.min(priorMin, history[i]);
            }
            Assertions.assertTrue(history.length == 1 || cap < priorMin,
                    parts[0] + " tail ceiling " + cap + " must be strictly below every prior recorded "
                            + "value (min " + priorMin + ") — the ratchet only tightens: " + parts[1]);

            // (a) The source respects the cap.
            int actual = countLines(source);
            Assertions.assertTrue(actual <= cap,
                    parts[0] + " grew to " + actual + " lines, exceeding its ratcheted ceiling " + cap
                            + " — split the class or extract, do not raise the ceiling.");

            // The cap may not carry slack beyond the measured size: a cap far above actual would let
            // the class regrow silently. Committed caps are the MEASURED size at ratchet time.
            Assertions.assertTrue(cap == actual,
                    parts[0] + " ceiling " + cap + " must equal the measured size " + actual
                            + " — re-measure and append the new (smaller) value, or revert the growth.");
        }
    }

    @Test
    public void newResourcesUnderCap() throws IOException {
        try (Stream<Path> files = Files.walk(REST_DOCUMENT_DIR)) {
            List<Path> javaFiles = files.filter(path -> path.toString().endsWith(".java")).toList();
            Assertions.assertFalse(javaFiles.isEmpty(), "Expected the rest.document package to contain classes");
            for (Path path : javaFiles) {
                int actual = countLines(path);
                Assertions.assertTrue(actual <= NEW_RESOURCE_MAX,
                        path + " has " + actual + " lines, exceeding the " + NEW_RESOURCE_MAX
                                + "-line cap for new rest.document classes.");
            }
        }
    }

    private static int countLines(Path path) throws IOException {
        try (Stream<String> lines = Files.lines(path)) {
            return (int) lines.count();
        }
    }
}
