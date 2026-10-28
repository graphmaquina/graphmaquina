package io.github.graphmaquina.semantics;

import static io.github.graphmaquina.fixtures.TicketMachineFixture.Id.APPROVE;
import static io.github.graphmaquina.fixtures.TicketMachineFixture.Id.AUTO_APPROVE;
import static io.github.graphmaquina.fixtures.TicketMachineFixture.Id.FAIL;
import static io.github.graphmaquina.fixtures.TicketMachineFixture.KEY;
import static io.github.graphmaquina.fixtures.TicketMachineFixture.State.DONE;
import static io.github.graphmaquina.fixtures.TicketMachineFixture.State.OPEN;
import static io.github.graphmaquina.fixtures.TicketMachineFixture.State.REVIEW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.StatePolicy;
import io.github.graphmaquina.api.SubmachineSpec;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.export.GraphView;
import io.github.graphmaquina.export.GraphvizExporter;
import io.github.graphmaquina.export.PolicyView;
import io.github.graphmaquina.export.TransitionView;
import io.github.graphmaquina.fixtures.TicketMachineFixture.Id;
import io.github.graphmaquina.fixtures.TicketMachineFixture.State;
import io.github.graphmaquina.fixtures.TicketMachineFixture.Ticket;
import io.github.graphmaquina.fixtures.TicketMachineFixture;
import io.github.graphmaquina.graph.DirectedGraph;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;

/**
 * The view and the Graphviz export of definitions: only declared edges, parallel edges drawn separately,
 * declaration order, policies as node attributes, labels, and the way {@code GraphView.of} fetches each state's watch.
 */
class GraphExportTest {

    @Test void exportContainsOnlyRealEdges() {
        GraphDefinition<Ticket,State,Id> graph = TicketMachineFixture.builder()
            .transition(AUTO_APPROVE, REVIEW, DONE).endTransition().build();
        GraphView view = GraphView.of(graph);
        assertEquals(3, view.states().size());
        assertEquals(3, view.transitions().size());
        String dot = GraphvizExporter.render(view);
        assertTrue(dot.startsWith("digraph "));
        assertTrue(dot.contains("APPROVE"));
        assertTrue(dot.contains("AUTO_APPROVE"));
    }

    /** Parallel edges survive: each identifier keeps its own arrow and its own label. */
    @Test void parallelEdgesAreDrawnSeparately() {
        GraphDefinition<Ticket,State,Id> graph = TicketMachineFixture.builder()
                .transition(AUTO_APPROVE, REVIEW, DONE).endTransition().build();
        String dot = GraphvizExporter.render(GraphView.of(graph));
        assertTrue(dot.contains("n1 -> n2 [label=\"APPROVE manual\"];"), dot);
        assertTrue(dot.contains("n1 -> n2 [label=\"AUTO_APPROVE\"];"), dot);
    }

    /** The order of the view is origin index, then identifier index, then destination index. */
    @Test void statesAndTransitionsFollowTheDeclaredOrder() {
        GraphDefinition<Ticket,State,Id> graph = TicketMachineFixture.builder()
                .transition(AUTO_APPROVE, REVIEW, DONE).endTransition().build();
        GraphView view = GraphView.of(graph);
        assertEquals(List.of("OPEN", "REVIEW", "DONE"), view.states());
        assertEquals(List.of("SUBMIT", "APPROVE", "AUTO_APPROVE"),
                view.transitions().stream().map(TransitionView::transition).toList());
    }

    /** A policy is a node attribute, never an arrow to a state the request could not reach. */
    @Test void policiesAreNodeAttributesAndNeverEdges() {
        GraphDefinition<Ticket,State,Id> graph = TicketMachineFixture.builder()
                .reject(OPEN, APPROVE, "approval requires review").build();
        GraphView view = GraphView.of(graph);
        PolicyView open = view.policies().get(0);
        assertEquals("OPEN", open.state());
        assertEquals("submit first", open.otherwise());
        assertEquals(List.of("APPROVE: approval requires review"), open.rejections());
        String dot = GraphvizExporter.render(view);
        assertTrue(dot.contains("tooltip=\"otherwise: submit first; APPROVE: approval requires review\""), dot);
        assertEquals(graph.transitions().size(),
                dot.lines().filter((String line) -> line.contains("->") && !line.contains("initial ->")).count(), dot);
    }

    /** States of a desk whose two first states reject every identifier but CLOSE. */
    enum Desk { OPEN, CLOSED, DONE }

    /** CLOSE moves the desk on; the ten others are only ever rejected. */
    enum Request { CLOSE, ALPHA, BRAVO, CHARLIE, DELTA, ECHO, FOXTROT, GOLF, HOTEL, INDIA, JULIETT }

