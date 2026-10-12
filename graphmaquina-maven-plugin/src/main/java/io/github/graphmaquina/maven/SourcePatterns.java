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
import java.util.Optional;
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
 * separated by {@code /}, in which a pair of backslashes stands for that separator. One spelled
 * {@code %ant[...]} is the pattern between the brackets, read without the rule for a trailing separator, which
 * plexus-utils applies to the whole pattern before it unwraps one: {@code %ant[fx/drafts/]} matches the path
 * {@code fx/drafts} alone, and no file below it. Either spelling needs at least two characters between its
 * brackets; a shorter one is a plain pattern, brackets included. A pattern is trimmed as {@link String#trim()}
 * trims it. Without includes every source is included.</p>
 *
 * <p>The scanner reaches a source only through the directories above it, and it enters a directory below the root
 * only when an include matches that directory and no exclude does, or when an include could match a path below
 * it. plexus-utils judges that a regular expression always could, and that any other include could when its names
 * match the names of the directory one by one, up to its first {@code **} or to the last name of the directory,
 * whichever comes first, unless the include starts with a separator: such an include matches a path as if it had
 * none, but never lets the scanner into a directory it does not match itself. So {@code /fx/**} selects the
 * sources under {@code fx}, a directory it matches, {@code /io/github/graphmaquina/generated/**} alone selects
 * nothing, as it matches none of the directories above those sources, and an excluded directory is entered only
 * when an include could match a path below it. Without includes every directory is entered.</p>
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
     * scanner of the compiler follows them, entering only the directories it enters, and leaving out what the
     * default excludes name.
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

    /**
     * Whether the scanner of the compiler selects the source at {@code path} under a root that holds it: whether it
     * enters every directory on the way to it, and the patterns include it and exclude it neither here nor by
     * default.
     *
     * @param path the path of a source relative to its root, its names separated by {@code /}
     * @return {@code true} if javac compiles that source
     */
    boolean selects(String path) {
        return skippedDirectory(path).isEmpty() && matched(path);
    }

    /**
     * The first directory on the way from a root to {@code path} that the scanner of the compiler does not enter, so
     * that it never reaches that path.
     *
     * @param path a path relative to its root, its names separated by {@code /}
     * @return that directory, relative to the root; empty when the scanner enters every directory above the path
     */
    Optional<String> skippedDirectory(String path) {
        List<String> names = names(path);
        for (int depth = 1; depth < names.size(); depth++) {
            String directory = String.join("/", names.subList(0, depth));
            if (!entered(directory)) {
                return Optional.of(directory);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether the scanner of the compiler, once it reaches the directory at the relative {@code path}, enters it: when
     * the patterns include it and exclude it neither here nor by default, or when an include could match a path below
     * it. Without includes it enters every directory.
     */
    private boolean entered(String path) {
        return includes.isEmpty() || matched(path)
                || includes.stream().anyMatch((String pattern) -> couldMatchBelow(pattern, path));
    }

    /** Whether the relative {@code path} is included, and excluded neither here nor by default. */
    private boolean matched(String path) {
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
        String normalized = normalized(pattern);
        if (spelled(normalized, REGEX)) {
            return path.matches(normalized.substring(REGEX.length(), normalized.length() - 1));
        }
        return matches(names(ant(normalized)), 0, names(path), 0);
    }

    /**
     * Whether plexus-utils judges that an include could match a path below the directory at the relative
     * {@code path}, which lets its scanner into that directory: a regular expression always could; any other include
     * could when its names match the names of the directory one by one, up to its first {@code **} or to the last
     * name of the directory, whichever comes first, provided that the include starts with a separator exactly when
     * the path does. A relative path never does, so an include that starts with a separator never could. plexus-utils
     * also tries the path with every backslash read as a separator, which only a name that holds a backslash changes.
     */
    private static boolean couldMatchBelow(String pattern, String path) {
        String normalized = normalized(pattern);
        if (spelled(normalized, REGEX)) {
            return true;
        }
        String ant = ant(normalized);
        return startMatches(ant, path) || startMatches(ant, path.replace('\\', '/'));
    }

    /** One of the two tests of {@link #couldMatchBelow}, over {@code path} as it is spelled. */
    private static boolean startMatches(String ant, String path) {
        if (ant.startsWith("/") != path.startsWith("/")) {
            return false;
        }
        List<String> pattern = names(ant);
        List<String> directory = names(path);
        int at = 0;
        while (at < pattern.size() && at < directory.size()) {
            if (pattern.get(at).equals("**")) {
                return true;
            }
            if (!matchesName(pattern.get(at), directory.get(at))) {
                return false;
            }
            at++;
        }
        return at == directory.size() || at < pattern.size();
    }

    /**
     * {@code pattern} normalized as plexus-utils normalizes the whole pattern before it reads the spelling inside it:
     * trimmed; then, in a pattern that starts with {@code %regex[}, every pair of backslashes turned into the
     * separator, and in any other every backslash, after which {@code **} is appended to it when it ends with the
     * separator.
     */
    private static String normalized(String pattern) {
        String normalized = pattern.trim();
        if (normalized.startsWith(REGEX)) {
            return normalized.replace("\\\\", "/");
        }
        normalized = normalized.replace('\\', '/');
        return normalized.endsWith("/") ? normalized + "**" : normalized;
    }

    /** The Ant pattern a normalized pattern that is no regular expression stands for, unwrapped from {@code %ant[]}. */
    private static String ant(String normalized) {
        return spelled(normalized, ANT) ? normalized.substring(ANT.length(), normalized.length() - 1) : normalized;
    }

    /**
     * Whether plexus-utils reads {@code pattern} as spelled with {@code prefix}: it starts with that prefix, ends with
     * {@code ]} and holds at least two characters between them.
     */
    private static boolean spelled(String pattern, String prefix) {
        return pattern.length() > prefix.length() + 2 && pattern.startsWith(prefix) && pattern.endsWith("]");
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
