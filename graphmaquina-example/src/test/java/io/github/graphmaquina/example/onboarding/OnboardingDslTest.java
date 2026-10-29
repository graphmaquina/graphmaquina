package io.github.graphmaquina.example.onboarding;

import static io.github.graphmaquina.example.onboarding.AccountStatus.ACTIVE;
import static io.github.graphmaquina.example.onboarding.AccountStatus.ADDRESS_SET;
import static io.github.graphmaquina.example.onboarding.AccountStatus.CLOSED;
import static io.github.graphmaquina.example.onboarding.AccountStatus.FLAGGED;
import static io.github.graphmaquina.example.onboarding.AccountStatus.INFO_SET;
import static io.github.graphmaquina.example.onboarding.AccountStatus.NEW;
import static io.github.graphmaquina.example.onboarding.AccountStatus.PASSWORD_SET;
import static io.github.graphmaquina.example.onboarding.AccountStatus.PREMIUM;
import static io.github.graphmaquina.example.onboarding.AccountStatus.REVIEW;
import static io.github.graphmaquina.example.onboarding.AccountTransition.ACTIVATE;
import static io.github.graphmaquina.example.onboarding.AccountTransition.ACTIVATE_PREMIUM;
import static io.github.graphmaquina.example.onboarding.AccountTransition.CLOSE;
import static io.github.graphmaquina.example.onboarding.AccountTransition.COMPLETE_ADDRESS;
import static io.github.graphmaquina.example.onboarding.AccountTransition.COMPLETE_INFO;
import static io.github.graphmaquina.example.onboarding.AccountTransition.FLAG;
import static io.github.graphmaquina.example.onboarding.AccountTransition.REQUEST_REVIEW;
import static io.github.graphmaquina.example.onboarding.AccountTransition.SET_PASSWORD;
import static io.github.graphmaquina.example.onboarding.AccountTransition.TOUCH;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.StateMachines;
import io.github.graphmaquina.test.GraphSnapshots;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The onboarding's graph written with the DSL in place of the annotations of {@link Accounts}: the structural
 * snapshot of the annotated graph pins it, and a machine built from it chains the same steps.
 */
class OnboardingDslTest {

    static GraphDefinition<Account, AccountStatus, AccountTransition> graph(AccountActions actions) {
        return StateGraph.define(MachineKey.of(Account.class, AccountStatus.class, AccountTransition.class))
                .initial(NEW).terminal(ACTIVE).terminal(PREMIUM).terminal(CLOSED).allowsCycle(PASSWORD_SET)
                .statePolicy(NEW, "a new account sets its password")
                .statePolicy(PASSWORD_SET, "an account completes its personal info")
                .statePolicy(INFO_SET, "an account completes its address")
                .statePolicy(ADDRESS_SET, "an account is activated")
                .statePolicy(FLAGGED, "a flagged account is reviewed")
                .statePolicy(REVIEW, "a reviewed account is closed")
                .transition(SET_PASSWORD, NEW, PASSWORD_SET).endTransition()
                .transition(TOUCH, PASSWORD_SET, PASSWORD_SET).endTransition()
                .transition(COMPLETE_INFO, PASSWORD_SET, INFO_SET).automatic()
                    .guard("infoComplete", actions::infoComplete)
                    .endTransition()
                .transition(COMPLETE_ADDRESS, INFO_SET, ADDRESS_SET).automatic()
                    .guard("addressComplete", actions::addressComplete)
                    .action("verifyAddress", actions::verifyAddress)
                    .recoverAndContinue(AddressFailures.Unverifiable.class, FLAG)
                    .endTransition()
                .transition(FLAG, INFO_SET, FLAGGED).endTransition()
                .transition(REQUEST_REVIEW, FLAGGED, REVIEW).automatic().endTransition()
                .transition(CLOSE, REVIEW, CLOSED).endTransition()
                .transition(ACTIVATE_PREMIUM, ADDRESS_SET, PREMIUM).automatic()
                    .guard("premium", actions::premium)
                    .endTransition()
                .transition(ACTIVATE, ADDRESS_SET, ACTIVE).automatic().endTransition()
                .automaticOrder(ADDRESS_SET, List.of(ACTIVATE_PREMIUM, ACTIVATE))
                .build();
    }

    @Test
    void theDslDeclaresTheAnnotatedGraph() {
        GraphSnapshots.assertMatches(graph(new AccountActions(new FakeAddressVerifier())), "contracts/account.graph");
    }

    @Test
    void aMachineOfTheDslGraphChainsTheSameSteps() {
        StateMachine<Account, AccountStatus, AccountTransition> accounts =
                StateMachines.from(graph(new AccountActions(new FakeAddressVerifier())))
                        .state(Account::status, Account::setStatus).identity(Account::id).build();
        Account account = new Account("d1");
        account.setInfoComplete(true);
        account.setAddressComplete(true);

        accounts.execute(account, SET_PASSWORD);

        assertEquals(ACTIVE, account.status());
    }
}
