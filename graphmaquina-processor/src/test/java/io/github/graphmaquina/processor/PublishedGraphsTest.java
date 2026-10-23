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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
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

    /**
     * The same machine, held by a set javac is handed: the set is processed, the machine it holds still is not, so
     * no companion is generated for the machine. The set is reported for it where it is declared, and its own
     * companion, which could only build the machine with that missing companion, is not generated either.
     */
    @Test
    void reportsASetHoldingAMachineThisCompilationNeverProcessed() {
        Map<String, String> sources = new LinkedHashMap<>(TypedFixtures.minimal());
        sources.put("fx.Library", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs
                public interface Library extends Machines {
                    Docs docs();
                }
                """);
        Compilation.Prepared prepared = Compilation.prepare(sources);

        Compilation.Result result = Compilation.compileWithImplicitSources(prepared, List.of("fx.Library"));

        assertEquals(List.of("[SG602] fx.Library holds fx.Docs, which this compilation never processed, so neither"
                + " gets a companion: javac does not process a source it compiles implicitly, from the source path,"
                + " so pass every source of the compilation to javac"), result.errors(), result.describe());
        assertEquals(5, result.lineOf("SG602"), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
        assertFalse(result.hasGenerated("fx.LibraryRuntime"), result.describe());
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
        assertTrue(index.annotationTypes().stream().allMatch((String name) -> name.startsWith("cons.")),
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
                "public interface Bills extends Machine<Bill, BillStatus, BillTransition>, Reevaluable<Bill>, "
                        + "AsyncCapable {"));
        producer.put("prod.Ledger", producer.get("prod.Ledger").replace(
                "public interface Ledger extends Machines {", "public interface Ledger extends Machines, Lifecycle {"));
        Path jar = publish(producer, directory.resolve("redeclaring.jar"), UnaryOperator.identity());

        Compilation.Result result = Compilation.compilePrepared(Compilation.prepare(consumer(), List.of(jar)));

        assertTrue(result.success(), result.describe());
        assertTrue(result.hasGenerated("cons.ShelfRuntime"), result::describe);
    }

    /**
     * A consumer's set holds a published parent and its published child. The model of the parent, built from the
     * classpath, reads its sub-machines as the producer's own compilation does, so the set builds the child's
     * definition once, hands it to the parent's published {@code graph(...)}, and runs the binding.
     */
    @Test
    void aConsumerSetBindsTheSubmachinesOfAPublishedParent() throws Exception {
        Path jar = publish(SubmachineGenerationTest.billing(), directory.resolve("billing.jar"),
                UnaryOperator.identity());
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("cons.Shop", """
                package cons;
                import fx.Bookings;
                import fx.Payments;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                /** Consumer set of the published binding fixture. */
                @StateGraphs
                public interface Shop extends Machines {
                    Bookings bookings();
                    Payments payments();
                }
                """);
        sources.put("cons.Scenario", """
                package cons;
                import fx.Booking;
                import fx.BookingLookups;
                import fx.Ledger;
                import fx.Payment;
                /** Executes the published binding fixture. */
                public final class Scenario {
                    public static Object run() {
                        Ledger ledger = new Ledger();
                        try (Shop shop = ShopRuntime.builder(ledger).build()) {
                            Payment deposit = new Payment("deposit");
                            Booking booking = new Booking("booking", deposit, new Payment("balance"));
                            BookingLookups.register(booking);
                            ledger.register(booking);
                            shop.bookings().book(booking);
                            shop.payments().capture(deposit);
                            return booking.status() + " " + deposit.status();
                        }
                    }
                }
                """);
        Compilation.Prepared prepared = Compilation.prepare(sources, List.of(jar));

        Compilation.Result result = Compilation.compilePrepared(prepared);

        assertTrue(result.success(), result.describe());
        assertTrue(result.generated("cons.ShopRuntime").contains(
                "this.bookingsBuilder = StateMachines.from(BookingsRuntime.graph(paymentsGraph, ledger))"),
                () -> result.generated("cons.ShopRuntime"));
        URL[] classes = {prepared.request().classesOutput().toUri().toURL(), jar.toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(classes, PublishedGraphsTest.class.getClassLoader())) {
            assertEquals("BALANCE CAPTURED", loader.loadClass("cons.Scenario").getMethod("run").invoke(null));
        }
    }

    /** The same set without the child is refused, as a set of the producer's own compilation would be. */
    @Test
    void aConsumerSetWithoutTheChildOfAPublishedParentIsRefused() throws IOException {
        Path jar = publish(SubmachineGenerationTest.billing(), directory.resolve("parent.jar"),
                UnaryOperator.identity());
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("cons.Shop", """
                package cons;
                import fx.Bookings;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                /** Consumer set of the published binding fixture. */
                @StateGraphs
                public interface Shop extends Machines {
                    Bookings bookings();
                }
                """);

        Compilation.Result result = Compilation.compilePrepared(Compilation.prepare(sources, List.of(jar)));

        String missing = "[SG806] the sub-machine of Bookings.%s is Payments, which is not in Shop";
        assertEquals(List.of(missing.formatted("DEPOSIT"), missing.formatted("BALANCE")), result.errors(),
                result.describe());
        assertFalse(result.hasGenerated("cons.ShopRuntime"), result.describe());
    }

    /**
     * A producer jar that lost its export index, as an assembly step that drops {@code META-INF/graphmaquina} leaves
     * it, is reported as the missing index it is, at every use the consumer makes of it: at each generated symbol a
     * machine binds and at the set that holds the producer's machine, which no index the consumer reads lists. None
     * of them is told to use another symbol or to apply a Definition the machine carries already.
     */
    @Test
    void reportsAProducerJarWithoutItsExportIndexWhereTheConsumerUsesIt() throws IOException {
        Path jar = publish(producer(), directory.resolve("producer.jar"), UnaryOperator.identity());
        try (FileSystem archive = FileSystems.newFileSystem(jar)) {
            Files.delete(archive.getPath(PUBLISHED));
        }

        Compilation.Result result = Compilation.compilePrepared(Compilation.prepare(consumer(), List.of(jar)));

        assertEquals(List.of(
                "[SG602] prod.BillRefs.Status" + unlisted(jar),
                "[SG602] prod.BillRefs.Id" + unlisted(jar),
                "[SG602] prod.BillActionsRefs.Cancel" + unlisted(jar),
                "[SG602] cons.Shelf holds prod.Bills, but prod.Bills" + unlisted(jar)), result.errors(),
                result.describe());
        assertTrue(result.lineOf("SG602") > 0, result.describe());
        assertFalse(result.hasGenerated("cons.ArchivesRuntime"), result.describe());
        assertFalse(result.hasGenerated("cons.ShelfRuntime"), result.describe());
    }

    /** The same producer compiled into a directory of classes, whose export index was deleted afterwards. */
    @Test
    void reportsAProducerDirectoryWithoutItsExportIndexWhereTheConsumerUsesIt() throws IOException {
        Compilation.Prepared producer = Compilation.prepare(producer());
        Compilation.Result produced = Compilation.compilePrepared(producer);
        assertTrue(produced.success(), produced.describe());
        Path classes = producer.request().classesOutput().toRealPath();
        Files.delete(classes.resolve(PUBLISHED));

        Compilation.Result result = Compilation.compilePrepared(Compilation.prepare(consumer(), List.of(classes)));

        assertEquals(List.of(
                "[SG602] prod.BillRefs.Status" + unlisted(classes),
                "[SG602] prod.BillRefs.Id" + unlisted(classes),
                "[SG602] prod.BillActionsRefs.Cancel" + unlisted(classes),
                "[SG602] cons.Shelf holds prod.Bills, but prod.Bills" + unlisted(classes)), result.errors(),
                result.describe());
    }

    /** What a diagnostic says after naming a type that {@code entry}, which lost its export index, carries. */
    private static String unlisted(Path entry) {
        return " comes from " + entry + ", which carries no export index " + PUBLISHED + ", so no index this"
                + " compilation reads lists it: package the index the producer's StateGraph build publishes with its"
                + " classes, or rebuild the producer through the complete lifecycle";
    }

    /**
     * A producer jar whose export index is an older build's, which lists neither the machine nor the action symbol
     * the classes beside it carry, is reported as that stale index wherever the consumer uses what it leaves out: at
     * the action a machine binds and at the set that holds the machine. The symbols the older index lists still
     * resolve.
     */
    @Test
    void reportsAProducerJarWhoseExportIndexIsStaleWhereTheConsumerUsesWhatItLeavesOut() throws IOException {
        String older = publishedIndex(olderProducer());
        Path jar = publish(producer(), directory.resolve("producer.jar"), (String current) -> older);

        Compilation.Result result = Compilation.compilePrepared(Compilation.prepare(consumer(), List.of(jar)));

        assertEquals(List.of(
                "[SG602] prod.BillActionsRefs.Cancel" + stale(jar),
                "[SG602] cons.Shelf holds prod.Bills, but prod.Bills" + stale(jar)), result.errors(),
                result.describe());
        assertFalse(result.hasGenerated("cons.ArchivesRuntime"), result.describe());
        assertFalse(result.hasGenerated("cons.ShelfRuntime"), result.describe());
    }

    /** The same producer compiled into a directory of classes, whose export index an older build's replaced. */
    @Test
    void reportsAProducerDirectoryWhoseExportIndexIsStaleWhereTheConsumerUsesWhatItLeavesOut() throws IOException {
        String older = publishedIndex(olderProducer());
        Compilation.Prepared producer = Compilation.prepare(producer());
        Compilation.Result produced = Compilation.compilePrepared(producer);
        assertTrue(produced.success(), produced.describe());
        Path classes = producer.request().classesOutput().toRealPath();
        Files.writeString(classes.resolve(PUBLISHED), older, StandardCharsets.UTF_8);

        Compilation.Result result = Compilation.compilePrepared(Compilation.prepare(consumer(), List.of(classes)));

        assertEquals(List.of(
                "[SG602] prod.BillActionsRefs.Cancel" + stale(classes),
                "[SG602] cons.Shelf holds prod.Bills, but prod.Bills" + stale(classes)), result.errors(),
                result.describe());
    }

    /** What a diagnostic says after naming a type that {@code entry} carries but its export index does not list. */
    private static String stale(Path entry) {
        return " comes from " + entry + ", whose export index " + PUBLISHED + " does not list it, so that index is"
                + " stale or another build's: package the index the producer's StateGraph build publishes with its"
                + " classes, or rebuild the producer through the complete lifecycle";
    }

    /** The export index a compilation of {@code sources} publishes. */
    private static String publishedIndex(Map<String, String> sources) throws IOException {
        Compilation.Prepared prepared = Compilation.prepare(sources);
        Compilation.Result result = Compilation.compilePrepared(prepared);
        assertTrue(result.success(), result.describe());
        return Files.readString(prepared.request().classesOutput().resolve(PUBLISHED), StandardCharsets.UTF_8);
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

    /** A machine, its entity, two exported actions and a set holding the machine, in a package of their own. */
    private static Map<String, String> producer() {
        Map<String, String> sources = new LinkedHashMap<>();
        putBillDomain(sources);
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

    /** The entity of {@link #producer()}, its states and its transitions, and the host of its two actions. */
    private static void putBillDomain(Map<String, String> sources) {
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
                    @GraphAction public ActionOutcome settle(TransitionContext<Bill, BillStatus, BillTransition> \
                context) {
                        return ActionOutcome.ok();
                    }
                    @GraphAction public ActionOutcome cancel(TransitionContext<Bill, BillStatus, BillTransition> \
                context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
    }

    /**
     * {@link #producer()} as an older build declared it: the same entity, whose properties it exports, and an action
     * host that exports settle alone, without the machine and the set.
     */
    private static Map<String, String> olderProducer() {
        Map<String, String> sources = producer();
        sources.remove("prod.Bills");
        sources.remove("prod.Ledger");
        sources.put("prod.BillActions", sources.get("prod.BillActions")
                .replace("@GraphAction public ActionOutcome cancel", "public ActionOutcome cancel"));
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
                    @ArchivesGraph.Transition(id = CANCEL, from = OPEN, to = PAID, \
                action = BillActionsRefs.Cancel.class)
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
