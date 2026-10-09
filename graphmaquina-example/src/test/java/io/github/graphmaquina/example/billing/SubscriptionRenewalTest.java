package io.github.graphmaquina.example.billing;

import static io.github.graphmaquina.test.GraphAssertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The payment machine the bookings bind is bound again by the subscriptions. A subscription charges the same
 * renewal payment in every period: entering {@code RENEWAL} again starts it again from the terminal state it reached
 * the period before.
 */
class SubscriptionRenewalTest {

    final BillingLookups lookups = new BillingLookups();
    final BillingMachines billing = BillingMachinesRuntime.builder(Duration.ofSeconds(10),
                    new BookingActions(new FakeRoomDesk()), lookups)
            .build();
    final Subscriptions subscriptions = billing.subscriptions();
    final Payments payments = billing.payments();

    @AfterEach
    void close() {
        billing.close();
    }

    @Test
    void aSubscriptionRestartsItsRenewalPaymentInEveryPeriod() {
        Payment renewal = new Payment("s1-renewal");
        Subscription subscription = new Subscription("s1", renewal);
        lookups.register(subscription);

        assertThat(subscriptions.startRenewal(subscription)).isApplied().movedTo(SubscriptionStatus.RENEWAL)
                .starts().forChild(PaymentsRuntime.KEY, renewal).single()
                .hadPrevious(PaymentStatus.AUTHORIZING)
                .startedAt(PaymentStatus.AUTHORIZING)
                .byParent(SubscriptionsRuntime.KEY, subscription, SubscriptionStatus.RENEWAL);
        assertThat(payments.capture(renewal)).isApplied()
                .records().forEntity(SubscriptionsRuntime.KEY, subscription).single()
                .hasTransition(SubscriptionTransition.RENEWED).to(SubscriptionStatus.ACTIVE).hasDepth(1);

        assertThat(subscriptions.startRenewal(subscription)).isApplied()
                .starts().forChild(PaymentsRuntime.KEY, renewal).single()
                .hadPrevious(PaymentStatus.CAPTURED)
                .startedAt(PaymentStatus.AUTHORIZING);
        assertThat(payments.decline(renewal)).isApplied()
                .records().forEntity(SubscriptionsRuntime.KEY, subscription).single()
                .hasTransition(SubscriptionTransition.LAPSE).to(SubscriptionStatus.LAPSED).hasDepth(1);
        assertThat(subscription, Subscription::status).isIn(SubscriptionStatus.LAPSED);
    }
}
