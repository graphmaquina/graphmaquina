package io.github.graphmaquina.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionGuard;
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
import java.util.Map;
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
 * edges, terminals and initial states, the escaping of user text, watch notes, the recovery rules of an edge, which
 * the view keeps in declaration order and the label draws between the guards and the fallback, and the colors of a
 * theme, each of which reaches the text only through its role.
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
        assertTrue(rendered.contains("[label=" + html("SUBMIT", "A", "[onFailure DISCARD]") + "]"), rendered);
        assertTrue(rendered.contains("[label=" + html("PUBLISH", "B", "[onFailure DISCARD]") + "]"), rendered);
    }

    @Test
    void terminalWithNoOutgoingEdgesIsDeclared() {
        GraphView view = new GraphView("X", "A", Set.of("B"), List.of("A", "B"), List.of(move("A", "GO", "B")),
                List.of(), List.of());
        assertEquals(opening("X")
                + "    n0 [label=\"A\"];\n"
                + "    n1 [label=\"B\"" + TERMINAL + "];\n"
                + "    initial -> n0;\n"
                + "    n0 -> n1 [label=<<b>GO</b>>];\n"
                + "}\n", GraphvizExporter.render(view));
    }

    @Test
    void nonFirstInitialStateDoesNotReorderTheStates() {
        GraphView view = new GraphView("X", "B", Set.of("B"), List.of("A", "B"), List.of(move("A", "GO", "B")),
                List.of(), List.of());
        assertEquals(opening("X")
                + "    n0 [label=\"A\"];\n"
                + "    n1 [label=\"B\"" + TERMINAL + "];\n"
                + "    initial -> n1;\n"
                + "    n0 -> n1 [label=<<b>GO</b>>];\n"
                + "}\n", GraphvizExporter.render(view));
    }

    /** Only declared edges are drawn: one arrow per edge of the view, whatever the state policies say. */
    @Test
    void onlyDeclaredEdgesAreEverDrawn() {
        GraphView view = new GraphView("X", "A", Set.of("B"), List.of("A", "B"), List.of(move("A", "GO", "B")),
                List.of(new PolicyView("B", "no way back", List.of("GO: B is final"))), List.of());
        String rendered = GraphvizExporter.render(view);
        assertEquals(1,
                rendered.lines().filter((String line) -> line.contains("->") && !line.contains("initial ->")).count());
        assertTrue(rendered.contains(
                "n1 [label=\"B\"" + TERMINAL + ", tooltip=\"otherwise: no way back; GO: B is final\"];"),
                rendered);
    }

    @Test
    void isolatedStatesAndUnorderedTerminalsAreAlwaysDeclaredDeterministically() {
        GraphView view = new GraphView("X", "initial", Set.of("Z", "B"), List.of("island"), List.of(),
                List.of(), List.of(new WatchView("watch0", "Peer", List.of())));
        assertEquals(opening("X")
                + "    n0 [label=\"island\"];\n"
                + "    n1 [label=\"initial\"];\n"
                + "    n2 [label=\"B\"" + TERMINAL + "];\n"
                + "    n3 [label=\"Z\"" + TERMINAL + "];\n"
                + "    n4 [label=\"watch0\"];\n"
                + "    initial -> n1;\n"
                + "    watch0 [" + NOTE + ", label=\"watches Peer\"];\n"
                + "    n4 -> watch0 [" + DASHED + "];\n"
                + "}\n", GraphvizExporter.render(view));
    }

    @Test
    void initialCanAlsoBeTerminalWithoutAnyEdges() {
        String rendered = GraphvizExporter.render(
                new GraphView("X", "done", Set.of("done"), List.of(), List.of(), List.of(), List.of()));
        assertTrue(rendered.contains("n0 [label=\"done\"" + TERMINAL + "];"), rendered);
        assertTrue(rendered.contains("initial -> n0;"), rendered);
    }

    @Test
    void userTextIsEscapedAndNeverUsedAsNodeIds() {
        String special = "quote\" slash\\N\r\nnext\rline\n雪";
        String escaped = "quote\\\" slash\\\\N\\nnext\\nline\\n雪";
        String inHtml = "quote&quot; slash\\N" + BR + "next" + BR + "line" + BR + "雪";
        GraphView view = new GraphView(special, special, Set.of("n0"), List.of(special, "n0"), List.of(
                new TransitionView(special, special, "n0", TransitionKind.ACTION, Optional.of(special),
                        List.of(special), true, Optional.of(special), Optional.of(special))),
                List.of(new PolicyView("n0", special, List.of())),
                List.of(new WatchView(special, special, List.of())));
        String rendered = GraphvizExporter.render(view);
        assertTrue(rendered.startsWith("digraph \"" + escaped + "\" {\n"), rendered);
        assertTrue(rendered.contains("label=<<b>" + inHtml + "</b>>];\n"), rendered);
        assertTrue(rendered.contains("n0 [label=\"" + escaped + "\"];"), rendered);
        assertTrue(rendered.contains("n1 [label=\"n0\"" + TERMINAL + ", tooltip=\"otherwise: " + escaped + "\"];"),
                rendered);
        assertTrue(rendered.contains("n0 -> n1 [label=" + html(inHtml, inHtml, "[async]", "[if " + inHtml + "]",
                "[onFailure " + inHtml + "]", "[watches " + inHtml + "]") + "];"), rendered);
        assertTrue(rendered.contains("watch0 [" + NOTE + ", label=\"watches " + escaped + "\"];"), rendered);
        assertFalse(rendered.contains("\r"), rendered);
    }

    /**
     * The four XML specials of an HTML-like label are entities wherever user text enters one, the arrow of a recovery
     * part included, so Graphviz never drops a label; quoted strings keep them as they are.
     */
    @Test
    void xmlSpecialsAreEntitiesInHtmlLabels() {
        String special = "A<&>\"B";
        String entities = "A&lt;&amp;&gt;&quot;B";
        GraphView view = new GraphView(special, special, Set.of("n0"), List.of(special, "n0"), List.of(
                new TransitionView(special, special, "n0", TransitionKind.ACTION, Optional.of(special),
                        List.of(special), false, Optional.empty(), Optional.empty(),
                        List.of(new RecoveryView("p." + special, special, List.of(), false)), false,
                        Optional.empty(), false)),
                List.of(), List.of());
        String rendered = GraphvizExporter.render(view);
        assertTrue(rendered.contains("label=<<b>" + entities + "</b>>];\n"), rendered);
        assertTrue(rendered.contains("n0 [label=\"A<&>\\\"B\"];"), rendered);
        assertTrue(rendered.contains("n0 -> n1 [label=" + html(entities, entities, "[if " + entities + "]",
                "[recover " + entities + " -&gt; " + entities + "]") + "];"), rendered);
    }

    /**
     * Graphviz refuses an empty bold element and would drop the whole label, so an empty title, identifier or bound
     * state is left out of the label rather than drawn in bold; the rest of the label stays.
     */
    @Test
    void emptyTextIsLeftOutRatherThanDrawnInBold() {
        GraphView child = new GraphView("Child/ChildStatus", "C", Set.of("D"), List.of("C", "D"),
                List.of(move("C", "", "D")), List.of(), List.of());
        TransitionView acting = new TransitionView("A", "", "", TransitionKind.ACTION, Optional.of("act"), List.of(),
                false, Optional.empty(), Optional.empty());
        GraphView view = new GraphView("", "A", Set.of(), List.of("A", ""), List.of(acting), List.of(), List.of(),
                List.of(new SubmachineView("", child, Map.of())));
        String rendered = GraphvizExporter.render(view);
        assertTrue(rendered.contains("labeljust=l, label=<>];\n"), rendered);
        assertTrue(rendered.contains("        label=<<font color=\"#4f5677\">: Child/ChildStatus</font>>;\n"),
                rendered);
        assertTrue(rendered.contains("s0_n0 -> s0_n1 [label=<>];"), rendered);
        assertTrue(rendered.contains("n0 -> s0_n0 [label=<" + BR + "<font color=\"#4f5677\">act" + BR
                + "</font>>, lhead=cluster_s0];"), rendered);
        assertFalse(rendered.contains("<b></b>"), rendered);
    }

    /** A bound state and its child's name are escaped in the cluster's title like every other label text. */
    @Test
    void aClusterTitleIsEscaped() {
        GraphView child = new GraphView("C&\"D", "C", Set.of(), List.of("C"), List.of(), List.of(), List.of());
        GraphView view = new GraphView("X", "A<&>B", Set.of(), List.of("A<&>B"), List.of(), List.of(), List.of(),
                List.of(new SubmachineView("A<&>B", child, Map.of())));
        assertTrue(GraphvizExporter.render(view).contains(
                "label=<<b>A&lt;&amp;&gt;B</b><font color=\"#4f5677\">: C&amp;&quot;D</font>>;\n"),
                GraphvizExporter.render(view));
    }

    @Test
    void multipleWatchesHaveSeparateNotes() {
        GraphView view = new GraphView("X", "A", Set.of(), List.of("A"), List.of(), List.of(), List.of(
                new WatchView("A", "Peer1", List.of()), new WatchView("A", "Peer2", List.of())));
        String rendered = GraphvizExporter.render(view);
        assertTrue(rendered.contains("watch0 [" + NOTE + ", label=\"watches Peer1\"];"), rendered);
        assertTrue(rendered.contains("watch1 [" + NOTE + ", label=\"watches Peer2\"];"), rendered);
        assertTrue(rendered.contains("n0 -> watch0 [" + DASHED + "];"), rendered);
        assertTrue(rendered.contains("n0 -> watch1 [" + DASHED + "];"), rendered);
    }

    /**
     * Every color of a drawing comes from its theme, each through its role: with nine colors of its own, none of
     * the defaults remains, and each one sits where its role says.
     */
    @Test
    void everyColorComesFromItsRoleInTheTheme() {
        GraphvizTheme theme = new GraphvizTheme("#000001", "#000002", "#000003", "#000004", "#000005", "#000006",
                "#000007", "#000008", "#000009");
        GraphView child = new GraphView("Child/ChildStatus", "C", Set.of("D"), List.of("C", "D"),
                List.of(move("C", "END", "D")), List.of(), List.of());
        TransitionView automatic = new TransitionView("A", "GO", "B", TransitionKind.ACTION, Optional.of("act"),
                List.of(), false, Optional.empty(), Optional.empty(), List.of(), true, Optional.empty(), false);
        GraphView view = new GraphView("X", "A", Set.of("D2"), List.of("A", "B", "D2"),
                List.of(automatic, move("B", "DONE", "D2")), List.of(),
                List.of(new WatchView("A", "Peer", List.of())),
                List.of(new SubmachineView("B", child, Map.of("D", "DONE"))));
        String rendered = GraphvizExporter.render(view, theme);
        for (String color : List.of("#ffffff", "#1b1f3a", "#4f5677", "#2457d6", "#f5f6fb", "#d4d8ea", "#a85a00",
                "#fff6e5")) {
            assertFalse(rendered.contains(color), color + "\n" + rendered);
        }
        assertTrue(rendered.contains("graph [rankdir=LR, bgcolor=\"#000001\","), rendered);
        assertTrue(rendered.contains("fontsize=12, fontcolor=\"#000002\", labelloc=t"), rendered);
        assertTrue(rendered.contains("node [shape=box, style=\"rounded,filled\", color=\"#000002\", "
                + "fillcolor=\"#000004\", fontcolor=\"#000002\","), rendered);
        assertTrue(rendered.contains("edge [color=\"#000003\", fontcolor=\"#000002\","), rendered);
        assertTrue(rendered.contains("initial [shape=point, width=0.14, height=0.14, fillcolor=\"#000002\", "
                + "label=\"\"];"), rendered);
        assertTrue(rendered.contains("[label=\"D2\", peripheries=2, fillcolor=\"#000002\", fontcolor=\"#000004\"]"),
                rendered);
        assertTrue(rendered.contains("<font color=\"#000003\">[auto]" + BR + "act" + BR + "</font>>, "
                + "color=\"#000005\", fontcolor=\"#000005\""), rendered);
        assertTrue(rendered.contains("label=<<b>B</b><font color=\"#000003\">: Child/ChildStatus</font>>;\n"),
                rendered);
        assertTrue(rendered.contains("graph [style=\"rounded,filled\", color=\"#000007\", fillcolor=\"#000006\", "
                + "fontsize=11, penwidth=1.2];"), rendered);
        assertTrue(rendered.contains("watch0 [shape=note, style=filled, color=\"#000008\", fillcolor=\"#000009\", "
                + "fontcolor=\"#000008\", label=\"watches Peer\"];"), rendered);
        assertTrue(rendered.contains("n0 -> watch0 [style=dashed, arrowhead=none, color=\"#000008\"];"), rendered);
    }

    // --- recovery rules -----------------------------------------------------------------------------------

    /** A PUBLISH action that succeeds; most tests below only read the graph. */
    private static final TransitionAction<Ticket, TicketState, TicketChange> PUBLISHES =
            (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> ActionOutcome.ok();

    /** The example's first rule, by the names the view keeps. */
    private static final RecoveryView UNAVAILABLE_RULE = new RecoveryView(
            "io.github.graphmaquina.fixtures.RecoveryFixtures$PublicationFailures$Unavailable", "SCHEDULE_RETRY",
            List.of("io.github.graphmaquina.fixtures.RecoveryFixtures$ServiceUnavailableException"), false);

    /** The example's second rule, by the names the view keeps. */
    private static final RecoveryView REJECTED_RULE = new RecoveryView(
            "io.github.graphmaquina.fixtures.RecoveryFixtures$PublicationFailures$Rejected", "RECORD_REJECTION",
            List.of("io.github.graphmaquina.fixtures.RecoveryFixtures$ProviderRejectedException"), false);

    private static final String UNAVAILABLE_FRAGMENT =
            "[recover RecoveryFixtures$PublicationFailures$Unavailable -&gt; SCHEDULE_RETRY]";

    private static final String REJECTED_FRAGMENT =
            "[recover RecoveryFixtures$PublicationFailures$Rejected -&gt; RECORD_REJECTION]";

    /** One rule is one line after the action, naming its kind by binary name without the package. */
    @Test
    void oneRuleIsOneFragmentOfItsEdgeLabel() {
        GraphView view = GraphView.of(RecoveryFixtures.graph(
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", PUBLISHES)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY)));
        assertEquals(List.of(new RecoveryView(UNAVAILABLE_RULE.kind(), "SCHEDULE_RETRY", List.of(), false)),
                edge(view, "READY", "PUBLISH").recoveries());
        String rendered = GraphvizExporter.render(view);
        assertTrue(rendered.contains("    n0 -> n1 [label=" + html("PUBLISH", "publish", UNAVAILABLE_FRAGMENT)
                + "];\n"), rendered);
        assertEquals(7 + view.states().size() + view.transitions().size(), rendered.lines().count(), rendered);
    }

    /**
     * The example's two rules follow the action in the order they were declared and precede the fallback. No
     * throwable type is drawn, neither a rule's nor the failsOn's, no other edge carries a rule, and the rules add
     * no arrow and no node: the text keeps its seven fixed lines, one per state and one per declared edge.
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
        assertTrue(rendered.contains("    n0 -> n1 [label=" + html("PUBLISH", "publish", UNAVAILABLE_FRAGMENT,
                REJECTED_FRAGMENT, "[onFailure MARK_INTEGRATION_FAILED]") + "];\n"), rendered);
        assertFalse(rendered.contains("Exception"), rendered);
        assertEquals(7 + view.states().size() + view.transitions().size(), rendered.lines().count(), rendered);
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
        assertTrue(rendered.contains("    n0 -> n1 [label=" + html("PUBLISH", "publish", REJECTED_FRAGMENT,
                UNAVAILABLE_FRAGMENT) + "];\n"), rendered);
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
        assertEquals(List.of(new RecoveryView(REJECTED_RULE.kind(), "RECORD_REJECTION", List.of(), false)),
                edge(view, "RETRY_PENDING", "PUBLISH").recoveries());
        assertEquals(List.of(), edge(view, "INTEGRATION_FAILED", "PUBLISH").recoveries());
        String rendered = GraphvizExporter.render(view);
        assertTrue(rendered.contains("    n0 -> n1 [label=" + html("PUBLISH", "publish", UNAVAILABLE_FRAGMENT,
                REJECTED_FRAGMENT) + "];\n"), rendered);
        assertTrue(rendered.contains("    n2 -> n1 [label=" + html("PUBLISH", "publish", REJECTED_FRAGMENT)
                + "];\n"), rendered);
        assertTrue(rendered.contains("    n4 -> n1 [label=" + html("PUBLISH", "publish") + "];\n"), rendered);
    }

    static Stream<Arguments> recoveryLabels() {
        List<RecoveryView> rules = List.of(new RecoveryView("a.b.First", "R1", List.of("a.b.Thrown"), false),
                new RecoveryView("Outer$Second", "R2", List.of(), false));
        String first = "[recover First -&gt; R1]";
        String second = "[recover Outer$Second -&gt; R2]";
        return Stream.of(
                Arguments.of("every part", labeled(List.of("g1", "g2"), true, Optional.of("F"),
                        Optional.of("Peer: c"), rules),
                        List.of("act", "[async]", "[if g1, g2]", first, second, "[onFailure F]", "[watches Peer: c]")),
                Arguments.of("rules alone", labeled(List.of(), false, Optional.empty(), Optional.empty(), rules),
                        List.of("act", first, second)),
                Arguments.of("guards without a fallback", labeled(List.of("g1"), false, Optional.empty(),
                        Optional.empty(), rules), List.of("act", "[if g1]", first, second)),
                Arguments.of("a fallback without guards", labeled(List.of(), false, Optional.of("F"),
                        Optional.empty(), rules), List.of("act", first, second, "[onFailure F]")),
                Arguments.of("a watch without a fallback", labeled(List.of(), false, Optional.empty(),
                        Optional.of("Peer: c"), rules), List.of("act", first, second, "[watches Peer: c]")),
                Arguments.of("no rules", labeled(List.of("g1", "g2"), true, Optional.of("F"),
                        Optional.of("Peer: c"), List.of()),
                        List.of("act", "[async]", "[if g1, g2]", "[onFailure F]", "[watches Peer: c]")),
                Arguments.of("aContinuingRouteIsMarked", continuing(true, true),
                        List.of("act", "[recover First -&gt; R1 continues]", second, "[onFailure F continues]")),
                Arguments.of("a continuing rule alone", continuing(true, false),
                        List.of("act", "[recover First -&gt; R1 continues]", second, "[onFailure F]")),
                Arguments.of("a continuing fallback alone", continuing(false, true),
                        List.of("act", first, second, "[onFailure F continues]")));
    }

    /** GO with two rules, the first one continuing or not, and the fallback F, continuing or not. */
    private static TransitionView continuing(boolean rule, boolean fallback) {
        return new TransitionView("A", "GO", "B", TransitionKind.ACTION, Optional.of("act"), List.of(), false,
                Optional.of("F"), Optional.empty(), List.of(new RecoveryView("a.b.First", "R1", List.of(), rule),
                        new RecoveryView("Outer$Second", "R2", List.of(), false)), false, Optional.empty(),
                fallback);
    }

    /**
     * The rule lines sit between the guards and the fallback whichever other parts the edge has, the kind keeps
     * its nested names after the last dot, and a kind with no package is shown whole.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("recoveryLabels")
    void theRuleFragmentsSitBetweenTheGuardsAndTheFallback(String caseName, TransitionView transition,
            List<String> parts) {
        assertEquals(singleEdgeDot(html("GO", parts.toArray(String[]::new))),
                GraphvizExporter.render(singleEdgeView(transition)));
    }

    /**
     * A rule's kind and recovery identifier are escaped with the rest of the label, like the HTML oracle below.
     * Each corpus text follows a package, so the line shows it whole, and the rule's throwable type is not drawn.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("dotEscapingCorpus")
    void aRecoveryFragmentIsEscapedLikeTheRestOfTheLabel(String caseName, String text) {
        TransitionView transition = labeled(List.of(), false, Optional.empty(), Optional.empty(),
                List.of(new RecoveryView("some.pkg." + text, text, List.of("some.pkg.Hidden" + text), false)));
        assertEquals(singleEdgeDot(html("GO", "act", baseHtml("[recover " + text + " -> " + text + "]"))),
                GraphvizExporter.render(singleEdgeView(transition)));
    }

    /**
     * Without rules nothing changes: the nine-argument view is the view without rules, its label holds no rule
     * line, and no edge of a graph that declares no rule views one.
     */
    @Test
    void anEdgeWithoutRulesIsDrawnAsBefore() {
        TransitionView nine = new TransitionView("A", "GO", "B", TransitionKind.ACTION, Optional.of("act"),
                List.of("g1", "g2"), true, Optional.of("F"), Optional.of("Peer: c"));
        assertEquals(labeled(List.of("g1", "g2"), true, Optional.of("F"), Optional.of("Peer: c"), List.of()), nine);
        assertEquals(singleEdgeDot(html("GO", "act", "[async]", "[if g1, g2]", "[onFailure F]", "[watches Peer: c]")),
                GraphvizExporter.render(singleEdgeView(nine)));
        for (TransitionView edge : GraphView.of(orderGraph()).transitions()) {
            assertEquals(List.of(), edge.recoveries(), edge.transition());
        }
    }

    /** A view copies the rules and the types it is given and refuses a missing one, as it does its guards. */
    @Test
    void theViewsCopyTheirRulesAndRefuseNulls() {
        List<String> types = new ArrayList<>(List.of("a.b.Thrown"));
        RecoveryView rule = new RecoveryView("a.b.Kind", "R", types, false);
        List<RecoveryView> rules = new ArrayList<>(List.of(rule));
        TransitionView transition = labeled(List.of(), false, Optional.empty(), Optional.empty(), rules);
        types.add("a.b.Other");
        rules.clear();
        assertEquals(List.of("a.b.Thrown"), rule.failsOn());
        assertEquals(List.of(rule), transition.recoveries());
        assertThrows(NullPointerException.class, () -> new RecoveryView(null, "R", List.of(), false));
        assertThrows(NullPointerException.class,
                () -> new RecoveryView("a.b.Kind", "R", Arrays.asList("a.b.Thrown", null), false));
        assertThrows(NullPointerException.class,
                () -> labeled(List.of(), false, Optional.empty(), Optional.empty(), null));
    }

    /** A failure kind of this test's own. */
    record Declined() implements FailureKind { }

    private static final TransitionAction<Doc, DocStatus, DocTransition> SUCCEEDS =
            (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ActionOutcome.ok();

    private static final TransitionGuard<Doc, DocStatus, DocTransition> OPEN =
            (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.pass();

    /** DRAFT is initial and ARCHIVED terminal; the other states reject by policy. */
    private static GraphBuilder<Doc, DocStatus, DocTransition> docs() {
        return StateGraph.define(Keys.DOCS).initial(DocStatus.DRAFT).terminal(DocStatus.ARCHIVED)
                .statePolicy(DocStatus.DRAFT, "a draft is submitted or discarded")
                .statePolicy(DocStatus.REVIEW, "a review is published or sent back")
                .statePolicy(DocStatus.PUBLISHED, "a publication is archived");
    }

    /**
     * The only automatic edge of its state is marked {@code [auto]} on the line right after its identifier, with no
     * number, and its arrow and identifier take the automatic color.
     */
    @Test
    void anAutomaticEdgeIsMarkedAuto() {
        GraphView view = GraphView.of(docs()
                .transition(DocTransition.SUBMIT, DocStatus.DRAFT, DocStatus.REVIEW).automatic()
                    .action("submit", SUCCEEDS).endTransition()
                .transition(DocTransition.PUBLISH, DocStatus.REVIEW, DocStatus.PUBLISHED)
                    .action("publish", SUCCEEDS).endTransition()
                .transition(DocTransition.ARCHIVE, DocStatus.PUBLISHED, DocStatus.ARCHIVED).automatic()
                    .endTransition()
                .build());
        TransitionView submit = edge(view, "DRAFT", "SUBMIT");
        assertTrue(submit.automatic());
        assertEquals(Optional.empty(), submit.automaticPosition());
        assertFalse(edge(view, "REVIEW", "PUBLISH").automatic());
        String rendered = GraphvizExporter.render(view);
        assertTrue(rendered.contains("[label=" + html("SUBMIT", "[auto]", "submit") + AUTOMATIC + "]"), rendered);
        assertTrue(rendered.contains("[label=" + html("PUBLISH", "publish") + "]"), rendered);
        assertTrue(rendered.contains("[label=" + html("ARCHIVE", "[auto]") + AUTOMATIC + "]"), rendered);
        assertFalse(rendered.contains("[auto "), rendered);
    }

    /**
     * A state with two automatic edges numbers them from 1 in its declared order, which is neither the order the
     * edges were declared in nor the order of the identifiers; a manual edge of that state gets no mark.
     */
    @Test
    void orderedAutomaticEdgesAreNumbered() {
        GraphView view = GraphView.of(docs()
                .transition(DocTransition.DISCARD, DocStatus.DRAFT, DocStatus.ARCHIVED).automatic().endTransition()
                .transition(DocTransition.SUBMIT, DocStatus.DRAFT, DocStatus.REVIEW).automatic()
                    .guard("open", OPEN).endTransition()
                .transition(DocTransition.RETOUCH, DocStatus.DRAFT, DocStatus.PUBLISHED).endTransition()
                .transition(DocTransition.PUBLISH, DocStatus.REVIEW, DocStatus.PUBLISHED).automatic()
                    .endTransition()
                .transition(DocTransition.ARCHIVE, DocStatus.PUBLISHED, DocStatus.ARCHIVED).endTransition()
                .automaticOrder(DocStatus.DRAFT, List.of(DocTransition.SUBMIT, DocTransition.DISCARD))
                .build());
        assertEquals(Optional.of(1), edge(view, "DRAFT", "SUBMIT").automaticPosition());
        assertEquals(Optional.of(2), edge(view, "DRAFT", "DISCARD").automaticPosition());
        assertEquals(Optional.empty(), edge(view, "DRAFT", "RETOUCH").automaticPosition());
        assertEquals(Optional.empty(), edge(view, "REVIEW", "PUBLISH").automaticPosition());
        String rendered = GraphvizExporter.render(view);
        assertTrue(rendered.contains("[label=" + html("SUBMIT", "[auto 1]", "[if open]") + AUTOMATIC + "]"), rendered);
        assertTrue(rendered.contains("[label=" + html("DISCARD", "[auto 2]") + AUTOMATIC + "]"), rendered);
        assertTrue(rendered.contains("[label=" + html("RETOUCH") + "]"), rendered);
        assertTrue(rendered.contains("[label=" + html("PUBLISH", "[auto]") + AUTOMATIC + "]"), rendered);
    }

    /** A graph's continuing recovery rule and continuing {@code onFailure} reach the view and the label. */
    @Test
    void aContinuingRouteOfAGraphReachesTheLabel() {
        GraphView view = GraphView.of(docs()
                .transition(DocTransition.SUBMIT, DocStatus.DRAFT, DocStatus.REVIEW).automatic()
                    .action("submit", SUCCEEDS).recoverAndContinue(Declined.class, DocTransition.RETOUCH)
                    .endTransition()
                .transition(DocTransition.RETOUCH, DocStatus.DRAFT, DocStatus.PUBLISHED).endTransition()
                .transition(DocTransition.PUBLISH, DocStatus.REVIEW, DocStatus.PUBLISHED)
                    .action("publish", SUCCEEDS).onFailureAndContinue(DocTransition.SEND_BACK).endTransition()
                .transition(DocTransition.SEND_BACK, DocStatus.REVIEW, DocStatus.PUBLISHED).endTransition()
                .transition(DocTransition.ARCHIVE, DocStatus.PUBLISHED, DocStatus.ARCHIVED).automatic()
                    .endTransition()
                .build());
        assertTrue(edge(view, "DRAFT", "SUBMIT").recoveries().get(0).continues());
        assertFalse(edge(view, "DRAFT", "SUBMIT").failureContinues());
        assertTrue(edge(view, "REVIEW", "PUBLISH").failureContinues());
        String rendered = GraphvizExporter.render(view);
        assertTrue(rendered.contains("[label=" + html("SUBMIT", "[auto]", "submit",
                "[recover GraphvizExporterTest$Declined -&gt; RETOUCH continues]") + AUTOMATIC + "]"), rendered);
        assertTrue(rendered.contains("[label=" + html("PUBLISH", "publish", "[onFailure SEND_BACK continues]") + "]"),
                rendered);
    }

    /** The action edge GO from A to B with the given parts and the action {@code act}. */
    private static TransitionView labeled(List<String> guards, boolean async, Optional<String> failureTransition,
            Optional<String> watch, List<RecoveryView> recoveries) {
        return new TransitionView("A", "GO", "B", TransitionKind.ACTION, Optional.of("act"), guards, async,
                failureTransition, watch, recoveries, false, Optional.empty(), false);
    }

    /** A graph of A and B whose only edge is {@code transition}, from A to the terminal B. */
    private static GraphView singleEdgeView(TransitionView transition) {
        return new GraphView("X", "A", Set.of("B"), List.of("A", "B"), List.of(transition), List.of(), List.of());
    }

    /** The whole text {@link #singleEdgeView} renders to, given its edge's label already written. */
    private static String singleEdgeDot(String label) {
        return opening("X")
                + "    n0 [label=\"A\"];\n"
                + "    n1 [label=\"B\"" + TERMINAL + "];\n"
                + "    initial -> n0;\n"
                + "    n0 -> n1 [label=" + label + "];\n"
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

    // --- the default theme, as the expected text writes it ----------------------------------------------------

    /** The end of a left-aligned line in an HTML-like label. */
    static final String BR = "<br align=\"left\"/>";

    /** What a terminal state carries after its label, with the default theme. */
    static final String TERMINAL = ", peripheries=2, fillcolor=\"#1b1f3a\", fontcolor=\"#ffffff\"";

    /** What an automatic edge carries after its label, with the default theme. */
    static final String AUTOMATIC = ", color=\"#2457d6\", fontcolor=\"#2457d6\"";

    /** The shape and colors of a watch note, with the default theme. */
    static final String NOTE = "shape=note, style=filled, color=\"#a85a00\", fillcolor=\"#fff6e5\", "
            + "fontcolor=\"#a85a00\"";

    /** The link of a watch note to its state, with the default theme. */
    static final String DASHED = "style=dashed, arrowhead=none, color=\"#a85a00\"";

    /** The initial dot of a level, with the default theme, without its prefix and indentation. */
    static final String INITIAL = "initial [shape=point, width=0.14, height=0.14, fillcolor=\"#1b1f3a\", label=\"\"];";

    /** The region of a sub-machine, with the default theme. */
    static final String CLUSTER = "graph [style=\"rounded,filled\", color=\"#d4d8ea\", fillcolor=\"#f5f6fb\", "
            + "fontsize=11, penwidth=1.2];";

    /** The first five lines of a drawing named {@code name} with the default theme, the initial dot last. */
    private static String opening(String name) {
        return defaults(name) + "    " + INITIAL + "\n";
    }

    /**
     * The first four lines of a drawing named {@code name} with the default theme: the digraph and the three default
     * statements, which carry the title.
     */
    static String defaults(String name) {
        return "digraph " + baseQuote(name) + " {\n"
                + "    graph [rankdir=LR, bgcolor=\"#ffffff\", pad=\"0.3\", nodesep=\"0.45\", ranksep=\"0.7\", "
                + "fontname=Helvetica, fontsize=12, fontcolor=\"#1b1f3a\", labelloc=t, "
                + "labeljust=l, label=<" + (name.isEmpty() ? "" : "<b>" + baseHtml(name) + "</b>") + ">];\n"
                + "    node [shape=box, style=\"rounded,filled\", color=\"#1b1f3a\", fillcolor=\"#ffffff\", "
                + "fontcolor=\"#1b1f3a\", fontname=Helvetica, fontsize=12, penwidth=1.2, "
                + "margin=\"0.18,0.06\"];\n"
                + "    edge [color=\"#4f5677\", fontcolor=\"#1b1f3a\", fontname=Helvetica, "
                + "fontsize=10, penwidth=1.1, arrowsize=0.7];\n";
    }

    /**
     * An edge label with the default theme, from text already written for HTML: the identifier in bold, then one
     * left-aligned line per part in the muted color.
     */
    static String html(String identifier, String... parts) {
        String bold = identifier.isEmpty() ? "" : "<b>" + identifier + "</b>";
        if (parts.length == 0) {
            return "<" + bold + ">";
        }
        StringBuilder lines = new StringBuilder();
        for (String part : parts) {
            lines.append(part).append(BR);
        }
        return "<" + bold + BR + "<font color=\"#4f5677\">" + lines + "</font>>";
    }

    // --- DOT escaping oracles (quote, restored to 278640e, and HTML-like labels) -----------------------------

    /**
     * Verbatim copy of {@link GraphvizExporter}'s {@code quote} helper at 278640e, kept as an oracle independent
     * of production so the full public {@code render} is checked byte for byte against it for every corpus case.
     */
    static String baseQuote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n") + "\"";
    }

    /**
     * The text of an HTML-like label, written independently of production: the four XML specials as entities, line
     * breaks normalized and ended as left-aligned lines, tabs as spaces, since Graphviz drops a tab, and U+FFFD for
     * the characters XML 1.0 forbids.
     */
    static String baseHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("\r\n", "\n").replace("\r", "\n").replace("\n", BR).replace("\t", " ")
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x{FFFE}\\x{FFFF}]", "\uFFFD");
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
                Arguments.of("control characters", "a\u0000\u0001\u001fb\u007f"),
                Arguments.of("tab", "a\tb"),
                Arguments.of("XML specials", "a<b>&c\"d -> e"),
                Arguments.of("noncharacters", "a\uFFFEb\uFFFFc"));
    }

    /**
     * Differential test kept from the withdrawn T10 experiment: production {@code quote} and the HTML-like label
     * text must escape exactly like these oracles through the full public {@code render}. {@code text} is the only
     * thing that varies per case; every other byte of the expected DOT is fixed by this view's shape, so a mismatch
     * points at the escaping.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("dotEscapingCorpus")
    void quoteMatchesTheInlineOracleThroughFullRender(String caseName, String text) {
        String rawTooltip = "otherwise: " + text;
        String label = html(baseHtml(text), baseHtml(text), "[async]", baseHtml("[if " + text + "]"),
                baseHtml("[onFailure " + text + "]"), baseHtml("[watches " + text + "]"));
        String rawWatchLabel = "watches " + text;
        GraphView view = new GraphView(text, text, Set.of("n0"), List.of(text, "n0"),
                List.of(new TransitionView(text, text, "n0", TransitionKind.ACTION, Optional.of(text),
                        List.of(text), true, Optional.of(text), Optional.of(text))),
                List.of(new PolicyView("n0", text, List.of())),
                List.of(new WatchView(text, text, List.of())));

        String rendered = GraphvizExporter.render(view);

        assertEquals(opening(text)
                + "    n0 [label=" + baseQuote(text) + "];\n"
                + "    n1 [label=\"n0\"" + TERMINAL + ", tooltip=" + baseQuote(rawTooltip) + "];\n"
                + "    initial -> n0;\n"
                + "    n0 -> n1 [label=" + label + "];\n"
                + "    watch0 [" + NOTE + ", label=" + baseQuote(rawWatchLabel) + "];\n"
                + "    n0 -> watch0 [" + DASHED + "];\n"
                + "}\n", rendered);
    }

    private static TransitionView move(String from, String transition, String to) {
        return new TransitionView(from, transition, to, TransitionKind.MOVE, Optional.empty(), List.of(), false,
                Optional.empty(), Optional.empty());
    }
}
