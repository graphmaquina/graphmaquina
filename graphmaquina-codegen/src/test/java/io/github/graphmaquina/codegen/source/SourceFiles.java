package io.github.graphmaquina.codegen.source;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
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
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Test support for the tests of the generator: materializes source texts on disk, exposes the classpath of the
 * test JVM, compiles a fixture into a jar so binary headers are exercised for real, and compiles original plus
 * generated sources under the project's own strict compiler options.
 */
public final class SourceFiles {

    private SourceFiles() {
    }

    /**
     * Writes each entry under {@code directory} at the package path its qualified type name implies and returns
     * the written paths, ordered by qualified name so a test never depends on map iteration order.
     */
    public static List<Path> write(Path directory, Map<String, String> sources) {
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
    public static List<Path> classpath() {
        List<Path> entries = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.isEmpty()) {
                entries.add(Path.of(entry));
            }
        }
        return List.copyOf(entries);
    }

    /**
     * Compiles {@code sources} with {@code -proc:none --release 21} and packs the result into {@code jar}. The
     * sources stay in {@code jar-sources} and the classes in {@code jar-classes}, both under {@code workDirectory}.
     */
    public static Path compileToJar(Path workDirectory, Map<String, String> sources, Path jar) {
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

    /**
     * Compiles {@code files} against the test classpath with {@code -proc:none --release 21} and the options every
     * module of this project compiles with, {@code -Xlint:all,-serial -Werror}, and returns what javac reported.
     */
    public static Compilation compileStrict(List<Path> files, Path classes) {
        return compileStrict(files, classes, List.of());
    }

    /**
     * The same, with {@code extra} appended, so a test can compile a fixture under a debug setting of its own,
     * for example {@code -g:none}, which drops the attributes a class output is normally read back through.
     */
    public static Compilation compileStrict(List<Path> files, Path classes, List<String> extra) {
        return compileStrict(files, classes, extra, StandardCharsets.UTF_8);
    }

    /**
     * The same, reading every source in {@code encoding}, as javac reads the sources of a project that declares
     * that encoding. The encoding is the file manager's own: javac lets it win over an {@code -encoding} option.
     */
    public static Compilation compileStrict(List<Path> files, Path classes, Charset encoding) {
        return compileStrict(files, classes, List.of(), encoding);
    }

    private static Compilation compileStrict(List<Path> files, Path classes, List<String> extra, Charset encoding) {
        try {
            Files.createDirectories(classes);
            List<String> options =
                new ArrayList<>(List.of("-proc:none", "-Xlint:all,-serial", "-Werror", "--release", "21"));
            options.addAll(extra);
            return run(files, classes, options, encoding);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void compile(List<Path> files, Path classes) throws IOException {
        Compilation compilation =
                run(files, classes, List.of("-proc:none", "--release", "21"), StandardCharsets.UTF_8);
        if (!compilation.success()) {
            throw new IllegalStateException("fixture compilation failed: " + compilation.diagnostics());
        }
    }

    private static Compilation run(List<Path> files, Path classes, List<String> options, Charset encoding)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, encoding)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath());
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null,
                    fileManager.getJavaFileObjectsFromPaths(files));
            boolean success = Boolean.TRUE.equals(task.call());
            return new Compilation(success, List.copyOf(diagnostics.getDiagnostics()));
        }
    }

    /** What one compilation reported: whether javac succeeded and every diagnostic it produced, in order. */
    public record Compilation(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) {

        /** The javac codes of the diagnostics, for assertions that must not depend on a localized message. */
        public List<String> codes() {
            return diagnostics.stream().map(Diagnostic::getCode).toList();
        }

        /** The diagnostics rendered in the root locale, for a failure message a developer can read. */
        public List<String> messages() {
            return diagnostics.stream().map(diagnostic -> diagnostic.getMessage(Locale.ROOT)).toList();
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
