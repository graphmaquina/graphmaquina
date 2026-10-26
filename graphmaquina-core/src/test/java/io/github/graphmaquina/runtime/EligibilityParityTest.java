package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.api.RejectionKind.NO_TRANSITION;
import static io.github.graphmaquina.fixtures.DocGraphs.docs;
import static io.github.graphmaquina.fixtures.DocGraphs.plain;
import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocMachines;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.GuardProbe;
import io.github.graphmaquina.fixtures.Refusal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * An eligibility check refuses exactly as execute would, on two equal entities, one asked and one executed. Where
 * execute returns a {@code Rejected}, the check is {@code Ineligible} with the same kind, reason and guard; where it
 * does not, the check is {@code Eligible}. The cases cover an identifier with no edge, a reason of the policy declared
 * for that identifier and its default, a terminal state with no policy, a synchronous guard rejecting first and last,
 * an asynchronous one, an input present and absent, and a machine of a registry and a standalone one. The guards run
 * in the same order and stop at the same one in both.
 */
class EligibilityParityTest {

    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(10);

    /**
     * One question to {@code machine}: the identifier {@code id} for a doc in {@code state}, with {@code input} when
     * there is one, and the refusal both ways are expected to give, empty when they admit it.
     */
    private record Case(String name, StateMachine<Doc, DocStatus, DocTransition> machine, DocStatus state,
                        DocTransition id, Optional<Object> input, Optional<Refusal> expected) {

        Eligibility<Doc, DocStatus, DocTransition> ask(Doc doc) {
            return input.map((Object each) -> machine.eligibility(doc, id, each))
                    .orElseGet(() -> machine.eligibility(doc, id));
        }

        Outcome<Doc, DocStatus, DocTransition> run(Doc doc) {
            return input.map((Object each) -> machine.execute(doc, id, each))
                    .orElseGet(() -> machine.execute(doc, id));
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static Case question(String name, StateMachine<Doc, DocStatus, DocTransition> machine, DocStatus state,
                            DocTransition id, Optional<Refusal> expected) {
        return new Case(name, machine, state, id, Optional.empty(), expected);
    }

    private static Case questionWith(String name, StateMachine<Doc, DocStatus, DocTransition> machine, Object input,
                                Optional<Refusal> expected) {
        return new Case(name, machine, DRAFT, SUBMIT, Optional.of(input), expected);
    }

    private static StateMachine<Doc, DocStatus, DocTransition> registered(
            GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        return DocMachines.registry(graph, ASYNC_TIMEOUT).of(DOCS);
    }

    private static StateMachine<Doc, DocStatus, DocTransition> standalone(
            GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        return StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id).build();
    }

    /** A review that refuses SUBMIT for a reason of its own and every other identifier for the default one. */
    private static GraphDefinition<Doc, DocStatus, DocTransition> reasoned() {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted")
                .statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .reject(REVIEW, SUBMIT, "it is in review already")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    /** A guard of its own that passes: one edge refuses to declare the same guard twice. */
    private static TransitionGuard<Doc, DocStatus, DocTransition> passing() {
        return new GuardProbe<>();
    }

    /** A guard of its own that rejects with {@code reason}. */
    private static TransitionGuard<Doc, DocStatus, DocTransition> rejecting(String reason) {
        GuardProbe<Doc, DocStatus, DocTransition> probe = new GuardProbe<>();
        probe.verdict = (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.reject(reason);
        return probe;
    }

    /** SUBMIT with the guards a, b and c, whose verdicts are {@code first}, {@code second} and {@code third}. */
    private static UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> three(
            TransitionGuard<Doc, DocStatus, DocTransition> first,
            TransitionGuard<Doc, DocStatus, DocTransition> second,
            TransitionGuard<Doc, DocStatus, DocTransition> third) {
        return (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .guard("a", first).guard("b", second).guard("c", third);
    }

    /** SUBMIT with a guard that passes and an asynchronous one, remote, that rejects. */
    private static UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> remoteRejects() {
        return (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .guard("local", passing())
                .asyncGuard("remote", (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                        CompletableFuture.completedFuture(GuardResult.reject("remote said no")));
    }

    /** SUBMIT with a guard that rejects unless the input is a String. */
    private static UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> needsNote() {
        return (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .guard("needsNote", (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                        ctx.input(String.class).map((String note) -> GuardResult.pass())
                                .orElseGet(() -> GuardResult.reject("a note is required")));
    }

    static Stream<Case> edgeCases() {
        StateMachine<Doc, DocStatus, DocTransition> reasoned = registered(reasoned());
        StateMachine<Doc, DocStatus, DocTransition> bare = registered(plain());
        return Stream.of(
                question("no edge", bare, DRAFT, PUBLISH,
                        Refusal.of(DRAFT, PUBLISH, NO_TRANSITION, "a draft is submitted or discarded")),
                question("reason of the identifier", reasoned, REVIEW, SUBMIT,
                        Refusal.of(REVIEW, SUBMIT, NO_TRANSITION, "it is in review already")),
                question("otherwise", reasoned, REVIEW, DISCARD,
                        Refusal.of(REVIEW, DISCARD, NO_TRANSITION, "a review is published")),
                question("terminal state with no policy", bare, ARCHIVED, SUBMIT,
                        Refusal.of(ARCHIVED, SUBMIT, NO_TRANSITION, "terminal state ARCHIVED accepts no transitions")),
                question("edge of a registry machine", bare, DRAFT, SUBMIT, Optional.empty()));
    }

    static Stream<Case> guardCases() {
        StateMachine<Doc, DocStatus, DocTransition> first = registered(docs(
                three(rejecting("no a"), passing(), rejecting("no c"))));
        StateMachine<Doc, DocStatus, DocTransition> last = registered(docs(
                three(passing(), passing(), rejecting("no c"))));
        StateMachine<Doc, DocStatus, DocTransition> allPass = registered(docs(
                three(passing(), passing(), passing())));
        StateMachine<Doc, DocStatus, DocTransition> note = registered(docs(needsNote()));
        return Stream.of(
                question("first synchronous guard rejects", first, DRAFT, SUBMIT,
                        Refusal.byGuard(DRAFT, SUBMIT, "a", "no a")),
                question("last synchronous guard rejects", last, DRAFT, SUBMIT,
                        Refusal.byGuard(DRAFT, SUBMIT, "c", "no c")),
                question("every guard passes", allPass, DRAFT, SUBMIT, Optional.empty()),
                question("asynchronous guard rejects", registered(docs(remoteRejects())), DRAFT, SUBMIT,
                        Refusal.byGuard(DRAFT, SUBMIT, "remote", "remote said no")),
                questionWith("input present", note, "urgent", Optional.empty()),
                question("input absent", note, DRAFT, SUBMIT, Refusal.byGuard(DRAFT, SUBMIT, "needsNote",
                        "a note is required")));
    }

    static Stream<Case> standaloneCases() {
        StateMachine<Doc, DocStatus, DocTransition> bare = standalone(plain());
        StateMachine<Doc, DocStatus, DocTransition> rejecting = standalone(docs(
                three(passing(), rejecting("no b"), passing())));
        return Stream.of(
                question("standalone edge", bare, DRAFT, SUBMIT, Optional.empty()),
                question("standalone terminal state with no policy", bare, ARCHIVED, SUBMIT,
                        Refusal.of(ARCHIVED, SUBMIT, NO_TRANSITION, "terminal state ARCHIVED accepts no transitions")),
                question("standalone guard rejects", rejecting, DRAFT, SUBMIT,
                        Refusal.byGuard(DRAFT, SUBMIT, "b", "no b")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource({"edgeCases", "guardCases", "standaloneCases"})
    void checkIsIneligibleExactlyWhereExecuteRejects(Case each) {
        Eligibility<Doc, DocStatus, DocTransition> answer = each.ask(new Doc("1", each.state()));
        Outcome<Doc, DocStatus, DocTransition> outcome = each.run(new Doc("1", each.state()));
        assertEquals(each.expected(), Refusal.ofCheck(answer));
        assertEquals(each.expected(), Refusal.ofExecute(outcome));
        assertEquals(outcome.asRejected().isEmpty(), answer.isEligible());
        assertEquals(outcome.asApplied().isPresent(), answer.isEligible());
        assertEquals(List.of(each.state(), each.id()), List.of(answer.from(), answer.transition()));
    }

    /** A guard that notes its name in {@code seen} and rejects when {@code rejecting} is its name. */
    private static GuardProbe<Doc, DocStatus, DocTransition> noting(String name, List<String> seen,
                                                                    Optional<String> rejecting) {
        GuardProbe<Doc, DocStatus, DocTransition> probe = new GuardProbe<>();
        probe.verdict = (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
            seen.add(name);
            return rejecting.filter(name::equals).map(GuardResult::reject).orElseGet(GuardResult::pass);
        };
        return probe;
    }

    /** SUBMIT with the guards a, b (asynchronous), c and d, which note their names in {@code seen}. */
    private static StateMachine<Doc, DocStatus, DocTransition> noters(List<String> seen, Optional<String> rejecting) {
        GuardProbe<Doc, DocStatus, DocTransition> b = noting("b", seen, rejecting);
        return registered(docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .guard("a", noting("a", seen, rejecting))
                .asyncGuard("b", (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                        CompletableFuture.completedFuture(b.test(ctx)))
                .guard("c", noting("c", seen, rejecting))
                .guard("d", noting("d", seen, rejecting))));
    }

    static Stream<Arguments> rejectingGuards() {
        return Stream.of(
                Arguments.of(Optional.empty(), List.of("a", "b", "c", "d")),
                Arguments.of(Optional.of("a"), List.of("a")),
                Arguments.of(Optional.of("b"), List.of("a", "b")),
                Arguments.of(Optional.of("c"), List.of("a", "b", "c")),
                Arguments.of(Optional.of("d"), List.of("a", "b", "c", "d")));
    }

    /** The guards run in declaration order and the first that rejects stops the rest, in the check and in execute. */
    @ParameterizedTest
    @MethodSource("rejectingGuards")
    void guardOrderMatchesExecute(Optional<String> rejecting, List<String> expected) {
        List<String> seen = new ArrayList<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = noters(seen, rejecting);
        machine.eligibility(new Doc("1", DRAFT), SUBMIT);
        List<String> checked = List.copyOf(seen);
        seen.clear();
        machine.execute(new Doc("2", DRAFT), SUBMIT);
        assertEquals(expected, checked);
        assertEquals(expected, seen);
    }
}
