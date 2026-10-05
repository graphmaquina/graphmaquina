package io.github.graphmaquina.test.snapshot;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardRef;
import io.github.graphmaquina.api.StatePolicy;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchRule;
import io.github.graphmaquina.api.WatchSpec;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The structure of a graph as a flat, versioned map of path to value: the machine identity, whether any edge
 * is asynchronous, the states and their order, the catalogue of transition identifiers, the initial and
 * terminal states, every declared edge under {@code /transitions/<from>/<id>} with its destination, kind,
 * action, guards, recovery identifier, the exception types treated as failures, timeout and hook, the
 * rejection policy of every state, and the watches with their peers and rules. It carries what a diagram
 * leaves out, so two definitions that render identically still differ here, and it carries names, types and
 * declared values such as timeouts and rejection reasons, never lambdas or the identity of a callback, so a
 * snapshot is stable across runs.
 *
 * <p>Absence is not recorded: a pair of an origin and an identifier with no edge has no path at all. What the
 * state would answer such a request with lives in its policy instead.
 *
 * <p>The map is immutable and always in canonical order — ascending {@link String#compareTo} of the path —
 * which is also the order {@link #serialize()} writes and the order a parsed snapshot comes back in.
 */
public final class GraphSnapshot {

    private final Map<String, String> fields;

    private GraphSnapshot(Map<String, String> fields) {
        this.fields = Collections.unmodifiableMap(new TreeMap<>(fields));
    }

    /** Reads the definition's own accessors only: no callback of the graph is ever invoked. */
    public static GraphSnapshot capture(GraphDefinition<?, ?, ?> definition) {
        return captureTyped(Objects.requireNonNull(definition, "definition"));
    }

    /**
     * Reads back a serialized snapshot.
     *
     * @throws IllegalArgumentException if the text is not a well-formed snapshot of the current version,
     *     including a snapshot of an earlier format, which is reported with what to do about it
     */
    public static GraphSnapshot parse(String text) {
        return new GraphSnapshot(SnapshotCodec.decode(Objects.requireNonNull(text, "text")));
    }

    public String serialize() {
        return SnapshotCodec.encode(fields);
    }

    public Map<String, String> fields() {
        return fields;
    }

    private static <E, S extends Enum<S>, T extends Enum<T>> GraphSnapshot captureTyped(
            GraphDefinition<E, S, T> definition) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("/machine/entity", definition.key().entity().getName());
        fields.put("/machine/states", definition.key().states().getName());
        fields.put("/machine/transitions", definition.key().transitions().getName());
        fields.put("/initial", definition.initial().name());
        fields.put("/hasAsync", Boolean.toString(definition.hasAsync()));
        List<S> states = definition.graph().nodes();
        fields.put("/states/count", Integer.toString(states.size()));
        for (int index = 0; index < states.size(); index++) {
            fields.put("/states/" + index, states.get(index).name());
        }
        T[] catalog = definition.key().transitions().getEnumConstants();
        fields.put("/catalog/count", Integer.toString(catalog.length));
        for (int index = 0; index < catalog.length; index++) {
            fields.put("/catalog/" + index, catalog[index].name());
        }
        for (S terminal : definition.terminals()) {
            fields.put("/terminals/" + terminal.name(), "true");
        }
        for (TransitionSpec<E, S, T> transition : definition.transitions()) {
            String path = "/transitions/" + transition.from().name() + "/" + transition.transition().name();
            fields.put(path + "/to", transition.to().name());
            fields.put(path + "/kind", transition.kind().name());
            transition.onFailure().ifPresent(id -> fields.put(path + "/onFailure", id.name()));
            captureCallbacks(fields, transition, path);
        }
        for (S state : states) {
            capturePolicy(fields, definition.policy(state));
            definition.watch(state).ifPresent(watch -> captureWatch(fields, watch));
        }
        return new GraphSnapshot(fields);
    }

    /**
     * The action, guards and hook the runtime would run, by name and mode only, plus the failure types and the
     * timeout of the edge: nothing here is ever invoked.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> void captureCallbacks(
            Map<String, String> fields, TransitionSpec<E, S, T> transition, String path) {
        transition.action().ifPresent(action -> {
            fields.put(path + "/action/name", action.name());
            fields.put(path + "/action/async", Boolean.toString(action.isAsync()));
        });
        fields.put(path + "/guards/count", Integer.toString(transition.guards().size()));
        for (int index = 0; index < transition.guards().size(); index++) {
            GuardRef<E, S, T> guard = transition.guards().get(index);
            fields.put(path + "/guards/" + index + "/name", guard.name());
            fields.put(path + "/guards/" + index + "/async", Boolean.toString(guard.isAsync()));
        }
        fields.put(path + "/failsOn/count", Integer.toString(transition.failsOn().size()));
        for (int index = 0; index < transition.failsOn().size(); index++) {
            fields.put(path + "/failsOn/" + index, transition.failsOn().get(index).getName());
        }
        transition.timeout().ifPresent(value -> fields.put(path + "/timeout", value.toString()));
        transition.afterCommit().ifPresent(value -> fields.put(path + "/afterCommit", value.name()));
    }

    private static <S extends Enum<S>, T extends Enum<T>> void capturePolicy(
            Map<String, String> fields, StatePolicy<S, T> policy) {
        String path = "/policies/" + policy.state().name();
        fields.put(path + "/otherwise", policy.otherwise());
        for (Map.Entry<T, String> rejection : policy.rejectionReasons().entrySet()) {
            fields.put(path + "/rejects/" + rejection.getKey().name(), rejection.getValue());
        }
    }

    private static <E, S extends Enum<S>, T extends Enum<T>> void captureWatch(
            Map<String, String> fields, WatchSpec<E, S, T, ?, ?, ?> watch) {
        String path = "/watches/" + watch.state().name();
        fields.put(path + "/peer/entity", watch.peer().entity().getName());
        fields.put(path + "/peer/states", watch.peer().states().getName());
        fields.put(path + "/peer/transitions", watch.peer().transitions().getName());
        fields.put(path + "/rules/count", Integer.toString(watch.rules().size()));
        for (int index = 0; index < watch.rules().size(); index++) {
            WatchRule<E, S, T, ?, ?, ?> rule = watch.rules().get(index);
            fields.put(path + "/rules/" + index + "/condition", rule.condition().name());
            fields.put(path + "/rules/" + index + "/transition", rule.transition().name());
        }
    }
}
