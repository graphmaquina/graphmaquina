package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.AccountGraphs.onboarding;
import static io.github.graphmaquina.fixtures.AccountStatus.NEW;
import static io.github.graphmaquina.fixtures.AccountStatus.PASSWORD_SET;
import static io.github.graphmaquina.fixtures.AccountTransition.SET_PASSWORD;
import static io.github.graphmaquina.fixtures.Keys.ACCOUNTS;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.AutomaticChain;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatePolicy;
import io.github.graphmaquina.api.SubmachineSpec;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.fixtures.Account;
import io.github.graphmaquina.fixtures.AccountGraphs.AddressVerifier;
import io.github.graphmaquina.fixtures.AccountStatus;
import io.github.graphmaquina.fixtures.AccountTransition;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocGraphs;
import io.github.graphmaquina.graph.DirectedGraph;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Pins that a build reads nothing of a definition once it consumed a builder: a builder's validation reads the key,
 * the automaticChain, and each state's sub-machine and watch, and building the machine and indexing its watches use
 * what it read. A definition written by hand that throws on a later read can then fail a build only while every
 * builder is still as it was, ready to be built again.
 */
class BuildReadsDefinitionOnceTest {

    @Test
    void aBuilderBuildsItsMachineWithoutReadingTheDefinitionOnceValidated() {
        ReadingOnce definition = new ReadingOnce(false, false);
        StateMachines.Builder<Account, AccountStatus, AccountTransition> builder = builder(definition);
        builder.validate();
        definition.freeze();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(DocGraphs.plain()).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .build()) {
            DefaultStateMachine<Account, AccountStatus, AccountTransition> machine = builder.build(registry, List.of());
            assertSame(ACCOUNTS, machine.key());
            assertEquals(List.of(), machine.watches());
            machine.closeIfOwned();
        }
    }

    @Test
    void aRegistryBuildReadsEachStatesWatchOnce() {
        Account account = new Account("a1", NEW);
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(DocGraphs.plain()).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .add(builder(new ReadingOnce(true, false)))
                .build()) {
            assertSame(DOCS, registry.of(DOCS).key());
            assertEquals(PASSWORD_SET, registry.of(ACCOUNTS).execute(account, SET_PASSWORD).recordedState());
        }
    }

    @Test
    void aStandaloneBuildReadsNothingOfTheDefinitionOnceItConsumedTheBuilder() {
        StateMachine<Account, AccountStatus, AccountTransition> machine =
                builder(new ReadingOnce(false, true)).build();
        assertSame(ACCOUNTS, machine.key());
    }

    @Test
    void aUsedBuilderRefusesConfigurationWithoutReadingTheDefinition() {
        StateMachines.Builder<Account, AccountStatus, AccountTransition> builder =
                builder(new ReadingOnce(false, true));
        builder.build();
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> builder.identity(Account::id));
        assertEquals("the builder for " + ACCOUNTS + " was already used", refused.getMessage());
    }

    @Test
    void aRegistryBuildReadsNothingOfADefinitionOnceItConsumedABuilder() {
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(DocGraphs.plain()).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .add(builder(new ReadingOnce(false, true)))
                .build()) {
            assertSame(ACCOUNTS, registry.of(ACCOUNTS).key());
        }
    }

    /** A builder of {@code definition}, the onboarding written by hand. */
    private static StateMachines.Builder<Account, AccountStatus, AccountTransition> builder(ReadingOnce definition) {
        return StateMachines.from(definition).state(Account::status, Account::setStatus).identity(Account::id);
    }

    /**
     * The onboarding, written by hand. Once frozen, every method throws on any read; with
     * {@code watchOnce}, the watch of a state throws on a second read of it; with {@code freezeAfterAutomatic}, it
     * freezes once the automatic edges of its last state were read, the last read a build makes of it before it
     * consumes a builder.
     */
    private static final class ReadingOnce implements GraphDefinition<Account, AccountStatus, AccountTransition> {

        private final GraphDefinition<Account, AccountStatus, AccountTransition> delegate =
                onboarding(new AddressVerifier());
        private final boolean watchOnce;
        private final boolean freezeAfterAutomatic;
        private final AtomicBoolean frozen = new AtomicBoolean();
        private final Set<AccountStatus> watchesRead = EnumSet.noneOf(AccountStatus.class);

        ReadingOnce(boolean watchOnce, boolean freezeAfterAutomatic) {
            this.watchOnce = watchOnce;
            this.freezeAfterAutomatic = freezeAfterAutomatic;
        }

        /** From now on, every method throws on any read. */
        void freeze() {
            frozen.set(true);
        }

        @Override
        public MachineKey<Account, AccountStatus, AccountTransition> key() {
            refuseIfFrozen("key");
            return delegate.key();
        }

        @Override
        public Optional<SubmachineSpec<Account, AccountStatus, AccountTransition, ?, ?, ?>> submachine(
                AccountStatus state) {
            refuseIfFrozen("submachine of " + state);
            return delegate.submachine(state);
        }

        @Override
        public Optional<WatchSpec<Account, AccountStatus, AccountTransition, ?, ?, ?>> watch(AccountStatus state) {
            refuseIfFrozen("watch of " + state);
            if (watchOnce && !watchesRead.add(state)) {
                throw new IllegalStateException("watch of " + state + " read again");
            }
            return delegate.watch(state);
        }

        private void refuseIfFrozen(String what) {
            if (frozen.get()) {
                throw new IllegalStateException(what + " read once frozen");
            }
        }

        @Override
        public AutomaticChain automaticChain() {
            refuseIfFrozen("automaticChain");
            return delegate.automaticChain();
        }

        @Override
        public AccountStatus initial() {
            refuseIfFrozen("initial");
            return delegate.initial();
        }

        @Override
        public Set<AccountStatus> terminals() {
            refuseIfFrozen("terminals");
            return delegate.terminals();
        }

        @Override
        public DirectedGraph<AccountStatus, AccountTransition> graph() {
            refuseIfFrozen("graph");
            return delegate.graph();
        }

        @Override
        public List<TransitionSpec<Account, AccountStatus, AccountTransition>> transitions() {
            refuseIfFrozen("transitions");
            return delegate.transitions();
        }

        @Override
        public List<TransitionSpec<Account, AccountStatus, AccountTransition>> outgoingFrom(AccountStatus state) {
            refuseIfFrozen("outgoingFrom " + state);
            return delegate.outgoingFrom(state);
        }

        @Override
        public List<TransitionSpec<Account, AccountStatus, AccountTransition>> automaticFrom(AccountStatus state) {
            refuseIfFrozen("automaticFrom " + state);
            List<TransitionSpec<Account, AccountStatus, AccountTransition>> automatic = delegate.automaticFrom(state);
            AccountStatus[] states = AccountStatus.values();
            if (freezeAfterAutomatic && state == states[states.length - 1]) {
                freeze();
            }
            return automatic;
        }

        @Override
        public Optional<TransitionSpec<Account, AccountStatus, AccountTransition>> findTransition(
                AccountStatus state, AccountTransition transition) {
            refuseIfFrozen("findTransition " + state);
            return delegate.findTransition(state, transition);
        }

        @Override
        public StatePolicy<AccountStatus, AccountTransition> policy(AccountStatus state) {
            refuseIfFrozen("policy of " + state);
            return delegate.policy(state);
        }

        @Override
        public boolean hasAsync() {
            refuseIfFrozen("hasAsync");
            return delegate.hasAsync();
        }
    }
}
