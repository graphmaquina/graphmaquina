package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.internal.VocabularyKind;
import io.github.graphmaquina.processor.model.Binding;
import io.github.graphmaquina.processor.model.MachineTypes;
import io.github.graphmaquina.processor.model.Property;
import io.github.graphmaquina.processor.model.RuleKind;
import io.github.graphmaquina.processor.model.RuleModel;
import io.github.graphmaquina.processor.model.WatchModel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Reads the watches a machine declares with the vocabulary generated for each of its pairs.
 *
 * <p>The identity of each {@code Watch} is checked by {@link TypedPairs}: it was generated for this very facade,
 * over a peer the facade declares with {@code @GraphPeers}. What the language itself refuses — a constant of the
 * wrong enum in {@code state}, {@code transition} or a filter — is left to javac, which has already reported it at
 * the exact value, and is never wrapped in a diagnostic of this tool.</p>
 *
 * <p>Everything else is resolved through the same resolvers the rest of the typed frontend uses: the peers of
 * the entity and the owner of a peer are properties, and a condition is a symbol resolved against the machine
 * and its peer together, so the method the generated code calls is the one the author marked.</p>
 *
 * <p>The companion of the watching machine names the peer's entity and enums, so a peer is refused as
 * {@code SG509} when its entity is a parameterized type, or when one of those types is not accessible from the
 * package the companion is generated in.</p>
 */
final class TypedWatchReader {

    private static final String NOT_A_MACHINE = "SG509";
    private static final String TWO_WATCHES = "SG403";
    private static final String NO_RULES = "SG404";
    private static final String ALTERNATIVE = "SG508";
    private static final String OPTIONAL = "java.util.Optional";

    /** The four elements of a rule, exactly one of which a {@code When} declares. */
    private static final List<String> ALTERNATIVES = List.of("condition", "allIn", "anyIn", "noneIn");

    private final VocabularyRegistry vocabulary;
    private final TypedPairs pairs;
    private final PropertyResolver properties;
    private final SymbolResolver symbols;
    private final Signatures signatures;
    private final Diagnostics diagnostics;
    private final Annotations annotations;

    TypedWatchReader(VocabularyRegistry vocabulary, TypedPairs pairs, PropertyResolver properties,
            SymbolResolver symbols, Signatures signatures, Diagnostics diagnostics) {
        this.vocabulary = vocabulary;
        this.pairs = pairs;
        this.properties = properties;
        this.symbols = symbols;
        this.signatures = signatures;
        this.diagnostics = diagnostics;
        this.annotations = new Annotations(signatures.elements());
    }

    /**
     * The watches {@code facade} declares, in the order it spells them; a watch with a problem is left out, and one
     * whose problem this processor reports makes the machine's own builder refuse the machine as a whole. The peer of
     * every watch whose identity holds is added to {@code used}.
     */
    List<WatchModel> read(TypeElement facade, MachineTypes machine, TypedPairs.Peers peers, Set<String> used) {
        Set<String> states = new LinkedHashSet<>();
        List<WatchModel> watches = new ArrayList<>();
        for (AnnotationMirror watch : vocabulary.findAll(facade, VocabularyKind.WATCH)) {
            Optional<String> peer = pairs.peer(facade, watch, peers, "watches");
            if (peer.isEmpty()) {
                continue;
            }
            used.add(peer.get());
            peers.usablePeer(peer.get()).flatMap((TypeElement peerElement) -> read(facade, watch, machine,
                    peerElement, states)).ifPresent(watches::add);
        }
        return List.copyOf(watches);
    }

    /** One watch of {@code facade} over {@code peerElement}, or empty when it has a problem, which was reported. */
    private Optional<WatchModel> read(TypeElement facade, AnnotationMirror watch, MachineTypes machine,
            TypeElement peerElement, Set<String> states) {
        int before = diagnostics.errors();
        Optional<AnnotationValue> declaredState = annotations.find(watch, "state");
        Optional<AnnotationValue> declaredPeers = annotations.find(watch, "peers");
        Optional<AnnotationValue> declaredOwner = annotations.find(watch, "owner");
        Optional<AnnotationValue> declaredRules = annotations.find(watch, "rules");
        if (declaredState.isEmpty() || declaredPeers.isEmpty() || declaredOwner.isEmpty() || declaredRules.isEmpty()) {
            // javac refuses a Watch that omits an element it requires, and leaves that element out.
            return Optional.empty();
        }
        Optional<MachineTypes> resolved = pairs.peerTypes(facade, watch, peerElement, "a watch cannot follow");
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        MachineTypes peer = resolved.get();
        if (!reachable(facade, watch, peer)) {
            return Optional.empty();
        }
        Optional<String> state = TypedValues.constantName(declaredState.get(), machine.statesElement());
        if (state.isEmpty()) {
            return Optional.empty();
        }
        if (!states.add(state.get())) {
            DiagnosticAnchor.of(facade, watch, declaredState.get()).report(diagnostics, TWO_WATCHES,
                    "the state " + state.get() + " declares two watches");
        }
        Optional<Property> peersOf = symbol(declaredPeers.get()).flatMap((TypeMirror property) -> properties.peers(
                property, machine, peer, DiagnosticAnchor.of(facade, watch, declaredPeers.get())));
        Optional<Property> ownerOf = symbol(declaredOwner.get()).flatMap((TypeMirror property) -> properties.owner(
                property, machine, peer, DiagnosticAnchor.of(facade, watch, declaredOwner.get())));
        List<AnnotationMirror> declared = annotations.annotations(watch, "rules");
        if (declared.isEmpty()) {
            DiagnosticAnchor.of(facade, watch, declaredRules.get()).report(diagnostics, NO_RULES,
                    "the watch of " + state.get() + " declares no rule");
        }
        List<RuleModel> rules = new ArrayList<>();
        for (AnnotationMirror rule : declared) {
            rule(facade, rule, machine, peer).ifPresent(rules::add);
        }
        if (peersOf.isEmpty() || ownerOf.isEmpty() || rules.size() != declared.size()
                || diagnostics.errors() > before) {
            return Optional.empty();
        }
        return Optional.of(new WatchModel(state.get(), peer, peersOf.get(), ownerOf.get(),
                isOptional(ownerOf.get(), machine), rules));
    }

