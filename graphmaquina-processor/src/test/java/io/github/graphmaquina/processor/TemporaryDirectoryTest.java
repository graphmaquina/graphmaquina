package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The directories {@link Compilation} hands out stay in the system's temporary directory only as long as the JVM
 * that made them: once it shuts down, nothing of them is left there.
 */
class TemporaryDirectoryTest {

    private static final String PRINTED = "directory ";

    @Test
    void aTemporaryDirectoryAndTheRootItLivesInAreGoneOnceTheJvmExits() throws IOException, InterruptedException {
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), MakesADirectory.class.getName());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "no exit after 60 s: " + output);
            assertEquals(0, process.exitValue(), output);
            Path directory = Path.of(output.lines().filter((String line) -> line.startsWith(PRINTED))
                    .findFirst().orElseThrow(() -> new AssertionError("no directory printed: " + output))
                    .substring(PRINTED.length()));
            assertFalse(Files.exists(directory), directory + " is left behind: " + output);
            assertFalse(Files.exists(directory.getParent()), directory.getParent() + " is left behind: " + output);
        } finally {
            process.destroyForcibly();
        }
    }

    /** Takes a temporary directory, writes a file in it and prints its path, then exits. */
    public static final class MakesADirectory {

        public static void main(String[] args) throws IOException {
            Path directory = Compilation.temporaryDirectory();
            Files.writeString(directory.resolve("file"), "content", StandardCharsets.UTF_8);
            System.out.println(PRINTED + directory);
        }
    }
}
