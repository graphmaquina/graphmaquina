package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Three compilations of the same project, in the same directories, as a developer runs them: the full graph,
 * a rename of a method, a property and an enum constant, and the deletion of the last export.
 *
 * <p>javac never removes a class file it no longer produces, so what proves the cycle correct is what is
 * <em>absent</em> afterwards: the binaries of the previous names, the vocabulary of a machine that no longer
 * exists, and the carrier, which belongs to the compilation and never to the jar. The published index follows
 * the declarations down to the empty one, and the inventory that made the cleanup possible is sealed with no
 * pending file left behind.</p>
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

    @Test
    void upgradesInventoriedUnderscoreRuntimesWithoutDeletingHandwrittenNeighbors() throws IOException {
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
        byte[] manualBefore = Files.readAllBytes(manualBinary);
        ManifestCodec.writeOwned(request.workDirectory().resolve("owned-outputs.json"), new OwnedOutputs(1,
                List.of("fx/Docs_.java", "fx/Library_.java"),
                List.of("fx/Docs_.class", "fx/Library_.class", "fx/Library_$Builder.class"),
                List.of(), List.of()));

        Compilation.Result upgraded = Compilation.compilePrepared(prepared);

        assertTrue(upgraded.success(), upgraded.describe());
        assertTrue(upgraded.hasGenerated("fx.DocsRuntime"), upgraded.describe());
        assertTrue(upgraded.hasGenerated("fx.LibraryRuntime"), upgraded.describe());
        assertFalse(Files.exists(oldMachine));
        assertFalse(Files.exists(oldSet));
        assertFalse(exists(request, "fx/Docs_.class"));
        assertFalse(exists(request, "fx/Library_.class"));
        assertFalse(exists(request, "fx/Library_$Builder.class"));
        assertTrue(Files.exists(manual));
        assertArrayEquals(manualBefore, Files.readAllBytes(manualBinary));
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

    /** The fixture of {@link TypedFixtures#minimal()} with its action, its property and one state renamed. */
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
                    @GraphAction public ActionOutcome release(TransitionContext<Doc, DocStatus, DocTransition> context) {
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
