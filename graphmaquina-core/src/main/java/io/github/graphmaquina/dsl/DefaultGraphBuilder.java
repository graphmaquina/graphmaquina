package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.StatePolicy;
import io.github.graphmaquina.api.TransitionKey;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchRule;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.error.InvalidGraphException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import lombok.NonNull;

/** Default, single-use implementation of {@link GraphBuilder}: accumulates drafts and assembles them on build(). */
final class DefaultGraphBuilder<E, S extends Enum<S>, T extends Enum<T>> implements GraphBuilder<E, S, T> {

    private final MachineKey<E, S, T> key;
    private final S[] states;
    private final T[] catalog;
    private Optional<S> initial = Optional.empty();
    private int initialDeclarations;
    private final Set<S> terminals;
    private final Set<S> cycleAllowed;
    private final Map<S, String> statePolicies;
    private final Map<S, Map<T, String>> rejections;
    private final List<TransitionSpec<E, S, T>> declared = new ArrayList<>();
    private final Map<S, WatchDraft<E, S, T, ?, ?, ?>> watches;
    private final Problems problems = new Problems();
    private boolean built;

    DefaultGraphBuilder(MachineKey<E, S, T> key) {
        this.key = key;
        this.states = key.states().getEnumConstants();
        this.catalog = key.transitions().getEnumConstants();
        this.terminals = EnumSet.noneOf(key.states());
        this.cycleAllowed = EnumSet.noneOf(key.states());
        this.statePolicies = new EnumMap<>(key.states());
        this.rejections = new EnumMap<>(key.states());
        this.watches = new EnumMap<>(key.states());
    }

    Problems problems() { return problems; }

    @Override
    public GraphBuilder<E, S, T> initial(@NonNull S state) {
        initialDeclarations++;
        initial = Optional.of(state);
        return this;
    }

    @Override
    public GraphBuilder<E, S, T> terminal(@NonNull S state) {
        terminals.add(state);
        return this;
    }

    @Override
    public GraphBuilder<E, S, T> allowsCycle(@NonNull S state) {
        cycleAllowed.add(state);
        return this;
    }

    @Override
    public GraphBuilder<E, S, T> statePolicy(@NonNull S state, @NonNull String otherwise) {
        if (statePolicies.putIfAbsent(state, otherwise) != null) {
            problems.add("SG102", state.ordinal(), -1, "the state " + state + " declares two policies");
        }
        return this;
    }

    @Override
    public GraphBuilder<E, S, T> reject(@NonNull S state, @NonNull T transition, @NonNull String reason) {
        Map<T, String> forState = rejections.computeIfAbsent(state, (S ignored) -> new LinkedHashMap<>());
        if (forState.putIfAbsent(transition, reason) != null) {
            problems.add("SG102", state.ordinal(), transition.ordinal(),
                    "the state " + state + " rejects " + transition + " twice");
        }
        return this;
    }

    @Override
    public TransitionBuilder<E, S, T> transition(@NonNull T transition, @NonNull S from, @NonNull S to) {
        return new TransitionDraft<>(this, from, transition, to);
    }

    @Override
    public <P, Q extends Enum<Q>, U extends Enum<U>> WatchBuilder<E, S, T, P, Q, U> watch(
            @NonNull S state, @NonNull MachineKey<P, Q, U> peer,
            @NonNull Function<? super E, ? extends Collection<? extends P>> peersOf,
            @NonNull Function<? super P, Optional<E>> ownerOf) {
        WatchDraft<E, S, T, P, Q, U> draft = new WatchDraft<>(this, state, peer, peersOf, ownerOf);
        if (watches.putIfAbsent(state, draft) != null) {
            problems.add("SG403", state.ordinal(), -1, "the state " + state + " declares two watches");
        }
        return draft;
    }

    /** Called by {@code endTransition()}: exactly one edge per closed draft. */
    void close(TransitionDraft<E, S, T> draft) {
        draft.toSpec(problems).ifPresent(declared::add);
    }

    @Override
    public GraphDefinition<E, S, T> build() {
        if (built) {
            throw new IllegalStateException("build() was already called on the graph " + key);
        }
        built = true;
        if (initialDeclarations == 0) {
            problems.add("SG201", "the graph " + key + " does not declare initial(...)");
        } else if (initialDeclarations > 1) {
            problems.add("SG201", "initial(...) was declared " + initialDeclarations + " times in " + key);
        }
        Map<TransitionKey<S, T>, TransitionSpec<E, S, T>> index = new LinkedHashMap<>();
        for (TransitionSpec<E, S, T> transition : declared) {
            if (index.putIfAbsent(transition.key(), transition) != null) {
                problems.add("SG102", transition.from().ordinal(), transition.transition().ordinal(),
                        "duplicate transition " + transition.key());
            }
        }
        List<TransitionSpec<E, S, T>> ordered = new ArrayList<>(index.values());
        ordered.sort(Comparator.comparingInt((TransitionSpec<E, S, T> spec) -> spec.from().ordinal())
                .thenComparingInt((TransitionSpec<E, S, T> spec) -> spec.transition().ordinal())
                .thenComparingInt((TransitionSpec<E, S, T> spec) -> spec.to().ordinal()));
        Map<S, StatePolicy<S, T>> policies = resolvePolicies();
        Map<S, WatchSpec<E, S, T, ?, ?, ?>> watchSpecs = resolveWatches();
        // A missing initial state, or a draft that could not be assembled, leaves the machine without an edge it
        // was meant to have, and every structural check downstream would report noise about a graph nobody wrote.
        if (problems.isEmpty()) {
            MachineOutlineValidator.validate(outline(ordered, policies, watchSpecs), problems);
        }
        if (!problems.isEmpty()) {
            throw new InvalidGraphException(key, problems.sorted());
        }
        return new DslGraphDefinition<>(key, states, initial.orElseThrow(), terminals, ordered, policies, watchSpecs);
    }

