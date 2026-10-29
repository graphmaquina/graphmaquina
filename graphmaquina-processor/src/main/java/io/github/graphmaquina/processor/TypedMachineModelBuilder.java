package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.annotation.internal.GeneratedVocabulary;
import io.github.graphmaquina.annotation.internal.VocabularyKind;
import io.github.graphmaquina.processor.model.AutomaticOrderModel;
import io.github.graphmaquina.processor.model.Binding;
import io.github.graphmaquina.processor.model.CheckModel;
import io.github.graphmaquina.processor.model.MachineModel;
import io.github.graphmaquina.processor.model.MachineTypes;
import io.github.graphmaquina.processor.model.OperationModel;
import io.github.graphmaquina.processor.model.Property;
import io.github.graphmaquina.processor.model.RejectionModel;
import io.github.graphmaquina.processor.model.StateAccess;
import io.github.graphmaquina.processor.model.StatePolicyModel;
import io.github.graphmaquina.processor.model.SubmachineModel;
import io.github.graphmaquina.processor.model.TransitionModel;
import io.github.graphmaquina.processor.model.WatchModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Turns a machine that declares its graph with the vocabulary generated for it into the {@link MachineModel}
 * the generators read, reporting every problem it finds with an SG code.
 *
 * <p>A facade is told what shape it must take, a top-level interface over a concrete entity and two distinct enums,
 * which no {@code Definition} can make of it, before it is told to apply its {@code Definition}. Identity comes first
 * among its declarations, twice over. The facade must carry the {@code Definition} generated for that very machine,
 * and every other declaration on it must belong to the same vocabulary, so a machine can never be described with the
 * annotations of another one that happens to have the same states. Inside the machine, an edge is identified by its
 * own {@code id}: one method owns one identifier and every annotation it carries must agree on it, while the interface
 * may add further branches of that identifier from other origins. Each state and each identifier is read as the enum
 * constant it is, checked against the machine's own enum, and only then named; each symbol is resolved to the
 * exported member behind it. What the language itself refuses — a constant of another enum, an annotation that omits
 * an element it requires — is left to javac, which has already reported it precisely.</p>
 *
 * <p>Everything the model must hold beyond that is a rule about a graph and not about a declaration, so it is
 * asked of {@link StructuralChecks}: the signature of an operation and the full outline of the graph. A key two
 * edges claim, a state given two policies, a rejection declared twice and a blank reason are reported here instead,
 * as {@code SG102} and {@code SG103}, the codes the outline check gives a duplicate and a blank reason.</p>
 *
 * <p>Only what the companion can implement is accepted. The operations and the checks are read from the abstract
 * methods the facade declares itself, so an abstract method it inherits from another interface than {@code Machine} is
 * refused as {@code SG504}, except a redeclaration of a method of {@code Machine} or a public method of {@code Object}
 * that the method the companion writes or inherits for it implements; one whose result that method cannot return is
 * refused with that reason. A method the facade declares that overrides one of those is no operation, and is refused as
 * {@code SG504} when it carries vocabulary or declares a result the companion's method cannot return. So is an instance
 * method the facade declares or inherits that has the signature of the companion's static {@code graph(...)}, which
 * only a model whose bindings all resolved tells, and only when no vocabulary of the machine is missing from it. An
 * abstract method the facade declares that carries a {@code Check} of its own vocabulary and no {@code Transition} is
 * no operation either, but a check, which {@link TypedChecks} reads against the operations once they are all known. An
 * entity of a parameterized type, which generated code can only name by its class, is refused as {@code SG601}.</p>
 */
final class TypedMachineModelBuilder {

    private final VocabularyRegistry vocabulary;
    private final StructuralChecks structure;
    private final PropertyResolver properties;
    private final TypedTransitions transitions;
    private final TypedPairs pairs;
    private final TypedWatchReader watches;
    private final TypedSubmachineReader submachines;
    private final Signatures signatures;
    private final Diagnostics diagnostics;
    private final Annotations annotations;
    private final TypedChecks checks;
    private final AutomaticChecks automaticChecks;

