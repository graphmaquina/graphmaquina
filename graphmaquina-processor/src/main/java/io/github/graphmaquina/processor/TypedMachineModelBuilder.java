package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.internal.GeneratedVocabulary;
import io.github.graphmaquina.annotation.internal.VocabularyKind;
import io.github.graphmaquina.processor.model.Binding;
import io.github.graphmaquina.processor.model.CellModel;
import io.github.graphmaquina.processor.model.ForbidModel;
import io.github.graphmaquina.processor.model.MachineModel;
import io.github.graphmaquina.processor.model.MachineTypes;
import io.github.graphmaquina.processor.model.OperationModel;
import io.github.graphmaquina.processor.model.Property;
import io.github.graphmaquina.processor.model.RowModel;
import io.github.graphmaquina.processor.model.StateAccess;
import io.github.graphmaquina.processor.model.WatchModel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Turns a machine that declares its graph with the vocabulary generated for it into the {@link MachineModel}
 * the generators read, reporting every problem it finds with an SG code.
 *
 * <p>Identity comes first. The facade must carry the {@code Definition} generated for that very machine, and
 * every other declaration on it must belong to the same vocabulary, so a machine can never be described with
 * the annotations of another one that happens to have the same states. Each state is then read as the enum
 * constant it is, checked against the machine's own enum, and only then named; each symbol is resolved to the
 * exported member behind it. What the language itself refuses — a constant of another enum, an annotation that
 * omits an element it requires — is left to javac, which has already reported it precisely.</p>
 *
 * <p>Everything the model must hold beyond that is a rule about a graph and not about a declaration, so it is
 * asked of {@link StructuralChecks}: the signature of an operation, the closure of the rows, the duplicated
 * cells and the full outline of the graph.</p>
 */
final class TypedMachineModelBuilder {

    private static final String FOREIGN = "SG606";
    private static final String HEADER = "SG502";
    private static final String DUPLICATE_ROW = "SG102";
    private static final String BLANK_REASON = "SG103";

    private final VocabularyRegistry vocabulary;
    private final StructuralChecks structure;
    private final PropertyResolver properties;
    private final TypedTransitions transitions;
    private final TypedWatchReader watches;
    private final Signatures signatures;
    private final Diagnostics diagnostics;
    private final Annotations annotations;

    TypedMachineModelBuilder(VocabularyRegistry vocabulary, StructuralChecks structure, PropertyResolver properties,
            TypedTransitions transitions, TypedWatchReader watches, Signatures signatures, Diagnostics diagnostics) {
        this.vocabulary = vocabulary;
        this.structure = structure;
        this.properties = properties;
        this.transitions = transitions;
        this.watches = watches;
        this.signatures = signatures;
        this.diagnostics = diagnostics;
        this.annotations = new Annotations(signatures.elements());
    }

    /** Empty when the machine has errors; nothing is generated for it then. */
    Optional<MachineModel> build(TypeElement type) {
        int before = diagnostics.errors();
        List<AnnotationMirror> definitions = own(type, VocabularyKind.DEFINITION, type);
        if (definitions.isEmpty()) {
            diagnostics.error(HEADER, type.getSimpleName() + " declares no graph: apply the Definition generated for it"
                    + ", which is the vocabulary its own name and states belong to", type);
            return Optional.empty();
        }
        AnnotationMirror definition = definitions.get(0);
        DiagnosticAnchor header = DiagnosticAnchor.of(type, definition);
        if (!structure.checkTopLevel(type, header)) {
            return Optional.empty();
        }
        Optional<MachineTypes> resolved = structure.machineTypes(type);
        if (resolved.isEmpty()) {
            header.report(diagnostics, HEADER, type.getSimpleName() + " must be an interface extending Machine<E, S>"
                    + " with a concrete entity type and an enum state type");
            return Optional.empty();
        }
        MachineTypes types = resolved.get();

        Optional<AnnotationValue> declaredInitial = annotations.find(definition, "initial");
        Optional<AnnotationValue> declaredState = annotations.find(definition, "state");
        Optional<AnnotationValue> declaredId = annotations.find(definition, "id");
        if (declaredInitial.isEmpty() || declaredState.isEmpty() || declaredId.isEmpty()) {
            // javac refuses a Definition that omits an element it requires, and leaves that element out.
            return Optional.empty();
        }
        Optional<String> initial = TypedValues.stateName(declaredInitial.get(), types.statesElement());
        Optional<List<String>> terminals = TypedValues.stateNames(annotations.array(definition, "terminals"),
                types.statesElement());
        Optional<List<String>> cycleAllowed = TypedValues.stateNames(annotations.array(definition, "allowsCycle"),
                types.statesElement());
        if (initial.isEmpty() || terminals.isEmpty() || cycleAllowed.isEmpty()) {
            return Optional.empty();
        }
        Optional<StateAccess> state = symbol(declaredState.get()).flatMap((TypeMirror symbol) ->
                properties.state(symbol, types, DiagnosticAnchor.of(type, definition, declaredState.get())));
        Optional<Property> id = symbol(declaredId.get()).flatMap((TypeMirror symbol) ->
                properties.id(symbol, types, DiagnosticAnchor.of(type, definition, declaredId.get())));
        Optional<Binding> defaultAfterCommit = transitions.afterCommit(type, definition, types);

        Set<String> closed = new LinkedHashSet<>();
        List<RowModel> rows = rows(type, types, closed);
        List<ForbidModel> forbids = forbids(type, types);
        List<CellModel> cells = cells(type, types, defaultAfterCommit);
        List<OperationModel> operations = operations(type, types, defaultAfterCommit);
        List<WatchModel> watched = watches.read(type, types);
        structure.checkDuplicateCells(type, forbids, cells, operations);
        structure.checkRowsClosed(types, closed, terminals.get(), header);
        if (state.isEmpty() || id.isEmpty() || diagnostics.errors() > before) {
            return Optional.empty();
        }
        MachineModel model = new MachineModel(types, packageName(type), initial.get(), terminals.get(), cycleAllowed.get(),
                state.get().reader(), state.get().writer(), id.get(), rows, forbids, cells, operations, watched);
        structure.checkOutline(model, header);
        return diagnostics.errors() > before ? Optional.empty() : Optional.of(model);
    }

