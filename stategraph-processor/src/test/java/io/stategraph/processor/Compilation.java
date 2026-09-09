package io.stategraph.processor;

import io.stategraph.codegen.GenerationRequest;
import io.stategraph.codegen.GenerationResult;
import io.stategraph.codegen.StateGraphCodegen;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Runs the two stages of a consumer build over a fixture: the pre-javac generator first, then javac with the
 * processor and the manifest the generator wrote.
 *
 * <p>Every stage works on real files, laid out the way a build does: {@code originals/} holds the fixture,
 * {@code bootstrap/} the sources the generator emits, {@code processor/} the ones the processor emits and
 * {@code classes/} the compiled result. Compilation always uses the project's own strict options,
 * {@code -Xlint:all,-serial -Werror}, so a fixture that only compiles under laxer rules is a failure here.</p>
 */
final class Compilation {

    private static final String COMPILATION_ID = "fx:test:main";

    /** One prepared fixture: the request the generator ran for and what it produced, manifest included. */
    record Prepared(GenerationRequest request, GenerationResult generation) { }

    /** What a compilation produced: diagnostics, the processor's source output, and a loader over the classes. */
    record Result(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics, Path output, ClassLoader classes) {

        List<String> errors() {
            List<String> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    errors.add(diagnostic.getMessage(null));
                }
            }
            return errors;
        }

        boolean hasError(String code) {
            return countError(code) > 0;
        }

        long countError(String code) {
            long count = 0;
            for (String error : errors()) {
                if (error.contains("[" + code + "]")) {
                    count++;
                }
            }
            return count;
        }

        /** Line of the first error carrying the code, or -1. */
        long lineOf(String code) {
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR && diagnostic.getMessage(null).contains("[" + code + "]")) {
                    return diagnostic.getLineNumber();
                }
            }
            return -1;
        }

        String describe() {
            StringBuilder text = new StringBuilder();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
                text.append(diagnostic.getKind()).append(' ').append(diagnostic.getLineNumber()).append(": ")
                        .append(diagnostic.getMessage(null)).append('\n');
            }
            return text.toString();
        }

        boolean hasGenerated(String qualifiedName) {
            return Files.exists(output.resolve(qualifiedName.replace('.', '/') + ".java"));
        }

        String generated(String qualifiedName) {
            try {
                return Files.readString(output.resolve(qualifiedName.replace('.', '/') + ".java"), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("no generated source for " + qualifiedName + "; diagnostics:\n" + describe(), e);
            }
        }

        /** Every class comes from the same loader, so reflection over two generated types sees one identity per type. */
        Class<?> load(String qualifiedName) {
            try {
                return classes.loadClass(qualifiedName);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private Compilation() { }

    /**
     * Materializes {@code sources} under a fresh temporary project and runs the generator over them.
     * {@code sources} maps qualified names to source text, one top-level type each.
     */
    static Prepared prepare(Map<String, String> sources) {
        return prepare(sources, List.of());
    }

    /**
     * The same, with {@code extraClasspath} appended to the classpath of the request. The entries reach the
     * generator, which reads the indexes their producers published, and javac, which compiles against their
     * classes: a dependency a build puts on one of the two only is a dependency of neither stage.
     */
    static Prepared prepare(Map<String, String> sources, List<Path> extraClasspath) {
        GenerationRequest request = materialize(sources, extraClasspath);
        try {
            return new Prepared(request, StateGraphCodegen.generate(request));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Compiles the originals and the generated bootstrap of {@code prepared} with the processor. */
    static Result compilePrepared(Prepared prepared) {
        return compilePrepared(prepared, List.of());
    }

    /**
     * Compiles {@code prepared} with {@code extra} appended to the standard options, so a test can replace the
     * processor javac runs. Lifecycle steps around javac arrive with task 10; nothing runs them yet.
     */
    static Result compilePrepared(Prepared prepared, List<String> extra) {
        GenerationRequest request = prepared.request();
        List<Path> files = new ArrayList<>(javaFiles(request.sources().get(0)));
        files.addAll(javaFiles(request.sourcesOutput()));
        List<String> options = new ArrayList<>(standardOptions(request));
        options.add("-Astategraph.annotationManifest=" + prepared.generation().annotationManifest());
        options.addAll(extra);
        return run(files, options, request);
    }

    /** Prepares {@code sources} and compiles them, which is what a consumer build does. */
    static Result compileTyped(Map<String, String> sources) {
        return compilePrepared(prepare(sources));
    }

    /** The same, over a classpath that also carries {@code extraClasspath}. */
    static Result compileTyped(Map<String, String> sources, List<Path> extraClasspath) {
        return compilePrepared(prepare(sources, extraClasspath));
    }

    /**
     * The pipeline the legacy string-based fixtures still go through: the generator sees no
     * {@code @GenerateGraphApi} in them, so it only writes the compilation carrier and a manifest that
     * describes an empty model, and the processor keeps building them with its legacy frontend.
     */
    static Result compile(Map<String, String> sources) {
        return compileTyped(sources);
    }

    /**
     * Compiles {@code sources} with the processor but without any generated bootstrap and without a manifest
     * option, so a test can observe what the processor reports when it has no manifest at all.
     */
    static Result compileRaw(Map<String, String> sources, List<String> extra) {
        GenerationRequest request = materialize(sources, List.of());
        List<String> options = new ArrayList<>(standardOptions(request));
        options.addAll(extra);
        return run(javaFiles(request.sources().get(0)), options, request);
    }

    /** Writes {@code sources} under a fresh temporary project and describes the compilation they belong to. */
    private static GenerationRequest materialize(Map<String, String> sources, List<Path> extraClasspath) {
        try {
            Path project = Files.createTempDirectory("stategraph-processor-");
            Path originals = write(project.resolve("originals"), sources);
            List<Path> classpath = new ArrayList<>(classpath());
            classpath.addAll(extraClasspath);
            return new GenerationRequest(project, List.of(originals), List.copyOf(classpath),
                    project.resolve("bootstrap"), project.resolve("processor"), project.resolve("classes"),
                    project.resolve("work"), COMPILATION_ID);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** javac compiles against the very classpath the request names, so both stages see one set of dependencies. */
    private static List<String> standardOptions(GenerationRequest request) {
        List<String> entries = new ArrayList<>();
        for (Path entry : request.classpath()) {
            entries.add(entry.toString());
        }
        return List.of(
                "-processor", StateGraphProcessor.class.getName(),
                "-classpath", String.join(File.pathSeparator, entries),
                "-d", request.classesOutput().toString(),
                "-s", request.processorSourcesOutput().toString(),
                "-Xlint:all,-serial", "-Werror");
    }

    private static Result run(List<Path> files, List<String> options, GenerationRequest request) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try {
            Path classes = Files.createDirectories(request.classesOutput());
            Path generated = Files.createDirectories(request.processorSourcesOutput());
            try (StandardJavaFileManager manager = compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8)) {
                manager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
                manager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(generated));
                boolean success = Boolean.TRUE.equals(compiler.getTask(null, manager, collector, options, null,
                        manager.getJavaFileObjectsFromPaths(files)).call());
                URL[] urls = {classes.toUri().toURL()};
                return new Result(success, collector.getDiagnostics(), generated,
                        new URLClassLoader(urls, Compilation.class.getClassLoader()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes each source at the package path its qualified name implies and returns {@code root}. */
    private static Path write(Path root, Map<String, String> sources) throws IOException {
        Files.createDirectories(root);
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = root.resolve(source.getKey().replace('.', File.separatorChar) + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
        }
        return root;
    }

    /** Every {@code .java} file under {@code root}, in a stable order; an absent root contributes nothing. */
    private static List<Path> javaFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The classpath of the test JVM, which is what the processor and the generator resolve against. */
    private static List<Path> classpath() {
        List<Path> entries = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.isEmpty()) {
                entries.add(Path.of(entry));
            }
        }
        return List.copyOf(entries);
    }
}