    TypedMachineModelBuilder(VocabularyRegistry vocabulary, StructuralChecks structure, PropertyResolver properties,
            TypedTransitions transitions, TypedPairs pairs, TypedWatchReader watches, TypedSubmachineReader submachines,
            Signatures signatures, AutomaticChecks automaticChecks, Diagnostics diagnostics) {
        this.vocabulary = vocabulary;
        this.structure = structure;
        this.properties = properties;
        this.transitions = transitions;
        this.pairs = pairs;
        this.watches = watches;
        this.submachines = submachines;
        this.signatures = signatures;
        this.automaticChecks = automaticChecks;
        this.diagnostics = diagnostics;
        this.annotations = new Annotations(signatures.elements());
        this.checks = new TypedChecks(vocabulary, signatures, diagnostics);
    }

    /**
     * Empty when the machine has an error this processor reports, or a value javac refused that the model cannot do
     * without; nothing is generated for it then. {@code complete} is false when part of the machine's vocabulary is
     * written where no builder reads it, or on the facade of another machine, whose builder refuses it, or when a
     * declaration holds a value javac could not resolve: every declaration is still checked, but no conclusion is
     * drawn from the graph as a whole, nor from the classes it binds, since that graph misses what the author
     * declared.
     */
    Optional<MachineModel> build(TypeElement type, boolean complete) {
        int before = diagnostics.errors();
        List<AnnotationMirror> definitions = own(type, VocabularyKind.DEFINITION, type);
        DiagnosticAnchor header = definitions.isEmpty()
                ? DiagnosticAnchor.of(type)
                : DiagnosticAnchor.of(type, definitions.get(0));
        Optional<MachineTypes> types = facadeTypes(type, header);
        if (types.isEmpty()) {
            return Optional.empty();
        }
        if (definitions.isEmpty()) {
            diagnostics.error(DiagnosticCode.SG502,
                    type.getSimpleName() + " declares no graph: apply the Definition generated for it"
                    + ", which is the vocabulary its own name, states and transitions belong to", type);
            return Optional.empty();
        }
        Optional<MachineModel> model = model(type, types.get(), definitions.get(0), before);
        if (model.isEmpty()) {
            return Optional.empty();
        }
        if (complete) {
            structure.checkOutline(model.get(), header);
            structure.checkGraphFactory(model.get());
        }
        return diagnostics.errors() > before ? Optional.empty() : model;
    }

    /**
     * The entity, state and transition types of the facade {@code type}, or empty after {@code SG502} or
     * {@code SG601} at {@code header} when it is no top-level interface extending {@code Machine<E, S, T>} over a
     * concrete entity type that is not parameterized and two distinct enum types.
     */
    private Optional<MachineTypes> facadeTypes(TypeElement type, DiagnosticAnchor header) {
        if (!structure.checkTopLevel(type, header)) {
            return Optional.empty();
        }
        Optional<MachineTypes> resolved = structure.machineTypes(type);
        if (resolved.isEmpty()) {
            // An invalid declaration is SG502; a declaration whose E, S and T do not resolve is SG601.
            if (type.getKind() != ElementKind.INTERFACE) {
                header.report(diagnostics, DiagnosticCode.SG502,
                        type.getSimpleName() + " must be an interface extending"
                        + " Machine<E, S, T>");
            } else {
                header.report(diagnostics, DiagnosticCode.SG601,
                        type.getSimpleName() + " does not resolve Machine<E, S, T>"
                        + " to a concrete entity type and two distinct enum types for its states and its"
                        + " transition identifiers");
            }
            return Optional.empty();
        }
        MachineTypes types = resolved.get();
        if (StructuralChecks.hasParameterizedEntity(types)) {
            header.report(diagnostics, DiagnosticCode.SG601,
                    type.getSimpleName() + " declares its entity as " + types.entity()
                    + ", a parameterized type: generated code names the entity by its class, so the entity type must"
                    + " not be parameterized");
            return Optional.empty();
        }
        return resolved;
    }

