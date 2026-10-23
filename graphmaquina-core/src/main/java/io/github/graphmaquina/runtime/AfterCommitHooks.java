package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.HookRef;
import io.github.graphmaquina.api.SubmachineStart;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.internal.Interrupts;
import java.util.List;

/** The after-commit hooks of a root call, which run once its phase 2 wrote every state, in the phase HOOK. */
final class AfterCommitHooks {

    private AfterCommitHooks() { }

    /**
     * Every committed hop with a hook, in log order, on the calling thread, using the edge that was selected; a
     * sub-machine's start runs no hook. Any throwable a hook throws that is not an Error, checked ones included,
     * becomes an AfterCommitException of the call, naming the hop and carrying every hop and every start the call
     * committed, the very {@code records} and {@code starts} the commit built; an Error propagates unchanged. A call
     * that logs at TRACE logs each hook before it runs. Each hook gets a handle of its own on the call's flow, in its
     * CommitContext, invalidated in a finally once the hook returned or threw; a hop without a hook makes none. An
     * InterruptedException that a hook throws interrupts the thread again.
     */
    static void run(Transaction tx, List<TransitionRecord<?, ?, ?>> records, List<SubmachineStart<?, ?, ?>> starts,
                    List<LogEntry> committed) {
        for (int index = 0; index < committed.size(); index++) {
            LogEntry entry = committed.get(index);
            if (entry instanceof LogEntry.Hop hop) {
                runHook(tx, records, starts, tx.registry().anyMachineFor(hop.ref().key()), hop);
            }
        }
    }

    private static <P, Q extends Enum<Q>, U extends Enum<U>> void runHook(
            Transaction tx, List<TransitionRecord<?, ?, ?>> records, List<SubmachineStart<?, ?, ?>> starts,
            DefaultStateMachine<P, Q, U> owner, LogEntry.Hop entry) {
        TransitionSpec<P, Q, U> spec = Typed.spec(owner.key(), entry);
        if (spec.afterCommit().isEmpty()) {
            return;
        }
        HookRef<P, Q, U> hook = spec.afterCommit().orElseThrow();
        P target = owner.key().entity().cast(entry.ref().instance());
        FlowHandle.Hook flow = tx.hookFlow(hook.name(), entry.ref());
        CommitContext<Q, U> context = new CommitContext<>(spec.from(), spec.transition(), spec.to(), spec.kind(),
                owner.definition().terminals().contains(spec.to()), flow);
        if (tx.log().trace()) {
            tx.log().trace("hook " + hook.name() + " " + CallLog.show(entry.ref()) + " " + spec.transition().name());
        }
        try {
            hook.run(target, context);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            Interrupts.keep(e);
            throw new AfterCommitException(tx.call(), tx.uow().journal(), records, starts,
                    Typed.record(owner.key(), entry), hook.name(), e);
        } finally {
            flow.invalidate();
        }
    }
}
