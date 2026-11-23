package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.api.WatchCondition.allIn;
import static io.github.graphmaquina.fixtures.AccountGraphs.machine;
import static io.github.graphmaquina.fixtures.AccountGraphs.onboarding;
import static io.github.graphmaquina.fixtures.AccountGraphs.onboardingOnAdvance;
import static io.github.graphmaquina.fixtures.AccountStatus.ACTIVE;
import static io.github.graphmaquina.fixtures.AccountStatus.FLAGGED;
import static io.github.graphmaquina.fixtures.AccountStatus.INFO_SET;
import static io.github.graphmaquina.fixtures.AccountStatus.NEW;
import static io.github.graphmaquina.fixtures.AccountStatus.PASSWORD_SET;
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
import io.github.graphmaquina.api.SubmachineSpec;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.Account;
import io.github.graphmaquina.fixtures.AccountGraphs.AddressVerifier;
import io.github.graphmaquina.fixtures.AccountStatus;
import io.github.graphmaquina.fixtures.AccountTransition;
import io.github.graphmaquina.graph.DirectedGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.Getter;
import org.junit.jupiter.api.Test;

/**
 * An {@link AutomaticChain#ON_ADVANCE} machine takes its automatic edges only when the application advances one of its
 * entities: the attempt, then that entity's chain. The commit of any other call chains no entity of that machine, while
 * the entities of an {@link AutomaticChain#AFTER_COMMIT} machine that the same commit moved chain as before. Accounts,
 * the onboarding fixture, and desks, whose INVITE cascades an account and whose watch reacts to their accounts, show
 * each case.
 */
class AdvanceOnlyChainTest {

    /** States of a desk. */
    enum DeskStatus { OPEN, INVITED, SETTLED }

    /** Identifiers of the desk machine. */
    enum DeskMove { INVITE, SETTLE }

    static final MachineKey<Desk, DeskStatus, DeskMove> DESKS =
            MachineKey.of(Desk.class, DeskStatus.class, DeskMove.class);

    /** A desk: an id, a status and the accounts its watch looks at. */
    @Getter(AccessLevel.PACKAGE)
    static final class Desk {

        private final String id;
        private DeskStatus status = DeskStatus.OPEN;
        private final List<Account> accounts = new ArrayList<>();

        Desk(String id, Account... accounts) {
            this.id = id;
            this.accounts.addAll(List.of(accounts));
        }

        void setStatus(DeskStatus status) { this.status = status; }
    }

    /** The action of INVITE. */
    private Consumer<TransitionContext<Desk, DeskStatus, DeskMove>> onInvite =
            (TransitionContext<Desk, DeskStatus, DeskMove> ctx) -> { };
    /** The desk whose watch owns every account; none by default. */
    private Optional<Desk> watcher = Optional.empty();

    /**
     * The desk graph under {@code chain}: OPEN -INVITE-> INVITED, running {@link #onInvite}; INVITED -SETTLE->
     * SETTLED, automatic. The watch of OPEN executes INVITE once every account of the desk is in INFO_SET.
     */
    private GraphDefinition<Desk, DeskStatus, DeskMove> desks(AutomaticChain chain) {
        return StateGraph.define(DESKS).automaticChain(chain).initial(DeskStatus.OPEN).terminal(DeskStatus.SETTLED)
                .statePolicy(DeskStatus.OPEN, "a desk invites")
                .statePolicy(DeskStatus.INVITED, "an invited desk settles")
                .transition(DeskMove.INVITE, DeskStatus.OPEN, DeskStatus.INVITED)
                    .action("invite", (TransitionContext<Desk, DeskStatus, DeskMove> ctx) -> {
                        onInvite.accept(ctx);
                        return ActionOutcome.ok();
                    }).endTransition()
                .transition(DeskMove.SETTLE, DeskStatus.INVITED, DeskStatus.SETTLED).automatic().endTransition()
                .watch(DeskStatus.OPEN, ACCOUNTS, Desk::accounts, (Account account) -> watcher)
                    .when(allIn(INFO_SET)).execute(DeskMove.INVITE)
                    .endWatch()
                .build();
    }

    /** Desks under {@code chain} and accounts over {@code accounts}. */
    private MachineRegistry registry(AutomaticChain chain,
                                     GraphDefinition<Account, AccountStatus, AccountTransition> accounts) {
        return MachineRegistry.builder()
                .add(StateMachines.from(desks(chain)).state(Desk::status, Desk::setStatus).identity(Desk::id))
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

    /** A self-loop on PASSWORD_SET, which an AFTER_COMMIT machine would chain from there, chains nothing. */
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
        try (MachineRegistry registry = registry(AutomaticChain.AFTER_COMMIT,
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
        try (MachineRegistry registry = registry(AutomaticChain.ON_ADVANCE, onboarding(new AddressVerifier()))) {
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
        try (MachineRegistry registry = registry(AutomaticChain.AFTER_COMMIT,
                onboardingOnAdvance(new AddressVerifier()))) {
            Outcome<Account, AccountStatus, AccountTransition> outcome = registry.of(ACCOUNTS).advance(account);
            assertEquals(Optional.of(COMPLETE_INFO), outcome.requestedTransition());
            assertEquals(List.of(), outcome.automaticSteps());
            Outcome<?, ?, ?> reaction = assertInstanceOf(Reaction.Done.class, outcome.reactions().get(0)).outcome();
            assertEquals(List.of("Done APPLIED SETTLE SETTLED"), described(reaction.automaticSteps()));
        }
        assertEquals(List.of(FLAGGED, DeskStatus.SETTLED), List.of(account.status(), desk.status()));
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
