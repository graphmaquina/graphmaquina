package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.AccountGraphs.machine;
import static io.github.graphmaquina.fixtures.AccountGraphs.onboarding;
import static io.github.graphmaquina.fixtures.AccountStatus.NEW;
import static io.github.graphmaquina.fixtures.AccountStatus.PASSWORD_SET;
import static io.github.graphmaquina.fixtures.AccountTransition.SET_PASSWORD;
import static io.github.graphmaquina.fixtures.StepTexts.described;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.StatePolicy;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.SubmachineSpec;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Account;
import io.github.graphmaquina.fixtures.AccountGraphs.AddressVerifier;
import io.github.graphmaquina.fixtures.AccountStatus;
import io.github.graphmaquina.fixtures.AccountTransition;
import io.github.graphmaquina.fixtures.Sneaky;
import io.github.graphmaquina.graph.DirectedGraph;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A GraphDefinition written by hand whose automaticFrom throws. A registry reads the automatic edges of every state
 * once as it is built, and what that read throws fails the build. The chain reads them before any attempt, outside any
 * call: what that read throws, anything but an Error, ends the chain as an aborted step that carries it, a checked
 * throwable wrapped in UndeclaredThrowableException, and never fails the call that committed. An attempt reads them
 * again under its own call, where what the read throws aborts that attempt in RESOLVE, as it aborts an advance.
 */
class AutomaticEdgesReadFailureTest {

    /** The onboarding, whose automaticFrom of PASSWORD_SET throws {@link #thrown} from its read {@link #from} on. */
    private static final class ThrowingEdges implements GraphDefinition<Account, AccountStatus, AccountTransition> {

        private final GraphDefinition<Account, AccountStatus, AccountTransition> delegate =
                onboarding(new AddressVerifier());
        private final Throwable thrown;
        private final int from;
        private int reads;

        ThrowingEdges(Throwable thrown, int from) {
            this.thrown = thrown;
            this.from = from;
        }

        @Override
        public List<TransitionSpec<Account, AccountStatus, AccountTransition>> automaticFrom(AccountStatus state) {
            if (state == PASSWORD_SET && ++reads >= from) {
                throw Sneaky.raise(thrown);
            }
            return delegate.automaticFrom(state);
        }

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

    /** An account in NEW whose personal info is complete, so PASSWORD_SET has a candidate that passes. */
    private static Account account() {
        Account account = new Account("a1", NEW);
        account.setInfoComplete(true);
        return account;
    }

    /**
     * The only step of the chain that {@code thrown}, from the first read of PASSWORD_SET after the one of the build
     * on, ends.
     */
    private static AutomaticStep.Aborted abortedBy(Throwable thrown) {
        Account account = account();
        StateMachine<Account, AccountStatus, AccountTransition> accounts = machine(new ThrowingEdges(thrown, 2));
        List<AutomaticStep> steps = accounts.execute(account, SET_PASSWORD).automaticSteps();
        assertEquals(PASSWORD_SET, account.status());
        assertEquals(1, steps.size());
        return assertInstanceOf(AutomaticStep.Aborted.class, steps.get(0));
    }

    /** The read of the build throws: the build fails with what it threw, as it is. */
    @Test
    void aGraphWhoseAutomaticEdgesCannotBeReadFailsTheBuild() {
        IllegalStateException unreadable = new IllegalStateException("the edges are unreadable");
        assertSame(unreadable, assertThrows(IllegalStateException.class,
                () -> machine(new ThrowingEdges(unreadable, 1))));
    }

    /**
     * The chain's own read throws before any attempt: a RuntimeException is the cause as it is, and a checked
     * exception arrives wrapped, with the thread interrupted again for an InterruptedException. The call that
     * committed returns its outcome.
     */
    @Test
    void aChainThatCannotReadTheAutomaticEdgesIsAborted() {
        IllegalStateException unreadable = new IllegalStateException("the edges are unreadable");
        assertSame(unreadable, abortedBy(unreadable).cause());
        IOException offline = new IOException("the edges are offline");
        assertSame(offline, assertInstanceOf(UndeclaredThrowableException.class, abortedBy(offline).cause())
                .getCause());
        InterruptedException interrupted = new InterruptedException("the read was interrupted");
        UndeclaredThrowableException wrapped =
                assertInstanceOf(UndeclaredThrowableException.class, abortedBy(interrupted).cause());
        assertTrue(Thread.interrupted());
        assertSame(interrupted, wrapped.getCause());
    }

    /**
     * The chain's read passes and the attempt's own read throws: the attempt aborts in RESOLVE, with nothing written,
     * and that abort is the step's cause; an advance whose read throws aborts the same way, which it throws.
     */
    @Test
    void anAttemptThatCannotReadTheAutomaticEdgesAbortsInResolve() {
        IOException offline = new IOException("the edges are offline");
        Account account = account();
        List<AutomaticStep> steps = machine(new ThrowingEdges(offline, 3)).execute(account, SET_PASSWORD)
                .automaticSteps();
        assertEquals(List.of("Aborted TransitionAbortedException"), described(steps));
        TransitionAbortedException cause = (TransitionAbortedException) ((AutomaticStep.Aborted) steps.get(0)).cause();
        assertEquals(List.of(CallPhase.RESOLVE, StatesWritten.NONE), List.of(cause.phase(), cause.statesWritten()));
        assertSame(offline, cause.getCause());
        StateMachine<Account, AccountStatus, AccountTransition> accounts = machine(new ThrowingEdges(offline, 2));
        TransitionAbortedException advance = assertThrows(TransitionAbortedException.class,
                () -> accounts.advance(account));
        assertEquals(CallPhase.RESOLVE, advance.phase());
        assertSame(offline, advance.getCause());
        assertEquals(OutcomeCode.APPLIED, accounts.execute(account(), SET_PASSWORD).code());
    }
}
