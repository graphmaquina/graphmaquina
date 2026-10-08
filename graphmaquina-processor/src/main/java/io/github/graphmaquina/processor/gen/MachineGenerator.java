package io.github.graphmaquina.processor.gen;

import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.codegen.emit.SymbolNames;
import io.github.graphmaquina.processor.model.Binding;
import io.github.graphmaquina.processor.model.MachineModel;
import io.github.graphmaquina.processor.model.OperationModel;
import io.github.graphmaquina.processor.model.RecoveryModel;
import io.github.graphmaquina.processor.model.RejectionModel;
import io.github.graphmaquina.processor.model.RuleKind;
import io.github.graphmaquina.processor.model.RuleModel;
import io.github.graphmaquina.processor.model.StatePolicyModel;
import io.github.graphmaquina.processor.model.TransitionModel;
import io.github.graphmaquina.processor.model.WatchModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;

/**
 * Emits {@code <Interface>Runtime}, whose name {@link SymbolNames#runtimeName} derives from the interface's simple
 * name normalized to PascalCase: the graph factory built with the typed graph DSL, and the facade that implements the
 * interface.
 *
 * <p>The companion is marked {@code @GeneratedCompanion}, which this processor owns, and suppresses the
 * {@code deprecation} and {@code removal} warnings of the members it calls: the author deprecates a bound member
 * in the sources they own and cannot suppress what a generated file says about it, and a build that compiles that
 * file with {@code -Xlint:all -Werror} fails on such a warning.</p>
 *
 * <p>Each recovery rule of an edge becomes one {@code recover} call of that edge, in declaration order, after its
 * {@code failsOn} calls and before its hook: {@code recover(K.class, id)} for a rule that lists no throwable type,
 * {@code recover(K.class, id, A.class)} for a rule that lists one, and
 * {@code recover(K.class, id, List.of(A.class, B.class))} for a rule that lists more, which is the only case in which
 * the companion writes {@code java.util.List}.</p>
 *
 * <p>Types are written along two paths. The types the companion names itself, its {@code java.lang} ones
 * included, are written as {@link Names} decides from what the companion's scope already holds, the member types
 * it inherits from the facade among them: by their simple name where that name resolves to them, qualified
 * otherwise. The types an operation takes as its input, the parameter types that pin a bound overload, the
 * {@code failsOn} classes, and the kind and the throwable classes of each recovery rule are written by
 * {@link TypeSource}, every declared type in them by its qualified name, together with their type annotations.
 * A qualified name starts with a package or, for a type of the unnamed
 * package, with the top-level type it is or is nested in, which is the whole name of a top-level one. A type of
 * the companion's scope takes that first segment wherever it heads a name; a field of that scope takes it, or a
 * simple name, only where an expression reads it: as the qualifier of a member the companion calls or reads, or
 * in an enum constant an annotation value names. So {@link #emit} answers, beside the source, every first segment
 * and simple name the companion writes and which of them an expression reads, and the processor refuses, as
 * {@code SG603} and before writing it, a facade whose companion would write one that its scope takes.</p>
 *
 * <p>Variables are named after the types they hold, or after their role. The parameter that takes the entity is
 * its decapitalized simple name; when that name is a keyword, or a name the companion already uses, it is that name
 * followed by {@code Entity}, and by the smallest numeric suffix from {@code 2} that frees it should that be used as
 * well: an entity {@code Case} is taken as {@code caseEntity}. Every other variable whose name is a keyword, is
 * taken by another variable in scope, or is an identifier the file writes at the head of a type reference gets the
 * smallest numeric suffix from {@code 2} that frees it: a host {@code Default} is taken as {@code default2}. So no
 * variable the companion declares obscures a type or a package it names.</p>
 *
 * <p>A bound method or an accessor the companion calls may declare a checked exception, which no functional
 * interface of the DSL declares. The lambda then makes the call in a block that catches what it throws, as
 * {@link BoundInvocation#caught} decides, and throws it on unchanged through a private static method of the companion,
 * so the engine receives what the method threw and routes it as it routes a checked exception thrown without being
 * declared. That method is named {@code rethrow}, followed by the smallest numeric suffix from {@code 2} that frees
 * the name when the facade declares or inherits a method of that name; the catch parameter is named {@code failure},
 * suffixed like a variable. A companion that makes no such call declares no such method.</p>
 */
