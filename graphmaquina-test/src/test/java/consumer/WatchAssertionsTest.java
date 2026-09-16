package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.WatchCondition;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.WatchBuilder;
import io.github.graphmaquina.test.GraphAssert;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.WatchAssert;
import io.github.graphmaquina.test.WatchRuleExpectation;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Contract of {@link WatchAssert}. A rule names the identifier of the owner's transition it executes, not a
 * destination, so two identifiers that reach the same state stay distinguishable. The rules are compared in
 * declaration order and with their multiplicity; both expectations get a match and a mismatch read off the
 * fields of the {@link GraphAssertionError}, both get their own null-subject case, and no case ever lets a
 * watch condition run.
 */
class WatchAssertionsTest {

    /** States of the watching machine. Two watchable destinations, so a rule cannot borrow another's edge. */
    enum WatchState { WAITING, DONE, CANCELLED }

    /** Identifiers of the watching machine; {@code AGAIN} is a second edge to the same destination. */
    enum WatchId { FINISH, CANCEL, AGAIN }

    /** An enum that belongs to no graph under test, used to reach the watches through raw types. */
    enum Foreign { OTHER }

    private static final String WATCH =
            String.class.getName() + "/" + WatchState.class.getName() + " watch WAITING";

    @Test
    void watchOrderIsCheckedWithoutEvaluatingConditions() {
        AtomicInteger calls = new AtomicInteger();
        WatchCondition<String, Entity, State, Id> condition = (owner, peers, view) -> {
            calls.incrementAndGet();
            return true;
        };
        GraphDefinition<String, WatchState, WatchId> graph = base()
                .watch(WatchState.WAITING, TestDomain.KEY, owner -> List.<Entity>of(),
                        peer -> Optional.of("owner"))
                    .when("first", condition).execute(WatchId.FINISH)
                    .when("second", condition).execute(WatchId.FINISH)
                    .endWatch()
                .build();
        WatchAssert<String, WatchState, WatchId> watch =
                GraphAssert.assertThat(graph).watch(WatchState.WAITING);
        watch.hasPeer(TestDomain.KEY).hasRulesExactly(List.of(
                new WatchRuleExpectation<>("first", WatchId.FINISH),
                new WatchRuleExpectation<>("second", WatchId.FINISH)));
        assertThrows(GraphAssertionError.class, () -> watch.hasRulesExactly(List.of(
                new WatchRuleExpectation<>("second", WatchId.FINISH),
                new WatchRuleExpectation<>("first", WatchId.FINISH))));
        assertEquals(0, calls.get());
    }

    // --- peer --------------------------------------------------------------------------------------