    /** The rows the facade closes; a state that carries one is closed even when its reason is refused. */
    private List<RowModel> rows(TypeElement type, MachineTypes types, Set<String> closed) {
        List<RowModel> rows = new ArrayList<>();
        for (AnnotationMirror row : own(type, VocabularyKind.ROW, type)) {
            Optional<AnnotationValue> declaredState = annotations.find(row, "state");
            Optional<AnnotationValue> declaredReason = annotations.find(row, "otherwise");
            if (declaredState.isEmpty() || declaredReason.isEmpty()) {
                continue;
            }
            Optional<String> state = TypedValues.stateName(declaredState.get(), types.statesElement());
            if (state.isEmpty() || !(declaredReason.get().getValue() instanceof String reason)) {
                continue;
            }
            if (!closed.add(state.get())) {
                DiagnosticAnchor.of(type, row, declaredState.get()).report(diagnostics, DUPLICATE_ROW,
                        "the row " + state.get() + " is closed twice");
                continue;
            }
            if (reason.isBlank()) {
                DiagnosticAnchor.of(type, row, declaredReason.get()).report(diagnostics, BLANK_REASON,
                        "the row " + state.get() + " has a blank reason");
                continue;
            }
            rows.add(new RowModel(state.get(), reason));
        }
        return rows;
    }

    /** The transitions the facade refuses, each with the reason it is refused for. */
    private List<ForbidModel> forbids(TypeElement type, MachineTypes types) {
        List<ForbidModel> forbids = new ArrayList<>();
        for (AnnotationMirror forbid : own(type, VocabularyKind.FORBID, type)) {
            Optional<AnnotationValue> declaredFrom = annotations.find(forbid, "from");
            Optional<AnnotationValue> declaredTo = annotations.find(forbid, "to");
            Optional<AnnotationValue> declaredReason = annotations.find(forbid, "reason");
            if (declaredFrom.isEmpty() || declaredTo.isEmpty() || declaredReason.isEmpty()) {
                continue;
            }
            Optional<String> from = TypedValues.stateName(declaredFrom.get(), types.statesElement());
            Optional<String> to = TypedValues.stateName(declaredTo.get(), types.statesElement());
            if (from.isEmpty() || to.isEmpty() || !(declaredReason.get().getValue() instanceof String reason)) {
                continue;
            }
            if (reason.isBlank()) {
                DiagnosticAnchor.of(type, forbid, declaredReason.get()).report(diagnostics, BLANK_REASON,
                        "the @Forbid " + from.get() + " → " + to.get() + " has a blank reason");
                continue;
            }
            forbids.add(new ForbidModel(from.get(), to.get(), reason));
        }
        return forbids;
    }

    /** The transitions the interface declares itself, which no operation of the facade triggers. */
    private List<CellModel> cells(TypeElement type, MachineTypes types,
            Optional<Binding> defaultAfterCommit) {
        List<CellModel> cells = new ArrayList<>();
        for (AnnotationMirror cell : own(type, VocabularyKind.CELL, type)) {
            transitions.read(type, cell, types, "", defaultAfterCommit).ifPresent(cells::add);
        }
        return cells;
    }

    /** The operations of the facade, each with the cell its own declaration triggers. */
    private List<OperationModel> operations(TypeElement type, MachineTypes types,
            Optional<Binding> defaultAfterCommit) {
        List<OperationModel> operations = new ArrayList<>();
        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) member;
            if (!StructuralChecks.isOperation(method)) {
                continue;
            }
            List<AnnotationMirror> actions = own(method, VocabularyKind.ACTION, type);
            List<AnnotationMirror> moves = own(method, VocabularyKind.MOVE, type);
            if (!structure.checkOperationKinds(method, !actions.isEmpty(), !moves.isEmpty())
                    || !structure.checkOperationSignature(method, types)) {
                continue;
            }
            String name = method.getSimpleName().toString();
            AnnotationMirror mirror = actions.isEmpty() ? moves.get(0) : actions.get(0);
            transitions.read(method, mirror, types, name, defaultAfterCommit)
                    .ifPresent((CellModel cell) -> operations.add(new OperationModel(name, cell)));
        }
        return operations;
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
        DiagnosticAnchor.of(element, mirror).report(diagnostics, FOREIGN, declaration.getQualifiedName()
                + " is the vocabulary of " + machine + ", not of " + facade.getQualifiedName());
        return false;
    }

    /** The symbol {@code value} names, or empty when javac could not read it as a class literal. */
    private static Optional<TypeMirror> symbol(AnnotationValue value) {
        return value.getValue() instanceof TypeMirror type ? Optional.of(type) : Optional.empty();
    }

    private String packageName(TypeElement type) {
        return signatures.elements().getPackageOf(type).getQualifiedName().toString();
    }
}
