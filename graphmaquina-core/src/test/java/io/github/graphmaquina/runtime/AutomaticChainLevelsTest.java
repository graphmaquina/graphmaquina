package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.AccountGraphs.onboarding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.AutomaticChainExceededException;
import io.github.graphmaquina.error.WatchChainExceededException;
import io.github.graphmaquina.fixtures.Account;
import io.github.graphmaquina.fixtures.AccountGraphs.AddressVerifier;
import io.github.graphmaquina.fixtures.AccountStatus;
import io.github.graphmaquina.fixtures.AccountTransition;
import io.github.graphmaquina.fixtures.StepTexts;
import io.github.graphmaquina.fixtures.TickingClock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;

/**
 * The levels of automatic attempts along the paths of continuations a call starts, and what else each attempt has of
 * its own. A chain nested in an attempt's post-commit starts from that attempt's level, and a watch reaction passes
 * the automatic level on as an attempt passes the watch level on, so a ping-pong through cascades or through watches
 * is cut by one of the two limits; a call that a hook starts is a root of its own, whose counters start from zero; and
 * each attempt has its own flow. Balls on a ladder that push each other, and relays that answer each other through a
 * watch, show the levels.
 */
class AutomaticChainLevelsTest {

    /** The heights of a ball, from H0 to the top H8. */
    enum Height { H0, H1, H2, H3, H4, H5, H6, H7, H8 }

    /** UP_n and PUSH_n lead from the height below to Hn: UP_n is automatic and pushes the partners, PUSH_n not. */
    enum Bounce { UP_1, UP_2, UP_3, UP_4, UP_5, UP_6, UP_7, UP_8, PUSH_1, PUSH_2, PUSH_3, PUSH_4, PUSH_5, PUSH_6,
        PUSH_7, PUSH_8 }

    /** The levels of a relay, from L0 to L8. */
    enum Level { L0, L1, L2, L3, L4, L5, L6, L7, L8 }

    /** ANSWER_n leads from L2n to L2n+1, and BOUNCE_n, automatic, on to L2n+2. */
    enum Hop { ANSWER_0, ANSWER_1, ANSWER_2, ANSWER_3, BOUNCE_0, BOUNCE_1, BOUNCE_2, BOUNCE_3 }

    static final MachineKey<Ball, Height, Bounce> BALLS = MachineKey.of(Ball.class, Height.class, Bounce.class);

    static final MachineKey<Relay, Level, Hop> RELAYS = MachineKey.of(Relay.class, Level.class, Hop.class);

    /** A ball: an id, its height and the balls each of its UP steps pushes. */
    @Getter(AccessLevel.PACKAGE)
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class Ball {

        private final String id;
        private Height height = Height.H0;
        private final List<Ball> partners = new ArrayList<>();

        void setHeight(Height height) { this.height = height; }
    }

    /** A relay: an id, its level and the relay it answers. */
    @Getter(AccessLevel.PACKAGE)
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class Relay {

        private final String id;
        private Level level = Level.L0;
        private Optional<Relay> partner = Optional.empty();

        void setLevel(Level level) { this.level = level; }
    }

    /** The after-commit hook of every UP step. */
    private Consumer<Ball> onUp = (Ball ball) -> { };

    /**
     * The ladder: from each height below H8, UP_n, automatic, whose action cascades PUSH_n into each partner and whose
     * hook is {@link #onUp}, and PUSH_n, manual, both to the height above. H8 is terminal.
     */
    private StateMachine<Ball, Height, Bounce> balls(int maxAutomaticChain) {
        GraphBuilder<Ball, Height, Bounce> graph = StateGraph.define(BALLS).initial(Height.H0).terminal(Height.H8);
        for (int step = 1; step < Height.values().length; step++) {
            Height from = Height.values()[step - 1];
            Height to = Height.values()[step];
            Bounce push = Bounce.valueOf("PUSH_" + step);
            graph = graph.statePolicy(from, "a ball goes up")
                    .transition(Bounce.valueOf("UP_" + step), from, to).automatic()
                        .action("up", (TransitionContext<Ball, Height, Bounce> ctx) -> {
                            for (Ball partner : ctx.entity().partners()) {
                                ctx.cascade(BALLS, partner, push);
                            }
                            return ActionOutcome.ok();
                        })
                        .afterCommit("up", (Ball ball) -> onUp.accept(ball)).endTransition()
                    .transition(push, from, to).endTransition();
        }
        return MachineRegistry.builder().maxAutomaticChain(maxAutomaticChain)
                .add(StateMachines.from(graph.build()).state(Ball::height, Ball::setHeight).identity(Ball::id))
                .build()
                .of(BALLS);
    }

