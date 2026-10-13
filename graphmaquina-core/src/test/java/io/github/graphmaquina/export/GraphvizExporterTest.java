package io.github.graphmaquina.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Keys;
import io.github.graphmaquina.fixtures.Order;
import io.github.graphmaquina.fixtures.OrderGraphs;
import io.github.graphmaquina.fixtures.OrderStatus;
import io.github.graphmaquina.fixtures.OrderTransition;
import io.github.graphmaquina.fixtures.RecoveryFixtures;
import io.github.graphmaquina.fixtures.RecoveryFixtures.ProviderRejectedException;
import io.github.graphmaquina.fixtures.RecoveryFixtures.PublicationFailures;
import io.github.graphmaquina.fixtures.RecoveryFixtures.ServiceUnavailableException;
import io.github.graphmaquina.fixtures.RecoveryFixtures.Ticket;
import io.github.graphmaquina.fixtures.RecoveryFixtures.TicketChange;
import io.github.graphmaquina.fixtures.RecoveryFixtures.TicketState;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The Graphviz text of a {@code GraphView}: the golden output of the order graph, what the view carries, parallel
 * edges, terminals and initial states, the escaping of user text, watch notes, and the recovery rules of an edge,
 * which the view keeps in declaration order and the label draws between the guards and the fallback.
 */
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

    /** A catalogue whose four terminal states follow OPEN in one order. */
    enum Ahead { OPEN, DONE, CANCELLED, EXPIRED, REJECTED }

    /** The same state names as {@link Ahead}, with the terminal ones declared the other way round. */
    enum Behind { OPEN, REJECTED, EXPIRED, CANCELLED, DONE }

    /** The identifiers that lead from OPEN to each terminal state, in declaration order. */
    enum Close { FIRST, SECOND, THIRD, FOURTH }

    /** The view of a graph whose first state leads to every other one, each of them terminal. */
    private static <S extends Enum<S>> GraphView everyOtherStateTerminal(Class<S> states) {
        S[] declared = states.getEnumConstants();
        GraphBuilder<Object, S, Close> graph = StateGraph.define(MachineKey.of(Object.class, states, Close.class))
                .initial(declared[0]).statePolicy(declared[0], "closes once");
        for (int i = 1; i < declared.length; i++) {
            graph = graph.terminal(declared[i])
                    .transition(Close.values()[i - 1], declared[0], declared[i]).endTransition();
        }
        return GraphView.of(graph.build());
    }

    /**
     * The terminals keep the node order {@code of} gives them, as the states do. The two catalogues name their
     * terminals alike in opposite orders. No two of these four names collide in the tables that {@code Set.copyOf}
     * fills in JDK 21, so such a copy would iterate them in one order in both views and could not keep both.
     */
    @Test
    void terminalsKeepTheNodeOrderOfTheGraph() {
        GraphView ahead = everyOtherStateTerminal(Ahead.class);
        GraphView behind = everyOtherStateTerminal(Behind.class);
        assertEquals(List.of("OPEN", "DONE", "CANCELLED", "EXPIRED", "REJECTED"), ahead.states());
        assertEquals(List.of("OPEN", "REJECTED", "EXPIRED", "CANCELLED", "DONE"), behind.states());
        assertEquals(List.of(List.of("DONE", "CANCELLED", "EXPIRED", "REJECTED"),
                        List.of("REJECTED", "EXPIRED", "CANCELLED", "DONE")),
                List.of(List.copyOf(ahead.terminals()), List.copyOf(behind.terminals())));
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

    /** Only declared edges are drawn: one arrow per edge of the view, whatever the state policies say. */
    @Test
    void onlyDeclaredEdgesAreEverDrawn() {
        GraphView view = new GraphView("X", "A", Set.of("B"), List.of("A", "B"), List.of(move("A", "GO", "B")),
                List.of(new PolicyView("B", "no way back", List.of("GO: B is final"))), List.of());
        String rendered = GraphvizExporter.render(view);
        assertEquals(1, rendered.lines().filter((String line) -> line.contains("->") && !line.contains("initial ->")).count());
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

    // --- recovery rules -----------------------------------------------------------------------------------

    /** A PUBLISH action that succeeds; most tests below only read the graph. */
    private static final TransitionAction<Ticket, TicketState, TicketChange> PUBLISHES =
            (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> ActionOutcome.ok();

    /** The example's first rule, by the names the view keeps. */
    private static final RecoveryView UNAVAILABLE_RULE = new RecoveryView(
            "io.github.graphmaquina.fixtures.RecoveryFixtures$PublicationFailures$Unavailable", "SCHEDULE_RETRY",
            List.of("io.github.graphmaquina.fixtures.RecoveryFixtures$ServiceUnavailableException"));

    /** The example's second rule, by the names the view keeps. */
    private static final RecoveryView REJECTED_RULE = new RecoveryView(
            "io.github.graphmaquina.fixtures.RecoveryFixtures$PublicationFailures$Rejected", "RECORD_REJECTION",
            List.of("io.github.graphmaquina.fixtures.RecoveryFixtures$ProviderRejectedException"));

    private static final String UNAVAILABLE_FRAGMENT =
            "[recover RecoveryFixtures$PublicationFailures$Unavailable -> SCHEDULE_RETRY]";

    private static final String REJECTED_FRAGMENT =
            "[recover RecoveryFixtures$PublicationFailures$Rejected -> RECORD_REJECTION]";

    /** One rule is one fragment after the action, naming its kind by binary name without the package. */
    @Test
    void oneRuleIsOneFragmentOfItsEdgeLabel() {
        GraphView view = GraphView.of(RecoveryFixtures.graph(
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", PUBLISHES)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY)));
        assertEquals(List.of(new RecoveryView(UNAVAILABLE_RULE.kind(), "SCHEDULE_RETRY", List.of())),
                edge(view, "READY", "PUBLISH").recoveries());
        String rendered = GraphvizExporter.render(view);
        assertTrue(rendered.contains("    n0 -> n1 [label=\"PUBLISH publish " + UNAVAILABLE_FRAGMENT + "\"];\n"),
                rendered);
        assertEquals(5 + view.states().size() + view.transitions().size(), rendered.lines().count(), rendered);
    }

    /**
     * The example's two rules follow the action in the order they were declared and precede the fallback. No
     * throwable type is drawn, neither a rule's nor the failsOn's, no other edge carries a rule, and the rules add
     * no arrow and no node: the text keeps its five fixed lines, one per state and one per declared edge.
     */
    @Test
    void severalRulesKeepTheirDeclarationOrderBeforeTheFallback() {
        GraphView view = GraphView.of(RecoveryFixtures.exampleGraph(PUBLISHES));
        assertEquals(List.of(UNAVAILABLE_RULE, REJECTED_RULE), edge(view, "READY", "PUBLISH").recoveries());
        for (TransitionView other : view.transitions()) {
            if (!other.transition().equals("PUBLISH")) {
                assertEquals(List.of(), other.recoveries(), other.transition());
            }
        }
        String rendered = GraphvizExporter.render(view);
        assertTrue(rendered.contains("    n0 -> n1 [label=\"PUBLISH publish " + UNAVAILABLE_FRAGMENT + " "
                + REJECTED_FRAGMENT + " [onFailure MARK_INTEGRATION_FAILED]\"];\n"), rendered);
        assertFalse(rendered.contains("Exception"), rendered);
        assertEquals(5 + view.states().size() + view.transitions().size(), rendered.lines().count(), rendered);
    }

    /**
     * Review focus 4, for the view: the declared order is kept and it is no priority. The same two rules declared
     * the other way round are viewed and drawn the other way round, while both orders send a returned kind and a
     * thrown type to the same recovery, with the same failure kind and the same state.
     */
    @Test
    void reorderedRulesAreDrawnInTheirNewOrderAndRouteAlike() {
        TransitionAction<Ticket, TicketState, TicketChange> returnsUnavailable =
                (TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                        ActionOutcome.failed(PublicationFailures.Unavailable.class, "provider unavailable");
        TransitionAction<Ticket, TicketState, TicketChange> throwsRejected =
                (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                    throw new ProviderRejectedException("content rejected");
                };
        GraphView declared = GraphView.of(twoRules(PUBLISHES, false));
        GraphView reordered = GraphView.of(twoRules(PUBLISHES, true));
        assertEquals(List.of(UNAVAILABLE_RULE, REJECTED_RULE), edge(declared, "READY", "PUBLISH").recoveries());
        assertEquals(List.of(REJECTED_RULE, UNAVAILABLE_RULE), edge(reordered, "READY", "PUBLISH").recoveries());
        String rendered = GraphvizExporter.render(reordered);
        assertTrue(rendered.contains("    n0 -> n1 [label=\"PUBLISH publish " + REJECTED_FRAGMENT + " "
                + UNAVAILABLE_FRAGMENT + "\"];\n"), rendered);
        for (boolean reversed : List.of(false, true)) {
            String order = reversed ? "reversed" : "declared";
            assertEquals(List.of(TicketChange.SCHEDULE_RETRY, Optional.of(PublicationFailures.Unavailable.class),
                    TicketState.RETRY_PENDING), route(twoRules(returnsUnavailable, reversed)), order);
            assertEquals(List.of(TicketChange.RECORD_REJECTION, Optional.of(PublicationFailures.Rejected.class),
                    TicketState.REJECTED_BY_PROVIDER), route(twoRules(throwsRejected, reversed)), order);
        }
    }

    /**
     * PUBLISH leaves three origins: READY with the example's two rules, RETRY_PENDING with one other rule and
     * INTEGRATION_FAILED with none. Each edge's view and label carry its own rules and no other origin's.
     */
    @Test
    void eachOriginOfAnIdentifierShowsOnlyItsOwnRules() {
        GraphView view = GraphView.of(publishedFromThreeOrigins());
        assertEquals(List.of(UNAVAILABLE_RULE, REJECTED_RULE), edge(view, "READY", "PUBLISH").recoveries());
        assertEquals(List.of(new RecoveryView(REJECTED_RULE.kind(), "RECORD_REJECTION", List.of())),
                edge(view, "RETRY_PENDING", "PUBLISH").recoveries());
        assertEquals(List.of(), edge(view, "INTEGRATION_FAILED", "PUBLISH").recoveries());
        String rendered = GraphvizExporter.render(view);
        assertTrue(rendered.contains("    n0 -> n1 [label=\"PUBLISH publish " + UNAVAILABLE_FRAGMENT + " "
                + REJECTED_FRAGMENT + "\"];\n"), rendered);
        assertTrue(rendered.contains("    n2 -> n1 [label=\"PUBLISH publish " + REJECTED_FRAGMENT + "\"];\n"),
                rendered);
        assertTrue(rendered.contains("    n4 -> n1 [label=\"PUBLISH publish\"];\n"), rendered);
    }

    static Stream<Arguments> recoveryLabels() {
        List<RecoveryView> rules = List.of(new RecoveryView("a.b.First", "R1", List.of("a.b.Thrown")),
                new RecoveryView("Outer$Second", "R2", List.of()));
        String fragments = "[recover First -> R1] [recover Outer$Second -> R2]";
        return Stream.of(
                Arguments.of("every part", labeled(List.of("g1", "g2"), true, Optional.of("F"),
                        Optional.of("Peer: c"), rules),
                        "GO act [async] [if g1, g2] " + fragments + " [onFailure F] [watches Peer: c]"),
                Arguments.of("rules alone", labeled(List.of(), false, Optional.empty(), Optional.empty(), rules),
                        "GO act " + fragments),
                Arguments.of("guards without a fallback", labeled(List.of("g1"), false, Optional.empty(),
                        Optional.empty(), rules), "GO act [if g1] " + fragments),
                Arguments.of("a fallback without guards", labeled(List.of(), false, Optional.of("F"),
                        Optional.empty(), rules), "GO act " + fragments + " [onFailure F]"),
                Arguments.of("a watch without a fallback", labeled(List.of(), false, Optional.empty(),
                        Optional.of("Peer: c"), rules), "GO act " + fragments + " [watches Peer: c]"),
                Arguments.of("no rules", labeled(List.of("g1", "g2"), true, Optional.of("F"),
                        Optional.of("Peer: c"), List.of()),
                        "GO act [async] [if g1, g2] [onFailure F] [watches Peer: c]"));
    }

    /**
     * The fragments sit between the guards and the fallback whichever other parts the edge has, the kind keeps
     * its nested names after the last dot, and a kind with no package is shown whole.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("recoveryLabels")
    void theRuleFragmentsSitBetweenTheGuardsAndTheFallback(String caseName, TransitionView transition,
            String label) {
        assertEquals(singleEdgeDot(baseQuote(label)), GraphvizExporter.render(singleEdgeView(transition)));
    }

    /**
     * A rule's kind and recovery identifier are escaped with the rest of the label, like the oracle below. Each
     * corpus text follows a package, so the fragment shows it whole, and the rule's throwable type is not drawn.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("dotEscapingCorpus")
    void aRecoveryFragmentIsEscapedLikeTheRestOfTheLabel(String caseName, String text) {
        TransitionView transition = labeled(List.of(), false, Optional.empty(), Optional.empty(),
                List.of(new RecoveryView("some.pkg." + text, text, List.of("some.pkg.Hidden" + text))));
        assertEquals(singleEdgeDot(baseQuote("GO act [recover " + text + " -> " + text + "]")),
                GraphvizExporter.render(singleEdgeView(transition)));
    }

    /**
     * Without rules nothing changes: the nine-argument view is the view without rules, its label is the one this
     * exporter has always drawn, and no edge of a graph that declares no rule views one.
     */
    @Test
    void anEdgeWithoutRulesIsDrawnAsBefore() {
        TransitionView nine = new TransitionView("A", "GO", "B", TransitionKind.ACTION, Optional.of("act"),
                List.of("g1", "g2"), true, Optional.of("F"), Optional.of("Peer: c"));
        assertEquals(labeled(List.of("g1", "g2"), true, Optional.of("F"), Optional.of("Peer: c"), List.of()), nine);
        assertEquals(singleEdgeDot("\"GO act [async] [if g1, g2] [onFailure F] [watches Peer: c]\""),
                GraphvizExporter.render(singleEdgeView(nine)));
        for (TransitionView edge : GraphView.of(orderGraph()).transitions()) {
            assertEquals(List.of(), edge.recoveries(), edge.transition());
        }
    }

    /** A view copies the rules and the types it is given and refuses a missing one, as it does its guards. */
    @Test
    void theViewsCopyTheirRulesAndRefuseNulls() {
        List<String> types = new ArrayList<>(List.of("a.b.Thrown"));
        RecoveryView rule = new RecoveryView("a.b.Kind", "R", types);
        List<RecoveryView> rules = new ArrayList<>(List.of(rule));
        TransitionView transition = labeled(List.of(), false, Optional.empty(), Optional.empty(), rules);
        types.add("a.b.Other");
        rules.clear();
        assertEquals(List.of("a.b.Thrown"), rule.failsOn());
        assertEquals(List.of(rule), transition.recoveries());
        assertThrows(NullPointerException.class, () -> new RecoveryView(null, "R", List.of()));
        assertThrows(NullPointerException.class,
                () -> new RecoveryView("a.b.Kind", "R", Arrays.asList("a.b.Thrown", null)));
        assertThrows(NullPointerException.class,
                () -> labeled(List.of(), false, Optional.empty(), Optional.empty(), null));
    }

    /** The action edge GO from A to B with the given parts and the action {@code act}. */
    private static TransitionView labeled(List<String> guards, boolean async, Optional<String> failureTransition,
            Optional<String> watch, List<RecoveryView> recoveries) {
        return new TransitionView("A", "GO", "B", TransitionKind.ACTION, Optional.of("act"), guards, async,
                failureTransition, watch, recoveries);
    }

    /** A graph of A and B whose only edge is {@code transition}, from A to the terminal B. */
    private static GraphView singleEdgeView(TransitionView transition) {
        return new GraphView("X", "A", Set.of("B"), List.of("A", "B"), List.of(transition), List.of(), List.of());
    }

    /** The whole text {@link #singleEdgeView} renders to, given its edge's label already quoted. */
    private static String singleEdgeDot(String quotedLabel) {
        return "digraph \"X\" {\n"
                + "    rankdir=LR;\n"
                + "    initial [shape=point, label=\"\"];\n"
                + "    n0 [label=\"A\", shape=ellipse];\n"
                + "    n1 [label=\"B\", shape=doublecircle];\n"
                + "    initial -> n0;\n"
                + "    n0 -> n1 [label=" + quotedLabel + "];\n"
                + "}\n";
    }

    /** The edge of {@code view} keyed by {@code (from, transition)}. */
    private static TransitionView edge(GraphView view, String from, String transition) {
        return view.transitions().stream()
                .filter((TransitionView candidate) -> candidate.from().equals(from)
                        && candidate.transition().equals(transition))
                .findFirst().orElseThrow();
    }

    /** PUBLISH with {@code action} and the example's two rules, declared in that order or the other way round. */
    private static GraphDefinition<Ticket, TicketState, TicketChange> twoRules(
            TransitionAction<Ticket, TicketState, TicketChange> action, boolean reversed) {
        return RecoveryFixtures.graph((TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> reversed
                ? publish.action("publish", action)
                        .recover(PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION,
                                ProviderRejectedException.class)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                ServiceUnavailableException.class)
                : publish.action("publish", action)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                ServiceUnavailableException.class)
                        .recover(PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION,
                                ProviderRejectedException.class));
    }

    /** Where PUBLISH sends a READY ticket of {@code graph}: the recovery, the failure kind and the state written. */
    private static List<Object> route(GraphDefinition<Ticket, TicketState, TicketChange> graph) {
        Ticket ticket = new Ticket("t-1");
        Outcome.Failed<Ticket, TicketState, TicketChange> failed = RecoveryFixtures.machine(graph)
                .execute(ticket, TicketChange.PUBLISH).asFailed().orElseThrow();
        return List.of(failed.failureTransition(), failed.failureKind(), ticket.state);
    }

    /**
     * PUBLISH leaves READY with the example's two rules, RETRY_PENDING with one rule of its own and
     * INTEGRATION_FAILED with none; RECORD_REJECTION also leaves RETRY_PENDING, so that rule has its route.
     */
    private static GraphDefinition<Ticket, TicketState, TicketChange> publishedFromThreeOrigins() {
        return StateGraph.define(RecoveryFixtures.KEY)
                .initial(TicketState.READY).terminal(TicketState.PUBLISHED).terminal(TicketState.CLOSED)
                .allowsCycle(TicketState.READY)
                .statePolicy(TicketState.READY, "publish the ticket")
                .statePolicy(TicketState.RETRY_PENDING, "retry the publication explicitly")
                .statePolicy(TicketState.REJECTED_BY_PROVIDER, "close the rejected ticket")
                .statePolicy(TicketState.INTEGRATION_FAILED, "close after manual review")
                .transition(TicketChange.PUBLISH, TicketState.READY, TicketState.PUBLISHED)
                    .action("publish", PUBLISHES)
                    .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                            ServiceUnavailableException.class)
                    .recover(PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION,
                            ProviderRejectedException.class)
                    .endTransition()
                .transition(TicketChange.PUBLISH, TicketState.RETRY_PENDING, TicketState.PUBLISHED)
                    .action("publish", PUBLISHES)
                    .recover(PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION)
                    .endTransition()
                .transition(TicketChange.PUBLISH, TicketState.INTEGRATION_FAILED, TicketState.PUBLISHED)
                    .action("publish", PUBLISHES).endTransition()
                .transition(TicketChange.SCHEDULE_RETRY, TicketState.READY, TicketState.RETRY_PENDING).endTransition()
                .transition(TicketChange.RECORD_REJECTION, TicketState.READY, TicketState.REJECTED_BY_PROVIDER)
                    .endTransition()
                .transition(TicketChange.RECORD_REJECTION, TicketState.RETRY_PENDING, TicketState.REJECTED_BY_PROVIDER)
                    .endTransition()
                .transition(TicketChange.MARK_INTEGRATION_FAILED, TicketState.READY, TicketState.INTEGRATION_FAILED)
                    .endTransition()
                .transition(TicketChange.RETRY, TicketState.RETRY_PENDING, TicketState.READY).endTransition()
                .transition(TicketChange.CLOSE, TicketState.REJECTED_BY_PROVIDER, TicketState.CLOSED).endTransition()
                .transition(TicketChange.CLOSE, TicketState.INTEGRATION_FAILED, TicketState.CLOSED).endTransition()
                .build();
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
