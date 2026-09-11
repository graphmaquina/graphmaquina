package io.github.graphmaquina.test;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Objects;

/** Chainable, typed assertion over an {@link ActionOutcome}. */
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

    public ActionOutcomeAssert hasReason(String reason) {
        Objects.requireNonNull(reason, "reason");
        ActionOutcome outcome = Checks.subject("action outcome", "kind", "Failed", actual);
        Checks.equal("action outcome", "kind", "Failed", outcome.getClass().getSimpleName());
        ActionOutcome.Failed failed = (ActionOutcome.Failed) outcome;
        Checks.equal("action outcome", "reason", reason, failed.reason());
        return this;
    }
}
