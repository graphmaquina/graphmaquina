package io.github.graphmaquina.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Keys;
import io.github.graphmaquina.fixtures.Order;
import io.github.graphmaquina.fixtures.OrderGraphs;
import io.github.graphmaquina.fixtures.OrderStatus;
import io.github.graphmaquina.fixtures.OrderTransition;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GraphvizExporterTest {

    private static String golden(String name) throws IOException {
        try (InputStream in = GraphvizExporterTest.class.getResourceAsStream("/golden/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static GraphDefinition<Order, OrderStatus, OrderTransition> orderGraph() {
        return OrderGraphs.order(
                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                        CompletableFuture.completedFuture(ActionOutcome.ok()),
                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass(),
                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass());
    }

    @Test
    void realEdgesMatchGolden() throws IOException {
        assertEquals(golden("order.dot"), GraphvizExporter.render(GraphView.of(orderGraph())));
    }

    @Test
    void viewCarriesNamesRoutesPoliciesAndWatches() {
        GraphView view = GraphView.of(orderGraph());
        assertEquals("Order/OrderStatus", view.name());
        assertEquals("NEW", view.initial());
        assertEquals(Set.of("SHIPPED", "CANCELLED"), view.terminals());
        assertEquals(List.of("NEW", "PAID", "PAYMENT_DECLINED", "SHIPPED", "CANCELLED"), view.states());
        TransitionView charge = view.transitions().get(0);
        assertEquals("CHARGE", charge.transition());
        assertEquals(Optional.of("DECLINE"), charge.failureTransition());
        assertTrue(charge.async());
        TransitionView ship = view.transitions().get(3);
        assertEquals("SHIP", ship.transition());
        assertEquals(Optional.of("Item: allIn PACKED"), ship.watch());
        assertEquals(List.of(new WatchView("PAID", "Item", List.of("allIn PACKED → SHIP"))), view.watches());
        assertEquals("cannot ship without payment", view.policies().get(0).otherwise());
        assertEquals("terminal state SHIPPED accepts no transitions", view.policies().get(3).otherwise());
    }

    /** Two edges between the same pair are two arrows, one per identifier. */
    @Test
    void parallelEdgesAreBothDrawn() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(Keys.DOCS)
                .initial(DocStatus.DRAFT).terminal(DocStatus.ARCHIVED)
                .statePolicy(DocStatus.DRAFT, "a draft goes forward or is archived")
                .statePolicy(DocStatus.REVIEW, "review only publishes")
                .statePolicy(DocStatus.PUBLISHED, "published only archives")
                .transition(DocTransition.SUBMIT, DocStatus.DRAFT, DocStatus.REVIEW)
                    .action("A", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ActionOutcome.ok())
                    .onFailure(DocTransition.DISCARD).endTransition()
                .transition(DocTransition.PUBLISH, DocStatus.DRAFT, DocStatus.REVIEW)
                    .action("B", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ActionOutcome.ok())
                    .onFailure(DocTransition.DISCARD).endTransition()
                .transition(DocTransition.DISCARD, DocStatus.DRAFT, DocStatus.ARCHIVED).endTransition()
                .transition(DocTransition.SEND_BACK, DocStatus.REVIEW, DocStatus.PUBLISHED).endTransition()
                .transition(DocTransition.ARCHIVE, DocStatus.PUBLISHED, DocStatus.ARCHIVED).endTransition()
                .build();
        String rendered = GraphvizExporter.render(GraphView.of(graph));
        assertTrue(rendered.contains("[label=\"SUBMIT A [onFailure DISCARD]\"]"), rendered);
        assertTrue(rendered.contains("[label=\"PUBLISH B [onFailure DISCARD]\"]"), rendered);
    }

    @Test
    void terminalWithNoOutgoingEdgesIsDeclared() {
        GraphView view = new GraphView("X", "A", Set.of("B"), List.of("A", "B"), List.of(move("A", "GO", "B")),
                List.of(), List.of());
        assertEquals("""
                digraph "X" {
                    rankdir=LR;
                    initial [shape=point, label=""];
                    n0 [label="A", shape=ellipse];
                    n1 [label="B", shape=doublecircle];
                    initial -> n0;
                    n0 -> n1 [label="GO"];
                }
                """, GraphvizExporter.render(view));
    }

    @Test
    void nonFirstInitialStateDoesNotReorderTheStates() {
        GraphView view = new GraphView("X", "B", Set.of("B"), List.of("A", "B"), List.of(move("A", "GO", "B")),
                List.of(), List.of());
        assertEquals("""
                digraph "X" {
                    rankdir=LR;
                    initial [shape=point, label=""];
                    n0 [label="A", shape=ellipse];
                    n1 [label="B", shape=doublecircle];
                    initial -> n1;
                    n0 -> n1 [label="GO"];
                }
                """, GraphvizExporter.render(view));
    }

    /** Nothing that was not declared is ever drawn: there is no mode that expands absent edges. */
    @Test
    void onlyDeclaredEdgesAreEverDrawn() {
        GraphView view = new GraphView("X", "A", Set.of("B"), List.of("A", "B"), List.of(move("A", "GO", "B")),
                List.of(new PolicyView("B", "no way back", List.of("GO: B is final"))), List.of());
        String rendered = GraphvizExporter.render(view);
        assertEquals(1, rendered.lines().filter((String line) -> line.contains("->") && !line.contains("initial ->")).count());
        assertFalse(rendered.contains("stay"), rendered);
        assertFalse(rendered.contains("forbid"), rendered);
        assertTrue(rendered.contains("n1 [label=\"B\", shape=doublecircle, tooltip=\"otherwise: no way back; GO: B is final\"];"),
                rendered);
    }

    @Test
    void isolatedStatesAndUnorderedTerminalsAreAlwaysDeclaredDeterministically() {
        GraphView view = new GraphView("X", "initial", Set.of("Z", "B"), List.of("island"), List.of(),
                List.of(), List.of(new WatchView("watch0", "Peer", List.of())));
        assertEquals("""
                digraph "X" {
                    rankdir=LR;
                    initial [shape=point, label=""];
                    n0 [label="island", shape=ellipse];
                    n1 [label="initial", shape=ellipse];
                    n2 [label="B", shape=doublecircle];
                    n3 [label="Z", shape=doublecircle];
                    n4 [label="watch0", shape=ellipse];
                    initial -> n1;
                    watch0 [shape=note, label="watches Peer"];
                    n4 -> watch0 [style=dashed, arrowhead=none];
                }
                """, GraphvizExporter.render(view));
    }

    @Test
    void initialCanAlsoBeTerminalWithoutAnyEdges() {
        String rendered = GraphvizExporter.render(
                new GraphView("X", "done", Set.of("done"), List.of(), List.of(), List.of(), List.of()));
        assertTrue(rendered.contains("n0 [label=\"done\", shape=doublecircle];"), rendered);
        assertTrue(rendered.contains("initial -> n0;"), rendered);
    }

    @Test
    void userTextIsEscapedAndNeverUsedAsNodeIds() {
        String special = "quote\" slash\\N\r\nnext\rline\n雪";
        String escaped = "quote\\\" slash\\\\N\\nnext\\nline\\n雪";
        GraphView view = new GraphView(special, special, Set.of("n0"), List.of(special, "n0"), List.of(
                new TransitionView(special, special, "n0", TransitionKind.ACTION, Optional.of(special), List.of(special),
                        true, Optional.of(special), Optional.of(special))),
                List.of(new PolicyView("n0", special, List.of())),
                List.of(new WatchView(special, special, List.of())));
        String rendered = GraphvizExporter.render(view);
        assertTrue(rendered.startsWith("digraph \"" + escaped + "\" {\n"), rendered);
        assertTrue(rendered.contains("n0 [label=\"" + escaped + "\", shape=ellipse];"), rendered);
        assertTrue(rendered.contains("n1 [label=\"n0\", shape=doublecircle, tooltip=\"otherwise: " + escaped + "\"];"),
                rendered);
        assertTrue(rendered.contains("n0 -> n1 [label=\"" + escaped + " " + escaped + " [async] [if " + escaped
                + "] [onFailure " + escaped + "] [watches " + escaped + "]\"];"), rendered);
        assertTrue(rendered.contains("watch0 [shape=note, label=\"watches " + escaped + "\"];"), rendered);
        assertFalse(rendered.contains("\r"), rendered);
    }

    @Test
    void multipleWatchesHaveSeparateNotes() {
        GraphView view = new GraphView("X", "A", Set.of(), List.of("A"), List.of(), List.of(), List.of(
                new WatchView("A", "Peer1", List.of()), new WatchView("A", "Peer2", List.of())));
        String rendered = GraphvizExporter.render(view);
        assertTrue(rendered.contains("watch0 [shape=note, label=\"watches Peer1\"];"), rendered);
        assertTrue(rendered.contains("watch1 [shape=note, label=\"watches Peer2\"];"), rendered);
        assertTrue(rendered.contains("n0 -> watch0 [style=dashed, arrowhead=none];"), rendered);
        assertTrue(rendered.contains("n0 -> watch1 [style=dashed, arrowhead=none];"), rendered);
    }

    // --- DOT escaping oracle (quote, restored to 278640e) -------------------------------------------------

    /**
     * Verbatim copy of {@link GraphvizExporter}'s {@code quote} helper at 278640e, kept as an oracle independent
     * of production so the full public {@code render} is checked byte for byte against it for every corpus case.
     */
    private static String baseQuote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n") + "\"";
    }

    static Stream<Arguments> dotEscapingCorpus() {
        return Stream.of(
                Arguments.of("empty", ""),
                Arguments.of("quotes", "say \"hi\" now"),
                Arguments.of("backslashes", "a\\b\\\\c"),
                Arguments.of("literal backslash-N", "before\\Nafter"),
                Arguments.of("CR alone", "a\rb"),
                Arguments.of("LF alone", "a\nb"),
                Arguments.of("CRLF", "a\r\nb"),
                Arguments.of("LFCR", "a\n\rb"),
                Arguments.of("mixed triples", "a\r\n\rb\r\r\nc\n\r\nd\n\n\re"),
                Arguments.of("unicode", "café 雪 😀"),
                Arguments.of("control characters", "a\u0000\u0001\u001fb\u007f"));
    }

    /**
     * Differential test kept from the withdrawn T10 experiment: production {@code quote} must escape exactly like
     * this oracle through the full public {@code render}. {@code text} is the only thing that varies per case;
     * every other byte of the expected DOT is fixed by this view's shape, so a mismatch points at the escaping.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("dotEscapingCorpus")
    void quoteMatchesTheInlineOracleThroughFullRender(String caseName, String text) {
        String rawTooltip = "otherwise: " + text;
        String rawLabel = text + " " + text + " [async] [if " + text + "] [onFailure " + text
                + "] [watches " + text + "]";
        String rawWatchLabel = "watches " + text;
        GraphView view = new GraphView(text, text, Set.of("n0"), List.of(text, "n0"),
                List.of(new TransitionView(text, text, "n0", TransitionKind.ACTION, Optional.of(text),
                        List.of(text), true, Optional.of(text), Optional.of(text))),
                List.of(new PolicyView("n0", text, List.of())),
                List.of(new WatchView(text, text, List.of())));

        String rendered = GraphvizExporter.render(view);

        assertEquals("digraph " + baseQuote(text) + " {\n"
                + "    rankdir=LR;\n"
                + "    initial [shape=point, label=\"\"];\n"
                + "    n0 [label=" + baseQuote(text) + ", shape=ellipse];\n"
                + "    n1 [label=\"n0\", shape=doublecircle, tooltip=" + baseQuote(rawTooltip) + "];\n"
                + "    initial -> n0;\n"
                + "    n0 -> n1 [label=" + baseQuote(rawLabel) + "];\n"
                + "    watch0 [shape=note, label=" + baseQuote(rawWatchLabel) + "];\n"
                + "    n0 -> watch0 [style=dashed, arrowhead=none];\n"
                + "}\n", rendered);
    }

    private static TransitionView move(String from, String transition, String to) {
        return new TransitionView(from, transition, to, TransitionKind.MOVE, Optional.empty(), List.of(), false,
                Optional.empty(), Optional.empty());
    }
}
