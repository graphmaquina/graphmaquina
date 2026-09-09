package io.stategraph.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Makes the generated carrier of a compilation newer than everything the previous compilation left in the
 * class output, so that the incremental compiler recompiles the whole module after a retirement.
 *
 * <p>{@code maven-compiler-plugin} decides whether to run javac at all by comparing the timestamps of the
 * sources with the timestamps of the classes. Retirement deletes the binaries of the previous cycle, so the
 * compilation must run again; but the sources it must run over are byte-identical to the ones the previous
 * cycle compiled, and a build that finished within the resolution of the filesystem can leave them looking no
 * newer than the classes that survived. Touching the one source every generation emits — the carrier of this
 * compilation — is enough to make the comparison unambiguous, and it changes no byte of a generated file, so
 * the generation stays deterministic.</p>
 *
 * <p>The margin is two seconds rather than one millisecond because filesystems still exist whose modification
 * times have a resolution of a second or two, and a timestamp that merely rounds to the same value as the
 * newest class file would leave the compilation looking up to date.</p>
 */
final class CarrierTimestamp {

    /** The package the generator emits the carrier of every compilation into. */
    private static final String CARRIER_PACKAGE = "io/stategraph/generated/bootstrap";

    /** How far past the newest previous output a refreshed carrier is dated. */
    private static final long MARGIN_MILLIS = 2_000L;

    private CarrierTimestamp() {
    }

    /**
     * The modification time of the newest file under {@code classesOutput}, read before anything is retired.
     *
     * @param classesOutput where javac writes the classes of this compilation
     * @return the newest modification time, or the epoch when the compilation has produced nothing yet
     * @throws IOException if the class output cannot be walked
     */
    static FileTime newestOutput(Path classesOutput) throws IOException {
        FileTime newest = FileTime.fromMillis(0L);
        if (!Files.isDirectory(classesOutput)) {
            return newest;
        }
        try (Stream<Path> walk = Files.walk(classesOutput)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                FileTime modified = Files.getLastModifiedTime(file);
                if (modified.compareTo(newest) > 0) {
                    newest = modified;
                }
            }
        }
        return newest;
    }

    /**
     * Dates every carrier source under {@code sourcesOutput} past {@code newest}, keeping its contents.
     *
     * @param sourcesOutput where the generator writes the vocabulary of this compilation
     * @param newest the newest modification time the previous compilation left behind
     * @return the carrier sources that were refreshed
     * @throws IOException if a carrier cannot be read or dated
     */
    static List<Path> refresh(Path sourcesOutput, FileTime newest) throws IOException {
        Path carriers = sourcesOutput.resolve(CARRIER_PACKAGE);
        List<Path> refreshed = new ArrayList<>();
        if (!Files.isDirectory(carriers)) {
            return List.copyOf(refreshed);
        }
        FileTime target = FileTime.fromMillis(newest.toMillis() + MARGIN_MILLIS);
        try (Stream<Path> walk = Files.walk(carriers)) {
            for (Path carrier : walk.filter(Files::isRegularFile).toList()) {
                if (!carrier.getFileName().toString().endsWith(".java")) {
                    continue;
                }
                FileTime current = Files.getLastModifiedTime(carrier);
                Files.setLastModifiedTime(carrier, current.compareTo(target) > 0 ? current : target);
                refreshed.add(carrier);
            }
        }
        return List.copyOf(refreshed);
    }
}
