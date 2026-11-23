package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.annotation.internal.VocabularyKind;
import io.github.graphmaquina.api.ChoiceEligibility;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.processor.model.ChoiceModel;
import io.github.graphmaquina.processor.model.MachineTypes;
import io.github.graphmaquina.processor.model.OperationModel;
import io.github.graphmaquina.processor.model.SubmachineModel;
import io.github.graphmaquina.processor.model.TransitionModel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Reads the choices a facade declares: its abstract methods that carry the {@code Choice} generated for that very
 * machine and neither a {@code Transition} nor a {@code Check}, each of which runs, or checks without running it, the
 * first of the candidate identifiers it names that is admitted.
 *
 * <p>A choice is no operation. It owns no identifier, so a candidate may also have an operation of its own, and opens
 * no edge. Its result says what it does: {@code Outcome<E, S, T>} runs the choice and
 * {@code ChoiceEligibility<E, S, T>} checks it, over the parameters {@code (E)} or {@code (E, I)}, without varargs
 * or type parameters of its own. It names at least one candidate, and each once. Each candidate is declared by an
 * edge of the graph and is no exit of a sub-machine, which only the sub-machine runs, and one that an operation of
 * the facade executes needs the input of that operation: of the same type, and none when the operation takes none.
 * An identifier that failure routes use may be a candidate, since a call can still request it. Each of these rules is
 * {@code SG516}, with a message of its own, and so is a method that carries a {@code Choice} beside a
 * {@code Transition} or a {@code Check}, since a choice is declared on a method of its own.</p>
 *
 * <p>A candidate that no edge declares, but whose {@code Transition} the facade or one of its methods carries, belongs
 * to a declaration refused for a reason already reported, and draws nothing more; the choice is left out. Where a
 * {@code Choice} may be written at all is asked elsewhere: on a default, static or private method it is never read,
 * which {@link VocabularyPlacement} reports, and the {@code Choice} of another machine is refused where it is read,
 * by the builder of the facade that carries it.</p>
 */
final class TypedChoices {

    private static final String OUTCOME = Outcome.class.getName();
    private static final String CHOICE_ELIGIBILITY = ChoiceEligibility.class.getName();

    /** The reason of a candidate whose refusal was reported already, which is not reported again. */
    private static final String REPORTED = "";

    private final TypedChecks checks;
    private final Signatures signatures;
    private final Diagnostics diagnostics;
    private final Annotations annotations;

    TypedChoices(TypedChecks checks, Signatures signatures, Diagnostics diagnostics) {
        this.checks = checks;
        this.signatures = signatures;
        this.diagnostics = diagnostics;
        this.annotations = new Annotations(signatures.elements());
    }

    /**
     * The facade, the types of its machine, the identifiers its edges declare, its operations and its sub-machines:
     * what the candidates of a choice are read against.
     */
    private record Graph(TypeElement facade, MachineTypes types, Set<String> declared,
                         List<OperationModel> operations, List<SubmachineModel> submachines) { }

    /**
     * The choices of {@code facade}, in the order {@code declared} holds them. {@code declared} are the abstract
     * methods the facade declares that carry a {@code Choice} of its own vocabulary and neither a {@code Transition}
     * nor a {@code Check}; {@code edges} are the edges the facade declares itself, {@code operations} its operations
     * and {@code submachines} its sub-machines, all read from the same facade. A choice with a problem is reported and
     * left out; one whose candidates javac could not read is left out, since javac has refused it already.
     */
    List<ChoiceModel> read(TypeElement facade, MachineTypes types, List<ExecutableElement> declared,
            List<TransitionModel> edges, List<OperationModel> operations, List<SubmachineModel> submachines) {
        Set<String> identifiers = new LinkedHashSet<>();
        for (TransitionModel edge : edges) {
            identifiers.add(edge.transitionId());
        }
        for (OperationModel operation : operations) {
            operation.transitions().forEach((TransitionModel edge) -> identifiers.add(edge.transitionId()));
        }
        Graph graph = new Graph(facade, types, identifiers, operations, submachines);
        List<ChoiceModel> choices = new ArrayList<>();
        for (ExecutableElement method : declared) {
            Optional<AnnotationMirror> choice = checks.own(method, VocabularyKind.CHOICE, facade).stream().findFirst();
            if (choice.isEmpty() || annotations.find(choice.get(), "value").isEmpty()) {
                // javac refuses a Choice that omits its candidates, and leaves them out.
                continue;
            }
            List<AnnotationValue> values = annotations.array(choice.get(), "value");
            Optional<List<String>> candidates = TypedValues.constantNames(values, types.transitionsElement());
            if (candidates.isEmpty()) {
                // javac has already refused a candidate it could not read as a constant of this machine's own enum.
                continue;
            }
            Optional<Boolean> check = role(method, types);
            boolean shaped = shaped(method, types);
            if (check.isPresent() && shaped && admits(method, choice.get(), values, candidates.get(), graph)) {
                choices.add(model(method, candidates.get(), check.get()));
            }
        }
        return choices;
    }

