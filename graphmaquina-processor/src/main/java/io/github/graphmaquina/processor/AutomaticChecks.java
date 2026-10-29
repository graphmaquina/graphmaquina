package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.processor.model.OperationModel;
import io.github.graphmaquina.processor.model.TransitionModel;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * The two rules about automatic declarations that only the annotation path can break, because the DSL has no
 * annotation to misspell and no operation to give an input. Both are reported at the declaration that carries the
 * problem, once per origin of the edge, since each origin is an edge of its own.
 *
 * <p>A continuing {@code onFailure} needs an {@code onFailure} to continue ({@code SG305}), and an automatic edge
 * cannot run an operation that takes an input, because an automatic attempt has none to hand it ({@code SG515}).</p>
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class AutomaticChecks {

    private final Diagnostics diagnostics;

    /** SG305 for every origin of an edge that asks its {@code onFailure} to continue the chain and declares none. */
    void checkContinuationHasRoute(boolean continues, Optional<String> onFailure, String id, List<String> from,
            String to, DiagnosticAnchor anchor) {
        if (!continues || onFailure.isPresent()) {
            return;
        }
        for (String origin : from) {
            anchor.report(diagnostics, DiagnosticCode.SG305,
                    "onFailureContinues needs an onFailure on " + describe(origin, id, to));
        }
    }

    /**
     * SG515 at each of the automatic {@code mirrors}, for the edges that the operation {@code method} declares when
     * it takes an input. {@code edges} are those of {@code mirrors}, in the same order.
     */
    void checkOperationInput(ExecutableElement method, Optional<?> input, List<AnnotationMirror> mirrors,
            List<TransitionModel> edges) {
        if (input.isEmpty()) {
            return;
        }
        for (int at = 0; at < edges.size(); at++) {
            reportInput(DiagnosticAnchor.of(method, mirrors.get(at)), edges.get(at), method.getSimpleName().toString());
        }
    }

    /**
     * SG515 at {@code type} for each automatic edge the interface declares itself whose identifier an operation of
     * {@code operations} that takes an input executes: a further branch of that operation from another origin.
     */
    void checkTypeLevelInput(TypeElement type, List<TransitionModel> declared, List<OperationModel> operations) {
        for (TransitionModel edge : declared) {
            for (OperationModel operation : operations) {
                if (operation.inputType().isPresent() && operation.transitionId().equals(edge.transitionId())) {
                    reportInput(DiagnosticAnchor.of(type), edge, operation.name());
                }
            }
        }
    }

    private void reportInput(DiagnosticAnchor anchor, TransitionModel edge, String operation) {
        if (!edge.automatic()) {
            return;
        }
        for (String origin : edge.from()) {
            anchor.report(diagnostics, DiagnosticCode.SG515, "the automatic transition "
                    + describe(origin, edge.transitionId(), edge.to()) + " runs the operation " + operation
                    + ", which takes an input, and an automatic attempt has none");
        }
    }

    private static String describe(String from, String id, String to) {
        return from + " -" + id + "-> " + to;
    }
}
