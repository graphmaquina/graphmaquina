package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.error.InvalidGraphException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two reference scenarios of {@link CrossModuleSources}, run end to end with the producer and the consumer
 * compiled apart, the consumer seeing only what the producer's build published: a booking whose deposit, a payment of
 * the producer, is captured leaves for {@code CONFIRMED}, and a paid order ships once the last of its items, items of
 * the producer, is packed. The checks of a binding hold against the child of a jar, at build time and, for a child jar
 * other than the one the consumer compiled against, when the set is built.
 */
class CrossModuleCompositionTest {

    /**
     * What {@code app.Scenario.run()} reports: the booking and its deposit once the deposit is captured, then the order
     * after its first item is packed and after its last.
     */
    private static final String BOTH_SCENARIOS = "CONFIRMED CAPTURED / PAID SHIPPED";

    private static final String SCENARIO = "app.Scenario";

    /** The third module: a set of the bookings of one jar and the payments of another, and its scenario. */
    private static final String THIRD_MODULE = "/cross-module-third";

    /** The exit of the booking's binding that a declined deposit takes. */
    private static final String DECLINED_EXIT =
            "@BookingsPayments.Exit(on = PaymentStatus.DECLINED, transition = DEPOSIT_DECLINED)";

    @TempDir
    Path directory;

    @Test
    void bothScenariosRunAcrossJars() throws Exception {
        Path jar = published(CrossModuleSources.producer(), "producer.jar");

        assertEquals(BOTH_SCENARIOS, run(compiled(CrossModuleSources.consumer(), List.of(jar)), List.of(jar),
                SCENARIO));
    }

    /** A project dependency of a multi-project build reaches the consumer as its directory of classes. */
    @Test
    void bothScenariosRunAcrossADirectoryOfClasses() throws Exception {
        Path classes = PublishedJars.classes(CrossModuleSources.producer(), List.of());
        assertTrue(Files.isRegularFile(classes.resolve(PublishedJars.PUBLISHED)), classes.toString());

        assertEquals(BOTH_SCENARIOS, run(compiled(CrossModuleSources.consumer(), List.of(classes)), List.of(classes),
                SCENARIO));
    }

    /** {@code pay.jar} holds the payments, {@code book.jar} the bookings compiled against it, and a third module both. */
    @Test
    void aThirdModuleSetHoldsAParentAndAChildFromTwoJars() throws Exception {
        List<Path> jars = bookAndPayJars();

        assertEquals("CONFIRMED CAPTURED", run(compiled(FixtureSources.resource(THIRD_MODULE), jars), jars,
                "desk.DeskScenario"));
    }

    @Test
    void aThirdModuleSetWithoutTheChildIsRefused() throws Exception {
        String set = FixtureSources.resource(THIRD_MODULE).get("desk.Desk");
        Map<String, String> sources = Map.of("desk.Desk", replaced(set, "    Payments payments();\n", ""));

        Compilation.Result result = Compilation.compilePrepared(Compilation.prepare(sources, bookAndPayJars()));

        assertEquals(List.of("[" + DiagnosticCode.SG806
                + "] the sub-machine of Bookings.DEPOSIT is Payments, which is not in Desk"), result.errors(),
                result.describe());
    }

    /** The consumer's set without the producer's items, which its orders watch; the scenario is left out. */
    @Test
    void aSetWithoutThePeersOfAWatchIsRefused() throws Exception {
        Path jar = published(CrossModuleSources.producer(), "producer.jar");
        Map<String, String> sources = CrossModuleSources.consumer();
        sources.remove(SCENARIO);
        sources.put("app.Store", replaced(sources.get("app.Store"), "\n    Items items();\n", "\n"));

        Compilation.Result result = Compilation.compilePrepared(Compilation.prepare(sources, List.of(jar)));

        assertEquals(List.of("[" + DiagnosticCode.SG510
                + "] the watch of Orders.PAID points to Items, which is not in Store"), result.errors(),
                result.describe());
    }