public final class MachineGenerator {

    private static final String GRAPH_DEFINITION = "io.github.graphmaquina.api.GraphDefinition";
    private static final String MACHINE_KEY = "io.github.graphmaquina.api.MachineKey";
    private static final String OUTCOME = "io.github.graphmaquina.api.Outcome";
    private static final String STATE_MACHINE = "io.github.graphmaquina.api.StateMachine";
    private static final String WATCH_CONDITION = "io.github.graphmaquina.api.WatchCondition";
    private static final String STATE_GRAPH = "io.github.graphmaquina.dsl.StateGraph";
    private static final String DURATION = "java.time.Duration";
    private static final String LIST = "java.util.List";
    private static final String OBJECTS = "java.util.Objects";
    private static final String OPTIONAL = "java.util.Optional";
    private static final String SET = "java.util.Set";
    private static final String COMPLETABLE_FUTURE = "java.util.concurrent.CompletableFuture";
    private static final String SUPPLIER = "java.util.function.Supplier";
    private static final String GENERATED = "io.github.graphmaquina.annotation.internal.GeneratedCompanion";
    private static final String SUPPRESS_WARNINGS = "java.lang.SuppressWarnings";

    private static final String OVERRIDE = "java.lang.Override";

    /** The fields the companion declares under a name it does not choose: {@code KEY} is part of its public API. */
    public static final Set<String> FIXED_FIELDS = Set.of("KEY");

    private MachineGenerator() { }

    /**
     * The names the companion gives its variables. {@code reserved} holds the names no later variable takes: the
     * identifiers the file writes at the head of a type reference, which a variable would obscure, and the names
     * {@code machine}, {@code call}, {@code state} and {@code failure} hold, of its field, of the parameters of its
     * constructor, {@code async} and {@code transitionsFrom}, and of every catch clause; {@code parameters} are the
     * parameters of {@code graph(...)}, and {@code entity} names the entity wherever a method or a lambda takes it.
     * {@code rethrow} names the private method that throws on what a lambda caught; it and {@code failure} are empty
     * when no lambda catches anything.
     */
    private record Variables(Set<String> reserved, String machine, String state, String call,
                             Map<String, String> parameters, String entity, String rethrow, String failure) {

        /** Every name a lambda of {@code graph(...)} may not take. */
        Set<String> taken() {
            Set<String> taken = new LinkedHashSet<>(parameters.values());
            taken.addAll(reserved);
            return taken;
        }
    }

    public static String qualifiedName(MachineModel model) {
        return SymbolNames.qualified(model.packageName(), SymbolNames.runtimeName(model.facadeName()));
    }

    /**
     * What {@link TypeSource} writes in the companion of {@code model}: the operations' inputs, the types that pin
     * an overload, the {@code failsOn} classes, and the kind and the throwable classes of every recovery rule.
     */
    private static Written rendered(MachineModel model, BoundInvocation invocations) {
        List<TypeMirror> sources = new ArrayList<>();
        for (OperationModel operation : model.operations()) {
            operation.inputType().ifPresent(sources::add);
        }
        for (TransitionModel transition : model.allTransitions()) {
            sources.addAll(transition.failsOn());
            for (RecoveryModel recovery : transition.recoveries()) {
                sources.add(recovery.kind());
                sources.addAll(recovery.failsOn());
            }
        }
        for (Binding binding : bindings(model)) {
            sources.addAll(invocations.casts(binding));
        }
        Written written = new Written(Map.of(), Map.of(), Map.of(), Map.of());
        for (TypeMirror type : sources) {
            written = written.and(TypeSource.written(type));
        }
        return written;
    }

