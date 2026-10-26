package io.github.graphmaquina.codegen.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.model.GeneratedSource;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.source.Bootstrap;
import io.github.graphmaquina.codegen.source.SourceFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the {@code Recovery} annotation nested in the vocabulary of a machine and the {@code recoveries} element
 * its {@code Transition} gains: the shape both are emitted with, the place {@code Recovery} takes in the inventory,
 * that an edge compiles without recoveries and with several of them, with and without throwable types, that javac
 * itself refuses a kind that is not a failure kind and a constant of another enum, and that a name whose first
 * segment the nested {@code Recovery} would take over is refused with SG603.
 */
class RecoveryVocabularyTest {

    @TempDir
    Path directory;

    private static final Map<String, String> ORIGINALS = originals();

    private static Map<String, String> originals() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", """
            package fx;
            /** State enum used by the generated vocabulary fixture. */ \
            public enum DocStatus { DRAFT, REVIEW, PUBLISHED, RETRY_PENDING, REJECTED, ARCHIVED }
            """);
        sources.put("fx.DocTransition", """
            package fx;
            /** Transition enum used by the generated vocabulary fixture. */ \
            public enum DocTransition { SUBMIT, PUBLISH, RETRY_LATER, REJECT, ARCHIVE }
            """);
        sources.put("fx.Doc", """
            package fx;
            /** Entity type used by the generated vocabulary fixture. */ public class Doc { }
            """);
        sources.put("fx.Docs", """
            package fx;
            import io.github.graphmaquina.annotation.GenerateGraphApi;
            import io.github.graphmaquina.api.Machine;
            /** The Docs machine, whose vocabulary the tests generate. */ @GenerateGraphApi
            public interface Docs extends Machine<Doc, DocStatus, DocTransition> {}
            """);
        sources.put("fx.DocActions", """
            package fx;
            import io.github.graphmaquina.annotation.GraphAction;
            /** Holds publish, the action of the PUBLISH edge, which declares the recovery rules. */
            public class DocActions {
                @GraphAction
                public Doc publish(Doc doc) { return doc; }
            }
            """);
        sources.put("fx.PublicationFailures", """
            package fx;
            import io.github.graphmaquina.api.FailureKind;
            /** Failure-kind fixtures used by typed recovery declarations. */ public final class PublicationFailures {
                private PublicationFailures() { }
                /** Failure category for a publication service that is unavailable. */ \
            public record Unavailable() implements FailureKind { }
                /** Failure category for a publication rejected by policy. */ \
            public record Rejected() implements FailureKind { }
                /** Failure category for an invalid publication. */ public record Invalid() implements FailureKind { }
            }
            """);
        return Map.copyOf(sources);
    }

    /**
     * An edge without recoveries on the type, and an edge on a method that declares three of them: one with a single
     * throwable type, one with two and one with none.
     */
    private static final Map<String, String> USAGE = Map.of("use.RecoveryUsage", """
        package use;
        /** Transitions with no recovery and with several recovery declarations. */ @fx.DocsGraph.Transition(
                id = fx.DocTransition.SUBMIT,
                from = fx.DocStatus.DRAFT,
                to = fx.DocStatus.REVIEW)
        public interface RecoveryUsage {

