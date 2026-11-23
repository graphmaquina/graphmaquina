package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.api.WatchCondition.allIn;
import static io.github.graphmaquina.fixtures.AccountGraphs.machine;
import static io.github.graphmaquina.fixtures.AccountGraphs.onboarding;
import static io.github.graphmaquina.fixtures.AccountGraphs.onboardingContinuingOnAdvance;
import static io.github.graphmaquina.fixtures.AccountGraphs.onboardingOnAdvance;
import static io.github.graphmaquina.fixtures.AccountStatus.ACTIVE;
import static io.github.graphmaquina.fixtures.AccountStatus.FLAGGED;
import static io.github.graphmaquina.fixtures.AccountStatus.INFO_SET;
import static io.github.graphmaquina.fixtures.AccountStatus.NEW;
import static io.github.graphmaquina.fixtures.AccountStatus.PASSWORD_SET;
import static io.github.graphmaquina.fixtures.AccountStatus.REVIEW;
import static io.github.graphmaquina.fixtures.AccountTransition.COMPLETE_ADDRESS;
import static io.github.graphmaquina.fixtures.AccountTransition.COMPLETE_INFO;
import static io.github.graphmaquina.fixtures.AccountTransition.FLAG;
import static io.github.graphmaquina.fixtures.AccountTransition.SET_PASSWORD;
import static io.github.graphmaquina.fixtures.AccountTransition.TOUCH;
import static io.github.graphmaquina.fixtures.Keys.ACCOUNTS;
import static io.github.graphmaquina.fixtures.StepTexts.described;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AutomaticChain;
import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.StatePolicy;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.SubmachineSpec;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.fixtures.Account;
import io.github.graphmaquina.fixtures.AccountGraphs.AddressFailures;
import io.github.graphmaquina.fixtures.AccountGraphs.AddressVerifier;
import io.github.graphmaquina.fixtures.AccountStatus;
import io.github.graphmaquina.fixtures.AccountTransition;
import io.github.graphmaquina.graph.DirectedGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.Getter;
import org.junit.jupiter.api.Test;

/**
 * An {@link AutomaticChain#ON_ADVANCE} machine takes its automatic edges on its own only when the application advances
 * one of its entities: the attempt, then that entity's chain. The other entities of that machine that the advance moved
 * do not chain, and the commit of any other call, a request that names an automatic edge and runs it included, chains
 * no entity of that machine, while the entities of an {@link AutomaticChain#AFTER_COMMIT} machine that the same commit
 * moved chain as before. Accounts, the onboarding fixture, and desks, whose INVITE and SETTLE cascade where a test says
 * and whose watch reacts to their accounts, show each case.
 */
class AdvanceOnlyChainTest {

    /** States of a desk. */
    enum DeskStatus { OPEN, INVITED, SETTLED }

    /** Identifiers of the desk machine. */
    enum DeskMove { INVITE, SETTLE }

    /** States of a desk's review, which a second machine over desks writes. */
    enum ReviewStatus { PENDING, STARTED, DONE }

    /** Identifiers of the review machine. */
    enum ReviewMove { START, FINISH }

    static final MachineKey<Desk, DeskStatus, DeskMove> DESKS =
            MachineKey.of(Desk.class, DeskStatus.class, DeskMove.class);

    static final MachineKey<Desk, ReviewStatus, ReviewMove> REVIEWS =
            MachineKey.of(Desk.class, ReviewStatus.class, ReviewMove.class);

    /** A desk: an id, a status, the state of its review and the accounts its watch looks at. */
    @Getter(AccessLevel.PACKAGE)
    static final class Desk {

        private final String id;
        private DeskStatus status = DeskStatus.OPEN;
        private ReviewStatus review = ReviewStatus.PENDING;
        private final List<Account> accounts = new ArrayList<>();

        Desk(String id, Account... accounts) {
            this.id = id;
            this.accounts.addAll(List.of(accounts));
        }

