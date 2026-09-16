package io.github.graphmaquina.processor.gen;

import io.github.graphmaquina.codegen.emit.SymbolNames;
import io.github.graphmaquina.processor.model.MachineAccessor;
import io.github.graphmaquina.processor.model.MachineModel;
import io.github.graphmaquina.processor.model.SetModel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.TypeElement;

/** Emits {@code <Set>Runtime}: a builder that assembles the registry from the machines' companions and hands out the facades. */
public final class SetGenerator {

    private static final String MACHINE = "io.github.graphmaquina.api.Machine";
    private static final String TRANSITION_LISTENER = "io.github.graphmaquina.api.TransitionListener";
    private static final String MACHINE_REGISTRY = "io.github.graphmaquina.runtime.MachineRegistry";
    private static final String STATE_MACHINES = "io.github.graphmaquina.runtime.StateMachines";
    private static final String DURATION = "java.time.Duration";
    private static final String HASH_MAP = "java.util.HashMap";
    private static final String MAP = "java.util.Map";
    private static final String OBJECTS = "java.util.Objects";
    private static final String OPTIONAL = "java.util.Optional";
    private static final String OPTIONAL_INT = "java.util.OptionalInt";
    private static final String SET = "java.util.Set";
    private static final String EXECUTOR = "java.util.concurrent.Executor";
    private static final String GENERATED = "javax.annotation.processing.Generated";

    private SetGenerator() { }

    public static String qualifiedName(SetModel model) {
        return SymbolNames.qualified(model.packageName(), SymbolNames.runtimeName(model.setName()));
    }

    public static String generate(SetModel model) {
        String set = model.setName();
        String companion = SymbolNames.runtimeName(set);
        boolean async = model.hasAsync();
        Names names = new Names(model.packageName(), types(model));
        Map<String, String> parameterNames = MachineGenerator.parameterNames(model.dependencies(), reserved(model));
        SourceWriter out = new SourceWriter();
        out.line("package " + model.packageName() + ";");
        out.blank();
        for (String type : names.imports()) {
            out.line("import " + type + ";");
        }
        out.blank();
        out.line("/** Generated set of {@link " + set + "}: builds the registry once and hands out the facades. Do not edit. */");
        out.line("@" + names.of(GENERATED) + "(\"io.github.graphmaquina.processor.StateGraphProcessor\")");
        out.open("public final class " + companion + " implements " + set);
        out.blank();
        if (async) {
            out.line("private static final " + names.of(SET) + "<Class<?>> ASYNC_MACHINES = " + names.of(SET) + ".of("
                    + asyncMachines(model, names) + ");");
            out.blank();
        }
        out.line("private final " + names.of(MACHINE_REGISTRY) + " machineRegistry;");
        for (MachineAccessor accessor : model.machines()) {
            out.line("private final " + facadeName(accessor, names) + " " + accessor.method() + ";");
        }
        out.blank();
        out.open("private " + companion + "(" + names.of(MACHINE_REGISTRY) + " machineRegistry)");
        out.line("this.machineRegistry = machineRegistry;");
        for (MachineAccessor accessor : model.machines()) {
            String companionOf = companionName(accessor, names);
            out.line("this." + accessor.method() + " = new " + companionOf + "(machineRegistry.of(" + companionOf + ".KEY));");
        }
        out.close();
        out.blank();
        List<String> parameters = new ArrayList<>();
        List<String> arguments = new ArrayList<>();
        if (async) {
            parameters.add(names.of(DURATION) + " asyncTimeout");
            arguments.add("asyncTimeout");
        }
        for (TypeElement dependency : model.dependencies()) {
            String name = parameterNames.get(dependency.getQualifiedName().toString());
            parameters.add(names.of(dependency.getQualifiedName().toString()) + " " + name);
            arguments.add(name);
        }
        out.line("/** Every class with instance members the machines use" + (async ? ", after the timeout shared by the asynchronous machines" : "") + ". */");
        out.open("public static Builder builder(" + String.join(", ", parameters) + ")");
        out.line("return new Builder(" + String.join(", ", arguments) + ");");
        out.close();
        out.blank();
        builder(model, names, parameterNames, out, parameters, async);
        for (MachineAccessor accessor : model.machines()) {
            out.blank();
            out.line("@Override");
            out.line("public " + facadeName(accessor, names) + " " + accessor.method() + "() { return " + accessor.method() + "; }");
        }
        out.blank();
        out.line("@Override");
        out.line("public void close() { machineRegistry.close(); }");
        out.close();
        return out.toString();
    }

