package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
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
import java.util.function.UnaryOperator;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What one compilation publishes about its graphs, and what the next one reads back from it.
 *
 * <p>The processor writes a provisional index of the local exports only, next to the classes it belongs to, and
 * only when nothing failed: the build promotes it to the published name after javac succeeds, so a dependent
 * never binds to declarations that never compiled. A dependent reads that published index from the classpath,
 * claims the annotation types it lists and links against the members it describes, and publishes an index of
 * its own that names none of them.</p>
 */
class PublishedGraphsTest {

    private static final String PENDING = "META-INF/graphmaquina/pending-exports.json";
    private static final String PUBLISHED = "META-INF/graphmaquina/exports.json";

    @TempDir
    Path directory;

    /**
     * A facade javac compiles implicitly, from the source path, is never processed, so the index this
     * compilation would publish names a machine it never verified. That is reported where it is detected, rather
     * than by publishing nothing and leaving the build's completion step to fail without saying why.
     */
    @Test
    void reportsAMachineTheIndexNamesButThisCompilationNeverProcessed() {
        Map<String, String> sources = new LinkedHashMap<>(TypedFixtures.minimal());
        sources.put("fx.Client", """
                package fx;
                public final class Client {
                    public static String describe(Docs docs) { return String.valueOf(docs); }
                }
                """);
        Compilation.Prepared prepared = Compilation.prepare(sources);

        Compilation.Result result = Compilation.compileWithImplicitSources(prepared, List.of("fx.Client"));

        assertEquals(1, result.countError("SG602"), result.describe());
        assertTrue(result.errors().get(0).contains("fx.Docs") && result.errors().get(0).contains("implicitly"),
                result.describe());
        assertFalse(Files.exists(prepared.request().classesOutput().resolve(PENDING)), result.describe());
    }

    @Test
    void publishesTheLocalIndexAndTheInventoryOfWhatItGenerated() throws IOException {
        Compilation.Prepared prepared = Compilation.prepare(TypedFixtures.watching());
        Compilation.Result result = Compilation.compilePrepared(prepared);
        assertTrue(result.success(), result.describe());

        Path classes = prepared.request().classesOutput();
        assertTrue(Files.isRegularFile(classes.resolve(PUBLISHED)), result::describe);
        assertFalse(Files.exists(classes.resolve(PENDING)), result::describe);

        CompileManifest manifest = ManifestCodec.readCompile(prepared.generation().annotationManifest());
        ExportIndex index = ManifestCodec.readExports(classes.resolve(PUBLISHED));
        assertEquals("fx:test:main", index.compilationId());
        assertEquals(List.of("fx.Docs", "fx.Pages"), facades(index));
        assertEquals(facades(manifest.local()), facades(index));
        assertEquals(symbols(manifest.local()), symbols(index));
        assertEquals(manifest.local().annotationTypes(), index.annotationTypes());

        assertEquals("""
                {
                  "schemaVersion": 2,
                  "generatedTypes": [
                    "fx.DocsRuntime",
                    "fx.LibraryRuntime",
                    "fx.PagesRuntime"
                  ]
                }
                """, Files.readString(Path.of(manifest.processorInventory()), StandardCharsets.UTF_8));
    }

    @Test
    void publishesNothingWhenADeclarationFailed() throws IOException {
        Compilation.Prepared prepared = Compilation.prepare(TypedFixtures.watching(TypedFixtures.PEERS, """
                @DocsPages.Watch(state = REVIEW, peers = DocRefs.Pages.class, owner = PageRefs.Doc.class,
                    rules = {})
                """));
        Compilation.Result result = Compilation.compilePrepared(prepared);

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG404"), result.describe());
        assertFalse(Files.exists(prepared.request().classesOutput().resolve(PENDING)), result::describe);
        assertFalse(Files.exists(prepared.request().classesOutput().resolve(PUBLISHED)), result::describe);
        assertFalse(Files.exists(Path.of(ManifestCodec.readCompile(prepared.generation().annotationManifest())
                .processorInventory())), result::describe);
    }