    /**
     * The model that {@code definition}, the {@code Definition} the facade {@code type} carries, and the other
     * declarations of the facade describe, each of them checked. Empty when javac refused a value of the
     * {@code Definition} the model needs, when its state or identity property did not resolve, or when an error was
     * reported since the build of the machine started, with {@code before} errors.
     */
    private Optional<MachineModel> model(TypeElement type, MachineTypes types, AnnotationMirror definition,
                                         int before) {
        Optional<AnnotationValue> declaredInitial = annotations.find(definition, "initial");
        Optional<AnnotationValue> declaredState = annotations.find(definition, "state");
        Optional<AnnotationValue> declaredId = annotations.find(definition, "id");
        if (declaredInitial.isEmpty() || declaredState.isEmpty() || declaredId.isEmpty()) {
            // javac refuses a Definition that omits an element it requires, and leaves that element out.
            return Optional.empty();
        }
        Optional<String> initial = TypedValues.constantName(declaredInitial.get(), types.statesElement());
        Optional<List<String>> terminals = TypedValues.constantNames(annotations.array(definition, "terminals"),
                types.statesElement());
        Optional<List<String>> cycleAllowed = TypedValues.constantNames(annotations.array(definition, "allowsCycle"),
                types.statesElement());
        if (initial.isEmpty() || terminals.isEmpty() || cycleAllowed.isEmpty()) {
            return Optional.empty();
        }
        Optional<StateAccess> state = TypedValues.symbol(declaredState.get()).flatMap((TypeMirror symbol) ->
                properties.state(symbol, types, DiagnosticAnchor.of(type, definition, declaredState.get())));
        Optional<Property> id = TypedValues.symbol(declaredId.get()).flatMap((TypeMirror symbol) ->
                properties.id(symbol, types, DiagnosticAnchor.of(type, definition, declaredId.get())));
        Optional<Binding> defaultAfterCommit = transitions.afterCommit(type, definition, types);

        List<StatePolicyModel> policies = policies(type, types);
        List<RejectionModel> rejections = rejections(type, types);
        List<TransitionModel> declared = declared(type, types, defaultAfterCommit);
        List<ExecutableElement> checkMethods = new ArrayList<>();
        List<OperationModel> operations = operations(type, types, defaultAfterCommit, checkMethods);
        List<CheckModel> declaredChecks = checks.read(type, types, checkMethods, operations);
        automaticChecks.checkTypeLevelInput(type, declared, operations);
        List<AutomaticOrderModel> orders = automaticOrders(type, types);
        structure.checkInheritedOperations(type);
        TypedPairs.Peers peers = pairs.peers(type);
        Set<String> used = new LinkedHashSet<>();
        List<SubmachineModel> bound = submachines.read(type, types, peers, used, operations);
        List<WatchModel> watched = watches.read(type, types, peers, used);
        pairs.checkUsed(type, peers, used);
        checkDuplicateKeys(type, declared, operations);
        return state.isEmpty() || id.isEmpty() || diagnostics.errors() > before
                ? Optional.empty()
                : Optional.of(new MachineModel(types, packageName(type), initial.get(), terminals.get(),
                        cycleAllowed.get(), state.get().reader(), state.get().writer(), id.get(), policies,
                        rejections, declared, operations, watched, bound, declaredChecks, orders));
    }

