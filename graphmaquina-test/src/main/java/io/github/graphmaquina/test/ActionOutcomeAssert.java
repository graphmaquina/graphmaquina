package io.github.graphmaquina.test;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Optional;
import lombok.NonNull;

/**
 * Chainable, typed assertion over an {@link ActionOutcome}. The expectations about a failure, its reason and its
 * failure kind, first require the {@code Failed} variant: an {@code Ok} outcome, or no outcome at all, fails them on
 * the property {@code kind} before the failure itself is read.
 */
public final class ActionOutcomeAssert {

    private final ActionOutcome actual;

    private ActionOutcomeAssert(ActionOutcome actual) {
        this.actual = actual;
    }

    public static ActionOutcomeAssert assertThat(ActionOutcome actual) {
        return new ActionOutcomeAssert(actual);
    }

    public ActionOutcomeAssert isOk() {
        ActionOutcome outcome = Checks.subject("action outcome", "kind", "Ok", actual);
        Checks.equal("action outcome", "kind", "Ok", outcome.getClass().getSimpleName());
        return this;
    }

    public ActionOutcomeAssert isFailed() {
        ActionOutcome outcome = Checks.subject("action outcome", "kind", "Failed", actual);
        Checks.equal("action outcome", "kind", "Failed", outcome.getClass().getSimpleName());
        return this;
    }

    public ActionOutcomeAssert hasReason(@NonNull String reason) {
        ActionOutcome outcome = Checks.subject("action outcome", "kind", "Failed", actual);
        Checks.equal("action outcome", "kind", "Failed", outcome.getClass().getSimpleName());
        ActionOutcome.Failed failed = (ActionOutcome.Failed) outcome;
        Checks.equal("action outcome", "reason", reason, failed.reason());
        return this;
    }

    /**
     * The failure kind the failed outcome carries, compared by identity: no class satisfies it but that very one,
     * {@link FailureKind} itself included. A failure without a kind reports a null actual value.
     *
     * @param expected the failure kind the outcome must carry
     * @return this view
     */
    public ActionOutcomeAssert hasFailureKind(@NonNull Class<? extends FailureKind> expected) {
        ActionOutcome outcome = Checks.subject("action outcome", "kind", "Failed", actual);
        Checks.equal("action outcome", "kind", "Failed", outcome.getClass().getSimpleName());
        ActionOutcome.Failed failed = (ActionOutcome.Failed) outcome;
        Checks.same("action outcome", "failure kind", expected, failed.failureKind().orElse(null));
        return this;
    }

    public ActionOutcomeAssert hasNoFailureKind() {
        ActionOutcome outcome = Checks.subject("action outcome", "kind", "Failed", actual);
        Checks.equal("action outcome", "kind", "Failed", outcome.getClass().getSimpleName());
        ActionOutcome.Failed failed = (ActionOutcome.Failed) outcome;
        Optional<Class<? extends FailureKind>> none = Optional.empty();
        Checks.equal("action outcome", "failure kind", none, failed.failureKind());
        return this;
    }
}
