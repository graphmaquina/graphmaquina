package io.github.graphmaquina.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ProcessorPath} to the way the processor class loader of javac searches a processor path: directories,
 * jars, the {@code Class-Path} of a jar's manifest, and the versioned entries of a multi-release jar for the release
 * of the JVM javac runs in, whatever JVM these tests run in.
 */
class ProcessorPathTest {

    /** The class file every path here looks for. */
    private static final String FILE = "p/Probe.class";

    @Test
    void aDirectoryOrAJarThatHoldsTheFileReachesIt(@TempDir Path directory) throws IOException {
        Path classes = directory.resolve("classes");
        Files.createDirectories(classes.resolve(FILE).getParent());
        Files.writeString(classes.resolve(FILE), "class", StandardCharsets.UTF_8);
        Path holding = jar(directory.resolve("holding.jar"), new Manifest(), FILE);
        Path other = jar(directory.resolve("other.jar"), new Manifest(), "q/Other.class");
        Path text = directory.resolve("notes.txt");
        Files.writeString(text, "no archive", StandardCharsets.UTF_8);

        assertTrue(ProcessorPath.reaches(List.of(classes), FILE, 21));
        assertTrue(ProcessorPath.reaches(List.of(other, holding), FILE, 21));
        assertFalse(ProcessorPath.reaches(List.of(other, text, directory.resolve("absent.jar")), FILE, 21));
    }

    /**
     * A jar reaches what the {@code Class-Path} of its manifest names, relative to the jar or as a file URL, and the
     * search ends when two manifests name each other.
     */
    @Test
    void aJarReachesWhatTheClassPathOfItsManifestNames(@TempDir Path directory) throws IOException {
        jar(Files.createDirectories(directory.resolve("lib")).resolve("holding.jar"), new Manifest(), FILE);
        Path relative = jar(directory.resolve("relative.jar"), classPath("lib/holding.jar"));
        Path absolute = jar(directory.resolve("absolute.jar"),
                classPath(directory.resolve("lib/holding.jar").toUri().toString()));
        Path one = jar(directory.resolve("one.jar"), classPath("two.jar"));
        jar(directory.resolve("two.jar"), classPath("one.jar"));

        assertTrue(ProcessorPath.reaches(List.of(relative), FILE, 21));
        assertTrue(ProcessorPath.reaches(List.of(absolute), FILE, 21));
        assertFalse(ProcessorPath.reaches(List.of(one), FILE, 21));
    }

    /**
     * A multi-release jar that holds the file only for Java 22 reaches it for javac of Java 22 and later, and not for
     * javac of Java 21, whichever JVM searches it. A directory laid out the same way is no multi-release jar and
     * reaches it for no release.
     */
    @Test
    void aMultiReleaseJarReachesItsVersionedEntriesForTheReleaseOfJavac(@TempDir Path directory) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
        Path versioned = jar(directory.resolve("versioned.jar"), manifest, "META-INF/versions/22/" + FILE);
        Path exploded = directory.resolve("exploded");
        Files.createDirectories(exploded.resolve("META-INF/versions/22/" + FILE).getParent());
        Files.writeString(exploded.resolve("META-INF/versions/22/" + FILE), "class", StandardCharsets.UTF_8);

        assertFalse(ProcessorPath.reaches(List.of(versioned), FILE, 21));
        assertTrue(ProcessorPath.reaches(List.of(versioned), FILE, 22));
        assertTrue(ProcessorPath.reaches(List.of(versioned), FILE, 25));
        assertFalse(ProcessorPath.reaches(List.of(exploded), FILE, 25));
    }

    /**
     * A {@code Class-Path} name that holds a character a URI does not allow, such as {@code [}, {@code {} or
     * {@code ^}, names a jar the processor class loader of javac reaches, since that loader resolves the name against
     * the jar as a URL.
     */
    @Test
    void aClassPathNameThatIsNoUriReachesTheJarItNames(@TempDir Path directory) throws IOException {
        Path lib = Files.createDirectories(directory.resolve("lib"));
        for (String name : List.of("proc[1].jar", "proc{x}.jar", "proc^x.jar")) {
            jar(lib.resolve(name), new Manifest(), FILE);
            Path pathing = jar(directory.resolve("pathing to " + name.charAt(4) + ".jar"), classPath("lib/" + name));

            assertTrue(ProcessorPath.reaches(List.of(pathing), FILE, 21), name);
        }
    }

    /**
     * Where the release of javac's JDK is unknown, a multi-release jar reaches the file under any of its versioned
     * entries, whichever JVM searches it: the check refuses no path javac might take.
     */
    @Test
    void anUnknownReleaseReadsEveryVersionedEntry(@TempDir Path directory) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
        Path versioned = jar(directory.resolve("versioned.jar"), manifest, "META-INF/versions/99/" + FILE);
        Path other = jar(directory.resolve("other.jar"), manifest, "META-INF/versions/99/q/Other.class");

        assertTrue(ProcessorPath.reaches(List.of(versioned), FILE, 0));
        assertFalse(ProcessorPath.reaches(List.of(other), FILE, 0));
    }

    /**
     * A release far beyond any JDK, as a damaged {@code release} file may declare, is read like any other, without
     * a lookup for each release below it.
     */
    @Test
    void anAbsurdReleaseIsReadWithoutALookupForEachReleaseBelowIt(@TempDir Path directory) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
        Path versioned = jar(directory.resolve("versioned.jar"), manifest, "META-INF/versions/22/" + FILE);

        assertTrue(assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> ProcessorPath.reaches(List.of(versioned), FILE, 20_260_101)));
    }

    /** A manifest whose {@code Class-Path} is {@code value}. */
    private static Manifest classPath(String value) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, value);
        return manifest;
    }

    /** Writes a jar at {@code jar} with {@code manifest} and an entry for each of {@code entries}. */
    private static Path jar(Path jar, Manifest manifest, String... entries) throws IOException {
        manifest.getMainAttributes().putIfAbsent(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (String entry : entries) {
                out.putNextEntry(new JarEntry(entry));
                out.write("class".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }
}