    private static void builder(SetModel model, Names names, Map<String, String> parameterNames, SourceWriter out,
                                List<String> parameters, boolean async) {
        String set = model.setName();
        String objects = names.of(OBJECTS);
        out.line("/** Optional settings mirror MachineRegistry.Builder and StateMachines.Builder. */");
        out.open("public static final class Builder");
        out.blank();
        for (String parameter : parameters) {
            out.line("private final " + parameter + ";");
        }
        if (async) {
            out.line("private final " + names.of(MAP) + "<Class<?>, " + names.of(DURATION) + "> timeouts = new "
                    + names.of(HASH_MAP) + "<>();");
        }
        out.line("private " + names.of(OPTIONAL) + "<" + names.of(EXECUTOR) + "> executor = " + names.of(OPTIONAL) + ".empty();");
        out.line("private " + names.of(OPTIONAL_INT) + " maxCascadeDepth = " + names.of(OPTIONAL_INT) + ".empty();");
        out.line("private " + names.of(OPTIONAL_INT) + " maxWatchChain = " + names.of(OPTIONAL_INT) + ".empty();");
        out.line("private " + names.of(OPTIONAL) + "<" + names.of(TRANSITION_LISTENER) + "> listener = " + names.of(OPTIONAL) + ".empty();");
        out.blank();
        out.open("private Builder(" + String.join(", ", parameters) + ")");
        for (String parameter : parameters) {
            String name = parameter.substring(parameter.indexOf(' ') + 1);
            out.line("this." + name + " = " + objects + ".requireNonNull(" + name + ", " + MachineGenerator.quote(name) + ");");
        }
        out.close();
        out.blank();
        if (async) {
            out.line("/** Overrides the shared timeout for one asynchronous machine. */");
            out.open("public Builder asyncTimeout(Class<? extends " + names.of(MACHINE) + "<?, ?, ?>> machine, "
                    + names.of(DURATION) + " timeout)");
            out.line(objects + ".requireNonNull(machine, \"machine\");");
            out.line(objects + ".requireNonNull(timeout, \"timeout\");");
            out.open("if (!ASYNC_MACHINES.contains(machine))");
            out.line("throw new IllegalArgumentException(machine.getSimpleName() + "
                    + MachineGenerator.quote(" is not an asynchronous machine of " + set) + ");");
            out.close();
            out.line("timeouts.put(machine, timeout);");
            out.line("return this;");
            out.close();
            out.blank();
        }
        out.open("public Builder asyncExecutor(" + names.of(EXECUTOR) + " executor)");
        out.line("this.executor = " + names.of(OPTIONAL) + ".of(" + objects + ".requireNonNull(executor, \"executor\"));");
        out.line("return this;");
        out.close();
        out.blank();
        out.open("public Builder maxCascadeDepth(int depth)");
        out.line("this.maxCascadeDepth = " + names.of(OPTIONAL_INT) + ".of(depth);");
        out.line("return this;");
        out.close();
        out.blank();
        out.open("public Builder maxWatchChain(int chain)");
        out.line("this.maxWatchChain = " + names.of(OPTIONAL_INT) + ".of(chain);");
        out.line("return this;");
        out.close();
        out.blank();
        out.open("public Builder listener(" + names.of(TRANSITION_LISTENER) + " listener)");
        out.line("this.listener = " + names.of(OPTIONAL) + ".of(" + objects + ".requireNonNull(listener, \"listener\"));");
        out.line("return this;");
        out.close();
        out.blank();
        out.open("public " + set + " build()");
        out.line(names.of(MACHINE_REGISTRY) + ".Builder registry = " + names.of(MACHINE_REGISTRY) + ".builder();");
        for (MachineAccessor accessor : model.machines()) {
            MachineModel machine = accessor.machine();
            String e = names.of(machine.types().entityElement().getQualifiedName().toString());
            String s = names.of(machine.types().statesElement().getQualifiedName().toString());
            String t = names.of(machine.types().transitionsElement().getQualifiedName().toString());
            String facade = facadeName(accessor, names);
            String variable = accessor.method() + "Builder";
            List<String> arguments = new ArrayList<>();
            for (TypeElement dependency : machine.instanceDependencies()) {
                arguments.add(parameterNames.get(dependency.getQualifiedName().toString()));
            }
            out.line(names.of(STATE_MACHINES) + ".Builder<" + e + ", " + s + ", " + t + "> " + variable + " = "
                    + names.of(STATE_MACHINES) + ".from(" + companionName(accessor, names) + ".graph("
                    + String.join(", ", arguments) + "))");
            out.indent().indent();
            out.line(".state(" + e + "::" + machine.stateGetter().method() + ", " + e + "::" + machine.stateSetter().method() + ")");
            out.line(".identity(" + e + "::" + machine.idGetter().method() + ")");
            out.line(".as(" + facade + ".class);");
            out.outdent().outdent();
            if (machine.hasAsync()) {
                out.line(variable + ".asyncTimeout(timeouts.getOrDefault(" + facade + ".class, asyncTimeout));");
            }
            out.line("executor.ifPresent(" + variable + "::asyncExecutor);");
            out.line("registry.add(" + variable + ");");
        }
        out.line("maxCascadeDepth.ifPresent(registry::maxCascadeDepth);");
        out.line("maxWatchChain.ifPresent(registry::maxWatchChain);");
        out.line("listener.ifPresent(registry::listener);");
        out.line("return new " + SymbolNames.runtimeName(set) + "(registry.build());");
        out.close();
        out.close();
    }

