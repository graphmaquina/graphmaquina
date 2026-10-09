package io.github.graphmaquina.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.fixtures.Billing;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Sub-machines in the view and in the Graphviz text. A view carries, for each bound state in node order, the child's
 * own view and the exits in declaration order. The text draws each bound state as a cluster holding the child's
 * graph: the golden outputs of one bound node, of two nodes bound to the same child, and of two levels of nesting,
 * and a hand-built view for the rules the goldens do not reach. A view without sub-machines renders as the order
 * graph's golden pins it.
 */
class SubmachineExportTest {

    private static String golden(String name) throws IOException {
        try (InputStream in = SubmachineExportTest.class.getResourceAsStream("/golden/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static TransitionView move(String from, String transition, String to) {
        return new TransitionView(from, transition, to, TransitionKind.MOVE, Optional.empty(), List.of(), false,
                Optional.empty(), Optional.empty());
    }

    /**
     * The payment graph with its risk check: AUTHORIZING, its initial state, is a cluster. The initial arrow points
     * at the risk check's own initial state; the exits HOLD and DECLINE leave from CLEARED and FLAGGED; CAPTURE and
     * VOID leave from the anchor.
     */
    @Test
    void oneBoundNodeMatchesGolden() throws IOException {
        assertEquals(golden("submachine-one-node.dot"),
                GraphvizExporter.render(GraphView.of(Billing.withRiskCheck().payments)));
    }

    /**
     * The booking graph binds the payment graph to DEPOSIT and to BALANCE: two clusters with the same child and
     * their own prefixes. The exit DEPOSIT_PAID goes from the first cluster's CAPTURED into the second cluster, and
     * the self-loop RESTART stays inside its cluster, without lhead or ltail.
     */
    @Test
    void twoNodesBoundToTheSameChildMatchGolden() throws IOException {
        assertEquals(golden("submachine-shared-child.dot"),
                GraphvizExporter.render(GraphView.of(new Billing().bookings)));
    }

    /**
     * The booking graph over the payment graph with its risk check: each payment cluster nests a risk-check
     * cluster, whose IDs carry two prefixes. An arrow into DEPOSIT points at the risk check's initial state, two
     * levels down, and is clipped at the outer cluster.
     */
    @Test
    void twoLevelsOfNestingMatchGolden() throws IOException {
        assertEquals(golden("submachine-nested.dot"),
                GraphvizExporter.render(GraphView.of(Billing.withRiskCheck().bookings)));
    }

    /**
     * Each bound state of a view built from a definition holds the child's own view, built the same way, and its
     * exits in declaration order; the risk check's binding sits one level down, in the view of the payment.
     */
    @Test
    void theViewHoldsEachBindingWithTheViewOfItsChild() {
        Billing billing = Billing.withRiskCheck();
        GraphView view = GraphView.of(billing.bookings);
        assertEquals(List.of("DEPOSIT", "BALANCE"),
                view.submachines().stream().map(SubmachineView::state).toList());
        SubmachineView deposit = view.submachines().get(0);
        assertEquals(GraphView.of(billing.payments), deposit.child());
        assertEquals(List.of("CAPTURED", "DECLINED", "VOIDED"), List.copyOf(deposit.exits().keySet()));
        assertEquals(Map.of("CAPTURED", "DEPOSIT_PAID", "DECLINED", "PAYMENT_FAILED", "VOIDED", "PAYMENT_FAILED"),
                deposit.exits());
        SubmachineView risk = deposit.child().submachines().get(0);
        assertEquals("AUTHORIZING", risk.state());
        assertEquals("Payment/RiskStatus", risk.child().name());
        assertEquals(Map.of("CLEARED", "HOLD", "FLAGGED", "DECLINE"), risk.exits());
        assertEquals(List.of(), risk.child().submachines());
    }

    /** A view built without sub-machines, by the constructor that leaves them out, has none. */
    @Test
    void theConstructorWithoutSubmachinesLeavesThemOut() {
        GraphView view = new GraphView("X", "A", Set.of("B"), List.of("A", "B"), List.of(move("A", "GO", "B")),
                List.of(), List.of());
        assertEquals(List.of(), view.submachines());
    }

    /** A sub-machine view copies its exits in their order, keeps them unmodifiable, and refuses null names. */
    @Test
    void aSubmachineViewCopiesItsExitsAndRefusesNulls() {
        GraphView child = new GraphView("C", "X", Set.of("Y", "Z"), List.of("X", "Y", "Z"), List.of(), List.of(),
                List.of());
        Map<String, String> exits = new LinkedHashMap<>();
        exits.put("Z", "LATE");
        exits.put("Y", "EARLY");
        SubmachineView view = new SubmachineView("A", child, exits);
        exits.put("X", "NEVER");
        assertEquals(List.of("Z", "Y"), List.copyOf(view.exits().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> view.exits().put("X", "NEVER"));
        Map<String, String> nullTerminal = new HashMap<>();
        nullTerminal.put(null, "EARLY");
        assertEquals("terminal", assertThrows(NullPointerException.class,
                () -> new SubmachineView("A", child, nullTerminal)).getMessage());
        Map<String, String> nullExit = new HashMap<>();
        nullExit.put("Y", null);
        assertEquals("exit", assertThrows(NullPointerException.class,
                () -> new SubmachineView("A", child, nullExit)).getMessage());
    }

    /**
     * The rules the goldens do not reach. A is bound and B is bound to the same child. The initial arrow enters A's
     * cluster. The exit AGAIN goes from Y back into A's own cluster, so it has no lhead. NEXT is no exit of A, so it
     * leaves A's anchor for B's cluster, clipped at both. A's watch note hangs from A's anchor. B declares no policy,
     * so its cluster has no tooltip.
     */
    @Test
    void anchorsWatchesAndArrowsWithinOneClusterFollowTheirRules() {
        GraphView child = new GraphView("Child/ChildStatus", "X", Set.of("Y"), List.of("X", "Y"),
                List.of(move("X", "GO", "Y")), List.of(), List.of());
        GraphView view = new GraphView("Parent/ParentStatus", "A", Set.of("C"), List.of("A", "B", "C"),
                List.of(move("A", "AGAIN", "A"), move("A", "NEXT", "B"), move("B", "DONE", "C")),
                List.of(new PolicyView("A", "a waits for its child", List.of())),
                List.of(new WatchView("A", "Peer", List.of())),
                List.of(new SubmachineView("A", child, Map.of("Y", "AGAIN")),
                        new SubmachineView("B", child, Map.of("Y", "DONE"))));
        assertEquals("""
                digraph "Parent/ParentStatus" {
                    rankdir=LR;
                    compound=true;
                    initial [shape=point, label=""];
                    n2 [label="C", shape=doublecircle];
                    subgraph cluster_s0 {
                        label="A: Child/ChildStatus";
                        tooltip="otherwise: a waits for its child";
                        s0_anchor [shape=point, style=invis, label=""];
                        s0_initial [shape=point, label=""];
                        s0_n0 [label="X", shape=ellipse];
                        s0_n1 [label="Y", shape=doublecircle];
                        s0_initial -> s0_n0;
                        s0_n0 -> s0_n1 [label="GO"];
                    }
                    subgraph cluster_s1 {
                        label="B: Child/ChildStatus";
                        s1_anchor [shape=point, style=invis, label=""];
                        s1_initial [shape=point, label=""];
                        s1_n0 [label="X", shape=ellipse];
                        s1_n1 [label="Y", shape=doublecircle];
                        s1_initial -> s1_n0;
                        s1_n0 -> s1_n1 [label="GO"];
                    }
                    initial -> s0_n0 [lhead=cluster_s0];
                    s0_n1 -> s0_n0 [label="AGAIN"];
                    s0_anchor -> s1_n0 [label="NEXT", ltail=cluster_s0, lhead=cluster_s1];
                    s1_n1 -> n2 [label="DONE"];
                    watch0 [shape=note, label="watches Peer"];
                    s0_anchor -> watch0 [style=dashed, arrowhead=none, ltail=cluster_s0];
                }
                """, GraphvizExporter.render(view));
    }
}
