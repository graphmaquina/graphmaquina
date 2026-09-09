package io.stategraph.processor;

import io.stategraph.annotation.GenerateGraphApi;
import io.stategraph.annotation.StateGraphs;
import io.stategraph.processor.model.MachineAccessor;
import io.stategraph.processor.model.MachineModel;
import io.stategraph.processor.model.SetModel;
import io.stategraph.processor.model.WatchModel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import lombok.RequiredArgsConstructor;

/**
 * Turns a @StateGraphs interface into a {@link SetModel}: one accessor per machine, every watched peer inside the set.
 *
 * <p>A machine is named by its own marker, {@code @GenerateGraphApi}, and by nothing else: an accessor that
 * returns anything else returns no machine of this library. Building a machine's model is the caller's work,
 * not this builder's; here a machine that failed is only a set that is not generated.</p>
 */
@RequiredArgsConstructor
final class SetModelBuilder {

    static final String MACHINES = "io.stategraph.api.Machines";

    private final Annotations annotations;
    private final Signatures signatures;
    private final Diagnostics diagnostics;

    /** {@code machines} resolves a machine to its model, or empty when that type has errors (already reported). */
    Optional<SetModel> build(TypeElement type, Function<TypeElement, Optional<MachineModel>> machines) {
        int before = diagnostics.errors();
        AnnotationMirror set = annotations.find(type, StateGraphs.class).orElseThrow();
        if (type.getNestingKind() != NestingKind.TOP_LEVEL) {
            diagnostics.error("SG502", type.getSimpleName() + " must be a top-level interface extending Machines: the generated companion"
                    + " lives next to it in the package", type, set);
            return Optional.empty();
        }
        if (type.getKind() != ElementKind.INTERFACE || !signatures.subtype(type.asType(), MACHINES)) {
            diagnostics.error("SG502", type.getSimpleName() + " must be an interface extending Machines", type, set);
            return Optional.empty();
        }
        List<MachineAccessor> accessors = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean silent = false;
        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) member;
            if (method.isDefault() || method.getModifiers().contains(Modifier.STATIC) || method.getModifiers().contains(Modifier.PRIVATE)) {
                continue;
            }
            TypeMirror returned = method.getReturnType();
            Optional<TypeElement> machineType = Optional.empty();
            if (returned instanceof DeclaredType declared && isMachine(declared.asElement())) {
                machineType = Optional.of((TypeElement) declared.asElement());
            }
            if (!method.getParameters().isEmpty() || machineType.isEmpty()) {
                diagnostics.error("SG510", method.getSimpleName() + " must be a no-argument accessor returning a"
                        + " @GenerateGraphApi interface", method);
                continue;
            }
            if (!seen.add(machineType.get().getQualifiedName().toString())) {
                diagnostics.error("SG510", machineType.get().getSimpleName() + " appears twice in " + type.getSimpleName(), method);
                continue;
            }
            Optional<MachineModel> model = machines.apply(machineType.get());
            if (model.isEmpty()) {
                silent = true;
                continue;
            }
            accessors.add(new MachineAccessor(method.getSimpleName().toString(), model.get()));
        }
        if (accessors.isEmpty() && !silent) {
            diagnostics.error("SG510", type.getSimpleName() + " declares no machine: add one accessor per"
                    + " @GenerateGraphApi interface", type, set);
        }
        for (MachineAccessor accessor : accessors) {
            for (WatchModel watch : accessor.machine().watches()) {
                String peer = watch.peer().facade().getQualifiedName().toString();
                if (!seen.contains(peer)) {
                    diagnostics.error("SG510", "the watch of " + accessor.machine().facadeName() + "." + watch.state() + " points to "
                            + watch.peer().facade().getSimpleName() + ", which is not in " + type.getSimpleName(), type, set);
                }
            }
        }
        if (silent || diagnostics.errors() > before) {
            return Optional.empty();
        }
        String packageName = signatures.elements().getPackageOf(type).getQualifiedName().toString();
        return Optional.of(new SetModel(type, packageName, accessors));
    }

    /** Whether {@code element} declares a graph this library generates an API for. */
    private boolean isMachine(Element element) {
        return annotations.find(element, GenerateGraphApi.class).isPresent();
    }
}