    /**
     * The companion of {@code model}, with what it writes at the head of its names: the names {@link Names} writes,
     * those an expression reads among them, and what {@link TypeSource} writes. The processor checks the latter
     * against the companion's scope before it writes the source.
     */
    public static Emitted emit(MachineModel model, BoundInvocation invocations) {
        TypeElement facadeType = model.types().facade();
        Names names = names(model, invocations);
        Written rendered = rendered(model, invocations);
        Variables variables = variables(model, names.written().and(rendered), invocations);
        String e = names.of(model.types().entityElement().getQualifiedName().toString());
        String s = names.of(model.types().statesElement().getQualifiedName().toString());
        String t = names.of(model.types().transitionsElement().getQualifiedName().toString());
        String generics = "<" + e + ", " + s + ", " + t + ">";
        String facade = names.of(facadeType.getQualifiedName().toString());
        String companion = SymbolNames.runtimeName(model.facadeName());
        String key = names.of(MACHINE_KEY);
        String outcome = names.of(OUTCOME);
        String objects = names.qualifier(OBJECTS);
        String definition = names.of(GRAPH_DEFINITION);
        String override = "@" + names.of(OVERRIDE);
        String machine = variables.machine();
        String entity = variables.entity();
        SourceWriter out = new SourceWriter();
        out.packageDeclaration(model.packageName());
        for (String type : names.imports()) {
            out.line("import " + type + ";");
        }
        out.blank();
        out.line("/** Generated companion of {@link " + facade + "}: builds its graph and implements its facade. Do not edit. */");
        out.line("@" + names.of(GENERATED));
        out.line("@" + names.of(SUPPRESS_WARNINGS) + "({\"deprecation\", \"removal\"})");
        out.open("public final class " + companion + " implements " + facade);
        out.blank();
        out.line("public static final " + key + generics + " KEY = " + names.qualifier(MACHINE_KEY) + ".of(" + e
                + ".class, " + s + ".class, " + t + ".class);");
        out.blank();
        out.line("private final " + names.of(STATE_MACHINE) + generics + " " + machine + ";");
        out.blank();
        out.open("public " + companion + "(" + names.of(STATE_MACHINE) + generics + " " + machine + ")");
        out.line("this." + machine + " = " + objects + ".requireNonNull(" + machine + ", " + quote(machine) + ");");
        out.close();
        out.blank();
        graph(model, names, variables, invocations, out);
        for (OperationModel operation : model.operations()) {
            Set<String> declared = new LinkedHashSet<>(variables.reserved());
            declared.add(entity);
            String input = unique("input", declared);
            String declaration = operation.inputType()
                    .map((TypeMirror type) -> ", " + TypeSource.render(type) + " " + input).orElse("");
            String argument = operation.inputType().isPresent() ? ", " + input : "";
            out.blank();
            out.line(override);
            out.open("public " + outcome + generics + " " + operation.name() + "(" + e + " " + entity + declaration + ")");
            out.line(objects + ".requireNonNull(" + entity + ", " + quote(entity) + ");");
            out.line("return " + machine + ".execute(" + entity + ", "
                    + constant(names, model.types().transitionsElement(), operation.transitionId()) + argument + ");");
            out.close();
        }
        out.blank();
        out.line(override);
        out.line("public " + definition + generics + " definition() { return " + machine + ".definition(); }");
        out.blank();
        out.line(override);
        out.line("public " + outcome + generics + " reevaluate(" + e + " " + entity + ") { return " + machine + ".reevaluate("
                + entity + "); }");
        out.blank();
        out.line(override);
        out.line("public " + names.of(SET) + "<" + t + "> transitionsFrom(" + s + " " + variables.state()
                + ") { return " + machine + ".transitionsFrom(" + variables.state() + "); }");
        out.blank();
        out.line(override);
        out.line("public <R> " + names.of(COMPLETABLE_FUTURE) + "<R> async(" + names.of(SUPPLIER) + "<R> "
                + variables.call() + ") { return " + machine + ".supplyAsync(" + variables.call() + "); }");
        if (!variables.rethrow().isEmpty()) {
            rethrower(out, names, variables.rethrow());
        }
        out.close();
        return new Emitted(out.toString(), names.written().and(rendered));
    }

