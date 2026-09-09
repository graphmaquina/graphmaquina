package io.stategraph.codegen.source;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Test support for the source reading layer: materializes source texts on disk, exposes the classpath of the
 * test JVM and compiles a fixture into a temporary jar so binary headers are exercised for real.
 */
final class SourceFiles {

    private SourceFiles() {
    }

    /**
     * Writes each entry under {@code directory} at the package path its qualified type name implies and returns
     * the written paths, ordered by qualified name so a test never depends on map iteration order.
     */
    static List<Path> write(Path directory, Map<String, String> sources) {
        List<String> names = new ArrayList<>(sources.keySet());
        names.sort(String::compareTo);
        List<Path> written = new ArrayList<>();
        for (String name : names) {
            Path file = directory.resolve(name.replace('.', File.separatorChar) + ".java");
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, sources.get(name), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            written.add(file);
        }
        return List.copyOf(written);
    }

    /** The classpath of the test JVM, which carries the StateGraph markers and {@code Machine} as binaries. */
    static List<Path> classpath() {
        List<Path> entries = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.isEmpty()) {
                entries.add(Path.of(entry));
            }
        }
        return List.copyOf(entries);
    }

    /** Compiles {@code sources} with {@code -proc:none} and packs the result into {@code jar}. */
    static Path compileToJar(Path workDirectory, Map<String, String> sources, Path jar) {
        try {
            Path sourceRoot = Files.createDirectories(workDirectory.resolve("jar-sources"));
            Path classes = Files.createDirectories(workDirectory.resolve("jar-classes"));
            compile(write(sourceRoot, sources), classes);
            pack(classes, jar);
            return jar;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void compile(List<Path> files, Path classes) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath());
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics,
                    List.of("-proc:none", "--release", "21"), null,
                    fileManager.getJavaFileObjectsFromPaths(files));
            if (!Boolean.TRUE.equals(task.call())) {
                throw new IllegalStateException("fixture compilation failed: " + diagnostics.getDiagnostics());
            }
        }
    }

    private static void pack(Path classes, Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream target = Files.newOutputStream(jar);
                JarOutputStream out = new JarOutputStream(target)) {
            List<Path> entries;
            try (Stream<Path> walk = Files.walk(classes)) {
                entries = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path entry : entries) {
                out.putNextEntry(new JarEntry(classes.relativize(entry).toString().replace(File.separatorChar, '/')));
                out.write(Files.readAllBytes(entry));
                out.closeEntry();
            }
        }
    }
}
