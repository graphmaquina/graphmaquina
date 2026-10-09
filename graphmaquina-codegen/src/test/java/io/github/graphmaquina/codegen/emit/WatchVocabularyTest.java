package io.github.graphmaquina.codegen.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.model.GeneratedSource;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import io.github.graphmaquina.codegen.model.SourceLocation;
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
 * Verifies the vocabulary emitted for ordered pairs of local machines: the owner's enums and the peer's enum
 * are used where the specification places them, a rule executes a transition of the owner, the watch repeats
 * through its container, every optional array may be omitted, a machine has such a vocabulary over each peer it
 * declares and over no other machine, an unknown peer is refused, and an empty model still carries its compilation.
 */
class WatchVocabularyTest {

    @TempDir
    Path directory;

    private static final Map<String, String> ORIGINALS = originals();

    private static Map<String, String> originals() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", """
            package fx;
            public enum DocStatus { DRAFT, REVIEW, PUBLISHED }
            """);
        sources.put("fx.PageStatus", """
            package fx;
            public enum PageStatus { OPEN, CLOSED }
            """);
        sources.put("fx.DocTransition", """
            package fx;
            public enum DocTransition { SUBMIT, PUBLISH, ARCHIVE }
            """);
        sources.put("fx.PageTransition", """
            package fx;
            public enum PageTransition { OPEN_PAGE, CLOSE_PAGE }
            """);
        sources.put("fx.Doc", """
            package fx;
            import io.github.graphmaquina.annotation.GraphProperty;
            import java.util.List;
            public class Doc {
                @GraphProperty
                private DocStatus status;
                @GraphProperty
                private List<Page> pages;
                public DocStatus status() { return status; }
                public List<Page> pages() { return pages; }
            }
            """);
        sources.put("fx.Page", """
            package fx;
            import io.github.graphmaquina.annotation.GraphProperty;
            public class Page {
                @GraphProperty
                private PageStatus status;
                @GraphProperty
                private Doc doc;
                public PageStatus status() { return status; }
                public Doc doc() { return doc; }
            }
            """);
        sources.put("fx.Docs", """
            package fx;
            import io.github.graphmaquina.annotation.GenerateGraphApi;
            import io.github.graphmaquina.annotation.GraphPeers;
            import io.github.graphmaquina.api.Machine;
            @GenerateGraphApi
            @GraphPeers(Pages.class)
            public interface Docs extends Machine<Doc, DocStatus, DocTransition> {}
            """);
        sources.put("fx.Pages", """
            package fx;
            import io.github.graphmaquina.annotation.GenerateGraphApi;
            import io.github.graphmaquina.api.Machine;
            @GenerateGraphApi
            public interface Pages extends Machine<Page, PageStatus, PageTransition> {}
            """);
        sources.put("fx.DocConditions", """
            package fx;
            import io.github.graphmaquina.annotation.GraphCondition;
            import java.util.Collection;
            public class DocConditions {
                @GraphCondition
                public boolean everyPageClosed(Doc doc, Collection<Page> pages) { return pages.isEmpty(); }
            }
            """);
        return Map.copyOf(sources);
    }

    /** Repeats the watch and, across its three rules, omits each optional array in some and fills it in another. */
    private static final Map<String, String> USAGE = Map.of("use.WatchUsage", """
        package use;
        @fx.DocsPages.Watch(
                state = fx.DocStatus.REVIEW,
                peers = fx.DocRefs.Pages.class,
                owner = fx.PageRefs.Doc.class,
                rules = {
                    @fx.DocsPages.When(
                            condition = fx.DocConditionsRefs.EveryPageClosed.class,
                            transition = fx.DocTransition.PUBLISH),
                    @fx.DocsPages.When(allIn = fx.PageStatus.CLOSED, transition = fx.DocTransition.PUBLISH)
                })
        @fx.DocsPages.Watch(
                state = fx.DocStatus.DRAFT,
                peers = fx.DocRefs.Pages.class,
                owner = fx.PageRefs.Doc.class,
                rules = @fx.DocsPages.When(
                        anyIn = {fx.PageStatus.OPEN, fx.PageStatus.CLOSED},
                        noneIn = fx.PageStatus.OPEN,
                        transition = fx.DocTransition.SUBMIT))
        public interface WatchUsage {}
        """);

    @Test
    void emitsAPairVocabularyThatUsesBothEnumsAndRepeatsThroughItsContainer() {
        List<GeneratedSource> emitted = ApiEmitter.emit(model(), "io.github.graphmaquina:test:main");

        String watch = content(emitted, "fx.DocsPages");
        assertTrue(watch.contains("machine = fx.Docs.class, peer = fx.Pages.class"), watch);
        assertTrue(watch.contains("fx.DocStatus state();"), watch);
        assertTrue(watch.contains("fx.DocTransition transition();"), watch);
        assertTrue(watch.contains("fx.PageStatus[] allIn() default {};"), watch);
        assertTrue(watch.contains("java.lang.Class<? extends io.github.graphmaquina.symbol.ConditionSymbol>[] "
                + "condition() default {};"), watch);
        assertTrue(watch.contains("@java.lang.annotation.Repeatable(DocsPages.Watches.class)"), watch);
        assertTrue(watch.contains("@java.lang.annotation.Target({})"), watch);

        SourceFiles.Compilation compilation = compile(emitted, USAGE);
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
        assertEquals(List.of(), compilation.messages());
    }

    /** A rule moves the owner, so only the owner's own identifiers type its {@code transition}. */
    @Test
    void refusesATransitionOfThePeerInTheOwnerSlotOfARule() {
        List<GeneratedSource> emitted = ApiEmitter.emit(model(), "io.github.graphmaquina:test:main");

        SourceFiles.Compilation compilation = compile(emitted, Map.of("use.WrongTransition", """
            package use;
            @fx.DocsPages.Watch(
                    state = fx.DocStatus.REVIEW,
                    peers = fx.DocRefs.Pages.class,
                    owner = fx.PageRefs.Doc.class,
                    rules = @fx.DocsPages.When(
                            allIn = fx.PageStatus.CLOSED,
                            transition = fx.PageTransition.CLOSE_PAGE))
            public interface WrongTransition {}
            """));

        assertFalse(compilation.success(), () -> String.valueOf(compilation.messages()));
        assertTrue(compilation.codes().stream().allMatch(code -> code.startsWith("compiler.err.")),
                () -> String.valueOf(compilation.codes()));
    }

    /**
     * A machine has the vocabulary of each pair it declares, its watch annotations first and its sub-machine
     * annotations after them, and no pair it does not declare has one: here {@code Docs} declares {@code Pages}
     * and {@code Notes}, {@code Pages} declares {@code Docs}, and {@code Notes} declares no peer.
     */
    @Test
    void namesTheAnnotationsOfEveryOrderedPairAMachineDeclaresAndOfNoOther() {
        GenerationModel model = new GenerationModel(List.of(graph("Docs", List.of("fx.Pages", "fx.Notes")),
                graph("Notes", List.of()), graph("Pages", List.of("fx.Docs"))), List.of());

        List<String> pairs = ApiEmitter.annotationTypes(model).stream()
                .filter((String name) -> !name.contains("Graph.")).toList();

        assertEquals(List.of("fx.DocsPages.Watch", "fx.DocsPages.Watches", "fx.DocsPages.When",
                "fx.DocsPages.Submachine", "fx.DocsPages.Submachines", "fx.DocsPages.Exit",
                "fx.DocsNotes.Watch", "fx.DocsNotes.Watches", "fx.DocsNotes.When",
                "fx.DocsNotes.Submachine", "fx.DocsNotes.Submachines", "fx.DocsNotes.Exit",
                "fx.PagesDocs.Watch", "fx.PagesDocs.Watches", "fx.PagesDocs.When",
                "fx.PagesDocs.Submachine", "fx.PagesDocs.Submachines", "fx.PagesDocs.Exit"), pairs);
    }

    @Test
    void reportsAPeerThatIsNotALocalMachineAtTheOwnerOrigin() {
        GraphDescriptor owner = new GraphDescriptor("fx.Docs", "fx", "fx.Doc", "fx.DocStatus",
                List.of("DRAFT"), "fx.DocTransition", List.of("SUBMIT"), List.of("other.Bills"),
                new SourceLocation("fx/Docs.java", 7L, 1L));
        CodegenException failure = assertThrows(CodegenException.class,
                () -> ApiEmitter.emit(new GenerationModel(List.of(owner), List.of()), "io.github.graphmaquina:test:main"));

        assertEquals("SG608", failure.code());
        assertEquals("fx/Docs.java", failure.location().orElseThrow().path());
        assertTrue(failure.getMessage().contains("other.Bills"), failure.getMessage());
    }

    @Test
    void emitsTheCompilationCarrierEvenForAnEmptyModel() {
        List<GeneratedSource> emitted =
                ApiEmitter.emit(new GenerationModel(List.of(), List.of()), "io.github.graphmaquina:empty:main");

        assertEquals(1, emitted.size(), () -> String.valueOf(emitted));
        GeneratedSource carrier = emitted.get(0);
        assertTrue(carrier.qualifiedName().startsWith("io.github.graphmaquina.generated.bootstrap.GraphCompilation"),
                carrier.qualifiedName());
        assertTrue(carrier.content().contains("@GraphCompilation(\"io.github.graphmaquina:empty:main\")"),
                carrier.content());

        List<Path> files = write(directory.resolve("generated"), emitted);
        SourceFiles.Compilation compilation = SourceFiles.compileStrict(files, directory.resolve("classes"));
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
    }

    /** A machine {@code fx.<name>} over enums of its own, declaring {@code peers}. */
    private static GraphDescriptor graph(String name, List<String> peers) {
        return new GraphDescriptor("fx." + name, "fx", "fx." + name + "Entity", "fx." + name + "Status",
                List.of("OPEN"), "fx." + name + "Transition", List.of("CLOSE"), peers,
                new SourceLocation("fx/" + name + ".java", 1L, 1L));
    }

    private GenerationModel model() {
        Path sources = directory.resolve("sources");
        SourceFiles.write(sources, ORIGINALS);
        return Bootstrap.inspect(new GenerationRequest(directory, List.of(sources), SourceFiles.classpath(),
                directory.resolve("generated"), directory.resolve("processor-generated"),
                directory.resolve("classes"), directory.resolve("work"), "io.github.graphmaquina:test:main"));
    }

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
        return emitted.stream().filter(source -> source.qualifiedName().equals(qualifiedName))
                .findFirst().orElseThrow().content();
    }
}
