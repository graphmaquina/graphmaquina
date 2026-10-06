package io.github.graphmaquina.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how {@link Jdk} finds an executable and the home and release of the JDK it belongs to: the same-JDK check
 * of the compiler compares homes, and the JDK check of the generator reads the release of the home of the
 * {@code java} it is given.
 */
class JdkTest {

    @Test
    void theHomeOfAnExecutableIsTheDirectoryAboveItsBinDirectory(@TempDir Path directory) throws IOException {
        Path java = Files.createDirectories(directory.resolve("jdk/bin")).resolve("java");
        Files.writeString(java, "", StandardCharsets.UTF_8);

        assertTrue(Jdk.same(directory.resolve("jdk"), Jdk.home(java.toString(), directory)));
        assertTrue(Jdk.same(directory.resolve("jdk"), Jdk.home("jdk/bin/java", directory)));
    }

    @Test
    void aBareNameIsFoundOnThePath(@TempDir Path directory) {
        // Every system these tests run on carries sh on its PATH, as /bin/sh at least.
        assertTrue(Files.isRegularFile(Jdk.launcher("sh", directory)));
        assertEquals(directory.resolve("no-such-launcher").toAbsolutePath(),
                Jdk.launcher("no-such-launcher", directory));
    }

    /**
     * On Windows, a launcher configured without its extension, which names no file, is the {@code .exe} beside it,
     * the file the process start runs. Elsewhere it stays the missing file it names. The Windows side is checked
     * here through the flag alone, not on Windows.
     */
    @Test
    void aLauncherWithoutItsExtensionIsTheExeBesideItOnWindows(@TempDir Path directory) throws IOException {
        Path exe = Files.createDirectories(directory.resolve("jdk/bin")).resolve("java.exe");
        Files.writeString(exe, "", StandardCharsets.UTF_8);
        String configured = directory.resolve("jdk/bin/java").toString();

        assertEquals(exe.toRealPath(), Jdk.launcher(configured, directory, true));
        assertEquals(directory.resolve("jdk/bin/java").toAbsolutePath(), Jdk.launcher(configured, directory, false));
        assertEquals(directory.resolve("jdk/bin/java.cmd").toAbsolutePath(),
                Jdk.launcher(directory.resolve("jdk/bin/java.cmd").toString(), directory, true));
    }

    @Test
    void theReleaseIsTheFeatureVersionTheReleaseFileDeclares(@TempDir Path directory) throws IOException {
        assertEquals(21, release(directory.resolve("modern"), "JAVA_VERSION=\"21.0.9\"\n"));
        assertEquals(17, release(directory.resolve("plain"), "JAVA_VERSION=\"17\"\n"));
        assertEquals(8, release(directory.resolve("legacy"), "JAVA_VERSION=\"1.8.0_392\"\n"));
        assertEquals(0, release(directory.resolve("unreadable"), "IMPLEMENTOR=\"nobody\"\n"));
        assertEquals(0, Jdk.release(directory.resolve("absent")));
    }

    /** The release {@link Jdk#release} reads from a home whose release file holds {@code content}. */
    private static int release(Path home, String content) throws IOException {
        Files.createDirectories(home);
        Files.writeString(home.resolve("release"), content, StandardCharsets.UTF_8);
        return Jdk.release(home);
    }
}
