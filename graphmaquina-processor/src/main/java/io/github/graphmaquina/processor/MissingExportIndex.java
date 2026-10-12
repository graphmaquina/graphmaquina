package io.github.graphmaquina.processor;

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

/**
 * Finds the dependency a type was read from when that dependency carries no export index.
 *
 * <p>A producer publishes {@code META-INF/graphmaquina/exports.json} beside its classes, and the generator reads the
 * index of every classpath entry into the manifest of this compilation, which is how the processor knows the
 * machines, the vocabulary and the symbols of a dependency. A jar or a directory of classes that lost its index, as
 * an assembly step that drops {@code META-INF/graphmaquina} leaves it, still carries the types that index listed,
 * and no index this compilation reads lists them. A use of one of them is reported as that missing index, rather
 * than as a symbol or a machine the author got wrong.</p>
 *
 * <p>The dependency is the classpath entry javac read the type's class file from, a jar or a directory of classes,
 * and it is named only when its missing index can be verified. A type read from a source has none, and neither has
 * one read from this compilation's own class output, whose index the generator never reads as a dependency's, or
 * from a file javac does not keep at a path of the default file system.</p>
 */
final class MissingExportIndex {

    /** Where a producer publishes its export index, beside its classes. */
    private static final String INDEX = "META-INF/graphmaquina/exports.json";

    private final Elements elements;
    private final Filer filer;

    MissingExportIndex(Elements elements, Filer filer) {
        this.elements = elements;
        this.filer = filer;
    }

    /**
     * The jar or the directory of classes javac read {@code type} from, when it carries no export index and is not
     * the class output of this compilation; empty otherwise, and whenever that cannot be told.
     */
    Optional<Path> entryOf(TypeElement type) {
        Optional<Path> classOutput = classOutput();
        return entry(type)
                .filter((Path entry) -> classOutput.filter((Path output) -> sameFile(output, entry)).isEmpty())
                .filter(MissingExportIndex::lacksIndex);
    }

    /**
     * What a diagnostic says after naming a type read from {@code entry}, a dependency that carries no export index.
     */
    static String unlisted(Path entry) {
        return " comes from " + entry + ", which carries no export index " + INDEX + ", so no index this compilation"
                + " reads lists it: package the index the producer's StateGraph build publishes with its classes, or"
                + " rebuild the producer through the complete lifecycle";
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
     * Whether {@code entry}, a directory of classes or a jar, carries no export index, as the generator reads one;
     * false when that cannot be told.
     */
    private static boolean lacksIndex(Path entry) {
        if (Files.isDirectory(entry)) {
            return !Files.isRegularFile(entry.resolve(INDEX));
        }
        if (!Files.isRegularFile(entry)) {
            return false;
        }
        try (FileSystem archive = FileSystems.newFileSystem(entry)) {
            return !Files.isRegularFile(archive.getPath(INDEX));
        } catch (IOException | ProviderNotFoundException unreadable) {
            return false;
        }
    }
}