    /** How the companion of {@code model} writes its types, from what it claims and what its scope holds already. */
    private static Names names(MachineModel model, BoundInvocation invocations) {
        TypeElement facade = model.types().facade();
        return new Names(model.packageName(), types(model, invocations),
                Names.Scope.of(invocations.elements(), Names.packageOf(facade), facade, Map.of()));
    }

    /**
     * The names of the companion's variables, chosen once the file knows what it writes: no variable takes an
     * identifier {@code written} holds, whether {@link Names} or a rendering of {@link TypeSource} writes it.
     */
    private static Variables variables(MachineModel model, Written written, BoundInvocation invocations) {
        Set<String> reserved = new LinkedHashSet<>(written.identifiers());
        String machine = unique("stateMachine", reserved);
        String call = unique("call", reserved);
        String state = unique("state", reserved);
        boolean catches = !caught(model, invocations).isEmpty();
        String failure = catches ? unique("failure", reserved) : "";
        String rethrow = catches ? rethrowName(model.types().facade(), invocations.elements()) : "";
        Map<String, String> parameters = parameterNames(model.instanceDependencies(), reserved);
        return new Variables(Set.copyOf(reserved), machine, state, call, parameters,
                entityParameter(model, parameters, reserved), rethrow, failure);
    }

    /**
     * The types the lambdas of the companion of {@code model} catch, in the order they first appear: one for each
     * call to a bound method or to a watch's peers or owner reader that declares a checked exception, as
     * {@link BoundInvocation#caught} decides; none when no call does.
     */
    private static Set<String> caught(MachineModel model, BoundInvocation invocations) {
        Set<String> caught = new LinkedHashSet<>();
        for (Binding binding : bindings(model)) {
            invocations.caught(binding).ifPresent(caught::add);
        }
        for (WatchModel watch : model.watches()) {
            invocations.caught(watch.peers().accessor()).ifPresent(caught::add);
            invocations.caught(watch.owner().accessor()).ifPresent(caught::add);
        }
        return caught;
    }

    /** Every binding the companion calls: the edges' actions, guards and hooks, and the watches' conditions. */
    private static List<Binding> bindings(MachineModel model) {
        List<Binding> bindings = new ArrayList<>();
        for (TransitionModel transition : model.allTransitions()) {
            bindings.addAll(transition.bindings());
        }
        for (WatchModel watch : model.watches()) {
            bindings.addAll(watch.bindings());
        }
        return bindings;
    }

    private static void graph(MachineModel model, Names names, Variables variables, BoundInvocation invocations,
                              SourceWriter out) {
        String e = names.of(model.types().entityElement().getQualifiedName().toString());
        String s = names.of(model.types().statesElement().getQualifiedName().toString());
        String t = names.of(model.types().transitionsElement().getQualifiedName().toString());
        TypeElement states = model.types().statesElement();
        TypeElement transitions = model.types().transitionsElement();
        String objects = names.qualifier(OBJECTS);
        Map<String, String> parameters = variables.parameters();
        List<String> declarations = new ArrayList<>();
        for (TypeElement dependency : model.instanceDependencies()) {
            declarations.add(names.of(dependency.getQualifiedName().toString()) + " "
                    + parameters.get(dependency.getQualifiedName().toString()));
        }
        out.line("/** The definition, built from the annotations of {@link "
                + names.of(model.types().facade().getQualifiedName().toString())
                + "}. One parameter per class with instance members. */");
        out.open("public static " + names.of(GRAPH_DEFINITION) + "<" + e + ", " + s + ", " + t + "> graph("
                + String.join(", ", declarations) + ")");
        for (TypeElement dependency : model.instanceDependencies()) {
            String name = parameters.get(dependency.getQualifiedName().toString());
            out.line(objects + ".requireNonNull(" + name + ", \"" + name + "\");");
        }
        out.line("return " + names.qualifier(STATE_GRAPH) + ".define(KEY)");
        out.indent().indent();
        out.line(".initial(" + constant(names, states, model.initial()) + ")");
        for (String terminal : model.terminals()) {
            out.line(".terminal(" + constant(names, states, terminal) + ")");
        }
        for (String state : model.cycleAllowed()) {
            out.line(".allowsCycle(" + constant(names, states, state) + ")");
        }
        for (StatePolicyModel policy : model.policies()) {
            out.line(".statePolicy(" + constant(names, states, policy.state()) + ", " + quote(policy.otherwise()) + ")");
        }
        for (RejectionModel rejection : model.rejections()) {
            out.line(".reject(" + constant(names, states, rejection.from()) + ", "
                    + constant(names, transitions, rejection.transition()) + ", " + quote(rejection.reason()) + ")");
        }
        for (TransitionModel transition : model.allTransitions()) {
            for (String from : transition.from()) {
                emitTransition(model, names, variables, invocations, transition, from, out);
            }
        }
        for (WatchModel watch : model.watches()) {
            emitWatch(model, names, variables, invocations, watch, out);
        }
        out.line(".build();");
        out.outdent().outdent();
        out.close();
    }