    /**
     * The reasons a state gives are rendered in the order they were declared. OPEN rejects ten identifiers in an
     * order that is neither their ordinal nor their alphabetical one, and CLOSED rejects the same ten the other way
     * round. An {@code EnumMap} would order both by ordinal. A hash map, {@code Map.copyOf} included, places the same
     * keys in the same order in both maps except where collisions reorder them, and keeping both declared orders
     * would take reordering every pair of the ten.
     */
    @Test void rejectionReasonsKeepTheirDeclaredOrderInTheRenderedTooltip() {
        List<Request> declared = List.of(Request.HOTEL, Request.BRAVO, Request.JULIETT, Request.DELTA, Request.ALPHA,
                Request.GOLF, Request.CHARLIE, Request.INDIA, Request.ECHO, Request.FOXTROT);
        MachineKey<Object, Desk, Request> key = MachineKey.of(Object.class, Desk.class, Request.class);
        GraphBuilder<Object, Desk, Request> desk = StateGraph.define(key)
                .initial(Desk.OPEN).terminal(Desk.DONE)
                .statePolicy(Desk.OPEN, "close it first").statePolicy(Desk.CLOSED, "close it again")
                .transition(Request.CLOSE, Desk.OPEN, Desk.CLOSED).endTransition()
                .transition(Request.CLOSE, Desk.CLOSED, Desk.DONE).endTransition();
        for (Request request : declared) {
            desk = desk.reject(Desk.OPEN, request, "no " + request.name() + " while open");
        }
        for (Request request : declared.reversed()) {
            desk = desk.reject(Desk.CLOSED, request, "no " + request.name() + " once closed");
        }
        GraphDefinition<Object, Desk, Request> graph = desk.build();
        assertEquals(List.of(declared, declared.reversed()),
                List.of(List.copyOf(graph.policy(Desk.OPEN).rejectionReasons().keySet()),
                        List.copyOf(graph.policy(Desk.CLOSED).rejectionReasons().keySet())));
        List<String> open = new ArrayList<>();
        List<String> closed = new ArrayList<>();
        for (Request request : declared) {
            open.add(request.name() + ": no " + request.name() + " while open");
            closed.addFirst(request.name() + ": no " + request.name() + " once closed");
        }
        GraphView view = GraphView.of(graph);
        assertEquals(List.of(open, closed),
                List.of(view.policies().get(0).rejections(), view.policies().get(1).rejections()));
        String dot = GraphvizExporter.render(view);
        assertTrue(dot.contains("tooltip=\"otherwise: close it first; " + String.join("; ", open) + "\""), dot);
        assertTrue(dot.contains("tooltip=\"otherwise: close it again; " + String.join("; ", closed) + "\""), dot);
    }

    /** A terminal gets the stable generated policy text when nobody declares one. */
    @Test void aTerminalCarriesTheGeneratedPolicyText() {
        GraphView view = GraphView.of(TicketMachineFixture.builder().build());
        PolicyView done = view.policies().get(2);
        assertEquals("DONE", done.state());
        assertEquals("terminal state DONE accepts no transitions", done.otherwise());
        assertEquals(List.of(), done.rejections());
    }

    /** The identifier leads every label, and a failure route is rendered by the identifier it takes. */
    @Test void labelsCarryIdentityActionGuardsAndRouting() {
        GraphDefinition<Ticket,State,Id> graph = TicketMachineFixture.builder()
                .transition(AUTO_APPROVE, REVIEW, DONE)
                    .action("auto", ctx -> ActionOutcome.ok())
                    .guard("ready", ctx -> GuardResult.pass())
                    .onFailure(APPROVE).endTransition().build();
        TransitionView auto = GraphView.of(graph).transitions().get(2);
        assertEquals(Optional.of("auto"), auto.action());
        assertEquals(List.of("ready"), auto.guards());
        assertEquals(Optional.of("APPROVE"), auto.failureTransition());
        assertTrue(GraphvizExporter.render(GraphView.of(graph))
                .contains("[label=\"AUTO_APPROVE auto [if ready] [onFailure APPROVE]\"]"));
    }

    /**
     * T11 causal evidence (issue #9, F12): during a real {@code GraphView.of} conversion, {@code watch(REVIEW)}
     * is fetched three times, once per outgoing edge of REVIEW, before the unrelated watches-summary loop
     * fetches every state's watch exactly once. That is the E_s x W_s shape: REVIEW's five rules are rescanned
     * once per edge instead of once per state. The wrapper below is test-only and delegates every
     * {@link GraphDefinition} method; production code exposes no counters.
     */
    @Test void watchIsFetchedOncePerTransitionNotOncePerState() {
        GraphDefinition<Ticket,State,Id> graph = repeatedWatchFixture();
        WatchCountingDefinition<Ticket,State,Id> wrapper = new WatchCountingDefinition<>(graph);
        GraphView.of(wrapper);
        // First 4 calls: one per transition, in transitions() order (SUBMIT, APPROVE, AUTO_APPROVE, FAIL) --
        // REVIEW appears three times because it has three outgoing edges. Last 3 calls: one per state (OPEN,
        // REVIEW, DONE), from the unrelated watches-summary loop inside GraphView.of.
        assertEquals(List.of(OPEN, REVIEW, REVIEW, REVIEW, OPEN, REVIEW, DONE), wrapper.watchCalls());
    }

