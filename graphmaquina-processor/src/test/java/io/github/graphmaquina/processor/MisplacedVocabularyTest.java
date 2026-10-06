package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * javac accepts the generated vocabulary on any method and any type, since its targets cannot say "an abstract
 * operation of this facade", and the processor claims it, so nothing else ever says it was not read. The
 * processor reads it on the facade and on the facade's abstract operations only, and refuses it everywhere else
 * where it is written: a declaration the author wrote is never dropped without a word.
 */
class MisplacedVocabularyTest {

    /** A default, static or private method is no operation: the edge declared on it would silently be missing. */
    @Test
    void sg504VocabularyOnADefaultStaticOrPrivateMethodOfTheFacade() {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withDocs(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY + """
                    @DocsGraph.Transition(id = RETURN, from = PUBLISHED, to = REVIEW)
                    default Outcome<Doc, DocStatus, DocTransition> reopen(Doc doc) { return submit(doc); }
                    @DocsGraph.Transition(id = SUBMIT, from = ARCHIVED, to = DRAFT)
                    static void revive() { }
                    @DocsGraph.Transition(id = PUBLISH, from = DRAFT, to = PUBLISHED)
                    private Outcome<Doc, DocStatus, DocTransition> rush(Doc doc) { return submit(doc); }
                """));

