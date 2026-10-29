package io.github.graphmaquina.example.onboarding;

import io.github.graphmaquina.annotation.GraphAction;
import io.github.graphmaquina.annotation.GraphGuard;
import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.TransitionContext;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The code behind the onboarding's edges: the guards that say when an automatic step may run, each refusing with the
 * reason the caller reads, and the action that asks the {@link AddressVerifier}. A plain class: {@link Accounts} says
 * which method serves which edge.
 */
@RequiredArgsConstructor
public final class AccountActions {

    @NonNull
    private final AddressVerifier verifier;

    @GraphGuard
    public GuardResult infoComplete(GuardContext<Account, AccountStatus, AccountTransition> ctx) {
        return ctx.entity().infoComplete() ? GuardResult.pass() : GuardResult.reject("personal info is incomplete");
    }

    @GraphGuard
    public GuardResult addressComplete(GuardContext<Account, AccountStatus, AccountTransition> ctx) {
        return ctx.entity().addressComplete() ? GuardResult.pass() : GuardResult.reject("address is incomplete");
    }

    @GraphGuard
    public GuardResult premium(GuardContext<Account, AccountStatus, AccountTransition> ctx) {
        return ctx.entity().premium() ? GuardResult.pass() : GuardResult.reject("the account is not premium");
    }

    /** An address the verifier cannot verify is a domain failure of its own kind, which the graph routes. */
    @GraphAction
    public ActionOutcome verifyAddress(TransitionContext<Account, AccountStatus, AccountTransition> ctx) {
        return verifier.verifies(ctx.entity())
                ? ActionOutcome.ok()
                : ActionOutcome.failed(AddressFailures.Unverifiable.class, "the address cannot be verified");
    }
}
