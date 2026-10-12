package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.codegen.GenerationRequest;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.DocumentationTool;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The generated sources document every member a {@code javadoc} run documents: the author cannot add a comment to a
 * generated file, so a documentation build that fails on warnings, under the default doclint or
 * {@code -Xdoclint:all}, must not find one there. Only the generated sources are documented; the author's own
 * sources, compiled already, are on the classpath, so their warnings are theirs and are not counted.
 */
class GeneratedJavadocTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "-Xdoclint:all"})
    void aMachineItsPairAndItsSetDocumentEveryMemberTheyDeclare(String doclint) {
        Map<String, String> sources = Fixtures.withLibrary(Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH,
                Fixtures.DOCS_BODY, Fixtures.LIBRARY);

        assertEquals("", javadoc(sources, doclint));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-Xdoclint:all"})
    void parentsTheirChildrenAndTheirSetDocumentEveryMemberTheyDeclare(String doclint) {
        assertEquals("", javadoc(SubmachineGenerationTest.billing(), doclint));
    }

    /**
     * Compiles {@code sources} as a consumer build does, then runs {@code javadoc} over the generated sources alone
     * with {@code doclint}, when not empty, and answers every warning and error it reports, one per line.
     */
    private static String javadoc(Map<String, String> sources, String doclint) {
        Compilation.Prepared prepared = Compilation.prepare(sources);
        Compilation.Result compiled = Compilation.compilePrepared(prepared);
        assertTrue(compiled.success(), compiled.describe());
        GenerationRequest request = prepared.request();
        List<Path> generated = new ArrayList<>(javaFiles(request.sourcesOutput()));
        generated.addAll(javaFiles(request.processorSourcesOutput()));
        assertTrue(generated.size() > 1, generated.toString());
        List<String> classpath = new ArrayList<>();
        for (Path entry : request.classpath()) {
            classpath.add(entry.toString());
        }
        classpath.add(request.classesOutput().toString());
        DocumentationTool tool = ToolProvider.getSystemDocumentationTool();
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        StringWriter output = new StringWriter();
        try (StandardJavaFileManager manager = tool.getStandardFileManager(collector, null, request.encoding())) {
            List<String> options = new ArrayList<>(List.of("-d", Files.createTempDirectory("graphmaquina-javadoc-")
                    .toString(), "-classpath", String.join(File.pathSeparator, classpath), "-encoding",
                    request.encoding().name(), "-quiet", "-Xmaxwarns", "10000"));
            if (!doclint.isEmpty()) {
                options.add(doclint);
            }
            boolean success = Boolean.TRUE.equals(tool.getTask(output, manager, collector, null, options,
                    manager.getJavaFileObjectsFromPaths(generated)).call());
            StringBuilder reported = new StringBuilder();
            for (Diagnostic<? extends JavaFileObject> diagnostic : collector.getDiagnostics()) {
                if (diagnostic.getKind() != Diagnostic.Kind.NOTE) {
                    reported.append(diagnostic.getKind()).append(' ')
                            .append(diagnostic.getSource() == null ? "" : diagnostic.getSource().getName())
                            .append(':').append(diagnostic.getLineNumber()).append(": ")
                            .append(diagnostic.getMessage(null)).append('\n');
                }
            }
            assertTrue(success || reported.length() > 0, output.toString());
            return reported.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> javaFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter((Path file) -> file.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