    /** A declared policy wins; a terminal without one gets the stable generated text. */
    private Map<S, StatePolicy<S, T>> resolvePolicies() {
        Map<S, StatePolicy<S, T>> resolved = new EnumMap<>(key.states());
        for (S state : states) {
            Optional<String> otherwise = Optional.ofNullable(statePolicies.get(state));
            if (otherwise.isEmpty() && terminals.contains(state)) {
                otherwise = Optional.of("terminal state " + state.name() + " accepts no transitions");
            }
            if (otherwise.isEmpty() || otherwise.get().isBlank()) {
                continue;
            }
            Map<T, String> declaredRejections = rejections.getOrDefault(state, Map.of());
            boolean blank = false;
            for (String reason : declaredRejections.values()) {
                blank = blank || reason.isBlank();
            }
            if (blank) {
                continue;
            }
            resolved.put(state, new StatePolicy<>(state, otherwise.get(), declaredRejections));
        }
        return resolved;
    }

    private Map<S, WatchSpec<E, S, T, ?, ?, ?>> resolveWatches() {
        Map<S, WatchSpec<E, S, T, ?, ?, ?>> resolved = new LinkedHashMap<>();
        for (Map.Entry<S, WatchDraft<E, S, T, ?, ?, ?>> entry : watches.entrySet()) {
            Optional<? extends WatchSpec<E, S, T, ?, ?, ?>> spec = entry.getValue().toSpec(problems);
            if (spec.isPresent()) {
                resolved.put(entry.getKey(), spec.get());
            }
        }
        return resolved;
    }

    private MachineOutline outline(List<TransitionSpec<E, S, T>> ordered, Map<S, StatePolicy<S, T>> policies,
                                   Map<S, WatchSpec<E, S, T, ?, ?, ?>> watchSpecs) {
        List<String> stateNames = new ArrayList<>();
        for (S state : states) {
            stateNames.add(state.name());
        }
        List<String> catalogNames = new ArrayList<>();
        for (T transition : catalog) {
            catalogNames.add(transition.name());
        }
        List<TransitionOutline> edges = new ArrayList<>();
        for (TransitionSpec<E, S, T> spec : ordered) {
            edges.add(new TransitionOutline(spec.from().name(), spec.transition().name(), spec.to().name(), spec.kind(),
                    spec.onFailure().map(Enum::name)));
        }
        List<PolicyOutline> policyOutlines = new ArrayList<>();
        for (S state : states) {
            String otherwise = statePolicies.get(state);
            if (otherwise == null && !terminals.contains(state)) {
                continue;
            }
            StatePolicy<S, T> resolved = policies.get(state);
            String text = otherwise != null ? otherwise
                    : resolved != null ? resolved.otherwise() : "terminal state " + state.name() + " accepts no transitions";
            Map<String, String> declaredRejections = new LinkedHashMap<>();
            for (Map.Entry<T, String> rejection : rejections.getOrDefault(state, Map.of()).entrySet()) {
                declaredRejections.put(rejection.getKey().name(), rejection.getValue());
            }
            policyOutlines.add(new PolicyOutline(state.name(), text, declaredRejections));
        }
        List<WatchOutline> watchOutlines = new ArrayList<>();
        for (WatchSpec<E, S, T, ?, ?, ?> watch : watchSpecs.values()) {
            List<String> executed = new ArrayList<>();
            for (WatchRule<E, S, T, ?, ?, ?> rule : watch.rules()) {
                executed.add(rule.transition().name());
            }
            watchOutlines.add(new WatchOutline(watch.state().name(), executed));
        }
        Set<String> terminalNames = new LinkedHashSet<>();
        for (S state : terminals) {
            terminalNames.add(state.name());
        }
        Set<String> cycleNames = new LinkedHashSet<>();
        for (S state : cycleAllowed) {
            cycleNames.add(state.name());
        }
        return new MachineOutline(key.toString(), stateNames, catalogNames, initial.orElseThrow().name(), terminalNames,
                cycleNames, edges, policyOutlines, watchOutlines);
    }
}
