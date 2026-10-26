package io.github.graphmaquina.dsl;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.RecoveryRule;
import io.github.graphmaquina.api.StatePolicy;
import io.github.graphmaquina.api.SubmachineSpec;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;

/**
 * Default, single-use implementation of {@link GraphBuilder}: accumulates drafts and assembles them on build().
 * It keeps every edge draft it opens, so that build() reports one that was never closed, and every sub-machine draft,
 * so that build() reports a state bound twice.
 */
final class DefaultGraphBuilder<E, S extends Enum<S>, T extends Enum<T>> implements GraphBuilder<E, S, T> {

    @Getter(AccessLevel.PACKAGE)
    private final MachineKey<E, S, T> key;
    private final S[] states;
    private final T[] catalog;
    private Optional<S> initial = Optional.empty();
    private int initialDeclarations;
    private final Set<S> terminals;
    private final Set<S> cycleAllowed;
    private final Map<S, String> statePolicies;
    private final Map<S, Map<T, String>> rejections;
    private final List<TransitionDraft<E, S, T>> drafts = new ArrayList<>();
    private final List<TransitionSpec<E, S, T>> declared = new ArrayList<>();
    private final Map<S, WatchDraft<E, S, T, ?, ?, ?>> watches;
    private final List<SubmachineDraft<E, S, T, ?, ?, ?>> submachines = new ArrayList<>();
    @Getter(AccessLevel.PACKAGE)
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

    /** Refuses any declaration once build() has run: nothing reads it any more, so it could only be lost. */
    private void requireUnbuilt() {
        if (built) {
            throw new IllegalStateException("build() was already called on the graph " + key);
        }
    }

    /**
     * The same refusal for a stage this graph opened; {@code stage} names the edge, the watch or the sub-machine it
     * would change.
     */
    void requireUnbuilt(String stage) {
        if (built) {
            throw new IllegalStateException(
                    "build() was already called on the graph " + key + ", so " + stage + " can no longer change");
        }
    }

    private void requireState(S state) {
        if (!key.states().isInstance(state)) {
            throw new IllegalArgumentException("state must belong to " + key.states().getName() + ": " + state.name());
        }
    }

    void requireTransition(T transition) {
        if (!key.transitions().isInstance(transition)) {
            throw new IllegalArgumentException("transition must belong to " + key.transitions().getName() + ": "
                    + transition.name());
        }
    }

    @Override
    public GraphBuilder<E, S, T> initial(@NonNull S state) {
        requireUnbuilt();
        requireState(state);
        initialDeclarations++;
        initial = Optional.of(state);
        return this;
    }

    @Override
    public GraphBuilder<E, S, T> terminal(@NonNull S state) {
        requireUnbuilt();
        requireState(state);
        terminals.add(state);
        return this;
    }

    @Override
    public GraphBuilder<E, S, T> allowsCycle(@NonNull S state) {
        requireUnbuilt();
        requireState(state);
        cycleAllowed.add(state);
        return this;
    }

    @Override
    public GraphBuilder<E, S, T> statePolicy(@NonNull S state, @NonNull String otherwise) {
        requireUnbuilt();
        requireState(state);
        if (statePolicies.putIfAbsent(state, otherwise) != null) {
            problems.add(DiagnosticCode.SG102,
                    state.ordinal(), -1, "the state " + state.name() + " declares two policies");
        }
        return this;
    }

    @Override
    public GraphBuilder<E, S, T> reject(@NonNull S state, @NonNull T transition, @NonNull String reason) {
        requireUnbuilt();
        requireState(state);
        requireTransition(transition);
        Map<T, String> forState = rejections.computeIfAbsent(state, (S ignored) -> new LinkedHashMap<>());
        if (forState.putIfAbsent(transition, reason) != null) {
            problems.add(DiagnosticCode.SG102, state.ordinal(), transition.ordinal(),
                    "the state " + state.name() + " rejects " + transition.name() + " twice");
        }
        return this;
    }

    @Override
    public TransitionBuilder<E, S, T> transition(@NonNull T transition, @NonNull S from, @NonNull S to) {
        requireUnbuilt();
        requireTransition(transition);
        requireState(from);
        requireState(to);
        TransitionDraft<E, S, T> draft = new TransitionDraft<>(this, from, transition, to);
        drafts.add(draft);
        return draft;
    }

