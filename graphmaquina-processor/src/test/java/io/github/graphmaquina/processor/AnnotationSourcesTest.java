package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.util.Trees;
import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.processor.model.RecoveryModel;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises source-binding boundaries that recovery integration cannot expose: defaults, malformed and classfile
 * input.
 */
class AnnotationSourcesTest {

    /** Two annotation types of one simple name, used on a field, on a method and on a parameter. */
    private static final String SAME_NAMED_FLAGS = """
            package fx;
            /** Two distinct annotation types share the same simple name. */
            public class Sample {
                /** First annotation namespace. */ public static class A {
                    /** An annotation with one optional class array. */ public @interface Flag {
                        Class<?>[] value() default String.class;
                    }
                }
                /** Second annotation namespace. */ public static class B {
                    /** A separate annotation with the same simple name. */ public @interface Flag {
                        Class<?>[] value();
                    }
                }
                @A.Flag(Integer.class) @B.Flag({Long.class, Double.class}) int field;
                @A.Flag void omitted(@B.Flag(Float.class) int argument) { }
                @A.Flag(String.class) void explicit() { }
            }
            """;

    @TempDir
    Path directory;

    @Test
    void resolvedAnnotationTypesDefaultsAndVariableDeclarationsKeepTheirOwnSpans() throws IOException {
        Probe processor = new Probe((ProcessingEnvironment environment) -> {
            AnnotationSources sources = new AnnotationSources(Trees.instance(environment));
            Annotations annotations = new Annotations(environment.getElementUtils());
            TypeElement sample = environment.getElementUtils().getTypeElement("fx.Sample");
            int[] found = {0};
            inspect(sample, (Element element) -> {
                for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
                    AnnotationValue array = annotations.value(annotation, "value");
                    for (AnnotationValue value : annotations.array(annotation, "value")) {
                        Optional<AnnotationSources.Location> location = sources.find(element, annotation, value);
                        if (element.getSimpleName().contentEquals("omitted")) {
                            assertTrue(location.isEmpty());
                            assertTrue(sources.find(element, annotation, array).isEmpty());
                        } else {
                            assertTrue(location.isPresent(), element + " " + annotation);
                            String literal = ((TypeMirror) value.getValue()).toString().replace("java.lang.", "")
                                    + ".class";
                            AnnotationSources.Location position = location.orElseThrow();
                            assertEquals(SAME_NAMED_FLAGS.lastIndexOf(literal), position.start());
                            long end = Trees.instance(environment).getSourcePositions()
                                    .getEndPosition(position.unit(), position.tree());
                            assertEquals(literal, SAME_NAMED_FLAGS.substring((int) position.start(), (int) end));
                            found[0]++;
                        }
                    }
                }
            });
            assertEquals(5, found[0]);
        });
        Run result = compile("Sample", SAME_NAMED_FLAGS, processor);
        assertTrue(result.success(), result.diagnostics().toString());
        assertTrue(processor.visited);
    }

    @Test
    void malformedValuesLeaveJavacErrorsAndStillBindTheirReadableSiblings() throws IOException {
        String source = """
                package fx;
                /** Annotation carrier with an unresolved class literal beside a readable class literal. */
                @Sample.Flags({String.class, Missing.class})
                public class Sample {
                    /** Class-array annotation for malformed-input coverage. */ \
                public @interface Flags { Class<?>[] value(); }
                }
                """;
        Probe processor = new Probe((ProcessingEnvironment environment) -> {
            AnnotationSources sources = new AnnotationSources(Trees.instance(environment));
            Annotations annotations = new Annotations(environment.getElementUtils());
            TypeElement sample = environment.getElementUtils().getTypeElement("fx.Sample");
            inspect(sample, (Element element) -> {
                for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
                    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                            : environment.getElementUtils().getElementValuesWithDefaults(annotation).entrySet()) {
                        sources.find(element, annotation, entry.getValue());
                    }
                }
            });
            AnnotationMirror annotation = sample.getAnnotationMirrors().getFirst();
            AnnotationValue readable = annotations.array(annotation, "value").getFirst();
            assertEquals(source.indexOf("String.class"),
                    sources.find(sample, annotation, readable).orElseThrow().start());
        });
        Run result = compile("Sample", source, processor);
        assertFalse(result.success());
        assertTrue(result.diagnostics().stream().anyMatch((Diagnostic<? extends JavaFileObject> diagnostic) ->
                diagnostic.getKind() == Diagnostic.Kind.ERROR));
        assertTrue(processor.visited);
    }

    @Test
    void sourceEmissionCountsEachProblemOnceAndDefaultsUseMessagerFallback() throws IOException {
        String source = """
                package fx;
                /** Source and defaulted values exercise both diagnostic emission paths. */
                @Sample.Flag(String.class)
                public class Sample {
                    /** Optional value with a class default. */ \
                public @interface Flag { Class<?> value() default Long.class; }
                    @Flag int defaulted;
                }
                """;
        Probe processor = new Probe((ProcessingEnvironment environment) -> {
            Diagnostics diagnostics = new Diagnostics(environment);
            Annotations annotations = new Annotations(environment.getElementUtils());
            TypeElement sample = environment.getElementUtils().getTypeElement("fx.Sample");
            inspect(sample, (Element element) -> {
                for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
                    AnnotationValue value = annotations.value(annotation, "value");
                    DiagnosticAnchor anchor = DiagnosticAnchor.of(element, annotation, value);
                    assertEquals(element.equals(sample), diagnostics.sourceStart(anchor).isPresent());
                    anchor.report(diagnostics, DiagnosticCode.SG102, element.getSimpleName().toString());
                }
            });
            assertEquals(2, diagnostics.errors());
        });
        Run result = compile("Sample", source, processor);
        assertFalse(result.success());
        assertEquals(2, result.diagnostics().size(), result.diagnostics().toString());
        Diagnostic<? extends JavaFileObject> explicit = result.diagnostics().getFirst();
        assertEquals(source.indexOf("String.class"), explicit.getStartPosition());
        assertEquals("String.class",
                source.substring((int) explicit.getStartPosition(), (int) explicit.getEndPosition()));
    }

    @Test
    void classfileAnnotationsHaveNoSourceAndDuplicatesUseRuleThenFallbackOrder() throws IOException {
        Run library = compile("Carrier", """
                package fx;
                /** Classfile-only edge with textual fallback first, which cannot be recovered from bytecode. */
                @Carrier.Edge(failsOn = {IllegalArgumentException.class, java.io.IOException.class},
                    recoveries = @Carrier.Recovery(kind = Carrier.Kind.class,
                        failsOn = {java.io.IOException.class, IllegalArgumentException.class}))
                public class Carrier {
                    /** Valid named failure kind. */ \
                public record Kind() implements io.github.graphmaquina.api.FailureKind {}
                    /** Stand-in nested recovery annotation. */ public @interface Recovery {
                        Class<?> kind(); Class<?>[] failsOn();
                    }
                    /** Stand-in edge annotation. */ public @interface Edge {
                        Recovery[] recoveries(); Class<?>[] failsOn();
                    }
                }
                """, null);
        assertTrue(library.success(), library.diagnostics().toString());
        Probe processor = new Probe((ProcessingEnvironment environment) -> {
            Diagnostics diagnostics = new Diagnostics(environment);
            Annotations annotations = new Annotations(environment.getElementUtils());
            TypeElement carrier = environment.getElementUtils().getTypeElement("fx.Carrier");
            AnnotationMirror edge = carrier.getAnnotationMirrors().getFirst();
            AnnotationMirror rule = annotations.annotations(edge, "recoveries").getFirst();
            List<AnnotationValue> ruleValues = annotations.array(rule, "failsOn");
            AnnotationValue kind = annotations.value(rule, "kind");
            RecoveryModel recovery = new RecoveryModel((TypeMirror) kind.getValue(), "RETRY",
                    annotations.types(rule, "failsOn"), rule, kind, ruleValues);
            for (AnnotationValue value : ruleValues) {
                assertTrue(diagnostics.sourceStart(DiagnosticAnchor.of(carrier, rule, value)).isEmpty());
            }
            for (AnnotationValue value : annotations.array(edge, "failsOn")) {
                assertTrue(diagnostics.sourceStart(DiagnosticAnchor.of(carrier, edge, value)).isEmpty());
            }
            new StructuralChecks(new Signatures(environment.getElementUtils(), environment.getTypeUtils()), diagnostics)
                    .checkRecoveries(List.of(recovery), annotations.types(edge, "failsOn"),
                            annotations.array(edge, "failsOn"), carrier, edge);
            assertEquals(2, diagnostics.errors());
        });
        Run result = compile("Trigger",
                "package fx; /** Triggers processing of the classfile carrier. */ public class Trigger {}", processor);
        assertFalse(result.success());
        assertEquals(List.of("[SG304] java.lang.IllegalArgumentException", "[SG304] java.io.IOException"),
                result.diagnostics().stream().map((Diagnostic<? extends JavaFileObject> diagnostic) ->
                        diagnostic.getMessage(null).split(" is declared", 2)[0]).toList());
        assertTrue(result.diagnostics().stream().allMatch((Diagnostic<? extends JavaFileObject> diagnostic) ->
                diagnostic.getStartPosition() == Diagnostic.NOPOS));
        assertTrue(processor.visited);
    }

    @Test
    void aDefaultedThrowableDoesNotDiscardTheOrderOfExplicitValues() throws IOException {
        String source = """
                package fx;
                /** A defaulted rule value coexists with explicitly ordered duplicate declarations. */
                @Carrier.Edge(failsOn = IllegalArgumentException.class, recoveries = {
                    @Carrier.Recovery(kind = Carrier.First.class),
                    @Carrier.Recovery(kind = Carrier.Second.class, failsOn = IllegalArgumentException.class)})
                public class Carrier {
                    /** First valid kind. */ public record First() implements io.github.graphmaquina.api.FailureKind {}
                    /** Second valid kind. */ \
                public record Second() implements io.github.graphmaquina.api.FailureKind {}
                    /** Rule with an omitted throwable default. */ public @interface Recovery {
                        Class<?> kind(); Class<?>[] failsOn() default java.io.IOException.class;
                    }
                    /** Edge with explicit rules and fallback. */ public @interface Edge {
                        Recovery[] recoveries(); Class<?>[] failsOn();
                    }
                }
                """;
        Probe processor = new Probe((ProcessingEnvironment environment) -> {
            Diagnostics diagnostics = new Diagnostics(environment);
            Annotations annotations = new Annotations(environment.getElementUtils());
            TypeElement carrier = environment.getElementUtils().getTypeElement("fx.Carrier");
            AnnotationMirror edge = carrier.getAnnotationMirrors().getFirst();
            List<RecoveryModel> recoveries = new ArrayList<>();
            for (AnnotationMirror rule : annotations.annotations(edge, "recoveries")) {
                AnnotationValue kind = annotations.value(rule, "kind");
                recoveries.add(new RecoveryModel((TypeMirror) kind.getValue(), "RETRY",
                        annotations.types(rule, "failsOn"), rule, kind, annotations.array(rule, "failsOn")));
            }
            new StructuralChecks(new Signatures(environment.getElementUtils(), environment.getTypeUtils()), diagnostics)
                    .checkRecoveries(recoveries, annotations.types(edge, "failsOn"),
                            annotations.array(edge, "failsOn"), carrier, edge);
            assertEquals(1, diagnostics.errors());
        });
        Run result = compile("Carrier", source, processor);
        assertFalse(result.success());
        assertEquals(1, result.diagnostics().size(), result.diagnostics().toString());
        assertEquals(source.lastIndexOf("IllegalArgumentException.class"),
                result.diagnostics().getFirst().getStartPosition());
    }

    /** Visits declarations and parameters, without scanning source expressions or method bodies. */
    private static void inspect(Element element, Consumer<Element> visitor) {
        visitor.accept(element);
        if (element instanceof ExecutableElement method) {
            method.getParameters().forEach(visitor);
        }
        for (Element child : element.getEnclosedElements()) {
            inspect(child, visitor);
        }
    }

    /** One real javac invocation, retaining diagnostics after its file manager closes. */
    private record Run(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) { }

    private Run compile(String name, String source, Probe processor) throws IOException {
        Path file = directory.resolve(name + ".java");
        Files.writeString(file, source, StandardCharsets.UTF_8);
        Path classes = Files.createDirectories(directory.resolve("classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager =
                compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8)) {
            List<String> options = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path")
                    + File.pathSeparator + classes, "-d", classes.toString()));
            options.add(processor == null ? "-proc:none" : "-proc:only");
            JavaCompiler.CompilationTask task = compiler.getTask(null, manager, collector, options, null,
                    manager.getJavaFileObjects(file));
            if (processor != null) {
                task.setProcessors(List.of(processor));
            }
            return new Run(task.call(), List.copyOf(collector.getDiagnostics()));
        }
    }

    /** Runs assertions once inside a real processing environment; no process-wide mutable test state. */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class Probe extends AbstractProcessor {
        private final Consumer<ProcessingEnvironment> verify;
        private boolean visited;

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            if (!round.processingOver() && !visited) {
                visited = true;
                verify.accept(processingEnv);
            }
            return true;
        }
    }
}