        assertEquals(3, result.countError("SG504"), result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.contains("reopen") && error.contains("default")),
                result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.contains("revive") && error.contains("static")),
                result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.contains("rush") && error.contains("private")),
                result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
    }

    /**
     * The only exit of a state is declared on a default method. The graph the processor reads therefore misses
     * it, so no conclusion is drawn from that graph as a whole: the author is told where the edge was not read,
     * and not that the state declares no exit.
     */
    @Test
    void drawsNoConclusionFromAGraphThatMissesAMisplacedEdge() {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withDocs(Fixtures.DOCS_HEADER,
                Fixtures.DOCS_BODY.replace("""
                            @DocsGraph.Transition(id = ARCHIVE, from = PUBLISHED, to = ARCHIVED, action = DocActionsRefs.ArchiveAsync.class, timeout = @Timeout(seconds = 2))
                            Outcome<Doc, DocStatus, DocTransition> archive(Doc doc);
                        """, """
                            @DocsGraph.Transition(id = ARCHIVE, from = PUBLISHED, to = ARCHIVED)
                            default Outcome<Doc, DocStatus, DocTransition> archive(Doc doc) { return submit(doc); }
                        """)));

        assertEquals(1, result.countError("SG504"), result.describe());
        assertEquals(1, result.errors().size(), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
    }

    /**
     * The vocabulary of Orders on an abstract operation of Refunds is Refunds' SG606. Orders misses that edge all
     * the same, so it draws no conclusion from its graph as a whole and is not generated either.
     */
    @Test
    void theMachineWhoseVocabularyIsOnAnotherFacadeDrawsNoConclusionFromItsGraph() {
        Compilation.Result result = Compilation.compileTyped(crossed("""
                    @RefundsGraph.Transition(id = RefundTransition.CLOSE, from = RefundStatus.OPEN, to = RefundStatus.DONE)
                    @OrdersGraph.Transition(id = OrderTransition.PAY, from = OrderStatus.NEW, to = OrderStatus.PAID)
                    Outcome<Refund, RefundStatus, RefundTransition> close(Refund refund);
                """));

        assertEquals(1, result.countError("SG606"), result.describe());
        assertEquals(1, result.errors().size(), result.describe());
        assertFalse(result.hasGenerated("shop.OrdersRuntime"), result.describe());
        assertFalse(result.hasGenerated("shop.RefundsRuntime"), result.describe());
    }

    /**
     * On a default method of Refunds, the vocabulary of Orders is refused as foreign, and the diagnostic sends the
     * author to Orders: an abstract operation of Refunds would be refused again, as SG606.
     */
    @Test
    void sg606ForAnotherMachinesVocabularyOnADefaultMethodPointsToThatMachine() {
        Compilation.Result result = Compilation.compileTyped(crossed("""
                    @RefundsGraph.Transition(id = RefundTransition.CLOSE, from = RefundStatus.OPEN, to = RefundStatus.DONE)
                    Outcome<Refund, RefundStatus, RefundTransition> close(Refund refund);
                    @OrdersGraph.Transition(id = OrderTransition.PAY, from = OrderStatus.NEW, to = OrderStatus.PAID)
                    default Outcome<Refund, RefundStatus, RefundTransition> settle(Refund refund) { return close(refund); }
                """));

        assertEquals(1, result.countError("SG606"), result.describe());
        assertEquals(0, result.countError("SG504"), result.describe());
        assertTrue(result.errors().get(0).contains("shop.Orders") && !result.errors().get(0).contains("of Refunds"),
                result.describe());
        assertEquals(1, result.errors().size(), result.describe());
    }

    /** Orders, which declares no operation, and Refunds, whose body is {@code refundsBody}. */
    private static Map<String, String> crossed(String refundsBody) {
        Map<String, String> sources = new java.util.LinkedHashMap<>();
        sources.put("shop.OrderStatus", "package shop;\npublic enum OrderStatus { NEW, PAID }\n");
        sources.put("shop.OrderTransition", "package shop;\npublic enum OrderTransition { PAY }\n");
        sources.put("shop.RefundStatus", "package shop;\npublic enum RefundStatus { OPEN, DONE }\n");
        sources.put("shop.RefundTransition", "package shop;\npublic enum RefundTransition { CLOSE }\n");
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
        sources.put("shop.Refund", """
                package shop;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Refund {
                    @GraphProperty private RefundStatus status = RefundStatus.OPEN;
                    @GraphProperty public String id() { return "r1"; }
                    public RefundStatus status() { return status; }
                    public void status(RefundStatus value) { status = value; }
                }
                """);
        sources.put("shop.Orders", """
                package shop;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                @GenerateGraphApi
                @OrdersGraph.Definition(initial = OrderStatus.NEW, terminals = OrderStatus.PAID, state = OrderRefs.Status.class, id = OrderRefs.Id.class)
                @OrdersGraph.StatePolicy(state = OrderStatus.NEW, otherwise = "a new order is paid")
                public interface Orders extends Machine<Order, OrderStatus, OrderTransition> {
                }
                """);
        sources.put("shop.Refunds", """
                package shop;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                @GenerateGraphApi
                @RefundsGraph.Definition(initial = RefundStatus.OPEN, terminals = RefundStatus.DONE, state = RefundRefs.Status.class, id = RefundRefs.Id.class)
                @RefundsGraph.StatePolicy(state = RefundStatus.OPEN, otherwise = "an open refund is closed")
                public interface Refunds extends Machine<Refund, RefundStatus, RefundTransition> {
                %s}
                """.formatted(refundsBody));
        return sources;
    }

    /**
     * Elsewhere, on an element that is neither a facade nor a method of one, the vocabulary of {@code fx.Docs} is
     * {@code SG606}: on a method of another class, on a type nested in the facade, or on a type that is no facade.
     */
    @Test
    void sg606VocabularyOnAnythingButTheFacade() {
        Map<String, String> sources = Fixtures.withDocs(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY + """
                    @DocsGraph.StatePolicy(state = ARCHIVED, otherwise = "nested")
                    interface Nested { }
                """);
        sources.put("fx.DocService", """
                package fx;
                import io.github.graphmaquina.api.Outcome;
                import static fx.DocStatus.*;
                import static fx.DocTransition.*;
                @DocsGraph.Reject(from = DRAFT, transition = ARCHIVE, reason = "elsewhere")
                public class DocService {
                    @DocsGraph.Transition(id = RETURN, from = PUBLISHED, to = DRAFT)
                    @DocsGraph.Transition(id = RETURN, from = ARCHIVED, to = DRAFT)
                    public Outcome<Doc, DocStatus, DocTransition> misplaced(Doc doc) { return null; }
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(3, result.countError("SG606"), result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.contains("fx.DocService.misplaced")
                && error.contains("Transitions")), result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.contains("fx.DocService")
                && error.contains("Reject")), result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.contains("fx.Docs.Nested")), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
    }
}
