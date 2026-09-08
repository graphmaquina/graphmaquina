package io.stategraph.error;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import lombok.Getter;

/** A peer was referenced by a facade type that no machine of the registry declared with as(...). */
@Getter
public final class UnknownFacadeException extends StateMachineException {

    private final Class<?> requested;
    private final List<Class<?>> registered;

    public UnknownFacadeException(Class<?> requested, Collection<Class<?>> registered) {
        super("no machine is registered for the facade " + Objects.requireNonNull(requested, "requested").getName()
                + "; registered facades: " + names(Objects.requireNonNull(registered, "registered")));
        this.requested = requested;
        this.registered = List.copyOf(registered);
    }

    private static List<String> names(Collection<Class<?>> types) {
        List<String> names = new ArrayList<>();
        for (Class<?> type : types) {
            names.add(type.getName());
        }
        return names;
    }
}
