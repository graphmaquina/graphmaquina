package io.github.graphmaquina.internal;

import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.error.CallFailedException;
import java.util.Optional;
import lombok.NonNull;

/**
 * The bridge through which the runtime attaches the frozen flow of a call to the failure the call throws: the
 * failure's constructors and its flow field belong to {@code io.github.graphmaquina.error}, and the runtime creates
 * failures in its own package too. {@link CallFailedException} registers its {@link Access} once, from its static
 * initializer, so the bridge is in place before any failure exists; a second registration is refused and changes
 * nothing. Not part of the API.
 */
public final class CallFailureFlows {

    /** Sets the flow a call failure hands out; implemented by {@link CallFailedException} alone. */
    public interface Access {

        /** Makes {@code flow} the flow that {@code failure} hands out. */
        void attach(CallFailedException failure, FlowView flow);
    }

    private static volatile Optional<Access> access = Optional.empty();

    private CallFailureFlows() { }

    /**
     * Registers the bridge. Called once, by {@link CallFailedException}'s static initializer.
     *
     * @throws NullPointerException if {@code access} is null
     * @throws IllegalStateException with "already registered" when a bridge is registered already
     */
    public static synchronized void register(@NonNull Access access) {
        if (CallFailureFlows.access.isPresent()) {
            throw new IllegalStateException("already registered");
        }
        CallFailureFlows.access = Optional.of(access);
    }

    /**
     * Makes {@code flow} the flow that {@code failure} hands out.
     *
     * @throws NullPointerException if {@code failure} or {@code flow} is null
     */
    public static void attach(@NonNull CallFailedException failure, @NonNull FlowView flow) {
        access.orElseThrow().attach(failure, flow);
    }
}
