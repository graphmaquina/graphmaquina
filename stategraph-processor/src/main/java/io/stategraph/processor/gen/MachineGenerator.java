package io.stategraph.processor.gen;

import io.stategraph.api.EdgeKind;
import io.stategraph.processor.model.Binding;
import io.stategraph.processor.model.CellModel;
import io.stategraph.processor.model.ForbidModel;
import io.stategraph.processor.model.MachineModel;
import io.stategraph.processor.model.OperationModel;
import io.stategraph.processor.model.RowModel;
import io.stategraph.processor.model.RuleKind;
import io.stategraph.processor.model.RuleModel;
import io.stategraph.processor.model.WatchModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/** Emits {@code <Interface>_}: the graph factory built with the SPI DSL, and the facade that implements the interface. */
public final class MachineGenerator {

    private static final String GRAPH_DEFINITION = "io.stategraph.api.GraphDefinition";
    private static final String MACHINE_KEY = "io.stategraph.api.MachineKey";
    private static final String OUTCOME = "io.stategraph.api.Outcome";
    private static final String STATE_MACHINE = "io.stategraph.api.StateMachine";
    private static final String WATCH_CONDITION = "io.stategraph.api.WatchCondition";
    private static final String STATE_GRAPH = "io.stategraph.dsl.StateGraph";
    private static final String DURATION = "java.time.Duration";
    private static final String OBJECTS = "java.util.Objects";
    private static final String OPTIONAL = "java.util.Optional";
    private static final String SET = "java.util.Set";
    private static final String COMPLETABLE_FUTURE = "java.util.concurrent.CompletableFuture";
    private static final String SUPPLIER = "java.util.function.Supplier";
    private static final String GENERATED = "javax.annotation.processing.Generated";

    /** The identifiers the companion declares itself; no generated parameter may shadow one of them. */
    private static final Set<String> RESERVED = Set.of("stateMachine", "call", "state");

    private MachineGenerator() { }

    public static String qualifiedName(MachineModel model) {
        return model.packageName() + "." + model.facadeName() + "_";
    }

    public static String generate(MachineModel model, BoundInvocation invocations) {
        Names names = new Names(model.packageName(), types(model));
        String e = names.of(model.types().entityElement().getQualifiedName().toString());
        String s = names.of(model.types().statesElement().getQualifiedName().toString());
        String facade = model.facadeName();
        String companion = facade + "_";
        String key = names.of(MACHINE_KEY);
        String outcome = names.of(OUTCOME);
        String objects = names.of(OBJECTS);
        String definition = names.of(GRAPH_DEFINITION);
        Map<String, String> parameters = parameterNames(model);
        String entity = entityParameter(model, parameters);
        SourceWriter out = new SourceWriter();
        out.line("package " + model.packageName() + ";");
        out.blank();
        for (String type : names.imports()) {
            out.line("import " + type + ";");
        }
        out.blank();
        out.line("/** Generated companion of {@link " + facade + "}: builds its graph and implements its facade. Do not edit. */");
        out.line("@" + names.of(GENERATED) + "(\"io.stategraph.processor.StateGraphProcessor\")");
        out.open("public final class " + companion + " implements " + facade);
        out.blank();
        out.line("public static final " + key + "<" + e + ", " + s + "> KEY = " + key + ".of(" + e + ".class, " + s + ".class);");
        out.blank();
        out.line("private final " + names.of(STATE_MACHINE) + "<" + e + ", " + s + "> stateMachine;");
        out.blank();
        out.open("public " + companion + "(" + names.of(STATE_MACHINE) + "<" + e + ", " + s + "> stateMachine)");
        out.line("this.stateMachine = " + objects + ".requireNonNull(stateMachine, \"stateMachine\");");
        out.close();
        out.blank();
        graph(model, names, parameters, invocations, out);
        for (OperationModel operation : model.operations()) {
            out.blank();
            out.line("@Override");
            out.open("public " + outcome + "<" + e + ", " + s + "> " + operation.name() + "(" + e + " " + entity + ")");
            out.line(objects + ".requireNonNull(" + entity + ", \"" + entity + "\");");
            out.line("return stateMachine.transitionTo(" + entity + ", " + s + "." + operation.cell().to() + ");");
            out.close();
        }
        out.blank();
        out.line("@Override");
        out.line("public " + definition + "<" + e + ", " + s + "> definition() { return stateMachine.definition(); }");
        out.blank();
        out.line("@Override");
        out.line("public " + outcome + "<" + e + ", " + s + "> reevaluate(" + e + " " + entity + ") { return stateMachine.reevaluate("
                + entity + "); }");
        out.blank();
        out.line("@Override");
        out.line("public " + names.of(SET) + "<" + s + "> allowedFrom(" + s + " state) { return stateMachine.allowedFrom(state); }");
        out.blank();
        out.line("@Override");
        out.line("public <T> " + names.of(COMPLETABLE_FUTURE) + "<T> async(" + names.of(SUPPLIER)
                + "<T> call) { return stateMachine.supplyAsync(call); }");
        out.close();
        return out.toString();
    }

