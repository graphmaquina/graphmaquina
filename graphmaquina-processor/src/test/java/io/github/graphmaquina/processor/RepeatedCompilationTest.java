package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import io.github.graphmaquina.codegen.manifest.OwnedOutputs;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import io.github.graphmaquina.codegen.output.CompilationOutputs;
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
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compilations of the same project, in the same directories, as a developer runs them, and what each leaves
 * behind. The first test runs three: the full graph, a rename of a method, a property and two enum constants, and
 * the deletion of the last export.
 *
 * <p>javac never removes a class file it no longer produces, so what proves that cycle correct is what is
 * <em>absent</em> afterwards: the binaries of the previous names, the vocabulary of a machine that no longer
 * exists, and the carrier, which belongs to the compilation and never to the jar. The published index follows
 * the declarations down to the empty one, and the inventory that made the cleanup possible is sealed with no
 * pending file left behind.</p>
 *
 * <p>The other tests rerun javac over the same directories without the build's lifecycle, give its
 * {@code prepare} step an inventory of a schema it does not read, and compile a half-finished rename that javac
 * rejects.</p>
 */
class RepeatedCompilationTest {

    private static final String PUBLISHED = "META-INF/graphmaquina/exports.json";
    private static final String PENDING = "META-INF/graphmaquina/pending-exports.json";
    private static final String CARRIER_PACKAGE = "io/github/graphmaquina/generated/bootstrap";

    @TempDir
    Path project;

    @Test
    void retiresTheBinariesOfEveryDeclarationTheNextCompilationNoLongerHas() throws IOException {
        Compilation.Prepared first = Compilation.prepare(project, TypedFixtures.minimal());
        Compilation.Result initial = Compilation.compilePrepared(first);
        assertTrue(initial.success(), initial.describe());
        GenerationRequest request = first.request();
        assertTrue(exists(request, "fx/DocRefs$Id.class"));
        assertTrue(exists(request, "fx/DocActionsRefs$Publish.class"));
        assertTrue(exists(request, "fx/DocsGraph.class"));
        assertTrue(exists(request, "fx/DocsRuntime.class"));
        assertTrue(symbols(request).contains("fx.DocRefs.Id"), () -> symbols(request).toString());

        Compilation.Result renamed = Compilation.compilePrepared(Compilation.prepare(project, renamed()));
        assertTrue(renamed.success(), renamed.describe());
        assertFalse(exists(request, "fx/DocRefs$Id.class"), "the retired property symbol survived");
        assertFalse(exists(request, "fx/DocActionsRefs$Publish.class"), "the retired action symbol survived");
        assertTrue(exists(request, "fx/DocRefs$Code.class"));
        assertTrue(exists(request, "fx/DocActionsRefs$Release.class"));
        assertEquals(List.of("fx.DocActionsRefs.Release", "fx.DocRefs.Code", "fx.DocRefs.Status"),
                symbols(request));

        Compilation.Result emptied = Compilation.compilePrepared(Compilation.prepare(project, unrelated()));
        assertTrue(emptied.success(), emptied.describe());
        assertNothingLeftOfTheGraph(request);
    }