    @Test
    void linksAgainstThePublishedIndexOfAProducerAndPublishesOnlyItsOwn() throws IOException {
        Path jar = publish(producer(), directory.resolve("producer.jar"), UnaryOperator.identity());
        ManifestProcessors.Recording.reset();

        Compilation.Prepared prepared = Compilation.prepare(consumer(), List.of(jar));
        Compilation.Result result = Compilation.compilePrepared(prepared, List.of("-processor",
                StateGraphProcessor.class.getName() + ',' + ManifestProcessors.Recording.class.getName()));

        assertTrue(result.success(), result.describe());
        assertTrue(result.hasGenerated("cons.ArchivesRuntime"), result::describe);
        assertTrue(result.hasGenerated("cons.ShelfRuntime"), result::describe);
        assertTrue(ManifestProcessors.Recording.claimed().containsAll(
                List.of("prod.BillsGraph.Definition", "prod.BillsGraph.Transition", "cons.ArchivesGraph.Definition")),
                () -> ManifestProcessors.Recording.claimed().toString());

        ExportIndex index = ManifestCodec.readExports(prepared.request().classesOutput().resolve(PUBLISHED));
        assertEquals(List.of("cons.Archives"), facades(index));
        assertEquals(List.of(), symbols(index));
        assertTrue(index.annotationTypes().stream().allMatch(name -> name.startsWith("cons.")),
                () -> index.annotationTypes().toString());
    }

    /**
     * A published machine whose facade also extends bases that redeclare methods of Machine, and a published set
     * whose base redeclares close(), are what the producer's companions implement: the producer publishes them,
     * and a consumer's set holds that machine, its model built from the classpath alone.
     */
    @Test
    void aConsumerSetHoldsAPublishedMachineWhoseBasesRedeclareWhatItsCompanionImplements() throws IOException {
        Map<String, String> producer = producer();
        producer.put("prod.Reevaluable", """
                package prod;
                import io.github.graphmaquina.api.Outcome;
                public interface Reevaluable<E> {
                    Outcome<E, ?, ?> reevaluate(E entity);
                }
                """);
        producer.put("prod.AsyncCapable", """
                package prod;
                import java.util.concurrent.CompletableFuture;
                import java.util.function.Supplier;
                public interface AsyncCapable {
                    <R> CompletableFuture<R> async(Supplier<R> call);
                }
                """);
        producer.put("prod.Lifecycle", """
                package prod;
                public interface Lifecycle {
                    void close();
                }
                """);
        producer.put("prod.Bills", producer.get("prod.Bills").replace(
                "public interface Bills extends Machine<Bill, BillStatus, BillTransition> {",
                "public interface Bills extends Machine<Bill, BillStatus, BillTransition>, Reevaluable<Bill>, AsyncCapable {"));
        producer.put("prod.Ledger", producer.get("prod.Ledger").replace(
                "public interface Ledger extends Machines {", "public interface Ledger extends Machines, Lifecycle {"));
        Path jar = publish(producer, directory.resolve("redeclaring.jar"), UnaryOperator.identity());

        Compilation.Result result = Compilation.compilePrepared(Compilation.prepare(consumer(), List.of(jar)));

        assertTrue(result.success(), result.describe());
        assertTrue(result.hasGenerated("cons.ShelfRuntime"), result::describe);
    }

    @Test
    void refusesAProducerWhosePublishedIndexCannotBeRead() throws IOException {
        Path jar = publish(producer(), directory.resolve("corrupt.jar"),
                (String index) -> index.replace("\"compilationId\"", "\"compilationIdentifier\""));

        CodegenException failure = assertThrows(CodegenException.class,
                () -> Compilation.prepare(consumer(), List.of(jar)));

        assertEquals("SG602", failure.code());
        assertTrue(failure.getMessage().contains("compilationId"), failure.getMessage());
    }

