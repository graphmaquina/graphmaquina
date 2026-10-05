package io.github.graphmaquina.codegen.source;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that the compilation helper the tests rely on reports no success for a compilation javac reported an
 * error in.
 */
class SourceFilesTest {

    @TempDir
    Path directory;

    /**
     * javac reports a byte that the encoding it reads a source in cannot decode as an error, yet its task still
     * returns {@code true} when that byte stands in a comment. Read as UTF-8, the byte 0xE9, an e with an acute
     * accent in ISO-8859-1, is such a byte.
     */
    @Test
    void reportsNoSuccessForAnErrorJavacReportsWithoutFailingItsTask() throws IOException {
        Path file = directory.resolve("src/fx/Resume.java");
        Files.createDirectories(file.getParent());
        Files.write(file, "package fx;\nfinal class Resume { /* r\u00e9sum\u00e9 */ }\n"
                .getBytes(StandardCharsets.ISO_8859_1));

        SourceFiles.Compilation compilation =
                SourceFiles.compileStrict(List.of(file), directory.resolve("classes"));

        assertTrue(compilation.codes().contains("compiler.err.illegal.char.for.encoding"),
                () -> String.valueOf(compilation.codes()));
        assertFalse(compilation.success(), () -> String.valueOf(compilation.messages()));
    }
}
