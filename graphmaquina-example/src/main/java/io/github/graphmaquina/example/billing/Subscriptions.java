package io.github.graphmaquina.example.billing;

import static io.github.graphmaquina.example.billing.SubscriptionStatus.ACTIVE;
import static io.github.graphmaquina.example.billing.SubscriptionStatus.LAPSED;
import static io.github.graphmaquina.example.billing.SubscriptionStatus.RENEWAL;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.annotation.GraphPeers;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;

/**
 * The subscription machine: {@code RENEWAL} holds the renewal payment, the same {@link Payments} the bookings bind.
 * Every renewal enters {@code RENEWAL} again, which starts the payment again at its initial state.
 */
@GenerateGraphApi
@GraphPeers(Payments.class)
@SubscriptionsGraph.Definition(initial = ACTIVE, terminals = LAPSED, allowsCycle = {ACTIVE, RENEWAL},
        state = SubscriptionRefs.Status.class, id = SubscriptionRefs.Id.class)
@SubscriptionsGraph.StatePolicy(state = ACTIVE,  otherwise = "a renewal is started first")
@SubscriptionsGraph.StatePolicy(state = RENEWAL, otherwise = "the renewal payment decides")
@SubscriptionsGraph.Transition(id = SubscriptionTransition.RENEWED, from = RENEWAL, to = ACTIVE)
@SubscriptionsGraph.Transition(id = SubscriptionTransition.LAPSE, from = RENEWAL, to = LAPSED)
@SubscriptionsPayments.Submachine(state = RENEWAL, child = SubscriptionRefs.Renewal.class,
        parentLookup = BillingLookupsRefs.SubscriptionOf.class,
        exits = {@SubscriptionsPayments.Exit(on = PaymentStatus.CAPTURED, transition = SubscriptionTransition.RENEWED),
                 @SubscriptionsPayments.Exit(on = PaymentStatus.DECLINED, transition = SubscriptionTransition.LAPSE),
                 @SubscriptionsPayments.Exit(on = PaymentStatus.VOIDED, transition = SubscriptionTransition.LAPSE)})
public interface Subscriptions extends Machine<Subscription, SubscriptionStatus, SubscriptionTransition> {

    @SubscriptionsGraph.Transition(id = SubscriptionTransition.START_RENEWAL, from = ACTIVE, to = RENEWAL)
    Outcome<Subscription, SubscriptionStatus, SubscriptionTransition> startRenewal(Subscription subscription);
}
