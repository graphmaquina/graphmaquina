package io.github.graphmaquina.codegen.source;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;

/**
 * Test support for the source and output layers: creates a symbolic link, or skips the calling test where a probe
 * finds that symbolic links cannot be created, as on Windows for an account without the privilege to.
 */
public final class SymbolicLinks {

    /** Why no symbolic link can be created here, empty when one can; {@code null} until {@link #refusal()} probes. */
    private static Optional<String> refusal;

    private SymbolicLinks() {
    }

    /**
     * Creates {@code link} pointing at {@code target} and returns it. The test that asked for it is aborted, as a
     * JUnit assumption, only when a probe link, created once in a temporary directory of its own, fails as
     * {@link #refusal()} describes, which is taken to mean that this platform or account cannot create symbolic links
     * at all. Any other failure, such as a link that already exists or a directory of the link that is missing, is a
     * mistake of the test and fails it.
     */
    public static Path create(Path link, Path target) throws IOException {
        Optional<String> refused = refusal();
        if (refused.isPresent()) {
            return Assumptions.abort("symbolic links cannot be created here: " + refused.get());
        }
        return Files.createSymbolicLink(link, target);
    }

    /**
     * Why a symbolic link cannot be created here, empty when it can. The probe creates one in a new temporary
     * directory, where no file is in its way and its directory exists, so an {@code UnsupportedOperationException}
     * or a {@code FileSystemException} there is taken to mean that links cannot be created at all.
     */
    private static synchronized Optional<String> refusal() throws IOException {
        if (refusal == null) {
            Path probe = Files.createTempDirectory("symbolic-links");
            Path link = probe.resolve("link");
            try {
                Files.createSymbolicLink(link, probe.resolve("target"));
                refusal = Optional.empty();
            } catch (UnsupportedOperationException | FileSystemException unsupported) {
                refusal = Optional.of(unsupported.toString());
            } finally {
                Files.deleteIfExists(link);
                Files.delete(probe);
            }
        }
        return refusal;
    }
}
