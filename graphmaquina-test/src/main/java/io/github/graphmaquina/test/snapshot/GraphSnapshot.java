package io.github.graphmaquina.test.snapshot;

import io.github.graphmaquina.api.EdgeSpec;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardRef;
import io.github.graphmaquina.api.WatchRule;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The structure of a graph as a flat, versioned map of path to value: the machine identity, the states
 * and their order, the initial and terminal states, every cell of the N×N matrix with its kind, reason,
 * action, guards, failure route, timeout and hook, and the watches with their peers and rules. It carries
 * what a diagram leaves out, so two definitions that render identically still differ here, and it carries
 * only names and types, never lambdas or the identity of a callback, so a snapshot is stable across runs.
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
    public static GraphSnapshot capture(GraphDefinition<?, ?> definition) {
        return captureTyped(Objects.requireNonNull(definition, "definition"));
    }

    /**
     * Reads back a serialized snapshot.
     *
     * @throws IllegalArgumentException if the text is not a well-formed snapshot of a known version
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

    private static <E, S extends Enum<S>> GraphSnapshot captureTyped(GraphDefinition<E, S> definition) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("/machine/entity", definition.key().entity().getName());
        fields.put("/machine/states", definition.key().states().getName());
        fields.put("/initial", definition.initial().name());
        fields.put("/hasAsync", Boolean.toString(definition.hasAsync()));
        S[] states = definition.key().states().getEnumConstants();
        fields.put("/states/count", Integer.toString(states.length));
        for (int index = 0; index < states.length; index++) {
            fields.put("/states/" + index, states[index].name());
        }
        for (S terminal : definition.terminals()) {
            fields.put("/terminals/" + terminal.name(), "true");
        }
        for (S from : states) {
            for (S to : states) {
                captureCell(fields, definition.edge(from, to), "/cells/" + from.name() + "/" + to.name());
            }
            definition.watch(from).ifPresent(watch -> {
                String prefix = "/watches/" + from.name();
                fields.put(prefix + "/peer/entity", watch.peer().entity().getName());
                fields.put(prefix + "/peer/states", watch.peer().states().getName());
                fields.put(prefix + "/rules/count", Integer.toString(watch.rules().size()));
                for (int index = 0; index < watch.rules().size(); index++) {
                    WatchRule<E, S, ?, ?> rule = watch.rules().get(index);
                    fields.put(prefix + "/rules/" + index + "/condition", rule.condition().name());
                    fields.put(prefix + "/rules/" + index + "/target", rule.target().name());
                }
            });
        }
        return new GraphSnapshot(fields);
    }

    private static <E, S extends Enum<S>> void captureCell(
            Map<String, String> fields, EdgeSpec<E, S> edge, String prefix) {
        fields.put(prefix + "/kind", edge.kind().name());
        fields.put(prefix + "/reason", edge.reason());
        edge.action().ifPresent(action -> {
            fields.put(prefix + "/action/name", action.name());
            fields.put(prefix + "/action/async", Boolean.toString(action.isAsync()));
        });
        fields.put(prefix + "/guards/count", Integer.toString(edge.guards().size()));
        for (int index = 0; index < edge.guards().size(); index++) {
            GuardRef<E, S> guard = edge.guards().get(index);
            fields.put(prefix + "/guards/" + index + "/name", guard.name());
            fields.put(prefix + "/guards/" + index + "/async", Boolean.toString(guard.isAsync()));
        }
        edge.onFailure().ifPresent(value -> fields.put(prefix + "/onFailure", value.name()));
        edge.timeout().ifPresent(value -> fields.put(prefix + "/timeout", value.toString()));
        edge.afterCommit().ifPresent(value -> fields.put(prefix + "/afterCommit", value.name()));
        fields.put(prefix + "/failsOn/count", Integer.toString(edge.failsOn().size()));
        for (int index = 0; index < edge.failsOn().size(); index++) {
            fields.put(prefix + "/failsOn/" + index, edge.failsOn().get(index).getName());
        }
    }
}
