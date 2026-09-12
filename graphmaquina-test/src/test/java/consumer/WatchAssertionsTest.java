package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.WatchCondition;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.WatchBuilder;
import io.github.graphmaquina.test.GraphAssert;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.WatchAssert;
import io.github.graphmaquina.test.WatchRuleExpectation;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Contract of {@link WatchAssert}. The rules are compared in declaration order, with their own targets
 * and with their multiplicity; both expectations get a match and a mismatch read off the fields of the
 * {@link GraphAssertionError}, both get their own null-subject case, and no case ever lets a watch
 * condition run.
 */
class WatchAssertionsTest {

    /** States of the watching machine. Two watchable destinations, so a rule cannot borrow another's target. */
    enum WatchState { WAITING, DONE, CANCELLED }

    /** An enum that belongs to no graph under test, used to reach the watches through raw types. */
    enum Foreign { OTHER }

    private static final String WATCH =
            String.class.getName() + "/" + WatchState.class.getName() + " watch WAITING";

    @Test
    void watchOrderIsCheckedWithoutEvaluatingConditions() {
        AtomicInteger calls = new AtomicInteger();
        WatchCondition<String, Entity, State> condition = (owner, peers, view) -> {
            calls.incrementAndGet();
            return true;
        };
        GraphDefinition<String, WatchState> graph =
                StateGraph.define(MachineKey.of(String.class, WatchState.class))
                        .initial(WatchState.WAITING)
                        .terminal(WatchState.DONE).terminal(WatchState.CANCELLED)
                        .from(WatchState.WAITING)
                            .to(WatchState.DONE).move().to(WatchState.CANCELLED).move().stay()
                            .watches(TestDomain.KEY, owner -> List.<Entity>of(), peer -> Optional.of("owner"))
                            .when("first", condition).goTo(WatchState.DONE)
                            .when("second", condition).goTo(WatchState.DONE)
                            .forbidOthers("waiting")
                        .from(WatchState.DONE).stay().forbidOthers("final")
                        .from(WatchState.CANCELLED).stay().forbidOthers("final").build();
        WatchAssert<String, WatchState> watch = GraphAssert.assertThat(graph).watch(WatchState.WAITING);
        watch.hasPeer(TestDomain.KEY).hasRulesExactly(List.of(
                new WatchRuleExpectation<>("first", WatchState.DONE),
                new WatchRuleExpectation<>("second", WatchState.DONE)));
        assertThrows(GraphAssertionError.class, () -> watch.hasRulesExactly(List.of(
                new WatchRuleExpectation<>("second", WatchState.DONE),
                new WatchRuleExpectation<>("first", WatchState.DONE))));
        assertEquals(0, calls.get());
    }

    // --- peer --------------------------------------------------------------------------------------

