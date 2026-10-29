package io.github.graphmaquina.fixtures;

import static io.github.graphmaquina.fixtures.AccountStatus.ACTIVE;
import static io.github.graphmaquina.fixtures.AccountStatus.ADDRESS_SET;
import static io.github.graphmaquina.fixtures.AccountStatus.CLOSED;
import static io.github.graphmaquina.fixtures.AccountStatus.FLAGGED;
import static io.github.graphmaquina.fixtures.AccountStatus.INFO_SET;
import static io.github.graphmaquina.fixtures.AccountStatus.NEW;
import static io.github.graphmaquina.fixtures.AccountStatus.PASSWORD_SET;
import static io.github.graphmaquina.fixtures.AccountStatus.PREMIUM;
import static io.github.graphmaquina.fixtures.AccountStatus.REVIEW;
import static io.github.graphmaquina.fixtures.AccountTransition.ACTIVATE;
import static io.github.graphmaquina.fixtures.AccountTransition.ACTIVATE_PREMIUM;
import static io.github.graphmaquina.fixtures.AccountTransition.CLOSE;
import static io.github.graphmaquina.fixtures.AccountTransition.COMPLETE_ADDRESS;
import static io.github.graphmaquina.fixtures.AccountTransition.COMPLETE_INFO;
import static io.github.graphmaquina.fixtures.AccountTransition.FLAG;
import static io.github.graphmaquina.fixtures.AccountTransition.REQUEST_REVIEW;
import static io.github.graphmaquina.fixtures.AccountTransition.SET_PASSWORD;
import static io.github.graphmaquina.fixtures.AccountTransition.TOUCH;
import static io.github.graphmaquina.fixtures.Keys.ACCOUNTS;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.runtime.StateMachines;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Graph definitions of the account fixture machine, an onboarding whose steps after the password are automatic:
 * NEW -SET_PASSWORD-> PASSWORD_SET, which TOUCH loops on; PASSWORD_SET -COMPLETE_INFO-> INFO_SET, automatic and
 * guarded by infoComplete; INFO_SET -COMPLETE_ADDRESS-> ADDRESS_SET, automatic and guarded by addressComplete, whose
 * action asks the {@link AddressVerifier} and whose {@link AddressFailures.Unverifiable} failure recovers by FLAG to
 * FLAGGED; FLAGGED -REQUEST_REVIEW-> REVIEW, automatic and unguarded; REVIEW -CLOSE-> CLOSED; and from ADDRESS_SET,
 * in this order, ACTIVATE_PREMIUM to PREMIUM, automatic and guarded by premium, then ACTIVATE to ACTIVE, automatic and
 * unguarded. ACTIVE, PREMIUM and CLOSED are terminal.
 */
public final class AccountGraphs {

    /** The reason the guard infoComplete rejects with. */
    public static final String INFO_INCOMPLETE = "personal info is incomplete";

    /** The reason the guard addressComplete rejects with. */
    public static final String ADDRESS_INCOMPLETE = "address is incomplete";

    /** The reason the guard premium rejects with. */
    public static final String NOT_PREMIUM = "the account is not premium";

    private AccountGraphs() { }

    /** The failure kinds the address verifier reports. */
    public static final class AddressFailures {

        private AddressFailures() { }

        /** The address could not be verified. */
        public record Unverifiable() implements FailureKind { }
    }

    /**
     * Test address verifier, the action of COMPLETE_ADDRESS, with a configurable behaviour, which verifies by default:
     * a test makes it return a failure, with a kind or without, or throw. It counts its calls.
     */
    public static final class AddressVerifier {

        public Function<Account, ActionOutcome> behaviour = (Account account) -> ActionOutcome.ok();
        public int calls;

        /** What {@link #behaviour} answers for {@code account}, once the call is counted. */
        public ActionOutcome verify(Account account) {
            calls++;
            return behaviour.apply(account);
        }
    }

    /** The onboarding, whose Unverifiable route to FLAGGED does not continue the automatic chain. */
    public static GraphDefinition<Account, AccountStatus, AccountTransition> onboarding(AddressVerifier verifier) {
        return graph(verifier, false, Optional.empty());
    }

