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
import java.util.TreeMap;
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

    /**
     * The originals of {@link #ORIGINALS} with a second machine, {@code fx.Pages}, which {@code fx.Docs} declares as
     * its peer, so the generation also emits the watch vocabulary of {@code fx.Docs} over it.
     */
    private static Map<String, String> withPeer() {
        Map<String, String> sources = new LinkedHashMap<>(ORIGINALS);
        sources.put("fx.Docs", """
            package fx;
            import io.github.graphmaquina.annotation.GenerateGraphApi;
            import io.github.graphmaquina.annotation.GraphPeers;
            import io.github.graphmaquina.api.Machine;
            @GenerateGraphApi
            @GraphPeers(Pages.class)
            public interface Docs extends Machine<Doc, DocStatus, DocTransition> {}
            """);
        sources.put("fx.PageStatus", """
            package fx;
            public enum PageStatus { OPEN, CLOSED }
            """);
        sources.put("fx.PageTransition", """
            package fx;
            public enum PageTransition { CLOSE_PAGE }
            """);
        sources.put("fx.Page", """
            package fx;
            public class Page { }
            """);
        sources.put("fx.Pages", """
            package fx;
            import io.github.graphmaquina.annotation.GenerateGraphApi;
            import io.github.graphmaquina.api.Machine;
            @GenerateGraphApi
            public interface Pages extends Machine<Page, PageStatus, PageTransition> {}
            """);
        return sources;
    }

    /**
     * Applies the annotations emitted for the machine as an application would: {@code Definition} and repeated
     * {@code StatePolicy}, {@code Transition} and {@code Reject} on the type, which javac gathers into their
     * containers, and {@code Transition} on two methods.
     */
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
        @fx.DocsGraph.Reject(
                from = fx.DocStatus.PUBLISHED,
                transition = fx.DocTransition.SUBMIT,
                reason = "already published")
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
        assertTrue(vocabulary.contains("java.lang.Class<? extends io.github.graphmaquina.symbol.ActionSymbol>[] "
                + "action() default {};"), vocabulary);
        assertTrue(vocabulary.contains("java.lang.Class<? extends io.github.graphmaquina.symbol.PropertySymbol> "
                + "state();"), vocabulary);
        assertTrue(vocabulary.contains("java.lang.Class<? extends io.github.graphmaquina.symbol.PropertySymbol> "
                + "id();"), vocabulary);
        assertTrue(vocabulary.contains("java.lang.Class<? extends io.github.graphmaquina.symbol.HookSymbol>[] "
                + "afterCommit() default {};"), vocabulary);
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

    /**
     * A type of the machine's own package shadows the {@code java.lang} type of the same simple name inside the
     * vocabulary generated there, so the vocabulary spells every name it does not declare itself qualified.
     */
    @Test
    void compilesTheVocabularyOfAPackageThatDeclaresClassStringAndThrowable() {
        Map<String, String> originals = Map.of(
                "school.Class", """
                    package school;
                    import io.github.graphmaquina.annotation.GraphProperty;
                    public class Class {
                        public enum Phase { PLANNED, RUNNING }
                        public enum Step { START }
                        @GraphProperty
                        private Phase phase;
                        @GraphProperty
                        private java.lang.String id;
                        public Phase phase() { return phase; }
                        public java.lang.String id() { return id; }
                    }
                    """,
                "school.String", "package school; public final class String {}",
                "school.Throwable", "package school; public final class Throwable {}",
                "school.Classes", """
                    package school;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Classes extends Machine<school.Class, school.Class.Phase, school.Class.Step> {}
                    """);
        Path root = directory.resolve("sources");
        List<Path> files = new ArrayList<>(SourceFiles.write(root, originals));
        GenerationModel model = Bootstrap.inspect(new GenerationRequest(directory, List.of(root),
            SourceFiles.classpath(), directory.resolve("generated"), directory.resolve("processor-generated"),
            directory.resolve("classes"), directory.resolve("work"), "school"));
        files.addAll(write(directory.resolve("generated"), ApiEmitter.emit(model, "school")));
        files.addAll(SourceFiles.write(directory.resolve("usage"), Map.of("use.ClassesUsage", """
            package use;
            @school.ClassesGraph.Definition(initial = school.Class.Phase.PLANNED,
                state = school.ClassRefs.Phase.class, id = school.ClassRefs.Id.class)
            @school.ClassesGraph.StatePolicy(state = school.Class.Phase.PLANNED, otherwise = "start it first")
            @school.ClassesGraph.Transition(id = school.Class.Step.START, from = school.Class.Phase.PLANNED,
                to = school.Class.Phase.RUNNING, failsOn = java.io.IOException.class)
            @school.ClassesGraph.Reject(from = school.Class.Phase.RUNNING, transition = school.Class.Step.START,
                reason = "already running")
            public interface ClassesUsage {}
            """)));

        SourceFiles.Compilation compilation = SourceFiles.compileStrict(files, directory.resolve("classes"));
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
    }

    /**
     * The watch vocabulary of machines {@code Retention} and {@code Policy} is named {@code RetentionPolicy}, a
     * simple name an import of the vocabulary's own unit would already claim; no unit generated in the machines'
     * package imports anything.
     */
    @Test
    void compilesAWatchVocabularyNamedLikeAnAnnotationTypeOfTheJdk() {
        Map<String, String> originals = Map.of(
                "fx.Doc", "package fx; public class Doc { public enum Status { A } public enum Step { GO } }",
                "fx.Policy", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Policy extends Machine<Doc, Doc.Status, Doc.Step> {}
                    """,
                "fx.Retention", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.annotation.GraphPeers;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    @GraphPeers(Policy.class)
                    public interface Retention extends Machine<Doc, Doc.Status, Doc.Step> {}
                    """);
        Path root = directory.resolve("sources");
        List<Path> files = new ArrayList<>(SourceFiles.write(root, originals));
        GenerationModel model = Bootstrap.inspect(new GenerationRequest(directory, List.of(root),
            SourceFiles.classpath(), directory.resolve("generated"), directory.resolve("processor-generated"),
            directory.resolve("classes"), directory.resolve("work"), "retention"));
        List<GeneratedSource> emitted = ApiEmitter.emit(model, "retention");
        assertTrue(emitted.stream().anyMatch(source -> source.qualifiedName().equals("fx.RetentionPolicy")));
        files.addAll(write(directory.resolve("generated"), emitted));

        SourceFiles.Compilation compilation = SourceFiles.compileStrict(files, directory.resolve("classes"));
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
        assertTrue(emitted.stream().filter(source -> source.qualifiedName().startsWith("fx."))
            .noneMatch(source -> source.content().contains("\nimport ")),
            () -> emitted.stream().map(GeneratedSource::content).toList().toString());
    }

    /**
     * A type of the unnamed package can only be named by its simple name, which a type nested in the generated
     * unit shadows inside that unit, whatever the generator writes. Such a name is refused at its declaration.
     */
    @Test
    void refusesANameOfTheUnnamedPackageThatAGeneratedNestedTypeShadows() {
        GenerationModel tickets = inspect("tickets", Map.of(
                "Doc", "public class Doc {}",
                "Timeout", "public enum Timeout { OPEN, EXPIRED }",
                "Transition", "public enum Transition { EXPIRE }",
                "Tickets", """
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Tickets extends Machine<Doc, Timeout, Transition> {}
                    """));
        CodegenException vocabulary = assertThrows(CodegenException.class, () -> ApiEmitter.emit(tickets, "tickets"));
        assertEquals("SG603", vocabulary.code());
        assertEquals("the vocabulary TicketsGraph cannot name the transition type Transition of Tickets: inside it,"
                + " Transition means its own nested type TicketsGraph.Transition", vocabulary.getMessage());
        assertEquals("tickets/Tickets.java", vocabulary.location().orElseThrow().path());

        GenerationModel item = inspect("item", Map.of("Item", """
                import io.github.graphmaquina.annotation.GraphProperty;
                public class Item {
                    @GraphProperty
                    private int item;
                    public int getItem() { return item; }
                }
                """));
        CodegenException container = assertThrows(CodegenException.class, () -> ApiEmitter.emit(item, "item"));
        assertEquals("SG603", container.code());
        assertEquals("the references container ItemRefs cannot name its host Item: inside it, Item means its own"
                + " nested type ItemRefs.Item", container.getMessage());
        assertEquals("item/Item.java", container.location().orElseThrow().path());
        assertEquals(3L, container.location().orElseThrow().line());
    }

    /**
     * A type the generator creates in a package is a top-level type there, in scope inside every unit generated in
     * that package, so it obscures a package of the same name (JLS 6.4.2). A name whose first segment is one of them
     * is refused at its declaration: an enum of package {@code DocsGraph} inside {@code fx.DocsGraph}, a peer of
     * that package inside the watch vocabulary {@code fx.DocsPages}, and a host of package {@code DocRefs} inside its
     * own container {@code DocRefs.DocRefs}.
     */
    @Test
    void refusesANameThatATypeGeneratedInTheSamePackageObscures() {
        GenerationModel vocabulary = inspect("vocabulary", Map.of(
                "DocsGraph.Doc",
                    "package DocsGraph; public class Doc { public enum Status { DRAFT } public enum Step { GO } }",
                "fx.Docs", """
                    package fx;
                    import DocsGraph.Doc;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Docs extends Machine<Doc, Doc.Status, Doc.Step> {}
                    """));
        CodegenException machine = assertThrows(CodegenException.class,
                () -> ApiEmitter.emit(vocabulary, "vocabulary"));
        assertEquals("SG603", machine.code());
        assertEquals("the vocabulary DocsGraph cannot name the state type DocsGraph.Doc.Status of fx.Docs: inside it,"
                + " DocsGraph means the type fx.DocsGraph generated in package fx", machine.getMessage());
        assertEquals("vocabulary/fx/Docs.java", machine.location().orElseThrow().path());

        GenerationModel pair = inspect("pair", Map.of(
                "DocsGraph.Page",
                    "package DocsGraph; public class Page { public enum Status { OPEN } public enum Step { GO } }",
                "DocsGraph.Pages", """
                    package DocsGraph;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Pages extends Machine<Page, Page.Status, Page.Step> {}
                    """,
                "fx.Doc", "package fx; public class Doc { public enum Status { DRAFT } public enum Step { GO } }",
                "fx.Docs", """
                    package fx;
                    import DocsGraph.Pages;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.annotation.GraphPeers;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    @GraphPeers(Pages.class)
                    public interface Docs extends Machine<Doc, Doc.Status, Doc.Step> {}
                    """));
        CodegenException watch = assertThrows(CodegenException.class, () -> ApiEmitter.emit(pair, "pair"));
        assertEquals("SG603", watch.code());
        assertEquals("the watch vocabulary DocsPages cannot name the peer machine DocsGraph.Pages: inside it,"
                + " DocsGraph means the type fx.DocsGraph generated in package fx", watch.getMessage());
        assertEquals("pair/fx/Docs.java", watch.location().orElseThrow().path());

        GenerationModel host = inspect("host", Map.of("DocRefs.Doc", """
                package DocRefs;
                import io.github.graphmaquina.annotation.GraphAction;
                public class Doc {
                    @GraphAction
                    public void archive(Object doc) {}
                }
                """));
        CodegenException container = assertThrows(CodegenException.class, () -> ApiEmitter.emit(host, "host"));
        assertEquals("SG603", container.code());
        assertEquals("the references container DocRefs cannot name its host DocRefs.Doc: inside it, DocRefs means"
                + " the type DocRefs.DocRefs generated in package DocRefs", container.getMessage());
        assertEquals("host/DocRefs/Doc.java", container.location().orElseThrow().path());
    }

    /** A name of the unnamed package that no generated type shadows still names the application's own type. */
    @Test
    void compilesAVocabularyOfTheUnnamedPackageWhoseStateEnumIsNamedTimeout() {
        Map<String, String> originals = Map.of(
                "Doc", """
                    import io.github.graphmaquina.annotation.GraphProperty;
                    public class Doc {
                        @GraphProperty
                        private Timeout status;
                        @GraphProperty
                        private String id;
                        public Timeout status() { return status; }
                        public String id() { return id; }
                    }
                    """,
                "Timeout", "public enum Timeout { OPEN, EXPIRED }",
                "Move", "public enum Move { EXPIRE }",
                "Tickets", """
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Tickets extends Machine<Doc, Timeout, Move> {}
                    """);
        List<Path> files = new ArrayList<>(SourceFiles.write(directory.resolve("timeout"), originals));
        files.addAll(write(directory.resolve("generated"), ApiEmitter.emit(inspect("timeout", originals), "timeout")));
        files.addAll(SourceFiles.write(directory.resolve("usage"), Map.of("TicketsUsage", """
                @TicketsGraph.Definition(initial = Timeout.OPEN, terminals = Timeout.EXPIRED,
                    state = DocRefs.Status.class, id = DocRefs.Id.class)
                @TicketsGraph.Transition(id = Move.EXPIRE, from = Timeout.OPEN, to = Timeout.EXPIRED,
                    timeout = @io.github.graphmaquina.annotation.Timeout(seconds = 5))
                public interface TicketsUsage {}
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

    /** The annotations of both machine vocabularies and of the watch vocabulary of the pair they form. */
    @Test
    void namesEveryGeneratedAnnotationAndContainerCanonically() {
        assertEquals(List.of("fx.DocsGraph.Definition", "fx.DocsGraph.Reject", "fx.DocsGraph.Rejects",
                "fx.DocsGraph.StatePolicies", "fx.DocsGraph.StatePolicy", "fx.DocsGraph.Transition",
                "fx.DocsGraph.Transitions", "fx.DocsPages.Watch", "fx.DocsPages.Watches", "fx.DocsPages.When",
                "fx.PagesGraph.Definition", "fx.PagesGraph.Reject", "fx.PagesGraph.Rejects",
                "fx.PagesGraph.StatePolicies", "fx.PagesGraph.StatePolicy", "fx.PagesGraph.Transition",
                "fx.PagesGraph.Transitions"),
                ApiEmitter.annotationTypes(inspect("peered", withPeer())).stream().sorted().toList());
    }

    /**
     * The processor routes on these markers, so each annotation must carry the kind it was emitted for, in both
     * machine vocabularies and in the watch vocabulary of the pair they form.
     */
    @Test
    void marksEveryAnnotationWithTheVocabularyKindItDeclares() {
        Map<String, String> kinds = new TreeMap<>();
        for (GeneratedSource source : ApiEmitter.emit(inspect("peered", withPeer()), "peered")) {
            String kind = null;
            for (String line : source.content().lines().map(String::strip).toList()) {
                int marker = line.indexOf("VocabularyKind.");
                if (marker >= 0) {
                    kind = line.substring(marker + "VocabularyKind.".length(), line.lastIndexOf(')'));
                } else if (kind != null && line.startsWith("public @interface ")) {
                    String name = line.substring("public @interface ".length(), line.indexOf(" {"));
                    kinds.put(source.qualifiedName() + '.' + name, kind);
                    kind = null;
                }
            }
        }

        Map<String, String> expected = new TreeMap<>();
        for (String vocabulary : List.of("fx.DocsGraph", "fx.PagesGraph")) {
            expected.put(vocabulary + ".Definition", "DEFINITION");
            expected.put(vocabulary + ".StatePolicy", "STATE_POLICY");
            expected.put(vocabulary + ".StatePolicies", "STATE_POLICIES");
            expected.put(vocabulary + ".Transition", "TRANSITION");
            expected.put(vocabulary + ".Transitions", "TRANSITIONS");
            expected.put(vocabulary + ".Reject", "REJECT");
            expected.put(vocabulary + ".Rejects", "REJECTS");
        }
        expected.put("fx.DocsPages.Watch", "WATCH");
        expected.put("fx.DocsPages.Watches", "WATCHES");
        expected.put("fx.DocsPages.When", "WHEN");
        assertEquals(expected, kinds);
    }

    /**
     * The two type errors the generated vocabulary must leave to javac: a state constant where a transition
     * identifier belongs and a foreign machine's constant in {@code from}. The same declaration written correctly
     * compiles.
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

    /** Writes {@code originals} under the source root {@code root} of the test directory and inspects them. */
    private GenerationModel inspect(String root, Map<String, String> originals) {
        Path sources = directory.resolve(root);
        SourceFiles.write(sources, originals);
        return Bootstrap.inspect(new GenerationRequest(directory, List.of(sources), SourceFiles.classpath(),
                directory.resolve("generated"), directory.resolve("processor-generated"),
                directory.resolve("classes"), directory.resolve("work"), root));
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