        void setStatus(DeskStatus status) { this.status = status; }

        void setReview(ReviewStatus review) { this.review = review; }
    }

    /** The action of INVITE. */
    private Consumer<TransitionContext<Desk, DeskStatus, DeskMove>> onInvite =
            (TransitionContext<Desk, DeskStatus, DeskMove> ctx) -> { };
    /** The action of SETTLE. */
    private Consumer<TransitionContext<Desk, DeskStatus, DeskMove>> onSettle =
            (TransitionContext<Desk, DeskStatus, DeskMove> ctx) -> { };
    /** The desk whose watch owns every account; none by default. */
    private Optional<Desk> watcher = Optional.empty();

    /** {@link #desks(AutomaticChain, boolean)} whose INVITE is manual. */
    private GraphDefinition<Desk, DeskStatus, DeskMove> desks(AutomaticChain chain) {
        return desks(chain, false);
    }

    /**
     * The desk graph under {@code chain}: OPEN -INVITE-> INVITED, running {@link #onInvite}, automatic when
     * {@code automaticInvite} says; INVITED -SETTLE-> SETTLED, automatic, running {@link #onSettle}. The watch of OPEN
     * executes INVITE once every account of the desk is in INFO_SET.
     */
    private GraphDefinition<Desk, DeskStatus, DeskMove> desks(AutomaticChain chain, boolean automaticInvite) {
        TransitionBuilder<Desk, DeskStatus, DeskMove> invite = StateGraph.define(DESKS).automaticChain(chain)
                .initial(DeskStatus.OPEN).terminal(DeskStatus.SETTLED)
                .statePolicy(DeskStatus.OPEN, "a desk invites")
                .statePolicy(DeskStatus.INVITED, "an invited desk settles")
                .transition(DeskMove.INVITE, DeskStatus.OPEN, DeskStatus.INVITED)
                    .action("invite", (TransitionContext<Desk, DeskStatus, DeskMove> ctx) -> {
                        onInvite.accept(ctx);
                        return ActionOutcome.ok();
                    });
        return (automaticInvite ? invite.automatic() : invite).endTransition()
                .transition(DeskMove.SETTLE, DeskStatus.INVITED, DeskStatus.SETTLED).automatic()
                    .action("settle", (TransitionContext<Desk, DeskStatus, DeskMove> ctx) -> {
                        onSettle.accept(ctx);
                        return ActionOutcome.ok();
                    }).endTransition()
                .watch(DeskStatus.OPEN, ACCOUNTS, Desk::accounts, (Account account) -> watcher)
                    .when(allIn(INFO_SET)).execute(DeskMove.INVITE)
                    .endWatch()
                .build();
    }

    /**
     * The review graph, ON_ADVANCE, over a desk's review: PENDING -START-> STARTED, then STARTED -FINISH-> DONE,
     * automatic.
     */
    private static GraphDefinition<Desk, ReviewStatus, ReviewMove> reviews() {
        return StateGraph.define(REVIEWS).automaticChain(AutomaticChain.ON_ADVANCE)
                .initial(ReviewStatus.PENDING).terminal(ReviewStatus.DONE)
                .statePolicy(ReviewStatus.PENDING, "a review starts")
                .statePolicy(ReviewStatus.STARTED, "a started review finishes")
                .transition(ReviewMove.START, ReviewStatus.PENDING, ReviewStatus.STARTED).endTransition()
                .transition(ReviewMove.FINISH, ReviewStatus.STARTED, ReviewStatus.DONE).automatic().endTransition()
                .build();
    }

    /** Desks over {@code desks} and accounts over {@code accounts}. */
    private static MachineRegistry registry(GraphDefinition<Desk, DeskStatus, DeskMove> desks,
                                            GraphDefinition<Account, AccountStatus, AccountTransition> accounts) {
        return MachineRegistry.builder()
                .add(StateMachines.from(desks).state(Desk::status, Desk::setStatus).identity(Desk::id))
                .add(StateMachines.from(accounts).state(Account::status, Account::setStatus).identity(Account::id))
                .build();
    }

