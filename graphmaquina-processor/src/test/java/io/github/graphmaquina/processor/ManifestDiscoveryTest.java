package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The processor must know exactly which annotations it owns: the fixed ones it always claims plus the ones the
 * manifest of the compilation lists, and nothing else. A manifest it cannot trust leaves it with the fixed set
 * alone and one SG602.
 */
class ManifestDiscoveryTest {

    /** Every annotation the processor claims whatever the compilation generates. */
    private static final Set<String> FIXED = Set.of(
            "io.github.graphmaquina.annotation.GenerateGraphApi",
            "io.github.graphmaquina.annotation.GraphPeers",
            "io.github.graphmaquina.annotation.GraphAction",
            "io.github.graphmaquina.annotation.GraphGuard",
            "io.github.graphmaquina.annotation.GraphHook",
            "io.github.graphmaquina.annotation.GraphCondition",
            "io.github.graphmaquina.annotation.GraphLookup",
            "io.github.graphmaquina.annotation.GraphProperty",
            "io.github.graphmaquina.annotation.GraphBindings",
            "io.github.graphmaquina.annotation.Timeout",
            "io.github.graphmaquina.annotation.internal.GeneratedVocabulary",
            "io.github.graphmaquina.annotation.internal.GeneratedSymbol",
            "io.github.graphmaquina.annotation.internal.GraphCompilation",
            "io.github.graphmaquina.annotation.internal.GeneratedCompanion",
            "io.github.graphmaquina.annotation.StateGraphs");

    /** What other source generators mark their output with, which this processor neither claims nor reads. */
    private static final String FOREIGN_GENERATED = """
            package thirdparty;
            import javax.annotation.processing.Generated;
            @Generated("org.mapstruct.ap.MappingProcessor")
            public class MapperImpl { }
            """;

    /** A machine marked for generation whose vocabulary no generation emitted, so nothing can read it. */
    private static final Map<String, String> UNGENERATED = Map.of("fx.Docs", """
            package fx;
            import io.github.graphmaquina.annotation.GenerateGraphApi;
            @GenerateGraphApi
            public interface Docs { }
            """);

    /** The vocabulary the generator emits for the machine of {@link TypedFixtures#minimal()}. */
    private static final Set<String> GENERATED = Set.of(
            "fx.DocsGraph.Definition", "fx.DocsGraph.StatePolicy", "fx.DocsGraph.StatePolicies",
            "fx.DocsGraph.Transition", "fx.DocsGraph.Transitions", "fx.DocsGraph.Recovery",
            "fx.DocsGraph.Reject", "fx.DocsGraph.Rejects");

    @Test
    void claimsTheFixedSetAndEveryAnnotationTheManifestLists() {
        Compilation.Result result = record(TypedFixtures.minimal(), ManifestProcessors.Recording.class);
        assertTrue(result.success(), result.describe());

        Set<String> claimed = ManifestProcessors.Recording.claimed();
        assertTrue(claimed.containsAll(List.of("fx.DocsGraph.Transition", "fx.DocsGraph.Transitions",
                "io.github.graphmaquina.annotation.internal.GraphCompilation",
                "io.github.graphmaquina.annotation.internal.GeneratedCompanion")), claimed::toString);
        assertFalse(claimed.contains(ManifestProcessors.GeneratedProbe.GENERATED), claimed::toString);
        assertFalse(claimed.stream().anyMatch(name -> name.contains("*")), claimed::toString);
        assertEquals(union(FIXED, GENERATED), new TreeSet<>(claimed));
    }

    /**
     * A foreign {@code @Generated} is not a declaration of this library: a compilation that carries nothing
     * else does not reach the processor at all, so it is not refused for the manifest it has no use for.
     */
    @Test
    void ignoresACompilationWhoseOnlyAnnotationIsAForeignGenerated() {
        Compilation.Result result = Compilation.compileRaw(Map.of("thirdparty.MapperImpl", FOREIGN_GENERATED),
                List.of("-Xlint:-processing"));

        assertEquals(0, result.countError("SG602"), result.describe());
        assertTrue(result.success(), result.describe());
    }

