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

    private MachineGenerator() { }

    public static String qualifiedName(MachineModel model) {
        return model.packageName() + "." + model.facadeName() + "_";
    }

    public static String generate(MachineModel model) {
        String e = model.types().entityName();
        String s = model.types().statesName();
        String facade = model.facadeName();
        String companion = facade + "_";
        Map<String, String> parameters = parameterNames(model);
        String entity = entityParameter(model, parameters);
        SourceWriter out = new SourceWriter();
        out.line("package " + model.packageName() + ";");
        out.blank();
        for (String type : imports(model)) {
            out.line("import " + type + ";");
        }
        out.blank();
        out.line("/** Generated companion of {@link " + facade + "}: builds its graph and implements its facade. Do not edit. */");
        out.line("@Generated(\"io.stategraph.processor.StateGraphProcessor\")");
        out.open("public final class " + companion + " implements " + facade);
        out.blank();
        out.line("public static final MachineKey<" + e + ", " + s + "> KEY = MachineKey.of(" + e + ".class, " + s + ".class);");
        out.blank();
        out.line("private final StateMachine<" + e + ", " + s + "> machine;");
        out.blank();
        out.open("public " + companion + "(StateMachine<" + e + ", " + s + "> machine)");
        out.line("this.machine = Objects.requireNonNull(machine, \"machine\");");
        out.close();
        out.blank();
        graph(model, parameters, out);
        for (OperationModel operation : model.operations()) {
            out.blank();
            out.line("@Override");
            out.open("public Outcome<" + e + ", " + s + "> " + operation.name() + "(" + e + " " + entity + ")");
            out.line("Objects.requireNonNull(" + entity + ", \"" + entity + "\");");
            out.line("return machine.transitionTo(" + entity + ", " + s + "." + operation.cell().to() + ");");
            out.close();
        }
        out.blank();
        out.line("@Override");
        out.line("public GraphDefinition<" + e + ", " + s + "> definition() { return machine.definition(); }");
        out.blank();
        out.line("@Override");
        out.line("public Outcome<" + e + ", " + s + "> reevaluate(" + e + " " + entity + ") { return machine.reevaluate("
                + entity + "); }");
        out.blank();
        out.line("@Override");
        out.line("public Set<" + s + "> allowedFrom(" + s + " state) { return machine.allowedFrom(state); }");
        out.blank();
        out.line("@Override");
        out.line("public <T> CompletableFuture<T> async(Supplier<T> call) { return machine.supplyAsync(call); }");
        out.close();
        return out.toString();
    }

    private static void graph(MachineModel model, Map<String, String> parameters, SourceWriter out) {
        String e = model.types().entityName();
        String s = model.types().statesName();
        List<String> declarations = new ArrayList<>();
        for (TypeElement dependency : model.instanceDependencies()) {
            declarations.add(dependency.getSimpleName() + " " + parameters.get(dependency.getQualifiedName().toString()));
        }
        out.line("/** The definition, built from the annotations of {@link " + model.facadeName() + "}. One parameter per class with instance members. */");
        out.open("public static GraphDefinition<" + e + ", " + s + "> graph(" + String.join(", ", declarations) + ")");
        for (TypeElement dependency : model.instanceDependencies()) {
            String name = parameters.get(dependency.getQualifiedName().toString());
            out.line("Objects.requireNonNull(" + name + ", \"" + name + "\");");
        }
        out.line("return StateGraph.define(KEY)");
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
                    emitCell(model, parameters, cell.get(), to, out);
                } else if (forbid.isPresent()) {
                    out.line(".to(" + s + "." + to + ").forbid(" + quote(forbid.get().reason()) + ")");
                }
            }
            out.line(".stay()");
            Optional<WatchModel> watch = watchOf(model, from);
            watch.ifPresent((WatchModel found) -> emitWatch(model, parameters, found, out));
            out.line(".forbidOthers(" + quote(rowReason(model, from)) + ")");
            out.outdent();
        }
        out.line(".build();");
        out.outdent().outdent();
        out.close();
    }

    private static void emitCell(MachineModel model, Map<String, String> parameters, CellModel cell, String to, SourceWriter out) {
        String s = model.types().statesName();
        StringBuilder line = new StringBuilder(".to(" + s + "." + to + ")");
        if (cell.kind() == EdgeKind.ACTION) {
            Binding action = cell.action().orElseThrow();
            line.append(action.async() ? ".asyncAction(" : ".action(").append(quote(cell.name())).append(", ")
                    .append(reference(action, parameters)).append(')');
            cell.timeout().ifPresent((Duration timeout) -> line.append(".timeout(Duration.parse(\"").append(timeout).append("\"))"));
        } else {
            line.append(".move()");
        }
        for (Binding guard : cell.guards()) {
            line.append(guard.async() ? ".asyncGuard(" : ".guard(").append(quote(guard.methodName())).append(", ")
                    .append(reference(guard, parameters)).append(')');
        }
        cell.onFailure().ifPresent((String state) -> line.append(".onFailure(").append(s).append('.').append(state).append(')'));
        for (TypeMirror type : cell.failsOn()) {
            line.append(".failsOn(").append(type).append(".class)");
        }
        cell.afterCommit().ifPresent((Binding hook) -> line.append(".afterCommit(").append(quote(hook.methodName())).append(", ")
                .append(reference(hook, parameters)).append(')'));
        out.line(line.toString());
    }

    private static void emitWatch(MachineModel model, Map<String, String> parameters, WatchModel watch, SourceWriter out) {
        String e = model.types().entityName();
        String s = model.types().statesName();
        String p = watch.peer().entityName();
        String q = watch.peer().statesName();
        String entity = entityParameter(model, parameters);
        String peer = lambdaParameter(decapitalize(p), parameters);
        String owner = watch.ownerIsOptional()
                ? peer + "." + watch.owner().method() + "()"
                : "Optional.ofNullable(" + peer + "." + watch.owner().method() + "())";
        out.line(".watches(MachineKey.of(" + p + ".class, " + q + ".class), (" + e + " " + entity + ") -> " + entity + "." + watch.peers().method()
                + "(), (" + p + " " + peer + ") -> " + owner + ")");
        out.indent();
        for (RuleModel rule : watch.rules()) {
            String condition = switch (rule.kind()) {
                case CONDITION -> quote(rule.condition().orElseThrow().methodName()) + ", " + reference(rule.condition().orElseThrow(), parameters);
                case ALL_IN -> "WatchCondition.allIn(" + q + "." + rule.argument() + ")";
                case ANY_IN -> "WatchCondition.anyIn(" + q + "." + rule.argument() + ")";
                case NONE_IN -> "WatchCondition.noneIn(" + q + "." + rule.argument() + ")";
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

    private static Set<String> imports(MachineModel model) {
        Set<String> imports = new LinkedHashSet<>();
        imports.add("io.stategraph.api.GraphDefinition");
        imports.add("io.stategraph.api.MachineKey");
        imports.add("io.stategraph.api.Outcome");
        imports.add("io.stategraph.api.StateMachine");
        imports.add("io.stategraph.dsl.StateGraph");
        imports.add("java.util.Objects");
        imports.add("java.util.Set");
        imports.add("java.util.concurrent.CompletableFuture");
        imports.add("java.util.function.Supplier");
        imports.add("javax.annotation.processing.Generated");
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
            imports.add(watch.peer().entityElement().getQualifiedName().toString());
            imports.add(watch.peer().statesElement().getQualifiedName().toString());
        }
        if (anyTimeout) {
            imports.add("java.time.Duration");
        }
        if (anyNullableOwner) {
            imports.add("java.util.Optional");
        }
        if (anyBuiltIn) {
            imports.add("io.stategraph.api.WatchCondition");
        }
        imports.add(model.types().entityElement().getQualifiedName().toString());
        imports.add(model.types().statesElement().getQualifiedName().toString());
        for (TypeElement dependency : model.instanceDependencies()) {
            imports.add(dependency.getQualifiedName().toString());
        }
        for (CellModel cell : model.allCells()) {
            for (Binding binding : cell.bindings()) {
                if (binding.isStatic()) {
                    imports.add(binding.owner().getQualifiedName().toString());
                }
            }
        }
        for (WatchModel watch : model.watches()) {
            for (Binding binding : watch.bindings()) {
                if (binding.isStatic()) {
                    imports.add(binding.owner().getQualifiedName().toString());
                }
            }
        }
        Set<String> needed = new LinkedHashSet<>();
        for (String type : imports) {
            if (!samePackageOrJavaLang(type, model.packageName())) {
                needed.add(type);
            }
        }
        return needed;
    }

    private static boolean samePackageOrJavaLang(String qualifiedName, String packageName) {
        int dot = qualifiedName.lastIndexOf('.');
        String owner = dot < 0 ? "" : qualifiedName.substring(0, dot);
        return owner.equals(packageName) || owner.equals("java.lang");
    }

    /** Qualified name to parameter name, one entry per instance dependency; two classes sharing a simple name get a {@code 2} suffix. */
    static Map<String, String> parameterNames(MachineModel model) {
        Map<String, String> names = new LinkedHashMap<>();
        Set<String> taken = new LinkedHashSet<>();
        for (TypeElement dependency : model.instanceDependencies()) {
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

    /** {@code owner::method} for static members, {@code parameter::method} for instance members. */
    static String reference(Binding binding, Map<String, String> parameters) {
        if (binding.isStatic()) {
            return binding.ownerName() + "::" + binding.methodName();
        }
        return parameters.get(binding.owner().getQualifiedName().toString()) + "::" + binding.methodName();
    }

    /** The decapitalised entity name, suffixed with {@code Entity} when a dependency parameter already claims it. */
    static String entityParameter(MachineModel model, Map<String, String> parameters) {
        String name = decapitalize(model.types().entityName());
        return parameters.containsValue(name) ? name + "Entity" : name;
    }

    /** A lambda parameter of {@code graph(...)} may not shadow one of its own parameters. */
    private static String lambdaParameter(String name, Map<String, String> parameters) {
        return parameters.containsValue(name) ? name + "Peer" : name;
    }

    static String decapitalize(String name) {
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