    /** {@link #onboarding} whose SET_PASSWORD runs {@code passwordSet} as its after-commit hook, named passwordSet. */
    public static GraphDefinition<Account, AccountStatus, AccountTransition> onboarding(
            AddressVerifier verifier, Consumer<Account> passwordSet) {
        return graph(verifier, false, Optional.of(passwordSet));
    }

    /** {@link #onboarding} whose Unverifiable route to FLAGGED continues the automatic chain. */
    public static GraphDefinition<Account, AccountStatus, AccountTransition> onboardingContinuing(
            AddressVerifier verifier) {
        return graph(verifier, true, Optional.empty());
    }

    /** A standalone machine over {@code graph}, whose identity is the account's id. */
    public static StateMachine<Account, AccountStatus, AccountTransition> machine(
            GraphDefinition<Account, AccountStatus, AccountTransition> graph) {
        return StateMachines.from(graph).state(Account::status, Account::setStatus).identity(Account::id).build();
    }

    private static GraphDefinition<Account, AccountStatus, AccountTransition> graph(
            AddressVerifier verifier, boolean continuing, Optional<Consumer<Account>> passwordSet) {
        TransitionBuilder<Account, AccountStatus, AccountTransition> password = StateGraph.define(ACCOUNTS)
                .initial(NEW).terminal(ACTIVE).terminal(PREMIUM).terminal(CLOSED).allowsCycle(PASSWORD_SET)
                .statePolicy(NEW, "a new account sets its password")
                .statePolicy(PASSWORD_SET, "an account completes its personal info")
                .statePolicy(INFO_SET, "an account completes its address")
                .statePolicy(ADDRESS_SET, "an account is activated")
                .statePolicy(FLAGGED, "a flagged account is reviewed")
                .statePolicy(REVIEW, "a reviewed account is closed")
                .transition(SET_PASSWORD, NEW, PASSWORD_SET);
        TransitionBuilder<Account, AccountStatus, AccountTransition> address = hooked(password, passwordSet)
                .endTransition()
                .transition(TOUCH, PASSWORD_SET, PASSWORD_SET).endTransition()
                .transition(COMPLETE_INFO, PASSWORD_SET, INFO_SET).automatic()
                    .guard("infoComplete", (GuardContext<Account, AccountStatus, AccountTransition> ctx) ->
                            ctx.entity().infoComplete() ? GuardResult.pass() : GuardResult.reject(INFO_INCOMPLETE))
                    .endTransition()
                .transition(COMPLETE_ADDRESS, INFO_SET, ADDRESS_SET).automatic()
                    .guard("addressComplete", (GuardContext<Account, AccountStatus, AccountTransition> ctx) ->
                            ctx.entity().addressComplete()
                                    ? GuardResult.pass() : GuardResult.reject(ADDRESS_INCOMPLETE))
                    .action("verify", (TransitionContext<Account, AccountStatus, AccountTransition> ctx) ->
                            verifier.verify(ctx.entity()));
        address = continuing
                ? address.recoverAndContinue(AddressFailures.Unverifiable.class, FLAG)
                : address.recover(AddressFailures.Unverifiable.class, FLAG);
        return address.endTransition()
                .transition(FLAG, INFO_SET, FLAGGED).endTransition()
                .transition(REQUEST_REVIEW, FLAGGED, REVIEW).automatic().endTransition()
                .transition(CLOSE, REVIEW, CLOSED).endTransition()
                .transition(ACTIVATE_PREMIUM, ADDRESS_SET, PREMIUM).automatic()
                    .guard("premium", (GuardContext<Account, AccountStatus, AccountTransition> ctx) ->
                            ctx.entity().premium() ? GuardResult.pass() : GuardResult.reject(NOT_PREMIUM))
                    .endTransition()
                .transition(ACTIVATE, ADDRESS_SET, ACTIVE).automatic().endTransition()
                .automaticOrder(ADDRESS_SET, List.of(ACTIVATE_PREMIUM, ACTIVATE))
                .build();
    }

    /** {@code edge} with {@code hook} as its after-commit hook, named passwordSet, when there is one. */
    private static TransitionBuilder<Account, AccountStatus, AccountTransition> hooked(
            TransitionBuilder<Account, AccountStatus, AccountTransition> edge, Optional<Consumer<Account>> hook) {
        return hook.isPresent() ? edge.afterCommit("passwordSet", hook.get()) : edge;
    }
}