    /** The processors ordered after this one still receive the {@code @Generated} of other tools. */
    @Test
    void leavesAForeignGeneratedToTheProcessorsAfterIt() {
        Map<String, String> sources = new LinkedHashMap<>(TypedFixtures.minimal());
        sources.put("thirdparty.MapperImpl", FOREIGN_GENERATED);
        ManifestProcessors.GeneratedProbe.reset();

        Compilation.Result result = Compilation.compilePrepared(Compilation.prepare(sources), processors(
                StateGraphProcessor.class, ManifestProcessors.GeneratedProbe.class));

        assertTrue(result.success(), result.describe());
        assertTrue(result.hasGenerated("fx.DocsRuntime"), result.describe());
        assertEquals(Set.of(ManifestProcessors.GeneratedProbe.GENERATED), ManifestProcessors.GeneratedProbe.seen());
    }

    @Test
    void readsRepeatedVocabularyAndItsTypedValuesFromTheMetadata() {
        Compilation.Result result = record(TypedFixtures.minimal(), ManifestProcessors.Recording.class);
        assertTrue(result.success(), result.describe());

        assertEquals(List.of("DRAFT"), ManifestProcessors.Recording.policies());
        assertEquals(List.of("fx.DocActionsRefs.Publish"), ManifestProcessors.Recording.actions());
    }

    @Test
    void leavesTheAnnotationOfAnotherProcessorToThatProcessor() {
        Map<String, String> sources = new LinkedHashMap<>(TypedFixtures.minimal());
        sources.put("fx.OtherMarker", """
                package fx;
                public @interface OtherMarker { }
                """);
        sources.put("fx.Other", """
                package fx;
                @OtherMarker
                public final class Other { }
                """);

        Compilation.Result result = record(sources, ManifestProcessors.Recording.class,
                ManifestProcessors.Other.class);

        assertTrue(result.success(), result.describe());
        assertFalse(ManifestProcessors.Recording.claimed().contains(ManifestProcessors.Other.MARKER),
                () -> ManifestProcessors.Recording.claimed().toString());
        assertEquals(Set.of(ManifestProcessors.Other.MARKER), ManifestProcessors.Other.seen());
    }

    @Test
    void keepsOnlyTheFixedSetWhenTheManifestIsNotUsable() {
        Compilation.Prepared prepared = Compilation.prepare(TypedFixtures.minimal());
        rewrite(prepared, manifest -> annotationTypes(manifest, added(manifest, "fx.DocsGraph.Nope")));

        ManifestProcessors.Recording.reset();
        ManifestProcessors.Other.reset();
        Compilation.compileWithoutLifecycle(prepared, processors(ManifestProcessors.Recording.class));

        assertEquals(new TreeSet<>(FIXED), new TreeSet<>(ManifestProcessors.Recording.claimed()));
    }