    /** The policy of each state: the reason every identifier it declares no edge for is refused with. */
    private List<StatePolicyModel> policies(TypeElement type, MachineTypes types) {
        List<StatePolicyModel> policies = new ArrayList<>();
        Set<String> declared = new LinkedHashSet<>();
        for (AnnotationMirror policy : own(type, VocabularyKind.STATE_POLICY, type)) {
            Optional<AnnotationValue> declaredState = annotations.find(policy, "state");
            Optional<AnnotationValue> declaredReason = annotations.find(policy, "otherwise");
            if (declaredState.isEmpty() || declaredReason.isEmpty()) {
                continue;
            }
            Optional<String> state = TypedValues.constantName(declaredState.get(), types.statesElement());
            if (state.isEmpty() || !(declaredReason.get().getValue() instanceof String reason)) {
                continue;
            }
            if (!declared.add(state.get())) {
                DiagnosticAnchor.of(type, policy, declaredState.get()).report(diagnostics, DiagnosticCode.SG102,
                        "the state " + state.get() + " declares two policies");
                continue;
            }
            if (reason.isBlank()) {
                DiagnosticAnchor.of(type, policy, declaredReason.get()).report(diagnostics, DiagnosticCode.SG103,
                        "the policy of " + state.get() + " has a blank reason");
                continue;
            }
            policies.add(new StatePolicyModel(state.get(), reason));
        }
        return policies;
    }

    /**
     * The orders the facade declares, each with the state it orders and the identifiers it names. An order javac
     * left a value out of is skipped: javac has already refused it. Whether an order is the one its state needs is a
     * question about the whole graph, which its outline answers.
     */
    private List<AutomaticOrderModel> automaticOrders(TypeElement type, MachineTypes types) {
        List<AutomaticOrderModel> orders = new ArrayList<>();
        for (AnnotationMirror order : own(type, VocabularyKind.AUTOMATIC_ORDER, type)) {
            Optional<AnnotationValue> declaredFrom = annotations.find(order, "from");
            Optional<AnnotationValue> declaredTransitions = annotations.find(order, "transitions");
            if (declaredFrom.isEmpty() || declaredTransitions.isEmpty()) {
                continue;
            }
            Optional<String> from = TypedValues.constantName(declaredFrom.get(), types.statesElement());
            Optional<List<String>> identifiers = TypedValues.constantNames(annotations.array(order, "transitions"),
                    types.transitionsElement());
            if (from.isPresent() && identifiers.isPresent()) {
                orders.add(new AutomaticOrderModel(from.get(), identifiers.get(), order));
            }
        }
        return orders;
    }

    /** The identifiers the facade refuses from a state, each with the reason it is refused for. */
    private List<RejectionModel> rejections(TypeElement type, MachineTypes types) {
        List<RejectionModel> rejections = new ArrayList<>();
        Set<String> declared = new LinkedHashSet<>();
        for (AnnotationMirror reject : own(type, VocabularyKind.REJECT, type)) {
            Optional<AnnotationValue> declaredFrom = annotations.find(reject, "from");
            Optional<AnnotationValue> declaredTransition = annotations.find(reject, "transition");
            Optional<AnnotationValue> declaredReason = annotations.find(reject, "reason");
            if (declaredFrom.isEmpty() || declaredTransition.isEmpty() || declaredReason.isEmpty()) {
                continue;
            }
            Optional<String> from = TypedValues.constantName(declaredFrom.get(), types.statesElement());
            Optional<String> transition = TypedValues.constantName(declaredTransition.get(),
                    types.transitionsElement());
            if (from.isEmpty() || transition.isEmpty() || !(declaredReason.get().getValue() instanceof String reason)) {
                continue;
            }
            if (!declared.add(from.get() + "/" + transition.get())) {
                DiagnosticAnchor.of(type, reject, declaredTransition.get()).report(diagnostics, DiagnosticCode.SG102,
                        "the state " + from.get() + " rejects " + transition.get() + " twice");
                continue;
            }
            if (reason.isBlank()) {
                DiagnosticAnchor.of(type, reject, declaredReason.get()).report(diagnostics, DiagnosticCode.SG103,
                        "the rejection of " + transition.get() + " in " + from.get() + " has a blank reason");
                continue;
            }
            rejections.add(new RejectionModel(from.get(), transition.get(), reason));
        }
        return rejections;
    }

