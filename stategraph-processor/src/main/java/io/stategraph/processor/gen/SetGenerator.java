package io.stategraph.processor.gen;

import io.stategraph.processor.model.MachineAccessor;
import io.stategraph.processor.model.MachineModel;
import io.stategraph.processor.model.SetModel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.TypeElement;

/** Emits {@code <Set>_}: a builder that assembles the registry from the machines' companions and hands out the facades. */
public final class SetGenerator {

    private SetGenerator() { }

    public static String qualifiedName(SetModel model) {
        return model.packageName() + "." + model.setName() + "_";
    }

    public static String generate(SetModel model) {
        String set = model.setName();
        String companion = set + "_";
        boolean async = model.hasAsync();
        Map<String, String> names = MachineGenerator.parameterNames(model.dependencies());
        SourceWriter out = new SourceWriter();
        out.line("package " + model.packageName() + ";");
        out.blank();
        for (String type : imports(model)) {
            out.line("import " + type + ";");
        }
        out.blank();
        out.line("/** Generated set of {@link " + set + "}: builds the registry once and hands out the facades. Do not edit. */");
        out.line("@Generated(\"io.stategraph.processor.StateGraphProcessor\")");
        out.open("public final class " + companion + " implements " + set);
        out.blank();
        if (async) {
            out.line("private static final Set<Class<?>> ASYNC_MACHINES = Set.of(" + asyncMachines(model) + ");");
            out.blank();
        }
        out.line("private final MachineRegistry registry;");
        for (MachineAccessor accessor : model.machines()) {
            out.line("private final " + accessor.machine().facadeName() + " " + accessor.method() + ";");
        }
        out.blank();
        out.open("private " + companion + "(MachineRegistry registry)");
        out.line("this.registry = registry;");
        for (MachineAccessor accessor : model.machines()) {
            String facade = accessor.machine().facadeName();
            out.line("this." + accessor.method() + " = new " + facade + "_(registry.of(" + facade + "_.KEY));");
        }
        out.close();
        out.blank();
        List<String> parameters = new ArrayList<>();
        List<String> arguments = new ArrayList<>();
        if (async) {
            parameters.add("Duration asyncTimeout");
            arguments.add("asyncTimeout");
        }
        for (TypeElement dependency : model.dependencies()) {
            String name = names.get(dependency.getQualifiedName().toString());
            parameters.add(dependency.getSimpleName() + " " + name);
            arguments.add(name);
        }
        out.line("/** Every class with instance members the machines use" + (async ? ", after the timeout shared by the asynchronous machines" : "") + ". */");
        out.open("public static Builder builder(" + String.join(", ", parameters) + ")");
        out.line("return new Builder(" + String.join(", ", arguments) + ");");
        out.close();
        out.blank();
        builder(model, names, out, parameters, async);
        for (MachineAccessor accessor : model.machines()) {
            out.blank();
            out.line("@Override");
            out.line("public " + accessor.machine().facadeName() + " " + accessor.method() + "() { return " + accessor.method() + "; }");
        }
        out.blank();
        out.line("@Override");
        out.line("public void close() { registry.close(); }");
        out.close();
        return out.toString();
    }

