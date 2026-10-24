package io.github.graphmaquina.test.snapshot;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardRef;
import io.github.graphmaquina.api.RecoveryRule;
import io.github.graphmaquina.api.StatePolicy;
import io.github.graphmaquina.api.SubmachineSpec;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchRule;
import io.github.graphmaquina.api.WatchSpec;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import lombok.Getter;

/**
 * The structure of a graph as a flat, versioned map of path to value: the machine identity, whether any edge
 * is asynchronous, the states and their order, the catalogue of transition identifiers, the initial and
 * terminal states, every declared edge under {@code /transitions/<from>/<id>} with its destination, kind,
 * action, guards, fallback recovery identifier, the exception types its failsOn lists, recovery rules, timeout
 * and hook, the rejection policy of every state, the watches with their peers and rules, and the sub-machines
 * with their child machines and exits. It carries what a diagram leaves out, so two definitions that render
 * identically still differ here, and it carries names, types and declared values such as timeouts and rejection
 * reasons, never lambdas or the identity of a callback, so a snapshot is stable across runs.
 *
 * <p>Absence is not recorded: a pair of an origin and an identifier with no edge has no path at all. What the
 * state would answer such a request with lives in its policy instead.
 *
 * <p>The recovery rules of an edge live under {@code /transitions/<from>/<id>/recoveries}: their {@code count},
 * then for the rule at index {@code <i>} its failure kind at {@code <i>/kind}, its recovery identifier at
 * {@code <i>/transition}, and its throwable types at {@code <i>/failsOn/count} and {@code <i>/failsOn/<j>}. Kinds
 * and types are binary names, as {@link Class#getName()} gives them. An edge without rules has no
 * {@code recoveries} path at all, not even a count of zero. The indexes follow the order the rules were declared
 * in, although that order selects nothing: a returned failure is routed by its kind and a thrown one by the most
 * specific declared type. Two definitions that only reorder the rules of an edge therefore route every failure
 * alike and still differ here, at the index paths.
 *
 * <p>The sub-machine bound to a state lives under {@code /submachines/<state>}: its child machine at
 * {@code child/entity}, {@code child/states} and {@code child/transitions}, as a watch records its peer, and at
 * {@code exits/<terminal>} the identifier that each terminal state of the child exits through. A state without one
 * has no path, and neither lookup of the binding is ever invoked. The child's own graph is not recorded, so two
 * parents over children of the same key whose graphs differ capture the same snapshot; capture the child's
 * definition on its own to pin it.
 *
 * <p>The map is immutable and always in canonical order — ascending {@link String#compareTo} of the path —
 * which is also the order {@link #serialize()} writes and the order a parsed snapshot comes back in.
 */
@Getter
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
     * @throws IllegalArgumentException if the text is not a well-formed snapshot of the current version
     */
    public static GraphSnapshot parse(String text) {
        return new GraphSnapshot(SnapshotCodec.decode(Objects.requireNonNull(text, "text")));
    }

    public String serialize() {
        return SnapshotCodec.encode(fields);
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
            captureRecoveries(fields, transition.recoveryRules(), path);
        }
        for (S state : states) {
            capturePolicy(fields, definition.policy(state));
            definition.watch(state).ifPresent(watch -> captureWatch(fields, watch));
            definition.submachine(state).ifPresent(
                    (SubmachineSpec<E, S, T, ?, ?, ?> submachine) -> captureSubmachine(fields, submachine));
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

    /** The recovery rules of an edge, indexed in declaration order; an edge without rules records nothing here. */
    private static <T extends Enum<T>> void captureRecoveries(
            Map<String, String> fields, List<RecoveryRule<T>> rules, String path) {
        if (rules.isEmpty()) {
            return;
        }
        fields.put(path + "/recoveries/count", Integer.toString(rules.size()));
        for (int index = 0; index < rules.size(); index++) {
            RecoveryRule<T> rule = rules.get(index);
            String rulePath = path + "/recoveries/" + index;
            fields.put(rulePath + "/kind", rule.kind().getName());
            fields.put(rulePath + "/transition", rule.transition().name());
            fields.put(rulePath + "/failsOn/count", Integer.toString(rule.failsOn().size()));
            for (int type = 0; type < rule.failsOn().size(); type++) {
                fields.put(rulePath + "/failsOn/" + type, rule.failsOn().get(type).getName());
            }
        }
    }

    private static <S extends Enum<S>, T extends Enum<T>> void capturePolicy(
            Map<String, String> fields, StatePolicy<S, T> policy) {
        String path = "/policies/" + policy.state().name();
        fields.put(path + "/otherwise", policy.otherwise());
        for (Map.Entry<T, String> rejection : policy.rejectionReasons().entrySet()) {
            fields.put(path + "/rejects/" + rejection.getKey().name(), rejection.getValue());
        }
    }

    /** The child machine of a sub-machine, by its key's three types, and the exit of each terminal state. */
    private static <E, S extends Enum<S>, T extends Enum<T>, P, Q extends Enum<Q>, U extends Enum<U>>
            void captureSubmachine(Map<String, String> fields, SubmachineSpec<E, S, T, P, Q, U> submachine) {
        String path = "/submachines/" + submachine.state().name();
        fields.put(path + "/child/entity", submachine.childKey().entity().getName());
        fields.put(path + "/child/states", submachine.childKey().states().getName());
        fields.put(path + "/child/transitions", submachine.childKey().transitions().getName());
        for (Map.Entry<Q, T> exit : submachine.exits().entrySet()) {
            fields.put(path + "/exits/" + exit.getKey().name(), exit.getValue().name());
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
