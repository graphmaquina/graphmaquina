package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * A watch finds the owner of a peer through exactly one of {@code owner}, a property of the peer, and
 * {@code ownerLookup}, an exported lookup, each of which holds one value at most. Declaring neither or both, an empty
 * array counting as none, is {@code SG405} at the watch, and then neither element is resolved, so nothing else is
 * reported about either; one element with two values is {@code SG607}, and a lookup that does not fit
 * {@code Optional<Owner> name(Peer)} is {@code SG505}. The rest of the watch is still read and reported as before.
 * The fixture is the shop under {@code src/test/resources/watch-owner}, whose orders watch their items.
 */
class WatchOwnerDiagnosticsTest {

    private static final String RESOURCES = "/watch-owner";

    /** The owner line of the fixture's watch, which each test replaces with the one it is about. */
    private static final String OWNER = "ownerLookup = OrderLookupsRefs.OrderOf.class,";

    private static final String NEITHER = "[" + DiagnosticCode.SG405 + "] the watch of PAID declares neither owner"
            + " nor ownerLookup: it declares exactly one of them";

    private static final String BOTH = "[" + DiagnosticCode.SG405 + "] the watch of PAID declares both owner and"
            + " ownerLookup: it declares exactly one of them";

    /** The refusal names the watch, at the line that opens it. */
    @Test
    void neitherOwnerNorLookupIsRefusedAtTheWatch() throws URISyntaxException {
        Map<String, String> sources = sources("");
        Compilation.Result result = compile(sources);

        assertErrors(List.of(NEITHER), result);
        assertEquals(lineOf(sources.get("shop.Orders"), "@OrdersItems.Watch("),
                result.lineOf(DiagnosticCode.SG405.name()), result::describe);
    }

    /** Both elements resolve on their own, yet the watch reports only that it declares both. */
    @Test
    void bothOwnerAndLookupAreRefusedWithNothingElse() throws URISyntaxException {
        assertErrors(List.of(BOTH),
                compile(sources("owner = ItemRefs.Order.class, ownerLookup = OrderLookupsRefs.OrderOf.class,")));
    }

    /** {@code owner} names a property of the order, not of the item, which is never resolved once both are declared. */
    @Test
    void bothWithAWrongOwnerAreStillRefusedWithNothingElse() throws URISyntaxException {
        assertErrors(List.of(BOTH),
                compile(sources("owner = OrderRefs.Id.class, ownerLookup = OrderLookupsRefs.OrderOf.class,")));
    }

    @Test
    void anEmptyOwnerArrayIsNoOwner() throws URISyntaxException {
        assertErrors(List.of(NEITHER), compile(sources("owner = {},")));
    }

    @Test
    void twoLookupsAreTooManyValues() throws URISyntaxException {
        assertErrors(List.of("[" + DiagnosticCode.SG607 + "] ownerLookup declares 2 values but holds one at most"),
                compile(sources("ownerLookup = {OrderLookupsRefs.OrderOf.class, OrderLookupsRefs.NoOrder.class},")));
    }

    /** {@code unrelated} takes the order rather than the item, so it is no lookup of an item's owner. */
    @Test
    void aLookupWithTheWrongSignatureNamesTheOneItNeeds() throws URISyntaxException {
        assertErrors(List.of("[" + DiagnosticCode.SG505 + "] no exported lookup named unrelated on shop.OrderLookups"
                + " has the signature this machine needs; expected Optional<Order> unrelated(Item)"),
                compile(sources("ownerLookup = OrderLookupsRefs.Unrelated.class,")));
    }

    /** A watch with no owner still has its rules read, and a watch without rules is reported as one. */
    @Test
    void theRestOfAWatchWithoutAnOwnerIsStillRead() throws URISyntaxException {
        Map<String, String> sources = sources("");
        sources.put("shop.Orders", replaced(sources.get("shop.Orders"),
                "rules = @OrdersItems.When(allIn = ItemStatus.PACKED, transition = SHIP)", "rules = {}"));

        assertErrors(List.of(NEITHER, "[" + DiagnosticCode.SG404 + "] the watch of PAID declares no rule"),
                compile(sources));
    }

    private static void assertErrors(List<String> expected, Compilation.Result result) {
        assertFalse(result.success(), result.describe());
        assertEquals(expected, result.errors(), result.describe());
    }

    private static Compilation.Result compile(Map<String, String> sources) {
        return Compilation.compileTyped(sources);
    }

    /** The fixture, whose watch declares {@code owner} in place of its owner line. */
    private static Map<String, String> sources(String owner) throws URISyntaxException {
        URL root = Objects.requireNonNull(WatchOwnerDiagnosticsTest.class.getResource(RESOURCES),
                RESOURCES + " is not on the test classpath");
        Map<String, String> sources = FixtureSources.read(Path.of(root.toURI()));
        sources.put("shop.Orders", replaced(sources.get("shop.Orders"), OWNER, owner));
        return sources;
    }

    /** The line of {@code source}, counted from 1, on which {@code text} starts. */
    private static long lineOf(String source, String text) {
        assertTrue(source.contains(text), () -> "the fixture no longer contains " + text);
        return source.substring(0, source.indexOf(text)).split("\n", -1).length;
    }

    /** {@code text} with {@code target}, which it must contain, replaced by {@code replacement}. */
    private static String replaced(String text, String target, String replacement) {
        assertTrue(text.contains(target), () -> "the fixture no longer contains " + target);
        return text.replace(target, replacement);
    }
}