    private static void builder(SetModel model, Map<String, String> names, SourceWriter out, List<String> parameters, boolean async) {
        String set = model.setName();
        out.line("/** Single-use. Optional settings mirror MachineRegistry.Builder and StateMachines.Builder. */");
        out.open("public static final class Builder");
        out.blank();
        for (String parameter : parameters) {
            out.line("private final " + parameter + ";");
        }
        if (async) {
            out.line("private final Map<Class<?>, Duration> timeouts = new HashMap<>();");
        }
        out.line("private Optional<Executor> executor = Optional.empty();");
        out.line("private OptionalInt maxCascadeDepth = OptionalInt.empty();");
        out.line("private OptionalInt maxWatchChain = OptionalInt.empty();");
        out.line("private Optional<TransitionListener> listener = Optional.empty();");
        out.blank();
        out.open("private Builder(" + String.join(", ", parameters) + ")");
        for (String parameter : parameters) {
            String name = parameter.substring(parameter.indexOf(' ') + 1);
            out.line("this." + name + " = Objects.requireNonNull(" + name + ", " + MachineGenerator.quote(name) + ");");
        }
        out.close();
        out.blank();
        if (async) {
            out.line("/** Overrides the shared timeout for one asynchronous machine. */");
            out.open("public Builder asyncTimeout(Class<? extends Machine<?, ?>> machine, Duration timeout)");
            out.line("Objects.requireNonNull(machine, \"machine\");");
            out.line("Objects.requireNonNull(timeout, \"timeout\");");
            out.open("if (!ASYNC_MACHINES.contains(machine))");
            out.line("throw new IllegalArgumentException(machine.getSimpleName() + "
                    + MachineGenerator.quote(" is not an asynchronous machine of " + set) + ");");
            out.close();
            out.line("timeouts.put(machine, timeout);");
            out.line("return this;");
            out.close();
            out.blank();
        }
        out.open("public Builder asyncExecutor(Executor executor)");
        out.line("this.executor = Optional.of(Objects.requireNonNull(executor, \"executor\"));");
        out.line("return this;");
        out.close();
        out.blank();
        out.open("public Builder maxCascadeDepth(int depth)");
        out.line("this.maxCascadeDepth = OptionalInt.of(depth);");
        out.line("return this;");
        out.close();
        out.blank();
        out.open("public Builder maxWatchChain(int chain)");
        out.line("this.maxWatchChain = OptionalInt.of(chain);");
        out.line("return this;");
        out.close();
        out.blank();
        out.open("public Builder listener(TransitionListener listener)");
        out.line("this.listener = Optional.of(Objects.requireNonNull(listener, \"listener\"));");
        out.line("return this;");
        out.close();
        out.blank();
        out.open("public " + set + " build()");
        out.line("MachineRegistry.Builder registry = MachineRegistry.builder();");
        for (MachineAccessor accessor : model.machines()) {
            MachineModel machine = accessor.machine();
            String e = machine.types().entityName();
            String s = machine.types().statesName();
            String facade = machine.facadeName();
            String variable = accessor.method() + "Builder";
            List<String> arguments = new ArrayList<>();
            for (TypeElement dependency : machine.instanceDependencies()) {
                arguments.add(names.get(dependency.getQualifiedName().toString()));
            }
            out.line("StateMachines.Builder<" + e + ", " + s + "> " + variable + " = StateMachines.from(" + facade + "_.graph("
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
        out.line("return new " + set + "_(registry.build());");
        out.close();
        out.close();
    }

    private static String asyncMachines(SetModel model) {
        List<String> names = new ArrayList<>();
        for (MachineAccessor accessor : model.machines()) {
            if (accessor.machine().hasAsync()) {
                names.add(accessor.machine().facadeName() + ".class");
            }
        }
        return String.join(", ", names);
    }

    private static Set<String> imports(SetModel model) {
        Set<String> imports = new LinkedHashSet<>();
        if (model.hasAsync()) {
            imports.add("io.stategraph.api.Machine");
            imports.add("java.time.Duration");
            imports.add("java.util.HashMap");
            imports.add("java.util.Map");
            imports.add("java.util.Set");
        }
        imports.add("io.stategraph.api.TransitionListener");
        imports.add("io.stategraph.runtime.MachineRegistry");
        imports.add("io.stategraph.runtime.StateMachines");
        imports.add("java.util.Objects");
        imports.add("java.util.Optional");
        imports.add("java.util.OptionalInt");
        imports.add("java.util.concurrent.Executor");
        imports.add("javax.annotation.processing.Generated");
        for (MachineAccessor accessor : model.machines()) {
            MachineModel machine = accessor.machine();
            imports.add(machine.types().facade().getQualifiedName().toString());
            imports.add(machine.packageName() + "." + machine.facadeName() + "_");
            imports.add(machine.types().entityElement().getQualifiedName().toString());
            imports.add(machine.types().statesElement().getQualifiedName().toString());
        }
        for (TypeElement dependency : model.dependencies()) {
            imports.add(dependency.getQualifiedName().toString());
        }
        Set<String> needed = new LinkedHashSet<>();
        for (String type : imports) {
            int dot = type.lastIndexOf('.');
            String owner = dot < 0 ? "" : type.substring(0, dot);
            if (!owner.equals(model.packageName()) && !owner.equals("java.lang")) {
                needed.add(type);
            }
        }
        return needed;
    }
}
