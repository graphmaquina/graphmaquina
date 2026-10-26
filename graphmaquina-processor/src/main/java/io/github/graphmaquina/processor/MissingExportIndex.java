package io.github.graphmaquina.processor;

import io.github.graphmaquina.codegen.manifest.PublishedEntries;
import java.io.IOException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
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
 *
 * <p>The generator asks the same question about a declared peer, so the answer lives in
 * {@link PublishedEntries}: which entry the location of a class file names, whether that entry's index lists a name,
 * the words that say so and the remedy, and how an entry is compared with a class output. This class adapts it to the
 * processor: it asks javac's {@code Elements} for the class file of a type and leaves out the class output of this
 * compilation, which only the {@code Filer} locates.</p>
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class MissingExportIndex {

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
                .filter((Path entry) -> classOutput
                        .filter((Path output) -> PublishedEntries.sameFile(output, entry)).isEmpty())
                .flatMap((Path entry) -> PublishedEntries.unlisted(entry, type.getQualifiedName().toString()));
    }

    /**
     * The classpath entry javac read {@code type} from, as {@link PublishedEntries#entry} finds it in the location of
     * its class file. Empty for a type read from a source, or from a file that is not kept at a path of the default
     * file system.
     */
    private Optional<Path> entry(TypeElement type) {
        Optional<JavaFileObject> file;
        try {
            file = Optional.ofNullable(elements.getFileObjectOf(type));
        } catch (UnsupportedOperationException unsupported) {
            return Optional.empty();
        }
        return file.filter((JavaFileObject read) -> read.getKind() == JavaFileObject.Kind.CLASS)
                .flatMap((JavaFileObject read) -> PublishedEntries.entry(read.toUri(),
                        elements.getBinaryName(type).toString()));
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
}
