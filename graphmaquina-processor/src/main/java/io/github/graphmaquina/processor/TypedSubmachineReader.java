package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.annotation.internal.VocabularyKind;
import io.github.graphmaquina.dsl.MachineOutlineValidator;
import io.github.graphmaquina.processor.model.Binding;
import io.github.graphmaquina.processor.model.MachineTypes;
import io.github.graphmaquina.processor.model.OperationModel;
import io.github.graphmaquina.processor.model.Property;
import io.github.graphmaquina.processor.model.SubmachineModel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Reads the sub-machines a machine declares with the vocabulary generated for each of its pairs: for each
 * {@code Submachine}, the state it is bound to, the peer the pair names as the child, the way from the parent to the
 * child and back, and the exits.
 *
 * <p>The identity of each {@code Submachine} is checked by {@link TypedPairs}. The child is reached through
 * {@code child}, a property of the parent's entity that returns an {@code Optional} of the child's, and leads back to
 * its parent through {@code parent}, a property of the child's entity that returns an {@code Optional} of the
 * parent's, or through {@code parentLookup}, an exported lookup that takes the child's entity and returns that
 * {@code Optional}. Each of the three holds one value at most ({@code SG607}); a property of another type is
 * {@code SG503}, and every symbol is resolved like any other ({@code SG605}, {@code SG505}). A child over another
 * entity than the parent's needs {@code child} and exactly one of {@code parent} or {@code parentLookup}, and a child
 * over the parent's own entity takes none of the three: both are {@code SG806}.</p>
 *
 * <p>Whether the exits match the terminal states of the child, have an edge from the bound state and are claimed by
 * nothing else there is a rule about the graph as a whole, which {@link MachineOutlineValidator} reports from the
 * outline ({@code SG801} to {@code SG804}). Two rules are this reader's own, because no outline holds what they read:
 * an operation of the facade that executes an exit, which it would request from the bound state too, is {@code SG804}
 * at that exit; and a machine that its sub-machines contain again, followed through the sub-machines each child
 * declares in turn, is {@code SG805} at the binding that starts the shortest such path.</p>
 *
 * <p>The companion of the parent names the child's entity, states and transitions, so a child is refused as
 * {@code SG509} when one of those types is not accessible from the package the companion is generated in.</p>
 */
final class TypedSubmachineReader {

    private final VocabularyRegistry vocabulary;
    private final TypedPairs pairs;
    private final PropertyResolver properties;
    private final SymbolResolver symbols;
    private final Signatures signatures;
    private final Diagnostics diagnostics;
    private final Annotations annotations;