    private static void emitTransition(MachineModel model, Names names, Variables variables,
                                       BoundInvocation invocations, TransitionModel transition, String from,
                                       SourceWriter out) {
        TypeElement states = model.types().statesElement();
        TypeElement transitions = model.types().transitionsElement();
        List<String> context = List.of(unique("context", variables.taken()));
        List<String> entity = List.of(variables.entity());
        StringBuilder line = new StringBuilder(".transition(" + constant(names, transitions, transition.transitionId())
                + ", " + constant(names, states, from) + ", " + constant(names, states, transition.to()) + ")");
        if (transition.kind() == TransitionKind.ACTION) {
            Binding action = transition.action().orElseThrow();
            line.append(action.async() ? ".asyncAction(" : ".action(").append(quote(transition.name())).append(", ")
                    .append(lambda(action, context, true, variables, names, invocations)).append(')');
            transition.timeout().ifPresent((Duration timeout) -> line.append(".timeout(").append(names.qualifier(DURATION))
                    .append(".parse(\"").append(timeout).append("\"))"));
        }
        for (Binding guard : transition.guards()) {
            line.append(guard.async() ? ".asyncGuard(" : ".guard(").append(quote(guard.methodName())).append(", ")
                    .append(lambda(guard, context, true, variables, names, invocations)).append(')');
        }
        transition.onFailure().ifPresent((String recovery) ->
                line.append(".onFailure(").append(constant(names, transitions, recovery)).append(')'));
        for (TypeMirror type : transition.failsOn()) {
            line.append(".failsOn(").append(TypeSource.render(type)).append(".class)");
        }
        for (RecoveryModel recovery : transition.recoveries()) {
            line.append(".recover(").append(TypeSource.render(recovery.kind())).append(".class, ")
                    .append(constant(names, transitions, recovery.transition()))
                    .append(recoveryTypes(names, recovery.failsOn())).append(')');
        }
        transition.afterCommit().ifPresent((Binding hook) -> {
            boolean contextual = hook.arguments().size() == 2;
            line.append(contextual ? ".afterCommitWith(" : ".afterCommit(").append(quote(hook.methodName())).append(", ")
                    .append(lambda(hook, contextual ? hookParameters(entity.get(0), variables) : entity, false, variables,
                            names, invocations))
                    .append(')');
        });
        out.line(line.append(".endTransition()").toString());
    }

    /**
     * What follows the identifier in the {@code recover} call of a rule that lists {@code types}: nothing for none,
     * {@code , A.class} for one, and {@code , List.of(A.class, B.class)} for more, in the order the rule lists them.
     */
    private static String recoveryTypes(Names names, List<TypeMirror> types) {
        if (types.isEmpty()) {
            return "";
        }
        List<String> classes = new ArrayList<>();
        for (TypeMirror type : types) {
            classes.add(TypeSource.render(type) + ".class");
        }
        return types.size() == 1
                ? ", " + classes.get(0)
                : ", " + names.qualifier(LIST) + ".of(" + String.join(", ", classes) + ")";
    }

