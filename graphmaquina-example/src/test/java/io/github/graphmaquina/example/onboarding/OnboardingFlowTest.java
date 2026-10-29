package io.github.graphmaquina.example.onboarding;

import static io.github.graphmaquina.example.onboarding.AccountStatus.ACTIVE;
import static io.github.graphmaquina.example.onboarding.AccountStatus.ADDRESS_SET;
import static io.github.graphmaquina.example.onboarding.AccountStatus.FLAGGED;
import static io.github.graphmaquina.example.onboarding.AccountStatus.INFO_SET;
import static io.github.graphmaquina.example.onboarding.AccountStatus.PASSWORD_SET;
import static io.github.graphmaquina.example.onboarding.AccountStatus.PREMIUM;
import static io.github.graphmaquina.example.onboarding.AccountStatus.REVIEW;
import static io.github.graphmaquina.example.onboarding.AccountTransition.ACTIVATE;
import static io.github.graphmaquina.example.onboarding.AccountTransition.COMPLETE_ADDRESS;
import static io.github.graphmaquina.example.onboarding.AccountTransition.COMPLETE_INFO;
import static io.github.graphmaquina.example.onboarding.AccountTransition.FLAG;
import static io.github.graphmaquina.test.GraphAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.TypedAutomaticStepsAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The onboarding's automatic steps, asserted through the public testing API. Setting the password lets the account
 * go on alone as far as its data allows; a guard that refuses stops the chain with its reason; an address the
 * verifier cannot verify takes a failure route that continues, into review; {@code advance} goes on once the missing
 * data arrives, and so does a self-loop; and the example's controller answers a bad request from what the outcome
 * carries.
 */
class OnboardingFlowTest {

    final FakeAddressVerifier verifier = new FakeAddressVerifier();
    final OnboardingMachines onboarding = OnboardingMachinesRuntime.builder(new AccountActions(verifier)).build();
    final Accounts accounts = onboarding.accounts();

    @AfterEach
    void close() {
        onboarding.close();
    }

    @Test
    void setPasswordAdvancesAsFarAsTheDataAllows() {
        Account account = new Account("a1");
        account.setInfoComplete(true);
        account.setAddressComplete(true);

        Outcome<Account, AccountStatus, AccountTransition> outcome = accounts.setPassword(account);

        assertThat(outcome).isApplied().movedTo(PASSWORD_SET).hasEntityState(Account::status, ACTIVE);
        TypedAutomaticStepsAssert<Account, AccountStatus, AccountTransition> steps =
                assertThat(outcome).automaticSteps().forEntity(AccountsRuntime.KEY, account).hasSize(3);
        steps.atIndex(0).isDone().outcome().isApplied().requestedTransition(COMPLETE_INFO).movedTo(INFO_SET);
        steps.atIndex(1).isDone().outcome().isApplied().requestedTransition(COMPLETE_ADDRESS).movedTo(ADDRESS_SET);
        steps.atIndex(2).isDone().outcome().isApplied().requestedTransition(ACTIVATE).movedTo(ACTIVE);

        Account premium = new Account("a2");
        premium.setInfoComplete(true);
        premium.setAddressComplete(true);
        premium.setPremium(true);
        accounts.setPassword(premium);
        assertEquals(PREMIUM, premium.status());
    }

    @Test
    void aMissingAddressIsReportedByItsGuard() {
        Account account = new Account("a3");
        account.setInfoComplete(true);

        Outcome<Account, AccountStatus, AccountTransition> outcome = accounts.setPassword(account);

        assertEquals(INFO_SET, account.status());
        Outcome.Ignored<?, ?, ?> block = outcome.automaticBlock().orElseThrow();
        assertEquals("address is incomplete", block.rejections().getFirst().reason());
        assertThat(outcome).isApplied().automaticBlock().hasReason(IgnoreReason.NO_CANDIDATE_ADMITTED)
                .rejections().single().transition(COMPLETE_ADDRESS).isRejectedByGuardNamed("addressComplete");
    }

    @Test
    void anUnverifiableAddressContinuesToReview() {
        verifier.refuse();
        Account account = new Account("a4");
        account.setInfoComplete(true);
        account.setAddressComplete(true);

        Outcome<Account, AccountStatus, AccountTransition> outcome = accounts.setPassword(account);

        assertTrue(outcome.automaticFailure().isPresent());
        assertThat(outcome).isApplied().hasNoAutomaticBlock().automaticFailure()
                .hasFailureTransition(FLAG).hasFailureKind(AddressFailures.Unverifiable.class).movedTo(FLAGGED);
        assertEquals(REVIEW, account.status());
    }

    @Test
    void advanceContinuesAfterTheDataArrives() {
        Account account = new Account("a5");
        account.setInfoComplete(true);
        accounts.setPassword(account);
        account.setAddressComplete(true);

        Outcome<Account, AccountStatus, AccountTransition> outcome = accounts.advance(account);

        assertThat(outcome).isApplied().requestedTransition(COMPLETE_ADDRESS).hasEntityState(Account::status, ACTIVE)
                .automaticSteps().forEntity(AccountsRuntime.KEY, account).single().isDone().outcome().isApplied()
                .requestedTransition(ACTIVATE);
        assertThat(accounts.advance(account)).isIgnored().hasReason(IgnoreReason.NO_AUTOMATIC_TRANSITION);
    }

    @Test
    void aSelfLoopRunsTheChainToo() {
        Account account = new Account("a6");
        accounts.setPassword(account);
        account.setInfoComplete(true);

        Outcome<Account, AccountStatus, AccountTransition> outcome = accounts.touch(account);

        assertThat(outcome).isApplied().movedTo(PASSWORD_SET).hasEntityState(Account::status, INFO_SET)
                .automaticBlock().rejections().single().hasReason("address is incomplete");
    }

    @Test
    void theControllerAnswersBadRequest() {
        OnboardingController controller = new OnboardingController(accounts);
        Account complete = new Account("a7");
        complete.setInfoComplete(true);
        complete.setAddressComplete(true);
        assertEquals(ACTIVE, controller.setPassword(complete));

        Account incomplete = new Account("a8");
        incomplete.setInfoComplete(true);
        BadRequestException blocked =
                assertThrows(BadRequestException.class, () -> controller.setPassword(incomplete));
        assertEquals("address is incomplete", blocked.getMessage());
        assertEquals(INFO_SET, incomplete.status());

        verifier.refuse();
        Account unverifiable = new Account("a9");
        unverifiable.setInfoComplete(true);
        unverifiable.setAddressComplete(true);
        BadRequestException failed =
                assertThrows(BadRequestException.class, () -> controller.setPassword(unverifiable));
        assertEquals("the address cannot be verified", failed.getMessage());
        assertEquals(REVIEW, unverifiable.status());

        BadRequestException rejected =
                assertThrows(BadRequestException.class, () -> controller.setPassword(unverifiable));
        assertEquals("a reviewed account is closed", rejected.getMessage());
    }
}
