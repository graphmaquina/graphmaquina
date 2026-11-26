package io.github.graphmaquina.maven;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The selection {@link SourcePatterns} makes, transcribed in its plainest form so that a test can hold that class to
 * it however it reads its patterns: every pattern is normalized, unwrapped, split and, when it is a regular
 * expression, compiled again for each path it is matched against, and the path is split again for each pattern,
 * around its separator quoted as a regular expression. The rules are the ones the description of
 * {@link SourcePatterns} gives.
 *
 * <p>Each method follows the method of the same name of {@link SourcePatterns}, as that class read when this
 * transcription was made, behaviour for behaviour. The methods too complex for the test profile of Checkstyle are
 * divided into methods named for the part they hold, and the walk over the characters of a name is the recursive
 * definition of the matching that {@link SourcePatterns} runs as one backtracking loop: {@code *} stands for any run
 * of chars and {@code ?} for one char.</p>
 */
final class ReferenceSourcePatterns {

    private static final String REGEX = "%regex[";
    private static final String ANT = "%ant[";

    /** The default excludes of plexus-utils 4.0.2, in its order. */
    private static final List<String> DEFAULT_EXCLUDES = List.of("**/*~", "**/#*#", "**/.#*", "**/%*%", "**/._*",
            "**/CVS", "**/CVS/**", "**/.cvsignore", "**/RCS", "**/RCS/**", "**/SCCS", "**/SCCS/**", "**/vssver.scc",
            "**/project.pj", "**/.svn", "**/.svn/**", "**/.arch-ids", "**/.arch-ids/**", "**/.bzr", "**/.bzr/**",
            "**/.MySCMServerInfo", "**/.DS_Store", "**/.metadata", "**/.metadata/**", "**/.hg", "**/.hg/**",
            "**/.git", "**/.git/**", "**/.gitignore", "**/BitKeeper", "**/BitKeeper/**", "**/ChangeSet",
            "**/ChangeSet/**", "**/_darcs", "**/_darcs/**", "**/.darcsrepo", "**/.darcsrepo/**", "**/-darcs-backup*",
            "**/.darcs-temp-mail");

    private final List<String> includes;
    private final List<String> excludes;
    private final char separator;

    /**
     * Holds the patterns of one execution.
     *
     * @param includes the patterns of the sources it compiles, none meaning every source
     * @param excludes the patterns of the sources it leaves out
     * @param separator the file separator of the platform the build runs on, {@code /} or {@code \}
     */
    ReferenceSourcePatterns(List<String> includes, List<String> excludes, char separator) {
        this.includes = List.copyOf(includes);
        this.excludes = List.copyOf(excludes);
        this.separator = separator;
    }

    /** {@code SourcePatterns.narrow}. */
    boolean narrow() {
        return !includes.isEmpty() || !excludes.isEmpty();
    }

    /** {@code SourcePatterns.select}. */
    List<Path> select(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> selected = new ArrayList<>();
        Files.walkFileTree(root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {

                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                        return directory.equals(root) || entered(relativePath(root, directory))
                                ? FileVisitResult.CONTINUE
                                : FileVisitResult.SKIP_SUBTREE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        if (attributes.isRegularFile() && file.getFileName().toString().endsWith(".java")
                                && matched(relativePath(root, file))) {
                            selected.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException failure) {
                        return FileVisitResult.CONTINUE;
                    }
                });
        selected.sort(Comparator.<Path>naturalOrder());
        return List.copyOf(selected);
    }

    /** {@code SourcePatterns.selects}. */
    boolean selects(String path) {
        return skippedDirectory(path).isEmpty() && matched(scannerPath(path, separator));
    }

    /** {@code SourcePatterns.skippedDirectory}. */
    Optional<String> skippedDirectory(String path) {
        List<String> names = names(path, '/');
        for (int depth = 1; depth < names.size(); depth++) {
            List<String> directory = names.subList(0, depth);
            if (!entered(String.join(String.valueOf(separator), directory))) {
                return Optional.of(String.join("/", directory));
            }
        }
        return Optional.empty();
    }

    /** {@code SourcePatterns.entered}. */
    private boolean entered(String path) {
        return includes.isEmpty() || matched(path)
                || includes.stream().anyMatch((String pattern) -> couldMatchBelow(pattern, path, separator));
    }

    /** {@code SourcePatterns.matched}. */
    private boolean matched(String path) {
        boolean included = includes.isEmpty()
                || includes.stream().anyMatch((String pattern) -> matchesSpelled(pattern, path, separator));
        return included && excludes.stream().noneMatch((String pattern) -> matchesSpelled(pattern, path, separator))
                && DEFAULT_EXCLUDES.stream().noneMatch((String pattern) -> matchesSpelled(pattern, path, separator));
    }

    /** {@code SourcePatterns.relativePath}. */
    private String relativePath(Path root, Path file) {
        List<String> names = new ArrayList<>();
        for (Path name : root.relativize(file)) {
            names.add(name.toString());
        }
        return String.join(String.valueOf(separator), names);
    }

