package io.github.graphmaquina.codegen.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that the classpath entry of a class file is found from the URI javac gives it, for a jar and for a
 * directory of classes kept under a path that is not plain ASCII, as a home directory with an accented name is.
 */
class PublishedEntriesTest {

    /** The class file both entries carry; javac never reads it here, so its bytes are not a class. */
    private static final String CLASS_FILE = "pay/Payments.class";

    @TempDir
    Path directory;

    @Test
    void findsTheJarOfAClassFileUnderAPathThatIsNotAscii() throws IOException {
        Path jar = Files.createDirectories(directory.resolve("producer ñ")).resolve("producer.jar");
        try (OutputStream target = Files.newOutputStream(jar);
                JarOutputStream out = new JarOutputStream(target)) {
            out.putNextEntry(new JarEntry(CLASS_FILE));
            out.write("not a class".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        assertEquals(Optional.of(jar.toAbsolutePath().normalize()), PublishedEntries.entry(uriOf(jar), "pay.Payments"));
    }

    @Test
    void findsTheDirectoryOfAClassFileUnderAPathThatIsNotAscii() throws IOException {
        Path classes = directory.resolve("producer ñ/classes");
        Files.createDirectories(classes.resolve(CLASS_FILE).getParent());
        Files.writeString(classes.resolve(CLASS_FILE), "not a class", StandardCharsets.UTF_8);

        assertEquals(Optional.of(classes.toAbsolutePath().normalize()),
                PublishedEntries.entry(uriOf(classes), "pay.Payments"));
    }

    /** The URI javac's own file manager gives {@code pay.Payments} on a classpath of {@code entry} alone. */
    private static URI uriOf(Path entry) throws IOException {
        try (StandardJavaFileManager files =
                ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of(entry));
            JavaFileObject read = files.getJavaFileForInput(StandardLocation.CLASS_PATH, "pay.Payments",
                    JavaFileObject.Kind.CLASS);
            return read.toUri();
        }
    }
}
