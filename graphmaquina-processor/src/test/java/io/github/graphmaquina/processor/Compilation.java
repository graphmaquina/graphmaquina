package io.github.graphmaquina.processor;

import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.GenerationResult;
import io.github.graphmaquina.codegen.StateGraphCodegen;
import io.github.graphmaquina.codegen.output.CompilationOutputs;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
 * {@code bootstrap/} the sources the generator emits, {@code processor/} the ones the processor emits,
 * {@code classes/} the compiled result and {@code work/} the manifest and the inventories. Compilation always uses
 * the project's own strict options, {@code -Xlint:all,-serial -Werror}, so a fixture that only compiles under
 * laxer rules is a failure here.</p>
 */
final class Compilation {

    private static final String COMPILATION_ID = "fx:test:main";

    /**
     * The one directory under the system's temporary directory that holds every directory this JVM hands out,
     * removed with everything in it when the JVM shuts down. A JVM killed outright runs no shutdown hook, so it
     * leaves this one directory behind.
     */
    private static final Path TEMPORARY_ROOT = temporaryRoot();

    /** One prepared fixture: the request the generator ran for and what it produced, manifest included. */
    record Prepared(GenerationRequest request, GenerationResult generation) { }

    /**
     * What a compilation produced: whether it succeeded, its diagnostics, the processor's source output and a loader
     * over the classes.
     */
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
        return prepare(temporaryDirectory(), sources, extraClasspath);
    }

    /**
     * The same, in a project directory a test chooses, so consecutive generations and compilations land in the
     * very same outputs a developer's build reuses. The originals are replaced wholesale, which is what editing
     * a source tree does; everything the two stages produced before is left for the lifecycle to retire.
     */
    static Prepared prepare(Path project, Map<String, String> sources) {
        return prepare(project, sources, List.of());
    }

    /**
     * The same, for a compilation whose sources are written and read in {@code encoding}: the request names it, so
     * the generator reads and writes the sources in it, and javac is given it as {@code -encoding}, so it reads them
     * and its {@code Filer} writes what the processor generates in it too.
     */
    static Prepared prepare(Map<String, String> sources, Charset encoding) {
        return prepare(temporaryDirectory(), sources, List.of(), encoding);
    }

    private static Prepared prepare(Path project, Map<String, String> sources, List<Path> extraClasspath) {
        return prepare(project, sources, extraClasspath, GenerationRequest.DEFAULT_ENCODING);
    }

    private static Prepared prepare(Path project, Map<String, String> sources, List<Path> extraClasspath,
            Charset encoding) {
        GenerationRequest request = materialize(project, sources, extraClasspath, encoding);
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
     * processor javac runs. The build's own lifecycle runs around javac: {@code prepare} retires what the
     * previous cycle owned before the compiler starts, and {@code complete} publishes the index and the
     * inventory only when javac succeeded, exactly as the Gradle and Maven plugins drive it.
     */
    static Result compilePrepared(Prepared prepared, List<String> extra) {
        GenerationRequest request = prepared.request();
        try {
            CompilationOutputs.prepare(request);
            Result result = compileWithoutLifecycle(prepared, extra);
            if (result.success()) {
                CompilationOutputs.complete(request);
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Compiles {@code prepared} without that lifecycle, for a test whose compilation is not one a build would
     * prepare and complete: one that replaces the StateGraph processor with a test processor of its own, so
     * nothing publishes an index, or one that corrupts the manifest after the generator wrote it.
     */
    static Result compileWithoutLifecycle(Prepared prepared, List<String> extra) {
        GenerationRequest request = prepared.request();
        List<Path> files = new ArrayList<>(javaFiles(request.sources().get(0)));
        files.addAll(javaFiles(request.sourcesOutput()));
        List<String> options = new ArrayList<>(standardOptions(request));
        options.add("-Agraphmaquina.annotationManifest=" + prepared.generation().annotationManifest());
        options.addAll(extra);
        return run(files, options, request);
    }

    /**
     * Prepares the outputs of {@code prepared} and runs javac the way a build that hands it only some sources
     * does: the originals named by {@code explicit} and the generated bootstrap are given to javac, and every
     * other original is left to the source path, from which javac compiles, under {@code -implicit:class}, the
     * ones the compilation needs, without processing them. Completion does not run: the test observes what javac
     * and the processor did.
     */
    static Result compileWithImplicitSources(Prepared prepared, List<String> explicit) {
        return compileWithSourcePath(prepared, explicit, List.of("-implicit:class"));
    }

    /**
     * Prepares the outputs of {@code prepared} and runs javac the way Maven's compiler plugin does: the originals
     * named by {@code listed} and the generated bootstrap are given to javac, the source path holds the originals
     * and the bootstrap, and no {@code -implicit} policy is set. Any other file under the originals stands for one
     * the build leaves out of the compilation. Should javac read such a file, it reports what is wrong in it, or
     * else warns that it compiled a file no processor saw, which {@code -Werror} turns into a failure. Completion
     * does not run.
     */
    static Result compileWithSourcePath(Prepared prepared, List<String> listed) {
        return compileWithSourcePath(prepared, listed, List.of());
    }

    /**
     * Prepares the outputs of {@code prepared}, then runs javac over {@code explicit} and the bootstrap, with the
     * source path and then {@code extra}.
     */
    private static Result compileWithSourcePath(Prepared prepared, List<String> explicit, List<String> extra) {
        GenerationRequest request = prepared.request();
        try {
            CompilationOutputs.prepare(request);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Path originals = request.sources().get(0);
        List<Path> files = new ArrayList<>();
        for (String name : explicit) {
            files.add(originals.resolve(name.replace('.', File.separatorChar) + ".java"));
        }
        files.addAll(javaFiles(request.sourcesOutput()));
        List<String> options = new ArrayList<>(standardOptions(request));
        options.add("-Agraphmaquina.annotationManifest=" + prepared.generation().annotationManifest());
        options.addAll(List.of("-sourcepath", originals + File.pathSeparator + request.sourcesOutput()));
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
     * Compiles {@code sources} with the processor but without any generated bootstrap and without a manifest
     * option, so a test can observe what the processor reports when it has no manifest at all.
     */
    static Result compileRaw(Map<String, String> sources, List<String> extra) {
        GenerationRequest request = materialize(temporaryDirectory(), sources, List.of(),
                GenerationRequest.DEFAULT_ENCODING);
        List<String> options = new ArrayList<>(standardOptions(request));
        options.addAll(extra);
        return run(javaFiles(request.sources().get(0)), options, request);
    }

    /**
     * Writes {@code sources} under {@code project} in {@code encoding} and describes the compilation they belong
     * to, which reads them in that encoding.
     */
    private static GenerationRequest materialize(Path project, Map<String, String> sources,
            List<Path> extraClasspath, Charset encoding) {
        try {
            Path originals = write(project.resolve("originals"), sources, encoding);
            List<Path> classpath = new ArrayList<>(classpath());
            classpath.addAll(extraClasspath);
            return new GenerationRequest(project, List.of(originals), List.copyOf(classpath),
                    project.resolve("bootstrap"), project.resolve("processor"), project.resolve("classes"),
                    project.resolve("work"), COMPILATION_ID, GenerationRequest.DEFAULT_RELEASE, encoding);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A new empty directory under {@link #TEMPORARY_ROOT}, so it lasts as long as this JVM: a test's compilations
     * and their classes stay readable for as long as the test uses them, and they go with the root.
     */
    static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory(TEMPORARY_ROOT, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Creates {@link #TEMPORARY_ROOT} and has it removed when the JVM exits. */
    private static Path temporaryRoot() {
        try {
            Path root = Files.createTempDirectory("graphmaquina-processor-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> removeAtExit(root)));
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Removes {@code root}; what cannot be removed stays where it is, and the standard error says so. */
    private static void removeAtExit(Path root) {
        try {
            deleteRecursively(root);
        } catch (IOException | UncheckedIOException e) {
            System.err.println("could not remove the temporary directory " + root + ": " + e);
        }
    }

    /**
     * Runs javac over {@code prepared} once more, without the build's lifecycle and with the class output of the
     * previous run on its classpath, which is what an IDE or a plain rerun of javac over the same directories
     * does: nothing retires what the previous compilation produced.
     */
    static Result recompileWithoutLifecycle(Prepared prepared) {
        GenerationRequest request = prepared.request();
        List<Path> files = new ArrayList<>(javaFiles(request.sources().get(0)));
        files.addAll(javaFiles(request.sourcesOutput()));
        List<String> options = new ArrayList<>(standardOptions(request, List.of(request.classesOutput())));
        options.add("-Agraphmaquina.annotationManifest=" + prepared.generation().annotationManifest());
        return run(files, options, request);
    }

    /** javac compiles against the very classpath the request names, so both stages see one set of dependencies. */
    private static List<String> standardOptions(GenerationRequest request) {
        return standardOptions(request, List.of());
    }

    /** The same, with {@code extraClasspath} after the request's own classpath, for javac alone. */
    private static List<String> standardOptions(GenerationRequest request, List<Path> extraClasspath) {
        List<String> entries = new ArrayList<>();
        for (Path entry : request.classpath()) {
            entries.add(entry.toString());
        }
        for (Path entry : extraClasspath) {
            entries.add(entry.toString());
        }
        return List.of(
                "-processor", StateGraphProcessor.class.getName(),
                "-classpath", String.join(File.pathSeparator, entries),
                "-d", request.classesOutput().toString(),
                "-s", request.processorSourcesOutput().toString(),
                "-encoding", request.encoding().name(),
                "-Xlint:all,-serial", "-Werror");
    }

    private static Result run(List<Path> files, List<String> options, GenerationRequest request) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try {
            Path classes = Files.createDirectories(request.classesOutput());
            Path generated = Files.createDirectories(request.processorSourcesOutput());
            try (StandardJavaFileManager manager = compiler.getStandardFileManager(collector, null,
                    request.encoding())) {
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

    /**
     * Writes each source at the package path its qualified name implies, in {@code encoding}, and returns
     * {@code root}, which is emptied first: a source a test no longer declares must be gone, as it is after an edit.
     */
    private static Path write(Path root, Map<String, String> sources, Charset encoding) throws IOException {
        deleteRecursively(root);
        Files.createDirectories(root);
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = root.resolve(source.getKey().replace('.', File.separatorChar) + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), encoding);
        }
        return root;
    }

    /** Removes {@code root} and everything below it, deepest entry first; an absent root is nothing to do. */
    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
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