    /**
     * SG516 at {@code choice}, a {@code Choice} that {@code method} carries beside a vocabulary of another role,
     * {@code other}, a {@code Transition} or a {@code Check}: a choice is declared on a method of its own.
     */
    void checkAlone(ExecutableElement method, AnnotationMirror choice, String other) {
        DiagnosticAnchor.of(method, choice).report(diagnostics, DiagnosticCode.SG516, method.getSimpleName()
                + " carries a Choice and a " + other + ": a Choice is declared on a method of its own, without a"
                + " Transition or a Check");
    }

    /**
     * Whether {@code method} checks its choice, true, or runs it, false, as its result says: empty after SG516 when
     * the result is neither {@code Outcome<E, S, T>} nor {@code ChoiceEligibility<E, S, T>} of the machine's own
     * types, as javac resolved them.
     */
    private Optional<Boolean> role(ExecutableElement method, MachineTypes types) {
        TypeMirror result = method.getReturnType();
        if (signatures.same(result, signatures.declared(OUTCOME, types.entity(), types.states(),
                types.transitions()))) {
            return Optional.of(false);
        }
        if (signatures.same(result, signatures.declared(CHOICE_ELIGIBILITY, types.entity(), types.states(),
                types.transitions()))) {
            return Optional.of(true);
        }
        String arguments = "<" + types.entityName() + ", " + types.statesName() + ", " + types.transitionsName() + ">";
        diagnostics.error(DiagnosticCode.SG516, method.getSimpleName() + " must return Outcome" + arguments
                + ", to run the choice, or ChoiceEligibility" + arguments + ", to check it", method);
        return Optional.empty();
    }

    /**
     * SG516 unless {@code method} takes the entity, and at most one input after it, without varargs or type
     * parameters of its own.
     */
    private boolean shaped(ExecutableElement method, MachineTypes types) {
        List<? extends VariableElement> parameters = method.getParameters();
        if ((parameters.size() == 1 || parameters.size() == 2) && !method.isVarArgs()
                && method.getTypeParameters().isEmpty()
                && signatures.same(parameters.get(0).asType(), types.entity())) {
            return true;
        }
        String name = method.getSimpleName().toString();
        diagnostics.error(DiagnosticCode.SG516, name + " must be declared as " + name + "(" + types.entityName()
                + ") or " + name + "(" + types.entityName() + ", I), the entity and at most one input, without"
                + " varargs or method type parameters", method);
        return false;
    }

    /**
     * Whether every candidate of {@code choice}, the {@code Choice} of {@code method}, may be one: SG516 at the
     * choice when it names none, and at the value of each candidate {@link #refusal} refuses.
     */
    private boolean admits(ExecutableElement method, AnnotationMirror choice, List<AnnotationValue> values,
            List<String> candidates, Graph graph) {
        String name = method.getSimpleName().toString();
        if (candidates.isEmpty()) {
            DiagnosticAnchor.of(method, choice).report(diagnostics, DiagnosticCode.SG516,
                    name + " names no candidate: a Choice names at least one identifier");
            return false;
        }
        Optional<TypeMirror> input = method.getParameters().size() == 2
                ? Optional.of(method.getParameters().get(1).asType()) : Optional.empty();
        Set<String> named = new LinkedHashSet<>();
        boolean admitted = true;
        for (int index = 0; index < candidates.size(); index++) {
            String candidate = candidates.get(index);
            Optional<String> refusal = named.add(candidate)
                    ? refusal(name, candidate, input, graph)
                    : Optional.of(" twice: a Choice names each candidate once");
            if (refusal.filter((String reason) -> !reason.equals(REPORTED)).isPresent()) {
                DiagnosticAnchor.of(method, choice, values.get(index)).report(diagnostics, DiagnosticCode.SG516,
                        name + " names " + candidate + refusal.get());
            }
            admitted &= refusal.isEmpty();
        }
        return admitted;
    }

