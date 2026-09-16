package io.github.graphmaquina.codegen.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        sources.put("fx.DocTransition", """
            package fx;
            public enum DocTransition { SUBMIT, PUBLISH, ARCHIVE }
            """);
        sources.put("fx.Doc", """
            package fx;
            import io.github.graphmaquina.annotation.GraphProperty;
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
            import io.github.graphmaquina.annotation.GenerateGraphApi;
            import io.github.graphmaquina.api.Machine;
            @GenerateGraphApi
            public interface Docs extends Machine<Doc, DocStatus, DocTransition> {}
            """);
        sources.put("fx.DocActions", """
            package fx;
            import io.github.graphmaquina.annotation.GraphAction;
            import io.github.graphmaquina.annotation.GraphGuard;
            import io.github.graphmaquina.annotation.GraphHook;
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
        import io.github.graphmaquina.annotation.Timeout;
        @fx.DocsGraph.Definition(
                initial = fx.DocStatus.DRAFT,
                terminals = fx.DocStatus.PUBLISHED,
                allowsCycle = {},
                state = fx.DocRefs.Status.class,
                id = fx.DocRefs.Id.class)
        @fx.DocsGraph.StatePolicy(state = fx.DocStatus.DRAFT, otherwise = "a draft must be reviewed first")
        @fx.DocsGraph.StatePolicy(state = fx.DocStatus.REVIEW, otherwise = "a review must be published first")
        @fx.DocsGraph.Transition(
                id = fx.DocTransition.SUBMIT,
                from = fx.DocStatus.DRAFT,
                to = fx.DocStatus.REVIEW)
        @fx.DocsGraph.Transition(
                id = fx.DocTransition.PUBLISH,
                from = fx.DocStatus.REVIEW,
                to = fx.DocStatus.PUBLISHED,
                action = fx.DocActionsRefs.Publish.class,
                guards = fx.DocActionsRefs.Reviewed.class)
        @fx.DocsGraph.Reject(
                from = fx.DocStatus.DRAFT,
                transition = fx.DocTransition.PUBLISH,
                reason = "review first")
        public interface DocsUsage {

            @fx.DocsGraph.Transition(
                    id = fx.DocTransition.PUBLISH,
                    from = fx.DocStatus.REVIEW,
                    to = fx.DocStatus.PUBLISHED,
                    action = fx.DocActionsRefs.Publish.class,
                    guards = fx.DocActionsRefs.Reviewed.class,
                    onFailure = fx.DocTransition.ARCHIVE,
                    failsOn = java.io.IOException.class,
                    timeout = @Timeout(seconds = 5),
                    afterCommit = fx.DocActionsRefs.Notified.class)
            void publish();

            @fx.DocsGraph.Transition(
                    id = fx.DocTransition.SUBMIT,
                    from = fx.DocStatus.DRAFT,
                    to = fx.DocStatus.REVIEW)
            void review();
        }
        """);

    @Test
    void emitsVocabularyAndSymbolsThatCompileWithTheOriginalSources() {
        List<GeneratedSource> emitted = ApiEmitter.emit(model(), "io.github.graphmaquina:test:main");
        List<String> names = emitted.stream().map(GeneratedSource::qualifiedName).toList();
        assertEquals(List.of("fx.DocsGraph", "fx.DocActionsRefs", "fx.DocRefs"), names.subList(0, 3));
        assertTrue(names.get(3).matches(
                "io\\.github\\.graphmaquina\\.generated\\.bootstrap\\.GraphCompilation[0-9a-f]{16}"), names.get(3));
        assertEquals(4, names.size(), names.toString());

        String vocabulary = content(emitted, "fx.DocsGraph");
        assertTrue(vocabulary.contains("fx.DocStatus initial();"), vocabulary);
        assertTrue(vocabulary.contains("fx.DocTransition id();"), vocabulary);
        assertTrue(vocabulary.contains("fx.DocTransition[] onFailure() default {};"), vocabulary);
        assertTrue(vocabulary.contains("Class<? extends ActionSymbol>[] action() default {};"), vocabulary);
        assertTrue(vocabulary.contains("Class<? extends PropertySymbol> state();"), vocabulary);
        assertTrue(vocabulary.contains("Class<? extends PropertySymbol> id();"), vocabulary);
        assertTrue(vocabulary.contains("Class<? extends HookSymbol>[] afterCommit() default {};"), vocabulary);
        for (String retired : List.of("Row", "Rows", "Cell", "Cells", "Action", "Move", "Forbid", "Forbids")) {
            assertFalse(vocabulary.contains("public @interface " + retired + " {"), retired);
        }
        assertTrue(content(emitted, "fx.DocActionsRefs").contains("public static final class Publish "
                + "implements io.github.graphmaquina.symbol.ActionSymbol {"), content(emitted, "fx.DocActionsRefs"));

        SourceFiles.Compilation compilation = compile(emitted, USAGE);
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
        assertEquals(List.of(), compilation.messages());
    }

    @Test
    void definitionAcceptsTypedHookAndRejectsActionSymbol() {
        List<GeneratedSource> emitted = ApiEmitter.emit(model(), "io.github.graphmaquina:test:main");
        String usage = USAGE.get("use.DocsUsage").replace("id = fx.DocRefs.Id.class)",
            "id = fx.DocRefs.Id.class, afterCommit = fx.DocActionsRefs.Notified.class)");
        SourceFiles.Compilation valid = compile(emitted, Map.of("use.DocsUsage", usage));
        assertTrue(valid.success(), () -> String.valueOf(valid.messages()));
        SourceFiles.Compilation invalid = compile(emitted, Map.of("use.DocsUsage",
            usage.replace("afterCommit = fx.DocActionsRefs.Notified.class)",
                "afterCommit = fx.DocActionsRefs.Publish.class)")));
        assertFalse(invalid.success());
        assertTrue(invalid.codes().stream().allMatch(code -> code.startsWith("compiler.err.")));
    }

    @Test
    void reportsAValueOfAnotherEnumAsAJavacTypeError() {
        List<GeneratedSource> emitted = ApiEmitter.emit(model(), "io.github.graphmaquina:test:main");
        SourceFiles.Compilation compilation = compile(emitted, Map.of(
                "bad.OtherStatus", "package bad; public enum OtherStatus { NEW }",
                "bad.WrongEnum", """
                    package bad;
                    @fx.DocsGraph.StatePolicy(state = bad.OtherStatus.NEW, otherwise = "another machine's enum")
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
        "com.Acme.docs.DocTransition", """
            package com.Acme.docs;
            public enum DocTransition { SUBMIT, PUBLISH, ARCHIVE }
            """,
        "com.Acme.docs.Doc", """
            package com.Acme.docs;
            import io.github.graphmaquina.annotation.GraphProperty;
            public class Doc {
                @GraphProperty
                private DocStatus status;
                public DocStatus status() { return status; }
            }
            """,
        "com.Acme.docs.Docs", """
            package com.Acme.docs;
            import io.github.graphmaquina.annotation.GenerateGraphApi;
            import io.github.graphmaquina.api.Machine;
            @GenerateGraphApi
            public interface Docs extends Machine<Doc, DocStatus, DocTransition> {}
            """,
        "com.Acme.docs.DocActions", """
            package com.Acme.docs;
            import io.github.graphmaquina.annotation.GraphAction;
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
                directory.resolve("work"), "io.github.graphmaquina:test:main"));
        List<GeneratedSource> emitted = ApiEmitter.emit(model, "io.github.graphmaquina:test:main");

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
            @com.Acme.docs.DocsGraph.Transition(
                    id = com.Acme.docs.DocTransition.PUBLISH,
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
    void compilesNormalizedNestedHostsAndMembersWithoutChangingTheirMetadata() {
        Map<String, String> originals = Map.of("fx.Outer_Host", """
            package fx;
            import io.github.graphmaquina.annotation.*;
            import io.github.graphmaquina.api.Machine;
            public class Outer_Host {
                public enum Status { NEW, DONE }
                public enum Transition_id { PROCESS }
                public static class Entity {
                    @GraphProperty public String _id;
                    @GraphProperty public Status current_state;
                }
                public static class HTTP_actions {
                    @GraphAction public void Process(Entity entity) {}
                    @GraphGuard public boolean has_title(Entity entity) { return true; }
                }
                @GenerateGraphApi
                public interface Ticket_machine extends Machine<Entity, Status, Transition_id> {}
            }
            """);
        Path root = directory.resolve("sources");
        List<Path> files = new ArrayList<>(SourceFiles.write(root, originals));
        GenerationModel model = Bootstrap.inspect(new GenerationRequest(directory, List.of(root),
            SourceFiles.classpath(), directory.resolve("generated"), directory.resolve("processor-generated"),
            directory.resolve("classes"), directory.resolve("work"), "normalized"));
        List<GeneratedSource> emitted = ApiEmitter.emit(model, "normalized");
        assertTrue(emitted.stream().allMatch(source -> !source.qualifiedName().contains("_")));
        assertTrue(model.symbols().stream().allMatch(symbol -> !symbol.qualifiedName().contains("_")));
        assertEquals(emitted, ApiEmitter.emit(model, "normalized"));
        assertTrue(content(emitted, "fx.OuterHostHTTPActionsRefs").contains("member = \"has_title\""));
        files.addAll(write(directory.resolve("generated"), emitted));
        files.addAll(SourceFiles.write(directory.resolve("usage"), Map.of("fx.Usage", """
            package fx;
            @OuterHostTicketMachineGraph.Definition(initial = Outer_Host.Status.NEW,
                id = OuterHostEntityRefs.Id.class, state = OuterHostEntityRefs.CurrentState.class)
            @OuterHostTicketMachineGraph.Transition(id = Outer_Host.Transition_id.PROCESS,
                from = Outer_Host.Status.NEW, to = Outer_Host.Status.DONE,
                action = OuterHostHTTPActionsRefs.Process.class,
                guards = OuterHostHTTPActionsRefs.HasTitle.class)
            public interface Usage {}
            """)));
        SourceFiles.Compilation compilation = SourceFiles.compileStrict(files, directory.resolve("classes"));
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
    }

    @Test
    void compilesSymbolsWhoseNamesShadowTheGeneratorMetadataAndCategoryTypes() {
        Map<String, String> originals = Map.of("fx.Actions", """
            package fx;
            import io.github.graphmaquina.annotation.*;
            public class Actions {
                @GraphAction public void ActionSymbol() {}
                @GraphGuard public boolean GuardSymbol() { return true; }
                @GraphHook public void HookSymbol() {}
                @GraphCondition public boolean ConditionSymbol() { return true; }
                @GraphProperty public String PropertySymbol;
                @GraphAction public void GeneratedSymbol() {}
                @GraphAction public void SymbolKind() {}
            }
            """);
        Path root = directory.resolve("sources");
        List<Path> files = new ArrayList<>(SourceFiles.write(root, originals));
        GenerationModel model = Bootstrap.inspect(new GenerationRequest(directory, List.of(root),
            SourceFiles.classpath(), directory.resolve("generated"), directory.resolve("processor-generated"),
            directory.resolve("classes"), directory.resolve("work"), "shadowed-imports"));
        List<GeneratedSource> emitted = ApiEmitter.emit(model, "shadowed-imports");
        assertEquals(List.of("ActionSymbol", "ConditionSymbol", "GeneratedSymbol", "GuardSymbol", "HookSymbol",
            "PropertySymbol", "SymbolKind"), model.symbols().stream().map(symbol -> symbol.member()).toList());
        files.addAll(write(directory.resolve("generated"), emitted));
        files.addAll(SourceFiles.write(directory.resolve("usage"), Map.of("fx.Usage", """
            package fx;
            public class Usage {
                Class<? extends io.github.graphmaquina.symbol.ActionSymbol> action = ActionsRefs.ActionSymbol.class;
                Class<? extends io.github.graphmaquina.symbol.ActionSymbol> metadata = ActionsRefs.GeneratedSymbol.class;
                Class<? extends io.github.graphmaquina.symbol.ActionSymbol> kind = ActionsRefs.SymbolKind.class;
                Class<? extends io.github.graphmaquina.symbol.GuardSymbol> guard = ActionsRefs.GuardSymbol.class;
                Class<? extends io.github.graphmaquina.symbol.HookSymbol> hook = ActionsRefs.HookSymbol.class;
                Class<? extends io.github.graphmaquina.symbol.ConditionSymbol> condition = ActionsRefs.ConditionSymbol.class;
                Class<? extends io.github.graphmaquina.symbol.PropertySymbol> property = ActionsRefs.PropertySymbol.class;
            }
            """)));
        SourceFiles.Compilation compilation = SourceFiles.compileStrict(files, directory.resolve("classes"));
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
    }

    @Test
    void rejectsInvalidNormalizedMachineNamesWithTheirSourceLocation() {
        Path root = directory.resolve("sources");
        SourceFiles.write(root, Map.of("fx._1machine", """
            package fx;
            import io.github.graphmaquina.annotation.GenerateGraphApi;
            import io.github.graphmaquina.api.Machine;
            @GenerateGraphApi
            public interface _1machine extends Machine<String, Thread.State, java.time.DayOfWeek> {}
            """));
        GenerationModel model = Bootstrap.inspect(new GenerationRequest(directory, List.of(root),
            SourceFiles.classpath(), directory.resolve("generated"), directory.resolve("processor-generated"),
            directory.resolve("classes"), directory.resolve("work"), "invalid"));
        CodegenException failure = assertThrows(CodegenException.class, () -> ApiEmitter.emit(model, "invalid"));
        assertEquals("SG603", failure.code());
        assertEquals("sources/fx/_1machine.java", failure.location().orElseThrow().path());
        assertTrue(failure.getMessage().contains("_1machine"), failure.getMessage());
    }

    @Test
    void namesEveryGeneratedAnnotationAndContainerCanonically() {
        assertEquals(List.of("fx.DocsGraph.Definition", "fx.DocsGraph.Reject", "fx.DocsGraph.Rejects",
                "fx.DocsGraph.StatePolicies", "fx.DocsGraph.StatePolicy", "fx.DocsGraph.Transition",
                "fx.DocsGraph.Transitions"),
                ApiEmitter.annotationTypes(model()).stream().sorted().toList());
    }

    /** The processor routes on these markers, so each annotation must carry the kind it was emitted for. */
    @Test
    void marksEveryAnnotationWithTheVocabularyKindItDeclares() {
        String vocabulary = content(ApiEmitter.emit(model(), "io.github.graphmaquina:test:main"), "fx.DocsGraph");
        List<String> kinds = new ArrayList<>();
        for (String line : vocabulary.lines().toList()) {
            int marker = line.indexOf("VocabularyKind.");
            if (marker >= 0) {
                kinds.add(line.substring(marker + "VocabularyKind.".length(), line.lastIndexOf(')')));
            }
        }
        assertEquals(List.of("DEFINITION", "STATE_POLICY", "STATE_POLICIES", "TRANSITION", "TRANSITIONS",
                "REJECT", "REJECTS"), kinds);
    }

    /**
     * The three type errors the generated vocabulary must leave to javac: a state constant where a transition
     * identifier belongs, a foreign machine's constant in {@code from}, and — for the same declaration written
     * correctly — no error at all.
     */
    @Test
    void refusesAStateConstantAsATransitionIdentifierAndAForeignConstantInFrom() {
        List<GeneratedSource> emitted = ApiEmitter.emit(model(), "io.github.graphmaquina:test:main");
        String declaration = """
            package use;
            @fx.DocsGraph.Transition(id = %s, from = %s, to = fx.DocStatus.REVIEW)
            public interface TypedUsage {}
            """;

        SourceFiles.Compilation correct = compile(emitted, Map.of("use.TypedUsage",
            declaration.formatted("fx.DocTransition.SUBMIT", "fx.DocStatus.DRAFT")));
        assertTrue(correct.success(), () -> String.valueOf(correct.messages()));

        SourceFiles.Compilation stateAsIdentifier = compile(emitted, Map.of("use.TypedUsage",
            declaration.formatted("fx.DocStatus.DRAFT", "fx.DocStatus.DRAFT")));
        assertFalse(stateAsIdentifier.success());
        assertTrue(stateAsIdentifier.codes().stream().allMatch(code -> code.startsWith("compiler.err.")),
            () -> String.valueOf(stateAsIdentifier.codes()));

        SourceFiles.Compilation foreignFrom = compile(emitted, Map.of(
            "bad.OtherStatus", "package bad; public enum OtherStatus { NEW }",
            "use.TypedUsage", declaration.formatted("fx.DocTransition.SUBMIT", "bad.OtherStatus.NEW")));
        assertFalse(foreignFrom.success());
        assertTrue(foreignFrom.codes().stream().allMatch(code -> code.startsWith("compiler.err.")),
            () -> String.valueOf(foreignFrom.codes()));
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
