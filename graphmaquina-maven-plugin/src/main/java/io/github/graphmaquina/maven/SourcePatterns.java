package io.github.graphmaquina.maven;

import java.io.IOException;
import java.nio.file.FileSystemLoopException;
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
import java.util.regex.Pattern;

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
 * plugin, and this plugin depends on nothing but the generator. Its default excludes are applied on top of the
 * excludes of the execution, as that scanner applies them: the source scanner of plexus-compiler-api 2.16.2,
 * which maven-compiler-plugin 3.15.0 uses, calls {@code DirectoryScanner.addDefaultExcludes()}. The list is the
 * one of plexus-utils 4.0.2, the version plexus-compiler 2.16.2 manages. Besides the Ant default excludes, which
 * Gradle and the generator's own walk apply, it leaves out the directories {@code RCS}, {@code BitKeeper},
 * {@code ChangeSet}, {@code _darcs}, {@code .metadata}, {@code .arch-ids} and {@code .darcsrepo}, and the files
 * {@code -darcs-backup*}.</p>
 *
 * <p>Those extra entries only take effect when this plugin lists files. A root the execution does not narrow is
 * handed to the generator whole, and the generator walks it with Ant's default excludes, not with plexus-utils'
 * longer list: a file under such a root inside one of those directories, or named {@code -darcs-backup*}, is read
 * by the generator although javac never compiles it.</p>
 */
final class SourcePatterns {

    private static final String REGEX = "%regex[";
    private static final String ANT = "%ant[";

    /**
     * The default excludes of plexus-utils 4.0.2, in its order: the companions editors and copies from macOS leave
     * beside a source, the files of version control systems, and their directories with everything inside them.
     */
    private static final List<String> DEFAULT_EXCLUDES = List.of("**/*~", "**/#*#", "**/.#*", "**/%*%", "**/._*",
            "**/CVS", "**/CVS/**", "**/.cvsignore", "**/RCS", "**/RCS/**", "**/SCCS", "**/SCCS/**", "**/vssver.scc",
            "**/project.pj", "**/.svn", "**/.svn/**", "**/.arch-ids", "**/.arch-ids/**", "**/.bzr", "**/.bzr/**",
            "**/.MySCMServerInfo", "**/.DS_Store", "**/.metadata", "**/.metadata/**", "**/.hg", "**/.hg/**",
            "**/.git", "**/.git/**", "**/.gitignore", "**/BitKeeper", "**/BitKeeper/**", "**/ChangeSet",
            "**/ChangeSet/**", "**/_darcs", "**/_darcs/**", "**/.darcsrepo", "**/.darcsrepo/**", "**/-darcs-backup*",
            "**/.darcs-temp-mail");

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
     * source under the root that the default excludes of its scanner leave. A root the execution does not narrow
     * is handed to the generator whole, and the generator walks it with Ant's default excludes, not with
     * plexus-utils' longer list; the class description says what that leaves in.
     *
     * @return {@code true} if the execution declares includes or excludes
     */
    boolean narrow() {
        return !includes.isEmpty() || !excludes.isEmpty();
    }

    /**
     * The Java sources under {@code root} these patterns select, in sorted order, following symbolic links as the
     * scanner of the compiler follows them, and leaving out what the default excludes name.
     *
     * <p>A link that leads back into a directory the walk is already inside is skipped, as the generator's own walk
     * skips it, so the walk ends. The scanner of the compiler follows such a link instead, again and again until
     * the file system refuses to resolve the path, and so lists the files below it once more under every longer
     * path. javac reads a file once whatever paths name it, so both select the same files.</p>
     *
     * @param root a source root of the compilation
     * @return the selected files, none when the root does not exist
     * @throws IOException if the root, or a directory below it, cannot be walked
     */
    List<Path> select(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> selected = new ArrayList<>();
        Files.walkFileTree(root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        if (attributes.isRegularFile() && file.getFileName().toString().endsWith(".java")
                                && selects(relativePath(root, file))) {
                            selected.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                        if (failure instanceof FileSystemLoopException) {
                            return FileVisitResult.CONTINUE;
                        }
                        throw failure;
                    }
                });
        selected.sort(Comparator.<Path>naturalOrder());
        return List.copyOf(selected);
    }

    /** Whether the relative {@code path} of one source is included, and excluded neither here nor by default. */
    private boolean selects(String path) {
        boolean included = includes.isEmpty()
                || includes.stream().anyMatch((String pattern) -> matches(pattern, path));
        return included && excludes.stream().noneMatch((String pattern) -> matches(pattern, path))
                && DEFAULT_EXCLUDES.stream().noneMatch((String pattern) -> matches(pattern, path));
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