    private static void graph(MachineModel model, Names names, Map<String, String> parameters, BoundInvocation invocations,
                              SourceWriter out) {
        String e = names.of(model.types().entityElement().getQualifiedName().toString());
        String s = names.of(model.types().statesElement().getQualifiedName().toString());
        String objects = names.of(OBJECTS);
        List<String> declarations = new ArrayList<>();
        for (TypeElement dependency : model.instanceDependencies()) {
            declarations.add(names.of(dependency.getQualifiedName().toString()) + " "
                    + parameters.get(dependency.getQualifiedName().toString()));
        }
        out.line("/** The definition, built from the annotations of {@link " + model.facadeName() + "}. One parameter per class with instance members. */");
        out.open("public static " + names.of(GRAPH_DEFINITION) + "<" + e + ", " + s + "> graph(" + String.join(", ", declarations) + ")");
        for (TypeElement dependency : model.instanceDependencies()) {
            String name = parameters.get(dependency.getQualifiedName().toString());
            out.line(objects + ".requireNonNull(" + name + ", \"" + name + "\");");
        }
        out.line("return " + names.of(STATE_GRAPH) + ".define(KEY)");
        out.indent().indent();
        out.line(".initial(" + s + "." + model.initial() + ")");
        for (String terminal : model.terminals()) {
            out.line(".terminal(" + s + "." + terminal + ")");
        }
        for (String state : model.cycleAllowed()) {
            out.line(".allowsCycle(" + s + "." + state + ")");
        }
        for (String from : model.types().stateNames()) {
            out.line(".from(" + s + "." + from + ")");
            out.indent();
            for (String to : model.types().stateNames()) {
                if (to.equals(from)) {
                    continue;
                }
                Optional<CellModel> cell = cellOf(model, from, to);
                Optional<ForbidModel> forbid = forbidOf(model, from, to);
                if (cell.isPresent()) {
                    emitCell(model, names, parameters, invocations, cell.get(), to, out);
                } else if (forbid.isPresent()) {
                    out.line(".to(" + s + "." + to + ").forbid(" + quote(forbid.get().reason()) + ")");
                }
            }
            out.line(".stay()");
            Optional<WatchModel> watch = watchOf(model, from);
            watch.ifPresent((WatchModel found) -> emitWatch(model, names, parameters, invocations, found, out));
            out.line(".forbidOthers(" + quote(rowReason(model, from)) + ")");
            out.outdent();
        }
        out.line(".build();");
        out.outdent().outdent();
        out.close();
    }

    private static void emitCell(MachineModel model, Names names, Map<String, String> parameters, BoundInvocation invocations,
                                 CellModel cell, String to, SourceWriter out) {
        String s = names.of(model.types().statesElement().getQualifiedName().toString());
        List<String> context = List.of(lambdaParameter("context", parameters));
        List<String> entity = List.of(entityParameter(model, parameters));
        StringBuilder line = new StringBuilder(".to(" + s + "." + to + ")");
        if (cell.kind() == EdgeKind.ACTION) {
            Binding action = cell.action().orElseThrow();
            line.append(action.async() ? ".asyncAction(" : ".action(").append(quote(cell.name())).append(", ")
                    .append(lambda(action, context, parameters, names, invocations)).append(')');
            cell.timeout().ifPresent((Duration timeout) -> line.append(".timeout(").append(names.of(DURATION))
                    .append(".parse(\"").append(timeout).append("\"))"));
        } else {
            line.append(".move()");
        }
        for (Binding guard : cell.guards()) {
            line.append(guard.async() ? ".asyncGuard(" : ".guard(").append(quote(guard.methodName())).append(", ")
                    .append(lambda(guard, context, parameters, names, invocations)).append(')');
        }
        cell.onFailure().ifPresent((String state) -> line.append(".onFailure(").append(s).append('.').append(state).append(')'));
        for (TypeMirror type : cell.failsOn()) {
            line.append(".failsOn(").append(type).append(".class)");
        }
        cell.afterCommit().ifPresent((Binding hook) -> line.append(".afterCommit(").append(quote(hook.methodName())).append(", ")
                .append(lambda(hook, entity, parameters, names, invocations)).append(')'));
        out.line(line.toString());
    }

