package io.github.graphmaquina.processor;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Fixture sources kept as {@code .java} files under {@code src/test/resources}, one tree per scenario side, read into
 * the map {@link Compilation} takes. A source that lives in a file is written and read as the Java it is, and costs
 * the test that uses it no lines.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class FixtureSources {

    private static final String EXTENSION = ".java";

    /**
     * Every {@code .java} file under {@code root}, keyed by its qualified name, which is its path relative to
     * {@code root} without the extension and with {@code /} read as {@code .}, in a stable sorted order.
     */
    static Map<String, String> read(Path root) {
        Map<String, String> sources = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter((Path entry) -> entry.toString().endsWith(EXTENSION)).sorted().toList()) {
                String relative = root.relativize(file).toString().replace(File.separatorChar, '.');
                sources.put(relative.substring(0, relative.length() - EXTENSION.length()),
                        Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return sources;
    }

    /**
     * The sources of the test resource directory {@code path}, such as {@code /watch-owner}, read as {@link #read}
     * reads a tree.
     */
    static Map<String, String> resource(String path) {
        URL root = Objects.requireNonNull(FixtureSources.class.getResource(path),
                path + " is not on the test classpath");
        try {
            return read(Path.of(root.toURI()));
        } catch (URISyntaxException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