    /** The identifiers the set companion declares itself: its fields, the builder's fields and the locals of {@code build()}. */
    private static Set<String> reserved(SetModel model) {
        Set<String> reserved = new LinkedHashSet<>();
        reserved.add("machineRegistry");
        reserved.add("registry");
        reserved.add("asyncTimeout");
        reserved.add("timeouts");
        reserved.add("executor");
        reserved.add("maxCascadeDepth");
        reserved.add("maxWatchChain");
        reserved.add("listener");
        for (MachineAccessor accessor : model.machines()) {
            reserved.add(accessor.method());
            reserved.add(accessor.method() + "Builder");
        }
        return reserved;
    }

    /** How this file writes the machine's facade interface. */
    private static String facadeName(MachineAccessor accessor, Names names) {
        return names.of(accessor.machine().types().facade().getQualifiedName().toString());
    }

    /** How this file writes the machine's generated companion. */
    private static String companionName(MachineAccessor accessor, Names names) {
        return names.of(MachineGenerator.qualifiedName(accessor.machine()));
    }

    private static String asyncMachines(SetModel model, Names names) {
        List<String> literals = new ArrayList<>();
        for (MachineAccessor accessor : model.machines()) {
            if (accessor.machine().hasAsync()) {
                literals.add(facadeName(accessor, names) + ".class");
            }
        }
        return String.join(", ", literals);
    }

    /** Every type the set companion names, in the order it claims them: its own first, then the fixed ones, then the machines'. */
    private static Set<String> types(SetModel model) {
        Set<String> types = new LinkedHashSet<>();
        types.add(model.type().getQualifiedName().toString());
        types.add(qualifiedName(model));
        if (model.hasAsync()) {
            types.add(MACHINE);
            types.add(DURATION);
            types.add(HASH_MAP);
            types.add(MAP);
            types.add(SET);
        }
        types.add(TRANSITION_LISTENER);
        types.add(MACHINE_REGISTRY);
        types.add(STATE_MACHINES);
        types.add(OBJECTS);
        types.add(OPTIONAL);
        types.add(OPTIONAL_INT);
        types.add(EXECUTOR);
        types.add(GENERATED);
        for (MachineAccessor accessor : model.machines()) {
            MachineModel machine = accessor.machine();
            types.add(machine.types().facade().getQualifiedName().toString());
            types.add(MachineGenerator.qualifiedName(machine));
            types.add(machine.types().entityElement().getQualifiedName().toString());
            types.add(machine.types().statesElement().getQualifiedName().toString());
            types.add(machine.types().transitionsElement().getQualifiedName().toString());
        }
        for (TypeElement dependency : model.dependencies()) {
            types.add(dependency.getQualifiedName().toString());
        }
        return types;
    }
}
