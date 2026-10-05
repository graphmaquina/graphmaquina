package io.github.graphmaquina.codegen.source;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;

/**
 * Test support for the source and output layers: creates a symbolic link, or skips the calling test where the file
 * system or the account running it cannot create one, as on Windows without the privilege to.
 */
public final class SymbolicLinks {

    private SymbolicLinks() {
    }

    /** Creates {@code link} pointing at {@code target} and returns it, or aborts the test that asked for it. */
    public static Path create(Path link, Path target) throws IOException {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | FileSystemException unsupported) {
            return Assumptions.abort("symbolic links cannot be created here: " + unsupported);
        }
    }
}