    /**
     * A rerun of javac over the same directories, without the lifecycle that retires the previous outputs, finds
     * the companions the previous run generated already compiled. They are this processor's own outputs, which the
     * previous inventory lists, so they are reported as stale outputs to retire, not as hand-written types that
     * collide with the generated names.
     */
    @Test
    void reportsItsOwnPreviousCompanionsAsStaleOutputRatherThanAsCollisions() {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx.Library", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs public interface Library extends Machines { Docs docs(); }
                """);
        Compilation.Prepared prepared = Compilation.prepare(project, sources);
        Compilation.Result first = Compilation.compilePrepared(prepared);
        assertTrue(first.success(), first.describe());

        Compilation.Result rerun = Compilation.recompileWithoutLifecycle(prepared);

        assertFalse(rerun.success(), rerun.describe());
        assertEquals(0, rerun.countError("SG603"), rerun.describe());
        assertEquals(2, rerun.countError("SG602"), rerun.describe());
        assertTrue(rerun.errors().stream().allMatch((String error) -> error.contains("previous compilation")
                && error.contains("prepare-compile")), rerun.describe());
    }

    /**
     * A rerun of javac without the lifecycle, after the action host stopped exporting, finds the previous action
     * symbol in the class output, which no export index sits beside. That output is this compilation's own, never a
     * dependency whose index went missing, so the symbol is refused as one this compilation does not export.
     */
    @Test
    void refusesASymbolLeftInItsOwnClassOutputAsUnknownRatherThanAsAMissingIndex() throws IOException {
        Compilation.Prepared first = Compilation.prepare(project, TypedFixtures.minimal());
        Compilation.Result initial = Compilation.compilePrepared(first);
        assertTrue(initial.success(), initial.describe());
        Files.delete(first.request().classesOutput().resolve(PUBLISHED));
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx.DocActions", sources.get("fx.DocActions").replace("@GraphAction public", "public"));

        Compilation.Result rerun = Compilation.recompileWithoutLifecycle(Compilation.prepare(project, sources));

        assertFalse(rerun.success(), rerun.describe());
        assertTrue(rerun.errors().contains("[SG605] fx.DocActionsRefs.Publish is not a symbol this compilation exports"
                + " or depends on; use one of the generated <Host>Refs types"), rerun.describe());
        assertFalse(rerun.errors().stream().anyMatch((String error) -> error.contains("export index")),
                rerun.describe());
    }

    @Test
    void refusesAnInventoryOfAnotherSchemaAndLeavesCleanupToTheUser() throws IOException {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx.Library", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs public interface Library extends Machines { Docs docs(); }
                """);
        Compilation.Prepared prepared = Compilation.prepare(project, sources);
        GenerationRequest request = prepared.request();
        Path output = request.processorSourcesOutput().resolve("fx");
        Files.createDirectories(output);
        Path oldMachine = Files.writeString(output.resolve("Docs_.java"),
                "package fx; public final class Docs_ {}");
        Path oldSet = Files.writeString(output.resolve("Library_.java"),
                "package fx; public final class Library_ { public static final class Builder {} }");
        Path manual = Files.writeString(output.resolve("Docs_Helper.java"),
                "package fx; public final class Docs_Helper {}");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "--release", "21", "-d", request.classesOutput().toString(),
                oldMachine.toString(), oldSet.toString(), manual.toString()));
        Path manualBinary = request.classesOutput().resolve("fx/Docs_Helper.class");
        ManifestCodec.writeOwned(request.workDirectory().resolve("owned-outputs.json"), new OwnedOutputs(99,
                List.of("fx/Docs_.java", "fx/Library_.java"),
                List.of("fx/Docs_.class", "fx/Library_.class", "fx/Library_$Builder.class"),
                List.of(), List.of()));

        Map<Path, byte[]> before = new LinkedHashMap<>();
        for (Path file : List.of(oldMachine, oldSet, manual, manualBinary,
                request.classesOutput().resolve("fx/Docs_.class"),
                request.classesOutput().resolve("fx/Library_.class"),
                request.classesOutput().resolve("fx/Library_$Builder.class"),
                request.workDirectory().resolve("owned-outputs.json"))) {
            before.put(file, Files.readAllBytes(file));
        }

        CodegenException failure = assertThrows(CodegenException.class, () -> Compilation.compilePrepared(prepared));

        assertEquals("SG602", failure.code());
        assertEquals("unsupported schemaVersion 99 in " + request.workDirectory().resolve("owned-outputs.json")
                + "; expected 2", failure.getMessage());
        for (Map.Entry<Path, byte[]> entry : before.entrySet()) {
            assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()), entry.getKey().toString());
        }
        assertFalse(Files.exists(request.processorSourcesOutput().resolve("fx/DocsRuntime.java")));
        assertFalse(Files.exists(request.processorSourcesOutput().resolve("fx/LibraryRuntime.java")));
        assertFalse(Files.exists(request.workDirectory().resolve("owned-outputs.pending.json")));
    }

    @Test
    void publishesNothingWhenTheRenameLeavesTheOldUsesBehindAndClearsTheStaleIndexNext() throws IOException {
        Compilation.Prepared first = Compilation.prepare(project, TypedFixtures.minimal());
        assertTrue(Compilation.compilePrepared(first).success());
        GenerationRequest request = first.request();

        Compilation.Result stale = Compilation.compilePrepared(Compilation.prepare(project, keptOldUses()));

        assertFalse(stale.success(), stale.describe());
        assertFalse(Files.exists(request.classesOutput().resolve(PUBLISHED)), stale::describe);

        // A compilation javac rejected may still have left a provisional index behind; the next prepare, which
        // is what a build runs before javac, removes it, so nothing can ever promote it afterwards.

        Path pending = request.classesOutput().resolve(PENDING);
        Files.createDirectories(pending.getParent());
        Files.writeString(pending, "stale", StandardCharsets.UTF_8);
        CompilationOutputs.prepare(request);
        assertFalse(Files.exists(pending));
        assertFalse(Files.exists(request.classesOutput().resolve(PUBLISHED)));
    }

    /**
     * The fixture of {@link TypedFixtures#minimal()} with its action, its property, one state and its identifier
     * renamed.
     */
    private static Map<String, String> renamed() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", """
                package fx;
                public enum DocStatus { DRAFT, LIVE }
                """);
        sources.put("fx.DocTransition", """
                package fx;
                public enum DocTransition { RELEASE }
                """);
        sources.put("fx.Doc", """
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Doc {
                    @GraphProperty private DocStatus status = DocStatus.DRAFT;
                    @GraphProperty public String code() { return "doc"; }
                    public DocStatus status() { return status; }
                    public void status(DocStatus value) { status = value; }
                }
                """);
        sources.put("fx.DocActions", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                public final class DocActions {
                    @GraphAction public ActionOutcome release(TransitionContext<Doc, DocStatus, DocTransition> \
                context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        sources.put("fx.Docs", """
                package fx;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import static fx.DocStatus.*;
                import static fx.DocTransition.*;
                @GenerateGraphApi
                @DocsGraph.Definition(initial = DRAFT, terminals = LIVE,
                    state = DocRefs.Status.class, id = DocRefs.Code.class)
                @DocsGraph.StatePolicy(state = DRAFT, otherwise = "release before finishing")
                public interface Docs extends Machine<Doc, DocStatus, DocTransition> {
                    @DocsGraph.Transition(id = RELEASE, from = DRAFT, to = LIVE, action = DocActionsRefs.Release.class)
                    Outcome<Doc, DocStatus, DocTransition> release(Doc doc);
                }
                """);
        return sources;
    }

    /** The same rename with the facade left on the old names, which is what a half-finished edit looks like. */
    private static Map<String, String> keptOldUses() {
        Map<String, String> sources = new LinkedHashMap<>(renamed());
        sources.put("fx.Docs", TypedFixtures.minimal().get("fx.Docs"));
        return sources;
    }

    /** A project that declares no graph at all, which is the last export being deleted. */
    private static Map<String, String> unrelated() {
        return Map.of("fx.Unrelated", """
                package fx;
                public final class Unrelated {
                    public String name() { return "unrelated"; }
                }
                """);
    }

    /** The published index, the class files and the inventory once the last export is gone. */
    private static void assertNothingLeftOfTheGraph(GenerationRequest request) throws IOException {
        ExportIndex index = ManifestCodec.readExports(request.classesOutput().resolve(PUBLISHED));
        assertEquals(List.of(), graphs(index));
        assertEquals(List.of(), index.model().symbols().stream().map(SymbolDescriptor::qualifiedName).toList());
        assertEquals(List.of(), index.annotationTypes());
        assertEquals(List.of(), classFiles(request, "fx/DocRefs"));
        assertEquals(List.of(), classFiles(request, "fx/DocActionsRefs"));
        assertEquals(List.of(), classFiles(request, "fx/DocsGraph"));
        assertEquals(List.of(), classFiles(request, "fx/DocsRuntime"));
        assertEquals(List.of(), classFiles(request, CARRIER_PACKAGE));
        assertTrue(exists(request, "fx/Unrelated.class"));

        OwnedOutputs owned = ManifestCodec.readOwned(request.workDirectory().resolve("owned-outputs.json"));
        assertEquals(List.of(), owned.sources());
        assertEquals(List.of(), owned.binaries());
        assertEquals(List.of(PUBLISHED), owned.resources());
        assertEquals(1, owned.excludedBinaries().size(), owned.excludedBinaries().toString());
        assertFalse(Files.exists(request.workDirectory().resolve("owned-outputs.pending.json")));
    }

    private static boolean exists(GenerationRequest request, String relative) {
        return Files.isRegularFile(request.classesOutput().resolve(relative));
    }

    /** Every class file under the class output whose path starts with {@code prefix}, for an absence check. */
    private static List<String> classFiles(GenerationRequest request, String prefix) throws IOException {
        Path classes = request.classesOutput();
        List<String> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                String name = classes.relativize(file).toString().replace(File.separatorChar, '/');
                if (name.startsWith(prefix) && name.endsWith(".class")) {
                    found.add(name);
                }
            }
        }
        return found;
    }

    private static List<String> symbols(GenerationRequest request) {
        try {
            return ManifestCodec.readExports(request.classesOutput().resolve(PUBLISHED))
                    .model().symbols().stream().map(SymbolDescriptor::qualifiedName).sorted().toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static List<String> graphs(ExportIndex index) {
        return index.model().graphs().stream().map(GraphDescriptor::facade).toList();
    }
}