    @Override
    public <P, Q extends Enum<Q>, U extends Enum<U>> WatchBuilder<E, S, T, P, Q, U> watch(
            @NonNull S state, @NonNull MachineKey<P, Q, U> peer,
            @NonNull Function<? super E, ? extends Collection<? extends P>> peersOf,
            @NonNull Function<? super P, Optional<E>> ownerOf) {
        requireUnbuilt();
        requireState(state);
        WatchDraft<E, S, T, P, Q, U> draft = new WatchDraft<>(this, state, peer, peersOf, ownerOf);
        if (watches.putIfAbsent(state, draft) != null) {
            problems.add(DiagnosticCode.SG403,
                    state.ordinal(), -1, "the state " + state.name() + " declares two watches");
        }
        return draft;
    }

    @Override
    public <P, Q extends Enum<Q>, U extends Enum<U>> SubmachineBuilder<E, S, T, P, Q, U> submachine(
            @NonNull S state, @NonNull GraphDefinition<P, Q, U> child,
            @NonNull Function<? super E, Optional<P>> childOf, @NonNull Function<? super P, Optional<E>> parentOf) {
        requireUnbuilt();
        requireState(state);
        SubmachineDraft<E, S, T, P, Q, U> draft = new SubmachineDraft<>(this, state, child, childOf, parentOf);
        submachines.add(draft);
        return draft;
    }

    @Override
    public <Q extends Enum<Q>, U extends Enum<U>> SubmachineBuilder<E, S, T, E, Q, U> submachine(
            @NonNull S state, @NonNull GraphDefinition<E, Q, U> child) {
        return submachine(state, child, Optional::of, Optional::of);
    }

    /** Called by {@code endTransition()}: a closed draft adds its one edge, or records its problems and adds none. */
    void close(TransitionDraft<E, S, T> draft) {
        draft.toSpec(problems).ifPresent(declared::add);
    }

    @Override
    public GraphDefinition<E, S, T> build() {
        requireUnbuilt();
        built = true;
        if (initialDeclarations == 0) {
            problems.add(DiagnosticCode.SG201, "the graph " + key + " does not declare initial(...)");
        } else if (initialDeclarations > 1) {
            problems.add(DiagnosticCode.SG201, "initial(...) was declared " + initialDeclarations + " times in " + key);
        }
        for (TransitionDraft<E, S, T> draft : drafts) {
            draft.checkClosed(problems);
        }
        Map<TransitionKey<S, T>, TransitionSpec<E, S, T>> index = new LinkedHashMap<>();
        for (TransitionSpec<E, S, T> transition : declared) {
            if (index.containsKey(transition.key())) {
                problems.add(DiagnosticCode.SG102, transition.from().ordinal(), transition.transition().ordinal(),
                        "duplicate transition " + transition.key());
            } else {
                index.put(transition.key(), transition);
            }
        }
        List<TransitionSpec<E, S, T>> ordered = new ArrayList<>(index.values());
        ordered.sort(Comparator.comparingInt((TransitionSpec<E, S, T> spec) -> spec.from().ordinal())
                .thenComparingInt((TransitionSpec<E, S, T> spec) -> spec.transition().ordinal())
                .thenComparingInt((TransitionSpec<E, S, T> spec) -> spec.to().ordinal()));
        Map<S, StatePolicy<S, T>> policies = resolvePolicies();
        Map<S, WatchSpec<E, S, T, ?, ?, ?>> watchSpecs = resolveWatches();
        // A missing initial state, or a draft that was never closed or could not be assembled, leaves the machine
        // without an edge it was meant to have, and every structural check downstream would report noise about a
        // graph nobody wrote.
        if (problems.isEmpty()) {
            MachineOutlineValidator.validate(outline(ordered, policies, watchSpecs), problems);
        }
        // SG805 reads only the children, which were built before this graph: it is checked whatever else was found.
        CompositionCycles.check(key, submachines, problems);
        if (!problems.isEmpty()) {
            throw new InvalidGraphException(key, problems.sorted());
        }
        return new DslGraphDefinition<>(key, states, initial.orElseThrow(), terminals, ordered, policies, watchSpecs,
                resolveSubmachines());
    }

    /** The first binding declared on each state; SG803 has already reported a second one. */
    private Map<S, SubmachineSpec<E, S, T, ?, ?, ?>> resolveSubmachines() {
        Map<S, SubmachineSpec<E, S, T, ?, ?, ?>> resolved = new EnumMap<>(key.states());
        for (SubmachineDraft<E, S, T, ?, ?, ?> draft : submachines) {
            if (!resolved.containsKey(draft.state())) {
                resolved.put(draft.state(), draft.toSpec());
            }
        }
        return resolved;
    }

