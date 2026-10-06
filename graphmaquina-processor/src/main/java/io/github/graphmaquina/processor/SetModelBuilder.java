package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.annotation.StateGraphs;
import io.github.graphmaquina.processor.gen.BoundInvocation;
import io.github.graphmaquina.processor.gen.SetGenerator;
import io.github.graphmaquina.processor.model.MachineAccessor;
import io.github.graphmaquina.processor.model.MachineModel;
import io.github.graphmaquina.processor.model.SetModel;
import io.github.graphmaquina.processor.model.WatchModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * <p>An accessor is read from the set's own declarations, which is what the companion implements, so one the set
 * inherits from another interface is refused. A method of {@code Machines}, {@code AutoCloseable} or
 * {@code Object} that the set declares again, or inherits again from another interface, is no accessor: the
 * companion writes or inherits it already, and no redeclaration can narrow their results, which are {@code void},
 * primitive or the final {@code String}. Each machine must also be one the generated set can hold and build: a
 * second machine over the same entity and state enum is refused, as the registry would refuse it, and so is a
 * machine whose types the set's package cannot name. Once nothing else is wrong with the set, an instance method it
 * declares or inherits, an accessor or not, that has the signature of the companion's own static
 * {@code builder(...)} is refused too.</p>
 *
 * <p>A machine is named by its own marker, {@code @GenerateGraphApi}, and by nothing else: an accessor that
 * returns anything else returns no machine of this library. Building a machine's model is the caller's work,
 * not this builder's; here a machine that failed is only a set that is not generated.</p>
 */
@RequiredArgsConstructor
final class SetModelBuilder {

    static final String MACHINES = "io.github.graphmaquina.api.Machines";

    /** The types whose methods a set's companion implements whatever the set declares. */
    private static final List<String> BUILT_IN = List.of(MACHINES, "java.lang.AutoCloseable", "java.lang.Object");

    private final Annotations annotations;
    private final Signatures signatures;
    private final Diagnostics diagnostics;
    private final BoundInvocation invocations;