    /** {@code SourcePatterns.scannerPath}. */
    private static String scannerPath(String path, char separator) {
        return String.join(String.valueOf(separator), names(path, '/'));
    }

    /** {@code SourcePatterns.matches(String, String, char)}. */
    static boolean matches(String pattern, String path, char separator) {
        return matchesSpelled(pattern, scannerPath(path, separator), separator);
    }

    /** {@code SourcePatterns.matchesSpelled}. */
    private static boolean matchesSpelled(String pattern, String path, char separator) {
        String normalized = normalized(pattern, separator);
        if (spelled(normalized, REGEX)) {
            return path.matches(normalized.substring(REGEX.length(), normalized.length() - 1));
        }
        return matches(names(ant(normalized), separator), 0, names(path, separator), 0);
    }

    /** {@code SourcePatterns.couldMatchBelow}. */
    private static boolean couldMatchBelow(String pattern, String path, char separator) {
        String normalized = normalized(pattern, separator);
        if (spelled(normalized, REGEX)) {
            return true;
        }
        String ant = ant(normalized);
        return startMatches(ant, separator, path, separator)
                || startMatches(ant, separator, path.replace('\\', '/'), '/');
    }

    /** {@code SourcePatterns.startMatches}, up to its walk over the names, which {@link #namesStart} holds. */
    private static boolean startMatches(String ant, char antSeparator, String path, char pathSeparator) {
        if (ant.startsWith(String.valueOf(pathSeparator)) != path.startsWith(String.valueOf(pathSeparator))) {
            return false;
        }
        return namesStart(names(ant, antSeparator), names(path, pathSeparator));
    }

    /** The walk of {@code SourcePatterns.startMatches} over the names of the include and of the directory. */
    private static boolean namesStart(List<String> pattern, List<String> directory) {
        int at = 0;
        while (at < Math.min(pattern.size(), directory.size()) && !pattern.get(at).equals("**")) {
            if (!matchesName(pattern.get(at), 0, directory.get(at), 0)) {
                return false;
            }
            at++;
        }
        return at < pattern.size() || at == directory.size();
    }

    /** {@code SourcePatterns.normalized}. */
    private static String normalized(String pattern, char separator) {
        String normalized = pattern.trim();
        if (normalized.startsWith(REGEX)) {
            return separator == '\\' ? normalized.replace("/", "\\\\") : normalized.replace("\\\\", "/");
        }
        normalized = normalized.replace(separator == '/' ? '\\' : '/', separator);
        return normalized.endsWith(String.valueOf(separator)) ? normalized + "**" : normalized;
    }

    /** {@code SourcePatterns.ant}. */
    private static String ant(String normalized) {
        return spelled(normalized, ANT) ? normalized.substring(ANT.length(), normalized.length() - 1) : normalized;
    }

    /** {@code SourcePatterns.spelled}. */
    private static boolean spelled(String pattern, String prefix) {
        return pattern.length() > prefix.length() + 2 && pattern.startsWith(prefix) && pattern.endsWith("]");
    }

    /** {@code SourcePatterns.names}. */
    private static List<String> names(String path, char separator) {
        List<String> names = new ArrayList<>();
        for (String name : path.split(Pattern.quote(String.valueOf(separator)))) {
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * {@code SourcePatterns.matches(List, int, List, int)}, up to a name {@code **}, whose walk
     * {@link #matchesAfterDoubleStar} holds.
     */
    private static boolean matches(List<String> pattern, int next, List<String> path, int at) {
        if (next == pattern.size()) {
            return at == path.size();
        }
        if (pattern.get(next).equals("**")) {
            return matchesAfterDoubleStar(pattern, next, path, at);
        }
        return at < path.size() && matchesName(pattern.get(next), 0, path.get(at), 0)
                && matches(pattern, next + 1, path, at + 1);
    }

    /** The walk of {@code SourcePatterns.matches(List, int, List, int)} over the names a {@code **} can stand for. */
    private static boolean matchesAfterDoubleStar(List<String> pattern, int next, List<String> path, int at) {
        for (int skipped = at; skipped <= path.size(); skipped++) {
            if (matches(pattern, next + 1, path, skipped)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code SourcePatterns.matchesName}, defined recursively: whether the chars of {@code name} from {@code at} on
     * match the chars of {@code pattern} from {@code next} on.
     */
    private static boolean matchesName(String pattern, int next, String name, int at) {
        if (next == pattern.length()) {
            return at == name.length();
        }
        if (pattern.charAt(next) == '*') {
            return matchesStar(pattern, next, name, at);
        }
        return at < name.length() && sameChar(pattern.charAt(next), name.charAt(at))
                && matchesName(pattern, next + 1, name, at + 1);
    }

    /** Whether the {@code *} at {@code next} stands for no further char, or for the char at {@code at} and more. */
    private static boolean matchesStar(String pattern, int next, String name, int at) {
        return matchesName(pattern, next + 1, name, at)
                || at < name.length() && matchesName(pattern, next, name, at + 1);
    }

    /** Whether one char of a pattern, {@code ?} standing for any, matches one char of a name. */
    private static boolean sameChar(char pattern, char name) {
        return pattern == '?' || pattern == name;
    }
}