    private static void emitWatch(MachineModel model, Names names, Variables variables, BoundInvocation invocations,
                                  WatchModel watch, SourceWriter out) {
        String e = names.of(model.types().entityElement().getQualifiedName().toString());
        String p = names.of(watch.peer().entityElement().getQualifiedName().toString());
        String q = names.of(watch.peer().statesElement().getQualifiedName().toString());
        String u = names.of(watch.peer().transitionsElement().getQualifiedName().toString());
        TypeElement peerStates = watch.peer().statesElement();
        String entity = variables.entity();
        List<String> arguments = conditionParameters(entity, variables);
        String peer = unique(decapitalize(watch.peer().entityName()), variables.taken());
        String owner = watch.ownerIsOptional()
                ? peer + "." + watch.owner().method() + "()"
                : names.qualifier(OPTIONAL) + ".ofNullable(" + peer + "." + watch.owner().method() + "())";
        String peers = lambda(e + " " + entity, entity + "." + watch.peers().method() + "()",
                invocations.caught(watch.peers().accessor()).map(names::of), true, variables.rethrow(), variables.failure());
        String owners = lambda(p + " " + peer, owner, invocations.caught(watch.owner().accessor()).map(names::of), true,
                variables.rethrow(), variables.failure());
        out.line(".watch(" + constant(names, model.types().statesElement(), watch.state()) + ", "
                + names.qualifier(MACHINE_KEY) + ".of(" + p + ".class, " + q + ".class, " + u + ".class), " + peers + ", "
                + owners + ")");
        out.indent();
        for (RuleModel rule : watch.rules()) {
            String condition = switch (rule.kind()) {
                case CONDITION -> quote(rule.condition().orElseThrow().methodName()) + ", "
                        + lambda(rule.condition().orElseThrow(), arguments, true, variables, names, invocations);
                case ALL_IN -> names.qualifier(WATCH_CONDITION) + ".allIn(" + constant(names, peerStates, rule.argument()) + ")";
                case ANY_IN -> names.qualifier(WATCH_CONDITION) + ".anyIn(" + constant(names, peerStates, rule.argument()) + ")";
                case NONE_IN -> names.qualifier(WATCH_CONDITION) + ".noneIn(" + constant(names, peerStates, rule.argument()) + ")";
            };
            out.line(".when(" + condition + ").execute(" + constant(names, model.types().transitionsElement(),
                    rule.transition()) + ")");
        }
        out.line(".endWatch()");
        out.outdent();
    }