    /** A producer compiled on its own, packaged with the index its completion step published. */
    private static Path publish(Map<String, String> sources, Path jar, UnaryOperator<String> index)
            throws IOException {
        Compilation.Prepared prepared = Compilation.prepare(sources);
        Compilation.Result result = Compilation.compilePrepared(prepared);
        assertTrue(result.success(), result.describe());
        Path classes = prepared.request().classesOutput();
        String published = Files.readString(classes.resolve(PUBLISHED), StandardCharsets.UTF_8);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Path file : files(classes)) {
                String name = classes.relativize(file).toString().replace(File.separatorChar, '/');
                // The completion step already removed the carrier, whose binaries belong to no jar.
                assertFalse(name.startsWith("io/github/graphmaquina/generated/bootstrap/"), name);
                if (name.equals(PUBLISHED)) {
                    continue;
                }
                out.putNextEntry(new JarEntry(name));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry(PUBLISHED));
            out.write(index.apply(published).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    /** A machine, its entity and two exported actions, in a package of their own. */
    private static Map<String, String> producer() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("prod.BillStatus", """
                package prod;
                public enum BillStatus { OPEN, PAID }
                """);
        sources.put("prod.BillTransition", """
                package prod;
                public enum BillTransition { SETTLE, CANCEL }
                """);
        sources.put("prod.Bill", """
                package prod;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Bill {
                    private final String id;
                    @GraphProperty private BillStatus status = BillStatus.OPEN;
                    public Bill(String id) { this.id = id; }
                    @GraphProperty public String id() { return id; }
                    public BillStatus status() { return status; }
                    public void status(BillStatus value) { status = value; }
                }
                """);
        sources.put("prod.BillActions", """
                package prod;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                public final class BillActions {
                    @GraphAction public ActionOutcome settle(TransitionContext<Bill, BillStatus, BillTransition> context) {
                        return ActionOutcome.ok();
                    }
                    @GraphAction public ActionOutcome cancel(TransitionContext<Bill, BillStatus, BillTransition> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        sources.put("prod.Bills", """
                package prod;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static prod.BillStatus.*;
                import static prod.BillTransition.*;
                @GenerateGraphApi
                @BillsGraph.Definition(initial = OPEN, terminals = PAID,
                    state = BillRefs.Status.class, id = BillRefs.Id.class)
                @BillsGraph.StatePolicy(state = OPEN, otherwise = "a bill is settled")
                public interface Bills extends Machine<Bill, BillStatus, BillTransition> {
                    @BillsGraph.Transition(id = SETTLE, from = OPEN, to = PAID, action = BillActionsRefs.Settle.class)
                    Outcome<Bill, BillStatus, BillTransition> settle(Bill bill);
                }
                """);
        sources.put("prod.Ledger", """
                package prod;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs
                public interface Ledger extends Machines {
                    Bills bills();
                }
                """);
        return sources;
    }

    /** A machine bound to a producer's action and a set holding a producer's machine, with no source of it. */
    private static Map<String, String> consumer() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("cons.Archives", """
                package cons;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import prod.Bill;
                import prod.BillActionsRefs;
                import prod.BillRefs;
                import prod.BillStatus;
                import prod.BillTransition;
                import static prod.BillStatus.*;
                import static prod.BillTransition.*;
                @GenerateGraphApi
                @ArchivesGraph.Definition(initial = OPEN, terminals = PAID,
                    state = BillRefs.Status.class, id = BillRefs.Id.class)
                @ArchivesGraph.StatePolicy(state = OPEN, otherwise = "an archived bill is cancelled")
                public interface Archives extends Machine<Bill, BillStatus, BillTransition> {
                    @ArchivesGraph.Transition(id = CANCEL, from = OPEN, to = PAID, action = BillActionsRefs.Cancel.class)
                    Outcome<Bill, BillStatus, BillTransition> cancel(Bill bill);
                }
                """);
        sources.put("cons.Shelf", """
                package cons;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                import prod.Bills;
                @StateGraphs
                public interface Shelf extends Machines {
                    Bills bills();
                }
                """);
        return sources;
    }

    private static List<String> facades(ExportIndex index) {
        List<String> facades = new ArrayList<>();
        for (GraphDescriptor graph : index.model().graphs()) {
            facades.add(graph.facade());
        }
        return facades;
    }

    private static List<String> symbols(ExportIndex index) {
        List<String> symbols = new ArrayList<>();
        for (SymbolDescriptor symbol : index.model().symbols()) {
            symbols.add(symbol.qualifiedName());
        }
        return symbols;
    }

    /** Every file under {@code root}, in a stable order. */
    private static List<Path> files(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