    /**
     * The consumer compiled against the producer's jar runs with a rebuilt jar whose payments have one more terminal
     * state, {@code VOIDED}, in place of the one it compiled against, which the loader does not hold: the companion of
     * the bookings binds them with two exits, and the set's generated builder, which builds every graph of the set,
     * refuses the third terminal state before any call.
     */
    @Test
    void aChildJarWithOtherTerminalsIsRefusedWhenTheSetIsBuilt() throws Exception {
        Path compiledAgainst = published(CrossModuleSources.producer(), "producer.jar");
        Path rebuilt = published(withVoidedPayments(CrossModuleSources.producer()), "rebuilt.jar");
        Path consumer = compiled(CrossModuleSources.consumer(), List.of(compiledAgainst));

        try (URLClassLoader loader = loader(consumer, List.of(rebuilt))) {
            assertEquals(4, loader.loadClass("pay.PaymentStatus").getEnumConstants().length);
            InvocationTargetException failure =
                    assertThrows(InvocationTargetException.class, () -> invoke(loader, SCENARIO));
            InvalidGraphException refused = assertInstanceOf(InvalidGraphException.class, failure.getCause());
            assertEquals(List.of(new InvalidGraphException.Problem(DiagnosticCode.SG801.name(),
                    "Booking/BookingStatus.DEPOSIT: the terminal VOIDED of Payment/PaymentStatus has no exit")),
                    refused.problems());
            assertTrue(Arrays.stream(refused.getStackTrace()).anyMatch((StackTraceElement frame) ->
                    frame.getClassName().equals("app.StoreRuntime") && frame.getMethodName().equals("builder")));
        }
    }

    /** The exits of the consumer's binding are checked against the terminal states the child's jar declares. */
    @Test
    void exitsAreCheckedAgainstTheTerminalsOfTheChildJar() throws Exception {
        Path jar = published(CrossModuleSources.producer(), "producer.jar");
        Map<String, String> sources = CrossModuleSources.consumer();
        sources.put("book.Bookings", replaced(sources.get("book.Bookings"), DECLINED_EXIT,
                DECLINED_EXIT.replace("DECLINED,", "PENDING,")));

        Compilation.Result result = Compilation.compilePrepared(Compilation.prepare(sources, List.of(jar)));

        assertEquals(List.of(
                "[" + DiagnosticCode.SG801 + "] Bookings.DEPOSIT: the terminal DECLINED of Payments has no exit",
                "[" + DiagnosticCode.SG801 + "] Bookings.DEPOSIT: the exit on PENDING names no terminal of Payments"),
                result.errors(), result.describe());
    }

    /**
     * An exit whose identifier has no edge from the bound state is reported against the child of a jar as it is when
     * the child is compiled with its parent.
     */
    @Test
    void anExitWithoutAnEdgeFromTheBoundStateIsReportedAsWithinOneCompilation() throws Exception {
        Path jar = published(CrossModuleSources.producer(), "producer.jar");
        Map<String, String> sources = CrossModuleSources.consumer();
        sources.put("book.Bookings", replaced(sources.get("book.Bookings"),
                "@BookingsGraph.Transition(id = DEPOSIT_DECLINED, from = DEPOSIT, to = CANCELLED)",
                "@BookingsGraph.Transition(id = DEPOSIT_DECLINED, from = REQUESTED, to = CANCELLED)"));
        Map<String, String> together = new LinkedHashMap<>(CrossModuleSources.producer());
        together.putAll(sources);

        Compilation.Result acrossJars = Compilation.compilePrepared(Compilation.prepare(sources, List.of(jar)));
        Compilation.Result within = Compilation.compileTyped(together);

        assertEquals(List.of("[" + DiagnosticCode.SG802 + "] Bookings.DEPOSIT: the exit on DECLINED names"
                + " DEPOSIT_DECLINED, which has no edge from DEPOSIT"), acrossJars.errors(), acrossJars.describe());
        assertEquals(within.errors(), acrossJars.errors(), within.describe());
    }

    /**
     * A watch whose peer's entity already returns the owner, because both modules share the domain, follows that
     * property across the jar: the shop of {@code src/test/resources/watch-owner} split in two, the items, the item
     * and the order in the producer's jar, and the orders, which find an item's order through {@code Item.order()},
     * in the consumer.
     */
    @Test
    void aWatchFindsItsOwnerThroughAPropertyOfADomainBothModulesShare() throws Exception {
        Map<String, String> producer = new LinkedHashMap<>();
        Map<String, String> consumer = new LinkedHashMap<>();
        List<String> domain = List.of("shop.Item", "shop.Items", "shop.ItemStatus", "shop.ItemTransition",
                "shop.Order", "shop.OrderStatus");
        FixtureSources.resource("/watch-owner").forEach((String name, String source) ->
                (domain.contains(name) ? producer : consumer).put(name, source));
        consumer.put("shop.Orders", replaced(consumer.get("shop.Orders"),
                "ownerLookup = OrderLookupsRefs.OrderOf.class,", "owner = ItemRefs.Order.class,"));
        consumer.put("shop.Scenario", replaced(consumer.get("shop.Scenario"), "/* hosts */", ""));
        Path jar = published(producer, "shop.jar");

        assertEquals("PAID SHIPPED", run(compiled(consumer, List.of(jar)), List.of(jar), "shop.Scenario"));
    }

