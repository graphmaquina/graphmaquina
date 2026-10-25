package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.internal.GeneratedVocabulary;
import io.github.graphmaquina.annotation.internal.VocabularyKind;
import io.github.graphmaquina.processor.model.CheckModel;
import io.github.graphmaquina.processor.model.MachineTypes;
import io.github.graphmaquina.processor.model.OperationModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Reads the checks a facade declares: its abstract methods that carry the {@code Check} generated for that very
 * machine and no {@code Transition}, each of which asks whether one operation of the facade may pass its admission
 * conditions now, without running it.
 *
 * <p>A check is no operation. It owns no identifier, so it never takes part in {@code SG703}, and opens no edge, so
 * it never takes part in {@code SG804} either. Its {@code id} names the identifier one of the facade's operations
 * executes, never a route the facade only declares on itself, the exit of a sub-machine or an identifier without an
 * edge ({@code SG512}). It repeats the parameters of that operation, the same entity and the same input, present
 * exactly when the operation takes one, under the result {@code Eligibility<E, S, T>} of the machine's own types and
 * without varargs or type parameters of its own ({@code SG513}). An operation has one check at most, and a method that
 * carries a {@code Transition} and a {@code Check} is refused, since a check is declared on a method of its own
 * ({@code SG514}).</p>
 *
 * <p>Where a {@code Check} may be written at all is asked elsewhere: on a default, static or private method it is
 * never read, which {@link VocabularyPlacement} reports, and the {@code Check} of another machine is refused where it
 * is read, by the builder of the facade that carries it.</p>
 */
final class TypedChecks {

    private static final String NO_OPERATION = "SG512";
    private static final String SIGNATURE = "SG513";
    private static final String DUPLICATE = "SG514";
    private static final String ELIGIBILITY = "io.github.graphmaquina.api.Eligibility";

    private final VocabularyRegistry vocabulary;
    private final Signatures signatures;
    private final Diagnostics diagnostics;
    private final Annotations annotations;

    TypedChecks(VocabularyRegistry vocabulary, Signatures signatures, Diagnostics diagnostics) {
        this.vocabulary = vocabulary;
        this.signatures = signatures;
        this.diagnostics = diagnostics;
        this.annotations = new Annotations(signatures.elements());
    }

    /**
     * The checks of {@code facade}, in the order {@code declared} holds them. {@code declared} are the abstract
     * methods the facade declares that carry a {@code Check} of its own vocabulary and no {@code Transition}, and
     * {@code operations} the operations read from the same facade. A check with a problem is reported and left out;
     * one whose {@code id} javac could not read is left out, since javac has refused it already.
     */
    List<CheckModel> read(TypeElement facade, MachineTypes types, List<ExecutableElement> declared,
            List<OperationModel> operations) {
        List<CheckModel> checks = new ArrayList<>();
        Map<String, ExecutableElement> checked = new LinkedHashMap<>();
        for (ExecutableElement method : declared) {
            Optional<AnnotationMirror> check = own(method, facade);
            Optional<AnnotationValue> id = check.flatMap((AnnotationMirror mirror) -> annotations.find(mirror, "id"));
            Optional<String> identifier = id.flatMap((AnnotationValue value) ->
                    TypedValues.constantName(value, types.transitionsElement()));
            if (identifier.isEmpty()) {
                // javac has already refused an id it could not read as a constant of this machine's own enum.
                continue;
            }
            Optional<OperationModel> operation = operations.stream()
                    .filter((OperationModel candidate) -> candidate.transitionId().equals(identifier.get()))
                    .findFirst();
            if (operation.isEmpty()) {
                DiagnosticAnchor.of(method, check.get(), id.get()).report(diagnostics, NO_OPERATION,
                        method.getSimpleName() + " checks " + identifier.get() + ", which no operation of "
                                + facade.getSimpleName() + " executes: a Check names the identifier of one of the"
                                + " facade's operations");
                continue;
            }
            if (claims(method, check.get(), identifier.get(), checked) && repeats(method, operation.get(), types)) {
                checks.add(model(method, identifier.get()));
            }
        }
        return checks;
    }