    private static void emitWatch(MachineModel model, Names names, Map<String, String> parameters, BoundInvocation invocations,
                                  WatchModel watch, SourceWriter out) {
        String e = names.of(model.types().entityElement().getQualifiedName().toString());
        String s = names.of(model.types().statesElement().getQualifiedName().toString());
        String p = names.of(watch.peer().entityElement().getQualifiedName().toString());
        String q = names.of(watch.peer().statesElement().getQualifiedName().toString());
        String entity = entityParameter(model, parameters);
        String peer = lambdaParameter(decapitalize(watch.peer().entityName()), parameters);
        String owner = watch.ownerIsOptional()
                ? peer + "." + watch.owner().method() + "()"
                : names.of(OPTIONAL) + ".ofNullable(" + peer + "." + watch.owner().method() + "())";
        out.line(".watches(" + names.of(MACHINE_KEY) + ".of(" + p + ".class, " + q + ".class), (" + e + " " + entity + ") -> " + entity + "."
                + watch.peers().method() + "(), (" + p + " " + peer + ") -> " + owner + ")");
        out.indent();
        for (RuleModel rule : watch.rules()) {
            String condition = switch (rule.kind()) {
                case CONDITION -> quote(rule.condition().orElseThrow().methodName()) + ", "
                        + lambda(rule.condition().orElseThrow(), List.of(entity, lambdaParameter("peers", parameters),
                                lambdaParameter("view", parameters)), parameters, names, invocations);
                case ALL_IN -> names.of(WATCH_CONDITION) + ".allIn(" + q + "." + rule.argument() + ")";
                case ANY_IN -> names.of(WATCH_CONDITION) + ".anyIn(" + q + "." + rule.argument() + ")";
                case NONE_IN -> names.of(WATCH_CONDITION) + ".noneIn(" + q + "." + rule.argument() + ")";
            };
            out.line(".when(" + condition + ").goTo(" + s + "." + rule.goTo() + ")");
        }
        out.outdent();
    }

    private static Optional<CellModel> cellOf(MachineModel model, String from, String to) {
        for (CellModel cell : model.allCells()) {
            if (cell.from().contains(from) && cell.to().equals(to)) {
                return Optional.of(cell);
            }
        }
        return Optional.empty();
    }

    private static Optional<ForbidModel> forbidOf(MachineModel model, String from, String to) {
        for (ForbidModel forbid : model.forbids()) {
            if (forbid.from().equals(from) && forbid.to().equals(to)) {
                return Optional.of(forbid);
            }
        }
        return Optional.empty();
    }

    private static Optional<WatchModel> watchOf(MachineModel model, String state) {
        for (WatchModel watch : model.watches()) {
            if (watch.state().equals(state)) {
                return Optional.of(watch);
            }
        }
        return Optional.empty();
    }

    private static String rowReason(MachineModel model, String state) {
        for (RowModel row : model.rows()) {
            if (row.state().equals(state)) {
                return row.otherwise();
            }
        }
        return state + " is final";
    }