    @Test
    void thePeerIsComparedAsAWholeKeyAndByValue() {
        WatchAssert<String, WatchState, WatchId> watch = watch(List.of(rule("ready", WatchId.FINISH)));
        assertSame(watch, watch.hasPeer(TestDomain.KEY));
        watch.hasPeer(MachineKey.of(Entity.class, State.class, Id.class));
        MachineKey<String, WatchState, WatchId> other =
                MachineKey.of(String.class, WatchState.class, WatchId.class);
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasPeer(other)),
                WATCH, "peer", other, TestDomain.KEY);
        MachineKey<String, State, Id> sameStatesOtherEntity =
                MachineKey.of(String.class, State.class, Id.class);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> watch.hasPeer(sameStatesOtherEntity)),
                WATCH, "peer", sameStatesOtherEntity, TestDomain.KEY);
        MachineKey<Entity, State, WatchId> sameStatesOtherIdentifiers =
                MachineKey.of(Entity.class, State.class, WatchId.class);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> watch.hasPeer(sameStatesOtherIdentifiers)),
                WATCH, "peer", sameStatesOtherIdentifiers, TestDomain.KEY);
    }

    // --- rules -------------------------------------------------------------------------------------

    @Test
    void aRuleIsItsConditionNameAndTheIdentifierItExecutes() {
        List<WatchRuleExpectation<WatchId>> declared = List.of(rule("ready", WatchId.FINISH));
        WatchAssert<String, WatchState, WatchId> watch = watch(declared);
        assertSame(watch, watch.hasRulesExactly(declared));
        List<WatchRuleExpectation<WatchId>> wrongName = List.of(rule("set", WatchId.FINISH));
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasRulesExactly(wrongName)),
                WATCH, "rules", wrongName, declared);
        List<WatchRuleExpectation<WatchId>> wrongTransition = List.of(rule("ready", WatchId.CANCEL));
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasRulesExactly(wrongTransition)),
                WATCH, "rules", wrongTransition, declared);
    }

    /**
     * {@code FINISH} and {@code AGAIN} both reach {@code DONE}, so a rule that executes one is not
     * satisfied by the other: the identifier is what the rule names.
     */
    @Test
    void twoIdentifiersToTheSameDestinationAreStillTwoRules() {
        List<WatchRuleExpectation<WatchId>> declared = List.of(rule("ready", WatchId.FINISH));
        WatchAssert<String, WatchState, WatchId> watch = watch(declared);
        List<WatchRuleExpectation<WatchId>> parallel = List.of(rule("ready", WatchId.AGAIN));
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasRulesExactly(parallel)),
                WATCH, "rules", parallel, declared);
    }

    @Test
    void everyRuleKeepsItsOwnIdentifier() {
        List<WatchRuleExpectation<WatchId>> declared =
                List.of(rule("first", WatchId.FINISH), rule("second", WatchId.CANCEL));
        WatchAssert<String, WatchState, WatchId> watch = watch(declared);
        watch.hasRulesExactly(declared);
        List<WatchRuleExpectation<WatchId>> swapped =
                List.of(rule("first", WatchId.CANCEL), rule("second", WatchId.FINISH));
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasRulesExactly(swapped)),
                WATCH, "rules", swapped, declared);
        List<WatchRuleExpectation<WatchId>> flattened =
                List.of(rule("first", WatchId.FINISH), rule("second", WatchId.FINISH));
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasRulesExactly(flattened)),
                WATCH, "rules", flattened, declared);
    }

    @Test
    void aMissingOrExtraRuleIsReportedAgainstTheDeclaredList() {
        List<WatchRuleExpectation<WatchId>> declared =
                List.of(rule("first", WatchId.FINISH), rule("second", WatchId.CANCEL));
        WatchAssert<String, WatchState, WatchId> watch = watch(declared);
        List<WatchRuleExpectation<WatchId>> missing = List.of(rule("first", WatchId.FINISH));
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasRulesExactly(missing)),
                WATCH, "rules", missing, declared);
        List<WatchRuleExpectation<WatchId>> extra = List.of(rule("first", WatchId.FINISH),
                rule("second", WatchId.CANCEL), rule("third", WatchId.FINISH));
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasRulesExactly(extra)),
                WATCH, "rules", extra, declared);
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasRulesExactly(List.of())),
                WATCH, "rules", List.of(), declared);
    }

    @Test
    void twoRulesThatLookAlikeAreStillTwoRules() {
        List<WatchRuleExpectation<WatchId>> declared =
                List.of(rule("ready", WatchId.FINISH), rule("ready", WatchId.FINISH));
        WatchAssert<String, WatchState, WatchId> watch = watch(declared);
        watch.hasRulesExactly(declared);
        List<WatchRuleExpectation<WatchId>> once = List.of(rule("ready", WatchId.FINISH));
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasRulesExactly(once)),
                WATCH, "rules", once, declared);
    }

    // --- absence and arguments ---------------------------------------------------------------------

    @Test
    void aStateThatDeclaresNoWatchHasNoSubject() {
        WatchAssert<String, WatchState, WatchId> watch = GraphAssert
                .assertThat(watchGraph(List.of(rule("ready", WatchId.FINISH))))
                .watch(WatchState.DONE);
        String context = String.class.getName() + "/" + WatchState.class.getName() + " watch DONE";
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasPeer(TestDomain.KEY)),
                context, "peer", TestDomain.KEY, null);
        List<WatchRuleExpectation<WatchId>> expected = List.of(rule("ready", WatchId.FINISH));
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasRulesExactly(expected)),
                context, "rules", expected, null);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void aStateOfAForeignEnumIsRejectedBeforeTheWatchIsRead() {
        GraphAssert raw = GraphAssert.assertThat(watchGraph(List.of(rule("ready", WatchId.FINISH))));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> raw.watch(Foreign.OTHER));
        assertTrue(error.getMessage().startsWith("state OTHER is not a state of "), error.getMessage());
    }

    @Test
    void aNullRequiredArgumentIsAnArgumentErrorNotAnAssertionFailure() {
        GraphAssert<String, WatchState, WatchId> graph =
                GraphAssert.assertThat(watchGraph(List.of(rule("ready", WatchId.FINISH))));
        assertEquals("state", assertThrows(NullPointerException.class,
                () -> graph.watch(null)).getMessage());
        WatchAssert<String, WatchState, WatchId> watch = graph.watch(WatchState.WAITING);
        assertEquals("expected", assertThrows(NullPointerException.class,
                () -> watch.hasPeer(null)).getMessage());
        assertEquals("expected", assertThrows(NullPointerException.class,
                () -> watch.hasRulesExactly(null)).getMessage());
        assertThrows(NullPointerException.class,
                () -> watch.hasRulesExactly(Arrays.asList(rule("ready", WatchId.FINISH), null)));
        assertEquals("condition", assertThrows(NullPointerException.class,
                () -> new WatchRuleExpectation<>(null, WatchId.FINISH)).getMessage());
        assertEquals("transition", assertThrows(NullPointerException.class,
                () -> new WatchRuleExpectation<WatchId>("ready", null)).getMessage());
    }

    // --- one null-subject case per expectation method -----------------------------------------------

    @Test
    void nullDefinitionFailsHasPeerWithContext() {
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> nullWatch().hasPeer(TestDomain.KEY)),
                "graph watch WAITING", "peer", TestDomain.KEY, null);
    }

    @Test
    void nullDefinitionFailsHasRulesExactlyWithContext() {
        List<WatchRuleExpectation<WatchId>> expected = List.of(rule("ready", WatchId.FINISH));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> nullWatch().hasRulesExactly(expected)),
                "graph watch WAITING", "rules", expected, null);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static WatchRuleExpectation<WatchId> rule(String condition, WatchId transition) {
        return new WatchRuleExpectation<>(condition, transition);
    }

    private static WatchAssert<String, WatchState, WatchId> watch(
            List<WatchRuleExpectation<WatchId>> rules) {
        return GraphAssert.assertThat(watchGraph(rules)).watch(WatchState.WAITING);
    }

    private static WatchAssert<String, WatchState, WatchId> nullWatch() {
        return GraphAssert.<String, WatchState, WatchId>assertThat(null).watch(WatchState.WAITING);
    }

    private static void assertFailure(GraphAssertionError error, String context, String property,
            Object expected, Object actual) {
        assertEquals(context, error.context());
        assertEquals(property, error.property());
        assertEquals(expected, error.expected());
        assertEquals(actual, error.actual());
    }

    /** Declares one watch rule per expectation, with a condition that fails the test if it is ever run. */
    private static GraphDefinition<String, WatchState, WatchId> watchGraph(
            List<WatchRuleExpectation<WatchId>> rules) {
        WatchCondition<String, Entity, State, Id> condition = (owner, peers, view) -> {
            throw new AssertionError("the condition ran");
        };
        WatchBuilder<String, WatchState, WatchId, Entity, State, Id> watch = base()
                .watch(WatchState.WAITING, TestDomain.KEY, owner -> List.<Entity>of(),
                        peer -> Optional.of("owner"));
        for (WatchRuleExpectation<WatchId> rule : rules) {
            watch = watch.when(rule.condition(), condition).execute(rule.transition());
        }
        return watch.endWatch().build();
    }

    /** {@code FINISH} and {@code AGAIN} are parallel edges: same endpoints, different identifiers. */
    private static GraphBuilder<String, WatchState, WatchId> base() {
        return StateGraph.define(MachineKey.of(String.class, WatchState.class, WatchId.class))
                .initial(WatchState.WAITING)
                .terminal(WatchState.DONE).terminal(WatchState.CANCELLED)
                .statePolicy(WatchState.WAITING, "waiting")
                .transition(WatchId.FINISH, WatchState.WAITING, WatchState.DONE).endTransition()
                .transition(WatchId.AGAIN, WatchState.WAITING, WatchState.DONE).endTransition()
                .transition(WatchId.CANCEL, WatchState.WAITING, WatchState.CANCELLED).endTransition();
    }
}
