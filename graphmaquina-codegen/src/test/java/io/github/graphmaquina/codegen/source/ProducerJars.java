package io.github.graphmaquina.codegen.source;

import io.github.graphmaquina.codegen.GenerationFiles;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Test support for the tests of the generator that read what a producer publishes: an export index written into a
 * directory of classes or packed into a jar, and a producer that javac alone compiled, packed into a jar together
 * with the index a test gives it.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProducerJars {

    /** Writes {@code index} under {@code root} as the named file of the published metadata directory. */
    public static Path published(Path root, String name, ExportIndex index) throws IOException {
        Path metadata = Files.createDirectories(root.resolve("META-INF/graphmaquina"));
        ManifestCodec.writeExports(metadata.resolve(name), index);
        return root;
    }

    /** Packs {@code index} into a jar at {@code jar}, under the entry {@code name}. */
    public static Path archived(Path jar, String name, ExportIndex index) throws IOException {
        Path staged = Files.createDirectories(jar.getParent().resolve("staged")).resolve("exports.json");
        ManifestCodec.writeExports(staged, index);
        try (OutputStream target = Files.newOutputStream(jar);
                JarOutputStream out = new JarOutputStream(target)) {
            out.putNextEntry(new JarEntry(name));
            out.write(Files.readAllBytes(staged));
            out.closeEntry();
        }
        return jar;
    }

    /**
     * Compiles {@code sources} with javac alone, as {@link SourceFiles#compileToJar} does, under
     * {@code workDirectory}, and packs the classes into {@code jar} with {@code index} as its published export index.
     */
    public static Path producer(Path workDirectory, Map<String, String> sources, ExportIndex index, Path jar)
            throws IOException {
        SourceFiles.compileToJar(workDirectory, sources, jar);
        Path staged = Files.createDirectories(workDirectory.resolve("staged")).resolve("exports.json");
        ManifestCodec.writeExports(staged, index);
        try (FileSystem archive = FileSystems.newFileSystem(jar)) {
            Path published = archive.getPath(GenerationFiles.PUBLISHED_INDEX);
            Files.createDirectories(published.getParent());
            Files.copy(staged, published);
        }
        return jar;
    }
}
