package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * The companions a scenario's compilation is pinned to generate, kept under {@code characterization/} in the test
 * resources: one directory per scenario, listing the names of its companions in {@code companions.txt} and holding
 * each companion's text in a file of its name followed by {@code .txt}.
 */
final class PinnedCompanions {

    private PinnedCompanions() { }

    /**
     * The compilation {@code result} succeeded and generated the very companions pinned for {@code scenario}: the
     * same files, each with the same text.
     */
    static void assertGenerated(String scenario, Compilation.Result result) throws IOException {
        assertTrue(result.success(), result.describe());
        List<Path> generated;
        try (Stream<Path> walk = Files.walk(result.output())) {
            generated = walk.filter(Files::isRegularFile).sorted().toList();
        }
        List<String> names = generated.stream().map((Path file) -> file.getFileName().toString()).toList();
        assertEquals(pinned(scenario, "companions.txt").lines().toList(), names,
                "the companions generated for " + scenario);
        for (Path file : generated) {
            assertEquals(pinned(scenario, file.getFileName() + ".txt"), Files.readString(file, StandardCharsets.UTF_8),
                    "the companion " + file.getFileName());
        }
    }

    private static String pinned(String scenario, String name) throws IOException {
        String resource = "/characterization/" + scenario + "/" + name;
        try (InputStream in = PinnedCompanions.class.getResourceAsStream(resource)) {
            assertNotNull(in, "nothing is pinned at " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
