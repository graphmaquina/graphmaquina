package io.github.graphmaquina.test;

import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Objects;

/** Chainable, typed assertion over a {@link GuardResult}. */
public final class GuardResultAssert {

    private final GuardResult actual;

    private GuardResultAssert(GuardResult actual) {
        this.actual = actual;
    }

    public static GuardResultAssert assertThat(GuardResult actual) {
        return new GuardResultAssert(actual);
    }

    public GuardResultAssert isPass() {
        GuardResult result = Checks.subject("guard result", "kind", "Pass", actual);
        Checks.equal("guard result", "kind", "Pass", result.getClass().getSimpleName());
        return this;
    }

    public GuardResultAssert isRejected() {
        GuardResult result = Checks.subject("guard result", "kind", "Reject", actual);
        Checks.equal("guard result", "kind", "Reject", result.getClass().getSimpleName());
        return this;
    }

    public GuardResultAssert hasReason(String reason) {
        Objects.requireNonNull(reason, "reason");
        GuardResult result = Checks.subject("guard result", "kind", "Reject", actual);
        Checks.equal("guard result", "kind", "Reject", result.getClass().getSimpleName());
        GuardResult.Reject reject = (GuardResult.Reject) result;
        Checks.equal("guard result", "reason", reason, reject.reason());
        return this;
    }
}
