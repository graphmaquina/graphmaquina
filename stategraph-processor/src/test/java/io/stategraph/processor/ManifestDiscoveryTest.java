package io.stategraph.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.stategraph.codegen.manifest.CompileManifest;
import io.stategraph.codegen.manifest.ExportIndex;
import io.stategraph.codegen.manifest.ManifestCodec;
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
            "io.stategraph.annotation.GenerateGraphApi",
            "io.stategraph.annotation.GraphPeers",
            "io.stategraph.annotation.GraphAction",
            "io.stategraph.annotation.GraphGuard",
            "io.stategraph.annotation.GraphHook",
            "io.stategraph.annotation.GraphCondition",
            "io.stategraph.annotation.GraphProperty",
            "io.stategraph.annotation.GraphBindings",
            "io.stategraph.annotation.Timeout",
            "io.stategraph.annotation.internal.GeneratedVocabulary",
            "io.stategraph.annotation.internal.GeneratedSymbol",
            "io.stategraph.annotation.internal.GraphCompilation",
            "io.stategraph.annotation.StateGraph",
            "io.stategraph.annotation.StateGraphs",
            "io.stategraph.annotation.Row",
            "io.stategraph.annotation.Rows",
            "io.stategraph.annotation.Action",
            "io.stategraph.annotation.Move",
            "io.stategraph.annotation.Cell",
            "io.stategraph.annotation.Cells",
            "io.stategraph.annotation.Forbid",
            "io.stategraph.annotation.Forbids",
            "io.stategraph.annotation.Watch",
            "io.stategraph.annotation.Watches",
            "io.stategraph.annotation.When",
            "javax.annotation.processing.Generated");

    /** The vocabulary the generator emits for the machine of {@link TypedFixtures#minimal()}. */
    private static final Set<String> GENERATED = Set.of(
            "fx.DocsGraph.Definition", "fx.DocsGraph.Row", "fx.DocsGraph.Rows", "fx.DocsGraph.Action",
            "fx.DocsGraph.Move", "fx.DocsGraph.Cell", "fx.DocsGraph.Cells", "fx.DocsGraph.Forbid",
            "fx.DocsGraph.Forbids");

    @Test
    void claimsTheFixedSetAndEveryAnnotationTheManifestLists() {
        Compilation.Result result = record(TypedFixtures.minimal(), ManifestProcessors.Recording.class);
        assertTrue(result.success(), result.describe());

        Set<String> claimed = ManifestProcessors.Recording.claimed();
        assertTrue(claimed.containsAll(List.of("fx.DocsGraph.Row", "fx.DocsGraph.Rows",
                "io.stategraph.annotation.internal.GraphCompilation",
                "javax.annotation.processing.Generated")), claimed::toString);
        assertFalse(claimed.stream().anyMatch(name -> name.contains("*")), claimed::toString);
        assertEquals(union(FIXED, GENERATED), new TreeSet<>(claimed));
    }

    @Test
    void readsRepeatedVocabularyAndItsTypedValuesFromTheMetadata() {
        Compilation.Result result = record(TypedFixtures.minimal(), ManifestProcessors.Recording.class);
        assertTrue(result.success(), result.describe());

        assertEquals(List.of("DRAFT", "PUBLISHED"), ManifestProcessors.Recording.rows());
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
     * Transition: a build that names no manifest has not been moved to the generator yet, so it keeps the
     * fixed set and its legacy machines. Task 13 turns this into SG602 with the legacy frontend it removes.
     */
    @Test
    void toleratesAnAbsentOptionWhileTheLegacyFrontendExists() {
        Compilation.Result legacy = Compilation.compileRaw(Fixtures.validDocs(), List.of());

        assertTrue(legacy.success(), legacy.describe());
        assertEquals(0, legacy.countError("SG602"), legacy.describe());
        assertTrue(legacy.hasGenerated("fx.Docs_"), legacy.describe());

        ManifestProcessors.Recording.reset();
        ManifestProcessors.Other.reset();
        Compilation.compileRaw(Fixtures.validDocs(), processors(ManifestProcessors.Recording.class));

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
        rewrite(prepared, manifest -> annotationTypes(manifest, added(manifest, "fx.DocsGraph.Row")));

        assertSingleManifestError(Compilation.compileWithoutLifecycle(prepared, List.of()),
                "fx.DocsGraph.Row twice");
    }

    @Test
    void reportsAMemberWhoseSignatureChangedAfterGeneration() {
        Compilation.Prepared prepared = Compilation.prepare(TypedFixtures.minimal());
        rewriteOriginal(prepared, "fx.DocActions", """
                package fx;
                import io.stategraph.annotation.GraphAction;
                import io.stategraph.api.ActionOutcome;
                import io.stategraph.api.TransitionContext;
                public final class DocActions {
                    @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus> context, String note) {
                        return ActionOutcome.ok();
                    }
                }
                """);

        assertSingleManifestError(Compilation.compilePrepared(prepared), "fx.DocActions.publish");
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

    /** The annotation types of the local index with {@code added} appended, duplicates kept. */
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
        try {
            return Files.createTempDirectory("stategraph-absent-").resolve("annotation-manifest.json");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
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
