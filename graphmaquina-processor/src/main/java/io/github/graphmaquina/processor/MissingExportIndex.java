package io.github.graphmaquina.processor;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
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
import javax.annotation.processing.Filer;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Finds the dependency a type was read from when that dependency's export index is missing or does not list it.
 *
 * <p>A producer publishes {@code META-INF/graphmaquina/exports.json} beside its classes, and the generator reads the
 * index of every classpath entry into the manifest of this compilation, which is how the processor knows the
 * machines, the vocabulary and the symbols of a dependency. A jar or a directory of classes that lost its index, as
 * an assembly step that drops {@code META-INF/graphmaquina} leaves it, still carries the types that index listed,
 * and no index this compilation reads lists them. So does one whose index is stale, or another build's, as an
 * assembly step that overwrites {@code META-INF/graphmaquina} leaves it: that index does not list every type the
 * classes beside it carry. A use of such a type is reported as that missing or stale index, rather than as a symbol
 * or a machine the author got wrong.</p>
 *
 * <p>The dependency is the classpath entry javac read the type's class file from, a jar or a directory of classes,
 * and it is named only when what its index says can be verified: that there is none, or that the one there, read
 * with the codec the generator reads it with, lists no machine and no symbol of that name. A type read from a source
 * has none, and neither has one read from this compilation's own class output, whose index the generator never reads
 * as a dependency's, or from a file javac does not keep at a path of the default file system.</p>
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class MissingExportIndex {

    /** Where a producer publishes its export index, beside its classes. */
    private static final String INDEX = "META-INF/graphmaquina/exports.json";

    /** What a diagnostic about a missing or stale index asks for. */
    private static final String REMEDY = ": package the index the producer's StateGraph build publishes with its"
            + " classes, or rebuild the producer through the complete lifecycle";

    private final Elements elements;
    private final Filer filer;

    /**
     * What a diagnostic says after naming {@code type} when the jar or the directory of classes javac read it from,
     * which is not the class output of this compilation, carries no export index, or one that lists no machine and
     * no symbol named like {@code type}: that entry, what its index lacks and the remedy. Empty otherwise, and
     * whenever that cannot be told.
     */
    Optional<String> unlisted(TypeElement type) {
        Optional<Path> classOutput = classOutput();
        return entry(type)
                .filter((Path entry) -> classOutput.filter((Path output) -> sameFile(output, entry)).isEmpty())
                .flatMap((Path entry) -> unlisted(entry, type.getQualifiedName().toString()));
    }

    /**
     * The classpath entry javac read {@code type} from, absolute and normalized: the jar that holds its class file,
     * or the directory the path of that class file, which the binary name of {@code type} spells, is relative to.
     * Empty for a type read from a source, or from a file that is not kept at a path of the default file system.
     */
    private Optional<Path> entry(TypeElement type) {
        JavaFileObject file;
        try {
            file = elements.getFileObjectOf(type);
        } catch (UnsupportedOperationException unsupported) {
            return Optional.empty();
        }
        if (file == null || file.getKind() != JavaFileObject.Kind.CLASS) {
            return Optional.empty();
        }
        URI uri = file.toUri();
        try {
            if ("jar".equalsIgnoreCase(uri.getScheme())) {
                String location = uri.getRawSchemeSpecificPart();
                int separator = location.indexOf("!/");
                if (separator < 0) {
                    return Optional.empty();
                }
                return Optional.of(Path.of(URI.create(location.substring(0, separator))).toAbsolutePath().normalize());
            }
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                return Optional.empty();
            }
            Path classFile = Path.of(uri).toAbsolutePath().normalize();
            Path relative = Path.of(elements.getBinaryName(type).toString().replace('.', '/') + ".class");
            if (!classFile.endsWith(relative)) {
                return Optional.empty();
            }
            Path root = classFile;
            for (int segment = 0; segment < relative.getNameCount() && root != null; segment++) {
                root = root.getParent();
            }
            return Optional.ofNullable(root);
        } catch (IllegalArgumentException | FileSystemNotFoundException | SecurityException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * The class output of this compilation, absolute and normalized: the directory the {@code Filer} resolves a file
     * of the unnamed package in. Empty when it is no directory of the default file system.
     */
    private Optional<Path> classOutput() {
        try {
            Path probe = Path.of(filer.getResource(StandardLocation.CLASS_OUTPUT, "", "graphmaquina-index").toUri());
            return Optional.ofNullable(probe.toAbsolutePath().normalize().getParent());
        } catch (IOException | IllegalArgumentException | FileSystemNotFoundException | SecurityException unknown) {
            return Optional.empty();
        }
    }

    /** Whether {@code first} and {@code second} locate one file, which only their paths tell when one is missing. */
    private static boolean sameFile(Path first, Path second) {
        try {
            return Files.isSameFile(first, second);
        } catch (IOException | SecurityException unreadable) {
            return first.equals(second);
        }
    }

    /**
     * What a diagnostic says after naming {@code name}, which {@code entry}, a directory of classes or a jar,
     * carries, when the export index of {@code entry} is missing or does not list it; empty when it lists it, and
     * when that cannot be told.
     */
    private static Optional<String> unlisted(Path entry, String name) {
        if (Files.isDirectory(entry)) {
            return unlisted(entry, entry.resolve(INDEX), name);
        }
        if (!Files.isRegularFile(entry)) {
            return Optional.empty();
        }
        try (FileSystem archive = FileSystems.newFileSystem(entry)) {
            return unlisted(entry, archive.getPath(INDEX), name);
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
            return Optional.of(" comes from " + entry + ", which carries no export index " + INDEX + ", so no index"
                    + " this compilation reads lists it" + REMEDY);
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
        return listed ? Optional.empty() : Optional.of(" comes from " + entry + ", whose export index " + INDEX
                + " does not list it, so that index is stale or another build's" + REMEDY);
    }
}
