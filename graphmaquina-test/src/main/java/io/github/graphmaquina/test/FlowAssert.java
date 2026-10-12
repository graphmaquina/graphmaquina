package io.github.graphmaquina.test;

import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.test.internal.Checks;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Chainable assertion over the flow of a call, obtained from {@link BaseOutcomeAssert#flow()} on any outcome view, from
 * {@link BaseCallFailureAssert#flow()} on any call-failure view, or from {@link #assertThat(FlowView)} for a flow a
 * test holds on its own, such as the one of a {@code CommitContext} it built to call a hook by hand. The entity of the
 * root call is compared by reference and a value by equality; a value is read with {@link FlowView#get(FlowKey)}, so
 * any key equal to the one that put it reads it, whatever its visibility. A key that holds no value reports
 * {@code Optional.empty} as its actual value, and a key that holds another value reports {@code Optional} of that
 * value, so the two failures read apart. The view reads the flow each time an expectation runs, not when it is built,
 * and {@link #trail()} opens the trail of the call.
 */
public final class FlowAssert {

    private static final String ROOT = "flow";
    private static final String NONE = "none";

    private final String context;
    private final Optional<FlowView> actual;

    /** The view of a flow that came from another view, which names it as that view's own. */
    FlowAssert(String context, Optional<FlowView> actual) {
        this.context = context;
        this.actual = actual;
    }

    public static FlowAssert assertThat(FlowView actual) {
        return new FlowAssert(ROOT, Optional.ofNullable(actual));
    }

    /**
     * The entity of the root call is this very instance, so a distinct instance that compares equal does not satisfy
     * it; when the two print alike, the failure names each by its class and identity. {@link FlowView#empty()} and a
     * flow that belongs to no call hold no entity, so they satisfy it for none, and the failure reports {@code none} as
     * the actual value.
     */
    public FlowAssert hasEntitySameAs(Object expected) {
        Objects.requireNonNull(expected, "expected");
        FlowView flow = Checks.present(context, "entity", expected, actual);
        Optional<Object> entity = flow.entity();
        if (entity.isEmpty()) {
            Checks.fail(context, "entity", expected, NONE);
        }
        Checks.same(context, "entity", expected, entity.get());
        return this;
    }

    /** The value the flow holds for {@code key} is equal to {@code expected}, by {@link Object#equals(Object)}. */
    public <V> FlowAssert hasValue(FlowKey<V> key, V expected) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(expected, "expected");
        Optional<V> wanted = Optional.of(expected);
        String property = "value of " + key;
        FlowView flow = Checks.present(context, property, wanted, actual);
        Checks.equal(context, property, wanted, flow.get(key));
        return this;
    }

    /** The flow holds no value for {@code key}, nor for any key equal to it. */
    public FlowAssert hasNoValue(FlowKey<?> key) {
        Objects.requireNonNull(key, "key");
        Optional<Object> none = Optional.empty();
        String property = "value of " + key;
        FlowView flow = Checks.present(context, property, none, actual);
        Checks.equal(context, property, none, flow.get(key));
        return this;
    }

    /**
     * The keys that hold a value, in the order {@link FlowView#keys()} lists them, which is the order of the writes
     * that gave each its value. No argument expects a flow that holds no value.
     */
    public FlowAssert hasKeysExactly(FlowKey<?>... keys) {
        Objects.requireNonNull(keys, "keys");
        List<FlowKey<?>> wanted = List.of(keys);
        FlowView flow = Checks.present(context, "keys", wanted, actual);
        Checks.equal(context, "keys", wanted, List.copyOf(flow.keys()));
        return this;
    }

    /** The trail of the call: empty unless the root machine of the call was built with {@code recordTrail}. */
    public TrailAssert trail() {
        return new TrailAssert(context + ".trail", actual.map(FlowView::trail));
    }
}
