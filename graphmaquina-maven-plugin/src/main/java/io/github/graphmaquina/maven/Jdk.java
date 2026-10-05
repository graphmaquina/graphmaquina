package io.github.graphmaquina.maven;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The JDK a {@code java} or {@code javac} executable belongs to: how this plugin tells that the generator and
 * javac run on the same JDK, and which Java release a JDK declares.
 *
 * <p>An executable is found the way the process that runs it is found: a bare name on the {@code PATH}, any other
 * relative path against the project directory, where both the generator and a forked javac are started. Symbolic
 * links are followed to the launcher they lead to, so a link such as {@code /usr/bin/java} is attributed to the JDK
 * it runs. The home of that JDK is the directory above the {@code bin} directory of the launcher.</p>
 */
final class Jdk {

    /** The feature release at the start of a {@code JAVA_VERSION}, past the {@code 1.} of Java 8 and earlier. */
    private static final Pattern FEATURE = Pattern.compile("^(?:1\\.)?(\\d{1,9})");

    private Jdk() {
    }

    /**
     * The launcher {@code executable} names, as the process that runs it would find it.
     *
     * @param executable a path or a bare name, as the build configures it
     * @param directory the directory the process is started in
     * @return the launcher, as its real path when it exists
     */
    static Path launcher(String executable, Path directory) {
        Path path = Path.of(executable);
        Path found = path.isAbsolute() || path.getNameCount() > 1
            ? directory.resolve(path)
            : onPath(executable).orElse(directory.resolve(path));
        try {
            return found.toRealPath();
        } catch (IOException absent) {
            return found.toAbsolutePath().normalize();
        }
    }

    /**
     * The home of the JDK {@code executable} belongs to: the directory above the {@code bin} directory of its
     * launcher.
     *
     * @param executable a path or a bare name, as the build configures it
     * @param directory the directory the process is started in
     * @return the home of that JDK
     */
    static Path home(String executable, Path directory) {
        Path launcher = launcher(executable, directory);
        Path bin = launcher.getParent();
        return bin == null || bin.getParent() == null ? launcher : bin.getParent();
    }

    /**
     * The Java feature release the {@code release} file of a JDK home declares as its {@code JAVA_VERSION}.
     *
     * @param home the home of a JDK
     * @return the feature release, {@code 0} when the home declares none this plugin can read
     */
    static int release(Path home) {
        Properties release = new Properties();
        try (Reader reader = Files.newBufferedReader(home.resolve("release"), StandardCharsets.UTF_8)) {
            release.load(reader);
        } catch (IOException | IllegalArgumentException unreadable) {
            return 0;
        }
        Matcher feature = FEATURE.matcher(release.getProperty("JAVA_VERSION", "").replace("\"", "").strip());
        return feature.find() ? Integer.parseInt(feature.group(1)) : 0;
    }

    /**
     * Whether two JDK homes are the same directory, compared by path when one of them does not exist.
     *
     * @param one the home of one JDK
     * @param other the home of the other
     * @return {@code true} if both are the same JDK
     */
    static boolean same(Path one, Path other) {
        try {
            return Files.isSameFile(one, other);
        } catch (IOException unreadable) {
            return one.toAbsolutePath().normalize().equals(other.toAbsolutePath().normalize());
        }
    }

    /**
     * The first regular file named {@code name}, or {@code name.exe}, in the entries of the {@code PATH}, taken in
     * order, an empty entry or one that is no valid path skipped.
     */
    private static Optional<Path> onPath(String name) {
        String search = System.getenv("PATH");
        if (search == null) {
            return Optional.empty();
        }
        for (String entry : search.split(File.pathSeparator)) {
            for (String candidate : List.of(name, name + ".exe")) {
                try {
                    Path file = Path.of(entry, candidate);
                    if (!entry.isEmpty() && Files.isRegularFile(file)) {
                        return Optional.of(file);
                    }
                } catch (InvalidPathException unusable) {
                    // An entry the operating system could not search either.
                }
            }
        }
        return Optional.empty();
    }
}