    /** watchLabel joins repeated rule names in declaration order; the rest of the view is unaffected. */
    @Test void watchLabelJoinsRepeatedRuleNamesInDeclarationOrder() {
        GraphView view = GraphView.of(repeatedWatchFixture());
        assertEquals(new TransitionView("REVIEW", "APPROVE", "DONE", TransitionKind.ACTION, Optional.of("manual"),
                List.of(), false, Optional.empty(), Optional.of("Ticket: r0, r2, r4")), view.transitions().get(1));
        assertEquals(new TransitionView("REVIEW", "AUTO_APPROVE", "DONE", TransitionKind.MOVE, Optional.empty(),
                List.of(), false, Optional.empty(), Optional.of("Ticket: r1, r3")), view.transitions().get(2));
    }

    /** FAIL leaves REVIEW, which has a watch, but no rule executes FAIL: the label is absent, not merely empty. */
    @Test void watchLabelIsEmptyWhenNoRuleMatchesTheTransitionId() {
        TransitionView fail = GraphView.of(repeatedWatchFixture()).transitions().get(3);
        assertEquals("FAIL", fail.transition());
        assertEquals(Optional.empty(), fail.watch());
    }

    /** OPEN declares no watch at all; the same absent label represents that as it does a rule that never matches. */
    @Test void watchLabelIsEmptyWhenTheStateHasNoWatchAtAll() {
        TransitionView submit = GraphView.of(repeatedWatchFixture()).transitions().get(0);
        assertEquals("SUBMIT", submit.transition());
        assertEquals(Optional.empty(), submit.watch());
    }

    /**
     * REVIEW has three outgoing edges (APPROVE, AUTO_APPROVE, FAIL) and a watch with five rules that repeat two
     * of those identifiers (APPROVE three times, AUTO_APPROVE twice) and never target FAIL. OPEN keeps no watch
     * at all. This single fixture exercises every shape T11 characterizes: several edges and several rules on
     * one state, repeated ids to exercise label concatenation, a transition id no rule matches, and a state with
     * no watch.
     */
    private static GraphDefinition<Ticket,State,Id> repeatedWatchFixture() {
        Ticket peer = new Ticket(9, OPEN);
        return TicketMachineFixture.builder()
                .transition(AUTO_APPROVE, REVIEW, DONE).endTransition()
                .transition(FAIL, REVIEW, DONE).endTransition()
                .watch(REVIEW, KEY, (Ticket ticket) -> List.of(peer), (Ticket owner) -> Optional.empty())
                    .when("r0", (Ticket ticket, Collection<? extends Ticket> peers,
                            PeerView<Ticket,State,Id> view) -> true)
                        .execute(APPROVE)
                    .when("r1", (Ticket ticket, Collection<? extends Ticket> peers,
                            PeerView<Ticket,State,Id> view) -> true)
                        .execute(AUTO_APPROVE)
                    .when("r2", (Ticket ticket, Collection<? extends Ticket> peers,
                            PeerView<Ticket,State,Id> view) -> true)
                        .execute(APPROVE)
                    .when("r3", (Ticket ticket, Collection<? extends Ticket> peers,
                            PeerView<Ticket,State,Id> view) -> true)
                        .execute(AUTO_APPROVE)
                    .when("r4", (Ticket ticket, Collection<? extends Ticket> peers,
                            PeerView<Ticket,State,Id> view) -> true)
                        .execute(APPROVE)
                .endWatch().build();
    }

    /**
     * Test-only {@link GraphDefinition} that delegates every method to a real definition and records, in order,
     * the states passed to {@link #watch}. It changes no production code and exposes no counters there; the
     * sequence it records is the causal evidence for T11 (issue #9, F12).
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class WatchCountingDefinition<E, S extends Enum<S>, T extends Enum<T>>
            implements GraphDefinition<E,S,T> {
        private final GraphDefinition<E,S,T> delegate;
        private final List<S> watchCalls = new ArrayList<>();

        @Override public MachineKey<E,S,T> key() { return delegate.key(); }
        @Override public S initial() { return delegate.initial(); }
        @Override public Set<S> terminals() { return delegate.terminals(); }
        @Override public DirectedGraph<S,T> graph() { return delegate.graph(); }
        @Override public List<TransitionSpec<E,S,T>> transitions() { return delegate.transitions(); }
        @Override public List<TransitionSpec<E,S,T>> outgoingFrom(S state) { return delegate.outgoingFrom(state); }
        @Override public List<TransitionSpec<E,S,T>> automaticFrom(S state) { return delegate.automaticFrom(state); }

        @Override public Optional<TransitionSpec<E,S,T>> findTransition(S from, T transition) {
            return delegate.findTransition(from, transition);
        }

        @Override public StatePolicy<S,T> policy(S state) { return delegate.policy(state); }

        @Override public Optional<WatchSpec<E,S,T,?,?,?>> watch(S state) {
            watchCalls.add(state);
            return delegate.watch(state);
        }

        @Override public Optional<SubmachineSpec<E,S,T,?,?,?>> submachine(S state) {
            return delegate.submachine(state);
        }

        @Override public boolean hasAsync() { return delegate.hasAsync(); }

        List<S> watchCalls() { return List.copyOf(watchCalls); }
    }
}