    /** An account in {@code status} whose info and address are complete, so each automatic edge it meets passes. */
    private static Account account(AccountStatus status) {
        Account account = new Account("a1", status);
        account.setInfoComplete(true);
        account.setAddressComplete(true);
        return account;
    }

    /** The operation moves the account to PASSWORD_SET, whose automatic edge it does not take. */
    @Test
    void anOperationOnAnOnAdvanceMachineRunsNoChain() {
        Account account = account(NEW);
        Outcome<Account, AccountStatus, AccountTransition> outcome =
                machine(onboardingOnAdvance(new AddressVerifier())).execute(account, SET_PASSWORD);
        assertEquals(OutcomeCode.APPLIED, outcome.code());
        assertEquals(List.of(), outcome.automaticSteps());
        assertEquals(PASSWORD_SET, account.status());
    }

    /** A self-loop on PASSWORD_SET, which an AFTER_COMMIT machine would chain from there, chains no account. */
    @Test
    void aSelfLoopOnAnOnAdvanceMachineRunsNoChain() {
        Account account = account(PASSWORD_SET);
        Outcome<Account, AccountStatus, AccountTransition> outcome =
                machine(onboardingOnAdvance(new AddressVerifier())).execute(account, TOUCH);
        assertEquals(OutcomeCode.APPLIED, outcome.code());
        assertEquals(List.of(), outcome.automaticSteps());
        assertEquals(PASSWORD_SET, account.status());
    }

    /** One advance runs its attempt, then the chain of its entity over every automatic edge, to the terminal state. */
    @Test
    void advanceTakesEveryAutomaticEdgeInOneCall() {
        Account account = account(PASSWORD_SET);
        Outcome<Account, AccountStatus, AccountTransition> outcome =
                machine(onboardingOnAdvance(new AddressVerifier())).advance(account);
        assertEquals(List.of(OutcomeCode.APPLIED, Optional.of(COMPLETE_INFO)),
                List.of(outcome.code(), outcome.requestedTransition()));
        assertEquals(List.of("Done APPLIED COMPLETE_ADDRESS ADDRESS_SET", "Done APPLIED ACTIVATE ACTIVE"),
                described(outcome.automaticSteps()));
        assertEquals(ACTIVE, account.status());
    }

    /**
     * An AFTER_COMMIT desk's operation cascades an account of the ON_ADVANCE machine: the account stays where the
     * cascade left it, and the desk, the cascading root, still runs its own chain.
     */
    @Test
    void aCascadedPeerOfAnOnAdvanceMachineDoesNotChain() {
        Account account = account(NEW);
        Desk desk = new Desk("d1");
        onInvite = (TransitionContext<Desk, DeskStatus, DeskMove> ctx) -> ctx.cascade(ACCOUNTS, account, SET_PASSWORD);
        try (MachineRegistry registry = registry(desks(AutomaticChain.AFTER_COMMIT),
                onboardingOnAdvance(new AddressVerifier()))) {
            Outcome<Desk, DeskStatus, DeskMove> outcome = registry.of(DESKS).execute(desk, DeskMove.INVITE);
            assertEquals(List.of("Done APPLIED SETTLE SETTLED"), described(outcome.automaticSteps()));
            AutomaticStep step = outcome.automaticSteps().get(0);
            assertEquals(List.of(DESKS, desk), List.of(step.key(), step.entity()));
        }
        assertEquals(List.of(PASSWORD_SET, DeskStatus.SETTLED), List.of(account.status(), desk.status()));
    }

