package io.github.graphmaquina.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Dates the carrier sources a generation emitted past everything the previous compilation left in the class
 * output, without changing a byte of them.
 *
 * <p>The preparation reads the newest modification time of the class output before the generator retires
 * anything, and afterwards gives every carrier source that time plus two seconds, unless the source is newer
 * already. Two seconds rather than one millisecond, because filesystems still exist whose modification times have a
 * resolution of a second or two. Only a modification time changes, so the generation stays deterministic.</p>
 *
 * <p>The dating is meant for a compiler that decides by timestamps alone whether a module is up to date.
 * maven-compiler-plugin 3.15.0, the version the checks of this plugin are written against, does not depend on it:
 * its stale-source scanner counts a source whose class file is missing as stale, and the preparation leaves the
 * carrier without a class file. The completion deletes that class file, and the preparation deletes it again, as a
 * binary the sealed inventory excluded or, after a cycle that never completed, as a class file whose
 * {@code SourceFile} attribute ties it to the carrier. The dating is kept because it is harmless; {@link #refresh}
 * also returns the carriers it dated, and the preparation fails when there are none.</p>
 */
final class CarrierTimestamp {

    /** The package the generator emits the carrier of every compilation into. */
    private static final String CARRIER_PACKAGE = "io/github/graphmaquina/generated/bootstrap";

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