    /**
     * Every type the companion writes through {@link Names}, each with the package that declares it, in the order it
     * claims them: its own first, then the fixed ones, then the types of the watched peers, then {@code Duration},
     * {@code Optional}, {@code WatchCondition} and {@code List} where an edge, a watch or a recovery rule that lists
     * two throwable types or more needs them, then the model's other types,
     * then, where a lambda catches what a call throws, {@code Throwable} and the types the lambdas catch.
     */
    private static Map<String, String> types(MachineModel model, BoundInvocation invocations) {
        Map<String, String> types = new LinkedHashMap<>();
        Names.claim(types, model.types().facade());
        Names.claim(types, qualifiedName(model));
        for (String fixed : List.of(GRAPH_DEFINITION, MACHINE_KEY, OUTCOME, STATE_MACHINE, STATE_GRAPH, OBJECTS, SET,
                COMPLETABLE_FUTURE, SUPPLIER, GENERATED, SUPPRESS_WARNINGS, OVERRIDE)) {
            Names.claim(types, fixed);
        }
        boolean anyTimeout = false;
        boolean anyNullableOwner = false;
        boolean anyBuiltIn = false;
        boolean anyTypeList = false;
        for (TransitionModel transition : model.allTransitions()) {
            anyTimeout |= transition.timeout().isPresent();
            for (RecoveryModel recovery : transition.recoveries()) {
                anyTypeList |= recovery.failsOn().size() > 1;
            }
        }
        for (WatchModel watch : model.watches()) {
            anyNullableOwner |= !watch.ownerIsOptional();
            for (RuleModel rule : watch.rules()) {
                anyBuiltIn |= rule.kind() != RuleKind.CONDITION;
            }
            Names.claim(types, watch.peer().entityElement());
            Names.claim(types, watch.peer().statesElement());
            Names.claim(types, watch.peer().transitionsElement());
        }
        if (anyTimeout) {
            Names.claim(types, DURATION);
        }
        if (anyNullableOwner) {
            Names.claim(types, OPTIONAL);
        }
        if (anyBuiltIn) {
            Names.claim(types, WATCH_CONDITION);
        }
        if (anyTypeList) {
            Names.claim(types, LIST);
        }
        Names.claim(types, model.types().entityElement());
        Names.claim(types, model.types().statesElement());
        Names.claim(types, model.types().transitionsElement());
        for (TypeElement dependency : model.instanceDependencies()) {
            Names.claim(types, dependency);
        }
        for (Binding binding : bindings(model)) {
            if (binding.isStatic()) {
                Names.claim(types, binding.owner());
            }
        }
        claimCaught(types, caught(model, invocations));
        return types;
    }

    /**
     * Claims in {@code types} {@code Throwable}, which the method that throws on takes, then each type of
     * {@code caught}; nothing when {@code caught} is empty.
     */
    static void claimCaught(Map<String, String> types, Set<String> caught) {
        if (caught.isEmpty()) {
            return;
        }
        Names.claim(types, BoundInvocation.THROWABLE);
        for (String type : caught) {
            Names.claim(types, type);
        }
    }

    /** {@code type.constant}, an expression that reads a constant of the enum {@code type}, as this file writes it. */
    private static String constant(Names names, TypeElement type, String constant) {
        return names.qualifier(type.getQualifiedName().toString()) + "." + constant;
    }

    /**
     * Qualified name to parameter name, one entry per dependency, so a set names them as its machines do. A name
     * is the decapitalized simple name of the class; one that {@code reserved} already holds, that an earlier
     * dependency took, or that is a keyword ({@code Default} gives {@code default}) gets the smallest numeric
     * suffix from {@code 2} that frees it, so two classes sharing a simple name get a {@code 2} suffix.
     */
    static Map<String, String> parameterNames(List<TypeElement> dependencies, Set<String> reserved) {
        Map<String, String> names = new LinkedHashMap<>();
        Set<String> taken = new LinkedHashSet<>(reserved);
        for (TypeElement dependency : dependencies) {
            names.put(dependency.getQualifiedName().toString(),
                    unique(decapitalize(dependency.getSimpleName().toString()), taken));
        }
        return names;
    }

    /**
     * The lambda that hands the arguments of the functional interface the DSL takes to the exact method the binding
     * selected: the receiver is the host class for a static member and the injected parameter for an instance one.
     * A lambda, rather than a method reference, because only a call can name the parameter types that keep the
     * selected overload when the host has another one that would also fit. {@code returns} is false for a hook, whose
     * functional interface returns nothing.
     */
    private static String lambda(Binding binding, List<String> arguments, boolean returns, Variables variables,
                                 Names names, BoundInvocation invocations) {
        String receiver = binding.isStatic()
                ? names.qualifier(binding.owner().getQualifiedName().toString())
                : variables.parameters().get(binding.owner().getQualifiedName().toString());
        return lambda(String.join(", ", arguments), invocations.emit(binding, receiver, arguments),
                invocations.caught(binding).map(names::of), returns, variables.rethrow(), variables.failure());
    }