    @Test
    void thePeerIsComparedAsAWholeKeyAndByValue() {
        WatchAssert<String, WatchState> watch = watch(List.of(rule("ready", WatchState.DONE)));
        assertSame(watch, watch.hasPeer(TestDomain.KEY));
        watch.hasPeer(MachineKey.of(Entity.class, State.class));
        MachineKey<String, WatchState> other = MachineKey.of(String.class, WatchState.class);
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasPeer(other)),
                WATCH, "peer", other, TestDomain.KEY);
        MachineKey<String, State> sameStatesOtherEntity = MachineKey.of(String.class, State.class);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> watch.hasPeer(sameStatesOtherEntity)),
                WATCH, "peer", sameStatesOtherEntity, TestDomain.KEY);
    }

    // --- rules -------------------------------------------------------------------------------------

    @Test
    void aRuleIsItsConditionNameAndItsTarget() {
        List<WatchRuleExpectation<WatchState>> declared = List.of(rule("ready", WatchState.DONE));
        WatchAssert<String, WatchState> watch = watch(declared);
        assertSame(watch, watch.hasRulesExactly(declared));
        List<WatchRuleExpectation<WatchState>> wrongName = List.of(rule("set", WatchState.DONE));
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasRulesExactly(wrongName)),
                WATCH, "rules", wrongName, declared);
        List<WatchRuleExpectation<WatchState>> wrongTarget = List.of(rule("ready", WatchState.CANCELLED));
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasRulesExactly(wrongTarget)),
                WATCH, "rules", wrongTarget, declared);
    }

    @Test
    void everyRuleKeepsItsOwnTarget() {
        List<WatchRuleExpectation<WatchState>> declared =
                List.of(rule("first", WatchState.DONE), rule("second", WatchState.CANCELLED));
        WatchAssert<String, WatchState> watch = watch(declared);
        watch.hasRulesExactly(declared);
        List<WatchRuleExpectation<WatchState>> swapped =
                List.of(rule("first", WatchState.CANCELLED), rule("second", WatchState.DONE));
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasRulesExactly(swapped)),
                WATCH, "rules", swapped, declared);
        List<WatchRuleExpectation<WatchState>> flattened =
                List.of(rule("first", WatchState.DONE), rule("second", WatchState.DONE));
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasRulesExactly(flattened)),
                WATCH, "rules", flattened, declared);
    }

    @Test
    void aMissingOrExtraRuleIsReportedAgainstTheDeclaredList() {
        List<WatchRuleExpectation<WatchState>> declared =
                List.of(rule("first", WatchState.DONE), rule("second", WatchState.CANCELLED));
        WatchAssert<String, WatchState> watch = watch(declared);
        List<WatchRuleExpectation<WatchState>> missing = List.of(rule("first", WatchState.DONE));
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasRulesExactly(missing)),
                WATCH, "rules", missing, declared);
        List<WatchRuleExpectation<WatchState>> extra = List.of(rule("first", WatchState.DONE),
                rule("second", WatchState.CANCELLED), rule("third", WatchState.DONE));
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasRulesExactly(extra)),
                WATCH, "rules", extra, declared);
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasRulesExactly(List.of())),
                WATCH, "rules", List.of(), declared);
    }

    @Test
    void twoRulesThatLookAlikeAreStillTwoRules() {
        List<WatchRuleExpectation<WatchState>> declared =
                List.of(rule("ready", WatchState.DONE), rule("ready", WatchState.DONE));
        WatchAssert<String, WatchState> watch = watch(declared);
        watch.hasRulesExactly(declared);
        List<WatchRuleExpectation<WatchState>> once = List.of(rule("ready", WatchState.DONE));
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasRulesExactly(once)),
                WATCH, "rules", once, declared);
    }

    // --- absence and arguments ---------------------------------------------------------------------

    @Test
    void aStateThatDeclaresNoWatchHasNoSubject() {
        WatchAssert<String, WatchState> watch = GraphAssert
                .assertThat(watchGraph(List.of(rule("ready", WatchState.DONE))))
                .watch(WatchState.DONE);
        String context = String.class.getName() + "/" + WatchState.class.getName() + " watch DONE";
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasPeer(TestDomain.KEY)),
                context, "peer", TestDomain.KEY, null);
        List<WatchRuleExpectation<WatchState>> expected = List.of(rule("ready", WatchState.DONE));
        assertFailure(assertThrows(GraphAssertionError.class, () -> watch.hasRulesExactly(expected)),
                context, "rules", expected, null);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void aStateOfAForeignEnumIsRejectedBeforeTheWatchIsRead() {
        GraphAssert raw = GraphAssert.assertThat(watchGraph(List.of(rule("ready", WatchState.DONE))));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> raw.watch(Foreign.OTHER));
        assertTrue(error.getMessage().startsWith("state OTHER is not a state of "), error.getMessage());
    }

    @Test
    void aNullRequiredArgumentIsAnArgumentErrorNotAnAssertionFailure() {
        GraphAssert<String, WatchState> graph =
                GraphAssert.assertThat(watchGraph(List.of(rule("ready", WatchState.DONE))));
        assertEquals("state", assertThrows(NullPointerException.class,
                () -> graph.watch(null)).getMessage());
        WatchAssert<String, WatchState> watch = graph.watch(WatchState.WAITING);
        assertEquals("expected", assertThrows(NullPointerException.class,
                () -> watch.hasPeer(null)).getMessage());
        assertEquals("expected", assertThrows(NullPointerException.class,
                () -> watch.hasRulesExactly(null)).getMessage());
        assertThrows(NullPointerException.class,
                () -> watch.hasRulesExactly(Arrays.asList(rule("ready", WatchState.DONE), null)));
        assertEquals("condition", assertThrows(NullPointerException.class,
                () -> new WatchRuleExpectation<>(null, WatchState.DONE)).getMessage());
        assertEquals("target", assertThrows(NullPointerException.class,
                () -> new WatchRuleExpectation<WatchState>("ready", null)).getMessage());
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
        List<WatchRuleExpectation<WatchState>> expected = List.of(rule("ready", WatchState.DONE));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> nullWatch().hasRulesExactly(expected)),
                "graph watch WAITING", "rules", expected, null);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static WatchRuleExpectation<WatchState> rule(String condition, WatchState target) {
        return new WatchRuleExpectation<>(condition, target);
    }

    private static WatchAssert<String, WatchState> watch(List<WatchRuleExpectation<WatchState>> rules) {
        return GraphAssert.assertThat(watchGraph(rules)).watch(WatchState.WAITING);
    }

    private static WatchAssert<String, WatchState> nullWatch() {
        return GraphAssert.<String, WatchState>assertThat(null).watch(WatchState.WAITING);
    }

    private static void assertFailure(GraphAssertionError error, String context, String property,
            Object expected, Object actual) {
        assertEquals(context, error.context());
        assertEquals(property, error.property());
        assertEquals(expected, error.expected());
        assertEquals(actual, error.actual());
    }

    /** Declares one watch rule per expectation, with a condition that fails the test if it is ever run. */
    private static GraphDefinition<String, WatchState> watchGraph(
            List<WatchRuleExpectation<WatchState>> rules) {
        WatchCondition<String, Entity, State> condition = (owner, peers, view) -> {
            throw new AssertionError("the condition ran");
        };
        WatchBuilder<String, WatchState, Entity, State> watch =
                StateGraph.define(MachineKey.of(String.class, WatchState.class))
                        .initial(WatchState.WAITING)
                        .terminal(WatchState.DONE).terminal(WatchState.CANCELLED)
                        .from(WatchState.WAITING)
                            .to(WatchState.DONE).move().to(WatchState.CANCELLED).move().stay()
                            .watches(TestDomain.KEY, owner -> List.<Entity>of(), peer -> Optional.of("owner"));
        for (WatchRuleExpectation<WatchState> rule : rules) {
            watch = watch.when(rule.condition(), condition).goTo(rule.target());
        }
        return watch.forbidOthers("waiting")
                .from(WatchState.DONE).stay().forbidOthers("final")
                .from(WatchState.CANCELLED).stay().forbidOthers("final").build();
    }
}