    /**
     * SG514 at {@code check}, a {@code Check} that {@code method} carries beside a {@code Transition}: the method is
     * an operation, and a check is declared on a method of its own.
     */
    void checkNotOnOperation(ExecutableElement method, AnnotationMirror check) {
        DiagnosticAnchor.of(method, check).report(diagnostics, DUPLICATE, method.getSimpleName() + " carries a"
                + " Transition and a Check: a Check is declared on a method of its own, apart from the operation it"
                + " checks");
    }

    /** The {@code Check} of {@code method} generated for {@code facade}, if it carries one. Reports nothing. */
    private Optional<AnnotationMirror> own(ExecutableElement method, TypeElement facade) {
        for (AnnotationMirror mirror : vocabulary.findAll(method, VocabularyKind.CHECK)) {
            TypeElement declaration = (TypeElement) mirror.getAnnotationType().asElement();
            AnnotationMirror metadata = annotations.find(declaration, GeneratedVocabulary.class).orElseThrow();
            TypeMirror machine = TypedValues.classLiteral(annotations.value(metadata, "machine"));
            if (machine instanceof DeclaredType declared && declared.asElement().equals(facade)) {
                return Optional.of(mirror);
            }
        }
        return Optional.empty();
    }

    /** SG514 at {@code check} unless {@code identifier} is still unchecked: an operation has one check at most. */
    private boolean claims(ExecutableElement method, AnnotationMirror check, String identifier,
            Map<String, ExecutableElement> checked) {
        if (!checked.containsKey(identifier)) {
            checked.put(identifier, method);
            return true;
        }
        DiagnosticAnchor.of(method, check).report(diagnostics, DUPLICATE, method.getSimpleName() + " checks "
                + identifier + ", which " + checked.get(identifier).getSimpleName() + " checks already: an operation"
                + " has one Check at most");
        return false;
    }

    /**
     * SG513 unless {@code method} is declared as {@code Eligibility<E, S, T> method(E)}, or as
     * {@code Eligibility<E, S, T> method(E, I)} when {@code operation} takes an input of the type {@code I}, without
     * varargs or type parameters of its own. Types are compared as javac resolved them.
     */
    private boolean repeats(ExecutableElement method, OperationModel operation, MachineTypes types) {
        List<? extends VariableElement> parameters = method.getParameters();
        DeclaredType eligibility = signatures.declared(ELIGIBILITY, types.entity(), types.states(),
                types.transitions());
        boolean shaped = parameters.size() == (operation.inputType().isPresent() ? 2 : 1) && !method.isVarArgs()
                && method.getTypeParameters().isEmpty();
        if (shaped && signatures.same(parameters.get(0).asType(), types.entity())
                && operation.inputType().map((TypeMirror input) -> signatures.same(parameters.get(1).asType(), input))
                        .orElse(true)
                && signatures.same(method.getReturnType(), eligibility)) {
            return true;
        }
        String name = method.getSimpleName().toString();
        diagnostics.error(SIGNATURE, name + " must be declared as Eligibility<" + types.entityName() + ", "
                + types.statesName() + ", " + types.transitionsName() + "> " + name + "(" + types.entityName()
                + operation.inputType().map((TypeMirror input) -> ", " + input).orElse("") + "), the parameters of "
                + operation.name(), method);
        return false;
    }

    /** The check {@code method} declares of {@code identifier}, whose signature is that of the operation it checks. */
    private static CheckModel model(ExecutableElement method, String identifier) {
        Optional<VariableElement> input = method.getParameters().size() == 2
                ? Optional.of(method.getParameters().get(1)) : Optional.empty();
        return new CheckModel(method.getSimpleName().toString(), identifier,
                method.getParameters().get(0).getSimpleName().toString(),
                input.map((VariableElement parameter) -> parameter.getSimpleName().toString()),
                input.map(VariableElement::asType));
    }
}
