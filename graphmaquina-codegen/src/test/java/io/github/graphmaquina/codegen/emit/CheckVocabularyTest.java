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
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the {@code Check} annotation nested in the vocabulary of a machine: the shape it is emitted with, that it
 * is written on methods only and takes the machine's own transition identifier, which javac itself enforces, the
 * place it takes in the inventory, and that a name whose first segment the nested {@code Check} would take over is
 * refused with SG603.
 */
class CheckVocabularyTest {

    @TempDir
    Path directory;

    private static final Map<String, String> ORIGINALS = originals();

    private static Map<String, String> originals() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", """
            package fx;
            /** State enum used by the generated vocabulary fixture. */
            public enum DocStatus { DRAFT, REVIEW, PUBLISHED }
            """);
        sources.put("fx.DocTransition", """
            package fx;
            /** Transition enum used by the generated vocabulary fixture. */
            public enum DocTransition { SUBMIT, PUBLISH }
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
        return Map.copyOf(sources);
    }

    /** A method that checks the SUBMIT operation, which javac accepts under the strict options. */
    private static final Map<String, String> USAGE = Map.of("use.CheckUsage", """
        package use;
        /** A facade fragment declaring a method that checks one operation. */
        public interface CheckUsage {

            @fx.DocsGraph.Check(id = fx.DocTransition.SUBMIT)
            void canSubmit();
        }
        """);

    /** The method of {@link #USAGE}, whose identifier is written {@code %s}. */
    private static final String CHECK_USAGE = """
        package use;
        /** Check fixture used to check javac's member type errors. */ public interface WrongCheck {

            @fx.DocsGraph.Check(id = %s)
            void canSubmit();
        }
        """;

    @Test
    void checkIsEmittedForMethods() {
        String vocabulary = content(emitted(), "fx.DocsGraph");

        assertEquals(List.of(
                "@io.github.graphmaquina.annotation.internal.GeneratedVocabulary(machine = fx.Docs.class,"
                        + " value = io.github.graphmaquina.annotation.internal.VocabularyKind.CHECK)",
                "@java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)",
                "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)",
                "public @interface Check {",
                "/** {@return the identifier of the operation the method checks} */",
                "fx.DocTransition id();",
                "}"), declaration(vocabulary, "Check"));
        assertFalse(String.join("\n", declaration(vocabulary, "Check")).contains("@java.lang.annotation.Repeatable("),
                "Check is not repeatable");
        assertEquals("Declares a method of {@link fx.Docs} that checks, without running it, whether the operation"
                + " that owns {@code id} may pass its admission conditions now.", documentation(vocabulary, "Check"));
    }

    @Test
    void compilesACheckOnAMethodUnderTheStrictOptions() {
        SourceFiles.Compilation compilation = compile(emitted(), USAGE);

        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
        assertEquals(List.of(), compilation.messages());
    }

    /** {@code Check} targets methods only, so javac refuses it on a type. */
    @Test
    void refusesACheckOnATypeAsAJavacError() {
        SourceFiles.Compilation compilation = compile(emitted(), Map.of("use.OnAType", """
            package use;
            /** Fixture writing a Check where only a method may carry it. */
            @fx.DocsGraph.Check(id = fx.DocTransition.SUBMIT)
            public interface OnAType {}
            """));

        assertFalse(compilation.success(), () -> String.valueOf(compilation.messages()));
        assertEquals(List.of("compiler.err.annotation.type.not.applicable"), compilation.codes(),
                () -> String.valueOf(compilation.messages()));
    }

    /** {@code id} is typed with the machine's own transition enum, so a state constant does not compile. */
    @Test
    void checkTakesTheMachinesOwnIdentifier() {
        SourceFiles.Compilation compilation = compile(emitted(), Map.of(
                "use.WrongCheck", CHECK_USAGE.formatted("fx.DocStatus.DRAFT")));

        assertFalse(compilation.success(), () -> String.valueOf(compilation.messages()));
        assertEquals(List.of("compiler.err.prob.found.req"), compilation.codes(),
                () -> String.valueOf(compilation.messages()));
    }

    /** The inventory names the annotations in the order the vocabulary declares them, with Check after Rejects. */
    @Test
    void checkComesLastInTheInventory() {
        GenerationModel model = inspect("sources", ORIGINALS);
        List<String> inventory = ApiEmitter.annotationTypes(model);
        List<String> declared = content(ApiEmitter.emit(model, "sources"), "fx.DocsGraph").lines()
                .map(String::strip)
                .filter((String line) -> line.startsWith("public @interface "))
                .map((String line) -> "fx.DocsGraph."
                        + line.substring("public @interface ".length(), line.indexOf(" {")))
                .toList();

        assertEquals("fx.DocsGraph.Check", inventory.get(inventory.size() - 1));
        assertEquals("fx.DocsGraph.Rejects", inventory.get(inventory.size() - 2));
        assertEquals(inventory, declared);
    }

    /**
     * Inside the vocabulary the nested {@code Check} shadows a type of that name, so a transition enum of the unnamed
     * package named {@code Check} is refused at the machine's declaration.
     */
    @Test
    void aNameCheckWouldTakeOverIsSG603() {
        GenerationModel unnamed = inspect("unnamed", Map.of(
                "Doc", "/** Entity fixture for the unnamed-package collision case. */ public class Doc {}",
                "Phase",
                "/** State enum used by the unnamed-package collision fixture. */ public enum Phase { OPEN, CLOSED }",
                "Check",
                "/** Transition enum deliberately named Check for the SG603 fixture. */ "
                        + "public enum Check { CLOSE }",
                "Tickets", """
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    /** Machine fixture using a deliberately conflicting Check transition enum. */ @GenerateGraphApi
                    public interface Tickets extends Machine<Doc, Phase, Check> {}
                    """));

        CodegenException failure = assertThrows(CodegenException.class, () -> ApiEmitter.emit(unnamed, "unnamed"));

        assertEquals("SG603", failure.code());
        assertEquals("the vocabulary TicketsGraph cannot name the transition type Check of Tickets: inside it,"
                + " Check means its own nested type TicketsGraph.Check", failure.getMessage());
        assertEquals("unnamed/Tickets.java", failure.location().orElseThrow().path());
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

    /** The text of the documentation comment of the nested annotation {@code name} in {@code source}, on one line. */
    private static String documentation(String source, String name) {
        List<String> lines = source.lines().map(String::strip).toList();
        int end = lines.subList(0, lines.indexOf("public @interface " + name + " {")).lastIndexOf("*/");
        int start = lines.subList(0, end).lastIndexOf("/**");
        return lines.subList(start + 1, end).stream()
                .map((String line) -> line.replaceFirst("^\\*\\s?", ""))
                .collect(Collectors.joining(" "))
                .strip();
    }
}
