package io.github.graphmaquina.example.onboarding;

import io.github.graphmaquina.api.Outcome;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The onboarding as a web controller drives it, without a web framework. The library reports and the caller decides:
 * the controller answers with its own {@link BadRequestException} when the call is rejected, or when an automatic step
 * the call triggered failed or stopped at a guard, and with the state the account reached otherwise. What committed
 * before the failure or the block stays committed in memory.
 */
@RequiredArgsConstructor
public final class OnboardingController {

    @NonNull
    private final Accounts accounts;

    public AccountStatus setPassword(Account account) {
        Outcome<Account, AccountStatus, AccountTransition> outcome = accounts.setPassword(account);
        if (outcome instanceof Outcome.Rejected<Account, AccountStatus, AccountTransition> rejected) {
            throw new BadRequestException(rejected.reason());
        }
        outcome.automaticFailure().ifPresent((Outcome.Failed<?, ?, ?> failure) -> {
            throw new BadRequestException(failure.reason());
        });
        outcome.automaticBlock().ifPresent((Outcome.Ignored<?, ?, ?> block) -> {
            throw new BadRequestException(block.rejections().getFirst().reason());
        });
        return account.status();
    }
}
