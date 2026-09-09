package io.stategraph.codegen.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.stategraph.codegen.CodegenException;
import io.stategraph.codegen.GenerationRequest;
import io.stategraph.codegen.model.GeneratedSource;
import io.stategraph.codegen.model.GenerationModel;
import io.stategraph.codegen.model.GraphDescriptor;
import io.stategraph.codegen.model.SourceLocation;
import io.stategraph.codegen.source.Bootstrap;
import io.stategraph.codegen.source.SourceFiles;
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
 * Verifies the vocabulary emitted for one ordered pair of local machines: the owner's enum and the peer's enum
 * are both used where the specification places them, the watch repeats through its container, every optional
 * array may be omitted, an unknown peer is refused, and an empty model still carries its compilation.
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
        sources.put("fx.Doc", """
            package fx;
            import io.stategraph.annotation.GraphProperty;
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
            import io.stategraph.annotation.GraphProperty;
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
            import io.stategraph.annotation.GenerateGraphApi;
            import io.stategraph.annotation.GraphPeers;
            import io.stategraph.api.Machine;
            @GenerateGraphApi
            @GraphPeers(Pages.class)
            public interface Docs extends Machine<Doc, DocStatus> {}
            """);
        sources.put("fx.Pages", """
            package fx;
            import io.stategraph.annotation.GenerateGraphApi;
            import io.stategraph.api.Machine;
            @GenerateGraphApi
            public interface Pages extends Machine<Page, PageStatus> {}
            """);
        sources.put("fx.DocConditions", """
            package fx;
            import io.stategraph.annotation.GraphCondition;
            import java.util.Collection;
            public class DocConditions {
                @GraphCondition
                public boolean everyPageClosed(Doc doc, Collection<Page> pages) { return pages.isEmpty(); }
            }
            """);
        return Map.copyOf(sources);
    }

    /** Repeats the watch, omits every optional array once and fills each of them once. */
    private static final Map<String, String> USAGE = Map.of("use.WatchUsage", """
        package use;
        @fx.DocsPages.Watch(
                state = fx.DocStatus.REVIEW,
                peers = fx.DocRefs.Pages.class,
                owner = fx.PageRefs.Doc.class,
                rules = {
                    @fx.DocsPages.When(
                            condition = fx.DocConditionsRefs.EveryPageClosed.class,
                            goTo = fx.DocStatus.PUBLISHED),
                    @fx.DocsPages.When(allIn = fx.PageStatus.CLOSED, goTo = fx.DocStatus.PUBLISHED)
                })
        @fx.DocsPages.Watch(
                state = fx.DocStatus.DRAFT,
                peers = fx.DocRefs.Pages.class,
                owner = fx.PageRefs.Doc.class,
                rules = @fx.DocsPages.When(
                        anyIn = {fx.PageStatus.OPEN, fx.PageStatus.CLOSED},
                        noneIn = fx.PageStatus.OPEN,
                        goTo = fx.DocStatus.REVIEW))
        public interface WatchUsage {}
        """);

    @Test
    void emitsAPairVocabularyThatUsesBothEnumsAndRepeatsThroughItsContainer() {
        List<GeneratedSource> emitted = ApiEmitter.emit(model(), "io.stategraph:test:main");

        String watch = content(emitted, "fx.DocsPages");
        assertTrue(watch.contains("machine = fx.Docs.class, peer = fx.Pages.class"), watch);
        assertTrue(watch.contains("fx.DocStatus state();"), watch);
        assertTrue(watch.contains("fx.DocStatus goTo();"), watch);
        assertTrue(watch.contains("fx.PageStatus[] allIn() default {};"), watch);
        assertTrue(watch.contains("Class<? extends ConditionSymbol>[] condition() default {};"), watch);
        assertTrue(watch.contains("@Repeatable(DocsPages.Watches.class)"), watch);
        assertTrue(watch.contains("@Target({})"), watch);

        SourceFiles.Compilation compilation = compile(emitted, USAGE);
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
        assertEquals(List.of(), compilation.messages());
    }

    @Test
    void namesTheWatchAnnotationsOfEveryOrderedPair() {
        assertTrue(ApiEmitter.annotationTypes(model()).containsAll(
                List.of("fx.DocsPages.Watch", "fx.DocsPages.Watches", "fx.DocsPages.When")),
                () -> String.valueOf(ApiEmitter.annotationTypes(model())));
    }

    @Test
    void reportsAPeerThatIsNotALocalMachineAtTheOwnerOrigin() {
        GraphDescriptor owner = new GraphDescriptor("fx.Docs", "fx", "fx.Doc", "fx.DocStatus",
                List.of("DRAFT"), List.of("other.Bills"), new SourceLocation("fx/Docs.java", 7L, 1L));
        CodegenException failure = assertThrows(CodegenException.class,
                () -> ApiEmitter.emit(new GenerationModel(List.of(owner), List.of()), "io.stategraph:test:main"));

        assertEquals("SG608", failure.code());
        assertEquals("fx/Docs.java", failure.location().orElseThrow().path());
        assertTrue(failure.getMessage().contains("other.Bills"), failure.getMessage());
    }

    @Test
    void emitsTheCompilationCarrierEvenForAnEmptyModel() {
        List<GeneratedSource> emitted =
                ApiEmitter.emit(new GenerationModel(List.of(), List.of()), "io.stategraph:empty:main");

        assertEquals(1, emitted.size(), () -> String.valueOf(emitted));
        GeneratedSource carrier = emitted.get(0);
        assertTrue(carrier.qualifiedName().startsWith("io.stategraph.generated.bootstrap.GraphCompilation_"),
                carrier.qualifiedName());
        assertTrue(carrier.content().contains("@GraphCompilation(\"io.stategraph:empty:main\")"),
                carrier.content());

        List<Path> files = write(directory.resolve("generated"), emitted);
        SourceFiles.Compilation compilation = SourceFiles.compileStrict(files, directory.resolve("classes"));
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
    }

    private GenerationModel model() {
        Path sources = directory.resolve("sources");
        SourceFiles.write(sources, ORIGINALS);
        return Bootstrap.inspect(new GenerationRequest(directory, List.of(sources), SourceFiles.classpath(),
                directory.resolve("generated"), directory.resolve("processor-generated"),
                directory.resolve("classes"), directory.resolve("work"), "io.stategraph:test:main"));
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