    /**
     * The relays: ANSWER_n from each even level L2n, which a watch of that level executes once the relay's partner
     * moved to an even level, and BOUNCE_n, automatic, from the odd level L2n+1 on. L8 is terminal.
     */
    private static StateMachine<Relay, Level, Hop> relays(int maxAutomaticChain, int maxWatchChain) {
        GraphBuilder<Relay, Level, Hop> graph = StateGraph.define(RELAYS).initial(Level.L0).terminal(Level.L8);
        for (int round = 0; round < 4; round++) {
            Level waiting = Level.values()[2 * round];
            Level answered = Level.values()[2 * round + 1];
            Hop answer = Hop.valueOf("ANSWER_" + round);
            graph = graph.statePolicy(waiting, "a relay answers").statePolicy(answered, "a relay bounces")
                    .transition(answer, waiting, answered).endTransition()
                    .transition(Hop.valueOf("BOUNCE_" + round), answered, Level.values()[2 * round + 2]).automatic()
                        .endTransition()
                    .watch(waiting, RELAYS, (Relay owner) -> owner.partner().stream().toList(), Relay::partner)
                        .when("partnerBounced", (Relay owner, Collection<? extends Relay> peers,
                                PeerView<Relay, Level, Hop> view) -> peers.stream()
                                .allMatch((Relay peer) -> peer.level().ordinal() % 2 == 0))
                        .execute(answer)
                    .endWatch();
        }
        return MachineRegistry.builder().maxAutomaticChain(maxAutomaticChain).maxWatchChain(maxWatchChain)
                .add(StateMachines.from(graph.build()).state(Relay::level, Relay::setLevel).identity(Relay::id))
                .build()
                .of(RELAYS);
    }

    /**
     * Where two relays that answer each other end, and the outcome of the call, once r1 answered first under these
     * limits.
     *
     * @param first the level r1 ends at
     * @param second the level r2 ends at
     * @param outcome the outcome of r1's answer
     */
    record PingPong(Level first, Level second, Outcome<Relay, Level, Hop> outcome) { }

    private static PingPong pingPong(int maxAutomaticChain, int maxWatchChain) {
        Relay first = new Relay("r1");
        Relay second = new Relay("r2");
        first.partner = Optional.of(second);
        second.partner = Optional.of(first);
        Outcome<Relay, Level, Hop> outcome = relays(maxAutomaticChain, maxWatchChain).execute(first, Hop.ANSWER_0);
        return new PingPong(first.level(), second.level(), outcome);
    }

    /**
     * The steps as a tree, each as {@code <ball>:} and the text of {@link StepTexts#describe}, followed by the steps
     * its own outcome lists, in brackets.
     */
    private static String tree(List<AutomaticStep> steps) {
        List<String> texts = new ArrayList<>();
        for (AutomaticStep step : steps) {
            String nested = step instanceof AutomaticStep.Done done && !done.outcome().automaticSteps().isEmpty()
                    ? tree(done.outcome().automaticSteps()) : "";
            texts.add(((Ball) step.entity()).id() + ":" + StepTexts.describe(step) + nested);
        }
        return "[" + String.join(", ", texts) + "]";
    }

    /** The causes of the aborted reactions anywhere under {@code outcome}, in the order they ran. */
    private static List<RuntimeException> abortedReactions(Outcome<?, ?, ?> outcome) {
        List<RuntimeException> found = new ArrayList<>();
        for (Reaction reaction : outcome.reactions()) {
            found.addAll(reaction instanceof Reaction.Aborted aborted
                    ? List.of(aborted.cause()) : abortedReactions(((Reaction.Done) reaction).outcome()));
        }
        for (AutomaticStep step : outcome.automaticSteps()) {
            if (step instanceof AutomaticStep.Done done) {
                found.addAll(abortedReactions(done.outcome()));
            }
        }
        return found;
    }

    /**
     * Each UP of a ball pushes the other one up too, so each attempt's commit moves the other ball, whose chain runs
     * nested one level deeper, until the attempt past maxAutomaticChain = 3 aborts. Each loop ends once a nested call
     * moved its ball, and the call does not chain the pushed ball again.
     */
    @Test
    void aCascadePingPongIsCut() {
        Ball x = new Ball("x");
        Ball y = new Ball("y");
        x.partners.add(y);
        y.partners.add(x);
        Outcome<Ball, Height, Bounce> outcome = balls(3).execute(x, Bounce.UP_1);
        assertEquals("[x:Done APPLIED UP_2 H2[y:Done APPLIED UP_3 H3[x:Done APPLIED UP_4 H4"
                + "[y:Aborted AutomaticChainExceededException], x:Aborted AutomaticChainExceededException]]]",
                tree(outcome.automaticSteps()));
        AutomaticStep.Aborted first = outcome.automaticAbort().orElseThrow();
        assertSame(y, first.entity());
        assertEquals(3, assertInstanceOf(AutomaticChainExceededException.class, first.cause()).maxChain());
        assertEquals(List.of(Height.H4, Height.H4), List.of(x.height(), y.height()));
    }

