package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Generated code lives in a package of its own choosing: a machine's companion next to its facade, a set's next
 * to the set. A type it names must be accessible from there, and a declaration that would make it name one that
 * is not is refused with an SG code where it is declared, never left to fail inside a generated file.
 */
class AccessibilityTest {

    /** A package-private host in another package: its member is public, the class is not. */
    @Test
    void sg505AHostTheCompanionsPackageCannotAccess() {
        Map<String, String> sources = shop("billing.ChargesRefs.Charge.class");
        sources.put("billing.Charges", """
                package billing;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                import shop.Order;
                import shop.OrderStatus;
                import shop.OrderTransition;
                final class Charges {
                    @GraphAction public ActionOutcome charge(TransitionContext<Order, OrderStatus, OrderTransition> \
                context) {
                        return ActionOutcome.ok();
                    }
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError(DiagnosticCode.SG505.name()), result.describe());
        assertTrue(result.errors().get(0).contains("billing.Charges") && result.errors().get(0).contains("shop"),
                result.describe());
        assertFalse(result.hasGenerated("shop.OrdersRuntime"), result.describe());
    }

    /** A public host nested in a package-private class of another package is just as unreachable. */
    @Test
    void sg505AHostWhoseEnclosingClassTheCompanionsPackageCannotAccess() {
        Map<String, String> sources = shop("billing.OuterInnerRefs.Charge.class");
        sources.put("billing.Outer", """
                package billing;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                import shop.Order;
                import shop.OrderStatus;
                import shop.OrderTransition;
                final class Outer {
                    public static final class Inner {
                        @GraphAction public ActionOutcome charge(\
                TransitionContext<Order, OrderStatus, OrderTransition> context) {
                            return ActionOutcome.ok();
                        }
                    }
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError(DiagnosticCode.SG505.name()), result.describe());
        assertTrue(result.errors().get(0).contains("billing.Outer"), result.describe());
        assertFalse(result.hasGenerated("shop.OrdersRuntime"), result.describe());
    }

    /**
     * A set in another package than its machine names the machine's entity, its enums and the hosts it is built
     * with; the facade is public, but the entity and the host are not.
     */
    @Test
    void sg510ASetWhosePackageCannotAccessWhatItsMachinesAreBuiltWith() {
        Map<String, String> sources = shop("PaymentsRefs.Charge.class");
        sources.put("shop.Order", sources.get("shop.Order").replace("public final class Order", "final class Order"));
        sources.put("shop.Payments",
                sources.get("shop.Payments").replace("public final class Payments", "final class Payments"));
        sources.put("app.Shop", """
                package app;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                import shop.Orders;
                @StateGraphs
                public interface Shop extends Machines {
                    Orders orders();
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError(DiagnosticCode.SG510.name()), result.describe());
        assertTrue(result.errors().get(0).contains("shop.Order") && result.errors().get(0).contains("shop.Payments"),
                result.describe());
        assertFalse(result.hasGenerated("app.ShopRuntime"), result.describe());
        assertTrue(result.hasGenerated("shop.OrdersRuntime"), result.describe());
    }

    /**
     * A watch types its peer with the peer's entity and enums, which the watching companion names; here the
     * watcher's facade lives in another package than the package-private peer entity.
     */
    @Test
    void sg509AWatchedPeerWhoseEntityTheWatchersPackageCannotAccess() {
        Map<String, String> sources = new LinkedHashMap<>();
        putPagesPackage(sources);
        sources.put("docs.Docs", """
                package docs;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.annotation.GraphPeers;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import pages.Doc;
                import pages.DocStatus;
                import pages.DocTransition;
                import pages.DocRefs;
                import pages.PageRefs;
                import pages.PageStatus;
                @GenerateGraphApi
                @GraphPeers(pages.Pages.class)
                @DocsGraph.Definition(initial = DocStatus.DRAFT, terminals = DocStatus.PUBLISHED,
                    state = DocRefs.Status.class, id = DocRefs.Id.class)
                @DocsGraph.StatePolicy(state = DocStatus.DRAFT, otherwise = "a draft is sent to review")
                @DocsGraph.StatePolicy(state = DocStatus.REVIEW, otherwise = "the pages decide")
                @DocsPages.Watch(state = DocStatus.REVIEW, peers = DocRefs.Pages.class, owner = PageRefs.Doc.class,
                    rules = @DocsPages.When(allIn = PageStatus.DONE, transition = DocTransition.PUBLISH))
                public interface Docs extends Machine<Doc, DocStatus, DocTransition> {
                    @DocsGraph.Transition(id = DocTransition.SUBMIT, from = DocStatus.DRAFT, to = DocStatus.REVIEW)
                    Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                    @DocsGraph.Transition(id = DocTransition.PUBLISH, from = DocStatus.REVIEW, to = DocStatus.PUBLISHED)
                    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError(DiagnosticCode.SG509.name()), result.describe());
        assertTrue(result.errors().get(0).contains("pages.Page"), result.describe());
        assertFalse(result.hasGenerated("docs.DocsRuntime"), result.describe());
    }

    /** The package {@code pages}: a document with its states, and a package-private page with its machine. */
    private static void putPagesPackage(Map<String, String> sources) {
        sources.put("pages.DocStatus", "package pages;\npublic enum DocStatus { DRAFT, REVIEW, PUBLISHED }\n");
        sources.put("pages.DocTransition", "package pages;\npublic enum DocTransition { SUBMIT, PUBLISH }\n");
        sources.put("pages.PageStatus", "package pages;\npublic enum PageStatus { OPEN, DONE }\n");
        sources.put("pages.PageTransition", "package pages;\npublic enum PageTransition { FINISH }\n");
        sources.put("pages.Doc", """
                package pages;
                import io.github.graphmaquina.annotation.GraphProperty;
                import java.util.ArrayList;
                import java.util.Collection;
                import java.util.List;
                public final class Doc {
                    @GraphProperty private DocStatus status = DocStatus.DRAFT;
                    private final List<Page> pages = new ArrayList<>();
                    @GraphProperty public String id() { return "doc"; }
                    public DocStatus status() { return status; }
                    public void status(DocStatus value) { status = value; }
                    @GraphProperty public Collection<Page> pages() { return pages; }
                }
                """);
        sources.put("pages.Page", """
                package pages;
                import io.github.graphmaquina.annotation.GraphProperty;
                import java.util.Optional;
                final class Page {
                    @GraphProperty private PageStatus status = PageStatus.OPEN;
                    @GraphProperty public String id() { return "page"; }
                    public PageStatus status() { return status; }
                    public void status(PageStatus value) { status = value; }
                    @GraphProperty public Optional<Doc> doc() { return Optional.empty(); }
                }
                """);
        sources.put("pages.Pages", """
                package pages;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                @GenerateGraphApi
                @PagesGraph.Definition(initial = PageStatus.OPEN, terminals = PageStatus.DONE,
                    state = PageRefs.Status.class, id = PageRefs.Id.class)
                @PagesGraph.StatePolicy(state = PageStatus.OPEN, otherwise = "a page is finished")
                public interface Pages extends Machine<Page, PageStatus, PageTransition> {
                    @PagesGraph.Transition(id = PageTransition.FINISH, from = PageStatus.OPEN, to = PageStatus.DONE)
                    Outcome<Page, PageStatus, PageTransition> finish(Page page);
                }
                """);
    }

    /** The shop domain, every type public, with the facade's payment bound to {@code action}. */
    private static Map<String, String> shop(String action) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shop.OrderStatus", "package shop;\npublic enum OrderStatus { NEW, PAID, CANCELLED }\n");
        sources.put("shop.OrderTransition", "package shop;\npublic enum OrderTransition { PAY, CANCEL }\n");
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
        sources.put("shop.Payments", """
                package shop;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                public final class Payments {
                    @GraphAction public ActionOutcome charge(TransitionContext<Order, OrderStatus, OrderTransition> \
                context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        sources.put("shop.Orders", """
                package shop;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static shop.OrderStatus.*;
                import static shop.OrderTransition.*;
                @GenerateGraphApi
                @OrdersGraph.Definition(initial = NEW, terminals = {PAID, CANCELLED}, state = OrderRefs.Status.class,
                    id = OrderRefs.Id.class)
                @OrdersGraph.StatePolicy(state = NEW, otherwise = "a new order is paid or cancelled")
                public interface Orders extends Machine<Order, OrderStatus, OrderTransition> {
                    @OrdersGraph.Transition(id = PAY, from = NEW, to = PAID, action = %s)
                    Outcome<Order, OrderStatus, OrderTransition> pay(Order order);

                    @OrdersGraph.Transition(id = CANCEL, from = NEW, to = CANCELLED)
                    Outcome<Order, OrderStatus, OrderTransition> cancel(Order order);
                }
                """.formatted(action));
        return sources;
    }
}