    /**
     * An ON_ADVANCE desk's operation cascades an account of the AFTER_COMMIT onboarding: the account chains from the
     * commit, while the desk, which no advance moved, stays where its operation left it.
     */
    @Test
    void anAfterCommitMachineInTheSameRegistryStillChains() {
        Account account = account(NEW);
        Desk desk = new Desk("d1");
        onInvite = (TransitionContext<Desk, DeskStatus, DeskMove> ctx) -> ctx.cascade(ACCOUNTS, account, SET_PASSWORD);
        try (MachineRegistry registry = registry(desks(AutomaticChain.ON_ADVANCE), onboarding(new AddressVerifier()))) {
            Outcome<Desk, DeskStatus, DeskMove> outcome = registry.of(DESKS).execute(desk, DeskMove.INVITE);
            assertEquals(List.of("Done APPLIED COMPLETE_INFO INFO_SET", "Done APPLIED COMPLETE_ADDRESS ADDRESS_SET",
                    "Done APPLIED ACTIVATE ACTIVE"), described(outcome.automaticSteps()));
            assertSame(account, outcome.automaticSteps().get(0).entity());
        }
        assertEquals(List.of(ACTIVE, DeskStatus.INVITED), List.of(account.status(), desk.status()));
    }

    /**
     * The advance's attempt moves the account to INFO_SET, where the desk's watch reaction flags it: the reaction's
     * commit, no advance of the account, does not chain it from FLAGGED, and the advance's chain, whose entity that
     * nested call moved, takes no further step. The reaction still chains the AFTER_COMMIT desk.
     */
    @Test
    void aNestedCallThatMovesTheAdvancedEntityStopsItsChain() {
        Account account = account(PASSWORD_SET);
        Desk desk = new Desk("d1", account);
        watcher = Optional.of(desk);
        onInvite = (TransitionContext<Desk, DeskStatus, DeskMove> ctx) -> ctx.cascade(ACCOUNTS, account, FLAG);
        try (MachineRegistry registry = registry(desks(AutomaticChain.AFTER_COMMIT),
                onboardingOnAdvance(new AddressVerifier()))) {
            Outcome<Account, AccountStatus, AccountTransition> outcome = registry.of(ACCOUNTS).advance(account);
            assertEquals(Optional.of(COMPLETE_INFO), outcome.requestedTransition());
            assertEquals(List.of(), outcome.automaticSteps());
            Outcome<?, ?, ?> reaction = assertInstanceOf(Reaction.Done.class, outcome.reactions().get(0)).outcome();
            assertEquals(List.of("Done APPLIED SETTLE SETTLED"), described(reaction.automaticSteps()));
        }
        assertEquals(List.of(FLAGGED, DeskStatus.SETTLED), List.of(account.status(), desk.status()));
    }

    /** An execute that names an automatic edge runs it, from PASSWORD_SET, and its commit chains no account. */
    @Test
    void anExecuteOfAnAutomaticIdentifierOnAnOnAdvanceMachineChainsNothing() {
        Account account = account(PASSWORD_SET);
        Outcome<Account, AccountStatus, AccountTransition> outcome =
                machine(onboardingOnAdvance(new AddressVerifier())).execute(account, COMPLETE_INFO);
        assertEquals(List.of(OutcomeCode.APPLIED, List.of()), List.of(outcome.code(), outcome.automaticSteps()));
        assertEquals(INFO_SET, account.status());
    }

    /** The advance's own attempt fails by the continuing route to FLAGGED, and its chain goes on from there. */
    @Test
    void aContinuingRouteOnTheAdvancesOwnAttemptGoesOn() {
        Account account = account(INFO_SET);
        Outcome<Account, AccountStatus, AccountTransition> outcome =
                machine(onboardingContinuingOnAdvance(unverifiable())).advance(account);
        assertEquals(List.of(OutcomeCode.FAILURE_ROUTED, FLAGGED), List.of(outcome.code(), outcome.recordedState()));
        assertEquals(List.of("Done APPLIED REQUEST_REVIEW REVIEW"), described(outcome.automaticSteps()));
        assertEquals(REVIEW, account.status());
    }

