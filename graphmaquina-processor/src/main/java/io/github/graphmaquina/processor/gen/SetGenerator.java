package io.github.graphmaquina.processor.gen;

import io.github.graphmaquina.codegen.emit.SymbolNames;
import io.github.graphmaquina.processor.model.Binding;
import io.github.graphmaquina.processor.model.MachineAccessor;
import io.github.graphmaquina.processor.model.MachineModel;
import io.github.graphmaquina.processor.model.Property;
import io.github.graphmaquina.processor.model.SetModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Emits {@code <Set>Runtime}, named by {@link SymbolNames#runtimeName} as a machine's companion is: a builder that
 * assembles the registry from the machines' companions and hands out the facades.
 *
 * <p>Like a machine's companion, it is marked {@code @GeneratedCompanion} and suppresses the {@code deprecation}
 * and {@code removal} warnings of the members it calls, and it writes its types and suffixes its variables as
 * {@link MachineGenerator} describes, its own {@code Builder} counted among the member types of its scope; a
 * variable is named after its role, after the accessor it serves, or after the class a parameter of
 * {@code builder(...)} takes. {@link #emit} answers, beside the source, what the processor checks against that
 * scope before writing it.</p>
 *
 * <p>Its {@code Builder} mirrors {@code MachineRegistry.Builder} and {@code StateMachines.Builder}: it creates
 * them when it is created and hands every setting to them as soon as it is given, so a setting they refuse is
 * refused where it is given, a second listener is refused instead of replacing the first, and the builder is
 * single-use. The state and the identity of each machine are read and written by lambdas that call the very
 * accessors the property selected, never by a method reference, which would also consider a static overload of
 * the same name.</p>
 */
public final class SetGenerator {

    private static final String MACHINE = "io.github.graphmaquina.api.Machine";
    private static final String TRANSITION_LISTENER = "io.github.graphmaquina.api.TransitionListener";
    private static final String MACHINE_REGISTRY = "io.github.graphmaquina.runtime.MachineRegistry";
    private static final String STATE_MACHINES = "io.github.graphmaquina.runtime.StateMachines";
    private static final String DURATION = "java.time.Duration";
    private static final String OBJECTS = "java.util.Objects";
    private static final String EXECUTOR = "java.util.concurrent.Executor";
    private static final String GENERATED = "io.github.graphmaquina.annotation.internal.GeneratedCompanion";
    private static final String SUPPRESS_WARNINGS = "java.lang.SuppressWarnings";
    private static final String OVERRIDE = "java.lang.Override";
    private static final String CLASS = "java.lang.Class";
    private static final String ILLEGAL_ARGUMENT = "java.lang.IllegalArgumentException";
    private static final String ILLEGAL_STATE = "java.lang.IllegalStateException";

    /** The member types the set companion declares itself: its public {@code Builder}. */
    public static final Set<String> MEMBER_TYPES = Set.of("Builder");

    /**
     * The parameters of the {@code Builder}'s methods and the local variable of its {@code build()}, by the role
     * they play, which also is their usual name.
     */
    private static final List<String> SETTER_PARAMETERS = List.of("machine", "timeout", "executor", "depth", "chain",
            "listener", "built");

    private SetGenerator() { }

    /**
     * The names the set companion gives its variables, each one free of every identifier the file writes at the
     * head of a type reference and of every other variable in its scope.
     *
     * <p>{@code fields} names, by accessor, the private field that keeps each facade, and {@code registry} the one
     * that keeps the registry; {@code builders} names, by accessor, the field of the {@code Builder} that holds each
     * machine's own builder, beside {@code registryBuilder} and {@code used}. {@code parameters} maps the qualified
     * name of each parameter type of {@code builder(...)} to the parameter's name, the shared timeout first when
     * the set has one, whose name {@code timeout} holds as well; {@code entity} and {@code state} name the
     * parameters of the accessor lambdas, and {@code setters}, by role, the parameters of the {@code Builder}'s
     * methods and the local variable of its {@code build()}.</p>
     */
    private record Variables(Map<String, String> fields, String registry, Map<String, String> builders,
                             String registryBuilder, String used, Map<String, String> parameters, String timeout,
                             String entity, String state, Map<String, String> setters) { }

    public static String qualifiedName(SetModel model) {
        return SymbolNames.qualified(model.packageName(), SymbolNames.runtimeName(model.setName()));
    }

    /** What {@link TypeSource} writes in the companion of {@code model}: the types that pin a state writer's argument. */
    private static Written rendered(SetModel model, BoundInvocation invocations) {
        Written written = new Written(Map.of(), Map.of(), Map.of(), Map.of());
        for (MachineAccessor accessor : model.machines()) {
            for (TypeMirror cast : invocations.casts(writer(accessor.machine()))) {
                written = written.and(TypeSource.written(cast));
            }
        }
        return written;
    }

    /**
     * The companion of {@code model}, with what it writes at the head of its names, as
     * {@link MachineGenerator#emit} answers them for a machine.
     */
    public static Emitted emit(SetModel model, BoundInvocation invocations) {
        String companion = SymbolNames.runtimeName(model.setName());
        Names names = names(model);
        Written rendered = rendered(model, invocations);
        Variables variables = variables(model, names.written().and(rendered));
        String set = names.of(model.type().getQualifiedName().toString());
        String registry = variables.registry();
        SourceWriter out = new SourceWriter();
        out.packageDeclaration(model.packageName());
        for (String type : names.imports()) {
            out.line("import " + type + ";");
        }
        out.blank();
        out.line("/** Generated set of {@link " + set + "}: builds the registry once and hands out the facades. Do not edit. */");
        out.line("@" + names.of(GENERATED));
        out.line("@" + names.of(SUPPRESS_WARNINGS) + "({\"deprecation\", \"removal\"})");
        out.open("public final class " + companion + " implements " + set);
        out.blank();
        out.line("private final " + names.of(MACHINE_REGISTRY) + " " + registry + ";");
        for (MachineAccessor accessor : model.machines()) {
            out.line("private final " + facadeName(accessor, names) + " " + variables.fields().get(accessor.method()) + ";");
        }
        out.blank();
        out.open("private " + companion + "(" + names.of(MACHINE_REGISTRY) + " " + registry + ")");
        out.line("this." + registry + " = " + registry + ";");
        for (MachineAccessor accessor : model.machines()) {
            out.line("this." + variables.fields().get(accessor.method()) + " = new " + companionName(accessor, names)
                    + "(" + registry + ".of(" + companionQualifier(accessor, names) + ".KEY));");
        }
        out.close();
        out.blank();
        List<String> parameters = new ArrayList<>();
        for (Map.Entry<String, String> parameter : variables.parameters().entrySet()) {
            parameters.add(names.of(parameter.getKey()) + " " + parameter.getValue());
        }
        out.line("/** Every class with instance members the machines use" + (model.hasAsync() ? ", after the timeout shared by the asynchronous machines" : "") + ". */");
        out.open("public static Builder builder(" + String.join(", ", parameters) + ")");
        out.line("return new Builder(" + String.join(", ", variables.parameters().values()) + ");");
        out.close();
        out.blank();
        builder(model, names, invocations, variables, parameters, out);
        for (MachineAccessor accessor : model.machines()) {
            out.blank();
            out.line("@" + names.of(OVERRIDE));
            out.line("public " + facadeName(accessor, names) + " " + accessor.method() + "() { return "
                    + variables.fields().get(accessor.method()) + "; }");
        }
        out.blank();
        out.line("@" + names.of(OVERRIDE));
        out.line("public void close() { " + registry + ".close(); }");
        out.close();
        return new Emitted(out.toString(), names.written().and(rendered));
    }

    private static void builder(SetModel model, Names names, BoundInvocation invocations, Variables variables,
                                List<String> parameters, SourceWriter out) {
        String objects = names.qualifier(OBJECTS);
        String registry = variables.registryBuilder();
        String used = variables.used();
        Map<String, String> setters = variables.setters();
        out.line("/**");
        out.line(" * Mirrors MachineRegistry.Builder and StateMachines.Builder, which it creates with itself and hands");
        out.line(" * every setting to as soon as it is given: what they refuse is refused where it is given, a second");
        out.line(" * listener included. Single-use and not thread-safe: once build() succeeded, nothing is accepted.");
        out.line(" */");
        out.open("public static final class Builder");
        out.blank();
        out.line("private final " + names.of(MACHINE_REGISTRY) + ".Builder " + registry + " = "
                + names.qualifier(MACHINE_REGISTRY) + ".builder();");
        for (MachineAccessor accessor : model.machines()) {
            out.line("private final " + machineBuilder(accessor.machine(), names) + " "
                    + variables.builders().get(accessor.method()) + ";");
        }
        out.line("private boolean " + used + ";");
        out.blank();
        out.open("private Builder(" + String.join(", ", parameters) + ")");
        for (String name : variables.parameters().values()) {
            out.line(objects + ".requireNonNull(" + name + ", " + MachineGenerator.quote(name) + ");");
        }
        for (MachineAccessor accessor : model.machines()) {
            MachineModel machine = accessor.machine();
            List<String> arguments = new ArrayList<>();
            for (TypeElement dependency : machine.instanceDependencies()) {
                arguments.add(variables.parameters().get(dependency.getQualifiedName().toString()));
            }
            String builder = variables.builders().get(accessor.method());
            out.line("this." + builder + " = " + names.qualifier(STATE_MACHINES) + ".from("
                    + companionQualifier(accessor, names) + ".graph(" + String.join(", ", arguments) + "))");
            out.indent().indent();
            out.line(".state(" + accessors(machine, names, invocations, variables) + ")");
            out.line(".identity(" + reader(machine, machine.idGetter(), names, invocations, variables) + ")");
            out.line(".as(" + facadeName(accessor, names) + ".class);");
            out.outdent().outdent();
            if (machine.hasAsync()) {
                out.line(builder + ".asyncTimeout(" + variables.timeout() + ");");
            }
            out.line(registry + ".add(" + builder + ");");
        }
        out.close();
        out.blank();
        if (model.hasAsync()) {
            String machine = setters.get("machine");
            String timeout = setters.get("timeout");
            out.line("/** Overrides the shared timeout for one asynchronous machine. */");
            out.open("public Builder asyncTimeout(" + names.of(CLASS) + "<? extends " + names.of(MACHINE) + "<?, ?, ?>> "
                    + machine + ", " + names.of(DURATION) + " " + timeout + ")");
            out.line("checkUnused();");
            out.line(objects + ".requireNonNull(" + machine + ", \"machine\");");
            out.line(objects + ".requireNonNull(" + timeout + ", \"timeout\");");
            for (MachineAccessor accessor : model.machines()) {
                if (accessor.machine().hasAsync()) {
                    out.open("if (" + machine + ".equals(" + facadeName(accessor, names) + ".class))");
                    out.line(variables.builders().get(accessor.method()) + ".asyncTimeout(" + timeout + ");");
                    out.line("return this;");
                    out.close();
                }
            }
            out.line("throw new " + names.of(ILLEGAL_ARGUMENT) + "(" + machine + ".getSimpleName() + "
                    + MachineGenerator.quote(" is not an asynchronous machine of " + model.setName()) + ");");
            out.close();
            out.blank();
        }
        String executor = setters.get("executor");
        out.open("public Builder asyncExecutor(" + names.of(EXECUTOR) + " " + executor + ")");
        out.line("checkUnused();");
        out.line(objects + ".requireNonNull(" + executor + ", \"executor\");");
        for (MachineAccessor accessor : model.machines()) {
            out.line(variables.builders().get(accessor.method()) + ".asyncExecutor(" + executor + ");");
        }
        out.line("return this;");
        out.close();
        out.blank();
        forward(out, "maxCascadeDepth", "int " + setters.get("depth"), registry + ".maxCascadeDepth(" + setters.get("depth") + ");");
        forward(out, "maxWatchChain", "int " + setters.get("chain"), registry + ".maxWatchChain(" + setters.get("chain") + ");");
        forward(out, "listener", names.of(TRANSITION_LISTENER) + " " + setters.get("listener"),
                registry + ".listener(" + setters.get("listener") + ");");
        String built = setters.get("built");
        String companion = names.of(qualifiedName(model));
        out.open("public " + names.of(model.type().getQualifiedName().toString()) + " build()");
        out.line("checkUnused();");
        out.line(companion + " " + built + " = new " + companion + "(" + registry + ".build());");
        out.line(used + " = true;");
        out.line("return " + built + ";");
        out.close();
        out.blank();
        out.line("/** Refuses any use once build() has succeeded: a built set is neither rebuilt nor reconfigured. */");
        out.open("private void checkUnused()");
        out.open("if (" + used + ")");
        out.line("throw new " + names.of(ILLEGAL_STATE) + "("
                + MachineGenerator.quote("this " + SymbolNames.runtimeName(model.setName()) + ".Builder was already used") + ");");
        out.close();
        out.close();
        out.close();
    }

    /** One setting the {@code Builder} hands to the registry's builder as soon as it is given. */
    private static void forward(SourceWriter out, String method, String parameter, String statement) {
        out.open("public Builder " + method + "(" + parameter + ")");
        out.line("checkUnused();");
        out.line(statement);
        out.line("return this;");
        out.close();
        out.blank();
    }

    /**
     * The state reader and writer of {@code machine} as two lambdas, each calling the accessor the property
     * selected; the writer's argument is pinned, as {@link BoundInvocation} decides, to the parameter type that
     * keeps that writer selected.
     */
    private static String accessors(MachineModel machine, Names names, BoundInvocation invocations, Variables variables) {
        String e = names.of(machine.types().entityElement().getQualifiedName().toString());
        String s = names.of(machine.types().statesElement().getQualifiedName().toString());
        String entity = variables.entity();
        String state = variables.state();
        return reader(machine, machine.stateGetter(), names, invocations, variables) + ", (" + e + " " + entity + ", "
                + s + " " + state + ") -> " + invocations.emit(writer(machine), entity, List.of(state));
    }

    /** A lambda that reads {@code property} from the entity of {@code machine} by calling its selected accessor. */
    private static String reader(MachineModel machine, Property property, Names names, BoundInvocation invocations,
                                 Variables variables) {
        String e = names.of(machine.types().entityElement().getQualifiedName().toString());
        return "(" + e + " " + variables.entity() + ") -> "
                + invocations.emit(property.accessor(), variables.entity(), List.of());
    }

    /**
     * The selected state writer of {@code machine}, with the state the set's lambda hands it as the static type of
     * its one argument, which is what decides whether the call must pin that argument's type.
     */
    public static Binding writer(MachineModel machine) {
        Binding setter = machine.stateSetter().accessor();
        return new Binding(setter.owner(), setter.method(), setter.effectiveType(), setter.role(),
                List.of(machine.types().states()), false, false);
    }

    /** {@code StateMachines.Builder<E, S, T>} for {@code machine}, as this file writes it. */
    private static String machineBuilder(MachineModel machine, Names names) {
        return names.of(STATE_MACHINES) + ".Builder<"
                + names.of(machine.types().entityElement().getQualifiedName().toString()) + ", "
                + names.of(machine.types().statesElement().getQualifiedName().toString()) + ", "
                + names.of(machine.types().transitionsElement().getQualifiedName().toString()) + ">";
    }

    /**
     * Allocates every variable of the companion. A field takes its name before anything its class declares
     * inside a method, a parameter before the lambdas of its method, and nothing takes an identifier the file
     * writes at the head of a type reference: so nothing a nested scope declares hides what that scope reads,
     * and no field named after an accessor collides with a field the companion needs for itself.
     */
    private static Variables variables(SetModel model, Written written) {
        Set<String> taken = new LinkedHashSet<>(written.identifiers());
        Map<String, String> fields = new LinkedHashMap<>();
        for (MachineAccessor accessor : model.machines()) {
            fields.put(accessor.method(), MachineGenerator.unique(accessor.method(), taken));
        }
        String registry = MachineGenerator.unique("machineRegistry", taken);
        String registryBuilder = MachineGenerator.unique("registry", taken);
        Map<String, String> builders = new LinkedHashMap<>();
        for (MachineAccessor accessor : model.machines()) {
            builders.put(accessor.method(), MachineGenerator.unique(accessor.method() + "Builder", taken));
        }
        String used = MachineGenerator.unique("used", taken);
        Map<String, String> parameters = new LinkedHashMap<>();
        String timeout = "";
        if (model.hasAsync()) {
            timeout = MachineGenerator.unique("asyncTimeout", taken);
            parameters.put(DURATION, timeout);
        }
        Map<String, String> dependencies = MachineGenerator.parameterNames(model.dependencies(), taken);
        parameters.putAll(dependencies);
        taken.addAll(dependencies.values());
        String entity = MachineGenerator.unique("entity", taken);
        String state = MachineGenerator.unique("state", taken);
        Map<String, String> setters = new LinkedHashMap<>();
        for (String role : SETTER_PARAMETERS) {
            setters.put(role, MachineGenerator.unique(role, new LinkedHashSet<>(taken)));
        }
        return new Variables(fields, registry, builders, registryBuilder, used, parameters, timeout, entity, state, setters);
    }

    /** How the set companion writes its types, from what it claims and what its scope holds already, its own Builder included. */
    private static Names names(SetModel model) {
        return new Names(model.packageName(), types(model), Names.Scope.of(Names.packageOf(model.type()), model.type(),
                Map.of("Builder", qualifiedName(model) + ".Builder")));
    }

    /** How this file writes the machine's facade interface. */
    private static String facadeName(MachineAccessor accessor, Names names) {
        return names.of(accessor.machine().types().facade().getQualifiedName().toString());
    }

    /** How this file writes the machine's generated companion. */
    private static String companionName(MachineAccessor accessor, Names names) {
        return names.of(MachineGenerator.qualifiedName(accessor.machine()));
    }

    /** How this file writes the machine's companion where it reads a static member of it. */
    private static String companionQualifier(MachineAccessor accessor, Names names) {
        return names.qualifier(MachineGenerator.qualifiedName(accessor.machine()));
    }

    /**
     * Every type the set companion writes through {@link Names}, in the order it claims them: its own first, then
     * the fixed ones, then the machines'.
     */
    private static Map<String, String> types(SetModel model) {
        Map<String, String> types = new LinkedHashMap<>();
        Names.claim(types, model.type());
        Names.claim(types, qualifiedName(model));
        if (model.hasAsync()) {
            for (String fixed : List.of(MACHINE, DURATION, CLASS, ILLEGAL_ARGUMENT)) {
                Names.claim(types, fixed);
            }
        }
        for (String fixed : List.of(TRANSITION_LISTENER, MACHINE_REGISTRY, STATE_MACHINES, OBJECTS, EXECUTOR, GENERATED,
                SUPPRESS_WARNINGS, OVERRIDE, ILLEGAL_STATE)) {
            Names.claim(types, fixed);
        }
        for (MachineAccessor accessor : model.machines()) {
            MachineModel machine = accessor.machine();
            Names.claim(types, machine.types().facade());
            Names.claim(types, MachineGenerator.qualifiedName(machine));
            Names.claim(types, machine.types().entityElement());
            Names.claim(types, machine.types().statesElement());
            Names.claim(types, machine.types().transitionsElement());
        }
        for (TypeElement dependency : model.dependencies()) {
            Names.claim(types, dependency);
        }
        return types;
    }
}