    /**
     * {@code machines} resolves a machine to its model, or empty, with the reason already reported: when that type
     * has errors, or as {@code SG602} at the set when the index of this compilation names it but no round handed it
     * to the processor.
     */
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
        Map<String, ExecutableElement> writers = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean silent = false;
        boolean anyAccessor = false;
        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) member;
            if (method.isDefault() || method.getModifiers().contains(Modifier.STATIC) || method.getModifiers().contains(Modifier.PRIVATE)) {
                continue;
            }
            if (signatures.overridden(method, type, BUILT_IN).isPresent()) {
                // A redeclaration of close(), toString(), equals or hashCode, which the companion writes or inherits.
                continue;
            }
            anyAccessor = true;
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
            if (!writesItsOwnState(method, model.get(), writers) || !reachable(method, model.get(), type)) {
                continue;
            }
            accessors.add(new MachineAccessor(method.getSimpleName().toString(), model.get()));
        }
        for (ExecutableElement inherited : signatures.inheritedAbstract(type, BUILT_IN)) {
            anyAccessor = true;
            diagnostics.error("SG510", type.getSimpleName() + " inherits the abstract method " + inherited.getSimpleName()
                    + " from " + ((TypeElement) inherited.getEnclosingElement()).getQualifiedName() + ", which the"
                    + " generated companion would not implement: declare every accessor on " + type.getSimpleName()
                    + " itself", type, set);
            if (inherited.getReturnType() instanceof DeclaredType returned && isMachine(returned.asElement())) {
                // The machine it returns is named by the set all the same: a watch of it is not missing too.
                seen.add(((TypeElement) returned.asElement()).getQualifiedName().toString());
            }
        }
        if (!anyAccessor) {
            // An accessor that was declared and refused has said why already; only a set without one says this.
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
        String packageName = signatures.elements().getPackageOf(type).getQualifiedName().toString();
        SetModel model = new SetModel(type, packageName, accessors);
        if (silent || diagnostics.errors() > before) {
            return Optional.empty();
        }
        checkBuilder(type, set, model);
        return diagnostics.errors() > before ? Optional.empty() : Optional.of(model);
    }

    /**
     * SG510 unless {@code machine} is the first machine of the set to write the state of its entity with its
     * state enum: a registry refuses a second machine over the same entity and state enum, whether its transition
     * catalogue is another one or the same, so a set that holds both could never be built. {@code writers} maps
     * each entity and state enum already written to the accessor that writes them.
     */
    private boolean writesItsOwnState(ExecutableElement method, MachineModel machine, Map<String, ExecutableElement> writers) {
        String written = machine.types().entityElement().getQualifiedName() + "/"
                + machine.types().statesElement().getQualifiedName();
        ExecutableElement previous = writers.putIfAbsent(written, method);
        if (previous == null) {
            return true;
        }
        diagnostics.error("SG510", method.getSimpleName() + " holds " + machine.facadeName() + ", which writes the state of "
                + machine.types().entityName() + " with " + machine.types().statesName() + " as "
                + previous.getSimpleName() + " already does: a registry holds one machine per entity and state enum,"
                + " whatever its transitions", method);
        return false;
    }

    /**
     * Whether the companion of {@code set}, generated in the set's package, can name every type it builds
     * {@code machine} with: the machine's entity and enums, the classes with instance members the machine is
     * built from, and the parameter type its state writer's argument is pinned to. {@code SG510} otherwise,
     * naming each type that package cannot access.
     */
    private boolean reachable(ExecutableElement method, MachineModel machine, TypeElement set) {
        String packageName = Accessibility.packageOf(set);
        Set<String> inaccessible = new LinkedHashSet<>();
        for (TypeMirror type : List.of(machine.types().entity(), machine.types().states(), machine.types().transitions())) {
            inaccessible.addAll(Accessibility.inaccessible(type, packageName));
        }
        for (TypeElement dependency : machine.instanceDependencies()) {
            inaccessible.addAll(Accessibility.inaccessible(dependency, packageName));
        }
        for (TypeMirror cast : invocations.casts(SetGenerator.writer(machine))) {
            inaccessible.addAll(Accessibility.inaccessible(cast, packageName));
        }
        if (inaccessible.isEmpty()) {
            return true;
        }
        diagnostics.error("SG510", method.getSimpleName() + " holds " + machine.types().facade().getQualifiedName()
                + ", which the companion of " + set.getSimpleName() + " builds with " + String.join(" and ", inaccessible)
                + ", which " + Accessibility.describe(packageName) + ", where that companion is generated, cannot access:"
                + " make " + (inaccessible.size() == 1 ? "it" : "them") + " public, or declare the set in the package of "
                + machine.facadeName(), method);
        return false;
    }

    /**
     * SG510 for an instance method of the set, abstract or default, declared or inherited, that the companion's own
     * static {@code builder(...)} would clash with, as {@link Signatures#clashing} finds it: one named
     * {@code builder} taking what that {@code builder(...)} takes, the timeout shared by the set's asynchronous
     * machines when it has any, then every class with instance members its machines use. javac would refuse the
     * companion. A method the set declares is reported where it is declared, as an accessor when it is abstract, and
     * one it only inherits at the set, with the interface that declares it. With any other parameters, the two are
     * overloads and both are declared. {@link StructuralChecks#checkGraphFactory} refuses the facade's counterpart.
     *
     * <p>It runs on a set without any other error, the only one whose companion's {@code builder(...)} is known:
     * fixing another error, a machine that failed or an accessor refused or missing, can add the timeout or a class
     * to it. The message names the companion, {@code <Set>Runtime}, or names it through the set when the set's name
     * has no PascalCase form: such a set, refused as {@code SG603} before it is built, is checked all the same.</p>
     */
    private void checkBuilder(TypeElement type, AnnotationMirror set, SetModel model) {
        List<TypeMirror> parameters = new ArrayList<>();
        if (model.hasAsync()) {
            parameters.add(signatures.declared("java.time.Duration"));
        }
        for (TypeElement dependency : model.dependencies()) {
            parameters.add(dependency.asType());
        }
        List<ExecutableElement> clashing = signatures.clashing(type, "builder", parameters);
        if (clashing.isEmpty()) {
            return;
        }
        String takes;
        if (parameters.isEmpty()) {
            takes = "no parameter for a set without asynchronous machines and without instance dependencies";
        } else if (!model.hasAsync()) {
            takes = "every class with instance members the set's machines use";
        } else if (model.dependencies().isEmpty()) {
            takes = "the timeout shared by the set's asynchronous machines";
        } else {
            takes = "the timeout shared by the set's asynchronous machines, then every class with instance members its"
                    + " machines use";
        }
        String builder = StructuralChecks.factory("builder", parameters);
        String companion = StructuralChecks.companion(model.setName());
        for (ExecutableElement method : clashing) {
            if (method.getEnclosingElement().equals(type)) {
                diagnostics.error("SG510", builder + " is the static " + builder + " " + companion + " declares, which"
                        + " takes " + takes + ": give the " + (method.isDefault() ? "method" : "accessor") + " another name",
                        method);
                return;
            }
        }
        diagnostics.error("SG510", type.getSimpleName() + " inherits " + builder + " from "
                + ((TypeElement) clashing.get(0).getEnclosingElement()).getQualifiedName() + ", but " + companion
                + " declares a static " + builder + ", which takes " + takes + ": give that method another name", type, set);
    }

    /** Whether {@code element} declares a graph this library generates an API for. */
    private boolean isMachine(Element element) {
        return annotations.find(element, GenerateGraphApi.class).isPresent();
    }
}
