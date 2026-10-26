package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Producers packaged the way a build publishes them: a fixture compiled on its own through the complete lifecycle,
 * its classes and the export index its completion step wrote packed into a jar that a consumer puts on its
 * classpath. A test chooses the index the jar carries, or drops it.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class PublishedJars {

    /** Where a published jar, and a directory of classes, carries its export index. */
    static final String PUBLISHED = "META-INF/graphmaquina/exports.json";

    /**
     * A producer compiled on its own, packaged with the index its completion step published after {@code index}
     * rewrote it.
     */
    static Path publish(Map<String, String> sources, Path jar, UnaryOperator<String> index)
            throws IOException {
        Compilation.Prepared prepared = Compilation.prepare(sources);
        Compilation.Result result = Compilation.compilePrepared(prepared);
        assertTrue(result.success(), result.describe());
        Path classes = prepared.request().classesOutput();
        String published = Files.readString(classes.resolve(PUBLISHED), StandardCharsets.UTF_8);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Path file : files(classes)) {
                String name = classes.relativize(file).toString().replace(File.separatorChar, '/');
                // The completion step already removed the carrier, whose binaries belong to no jar.
                assertFalse(name.startsWith("io/github/graphmaquina/generated/bootstrap/"), name);
                if (name.equals(PUBLISHED)) {
                    continue;
                }
                out.putNextEntry(new JarEntry(name));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry(PUBLISHED));
            out.write(index.apply(published).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    /** The export index a compilation of {@code sources} publishes. */
    static String publishedIndex(Map<String, String> sources) throws IOException {
        Compilation.Prepared prepared = Compilation.prepare(sources);
        Compilation.Result result = Compilation.compilePrepared(prepared);
        assertTrue(result.success(), result.describe());
        return Files.readString(prepared.request().classesOutput().resolve(PUBLISHED), StandardCharsets.UTF_8);
    }

    /** Deletes the export index from {@code jar}, as an assembly step that drops {@code META-INF/graphmaquina} does. */
    static void stripIndex(Path jar) throws IOException {
        try (FileSystem archive = FileSystems.newFileSystem(jar)) {
            Files.delete(archive.getPath(PUBLISHED));
        }
    }

    /** Every file under {@code root}, in a stable order. */
    private static List<Path> files(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