    /**
     * A graph is declared with the vocabulary a generation emitted, so a build that names no manifest has run
     * no generation and has nothing to declare one with. The fixed set is still claimed, so the marker it did
     * write is met with SG602 rather than with an annotation nobody owns.
     */
    @Test
    void reportsACompilationThatNamesNoManifestAtAll() {
        Compilation.Result result = Compilation.compileRaw(UNGENERATED, List.of());

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError("SG602"), result.describe());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG602]")
                && error.contains(ManifestRegistry.OPTION)), result::describe);
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());

        ManifestProcessors.Recording.reset();
        ManifestProcessors.Other.reset();
        Compilation.compileRaw(UNGENERATED, processors(ManifestProcessors.Recording.class));

        assertEquals(new TreeSet<>(FIXED), new TreeSet<>(ManifestProcessors.Recording.claimed()));
    }

    @Test
    void reportsAManifestTheOptionNamesButNothingWrote() {
        Compilation.Result result = Compilation.compileRaw(TypedFixtures.minimal(),
                List.of("-A" + ManifestRegistry.OPTION + '=' + absentManifest()));

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError("SG602"), result.describe());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG602]")
                && error.contains("annotation-manifest.json") && error.contains("does not exist")),
                result::describe);
    }

    @Test
    void reportsAManifestWrittenByAnotherToolVersion() {
        Compilation.Prepared prepared = Compilation.prepare(TypedFixtures.minimal());
        rewrite(prepared, manifest -> new CompileManifest(manifest.schemaVersion(), "0.0.0-another-tool",
                manifest.local(), manifest.dependencies(), manifest.processorInventory(),
                manifest.ownedSources()));

        assertSingleManifestError(Compilation.compileWithoutLifecycle(prepared, List.of()),
                "0.0.0-another-tool");
    }

    @Test
    void reportsAManifestWithASchemaItDoesNotRead() {
        Compilation.Prepared prepared = Compilation.prepare(TypedFixtures.minimal());
        rewrite(prepared, manifest -> new CompileManifest(9, manifest.toolVersion(), manifest.local(),
                manifest.dependencies(), manifest.processorInventory(), manifest.ownedSources()));

        assertSingleManifestError(Compilation.compileWithoutLifecycle(prepared, List.of()), "schemaVersion 9");
    }

    @Test
    void reportsAnAnnotationTypeTheCompilationDoesNotDeclare() {
        Compilation.Prepared prepared = Compilation.prepare(TypedFixtures.minimal());
        rewrite(prepared, manifest -> annotationTypes(manifest, added(manifest, "fx.DocsGraph.Nope")));

        assertSingleManifestError(Compilation.compileWithoutLifecycle(prepared, List.of()),
                "fx.DocsGraph.Nope");
    }

    @Test
    void reportsADuplicatedAnnotationType() {
        Compilation.Prepared prepared = Compilation.prepare(TypedFixtures.minimal());
        rewrite(prepared, manifest -> annotationTypes(manifest, added(manifest, "fx.DocsGraph.Transition")));

        assertSingleManifestError(Compilation.compileWithoutLifecycle(prepared, List.of()),
                "fx.DocsGraph.Transition twice");
    }

    @Test
    void reportsAMemberWhoseSignatureChangedAfterGeneration() {
        Compilation.Prepared prepared = Compilation.prepare(TypedFixtures.minimal());
        rewriteOriginal(prepared, "fx.DocActions", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                public final class DocActions {
                    @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus, DocTransition> \
                context, String note) {
                        return ActionOutcome.ok();
                    }
                }
                """);

        assertSingleManifestError(Compilation.compilePrepared(prepared), "fx.DocActions.publish");
    }

    @Test
    void refusesAChangedParameterTypeBeforePublishingAnUnusedExport() {
        String original = """
                package fx;
                public final class Hooks {
                    @io.github.graphmaquina.annotation.GraphHook
                    public void notify(java.lang.Object value) {}
                }
                """;
        Compilation.Prepared prepared = Compilation.prepare(Map.of("fx.Hooks", original));
        rewriteOriginal(prepared, "fx.Hooks", original.replace("java.lang.Object", "java.lang.String"));
        assertSingleManifestError(Compilation.compilePrepared(prepared), "fx.Hooks.notify");
        assertFalse(Files.exists(prepared.request().classesOutput().resolve("META-INF/graphmaquina/exports.json")));
    }

    @Test
    void refusesAChangedStaticModifierBeforePublishingAnUnusedExport() {
        String original = """
                package fx;
                public final class Hooks {
                    @io.github.graphmaquina.annotation.GraphHook
                    public void notify(java.lang.Object value) {}
                }
                """;
        Compilation.Prepared prepared = Compilation.prepare(Map.of("fx.Hooks", original));
        rewriteOriginal(prepared, "fx.Hooks", original.replace("public void", "public static void"));
        assertSingleManifestError(Compilation.compilePrepared(prepared), "fx.Hooks.notify");
    }

    @Test
    void acceptsUnchangedSignaturesWithAParameterizedEnclosingType() {
        Compilation.Result result = Compilation.compileTyped(Map.of(
                "fx.Outer", "package fx; public class Outer<T> { public class Inner {} }",
                "fx.Hooks", """
                    package fx;
                    public final class Hooks {
                        @io.github.graphmaquina.annotation.GraphHook
                        public void notify(Outer<String>.Inner value) {}
                    }
                    """));
        assertTrue(result.success(), result.describe());
    }

    @Test
    void acceptsUnchangedSignaturesWithAnImplicitEnclosingType() {
        Compilation.Result result = Compilation.compileTyped(Map.of("fx.Outer", """
                package fx;
                public class Outer<T> {
                    public class Inner {}
                    @io.github.graphmaquina.annotation.GraphHook
                    public void notify(Inner value) {}
                }
                """));
        assertTrue(result.success(), result.describe());
    }

    @Test
    void acceptsUnchangedSignaturesWithAnInheritedEnclosingType() {
        Compilation.Result result = Compilation.compileTyped(Map.of(
                "fx.Base", "package fx; public class Base<T> { public class Inner {} }",
                "fx.Sub", "package fx; public class Sub<A, B> extends Base<B> {}",
                "fx.Hooks", """
                    package fx;
                    public final class Hooks {
                        @io.github.graphmaquina.annotation.GraphHook
                        public void notify(Sub<Integer, String>.Inner value) {}
                    }
                    """));
        assertTrue(result.success(), result.describe());
    }

    @Test
    void acceptsUnchangedSignaturesWithAnImplicitInheritedEnclosingType() {
        Compilation.Result result = Compilation.compileTyped(Map.of(
                "fx.Base", "package fx; public class Base<T> { public class Inner {} }",
                "fx.Sub", """
                    package fx;
                    public class Sub<A, B> extends Base<B> {
                        @io.github.graphmaquina.annotation.GraphHook
                        public void notify(Inner value) {}
                    }
                    """));
        assertTrue(result.success(), result.describe());
    }

    @Test
    void reportsAnEnumWhoseConstantsChangedAfterGeneration() {
        Compilation.Prepared prepared = Compilation.prepare(TypedFixtures.minimal());
        rewriteOriginal(prepared, "fx.DocStatus", """
                package fx;
                public enum DocStatus { DRAFT, PUBLISHED, ARCHIVED }
                """);

        assertSingleManifestError(Compilation.compilePrepared(prepared), "fx.DocStatus");
    }

    @Test
    void reportsATransitionEnumWhoseConstantsChangedAfterGeneration() {
        Compilation.Prepared prepared = Compilation.prepare(TypedFixtures.minimal());
        rewriteOriginal(prepared, "fx.DocTransition", """
                package fx;
                public enum DocTransition { PUBLISH, ARCHIVE }
                """);

        assertSingleManifestError(Compilation.compilePrepared(prepared), "fx.DocTransition");
    }

    /** Exactly one SG602, naming the manifest and the claim that no longer holds. */
    private static void assertSingleManifestError(Compilation.Result result, String cause) {
        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError("SG602"), result.describe());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG602]")
                && error.contains("annotation-manifest.json") && error.contains(cause)), result::describe);
    }

    /** Prepares {@code sources} and compiles them with the given test processors instead of the real one. */
    private static Compilation.Result record(Map<String, String> sources, Class<?>... processors) {
        ManifestProcessors.Recording.reset();
        ManifestProcessors.Other.reset();
        return Compilation.compileWithoutLifecycle(Compilation.prepare(sources), processors(processors));
    }

    private static List<String> processors(Class<?>... processors) {
        List<String> names = new ArrayList<>();
        for (Class<?> processor : processors) {
            names.add(processor.getName());
        }
        return List.of("-processor", String.join(",", names));
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> all = new TreeSet<>(first);
        all.addAll(second);
        return all;
    }

    /** The annotation types of the local index with {@code type} appended, duplicates kept. */
    private static List<String> added(CompileManifest manifest, String type) {
        List<String> types = new ArrayList<>(manifest.local().annotationTypes());
        types.add(type);
        return types;
    }

    private static CompileManifest annotationTypes(CompileManifest manifest, List<String> types) {
        ExportIndex local = manifest.local();
        return new CompileManifest(manifest.schemaVersion(), manifest.toolVersion(),
                new ExportIndex(local.schemaVersion(), local.toolVersion(), local.compilationId(),
                        local.model(), types),
                manifest.dependencies(), manifest.processorInventory(), manifest.ownedSources());
    }

    private static void rewrite(Compilation.Prepared prepared, UnaryOperator<CompileManifest> change) {
        Path file = prepared.generation().annotationManifest();
        try {
            ManifestCodec.writeCompile(file, change.apply(ManifestCodec.readCompile(file)));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** A manifest path under a directory that exists, so only the file itself is missing. */
    private static Path absentManifest() {
        return Compilation.temporaryDirectory().resolve("annotation-manifest.json");
    }

    /** Replaces one original after the generator has already read it, as an edit between the two stages does. */
    private static void rewriteOriginal(Compilation.Prepared prepared, String qualifiedName, String source) {
        Path file = prepared.request().sources().get(0)
                .resolve(qualifiedName.replace('.', File.separatorChar) + ".java");
        try {
            Files.writeString(file, source, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
