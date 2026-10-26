package io.github.graphmaquina.codegen.manifest;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationFiles;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Answers, for a type javac read from a class file, which classpath entry carries it and whether the export index of
 * that entry lists it, so that a use of a type whose dependency lost its index, or carries a stale one, is reported as
 * that index rather than as a symbol or a machine the author got wrong.
 *
 * <p>A producer publishes {@code META-INF/graphmaquina/exports.json} beside its classes, and the generator reads the
 * index of every classpath entry but the compilation's own class output. A jar or a directory of classes that lost its
 * index, as an assembly step that drops {@code META-INF/graphmaquina} leaves it, still carries the types that index
 * listed, and no index a compilation reads lists them. So does one whose index is stale, or another build's, as an
 * assembly step that overwrites {@code META-INF/graphmaquina} leaves it: that index does not list every type the
 * classes beside it carry. The generator and the processor ask the same question here, each about the types it
 * resolves.</p>
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class PublishedEntries {

    /** What a diagnostic about a missing or stale index asks for. */
    public static final String REMEDY = ": package the index the producer's StateGraph build publishes with its"
            + " classes, or rebuild the producer through the complete lifecycle";

    /**
     * The classpath entry javac read the class file at {@code classFile} from, absolute and normalized: the jar that
     * holds it, or the directory the path of that class file, which {@code binaryName} spells, is relative to. Empty
     * for a file that is not kept at a path of the default file system, or not where {@code binaryName} places it.
     *
     * @param classFile the URI javac gives the class file of a type
     * @param binaryName the binary name of that type, such as {@code pay.Outer$Inner}
     * @return that jar or directory of classes, or empty
     */
    public static Optional<Path> entry(@NonNull URI classFile, @NonNull String binaryName) {
        try {
            if ("jar".equalsIgnoreCase(classFile.getScheme())) {
                String location = classFile.getRawSchemeSpecificPart();
                int separator = location.indexOf("!/");
                if (separator < 0) {
                    return Optional.empty();
                }
                return Optional.of(Path.of(URI.create(location.substring(0, separator))).toAbsolutePath().normalize());
            }
            if (!"file".equalsIgnoreCase(classFile.getScheme())) {
                return Optional.empty();
            }
            Path file = Path.of(classFile).toAbsolutePath().normalize();
            Path relative = Path.of(binaryName.replace('.', '/') + ".class");
            if (!file.endsWith(relative)) {
                return Optional.empty();
            }
            Path root = file;
            for (int segment = 0; segment < relative.getNameCount() && root != null; segment++) {
                root = root.getParent();
            }
            return Optional.ofNullable(root);
        } catch (IllegalArgumentException | FileSystemNotFoundException | SecurityException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * What a diagnostic says after naming {@code name}, which {@code entry}, a directory of classes or a jar,
     * carries, when the export index of {@code entry} is missing or does not list it: that entry, what its index
     * lacks and {@link #REMEDY}. Empty when it lists it, and when that cannot be told.
     *
     * @param entry the jar or directory of classes that carries {@code name}
     * @param name the canonical name of a machine or the qualified name of a symbol
     * @return the rest of the diagnostic, or empty
     */
    public static Optional<String> unlisted(@NonNull Path entry, @NonNull String name) {
        if (Files.isDirectory(entry)) {
            return unlisted(entry, entry.resolve(GenerationFiles.PUBLISHED_INDEX), name);
        }
        if (!Files.isRegularFile(entry)) {
            return Optional.empty();
        }
        try (FileSystem archive = FileSystems.newFileSystem(entry)) {
            return unlisted(entry, archive.getPath(GenerationFiles.PUBLISHED_INDEX), name);
        } catch (IOException | ProviderNotFoundException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * The same, for the index {@code entry} carries at {@code index}, read with the codec the generator reads it
     * with: an index there lists {@code name} when one of its machines or one of its symbols is named so.
     */
    private static Optional<String> unlisted(Path entry, Path index, String name) {
        if (!Files.isRegularFile(index)) {
            return Optional.of(" comes from " + entry + ", which carries no export index "
                    + GenerationFiles.PUBLISHED_INDEX + ", so no index this compilation reads lists it" + REMEDY);
        }
        ExportIndex published;
        try {
            published = ManifestCodec.readExports(index);
        } catch (IOException | CodegenException unreadable) {
            return Optional.empty();
        }
        boolean listed = published.model().graphs().stream()
                .anyMatch((GraphDescriptor graph) -> graph.facade().equals(name))
                || published.model().symbols().stream()
                        .anyMatch((SymbolDescriptor symbol) -> symbol.qualifiedName().equals(name));
        return listed ? Optional.empty() : Optional.of(" comes from " + entry + ", whose export index "
                + GenerationFiles.PUBLISHED_INDEX + " does not list it, so that index is stale or another build's"
                + REMEDY);
    }
}
