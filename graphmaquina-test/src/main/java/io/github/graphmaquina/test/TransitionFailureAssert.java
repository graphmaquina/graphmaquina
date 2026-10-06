package io.github.graphmaquina.test;

import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.CascadeException;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Objects;

/**
 * Chainable assertion over a throwable the engine raised, and the entry point to the views that know what
 * each phase carries. Four narrowing methods keep the transition phases apart: an abort discarded the unit
 * of work and nothing moved, a commit failure got partway through writing, and an after-commit failure
 * came from a hook or the registry listener with nothing rolled back. A fifth, {@link #isInvalidGraph()},
 * narrows to the one failure that is not a transition at all. A subject of the wrong kind — or no subject
 * at all — fails there, so a narrowed view always has one.
 */
public final class TransitionFailureAssert extends BaseFailureAssert<TransitionFailureAssert> {

    private TransitionFailureAssert(Throwable actual) {
        super("failure", actual);
    }

    public static TransitionFailureAssert assertThat(Throwable actual) {
        return new TransitionFailureAssert(actual);
    }

    /**
     * Runs {@code operation} and returns the exception it threw. It asserts nothing about that exception and
     * catches no {@link Error}, so an {@link AssertionError} raised inside the body — by another assertion, or
     * by the test itself — propagates untouched rather than being reported as the operation's failure. A body
     * that completes normally is the one thing it does report, as a {@link GraphAssertionError}. An
     * {@link InterruptedException} is returned like any other exception, with the thread's interrupt status
     * set again.
     */
    public static Throwable captureFailure(ThrowingRunnable operation) {
        Objects.requireNonNull(operation, "operation");
        try {
            operation.run();
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return failure;
        }
        throw new GraphAssertionError("operation", "failure", "an exception", "completed normally");
    }

    @Override
    protected TransitionFailureAssert self() {
        return this;
    }

    /**
     * Any abort, including the aggregate a COLLECT cascade raises: {@link CascadeException} is itself a
     * {@link TransitionAbortedException}, and narrowing here does not contradict that hierarchy.
     */
    public AbortAssert isAbort() {
        return new AbortAssert("abort", narrow(TransitionAbortedException.class));
    }

    public CascadeAbortAssert isCascadeAbort() {
        return new CascadeAbortAssert("cascade abort", narrow(CascadeException.class));
    }

    public CommitFailureAssert isCommitFailure() {
        return new CommitFailureAssert("commit failure", narrow(CommitFailedException.class));
    }

    public AfterCommitAssert isAfterCommit() {
        return new AfterCommitAssert("after-commit failure", narrow(AfterCommitException.class));
    }

    /**
     * A graph the DSL refused to build, read from here by its problem codes. This is the typed route for a
     * subject held as a {@link Throwable} — what {@link #captureFailure} returns — where overload selection
     * would otherwise send a consumer to a cast and a {@link ClassCastException} rather than to an
     * inspectable failure.
     */
    public InvalidGraphAssert isInvalidGraph() {
        return InvalidGraphAssert.assertThat(narrow(InvalidGraphException.class));
    }

    private <T extends Throwable> T narrow(Class<T> kind) {
        Throwable failure = Checks.subject(context, "type", kind, actual);
        if (!kind.isInstance(failure)) {
            Checks.fail(context, "type", kind, failure);
        }
        return kind.cast(failure);
    }
}