    /**
     * Why {@code candidate}, named once by the choice {@code name}, whose input is {@code input}, cannot be one of its
     * candidates, said after the candidate's name; empty when it can be. {@link #REPORTED} when no edge declares it
     * but the facade or one of its methods carries its {@code Transition}: that declaration was refused, which was
     * reported.
     */
    private Optional<String> refusal(String name, String candidate, Optional<TypeMirror> input, Graph graph) {
        if (!graph.declared().contains(candidate)) {
            return Optional.of(refused(candidate, graph) ? REPORTED
                    : ", which no edge of " + graph.facade().getSimpleName() + " declares: each candidate of a"
                            + " Choice is the identifier of an edge");
        }
        for (SubmachineModel submachine : graph.submachines()) {
            for (SubmachineModel.Exit exit : submachine.exits()) {
                if (exit.transition().equals(candidate)) {
                    return Optional.of(", an exit of the sub-machine of " + graph.facade().getSimpleName() + "."
                            + submachine.state() + ", which only the sub-machine runs: a Choice cannot name an exit");
                }
            }
        }
        for (OperationModel operation : graph.operations()) {
            if (operation.transitionId().equals(candidate) && !sameInput(operation.inputType(), input)) {
                return Optional.of(", which " + operation.name() + " executes with " + described(operation.inputType())
                        + ", while " + name + " takes " + described(input) + ": a Choice takes the same input as each"
                        + " operation that executes one of its candidates");
            }
        }
        return Optional.empty();
    }

    /**
     * Whether the facade of {@code graph}, or one of its methods whatever its modifiers, carries a {@code Transition}
     * of its own vocabulary that names {@code candidate}, which no edge declares: a declaration refused, as
     * {@code SG504}, {@code SG703} or an error of the edge, which was reported.
     */
    private boolean refused(String candidate, Graph graph) {
        if (checks.declaredOnAMethod(graph.facade(), candidate, graph.types())) {
            return true;
        }
        for (AnnotationMirror transition : checks.own(graph.facade(), VocabularyKind.TRANSITION, graph.facade())) {
            Optional<String> named = annotations.find(transition, "id").flatMap((AnnotationValue value) ->
                    TypedValues.constantName(value, graph.types().transitionsElement()));
            if (named.isPresent() && named.get().equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code operation} and {@code choice} both take no input, or inputs of the same type. */
    private boolean sameInput(Optional<TypeMirror> operation, Optional<TypeMirror> choice) {
        return operation.isPresent() == choice.isPresent()
                && operation.map((TypeMirror type) -> signatures.same(type, choice.get())).orElse(true);
    }

    /** {@code an input of type I}, or {@code no input}. */
    private static String described(Optional<TypeMirror> input) {
        return input.map((TypeMirror type) -> "an input of type " + type).orElse("no input");
    }

    /** The choice {@code method} declares of {@code candidates}, a check when {@code check} is true. */
    private static ChoiceModel model(ExecutableElement method, List<String> candidates, boolean check) {
        Optional<VariableElement> input = method.getParameters().size() == 2
                ? Optional.of(method.getParameters().get(1)) : Optional.empty();
        return new ChoiceModel(method.getSimpleName().toString(), candidates, check,
                method.getParameters().get(0).getSimpleName().toString(),
                input.map((VariableElement parameter) -> parameter.getSimpleName().toString()),
                input.map(VariableElement::asType));
    }
}