    /**
     * A declared policy wins; a terminal without one gets the stable generated text. A policy holding a blank
     * reason is left out, because {@link StatePolicy} refuses it and the validator reports it as SG103.
     */
    private Map<S, StatePolicy<S, T>> resolvePolicies() {
        Map<S, StatePolicy<S, T>> resolved = new EnumMap<>(key.states());
        for (S state : states) {
            Optional<String> otherwise = Optional.ofNullable(statePolicies.get(state));
            if (otherwise.isEmpty() && terminals.contains(state)) {
                otherwise = Optional.of("terminal state " + state.name() + " accepts no transitions");
            }
            Map<T, String> reasons = rejections.getOrDefault(state, Map.of());
            if (otherwise.isEmpty() || otherwise.get().isBlank()
                    || reasons.values().stream().anyMatch(String::isBlank)) {
                continue;
            }
            resolved.put(state, new StatePolicy<>(state, otherwise.get(), reasons));
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
        List<TransitionOutline> edges = edgeOutlines(ordered);
        List<PolicyOutline> policyOutlines = policyOutlines(policies);
        List<WatchOutline> watchOutlines = watchOutlines(watchSpecs);
        Set<String> terminalNames = new LinkedHashSet<>();
        for (S state : terminals) {
            terminalNames.add(state.name());
        }
        Set<String> cycleNames = new LinkedHashSet<>();
        for (S state : cycleAllowed) {
            cycleNames.add(state.name());
        }
        List<SubmachineOutline> submachineOutlines = new ArrayList<>();
        for (SubmachineDraft<E, S, T, ?, ?, ?> draft : submachines) {
            submachineOutlines.add(draft.toOutline());
        }
        return new MachineOutline(key.toString(), stateNames, catalogNames, initial.orElseThrow().name(), terminalNames,
                cycleNames, edges, policyOutlines, watchOutlines, submachineOutlines);
    }

    /** The outline of each edge of {@code ordered}, in that order, with the identifiers its recovery rules name. */
    private List<TransitionOutline> edgeOutlines(List<TransitionSpec<E, S, T>> ordered) {
        List<TransitionOutline> edges = new ArrayList<>();
        for (TransitionSpec<E, S, T> spec : ordered) {
            List<String> recoveries = new ArrayList<>();
            for (RecoveryRule<T> rule : spec.recoveryRules()) {
                recoveries.add(rule.transition().name());
            }
            edges.add(new TransitionOutline(spec.from().name(), spec.transition().name(), spec.to().name(), spec.kind(),
                    spec.onFailure().map(Enum::name), recoveries));
        }
        return edges;
    }

    /**
     * The outline of the policy of each state that declares one or is terminal, in state order: the declared text,
     * else the resolved one of {@code policies}, else the generated text of a terminal, with its declared rejections.
     */
    private List<PolicyOutline> policyOutlines(Map<S, StatePolicy<S, T>> policies) {
        List<PolicyOutline> policyOutlines = new ArrayList<>();
        for (S state : states) {
            Optional<String> otherwise = Optional.ofNullable(statePolicies.get(state));
            if (otherwise.isEmpty() && !terminals.contains(state)) {
                continue;
            }
            String text = otherwise
                    .or(() -> Optional.ofNullable(policies.get(state)).map(StatePolicy::otherwise))
                    .orElse("terminal state " + state.name() + " accepts no transitions");
            Map<String, String> declaredRejections = new LinkedHashMap<>();
            for (Map.Entry<T, String> rejection : rejections.getOrDefault(state, Map.of()).entrySet()) {
                declaredRejections.put(rejection.getKey().name(), rejection.getValue());
            }
            policyOutlines.add(new PolicyOutline(state.name(), text, declaredRejections));
        }
        return policyOutlines;
    }

    /** The outline of each watch of {@code watchSpecs}, in its order, with the identifiers its rules select. */
    private List<WatchOutline> watchOutlines(Map<S, WatchSpec<E, S, T, ?, ?, ?>> watchSpecs) {
        List<WatchOutline> watchOutlines = new ArrayList<>();
        for (WatchSpec<E, S, T, ?, ?, ?> watch : watchSpecs.values()) {
            List<String> executed = new ArrayList<>();
            for (WatchRule<E, S, T, ?, ?, ?> rule : watch.rules()) {
                executed.add(rule.transition().name());
            }
            watchOutlines.add(new WatchOutline(watch.state().name(), executed));
        }
        return watchOutlines;
    }
}