    /** An execute of the same automatic edge fails by the same continuing route, and its commit chains no account. */
    @Test
    void aContinuingRouteOnAnExecutedAutomaticEdgeChainsNothing() {
        Account account = account(INFO_SET);
        Outcome<Account, AccountStatus, AccountTransition> outcome =
                machine(onboardingContinuingOnAdvance(unverifiable())).execute(account, COMPLETE_ADDRESS);
        assertEquals(List.of(OutcomeCode.FAILURE_ROUTED, FLAGGED), List.of(outcome.code(), outcome.recordedState()));
        assertEquals(List.of(), outcome.automaticSteps());
        assertEquals(FLAGGED, account.status());
    }

    /**
     * The advance's attempt, SETTLE, cascades INVITE to a second desk, which lands in INVITED, whose SETTLE is
     * automatic: that desk is not the advanced one, so it does not chain.
     */
    @Test
    void anAdvancesAttemptThatCascadesToAnotherEntityOfItsMachineDoesNotChainIt() {
        Desk desk = new Desk("d1");
        desk.setStatus(DeskStatus.INVITED);
        Desk peer = new Desk("d2");
        onSettle = (TransitionContext<Desk, DeskStatus, DeskMove> ctx) -> ctx.cascade(DESKS, peer, DeskMove.INVITE);
        try (MachineRegistry registry = registry(desks(AutomaticChain.ON_ADVANCE), onboarding(new AddressVerifier()))) {
            Outcome<Desk, DeskStatus, DeskMove> outcome = registry.of(DESKS).advance(desk);
            assertEquals(Optional.of(DeskMove.SETTLE), outcome.requestedTransition());
            assertEquals(List.of(), outcome.automaticSteps());
        }
        assertEquals(List.of(DeskStatus.SETTLED, DeskStatus.INVITED), List.of(desk.status(), peer.status()));
    }

