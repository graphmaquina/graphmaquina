package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.output.CompilationOutputs;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * The processor on a runtime whose class loader cannot see {@code com.sun.source}, as without the jdk.compiler
 * module: {@code Trees} fails to load with NoClassDefFoundError, and the processor must still start, report SG302
 * through Messager at the value and generate companions.
 */
class NoJdkCompilerFallbackTest {

    private static final String FORBIDDEN_RULE = """
            @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                action = TicketActionsRefs.Publish.class,
                recoveries = @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                    transition = SCHEDULE_RETRY, failsOn = RuntimeException.class))
            Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
            """;

    /** Loads everything but the platform from the test classpath first, and refuses every com.sun.source class. */
    private static final class RefusingLoader extends URLClassLoader {

        private final List<String> refused = new ArrayList<>();

        private RefusingLoader(URL[] urls) {
            super(urls, NoJdkCompilerFallbackTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("com.sun.source.")) {
                refused.add(name);
                throw new ClassNotFoundException(name);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null && !name.startsWith("java.") && !name.startsWith("javax.")
                        && !name.startsWith("jdk.") && !name.startsWith("com.sun.") && !name.startsWith("sun.")) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException notHere) {
                        loaded = null;
                    }
                }
                if (loaded == null) {
                    return super.loadClass(name, resolve);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    /** Trees is refused, SG302 stays at the exact value, and the valid example still generates its companion. */
    @Test
    void theProcessorWorksWhenComSunSourceCannotBeLoaded() throws Exception {
        try (RefusingLoader loader = loader()) {
            Map<String, String> forbidden = RecoveryFixtures.sources(FORBIDDEN_RULE);
            Compilation.Prepared prepared = Compilation.prepare(forbidden);
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            assertFalse(run(prepared, loader, diagnostics), diagnostics.getDiagnostics().toString());
            String literal = "RuntimeException.class";
            long start = forbidden.get("fx.Tickets").indexOf(literal);
            List<Diagnostic<? extends JavaFileObject>> sg302 = diagnostics.getDiagnostics().stream()
                    .filter((Diagnostic<? extends JavaFileObject> candidate) ->
                            candidate.getKind() == Diagnostic.Kind.ERROR
                                    && candidate.getMessage(null).startsWith("[SG302]"))
                    .toList();
            assertEquals(1, sg302.size(), diagnostics.getDiagnostics().toString());
            assertEquals(start, sg302.get(0).getStartPosition());
            assertEquals(start + literal.length(), sg302.get(0).getEndPosition());

            Compilation.Prepared valid = Compilation.prepare(RecoveryFixtures.example());
            DiagnosticCollector<JavaFileObject> validDiagnostics = new DiagnosticCollector<>();
            assertTrue(run(valid, loader, validDiagnostics), validDiagnostics.getDiagnostics().toString());
            assertTrue(Files.exists(valid.request().processorSourcesOutput().resolve("fx/TicketsRuntime.java")));
            assertTrue(loader.refused.contains("com.sun.source.util.Trees"), loader.refused.toString());
        }
    }

    private static RefusingLoader loader() throws MalformedURLException {
        List<URL> urls = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.isEmpty()) {
                urls.add(Path.of(entry).toUri().toURL());
            }
        }
        return new RefusingLoader(urls.toArray(new URL[0]));
    }

    private static boolean run(Compilation.Prepared prepared, RefusingLoader loader,
            DiagnosticCollector<JavaFileObject> diagnostics) throws Exception {
        GenerationRequest request = prepared.request();
        CompilationOutputs.prepare(request);
        List<Path> files = new ArrayList<>(javaFiles(request.sources().get(0)));
        files.addAll(javaFiles(request.sourcesOutput()));
        List<String> entries = new ArrayList<>();
        for (Path entry : request.classpath()) {
            entries.add(entry.toString());
        }
        List<String> options = List.of("-classpath", String.join(File.pathSeparator, entries),
                "-d", request.classesOutput().toString(), "-s", request.processorSourcesOutput().toString(),
                "-Xlint:all,-serial", "-Werror",
                "-Agraphmaquina.annotationManifest=" + prepared.generation().annotationManifest());
        Processor processor = (Processor) loader.loadClass(StateGraphProcessor.class.getName())
                .getDeclaredConstructor().newInstance();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null,
                StandardCharsets.UTF_8)) {
            manager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT,
                    List.of(Files.createDirectories(request.classesOutput())));
            manager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT,
                    List.of(Files.createDirectories(request.processorSourcesOutput())));
            JavaCompiler.CompilationTask task = compiler.getTask(null, manager, diagnostics, options, null,
                    manager.getJavaFileObjectsFromPaths(files));
            task.setProcessors(List.of(processor));
            return Boolean.TRUE.equals(task.call());
        }
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter((Path file) -> file.toString().endsWith(".java")).sorted().toList();
        }
    }
}