    /** The producer's jar of the payments alone, then the consumer's jar of the bookings, compiled against it. */
    private List<Path> bookAndPayJars() throws Exception {
        Path pay = published(only(CrossModuleSources.producer(), "pay."), "pay.jar");
        Path book = PublishedJars.publish(only(CrossModuleSources.consumer(), "book."), List.of(pay),
                directory.resolve("book.jar"), UnaryOperator.identity());
        return List.of(pay, book);
    }

    /**
     * The producer rebuilt with one more terminal state of the payments, {@code VOIDED}, which a payment reaches from
     * {@code PENDING}.
     */
    private static Map<String, String> withVoidedPayments(Map<String, String> producer) {
        Map<String, String> rebuilt = new LinkedHashMap<>(producer);
        rebuilt.put("pay.PaymentStatus", replaced(producer.get("pay.PaymentStatus"), "    DECLINED\n",
                "    DECLINED,\n    VOIDED\n"));
        rebuilt.put("pay.PaymentTransition", replaced(producer.get("pay.PaymentTransition"), "    DECLINE\n",
                "    DECLINE,\n    VOID\n"));
        String payments = replaced(producer.get("pay.Payments"), "terminals = {CAPTURED, DECLINED},",
                "terminals = {CAPTURED, DECLINED, PaymentStatus.VOIDED},");
        rebuilt.put("pay.Payments", replaced(payments, "public interface Payments",
                "@PaymentsGraph.Transition(id = PaymentTransition.VOID, from = PENDING, to = PaymentStatus.VOIDED)\n"
                + "public interface Payments"));
        return rebuilt;
    }

    /** The sources of {@code sources} whose qualified name starts with {@code prefix}. */
    private static Map<String, String> only(Map<String, String> sources, String prefix) {
        Map<String, String> kept = new LinkedHashMap<>();
        sources.forEach((String name, String source) -> {
            if (name.startsWith(prefix)) {
                kept.put(name, source);
            }
        });
        return kept;
    }

    /** {@code sources} compiled on their own into {@code name}, a jar that carries the index they published. */
    private Path published(Map<String, String> sources, String name) throws Exception {
        return PublishedJars.publish(sources, directory.resolve(name), UnaryOperator.identity());
    }

    /** The class output of {@code sources} compiled over {@code classpath} through the whole lifecycle. */
    private static Path compiled(Map<String, String> sources, List<Path> classpath) {
        Compilation.Prepared prepared = Compilation.prepare(sources, classpath);
        Compilation.Result result = Compilation.compilePrepared(prepared);
        assertTrue(result.success(), result.describe());
        return prepared.request().classesOutput();
    }

    /** What {@code type}'s {@code run()} returns, with {@code classes} and {@code runtime} loaded together. */
    private static Object run(Path classes, List<Path> runtime, String type) throws Exception {
        try (URLClassLoader loader = loader(classes, runtime)) {
            return invoke(loader, type);
        }
    }

    /** A loader of {@code classes} and of the {@code runtime} entries, and of nothing else of either module. */
    private static URLClassLoader loader(Path classes, List<Path> runtime) throws MalformedURLException {
        List<URL> urls = new ArrayList<>();
        urls.add(classes.toUri().toURL());
        for (Path entry : runtime) {
            urls.add(entry.toUri().toURL());
        }
        return new URLClassLoader(urls.toArray(URL[]::new), CrossModuleCompositionTest.class.getClassLoader());
    }

    private static Object invoke(ClassLoader loader, String type) throws Exception {
        return loader.loadClass(type).getMethod("run").invoke(null);
    }

    /** {@code text} with {@code target}, which it must contain, replaced by {@code replacement}. */
    private static String replaced(String text, String target, String replacement) {
        assertTrue(text.contains(target), () -> "the fixture no longer contains " + target);
        return text.replace(target, replacement);
    }
}