    /**
     * {@code (parameters) -> call}, or, when {@code caught} names what the call throws that the lambda may not, a
     * block that makes the call and catches that as {@code failure}, to throw it on unchanged through the
     * companion's method {@code rethrow}. {@code returns} is false where the functional interface returns nothing,
     * and the block then makes the call as a statement.
     */
    static String lambda(String parameters, String call, Optional<String> caught, boolean returns, String rethrow,
                         String failure) {
        String head = "(" + parameters + ") -> ";
        if (caught.isEmpty()) {
            return head + call;
        }
        return head + "{ try { " + (returns ? "return " : "") + call + "; } catch (" + caught.get() + " " + failure
                + ") { throw " + rethrow + "(" + failure + "); } }";
    }

    /**
     * The name of the method through which the companion of {@code implemented} throws on what a lambda caught:
     * {@code rethrow}, followed by the smallest numeric suffix from {@code 2} that frees it when a method
     * {@code implemented} declares or inherits has it. A static method may not hide an instance method the companion
     * inherits, and a call by a name both have would consider both.
     */
    static String rethrowName(TypeElement implemented, Elements elements) {
        Set<String> methods = new LinkedHashSet<>();
        for (ExecutableElement method : ElementFilter.methodsIn(elements.getAllMembers(implemented))) {
            methods.add(method.getSimpleName().toString());
        }
        return unique("rethrow", methods);
    }

    /**
     * The private method {@code rethrow}, which throws what it is given unchanged, a checked exception included,
     * through a type variable that a call leaves to javac, which infers it as {@code RuntimeException}. Its own
     * names, {@code X} and {@code failure}, cannot hide what it writes: {@code Throwable}, by its simple name or as
     * {@code java.lang.Throwable}.
     */
    static void rethrower(SourceWriter out, Names names, String rethrow) {
        String throwable = names.of(BoundInvocation.THROWABLE);
        out.blank();
        out.line("/** Throws what a bound method or an accessor threw, unchanged, which its lambda cannot declare. */");
        out.line("@" + names.of(SUPPRESS_WARNINGS) + "(\"unchecked\")");
        out.open("private static <X extends " + throwable + "> X " + rethrow + "(" + throwable + " failure) throws X");
        out.line("throw (X) failure;");
        out.close();
    }

    /**
     * The decapitalised entity name, suffixed with {@code Entity} when it is a keyword ({@code Case} gives
     * {@code case}), or when a dependency parameter or {@code reserved} already claims it; a suffixed name still
     * taken gets a numeric suffix as well.
     */
    private static String entityParameter(MachineModel model, Map<String, String> parameters, Set<String> reserved) {
        String name = decapitalize(model.types().entityName());
        Set<String> taken = new LinkedHashSet<>(reserved);
        taken.addAll(parameters.values());
        return SourceVersion.isName(name) && !taken.contains(name) ? name : unique(name + "Entity", taken);
    }

    /**
     * The three parameters of a condition lambda. They are named against one another as well as against the
     * enclosing method, because an entity type named {@code Peers} or {@code View} would otherwise give the
     * lambda two parameters of one name.
     */
    private static List<String> conditionParameters(String entity, Variables variables) {
        Set<String> taken = variables.taken();
        return List.of(unique(entity, taken), unique("peers", taken), unique("view", taken));
    }

    /**
     * Names of a contextual hook lambda's parameters, distinct from each other and from the enclosing method's,
     * because an entity type named {@code Commit} would otherwise give the lambda two parameters of one name.
     */
    private static List<String> hookParameters(String entity, Variables variables) {
        Set<String> taken = variables.taken();
        return List.of(unique(entity, taken), unique("commit", taken));
    }

    /**
     * {@code name}, suffixed until it is a legal identifier nothing in {@code taken} claims; the answer joins
     * {@code taken}. A keyword is never legal, so a name derived from a type such as {@code Interface} becomes
     * {@code interface2}.
     */
    static String unique(String name, Set<String> taken) {
        String candidate = name;
        int suffix = 1;
        while (!SourceVersion.isName(candidate) || !taken.add(candidate)) {
            suffix++;
            candidate = name + suffix;
        }
        return candidate;
    }

    static String decapitalize(String name) {
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