    /**
     * The edges the interface declares itself rather than on an operation; one of them may be a further branch, from
     * another origin, of an identifier an operation executes.
     */
    private List<TransitionModel> declared(TypeElement type, MachineTypes types, Optional<Binding> defaultAfterCommit) {
        List<TransitionModel> declared = new ArrayList<>();
        for (AnnotationMirror transition : own(type, VocabularyKind.TRANSITION, type)) {
            transitions.read(type, transition, types, "", defaultAfterCommit).ifPresent(declared::add);
        }
        return declared;
    }

    /**
     * The operations of the facade, each with the identifier it executes and every edge its own declarations
     * open. All of a method's annotations must name one identifier, and no two methods may own the same one. A
     * redeclaration of a method of {@code Machine} or a public method of {@code Object} is no operation, since the
     * companion writes or inherits that method already: one that carries vocabulary, or declares a result the
     * companion's method cannot return, is refused as {@code SG504}. A method that carries a {@code Check} of the
     * facade's own vocabulary and no {@code Transition} is no operation either: it is added to
     * {@code checkMethods}, for {@link TypedChecks} to read once every operation is known. One that carries both is
     * {@code SG514}, and is still read as the operation its {@code Transition} declares.
     */
    private List<OperationModel> operations(TypeElement type, MachineTypes types,
                                            Optional<Binding> defaultAfterCommit,
                                            List<ExecutableElement> checkMethods) {
        List<OperationModel> operations = new ArrayList<>();
        Map<String, ExecutableElement> owners = new LinkedHashMap<>();
        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) member;
            if (!StructuralChecks.isOperation(method)) {
                continue;
            }
            List<AnnotationMirror> mirrors = own(method, VocabularyKind.TRANSITION, type);
            List<AnnotationMirror> checked = own(method, VocabularyKind.CHECK, type);
            Optional<ExecutableElement> builtIn = structure.overriddenBuiltIn(method, type);
            if (builtIn.isPresent()) {
                structure.checkNotAnOperation(method, builtIn.get(), type, !mirrors.isEmpty() || !checked.isEmpty());
                continue;
            }
            if (!checked.isEmpty() && mirrors.isEmpty()) {
                checkMethods.add(method);
                continue;
            }
            if (!checked.isEmpty()) {
                checks.checkNotOnOperation(method, checked.get(0));
            }
            operation(method, mirrors, types, defaultAfterCommit, owners).ifPresent(operations::add);
        }
        return operations;
    }

    /**
     * The operation {@code method} declares with {@code mirrors}, the {@code Transition} annotations of its own
     * vocabulary it carries, or empty when it has a problem, which was reported. {@code owners} holds the method that
     * owns each identifier claimed so far.
     */
    private Optional<OperationModel> operation(ExecutableElement method, List<AnnotationMirror> mirrors,
            MachineTypes types, Optional<Binding> defaultAfterCommit, Map<String, ExecutableElement> owners) {
        if (!structure.checkOperationDeclared(method, !mirrors.isEmpty())
                || !structure.checkOperationSignature(method, types)) {
            return Optional.empty();
        }
        Optional<String> identifier = identity(method, mirrors, types);
        if (identifier.isEmpty() || !owns(method, identifier.get(), owners)) {
            return Optional.empty();
        }
        String name = method.getSimpleName().toString();
        Optional<VariableElement> input = method.getParameters().size() == 2
                ? Optional.of(method.getParameters().get(1)) : Optional.empty();
        Optional<TypeMirror> inputType = input.map(VariableElement::asType);
        String entityParameter = method.getParameters().get(0).getSimpleName().toString();
        Optional<String> inputParameter =
                input.map((VariableElement parameter) -> parameter.getSimpleName().toString());
        List<TransitionModel> edges = new ArrayList<>();
        for (AnnotationMirror mirror : mirrors) {
            transitions.read(method, mirror, types, name, defaultAfterCommit).ifPresent(edges::add);
        }
        if (edges.size() != mirrors.size()) {
            return Optional.empty();
        }
        automaticChecks.checkOperationInput(method, input, mirrors, edges);
        return Optional.of(new OperationModel(name, identifier.get(), edges, inputType, entityParameter,
                inputParameter));
    }

    /**
     * The one identifier every annotation of {@code method} names, or empty after {@code SG703} when they
     * disagree. The identity of an operation is what it executes, never the name of its action or of the
     * method itself.
     */
    private Optional<String> identity(ExecutableElement method, List<AnnotationMirror> mirrors, MachineTypes types) {
        Set<String> identifiers = new LinkedHashSet<>();
        for (AnnotationMirror mirror : mirrors) {
            transitions.identifier(mirror, types).ifPresent(identifiers::add);
        }
        if (identifiers.isEmpty()) {
            // javac has already refused an id it could not read as a constant of this machine's own enum.
            return Optional.empty();
        }
        if (identifiers.size() > 1) {
            diagnostics.error(DiagnosticCode.SG703, method.getSimpleName() + " declares the transitions " + identifiers
                    + ": every annotation of one operation names the same identifier", method);
            return Optional.empty();
        }
        return Optional.of(identifiers.iterator().next());
    }

    /** SG703 unless {@code identifier} is still unclaimed: two methods cannot execute the same transition. */
    private boolean owns(ExecutableElement method, String identifier, Map<String, ExecutableElement> owners) {
        ExecutableElement previous = owners.putIfAbsent(identifier, method);
        if (previous == null) {
            return true;
        }
        diagnostics.error(DiagnosticCode.SG703, "the transition " + identifier + " is already executed by "
                + previous.getSimpleName() + ": one identifier belongs to one operation", method);
        return false;
    }

    /** SG102 for every key {@code (from, id)} two declarations of the same machine both claim. */
    private void checkDuplicateKeys(TypeElement type, List<TransitionModel> declared, List<OperationModel> operations) {
        List<TransitionModel> all = new ArrayList<>(declared);
        for (OperationModel operation : operations) {
            all.addAll(operation.transitions());
        }
        Set<String> seen = new LinkedHashSet<>();
        for (TransitionModel transition : all) {
            for (String from : transition.from()) {
                if (!seen.add(from + "/" + transition.transitionId())) {
                    diagnostics.error(DiagnosticCode.SG102, "the transition " + transition.transitionId() + " leaves "
                            + from + " twice", type);
                }
            }
        }
    }

    /** Every vocabulary of {@code kind} on {@code element} that was generated for {@code facade}. */
    private List<AnnotationMirror> own(Element element, VocabularyKind kind, TypeElement facade) {
        List<AnnotationMirror> own = new ArrayList<>();
        for (AnnotationMirror mirror : vocabulary.findAll(element, kind)) {
            if (owns(element, mirror, facade)) {
                own.add(mirror);
            }
        }
        return own;
    }

    /**
     * Whether {@code facade} is the machine {@code mirror} was generated for, and {@code SG606} at it when
     * another one was. The vocabulary of two machines over the same states is interchangeable to the compiler
     * and is not interchangeable here: each declaration belongs to the graph whose name it carries.
     */
    private boolean owns(Element element, AnnotationMirror mirror, TypeElement facade) {
        TypeElement declaration = (TypeElement) mirror.getAnnotationType().asElement();
        AnnotationMirror metadata = annotations.find(declaration, GeneratedVocabulary.class).orElseThrow();
        TypeMirror machine = TypedValues.classLiteral(annotations.value(metadata, "machine"));
        if (machine instanceof DeclaredType declared && declared.asElement().equals(facade)) {
            return true;
        }
        DiagnosticAnchor.of(element, mirror).report(diagnostics, DiagnosticCode.SG606, declaration.getQualifiedName()
                + " is the vocabulary of " + machine + ", not of " + facade.getQualifiedName());
        return false;
    }

    private String packageName(TypeElement type) {
        return signatures.elements().getPackageOf(type).getQualifiedName().toString();
    }
}
