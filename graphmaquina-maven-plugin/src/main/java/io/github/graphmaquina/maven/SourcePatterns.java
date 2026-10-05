package io.github.graphmaquina.maven;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The includes and excludes one compiler execution selects its sources with, matched the way the directory
 * scanner of maven-compiler-plugin matches them, so that the generator parses exactly the sources javac compiles.
 *
 * <p>A pattern is matched against the path of a source relative to its source root, one name at a time and
 * case-sensitively: {@code *} stands for any run of characters within a name, {@code ?} for one character, a
 * whole name {@code **} for any number of names, none included, and a pattern that ends with a separator for
 * everything below it; either separator is accepted, and empty names, a leading separator included, are
 * ignored. A pattern spelled {@code %regex[...]} is a regular expression over the whole relative path,
 * separated by {@code /}, and one spelled {@code %ant[...]} is the pattern between the brackets. Without
 * includes every source is included.</p>
 *
 * <p>The scanner of the compiler cannot be reused here: Maven does not export plexus-utils to the realm of a
 * plugin, and this plugin depends on nothing but the generator. Its default excludes — files of version
 * control and editor backups such as {@code **}{@code /.git/**} or {@code **}{@code /._*} — are not applied,
 * just as the generator does not apply them to a root it walks itself.</p>
 */
final class SourcePatterns {

    private static final String REGEX = "%regex[";
    private static final String ANT = "%ant[";

    private final List<String> includes;
    private final List<String> excludes;

    /**
     * Holds the patterns of one execution.
     *
     * @param includes the patterns of the sources it compiles, none meaning every source
     * @param excludes the patterns of the sources it leaves out
     */
    SourcePatterns(List<String> includes, List<String> excludes) {
        this.includes = List.copyOf(includes);
        this.excludes = List.copyOf(excludes);
    }

    /**
     * Whether these patterns narrow the sources of a root at all. When they do not, javac compiles every Java
     * source under the root, which is exactly what the generator reads from a root given to it as it is.
     *
     * @return {@code true} if the execution declares includes or excludes
     */
    boolean narrow() {
        return !includes.isEmpty() || !excludes.isEmpty();
    }

    /**
     * The Java sources under {@code root} these patterns select, in sorted order, following symbolic links the
     * way the scanner of the compiler follows them.
     *
     * @param root a source root of the compilation
     * @return the selected files, none when the root does not exist
     * @throws IOException if the root cannot be walked
     */
    List<Path> select(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root, FileVisitOption.FOLLOW_LINKS)) {
            return walk.filter(Files::isRegularFile)
                    .filter((Path file) -> file.getFileName().toString().endsWith(".java"))
                    .filter((Path file) -> selects(relativePath(root, file)))
                    .sorted()
                    .toList();
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    /** Whether the relative {@code path} of one source is included and not excluded. */
    private boolean selects(String path) {
        boolean included = includes.isEmpty()
                || includes.stream().anyMatch((String pattern) -> matches(pattern, path));
        return included && excludes.stream().noneMatch((String pattern) -> matches(pattern, path));
    }

    /** The path of {@code file} relative to {@code root}, its names separated by {@code /} on every platform. */
    private static String relativePath(Path root, Path file) {
        List<String> names = new ArrayList<>();
        for (Path name : root.relativize(file)) {
            names.add(name.toString());
        }
        return String.join("/", names);
    }

    /**
     * Whether a relative path matches one pattern of the compiler.
     *
     * @param pattern an include or exclude, as the POM spells it
     * @param path a path relative to its source root, its names separated by {@code /}
     * @return {@code true} if the pattern selects that path
     */
    static boolean matches(String pattern, String path) {
        String spelled = pattern.strip();
        if (spelled.startsWith(REGEX) && spelled.endsWith("]")) {
            return path.matches(spelled.substring(REGEX.length(), spelled.length() - 1));
        }
        if (spelled.startsWith(ANT) && spelled.endsWith("]")) {
            spelled = spelled.substring(ANT.length(), spelled.length() - 1);
        }
        String normalized = spelled.replace('\\', '/');
        if (normalized.endsWith("/")) {
            normalized += "**";
        }
        return matches(names(normalized), 0, names(path), 0);
    }

    private static List<String> names(String path) {
        List<String> names = new ArrayList<>();
        for (String name : path.split("/")) {
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /** Whether the names of the pattern from {@code next} on match the names of the path from {@code at} on. */
    private static boolean matches(List<String> pattern, int next, List<String> path, int at) {
        if (next == pattern.size()) {
            return at == path.size();
        }
        if (pattern.get(next).equals("**")) {
            for (int skipped = at; skipped <= path.size(); skipped++) {
                if (matches(pattern, next + 1, path, skipped)) {
                    return true;
                }
            }
            return false;
        }
        return at < path.size() && matchesName(pattern.get(next), path.get(at))
                && matches(pattern, next + 1, path, at + 1);
    }

    /** Whether one name of a path matches one name of a pattern, where {@code *} and {@code ?} are wildcards. */
    private static boolean matchesName(String pattern, String name) {
        StringBuilder regex = new StringBuilder();
        for (char character : pattern.toCharArray()) {
            switch (character) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(character)));
            }
        }
        return name.matches(regex.toString());
    }
}
