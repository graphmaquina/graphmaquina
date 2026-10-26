package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * A watch whose {@code ownerLookup} names an exported lookup follows each peer back through it. The companion hands
 * the lookup to the DSL as the watch's {@code ownerOf}, called on its class when static and on the instance the
 * builder takes otherwise, with no wrapper: an empty {@code Optional} is a peer without an owner, which no reaction
 * follows, and a lookup that returns null is refused by the engine as any {@code ownerOf} is. A checked exception the
 * lookup declares is caught like any binding's, and a class that hosts both the lookup and a condition of the machine
 * is one parameter of the builder. An {@code owner} property keeps both of its shapes and the code generated for them.
 * Each scenario compiles the shop under {@code src/test/resources/watch-owner}, pays an order of two items, packs
 * them one by one and reports the order's state after each, then each reaction the second packing ended aborted.
 */
class WatchOwnerLookupTest {

    private static final String RESOURCES = "/watch-owner";

    /** The owner line of the fixture's watch, which each test replaces with the one it is about. */
    private static final String OWNER = "ownerLookup = OrderLookupsRefs.OrderOf.class,";

    /** Where the fixture's scenario passes the instance hosts its builder takes, none as written. */
    private static final String HOSTS = "/* hosts */";

    /** The peers reader of the watch, which the companion writes before the owner's. */
    private static final String PEERS = "(Order order) -> order.items(), ";

    @Test
    void aStaticLookupFindsTheOwner() throws Exception {
        Compilation.Result result = compile(sources(OWNER, ""));

        assertEquals("PAID SHIPPED", run(result));
        assertCompanion(result, PEERS + "(Item item) -> OrderLookups.orderOf(item))");
    }

    @Test
    void anInstanceLookupFindsTheOwner() throws Exception {
        Compilation.Result result = compile(sources("ownerLookup = OrderLookupsRefs.HeldOrderOf.class,",
                "new OrderLookups()"));

        assertEquals("PAID SHIPPED", run(result));
        assertCompanion(result, PEERS + "(Item item) -> orderLookups.heldOrderOf(item))");
    }

    /** Once both items are packed the order would ship, had the lookup found it. */
    @Test
    void anEmptyLookupLeavesThePeerWithoutReaction() throws Exception {
        assertEquals("PAID PAID", run(compile(sources("ownerLookup = OrderLookupsRefs.NoOrder.class,", ""))));
    }

    @Test
    void aNullLookupIsRefusedByTheEngine() throws Exception {
        Compilation.Result result = compile(sources("ownerLookup = OrderLookupsRefs.NullOrder.class,", ""));

        assertEquals("PAID PAID, NullPointerException: the ownerOf of Order/OrderStatus.PAID returned null",
                run(result));
        assertCompanion(result, PEERS + "(Item item) -> OrderLookups.nullOrder(item))");
    }

    /** Every fixture compiles with {@code -Werror}, which an uncaught checked exception would fail. */
    @Test
    void aCheckedExceptionOfTheLookupIsCaught() throws Exception {
        Compilation.Result result = compile(sources("ownerLookup = OrderLookupsRefs.StoredOrderOf.class,", ""));

        assertEquals("PAID SHIPPED", run(result));
        assertCompanion(result, PEERS + "(Item item) -> { try { return OrderLookups.storedOrderOf(item); }"
                + " catch (Exception failure) { throw rethrow(failure); } })");
    }

    /** The scenario passes one host, which compiles only when the builder takes exactly that one. */
    @Test
    void oneInstanceHostServesTheLookupAndACondition() throws Exception {
        Map<String, String> sources = sources("ownerLookup = OrderLookupsRefs.HeldOrderOf.class,",
                "new OrderLookups()");
        sources.put("shop.Orders", replaced(sources.get("shop.Orders"), "allIn = ItemStatus.PACKED",
                "condition = OrderLookupsRefs.Packed.class"));
        Compilation.Result result = compile(sources);

        assertEquals("PAID SHIPPED", run(result));
        assertCompanion(result, " graph(OrderLookups orderLookups) {");
    }

    /**
     * Within one machine the hosts come watch by watch, the conditions and then the owner lookup of each, before the
     * hosts of the edges declared on operations: here a condition host, the lookup host and the host of the action of
     * {@code pay}, three classes, so the scenario's builder call compiles only in that order.
     */
    @Test
    void theLookupHostComesWithItsWatchBeforeTheHostsOfOperations() throws Exception {
        Map<String, String> sources = sources("ownerLookup = OrderLookupsRefs.HeldOrderOf.class,",
                "new OrderConditions(), new OrderLookups(), new OrderActions()");
        String orders = replaced(sources.get("shop.Orders"), "allIn = ItemStatus.PACKED",
                "condition = OrderConditionsRefs.Ready.class");
        sources.put("shop.Orders", replaced(orders, "@OrdersGraph.Transition(id = PAY, from = NEW, to = PAID)",
                "@OrdersGraph.Transition(id = PAY, from = NEW, to = PAID, action = OrderActionsRefs.Charge.class)"));
        Compilation.Result result = compile(sources);

        assertEquals("PAID SHIPPED", run(result));
        assertCompanion(result, " graph(OrderConditions orderConditions, OrderLookups orderLookups,"
                + " OrderActions orderActions) {");
    }

    /** {@code order} returns an {@code Optional} of the order and {@code placedIn} the order itself. */
    @Test
    void ownerPropertiesKeepBothShapes() throws Exception {
        Compilation.Result optional = compile(sources("owner = ItemRefs.Order.class,", ""));
        Compilation.Result plain = compile(sources("owner = ItemRefs.PlacedIn.class,", ""));

        assertEquals("PAID SHIPPED", run(optional));
        assertEquals("PAID SHIPPED", run(plain));
        assertCompanion(optional, PEERS + "(Item item) -> item.order())");
        assertCompanion(plain, PEERS + "(Item item) -> Optional.of(Objects.requireNonNull(item.placedIn(),"
                + " \"the ownerOf of Order/OrderStatus.PAID returned null\")))");
    }

    private static void assertCompanion(Compilation.Result result, String expected) {
        String companion = result.generated("shop.OrdersRuntime");
        assertTrue(companion.contains(expected), companion);
    }

    /** What the scenario of {@code result} reports. */
    private static Object run(Compilation.Result result) throws Exception {
        return result.load("shop.Scenario").getMethod("run").invoke(null);
    }

    /** Compiles {@code sources}, which must succeed. */
    private static Compilation.Result compile(Map<String, String> sources) {
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result.describe());
        return result;
    }

    /**
     * The fixture, whose watch declares {@code owner} in place of its owner line and whose scenario hands the builder
     * {@code hosts}.
     */
    private static Map<String, String> sources(String owner, String hosts) throws URISyntaxException {
        URL root = Objects.requireNonNull(WatchOwnerLookupTest.class.getResource(RESOURCES),
                RESOURCES + " is not on the test classpath");
        Map<String, String> sources = FixtureSources.read(Path.of(root.toURI()));
        sources.put("shop.Orders", replaced(sources.get("shop.Orders"), OWNER, owner));
        sources.put("shop.Scenario", replaced(sources.get("shop.Scenario"), HOSTS, hosts));
        return sources;
    }

    /** {@code text} with {@code target}, which it must contain, replaced by {@code replacement}. */
    private static String replaced(String text, String target, String replacement) {
        assertTrue(text.contains(target), () -> "the fixture no longer contains " + target);
        return text.replace(target, replacement);
    }
}