    TypedSubmachineReader(VocabularyRegistry vocabulary, TypedPairs pairs, PropertyResolver properties,
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
     * The sub-machines {@code facade} declares, in the order it spells them; one with a problem is left out, and one
     * whose problem this processor reports makes the machine's own builder refuse the machine as a whole. The peer of
     * every binding whose identity holds is added to {@code used}. {@code operations} are the operations of the
     * facade, none of which may execute an exit.
     */
    List<SubmachineModel> read(TypeElement facade, MachineTypes machine, TypedPairs.Peers peers, Set<String> used,
            List<OperationModel> operations) {
        List<SubmachineModel> submachines = new ArrayList<>();
        for (AnnotationMirror submachine : vocabulary.findAll(facade, VocabularyKind.SUBMACHINE)) {
            Optional<String> peer = pairs.peer(facade, submachine, peers, "declares a sub-machine of");
            if (peer.isEmpty()) {
                continue;
            }
            used.add(peer.get());
            peers.usablePeer(peer.get()).flatMap((TypeElement child) -> read(facade, submachine, machine, child,
                    operations)).ifPresent(submachines::add);
        }
        checkCycle(facade);
        return List.copyOf(submachines);
    }

    /**
     * One sub-machine of {@code facade} over {@code childElement}, or empty when it has a problem, which was reported.
     */
    private Optional<SubmachineModel> read(TypeElement facade, AnnotationMirror submachine, MachineTypes machine,
            TypeElement childElement, List<OperationModel> operations) {
        int before = diagnostics.errors();
        Optional<AnnotationValue> declaredState = annotations.find(submachine, "state");
        if (declaredState.isEmpty() || annotations.find(submachine, "exits").isEmpty()) {
            // javac refuses a Submachine that omits an element it requires, and leaves that element out.
            return Optional.empty();
        }
        Optional<MachineTypes> resolved = pairs.peerTypes(facade, submachine, childElement,
                "a parent cannot bind as its sub-machine");
        Optional<String> state = TypedValues.constantName(declaredState.get(), machine.statesElement());
        if (resolved.isEmpty() || state.isEmpty()) {
            return Optional.empty();
        }
        MachineTypes child = resolved.get();
        if (!reachable(facade, submachine, child)) {
            return Optional.empty();
        }
        Optional<AnnotationValue> childValue = single(facade, submachine, "child");
        Optional<AnnotationValue> parentValue = single(facade, submachine, "parent");
        Optional<AnnotationValue> lookupValue = single(facade, submachine, "parentLookup");
        if (diagnostics.errors() > before) {
            return Optional.empty();
        }
        if (!hasWays(facade, submachine, machine, child, state.get(), childValue, parentValue, lookupValue)) {
            return Optional.empty();
        }
        Optional<Property> childOf = childValue.flatMap((AnnotationValue value) -> TypedValues.symbol(value).flatMap(
                (TypeMirror property) -> properties.child(property, machine, child,
                        DiagnosticAnchor.of(facade, submachine, value))));
        Optional<Property> parentOf = parentValue.flatMap((AnnotationValue value) -> TypedValues.symbol(value).flatMap(
                (TypeMirror property) -> properties.parent(property, machine, child,
                        DiagnosticAnchor.of(facade, submachine, value))));
        Optional<Binding> parentLookup = lookupValue.flatMap((AnnotationValue value) ->
                TypedValues.symbol(value).flatMap((TypeMirror lookup) -> symbols.resolve(lookup,
                        Bindings.Role.LOOKUP, machine, Optional.of(child), DiagnosticAnchor.of(facade, submachine,
                                value))));
        List<AnnotationMirror> declaredExits = annotations.annotations(submachine, "exits");
        List<SubmachineModel.Exit> exits = exits(facade, declaredExits, machine, child, state.get(), operations);
        Optional<List<String>> terminals = terminals(child);
        if (childOf.isPresent() != childValue.isPresent() || parentOf.isPresent() != parentValue.isPresent()
                || parentLookup.isPresent() != lookupValue.isPresent() || exits.size() != declaredExits.size()
                || terminals.isEmpty() || diagnostics.errors() > before) {
            // javac has already refused a value it could not read, and the child's own builder a child whose
            // graph declares no terminal states it can read.
            return Optional.empty();
        }
        return Optional.of(new SubmachineModel(state.get(), child, terminals.get(), childOf, parentOf, parentLookup,
                exits));
    }

    /**
     * Whether the companion of {@code facade}, generated in the facade's package, can name the types it binds
     * {@code child} with: the child's entity, which the lambdas of the binding take, its states, which the exits name,
     * and its transitions, which the type of its definition names. {@code SG509} otherwise, naming each type that
     * package cannot access.
     */
    private boolean reachable(TypeElement facade, AnnotationMirror submachine, MachineTypes child) {
        String packageName = Accessibility.packageOf(facade);
        Set<String> inaccessible = new LinkedHashSet<>();
        for (TypeMirror type : List.of(child.entity(), child.states(), child.transitions())) {
            inaccessible.addAll(Accessibility.inaccessible(type, packageName));
        }
        if (inaccessible.isEmpty()) {
            return true;
        }
        DiagnosticAnchor.of(facade, submachine).report(diagnostics, DiagnosticCode.SG509,
                child.facade().getQualifiedName()
                + " is bound as a sub-machine with " + String.join(" and ", inaccessible) + ", which "
                + Accessibility.describe(packageName) + ", where the companion of " + facade.getSimpleName()
                + " is generated, cannot access: make " + (inaccessible.size() == 1 ? "it" : "them") + " public");
        return false;
    }

    /**
     * The one value of the element {@code name}: none is empty, one is that value, and more is empty after
     * {@code SG607} at that element.
     */
    private Optional<AnnotationValue> single(TypeElement facade, AnnotationMirror submachine, String name) {
        Optional<AnnotationValue> declared = annotations.find(submachine, name);
        if (declared.isEmpty()) {
            return Optional.empty();
        }
        return TypedValues.optional(submachine, name, annotations, DiagnosticAnchor.of(facade, submachine,
                declared.get()), diagnostics);
    }

    /**
     * Whether the binding declares the ways its entities need, and {@code SG806} otherwise: a child over another
     * entity is reached through {@code child} and leads back through exactly one of {@code parent} or
     * {@code parentLookup}; a child over the parent's own entity is the entity itself, both ways.
     */
    private boolean hasWays(TypeElement facade, AnnotationMirror submachine, MachineTypes machine, MachineTypes child,
            String state, Optional<AnnotationValue> childValue, Optional<AnnotationValue> parentValue,
            Optional<AnnotationValue> lookupValue) {
        String governs = facade.getSimpleName() + "." + state + ": " + child.facade().getSimpleName() + " governs ";
        if (signatures.same(machine.entity(), child.entity())) {
            if (childValue.isEmpty() && parentValue.isEmpty() && lookupValue.isEmpty()) {
                return true;
            }
            DiagnosticAnchor.of(facade, submachine).report(diagnostics, DiagnosticCode.SG806,
                    governs + machine.entityName()
                    + " itself, so child, parent and parentLookup must be empty");
            return false;
        }
        if (childValue.isPresent() && parentValue.isPresent() != lookupValue.isPresent()) {
            return true;
        }
        DiagnosticAnchor.of(facade, submachine).report(diagnostics, DiagnosticCode.SG806, governs + child.entityName()
                + ", so the sub-machine needs child and one of parent or parentLookup");
        return false;
    }

    /**
     * The exits declared in {@code declared}, in declaration order, each read against the enum of its own side; one
     * javac could not read is left out. {@code SG804} at the identifier of an exit that an operation executes.
     */
    private List<SubmachineModel.Exit> exits(TypeElement facade, List<AnnotationMirror> declared, MachineTypes machine,
            MachineTypes child, String state, List<OperationModel> operations) {
        List<SubmachineModel.Exit> exits = new ArrayList<>();
        for (AnnotationMirror exit : declared) {
            Optional<AnnotationValue> declaredOn = annotations.find(exit, "on");
            Optional<AnnotationValue> declaredTransition = annotations.find(exit, "transition");
            if (declaredOn.isEmpty() || declaredTransition.isEmpty()) {
                // javac refuses an Exit that omits an element it requires, and leaves that element out.
                continue;
            }
            Optional<String> on = TypedValues.constantName(declaredOn.get(), child.statesElement());
            Optional<String> transition = TypedValues.constantName(declaredTransition.get(),
                    machine.transitionsElement());
            if (on.isEmpty() || transition.isEmpty()) {
                continue;
            }
            for (OperationModel operation : operations) {
                if (operation.transitionId().equals(transition.get())) {
                    DiagnosticAnchor.of(facade, exit, declaredTransition.get()).report(diagnostics,
                            DiagnosticCode.SG804, facade.getSimpleName() + "." + state + ": " + transition.get()
                                    + " is an exit of its sub-machine and cannot be the operation "
                                    + operation.name());
                }
            }
            exits.add(new SubmachineModel.Exit(on.get(), transition.get()));
        }
        return exits;
    }

    /**
     * The terminal states {@code child}'s own {@code Definition} declares, in the order of its state enum, or empty
     * when it declares no {@code Definition} of its own or one whose terminal states javac could not read.
     */
    private Optional<List<String>> terminals(MachineTypes child) {
        for (AnnotationMirror definition : vocabulary.findAll(child.facade(), VocabularyKind.DEFINITION)) {
            if (!pairs.generatedFor(definition, child.facade())) {
                continue;
            }
            Optional<List<String>> declared = TypedValues.constantNames(annotations.array(definition, "terminals"),
                    child.statesElement());
            return declared.map((List<String> names) -> child.stateNames().stream().filter(names::contains).toList());
        }
        return Optional.empty();
    }

    /** One binding a machine declares on itself, as {@link #checkCycle} follows it. */
    private record Hop(String state, int ordinal, TypeElement child, AnnotationMirror submachine) { }

    /** A machine {@link #checkCycle} reached, the path that reached it and the binding that path starts with. */
    private record Visit(TypeElement machine, String path, AnnotationMirror first) { }

    /**
     * {@code SG805} when the sub-machines of {@code facade}, followed through the sub-machines each child declares in
     * turn, reach {@code facade} again. The walk is breadth first, so the path it reports is a shortest one: it
     * starts from the bindings of {@code facade} in the order of their states, and follows each child's bindings in
     * the order of its own states. It visits each machine once, so it ends on any cycle.
     */
    private void checkCycle(TypeElement facade) {
        String name = facade.getSimpleName().toString();
        Deque<Visit> queue = new ArrayDeque<>();
        for (Hop hop : hops(facade)) {
            queue.add(new Visit(hop.child(), name + "." + hop.state(), hop.submachine()));
        }
        Set<TypeElement> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            Visit visit = queue.removeFirst();
            if (visit.machine().equals(facade)) {
                DiagnosticAnchor.of(facade, visit.first()).report(diagnostics, DiagnosticCode.SG805,
                        "the sub-machines of " + name
                        + " contain " + name + " again: " + visit.path() + " -> " + name);
                return;
            }
            if (seen.add(visit.machine())) {
                for (Hop hop : hops(visit.machine())) {
                    queue.add(new Visit(hop.child(), visit.path() + " -> " + visit.machine().getSimpleName() + "."
                            + hop.state(), visit.first()));
                }
            }
        }
    }

