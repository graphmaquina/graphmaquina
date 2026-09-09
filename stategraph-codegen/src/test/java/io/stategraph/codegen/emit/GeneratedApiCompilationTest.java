package io.stategraph.codegen.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.stategraph.codegen.GenerationRequest;
import io.stategraph.codegen.model.GeneratedSource;
import io.stategraph.codegen.model.GenerationModel;
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
 * Verifies that the vocabulary and the symbols emitted for a minimal machine compile together with the original
 * sources under the project's own options, and that a value of another machine's enum is rejected by javac
 * itself rather than by the generator.
 */
class GeneratedApiCompilationTest {

    @TempDir
    Path directory;

    private static final Map<String, String> ORIGINALS = originals();

    private static Map<String, String> originals() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", """
            package fx;
            public enum DocStatus { DRAFT, REVIEW, PUBLISHED }
            """);
        sources.put("fx.Doc", """
            package fx;
            import io.stategraph.annotation.GraphProperty;
            public class Doc {
                @GraphProperty
                private DocStatus status;
                @GraphProperty
                private String id;
                public DocStatus status() { return status; }
                public void setStatus(DocStatus next) { this.status = next; }
                public String id() { return id; }
            }
            """);
        sources.put("fx.Docs", """
            package fx;
            import io.stategraph.annotation.GenerateGraphApi;
            import io.stategraph.api.Machine;
            @GenerateGraphApi
            public interface Docs extends Machine<Doc, DocStatus> {}
            """);
        sources.put("fx.DocActions", """
            package fx;
            import io.stategraph.annotation.GraphAction;
            import io.stategraph.annotation.GraphGuard;
            import io.stategraph.annotation.GraphHook;
            public class DocActions {
                @GraphAction
                public Doc publish(Doc doc) { return doc; }
                @GraphGuard
                public boolean reviewed(Doc doc) { return doc != null; }
                @GraphHook
                public void notified(Doc doc) { }
            }
            """);
        return Map.copyOf(sources);
    }

    /** Applies every emitted annotation of the machine exactly as an application would. */
    private static final Map<String, String> USAGE = Map.of("use.DocsUsage", """
        package use;
        import io.stategraph.annotation.Timeout;
        @fx.DocsGraph.Definition(
                initial = fx.DocStatus.DRAFT,
                terminals = fx.DocStatus.PUBLISHED,
                allowsCycle = {},
                state = fx.DocRefs.Status.class,
                id = fx.DocRefs.Id.class)
        @fx.DocsGraph.Row(state = fx.DocStatus.DRAFT, otherwise = "a draft must be reviewed first")
        @fx.DocsGraph.Row(state = fx.DocStatus.REVIEW, otherwise = "a review must be published first")
        @fx.DocsGraph.Cell(from = fx.DocStatus.DRAFT, to = fx.DocStatus.REVIEW)
        @fx.DocsGraph.Cell(
                from = fx.DocStatus.REVIEW,
                to = fx.DocStatus.PUBLISHED,
                action = fx.DocActionsRefs.Publish.class,
                guards = fx.DocActionsRefs.Reviewed.class)
        @fx.DocsGraph.Forbid(from = fx.DocStatus.DRAFT, to = fx.DocStatus.PUBLISHED, reason = "review first")
        public interface DocsUsage {

            @fx.DocsGraph.Action(
                    from = fx.DocStatus.REVIEW,
                    to = fx.DocStatus.PUBLISHED,
                    action = fx.DocActionsRefs.Publish.class,
                    guards = fx.DocActionsRefs.Reviewed.class,
                    onFailure = fx.DocStatus.DRAFT,
                    failsOn = java.io.IOException.class,
                    timeout = @Timeout(seconds = 5),
                    afterCommit = fx.DocActionsRefs.Notified.class)
            void publish();

