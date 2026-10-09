package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.annotation.GraphPeers;
import io.github.graphmaquina.annotation.internal.GeneratedVocabulary;
import io.github.graphmaquina.annotation.internal.VocabularyKind;
import io.github.graphmaquina.processor.model.Binding;
import io.github.graphmaquina.processor.model.MachineTypes;
import io.github.graphmaquina.processor.model.Property;
import io.github.graphmaquina.processor.model.RuleKind;
import io.github.graphmaquina.processor.model.RuleModel;
import io.github.graphmaquina.processor.model.WatchModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Reads the watches a machine declares with the vocabulary generated for each of its pairs.
 *
 * <p>Watch vocabulary belongs to an ordered pair, not to a single machine, so identity is checked on both
 * halves of it: the annotation must have been generated for this very facade, and the peer it names must be one
 * the facade declares with {@code @GraphPeers}. The pair is therefore never repeated in a declaration and can
 * never be inferred from the states, which two machines may well share. The vocabulary of a pair also holds the
 * sub-machine annotations; the identity of each {@code Submachine} on the facade is checked here the same way,
 * and nothing else of it is read.</p>
 *
 * <p>The declaration of the pair and its uses must agree in both directions. A peer nothing watches is a
 * declaration that generated vocabulary for nothing, and a peer declared twice generates it twice; both are
 * refused as {@code SG608} rather than silently ignored. What the language itself refuses — a constant of the
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

    private static final String FOREIGN = "SG606";
    private static final String PAIR = "SG608";
    private static final String NOT_A_MACHINE = "SG509";
    private static final String TWO_WATCHES = "SG403";
    private static final String NO_RULES = "SG404";
    private static final String ALTERNATIVE = "SG508";
    private static final String OPTIONAL = "java.util.Optional";

    /** The four elements of a rule, exactly one of which a {@code When} declares. */
    private static final List<String> ALTERNATIVES = List.of("condition", "allIn", "anyIn", "noneIn");

    private final VocabularyRegistry vocabulary;
    private final StructuralChecks structure;
    private final PropertyResolver properties;
    private final SymbolResolver symbols;
    private final Signatures signatures;
    private final Diagnostics diagnostics;
    private final Annotations annotations;

    TypedWatchReader(VocabularyRegistry vocabulary, StructuralChecks structure, PropertyResolver properties,
            SymbolResolver symbols, Signatures signatures, Diagnostics diagnostics) {
        this.vocabulary = vocabulary;
        this.structure = structure;
        this.properties = properties;
        this.symbols = symbols;
        this.signatures = signatures;
        this.diagnostics = diagnostics;
        this.annotations = new Annotations(signatures.elements());
    }

    /** The peers {@code @GraphPeers} declares: where it was written, the ones it names and the usable ones. */
    private record Peers(Optional<AnnotationMirror> mirror, Map<String, TypeElement> declared, Set<String> usable) { }

    /**
     * The watches {@code facade} declares, in the order it spells them; a watch with a problem is left out, and one
     * whose problem this processor reports makes the machine's own builder refuse the machine as a whole. The
     * identity of each sub-machine annotation on {@code facade} is checked too.
     */
    List<WatchModel> read(TypeElement facade, MachineTypes machine) {
        Peers peers = peers(facade);
        Set<String> watched = new LinkedHashSet<>();
        Set<String> states = new LinkedHashSet<>();
        List<WatchModel> watches = new ArrayList<>();
        for (AnnotationMirror submachine : vocabulary.findAll(facade, VocabularyKind.SUBMACHINE)) {
            peer(facade, submachine, peers, "declares a sub-machine of");
        }
        for (AnnotationMirror watch : vocabulary.findAll(facade, VocabularyKind.WATCH)) {
            Optional<String> peer = peer(facade, watch, peers, "watches");
            if (peer.isEmpty()) {
                continue;
            }
            watched.add(peer.get());
            if (peers.usable().contains(peer.get())) {
                read(facade, watch, machine, peers.declared().get(peer.get()), states).ifPresent(watches::add);
            }
        }
        for (String declared : peers.declared().keySet()) {
            if (!watched.contains(declared)) {
                DiagnosticAnchor.of(facade, peers.mirror().orElseThrow()).report(diagnostics, PAIR, declared
                        + " is declared as a peer of " + facade.getSimpleName() + " but no watch of it uses that pair;"
                        + " declare the peers this machine watches, and only those");
            }
        }
        return List.copyOf(watches);
    }

    /**
     * The peers of {@code facade}, with {@code SG608} for one it names twice and for one not marked
     * {@code @GenerateGraphApi}: watch vocabulary is typed with the peer's own states, which only such a machine's
     * generated API declares.
     */
    private Peers peers(TypeElement facade) {
        Optional<AnnotationMirror> mirror = annotations.find(facade, GraphPeers.class);
        Map<String, TypeElement> declared = new LinkedHashMap<>();
        Set<String> usable = new LinkedHashSet<>();
        if (mirror.isEmpty()) {
            return new Peers(mirror, Map.of(), Set.of());
        }
        for (AnnotationValue value : annotations.array(mirror.get(), "value")) {
            Optional<TypeElement> peer = element(value);
            if (peer.isEmpty()) {
                // javac has already refused a value it could not read as the machine the element declares.
                continue;
            }
            String name = peer.get().getQualifiedName().toString();
            DiagnosticAnchor anchor = DiagnosticAnchor.of(facade, mirror.get(), value);
            if (declared.putIfAbsent(name, peer.get()) != null) {
                anchor.report(diagnostics, PAIR, name + " is declared twice as a peer of " + facade.getSimpleName());
            } else if (annotations.find(peer.get(), GenerateGraphApi.class).isEmpty()) {
                anchor.report(diagnostics, PAIR, name + " is not a @GenerateGraphApi machine: a watch is typed with"
                        + " the peer's own states, which only a generated API declares");
            } else {
                usable.add(name);
            }
        }
        return new Peers(mirror, Collections.unmodifiableMap(declared), Collections.unmodifiableSet(usable));
    }

    /**
     * The peer the pair of {@code declared} names, or empty after reporting {@code SG606} when the vocabulary was
     * generated for another machine, and {@code SG608} when the facade declares no such peer, saying that the
     * facade {@code uses} it, as in {@code watches}.
     */
    private Optional<String> peer(TypeElement facade, AnnotationMirror declared, Peers peers, String uses) {
        TypeElement declaration = (TypeElement) declared.getAnnotationType().asElement();
        AnnotationMirror metadata = annotations.find(declaration, GeneratedVocabulary.class).orElseThrow();
        TypeMirror owner = TypedValues.classLiteral(annotations.value(metadata, "machine"));
        if (!(owner instanceof DeclaredType declaredOwner) || !declaredOwner.asElement().equals(facade)) {
            DiagnosticAnchor.of(facade, declared).report(diagnostics, FOREIGN, declaration.getQualifiedName()
                    + " is the vocabulary of a pair of " + owner + ", not of " + facade.getQualifiedName());
            return Optional.empty();
        }
        List<AnnotationValue> pair = annotations.array(metadata, "peer");
        Optional<TypeElement> peer = pair.size() == 1 ? element(pair.get(0)) : Optional.empty();
        if (peer.isEmpty()) {
            DiagnosticAnchor.of(facade, declared).report(diagnostics, FOREIGN, declaration.getQualifiedName()
                    + " names no single peer machine, so it is not the vocabulary of a pair");
            return Optional.empty();
        }
        String name = peer.get().getQualifiedName().toString();
        if (!peers.declared().containsKey(name)) {
            DiagnosticAnchor.of(facade, declared).report(diagnostics, PAIR, facade.getSimpleName() + " " + uses + " "
                    + name + ", which it does not declare with @GraphPeers");
            return Optional.empty();
        }
        return Optional.of(name);
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
        Optional<MachineTypes> resolved = structure.machineTypes(peerElement);
        if (resolved.isEmpty()) {
            DiagnosticAnchor.of(facade, watch).report(diagnostics, NOT_A_MACHINE, peerElement.getQualifiedName()
                    + " must be an interface extending Machine<E, S, T> with a concrete entity type and two"
                    + " distinct enum types for its states and its transition identifiers");
            return Optional.empty();
        }
        MachineTypes peer = resolved.get();
        if (StructuralChecks.hasParameterizedEntity(peer)) {
            DiagnosticAnchor.of(facade, watch).report(diagnostics, NOT_A_MACHINE, peerElement.getQualifiedName()
                    + " declares its entity as " + peer.entity() + ", a parameterized type, which a watch cannot follow");
            return Optional.empty();
        }
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
    private Optional<RuleModel> rule(TypeElement facade, AnnotationMirror rule, MachineTypes machine, MachineTypes peer) {
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
    private Optional<RuleModel> condition(AnnotationValue value, String executed, MachineTypes machine, MachineTypes peer,
            DiagnosticAnchor anchor) {
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

    /** The type {@code value} names, or empty when javac could not read it as a declared type. */
    private static Optional<TypeElement> element(AnnotationValue value) {
        return value.getValue() instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element
                ? Optional.of(element)
                : Optional.empty();
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
