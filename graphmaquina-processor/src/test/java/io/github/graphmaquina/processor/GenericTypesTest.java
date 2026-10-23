package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A generated symbol names its host by a class literal, which is the raw type of a generic class. The generated
 * code names an entity by its class and calls an instance member on a parameter of the host's class, so neither
 * a parameterized entity nor an instance member of a generic host can be generated: each is refused with a
 * diagnostic that says so, never with one that compares a raw type with a parameterized one.
 */
class GenericTypesTest {

    @Test
    void sg601AParameterizedEntity() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shop.TicketStatus", "package shop;\npublic enum TicketStatus { OPEN, CLOSED }\n");
        sources.put("shop.TicketTransition", "package shop;\npublic enum TicketTransition { CLOSE }\n");
        sources.put("shop.Ticket", """
                package shop;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Ticket<P> {
                    @GraphProperty private TicketStatus status = TicketStatus.OPEN;
                    @GraphProperty public String id() { return "t1"; }
                    public TicketStatus status() { return status; }
                    public void status(TicketStatus value) { status = value; }
                }
                """);
        sources.put("shop.Tickets", """
                package shop;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static shop.TicketStatus.*;
                import static shop.TicketTransition.*;
                @GenerateGraphApi
                @TicketsGraph.Definition(initial = OPEN, terminals = CLOSED, \
                state = TicketRefs.Status.class, id = TicketRefs.Id.class)
                @TicketsGraph.StatePolicy(state = OPEN, otherwise = "an open ticket is closed")
                public interface Tickets extends Machine<Ticket<String>, TicketStatus, TicketTransition> {
                    @TicketsGraph.Transition(id = CLOSE, from = OPEN, to = CLOSED)
                    Outcome<Ticket<String>, TicketStatus, TicketTransition> close(Ticket<String> ticket);
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError("SG601"), result.describe());
        assertTrue(result.errors().get(0).contains("shop.Ticket<java.lang.String>")
                && result.errors().get(0).contains("parameterized"), result.describe());
        assertEquals(0, result.countError("SG605"), result.describe());
        assertFalse(result.hasGenerated("shop.TicketsRuntime"), result.describe());
    }

    /**
     * An inner class of a generic class is parameterized through that class: {@code Store<String>.Doc} is refused as
     * a parameterized entity, as {@code Ticket<String>} is, not with a comparison of its properties' raw host with
     * it.
     */
    @Test
    void sg601AnEntityParameterizedThroughTheClassItIsAnInnerClassOf() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", "package fx;\npublic enum DocStatus { DRAFT, PUBLISHED }\n");
        sources.put("fx.DocTransition", "package fx;\npublic enum DocTransition { PUBLISH }\n");
        sources.put("fx.Store", """
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Store<T> {
                    public final class Doc {
                        @GraphProperty private DocStatus status = DocStatus.DRAFT;
                        @GraphProperty public String id() { return "doc"; }
                        public DocStatus status() { return status; }
                        public void status(DocStatus value) { status = value; }
                    }
                }
                """);
        sources.put("fx.Docs", """
                package fx;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static fx.DocStatus.*;
                import static fx.DocTransition.*;
                @GenerateGraphApi
                @DocsGraph.Definition(initial = DRAFT, terminals = PUBLISHED,
                    state = StoreDocRefs.Status.class, id = StoreDocRefs.Id.class)
                @DocsGraph.StatePolicy(state = DRAFT, otherwise = "a draft is published")
                public interface Docs extends Machine<Store<String>.Doc, DocStatus, DocTransition> {
                    @DocsGraph.Transition(id = PUBLISH, from = DRAFT, to = PUBLISHED)
                    Outcome<Store<String>.Doc, DocStatus, DocTransition> publish(Store<String>.Doc doc);
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError("SG601"), result.describe());
        assertTrue(result.errors().get(0).contains("fx.Store<java.lang.String>.Doc")
                && result.errors().get(0).contains("parameterized"), result.describe());
        assertEquals(0, result.countError("SG605"), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
    }

    /**
     * A peer over a parameterized entity is refused at its own header, and a watch of it is refused for that
     * reason too, rather than with a comparison of the raw host of its property symbols with its entity.
     */
    @Test
    void sg509AWatchOfAPeerOverAParameterizedEntity() {
        Map<String, String> sources = TypedFixtures.watching();
        sources.put("fx.Page", sources.get("fx.Page")
                .replace("public final class Page {", "public final class Page<T> {")
                .replace("public Page(String id, Doc doc)", "public Page(String id, Doc doc, T tag)"));
        sources.put("fx.Doc", sources.get("fx.Doc").replace("List<Page>", "List<Page<String>>")
                .replace("Collection<Page>", "Collection<Page<String>>")
                .replace("void attach(Page page)", "void attach(Page<String> page)"));
        sources.put("fx.Pages", sources.get("fx.Pages").replace("Machine<Page, PageStatus, PageTransition>",
                "Machine<Page<String>, PageStatus, PageTransition>")
                .replace("Outcome<Page, PageStatus, PageTransition> finish(Page page)",
                        "Outcome<Page<String>, PageStatus, PageTransition> finish(Page<String> page)"));
        sources.put("fx.DocActions", sources.get("fx.DocActions")
                .replace("Iterable<? extends Page>", "Iterable<? extends Page<String>>")
                .replace("Collection<? extends Page>", "Collection<? extends Page<String>>")
                .replace("PeerView<Page, PageStatus, PageTransition>",
                        "PeerView<Page<String>, PageStatus, PageTransition>"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError("SG601"), result.describe());
        assertEquals(1, result.countError("SG509"), result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.contains("[SG509]")
                && error.contains("fx.Page<java.lang.String>") && error.contains("parameterized")), result.describe());
        assertEquals(0, result.countError("SG605"), result.describe());
    }

    @Test
    void sg505AnInstanceMemberOfAGenericHost() {
        Compilation.Result result = Compilation.compileTyped(ledger(""));

        assertEquals(1, result.countError("SG505"), result.describe());
        assertTrue(result.errors().get(0).contains("shop.Ledger") && result.errors().get(0).contains("generic"),
                result.describe());
        assertFalse(result.hasGenerated("shop.OrdersRuntime"), result.describe());
    }

    /**
     * An instance member of an inner class of a generic class is called on a parameter of that inner class, which
     * generated code could only write raw, so it is refused as a member of a generic class is, not as a member whose
     * signature does not fit.
     */
    @Test
    void sg505AnInstanceMemberOfAnInnerClassOfAGenericClass() {
        Compilation.Result result = Compilation.compileTyped(book(""));

        assertEquals(1, result.countError("SG505"), result.describe());
        assertTrue(result.errors().get(0).contains("shop.Ledger.Book")
                && result.errors().get(0).contains("an inner class of a generic class"), result.describe());
        assertFalse(result.errors().get(0).contains("has the signature this machine needs"), result.describe());
        assertFalse(result.hasGenerated("shop.OrdersRuntime"), result.describe());
    }

    /** A static nested class of a generic class is not parameterized by it, so its instance members are bound. */
    @Test
    void anInstanceMemberOfAStaticNestedClassOfAGenericClassIsBound() {
        Compilation.Result result = Compilation.compileTyped(book("static "));

        assertTrue(result.success(), result.describe());
    }

    /** A static member is called through the class itself, whose raw name is all the call needs. */
    @Test
    void aStaticMemberOfAGenericHostIsBound() {
        Compilation.Result result = Compilation.compileTyped(ledger("static "));

        assertTrue(result.success(), result.describe());
    }

    /**
     * The shop domain paid through {@code post}, an instance member of {@code Book}, a class nested in
     * {@code Ledger<X>} and declared with {@code modifier} after {@code public}.
     */
    private static Map<String, String> book(String modifier) {
        Map<String, String> sources = ledger("");
        sources.put("shop.Ledger", """
                package shop;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                public class Ledger<X> {
                    public %sclass Book {
                        @GraphAction public ActionOutcome post(TransitionContext<Order, OrderStatus, OrderTransition> \
                context) {
                            return ActionOutcome.ok();
                        }
                    }
                    public X last() { return null; }
                }
                """.formatted(modifier));
        sources.put("shop.Orders", sources.get("shop.Orders").replace("LedgerRefs.Post", "LedgerBookRefs.Post"));
        return sources;
    }

    /** The shop domain paid through {@code Ledger<X>.post}, declared with {@code modifier} after {@code public}. */
    private static Map<String, String> ledger(String modifier) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shop.OrderStatus", "package shop;\npublic enum OrderStatus { NEW, PAID }\n");
        sources.put("shop.OrderTransition", "package shop;\npublic enum OrderTransition { PAY }\n");
        sources.put("shop.Order", """
                package shop;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Order {
                    @GraphProperty private OrderStatus status = OrderStatus.NEW;
                    @GraphProperty public String id() { return "o1"; }
                    public OrderStatus status() { return status; }
                    public void status(OrderStatus value) { status = value; }
                }
                """);
        sources.put("shop.Ledger", """
                package shop;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                public class Ledger<X> {
                    @GraphAction public %sActionOutcome post(TransitionContext<Order, OrderStatus, OrderTransition> \
                context) {
                        return ActionOutcome.ok();
                    }
                    public X last() { return null; }
                }
                """.formatted(modifier));
        sources.put("shop.Orders", """
                package shop;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static shop.OrderStatus.*;
                import static shop.OrderTransition.*;
                @GenerateGraphApi
                @OrdersGraph.Definition(initial = NEW, terminals = PAID, \
                state = OrderRefs.Status.class, id = OrderRefs.Id.class)
                @OrdersGraph.StatePolicy(state = NEW, otherwise = "a new order is paid")
                public interface Orders extends Machine<Order, OrderStatus, OrderTransition> {
                    @OrdersGraph.Transition(id = PAY, from = NEW, to = PAID, action = LedgerRefs.Post.class)
                    Outcome<Order, OrderStatus, OrderTransition> pay(Order order);
                }
                """);
        return sources;
    }
}
