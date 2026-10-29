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

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;

/**
 * The account machine: an onboarding whose steps after the password are automatic. Setting the password moves a new
 * account to PASSWORD_SET, and from there the account goes on alone as far as its data allows: its personal info, its
 * address, which the verifier checks, and its activation, premium first. An address the verifier cannot verify flags
 * the account, and the chain goes on into review, which the operation close ends. The automatic edges have no
 * operation: the chain runs them, and so does {@code advance}. The processor generates AccountsRuntime.
 */
@GenerateGraphApi
@AccountsGraph.Definition(initial = NEW, terminals = {ACTIVE, PREMIUM, CLOSED}, allowsCycle = PASSWORD_SET,
        state = AccountRefs.Status.class, id = AccountRefs.Id.class)
@AccountsGraph.StatePolicy(state = NEW, otherwise = "a new account sets its password")
@AccountsGraph.StatePolicy(state = PASSWORD_SET, otherwise = "an account completes its personal info")
@AccountsGraph.StatePolicy(state = INFO_SET, otherwise = "an account completes its address")
@AccountsGraph.StatePolicy(state = ADDRESS_SET, otherwise = "an account is activated")
@AccountsGraph.StatePolicy(state = FLAGGED, otherwise = "a flagged account is reviewed")
@AccountsGraph.StatePolicy(state = REVIEW, otherwise = "a reviewed account is closed")
@AccountsGraph.Transition(id = COMPLETE_INFO, from = PASSWORD_SET, to = INFO_SET, automatic = true,
        guards = AccountActionsRefs.InfoComplete.class)
@AccountsGraph.Transition(id = COMPLETE_ADDRESS, from = INFO_SET, to = ADDRESS_SET, automatic = true,
        guards = AccountActionsRefs.AddressComplete.class, action = AccountActionsRefs.VerifyAddress.class,
        recoveries = @AccountsGraph.Recovery(kind = AddressFailures.Unverifiable.class, transition = FLAG,
                continueChain = true))
@AccountsGraph.Transition(id = FLAG, from = INFO_SET, to = FLAGGED)
@AccountsGraph.Transition(id = REQUEST_REVIEW, from = FLAGGED, to = REVIEW, automatic = true)
@AccountsGraph.Transition(id = ACTIVATE_PREMIUM, from = ADDRESS_SET, to = PREMIUM, automatic = true,
        guards = AccountActionsRefs.Premium.class)
@AccountsGraph.Transition(id = ACTIVATE, from = ADDRESS_SET, to = ACTIVE, automatic = true)
@AccountsGraph.AutomaticOrder(from = ADDRESS_SET, transitions = {ACTIVATE_PREMIUM, ACTIVATE})
public interface Accounts extends Machine<Account, AccountStatus, AccountTransition> {

    @AccountsGraph.Transition(id = SET_PASSWORD, from = NEW, to = PASSWORD_SET)
    Outcome<Account, AccountStatus, AccountTransition> setPassword(Account account);

    /** Records that the account's data changed: a self-loop, whose commit runs the chain like any other. */
    @AccountsGraph.Transition(id = TOUCH, from = PASSWORD_SET, to = PASSWORD_SET)
    Outcome<Account, AccountStatus, AccountTransition> touch(Account account);

    @AccountsGraph.Transition(id = CLOSE, from = REVIEW, to = CLOSED)
    Outcome<Account, AccountStatus, AccountTransition> close(Account account);
}