    /** Every type the companion names, in the order it claims them: its own first, then the fixed ones, then the model's. */
    private static Set<String> types(MachineModel model) {
        Set<String> types = new LinkedHashSet<>();
        types.add(model.types().facade().getQualifiedName().toString());
        types.add(qualifiedName(model));
        types.add(GRAPH_DEFINITION);
        types.add(MACHINE_KEY);
        types.add(OUTCOME);
        types.add(STATE_MACHINE);
        types.add(STATE_GRAPH);
        types.add(OBJECTS);
        types.add(SET);
        types.add(COMPLETABLE_FUTURE);
        types.add(SUPPLIER);
        types.add(GENERATED);
        boolean anyTimeout = false;
        boolean anyNullableOwner = false;
        boolean anyBuiltIn = false;
        for (CellModel cell : model.allCells()) {
            anyTimeout |= cell.timeout().isPresent();
        }
        for (WatchModel watch : model.watches()) {
            anyNullableOwner |= !watch.ownerIsOptional();
            for (RuleModel rule : watch.rules()) {
                anyBuiltIn |= rule.kind() != RuleKind.CONDITION;
            }
            types.add(watch.peer().entityElement().getQualifiedName().toString());
            types.add(watch.peer().statesElement().getQualifiedName().toString());
        }
        if (anyTimeout) {
            types.add(DURATION);
        }
        if (anyNullableOwner) {
            types.add(OPTIONAL);
        }
        if (anyBuiltIn) {
            types.add(WATCH_CONDITION);
        }
        types.add(model.types().entityElement().getQualifiedName().toString());
        types.add(model.types().statesElement().getQualifiedName().toString());
        for (TypeElement dependency : model.instanceDependencies()) {
            types.add(dependency.getQualifiedName().toString());
        }
        for (CellModel cell : model.allCells()) {
            for (Binding binding : cell.bindings()) {
                if (binding.isStatic()) {
                    types.add(binding.owner().getQualifiedName().toString());
                }
            }
        }
        for (WatchModel watch : model.watches()) {
            for (Binding binding : watch.bindings()) {
                if (binding.isStatic()) {
                    types.add(binding.owner().getQualifiedName().toString());
                }
            }
        }
        return types;
    }

    /** Qualified name to parameter name, one entry per instance dependency; two classes sharing a simple name get a {@code 2} suffix. */
    static Map<String, String> parameterNames(MachineModel model) {
        return parameterNames(model.instanceDependencies(), RESERVED);
    }

    /** The same convention over any list of dependencies, so a set names them as its machines do; {@code reserved} is what the file already declares. */
    static Map<String, String> parameterNames(List<TypeElement> dependencies, Set<String> reserved) {
        Map<String, String> names = new LinkedHashMap<>();
        Set<String> taken = new LinkedHashSet<>(reserved);
        for (TypeElement dependency : dependencies) {
            String base = decapitalize(dependency.getSimpleName().toString());
            String name = base;
            int suffix = 1;
            while (!taken.add(name)) {
                suffix++;
                name = base + suffix;
            }
            names.put(dependency.getQualifiedName().toString(), name);
        }
        return names;
    }

    /**
     * The lambda that hands the arguments of the SPI's own functional interface to the exact method the binding
     * selected: {@code owner} is the class for a static member and the injected parameter for an instance one.
     * A lambda, rather than a method reference, because only a call can name the parameter types that keep the
     * selected overload when the host declares another one that would also fit.
     */
    static String lambda(Binding binding, List<String> arguments, Map<String, String> parameters, Names names,
                         BoundInvocation invocations) {
        String receiver = binding.isStatic()
                ? names.of(binding.owner().getQualifiedName().toString())
                : parameters.get(binding.owner().getQualifiedName().toString());
        return "(" + String.join(", ", arguments) + ") -> " + invocations.emit(binding, receiver, arguments);
    }

    /** The decapitalised entity name, suffixed with {@code Entity} when a dependency parameter or the companion itself already claims it. */
    static String entityParameter(MachineModel model, Map<String, String> parameters) {
        String name = decapitalize(model.types().entityName());
        return parameters.containsValue(name) || RESERVED.contains(name) ? name + "Entity" : name;
    }

    /** A lambda parameter of {@code graph(...)} may not shadow one of its own parameters, nor a reserved name. */
    private static String lambdaParameter(String name, Map<String, String> parameters) {
        String unique = name;
        int suffix = 1;
        while (parameters.containsValue(unique) || RESERVED.contains(unique)) {
            suffix++;
            unique = name + suffix;
        }
        return unique;
    }

    static String decapitalize(String name) {
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