    /**
     * Whether the companion of {@code facade}, generated in the facade's package, can name the types it watches
     * {@code peer} with: the peer's entity and its two enums, which it writes in the key and the lambdas of the
     * watch. {@code SG509} otherwise, naming each type that package cannot access.
     */
    private boolean reachable(TypeElement facade, AnnotationMirror watch, MachineTypes peer) {
        String packageName = Accessibility.packageOf(facade);
        Set<String> inaccessible = new LinkedHashSet<>();
        for (TypeMirror type : List.of(peer.entity(), peer.states(), peer.transitions())) {
            inaccessible.addAll(Accessibility.inaccessible(type, packageName));
        }
        if (inaccessible.isEmpty()) {
            return true;
        }
        DiagnosticAnchor.of(facade, watch).report(diagnostics, NOT_A_MACHINE, peer.facade().getQualifiedName()
                + " is watched with " + String.join(" and ", inaccessible) + ", which "
                + Accessibility.describe(packageName) + ", where the companion of " + facade.getSimpleName()
                + " is generated, cannot access: make " + (inaccessible.size() == 1 ? "it" : "them") + " public");
        return false;
    }

    /**
     * One rule of a watch: exactly one of the four alternatives, and the transition of the machine it executes.
     * {@code SG508} when none or several are declared, since neither an unconditional rule nor a rule with two
     * meanings is a rule the model can hold.
     */
    private Optional<RuleModel> rule(TypeElement facade, AnnotationMirror rule, MachineTypes machine,
            MachineTypes peer) {
        int before = diagnostics.errors();
        Optional<AnnotationValue> declaredTransition = annotations.find(rule, "transition");
        if (declaredTransition.isEmpty()) {
            // javac refuses a When that omits the transition it requires, and leaves that element out.
            return Optional.empty();
        }
        List<String> given = new ArrayList<>();
        for (String alternative : ALTERNATIVES) {
            if (!values(rule, alternative).isEmpty()) {
                given.add(alternative);
            }
        }
        if (given.size() != 1) {
            DiagnosticAnchor.of(facade, rule).report(diagnostics, ALTERNATIVE, "a rule declares exactly one of"
                    + " condition, allIn, anyIn or noneIn; this one declares " + (given.isEmpty() ? "none" : given));
            return Optional.empty();
        }
        Optional<String> executed = TypedValues.constantName(declaredTransition.get(), machine.transitionsElement());
        String alternative = given.get(0);
        Optional<AnnotationValue> value = TypedValues.optional(rule, alternative, annotations,
                DiagnosticAnchor.of(facade, rule), diagnostics);
        if (executed.isEmpty() || value.isEmpty()) {
            return Optional.empty();
        }
        DiagnosticAnchor anchor = DiagnosticAnchor.of(facade, rule, value.get());
        Optional<RuleModel> read = alternative.equals("condition")
                ? condition(value.get(), executed.get(), machine, peer, anchor)
                : filter(value.get(), executed.get(), alternative, peer);
        return diagnostics.errors() > before ? Optional.empty() : read;
    }

    /** A rule that asks a condition, resolved against the machine and the peer the pair names. */
    private Optional<RuleModel> condition(AnnotationValue value, String executed, MachineTypes machine,
            MachineTypes peer, DiagnosticAnchor anchor) {
        return symbol(value).flatMap((TypeMirror symbol) ->
                        symbols.resolve(symbol, Bindings.Role.CONDITION, machine, Optional.of(peer), anchor))
                .map((Binding condition) -> new RuleModel(RuleKind.CONDITION, "", Optional.of(condition), executed));
    }

    /** A rule that filters the peers' states; the constant is read against the peer's own enum. */
    private Optional<RuleModel> filter(AnnotationValue value, String executed, String alternative, MachineTypes peer) {
        return TypedValues.constantName(value, peer.statesElement())
                .map((String argument) -> new RuleModel(kind(alternative), argument, Optional.empty(), executed));
    }

    /** Whether the owner accessor answers with an {@code Optional}, which decides how the peer is followed back. */
    private boolean isOptional(Property owner, MachineTypes machine) {
        return signatures.same(owner.accessor().effectiveType().getReturnType(),
                signatures.declared(OPTIONAL, machine.entity()));
    }

    /** The values of an array element, and none when this annotation does not declare that element at all. */
    private List<AnnotationValue> values(AnnotationMirror mirror, String name) {
        return annotations.find(mirror, name).isEmpty() ? List.of() : annotations.array(mirror, name);
    }

    /** The symbol {@code value} names, or empty when javac could not read it as a class literal. */
    private static Optional<TypeMirror> symbol(AnnotationValue value) {
        return value.getValue() instanceof TypeMirror type ? Optional.of(type) : Optional.empty();
    }

    private static RuleKind kind(String alternative) {
        return switch (alternative) {
            case "allIn" -> RuleKind.ALL_IN;
            case "anyIn" -> RuleKind.ANY_IN;
            case "noneIn" -> RuleKind.NONE_IN;
            default -> throw new IllegalArgumentException(alternative + " is not a filter of a watch rule");
        };
    }
}
