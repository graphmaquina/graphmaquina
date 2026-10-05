package io.github.graphmaquina.codegen.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that {@link SymbolicLinks} skips a test only where its probe finds that symbolic links cannot be created: a
 * link it cannot create because of a mistake in the test's own setup fails that test instead. Each test first creates
 * one link that must succeed, so where the probe fails it is skipped before it asserts anything.
 */
class SymbolicLinksTest {

    @TempDir
    Path directory;

    @Test
    void createsALinkToItsTarget() throws IOException {
        Path target = Files.createDirectories(directory.resolve("target"));

        Path link = SymbolicLinks.create(directory.resolve("link"), target);

        assertTrue(Files.isSymbolicLink(link));
        assertEquals(target, Files.readSymbolicLink(link));
    }

    @Test
    void failsTheTestWhenTheLinkAlreadyExists() throws IOException {
        Path target = Files.createDirectories(directory.resolve("target"));
        Path link = SymbolicLinks.create(directory.resolve("link"), target);

        assertThrows(FileAlreadyExistsException.class, () -> SymbolicLinks.create(link, target));
    }

    @Test
    void failsTheTestWhenTheDirectoryOfTheLinkIsMissing() throws IOException {
        Path target = Files.createDirectories(directory.resolve("target"));
        SymbolicLinks.create(directory.resolve("link"), target);

        assertThrows(NoSuchFileException.class,
                () -> SymbolicLinks.create(directory.resolve("missing/link"), target));
    }
}