    /**
     * The bindings {@code machine} declares with the vocabulary of its own pairs, each over the one peer its pair
     * names, in the order of their states. Reports nothing: each machine's own builder reports its problems.
     */
    private List<Hop> hops(TypeElement machine) {
        List<Hop> hops = new ArrayList<>();
        for (AnnotationMirror submachine : vocabulary.findAll(machine, VocabularyKind.SUBMACHINE)) {
            Optional<AnnotationValue> state = annotations.find(submachine, "state");
            Optional<TypeElement> child = pairs.peerOf(submachine);
            if (!pairs.generatedFor(submachine, machine) || child.isEmpty() || state.isEmpty()
                    || !(state.get().getValue() instanceof VariableElement constant)) {
                continue;
            }
            hops.add(new Hop(constant.getSimpleName().toString(), ordinal(constant), child.get(), submachine));
        }
        hops.sort(Comparator.comparingInt((Hop hop) -> hop.ordinal()));
        return hops;
    }

    /** The position of {@code constant} among the constants of its enum. */
    private static int ordinal(VariableElement constant) {
        int ordinal = 0;
        for (Element member : constant.getEnclosingElement().getEnclosedElements()) {
            if (member.equals(constant)) {
                return ordinal;
            }
            if (member.getKind() == ElementKind.ENUM_CONSTANT) {
                ordinal++;
            }
        }
        return ordinal;
    }
}