            @fx.DocsGraph.Transition(
                    id = fx.DocTransition.PUBLISH,
                    from = fx.DocStatus.REVIEW,
                    to = fx.DocStatus.PUBLISHED,
                    action = fx.DocActionsRefs.Publish.class,
                    recoveries = {
                        @fx.DocsGraph.Recovery(
                                kind = fx.PublicationFailures.Unavailable.class,
                                transition = fx.DocTransition.RETRY_LATER,
                                failsOn = java.net.ConnectException.class),
                        @fx.DocsGraph.Recovery(
                                kind = fx.PublicationFailures.Rejected.class,
                                transition = fx.DocTransition.REJECT,
                                failsOn = {java.io.FileNotFoundException.class,
                                    java.util.concurrent.TimeoutException.class}),
                        @fx.DocsGraph.Recovery(
                                kind = fx.PublicationFailures.Invalid.class,
                                transition = fx.DocTransition.ARCHIVE)
                    },
                    failsOn = java.io.IOException.class,
                    onFailure = fx.DocTransition.ARCHIVE)
            void publish();
        }
        """);

    /** The edge of {@link #USAGE}'s method with a single recovery, whose {@code kind} and {@code transition} vary. */
    private static final String SINGLE_RECOVERY = """
        package use;
        /** Transition fixture used to check javac's recovery member type errors. */ public interface WrongRecovery {

            @fx.DocsGraph.Transition(
                    id = fx.DocTransition.PUBLISH,
                    from = fx.DocStatus.REVIEW,
                    to = fx.DocStatus.PUBLISHED,
                    action = fx.DocActionsRefs.Publish.class,
                    recoveries = @fx.DocsGraph.Recovery(kind = %s, transition = %s))
            void publish();
        }
        """;

    @Test
    void emitsRecoveryAsANestedAnnotationThatOnlyATransitionHolds() {
        String vocabulary = content(emitted(), "fx.DocsGraph");

        assertEquals(List.of(
                "@io.github.graphmaquina.annotation.internal.GeneratedVocabulary(machine = fx.Docs.class,"
                        + " value = io.github.graphmaquina.annotation.internal.VocabularyKind.RECOVERY)",
                "@java.lang.annotation.Target({})",
                "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)",
                "public @interface Recovery {",
                "/** {@return the failure kind the rule takes} */",
                "java.lang.Class<? extends io.github.graphmaquina.api.FailureKind> kind();",
                "/** {@return the transition the rule takes, resolved from the origin the entity is in} */",
                "fx.DocTransition transition();",
                "/** {@return the throwable types of the action the rule takes; none by default} */",
                "java.lang.Class<? extends java.lang.Throwable>[] failsOn() default {};",
                "}"), declaration(vocabulary, "Recovery"));
        List<String> transition = declaration(vocabulary, "Transition");
        assertTrue(transition.contains("DocsGraph.Recovery[] recoveries() default {};"), transition::toString);
    }

    /** The inventory names the annotations in the order the vocabulary declares them: Recovery after Transitions. */
    @Test
    void listsRecoveryInTheInventoryWhereTheVocabularyDeclaresIt() {
        GenerationModel model = inspect("sources", ORIGINALS);
        List<String> declared = content(ApiEmitter.emit(model, "sources"), "fx.DocsGraph").lines()
                .map(String::strip)
                .filter((String line) -> line.startsWith("public @interface "))
                .map((String line) -> "fx.DocsGraph."
                        + line.substring("public @interface ".length(), line.indexOf(" {")))
                .toList();

        assertEquals(List.of("fx.DocsGraph.Definition", "fx.DocsGraph.StatePolicy", "fx.DocsGraph.StatePolicies",
                "fx.DocsGraph.Transition", "fx.DocsGraph.Transitions", "fx.DocsGraph.Recovery", "fx.DocsGraph.Reject",
                "fx.DocsGraph.Rejects", "fx.DocsGraph.Check"), ApiEmitter.annotationTypes(model));
        assertEquals(ApiEmitter.annotationTypes(model), declared);
    }

    @Test
    void compilesAnEdgeWithoutRecoveriesAndAnEdgeWithThreeOfThem() {
        SourceFiles.Compilation compilation = compile(emitted(), USAGE);

        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
        assertEquals(List.of(), compilation.messages());
    }

    /** {@code kind} is a {@code Class<? extends FailureKind>}: a record that does not implement it does not compile. */
    @Test
    void refusesAKindThatIsNotAFailureKindAsAJavacTypeError() {
        SourceFiles.Compilation compilation = compile(emitted(), Map.of(
                "use.NotAKind",
                "package use; /** Candidate kind missing FailureKind for a type-error fixture. */ "
                        + "public record NotAKind() { }",
                "use.WrongRecovery", SINGLE_RECOVERY.formatted("use.NotAKind.class",
                        "fx.DocTransition.RETRY_LATER")));

        assertFalse(compilation.success(), () -> String.valueOf(compilation.messages()));
        assertEquals(List.of("compiler.err.prob.found.req"), compilation.codes(),
                () -> String.valueOf(compilation.messages()));
    }

    /** {@code transition} is typed with the machine's own transition enum, so a state constant does not compile. */
    @Test
    void refusesATransitionConstantOfAnotherEnumAsAJavacTypeError() {
        SourceFiles.Compilation compilation = compile(emitted(), Map.of(
                "use.WrongRecovery", SINGLE_RECOVERY.formatted("fx.PublicationFailures.Unavailable.class",
                        "fx.DocStatus.RETRY_PENDING")));

        assertFalse(compilation.success(), () -> String.valueOf(compilation.messages()));
        assertEquals(List.of("compiler.err.prob.found.req"), compilation.codes(),
                () -> String.valueOf(compilation.messages()));
    }

    /**
     * Inside the vocabulary the nested {@code Recovery} shadows a type of that name and obscures a package of that
     * name, so a transition enum of the unnamed package named {@code Recovery}, and a state enum of a package named
     * {@code Recovery}, are refused at the machine's declaration.
     */
    @Test
    void refusesANameWhoseFirstSegmentTheNestedRecoveryTakesOver() {
        GenerationModel unnamed = inspect("unnamed", Map.of(
                "Doc", "/** Entity fixture for the unnamed-package collision case. */ public class Doc {}",
                "Phase",
                "/** State enum used by the unnamed-package collision fixture. */ public enum Phase { OPEN, CLOSED }",
                "Recovery",
                "/** Transition enum deliberately named Recovery for the SG603 fixture. */ "
                        + "public enum Recovery { CLOSE }",
                "Tickets", """
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    /** Machine fixture using a deliberately conflicting Recovery transition enum. */ @GenerateGraphApi
                    public interface Tickets extends Machine<Doc, Phase, Recovery> {}
                    """));
        CodegenException transition = assertThrows(CodegenException.class,
                () -> ApiEmitter.emit(unnamed, "unnamed"));
        assertEquals("SG603", transition.code());
        assertEquals("the vocabulary TicketsGraph cannot name the transition type Recovery of Tickets: inside it,"
                + " Recovery means its own nested type TicketsGraph.Recovery", transition.getMessage());
        assertEquals("unnamed/Tickets.java", transition.location().orElseThrow().path());

        GenerationModel packaged = inspect("packaged", Map.of(
                "Recovery.Doc",
                    "package Recovery; /** Entity with nested enums for the package-segment collision fixture. */ "
                            + "public class Doc { /** State enum nested in Recovery.Doc for the SG603 fixture. */ "
                            + "public enum Status { DRAFT } "
                            + "/** Transition enum nested in Recovery.Doc for the SG603 fixture. */ "
                            + "public enum Step { GO } }",
                "fx.Docs", """
                    package fx;
                    import Recovery.Doc;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    /** Machine fixture referencing nested types in package Recovery for SG603. */ @GenerateGraphApi
                    public interface Docs extends Machine<Doc, Doc.Status, Doc.Step> {}
                    """));
        CodegenException state = assertThrows(CodegenException.class, () -> ApiEmitter.emit(packaged, "packaged"));
        assertEquals("SG603", state.code());
        assertEquals("the vocabulary DocsGraph cannot name the state type Recovery.Doc.Status of fx.Docs: inside it,"
                + " Recovery means its own nested type DocsGraph.Recovery", state.getMessage());
        assertEquals("packaged/fx/Docs.java", state.location().orElseThrow().path());
    }

    /** The units emitted for {@link #ORIGINALS}. */
    private List<GeneratedSource> emitted() {
        return ApiEmitter.emit(inspect("sources", ORIGINALS), "sources");
    }

    /** Writes {@code originals} under the source root {@code root} of the test directory and inspects them. */
    private GenerationModel inspect(String root, Map<String, String> originals) {
        Path sources = directory.resolve(root);
        SourceFiles.write(sources, originals);
        return Bootstrap.inspect(new GenerationRequest(directory, List.of(sources), SourceFiles.classpath(),
                directory.resolve("generated"), directory.resolve("processor-generated"),
                directory.resolve("classes"), directory.resolve("work"), root));
    }

    /** Compiles {@link #ORIGINALS}, {@code usage} and {@code emitted} together under the strict options. */
    private SourceFiles.Compilation compile(List<GeneratedSource> emitted, Map<String, String> usage) {
        List<Path> files = new ArrayList<>(SourceFiles.write(directory.resolve("sources"), ORIGINALS));
        files.addAll(SourceFiles.write(directory.resolve("usage"), usage));
        files.addAll(write(directory.resolve("generated"), emitted));
        return SourceFiles.compileStrict(files, directory.resolve("classes"));
    }

    private static List<Path> write(Path root, List<GeneratedSource> emitted) {
        List<Path> written = new ArrayList<>();
        for (GeneratedSource source : emitted) {
            Path file = root.resolve(source.qualifiedName().replace('.', '/') + ".java");
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.content(), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            written.add(file);
        }
        return written;
    }

    private static String content(List<GeneratedSource> emitted, String qualifiedName) {
        return emitted.stream().filter((GeneratedSource source) -> source.qualifiedName().equals(qualifiedName))
                .findFirst().orElseThrow().content();
    }

    /**
     * The stripped lines of the nested annotation {@code name} in {@code source}, from the first line after its
     * documentation comment, which carries its metadata, to its closing brace.
     */
    private static List<String> declaration(String source, String name) {
        List<String> lines = source.lines().map(String::strip).toList();
        int header = lines.indexOf("public @interface " + name + " {");
        assertTrue(header > 0, () -> name + " is not declared in:\n" + source);
        int start = lines.subList(0, header).lastIndexOf("*/") + 1;
        int end = header + lines.subList(header, lines.size()).indexOf("}");
        return lines.subList(start, end + 1);
    }
}