    /**
     * The advance's attempt cascades START to the same desk in the review machine, also ON_ADVANCE, whose STARTED has
     * an automatic edge: the same instance under another key is not the advanced entity, so its review does not chain.
     */
    @Test
    void anAdvancesAttemptThatMovesItsEntityInAnotherOnAdvanceMachineDoesNotChainIt() {
        Desk desk = new Desk("d1");
        desk.setStatus(DeskStatus.INVITED);
        onSettle = (TransitionContext<Desk, DeskStatus, DeskMove> ctx) -> ctx.cascade(REVIEWS, desk, ReviewMove.START);
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(desks(AutomaticChain.ON_ADVANCE)).state(Desk::status, Desk::setStatus)
                        .identity(Desk::id))
                .add(StateMachines.from(reviews()).state(Desk::review, Desk::setReview).identity(Desk::id))
                .add(StateMachines.from(onboarding(new AddressVerifier())).state(Account::status, Account::setStatus)
                        .identity(Account::id))
                .build()) {
            assertEquals(List.of(), registry.of(DESKS).advance(desk).automaticSteps());
        }
        assertEquals(List.of(DeskStatus.SETTLED, ReviewStatus.STARTED), List.of(desk.status(), desk.review()));
    }

    /**
     * The advance's attempt INVITE moves the desk to INVITED, and the step of its chain, SETTLE, cascades INVITE to a
     * second desk: that step is no advance the application made, so the second desk does not chain.
     */
    @Test
    void aChainStepThatMovesAnOnAdvancePeerDoesNotChainIt() {
        Desk desk = new Desk("d1");
        Desk peer = new Desk("d2");
        onSettle = (TransitionContext<Desk, DeskStatus, DeskMove> ctx) -> ctx.cascade(DESKS, peer, DeskMove.INVITE);
        try (MachineRegistry registry = registry(desks(AutomaticChain.ON_ADVANCE, true),
                onboarding(new AddressVerifier()))) {
            Outcome<Desk, DeskStatus, DeskMove> outcome = registry.of(DESKS).advance(desk);
            assertEquals(Optional.of(DeskMove.INVITE), outcome.requestedTransition());
            assertEquals(List.of("Done APPLIED SETTLE SETTLED"), described(outcome.automaticSteps()));
        }
        assertEquals(List.of(DeskStatus.SETTLED, DeskStatus.INVITED), List.of(desk.status(), peer.status()));
    }

    /** The advance that the async facade submits takes every automatic edge, as the synchronous one does. */
    @Test
    void anAsyncAdvanceOnAnOnAdvanceMachineChainsAsTheSyncOneDoes() {
        Account account = account(PASSWORD_SET);
        Outcome<Account, AccountStatus, AccountTransition> outcome =
                machine(onboardingOnAdvance(new AddressVerifier())).async().advance(account).join();
        assertEquals(List.of(OutcomeCode.APPLIED, Optional.of(COMPLETE_INFO)),
                List.of(outcome.code(), outcome.requestedTransition()));
        assertEquals(List.of("Done APPLIED COMPLETE_ADDRESS ADDRESS_SET", "Done APPLIED ACTIVATE ACTIVE"),
                described(outcome.automaticSteps()));
        assertEquals(ACTIVE, account.status());
    }

    /**
     * SET_PASSWORD's hook advances its own account: the operation's commit chains no account, and the hook's advance
     * takes every automatic edge, which its own outcome lists.
     */
    @Test
    void aHookThatAdvancesItsOwnEntityChainsItInTheHooksOutcome() {
        AtomicReference<StateMachine<Account, AccountStatus, AccountTransition>> self = new AtomicReference<>();
        List<Outcome<Account, AccountStatus, AccountTransition>> hooked = new ArrayList<>();
        self.set(machine(onboardingOnAdvance(new AddressVerifier(),
                (Account each) -> hooked.add(self.get().advance(each)))));
        Account account = account(NEW);
        assertEquals(List.of(), self.get().execute(account, SET_PASSWORD).automaticSteps());
        assertEquals(Optional.of(COMPLETE_INFO), hooked.get(0).requestedTransition());
        assertEquals(List.of("Done APPLIED COMPLETE_ADDRESS ADDRESS_SET", "Done APPLIED ACTIVATE ACTIVE"),
                described(hooked.get(0).automaticSteps()));
        assertEquals(ACTIVE, account.status());
    }

    /** An address verifier that cannot verify any address. */
    private static AddressVerifier unverifiable() {
        AddressVerifier verifier = new AddressVerifier();
        verifier.behaviour = (Account target) ->
                ActionOutcome.failed(AddressFailures.Unverifiable.class, "address cannot be verified");
        return verifier;
    }

    /**
     * A definition written by hand whose automaticChain throws fails the build with what it threw, and one that answers
     * null with a NullPointerException; either build consumes nothing, so building again fails the same way.
     */
    @Test
    void aDefinitionWhoseAutomaticChainThrowsFailsWhenTheMachineIsBuilt() {
        IllegalArgumentException thrown = new IllegalArgumentException("no chain declared");
        StateMachines.Builder<Account, AccountStatus, AccountTransition> throwing = builder(() -> {
            throw thrown;
        });
        assertSame(thrown, assertThrows(IllegalArgumentException.class, throwing::build));
        assertSame(thrown, assertThrows(IllegalArgumentException.class, throwing::build));
        StateMachines.Builder<Account, AccountStatus, AccountTransition> answeringNull = builder(() -> null);
        assertThrows(NullPointerException.class, answeringNull::build);
        assertThrows(NullPointerException.class, answeringNull::build);
    }

    /**
     * A build reads the definition's automaticChain once and hands that answer to the machine: a definition that
     * throws on any read after its first builds, standalone and in a registry beside a desk, whose builder a later
     * read that threw would consume, and its machine chains as that first answer, ON_ADVANCE, says.
     */
    @Test
    void aBuildReadsTheAutomaticChainOnce() {
        List<String> reads = new ArrayList<>();
        Account alone = account(NEW);
        assertEquals(List.of(), builder(answeringOnce(reads)).build().execute(alone, SET_PASSWORD).automaticSteps());
        Account registered = account(NEW);
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(desks(AutomaticChain.AFTER_COMMIT)).state(Desk::status, Desk::setStatus)
                        .identity(Desk::id))
                .add(builder(answeringOnce(reads)))
                .build()) {
            assertEquals(List.of(), registry.of(ACCOUNTS).execute(registered, SET_PASSWORD).automaticSteps());
        }
        assertEquals(List.of("read", "read"), reads);
        assertEquals(List.of(PASSWORD_SET, PASSWORD_SET), List.of(alone.status(), registered.status()));
    }

    /** An automaticChain that answers ON_ADVANCE on its first read, noted in {@code reads}, and throws on any later. */
    private static Supplier<AutomaticChain> answeringOnce(List<String> reads) {
        AtomicBoolean answered = new AtomicBoolean();
        return () -> {
            if (answered.getAndSet(true)) {
                throw new IllegalStateException("automaticChain read again");
            }
            reads.add("read");
            return AutomaticChain.ON_ADVANCE;
        };
    }

    /** A standalone machine's builder over the onboarding, whose automaticChain is {@code chain}'s answer. */
    private static StateMachines.Builder<Account, AccountStatus, AccountTransition> builder(
            Supplier<AutomaticChain> chain) {
        return StateMachines.from(new HandWritten(chain)).state(Account::status, Account::setStatus)
                .identity(Account::id);
    }

    /** The onboarding, written by hand, whose automaticChain answers what {@link #chain} supplies. */
    private static final class HandWritten implements GraphDefinition<Account, AccountStatus, AccountTransition> {

        private final GraphDefinition<Account, AccountStatus, AccountTransition> delegate =
                onboarding(new AddressVerifier());
        private final Supplier<AutomaticChain> chain;

        HandWritten(Supplier<AutomaticChain> chain) {
            this.chain = chain;
        }

        @Override
        public AutomaticChain automaticChain() { return chain.get(); }

        @Override
        public MachineKey<Account, AccountStatus, AccountTransition> key() { return delegate.key(); }

        @Override
        public AccountStatus initial() { return delegate.initial(); }

        @Override
        public Set<AccountStatus> terminals() { return delegate.terminals(); }

        @Override
        public DirectedGraph<AccountStatus, AccountTransition> graph() { return delegate.graph(); }

        @Override
        public List<TransitionSpec<Account, AccountStatus, AccountTransition>> transitions() {
            return delegate.transitions();
        }

        @Override
        public List<TransitionSpec<Account, AccountStatus, AccountTransition>> outgoingFrom(AccountStatus state) {
            return delegate.outgoingFrom(state);
        }

        @Override
        public List<TransitionSpec<Account, AccountStatus, AccountTransition>> automaticFrom(AccountStatus state) {
            return delegate.automaticFrom(state);
        }

        @Override
        public Optional<TransitionSpec<Account, AccountStatus, AccountTransition>> findTransition(
                AccountStatus state, AccountTransition transition) {
            return delegate.findTransition(state, transition);
        }

        @Override
        public StatePolicy<AccountStatus, AccountTransition> policy(AccountStatus state) {
            return delegate.policy(state);
        }

        @Override
        public Optional<WatchSpec<Account, AccountStatus, AccountTransition, ?, ?, ?>> watch(AccountStatus state) {
            return delegate.watch(state);
        }

        @Override
        public Optional<SubmachineSpec<Account, AccountStatus, AccountTransition, ?, ?, ?>> submachine(
                AccountStatus state) {
            return delegate.submachine(state);
        }

        @Override
        public boolean hasAsync() { return delegate.hasAsync(); }
    }
}