            @fx.DocsGraph.Move(from = fx.DocStatus.DRAFT, to = fx.DocStatus.REVIEW)
            void review();
        }
        """);

    @Test
    void emitsVocabularyAndSymbolsThatCompileWithTheOriginalSources() {
        List<GeneratedSource> emitted = ApiEmitter.emit(model(), "io.stategraph:test:main");
        List<String> names = emitted.stream().map(GeneratedSource::qualifiedName).toList();
        assertEquals(List.of("fx.DocsGraph", "fx.DocActionsRefs", "fx.DocRefs"), names.subList(0, 3));
        assertTrue(names.get(3).matches(
                "io\\.stategraph\\.generated\\.bootstrap\\.GraphCompilation_[0-9a-f]{16}"), names.get(3));
        assertEquals(4, names.size(), names.toString());

        String vocabulary = content(emitted, "fx.DocsGraph");
        assertTrue(vocabulary.contains("fx.DocStatus initial();"), vocabulary);
        assertTrue(vocabulary.contains("Class<? extends ActionSymbol> action();"), vocabulary);
        assertTrue(content(emitted, "fx.DocActionsRefs").contains("public static final class Publish "
                + "implements ActionSymbol {"), content(emitted, "fx.DocActionsRefs"));

        SourceFiles.Compilation compilation = compile(emitted, USAGE);
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
        assertEquals(List.of(), compilation.messages());
    }

    @Test
    void reportsAValueOfAnotherEnumAsAJavacTypeError() {
        List<GeneratedSource> emitted = ApiEmitter.emit(model(), "io.stategraph:test:main");
        SourceFiles.Compilation compilation = compile(emitted, Map.of(
                "bad.OtherStatus", "package bad; public enum OtherStatus { NEW }",
                "bad.WrongEnum", """
                    package bad;
                    @fx.DocsGraph.Row(state = bad.OtherStatus.NEW, otherwise = "another machine's enum")
                    public interface WrongEnum {}
                    """));

        assertFalse(compilation.success(), () -> String.valueOf(compilation.messages()));
        assertTrue(compilation.codes().stream().allMatch(code -> code.startsWith("compiler.err.")),
                () -> String.valueOf(compilation.codes()));
        assertTrue(compilation.messages().stream().noneMatch(message -> message.contains("SG5")),
                () -> String.valueOf(compilation.messages()));
    }

    /** A package whose second segment is upper-case: legal Java that no canonical name can be split by hand. */
    private static final Map<String, String> UPPER_CASE_PACKAGE = Map.of(
        "com.Acme.docs.DocStatus", """
            package com.Acme.docs;
            public enum DocStatus { DRAFT, PUBLISHED }
            """,
        "com.Acme.docs.Doc", """
            package com.Acme.docs;
            import io.stategraph.annotation.GraphProperty;
            public class Doc {
                @GraphProperty
                private DocStatus status;
                public DocStatus status() { return status; }
            }
            """,
        "com.Acme.docs.Docs", """
            package com.Acme.docs;
            import io.stategraph.annotation.GenerateGraphApi;
            import io.stategraph.api.Machine;
            @GenerateGraphApi
            public interface Docs extends Machine<Doc, DocStatus> {}
            """,
        "com.Acme.docs.DocActions", """
            package com.Acme.docs;
            import io.stategraph.annotation.GraphAction;
            public class DocActions {
                @GraphAction
                public Doc publish(Doc doc) { return doc; }
            }
            """);

    @Test
    void keepsTheDeclaredPackageOfAFacadeWhoseSegmentsAreNotAllLowerCase() {
        Path sources = directory.resolve("sources");
        SourceFiles.write(sources, UPPER_CASE_PACKAGE);
        GenerationModel model = Bootstrap.inspect(new GenerationRequest(directory, List.of(sources),
                SourceFiles.classpath(), directory.resolve("generated"),
                directory.resolve("processor-generated"), directory.resolve("classes"),
                directory.resolve("work"), "io.stategraph:test:main"));
        List<GeneratedSource> emitted = ApiEmitter.emit(model, "io.stategraph:test:main");

        assertEquals(List.of("com.Acme.docs.DocsGraph", "com.Acme.docs.DocActionsRefs",
                "com.Acme.docs.DocRefs"),
                emitted.stream().map(GeneratedSource::qualifiedName)
                        .filter(name -> name.startsWith("com.")).toList());
        assertTrue(content(emitted, "com.Acme.docs.DocsGraph").startsWith("package com.Acme.docs;\n"),
                content(emitted, "com.Acme.docs.DocsGraph"));
        assertTrue(content(emitted, "com.Acme.docs.DocActionsRefs").startsWith("package com.Acme.docs;\n"),
                content(emitted, "com.Acme.docs.DocActionsRefs"));

        List<Path> files = new ArrayList<>(SourceFiles.write(sources, UPPER_CASE_PACKAGE));
        files.addAll(SourceFiles.write(directory.resolve("usage"), Map.of("use.AcmeUsage", """
            package use;
            @com.Acme.docs.DocsGraph.Definition(
                    initial = com.Acme.docs.DocStatus.DRAFT,
                    state = com.Acme.docs.DocRefs.Status.class,
                    id = com.Acme.docs.DocRefs.Status.class)
            @com.Acme.docs.DocsGraph.Cell(
                    from = com.Acme.docs.DocStatus.DRAFT,
                    to = com.Acme.docs.DocStatus.PUBLISHED,
                    action = com.Acme.docs.DocActionsRefs.Publish.class)
            public interface AcmeUsage {}
            """)));
        List<Path> generated = write(directory.resolve("generated"), emitted);
        files.addAll(generated);
        assertTrue(generated.contains(directory.resolve("generated/com/Acme/docs/DocsGraph.java")),
                generated.toString());

        SourceFiles.Compilation compilation = SourceFiles.compileStrict(files, directory.resolve("classes"));
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
        assertEquals(List.of(), compilation.messages());
    }

    @Test
    void namesEveryGeneratedAnnotationAndContainerCanonically() {
        assertEquals(List.of("fx.DocsGraph.Action", "fx.DocsGraph.Cell", "fx.DocsGraph.Cells",
                "fx.DocsGraph.Definition", "fx.DocsGraph.Forbid", "fx.DocsGraph.Forbids", "fx.DocsGraph.Move",
                "fx.DocsGraph.Row", "fx.DocsGraph.Rows"),
                ApiEmitter.annotationTypes(model()).stream().sorted().toList());
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