    /**
     * Each bounce of a relay makes its partner answer, through a watch, and bounce in turn: the watch level and the
     * automatic level both grow by one per round, as a reaction passes the automatic level on and an attempt the
     * watch level. Either limit cuts the ping-pong: maxAutomaticChain = 2 aborts r1's second bounce, the third one;
     * maxWatchChain = 2 lets that bounce run and refuses the two reactions of r2 one watch level above it, to r1's
     * second answer and to that bounce, before their rules run.
     */
    @Test
    void aWatchPingPongIsCut() {
        PingPong cutByAttempts = pingPong(2, 10);
        assertEquals(List.of(Level.L3, Level.L2), List.of(cutByAttempts.first(), cutByAttempts.second()));
        AutomaticStep.Aborted aborted = cutByAttempts.outcome().automaticAbort().orElseThrow();
        assertEquals("r1", ((Relay) aborted.entity()).id());
        assertEquals(2, assertInstanceOf(AutomaticChainExceededException.class, aborted.cause()).maxChain());
        assertEquals(List.of(), abortedReactions(cutByAttempts.outcome()));
        PingPong cutByReactions = pingPong(10, 2);
        assertEquals(List.of(Level.L4, Level.L2), List.of(cutByReactions.first(), cutByReactions.second()));
        assertEquals(Optional.empty(), cutByReactions.outcome().automaticAbort());
        List<RuntimeException> cut = abortedReactions(cutByReactions.outcome());
        assertEquals(2, cut.size());
        for (RuntimeException refused : cut) {
            assertEquals(2, assertInstanceOf(WatchChainExceededException.class, refused).maxChain());
        }
    }

    /**
     * A call that a hook of an attempt starts is a root of its own, so its chain counts from level 1 again: under
     * maxAutomaticChain = 1, the ball the hook moves takes one step, as the ball of the call did, before its second
     * attempt aborts.
     */
    @Test
    void aHookStartedCallResetsTheCounters() {
        Ball x = new Ball("x");
        Ball z = new Ball("z");
        StateMachine<Ball, Height, Bounce> balls = balls(1);
        List<Outcome<Ball, Height, Bounce>> hooked = new ArrayList<>();
        onUp = (Ball ball) -> {
            if (ball == x && ball.height() == Height.H2) {
                hooked.add(balls.execute(z, Bounce.UP_1));
            }
        };
        Outcome<Ball, Height, Bounce> outcome = balls.execute(x, Bounce.UP_1);
        assertEquals("[x:Done APPLIED UP_2 H2, x:Aborted AutomaticChainExceededException]",
                tree(outcome.automaticSteps()));
        assertEquals("[z:Done APPLIED UP_2 H2, z:Aborted AutomaticChainExceededException]",
                tree(hooked.get(0).automaticSteps()));
        assertEquals(List.of(Height.H2, Height.H2), List.of(x.height(), z.height()));
    }

    /**
     * Each attempt is a call of its own with its own flow: when the machine records its trail, the call's trail holds
     * its own hop alone, and each attempt's trail the hop of that attempt, marked as the clock reads after the hop
     * before it.
     */
    @Test
    void eachAttemptHasItsOwnFlow() {
        TickingClock clock = new TickingClock();
        Account account = new Account("a1", AccountStatus.NEW);
        account.setInfoComplete(true);
        account.setAddressComplete(true);
        StateMachine<Account, AccountStatus, AccountTransition> accounts = StateMachines
                .from(onboarding(new AddressVerifier())).state(Account::status, Account::setStatus)
                .identity(Account::id).recordTrail(clock).build();
        Outcome<Account, AccountStatus, AccountTransition> root =
                accounts.execute(account, AccountTransition.SET_PASSWORD);
        assertEquals(List.of(new FlowEntry.Hop(root.records().get(0), Optional.empty(), TickingClock.at(1))),
                root.flow().trail());
        assertEquals(3, root.automaticSteps().size());
        for (int index = 0; index < 3; index++) {
            Outcome<?, ?, ?> attempt = ((AutomaticStep.Done) root.automaticSteps().get(index)).outcome();
            assertEquals(List.of(new FlowEntry.Hop(attempt.records().get(0), Optional.empty(),
                    TickingClock.at(index + 2))), attempt.flow().trail());
        }
    }
}
