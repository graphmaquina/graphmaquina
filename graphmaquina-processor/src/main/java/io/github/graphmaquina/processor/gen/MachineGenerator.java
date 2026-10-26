package io.github.graphmaquina.processor.gen;

import io.github.graphmaquina.annotation.internal.GeneratedCompanion;
import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.WatchCondition;
import io.github.graphmaquina.codegen.emit.SymbolNames;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.processor.model.Binding;
import io.github.graphmaquina.processor.model.CheckModel;
import io.github.graphmaquina.processor.model.MachineModel;
import io.github.graphmaquina.processor.model.MachineTypes;
import io.github.graphmaquina.processor.model.OperationModel;
import io.github.graphmaquina.processor.model.Property;
import io.github.graphmaquina.processor.model.RecoveryModel;
import io.github.graphmaquina.processor.model.RuleKind;
import io.github.graphmaquina.processor.model.RuleModel;
import io.github.graphmaquina.processor.model.SubmachineModel;
import io.github.graphmaquina.processor.model.TransitionModel;
import io.github.graphmaquina.processor.model.WatchModel;
import io.github.graphmaquina.processor.model.WatchOwner;
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
 * {@code deprecation} and {@code removal} warnings of the members it calls and the {@code rawtypes} warnings of the
 * types it writes back: the author deprecates a bound member, or declares a raw operation input or an entity that is
 * an inner class of a generic class, used raw, in the sources they own and cannot suppress what a generated file says
 * about it, and a build that compiles that file with {@code -Xlint:all -Werror} fails on such a warning.</p>
 *
 * <p>Every public member it declares, but the operations and the methods of {@code Machine} it implements, whose
 * comments {@code javadoc} inherits from the facade, carries a documentation comment with the tags its signature
 * calls for, as {@link SourceWriter#javadoc} writes it: the key, the constructor, {@code graph(...)} and the methods
 * that implement a check, whose comment says what they ask and that they run nothing.</p>
 *
 * <p>Each recovery rule of an edge becomes one {@code recover} call of that edge, in declaration order, after its
 * {@code failsOn} calls and before its hook: {@code recover(K.class, id)} for a rule that lists no throwable type,
 * {@code recover(K.class, id, A.class)} for a rule that lists one, and
 * {@code recover(K.class, id, List.of(A.class, B.class))} for a rule that lists more, which is the only case in which
 * the companion claims {@code java.util.List} through {@link Names}.</p>
 *
 * <p>Each sub-machine of the model becomes one {@code submachine} call, after the watches, in declaration order, with
 * one {@code exit} call per declared exit and then {@code endSubmachine()}. Its child's definition is a parameter of
 * {@code graph(...)}: one per distinct child, in order of first appearance, before the classes with instance members.
 * A child over the machine's own entity takes the DSL's overload without lookups; any other takes a lambda that reads
 * the {@code child} property of the machine's entity, and one that reads the {@code parent} property of the child's
 * entity or calls the {@code parentLookup}. Both properties return an {@code Optional} already, as the DSL takes them.
 * </p>
 *
 * <p>{@code graph(...)} declares the graph on the builder {@code StateGraph.define(KEY)} returns, one statement per
 * declaration, in the order the DSL takes them: the initial state, the terminals, the cycle allowances, the policies,
 * the rejections, each edge from each of its origins, the watches and the sub-machines. Each statement hands the
 * builder to the chain of its declaration and keeps the builder that chain returns, so no chain is longer than one
 * declaration's: javac attributes a chain recursively, one level per call. A graph of more than
 * {@value #DECLARATIONS_PER_METHOD} declarations is declared by the private methods {@code graphPart1},
 * {@code graphPart2}, …, of that many statements at most, which take the builder and the parameters of
 * {@code graph(...)} and return the builder, so that no method outgrows the 64 KB of code a method may hold. They are
 * named {@code graphPart} followed by their number, with as many underscores after {@code graphPart} as it takes for
 * no such name to be one of a method the facade declares or inherits. The builder's variable is named {@code graph},
 * suffixed as any variable is.</p>
 *
 * <p>Each check of the model becomes one method, after the operations, in declaration order. It refuses its null
 * arguments as an operation does, then returns {@code stateMachine.eligibility(entity, ID)}, or
 * {@code stateMachine.eligibility(entity, ID, input)} when the check has an input, which a primitive one reaches
 * boxed, since the state machine takes it as an {@code Object}. A check executes nothing and opens no edge, so the
 * graph is the one its operations declare. The companion claims {@code Eligibility} through {@link Names} only when
 * the facade declares a check.</p>
 *
 * <p>Types are written along two paths. The types the companion names itself, its {@code java.lang} ones
 * included, are written as {@link Names} decides from what the companion's scope already holds, the member types
 * it inherits from the facade among them: by their simple name where that name resolves to them, qualified
 * otherwise. The types an operation or a check takes as its input, the parameter types that pin a bound overload, the
 * {@code failsOn} classes, and the kind and the throwable classes of each recovery rule are written by
 * {@link TypeSource}, every declared type in them by its qualified name, together with their type annotations.
 * A qualified name starts with a package or, for a type of the unnamed package, with the top-level type it is or is
 * nested in, which is the whole name of a top-level one. A type of the companion's scope takes that first segment
 * wherever it heads a name; a field of that scope takes it, or a simple name, only where an expression reads it: as the
 * qualifier of a member the companion calls or reads, or in an enum constant an annotation value names. So
 * {@link #emit} answers, beside the source, every first segment and simple name the companion writes and which of them
 * an expression reads, and the processor refuses, as {@code SG603} and before writing it, a facade whose companion
 * would write one that its scope takes.</p>
 *
 * <p>Variables are named after the types they hold, or after their role, but for the parameters of an operation or a
 * check, which take the names the facade's method declares them with. The parameter that takes the definition of a
 * child is the decapitalized simple name of the child's facade followed by {@code Graph}. The parameter that takes the
 * entity anywhere else is its decapitalized simple name; when that name is a keyword, or a name the companion already
 * uses, it is that name followed by {@code Entity}, and by the smallest numeric suffix from {@code 2} that frees it
 * should that be used as well: an entity {@code Case} is taken as {@code caseEntity}. Every other variable whose name
 * is a keyword, is taken by another variable in scope, or is an identifier the file writes at the head of a type
 * reference gets the smallest numeric suffix from {@code 2} that frees it: a host {@code Default} is taken as
 * {@code default2}. So does a declared parameter of an operation or a check whose name the companion already uses, as
 * its field's or that of a variable of another of its methods: a parameter {@code stateMachine} is taken as
 * {@code stateMachine2}. An operation, and a check, refuse a null entity, and a null input of a reference type, with a
 * {@code NullPointerException} that names the parameter as the facade declares it. So no variable the companion
 * declares obscures a type or a package it names.</p>
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

    static final String GRAPH_DEFINITION = GraphDefinition.class.getName();
    static final String MACHINE_KEY = MachineKey.class.getName();
    private static final String OUTCOME = Outcome.class.getName();
    private static final String ELIGIBILITY = Eligibility.class.getName();
    private static final String STATE_MACHINE = StateMachine.class.getName();
    static final String WATCH_CONDITION = WatchCondition.class.getName();
    static final String STATE_GRAPH = StateGraph.class.getName();
    static final String GRAPH_BUILDER = GraphBuilder.class.getName();
    static final String DURATION = "java.time.Duration";
    static final String LIST = "java.util.List";
    static final String OBJECTS = "java.util.Objects";
    static final String OPTIONAL = "java.util.Optional";
    private static final String SET = "java.util.Set";
    private static final String COMPLETABLE_FUTURE = "java.util.concurrent.CompletableFuture";
    private static final String SUPPLIER = "java.util.function.Supplier";
    static final String GENERATED = GeneratedCompanion.class.getName();
    static final String SUPPRESS_WARNINGS = "java.lang.SuppressWarnings";

    static final String OVERRIDE = "java.lang.Override";

    /** The fields the companion declares under a name it does not choose: {@code KEY} is part of its public API. */
    public static final Set<String> FIXED_FIELDS = Set.of("KEY");

    /**
     * The most declarations one method of the companion makes on the graph's builder: a graph of more is declared by
     * private methods of at most this many statements each, so that no method outgrows the 64 KB of code a method may
     * hold.
     */
    static final int DECLARATIONS_PER_METHOD = 100;

    private MachineGenerator() { }

    /**
     * The names the companion gives its variables. {@code reserved} holds the names no later variable takes: the
     * identifiers the file writes at the head of a type reference, which a variable would obscure, and the names
     * {@code machine}, {@code call}, {@code state} and {@code failure} hold, of its field, of the parameters of its
     * constructor, {@code async} and {@code transitionsFrom}, and of every catch clause; {@code definitions} and
     * {@code parameters} are the parameters of {@code graph(...)}, the definitions of the children by the qualified
     * name of their facade and the classes with instance members by their own, and {@code entity} names the entity
     * wherever a method other than an operation, or a lambda, takes it. {@code rethrow} names the private method that
     * throws on what a lambda caught; it and {@code failure} are empty when no lambda catches anything.
     * {@code builder} names the variable that holds the graph's builder, and the parameter of each private method that
     * declares part of the graph, the methods {@code part} names followed by their number, starting at {@code 1}.
     */
    record Variables(Set<String> reserved, String machine, String state, String call,
                     Map<String, String> definitions, Map<String, String> parameters, String entity,
                     String rethrow, String failure, String builder, String part) {

        /** Every name a lambda of {@code graph(...)} may not take. */
        Set<String> taken() {
            Set<String> taken = new LinkedHashSet<>(definitions.values());
            taken.addAll(parameters.values());
            taken.addAll(reserved);
            return taken;
        }
    }

    public static String qualifiedName(MachineModel model) {
        return SymbolNames.qualified(model.packageName(), SymbolNames.runtimeName(model.facadeName()));
    }

    /**
     * What {@link TypeSource} writes in the companion of {@code model}: the inputs of the operations and of the
     * checks, the types that pin an overload, the {@code failsOn} classes, and the kind and the throwable classes of
     * every recovery rule.
     */
    private static Written rendered(MachineModel model, BoundInvocation invocations) {
        List<TypeMirror> sources = new ArrayList<>();
        for (OperationModel operation : model.operations()) {
            operation.inputType().ifPresent(sources::add);
        }
        for (CheckModel check : model.checks()) {
            check.inputType().ifPresent(sources::add);
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
        Names names = names(model, invocations);
        Written rendered = rendered(model, invocations);
        Variables variables = variables(model, names.written().and(rendered), invocations);
        String facade = names.of(model.types().facade().getQualifiedName().toString());
        String objects = names.qualifier(OBJECTS);
        SourceWriter out = new SourceWriter();
        openCompanion(out, names, model.packageName(), "Generated companion of {@link " + facade
                + "}: builds its graph and implements its facade.", SymbolNames.runtimeName(model.facadeName()),
                facade);
        fieldsAndConstructor(model, names, variables, objects, out);
        GraphStatements.graph(model, names, variables, invocations, out);
        for (OperationModel operation : model.operations()) {
            operation(model, names, variables, objects, operation, out);
        }
        for (CheckModel check : model.checks()) {
            check(model, names, variables, objects, check, out);
        }
        delegations(model, names, variables, out);
        if (!variables.rethrow().isEmpty()) {
            rethrower(out, names, variables.rethrow());
        }
        out.close();
        return new Emitted(out.toString(), names.written().and(rendered));
    }

    /**
     * Opens the source of a companion, a machine's or a set's: its package and its imports, its documentation
     * comment, which says {@code summary} and that the file is not to be edited, the annotations every companion
     * carries, {@code @GeneratedCompanion} and {@code @SuppressWarnings}, whose names {@link #GENERATED} and
     * {@link #SUPPRESS_WARNINGS} hold and {@code names} claims, and the declaration of the class {@code companion},
     * which implements {@code implemented}.
     */
    static void openCompanion(SourceWriter out, Names names, String packageName, String summary, String companion,
                              String implemented) {
        out.packageDeclaration(packageName);
        for (String type : names.imports()) {
            out.line("import " + type + ";");
        }
        out.blank();
        out.line("/** " + summary + " Do not edit. */");
        out.line("@" + names.of(GENERATED));
        out.line("@" + names.of(SUPPRESS_WARNINGS) + "({\"deprecation\", \"removal\", \"rawtypes\"})");
        out.open("public final class " + companion + " implements " + implemented);
        out.blank();
    }

    /**
     * The key of the machine, the field that holds the state machine, and the constructor that takes it, which
     * refuses a null one through {@code objects}, the way the companion writes {@code java.util.Objects}.
     */
    private static void fieldsAndConstructor(MachineModel model, Names names, Variables variables, String objects,
                                             SourceWriter out) {
        String e = names.of(model.types().entityElement().getQualifiedName().toString());
        String s = names.of(model.types().statesElement().getQualifiedName().toString());
        String t = names.of(model.types().transitionsElement().getQualifiedName().toString());
        String generics = typeArguments(model.types(), names);
        String machine = variables.machine();
        out.javadoc("The key of the machine: its entity, state and transition classes.", List.of());
        out.line("public static final " + names.of(MACHINE_KEY) + generics + " KEY = " + names.qualifier(MACHINE_KEY)
                + ".of(" + e + ".class, " + s + ".class, " + t + ".class);");
        out.blank();
        out.line("private final " + names.of(STATE_MACHINE) + generics + " " + machine + ";");
        out.blank();
        out.javadoc("Implements the facade over the given state machine, which every method of it calls.",
                List.of("@param " + machine + " the state machine of the facade"));
        out.open("public " + SymbolNames.runtimeName(model.facadeName()) + "(" + names.of(STATE_MACHINE) + generics
                + " " + machine + ")");
        out.line("this." + machine + " = " + objects + ".requireNonNull(" + machine + ", " + quote(machine) + ");");
        out.close();
        out.blank();
    }

    /**
     * The method that implements {@code operation}: it refuses a null entity, and a null input of a reference type,
     * through {@code objects}, then executes the operation's transition on the state machine.
     */
    private static void operation(MachineModel model, Names names, Variables variables, String objects,
                                  OperationModel operation, SourceWriter out) {
        String e = names.of(model.types().entityElement().getQualifiedName().toString());
        Parameters parameters = Parameters.of(variables, operation.entityParameter(), operation.inputParameter(),
                operation.inputType());
        out.blank();
        out.line("@" + names.of(OVERRIDE));
        out.open("public " + names.of(OUTCOME) + typeArguments(model.types(), names) + " " + operation.name() + "("
                + e + " " + parameters.entity() + parameters.declaration() + ")");
        parameters.refuseNull(objects, out);
        out.line("return " + variables.machine() + ".execute(" + parameters.entity() + ", "
                + constant(names, model.types().transitionsElement(), operation.transitionId())
                + parameters.argument() + ");");
        out.close();
    }

    /**
     * The method that implements {@code check}: it refuses a null entity, and a null input of a reference type,
     * through {@code objects}, then asks the state machine whether the check's transition would be admitted, with the
     * input when the check has one. Its parameters take the names an operation's do, and its documentation comment
     * says what it asks and that it runs nothing.
     */
    private static void check(MachineModel model, Names names, Variables variables, String objects, CheckModel check,
                              SourceWriter out) {
        String e = names.of(model.types().entityElement().getQualifiedName().toString());
        Parameters parameters = Parameters.of(variables, check.entityParameter(), check.inputParameter(),
                check.inputType());
        String target = parameters.entity();
        String transition = constant(names, model.types().transitionsElement(), check.transitionId());
        List<String> tags = new ArrayList<>();
        tags.add("@param " + target + " the entity to check");
        parameters.input().ifPresent((String name) -> tags.add("@param " + name + " the input of the operation"));
        tags.add("@return the answer: eligible, or ineligible with the reason");
        out.blank();
        out.javadoc("Asks whether {@code " + transition + "} would be admitted for {@code " + target + "} in the state"
                + " it is in now, as the operation that executes it would decide, without running it: it writes no"
                + " state, runs no action and tells no observer. It refuses a null argument with a"
                + " {@code NullPointerException} that names the parameter.", tags);
        out.line("@" + names.of(OVERRIDE));
        out.open("public " + names.of(ELIGIBILITY) + typeArguments(model.types(), names) + " " + check.name() + "("
                + e + " " + target + parameters.declaration() + ")");
        parameters.refuseNull(objects, out);
        out.line("return " + variables.machine() + ".eligibility(" + target + ", " + transition
                + parameters.argument() + ");");
        out.close();
    }

    /**
     * The parameters of a method that implements an operation or a check, as the companion declares them: the entity
     * under {@code entity}, and the input, when the method has one, under {@code input}, each taken from the name the
     * facade's method declares it with, {@code declaredEntity} and {@code declaredInput}, by the smallest numeric
     * suffix from {@code 2} that frees it should the companion use the name already. {@code inputType} is the type
     * javac resolved for the input.
     */
    private record Parameters(String declaredEntity, String entity, Optional<String> declaredInput,
                              Optional<String> input, Optional<TypeMirror> inputType) {

        /**
         * The parameters for the names {@code entityParameter} and {@code inputParameter} the facade declares, which
         * take a name no other variable of the companion has: the entity first, then the input.
         */
        static Parameters of(Variables variables, String entityParameter, Optional<String> inputParameter,
                             Optional<TypeMirror> inputType) {
            Set<String> declared = new LinkedHashSet<>(variables.reserved());
            String entity = unique(entityParameter, declared);
            Optional<String> input = inputParameter.map((String name) -> unique(name, declared));
            return new Parameters(entityParameter, entity, inputParameter, input, inputType);
        }

        /** {@code , Type input}, to follow the entity in the declaration of the method; nothing without an input. */
        String declaration() {
            return inputType.map((TypeMirror type) -> ", " + TypeSource.render(type) + " " + input.orElseThrow())
                    .orElse("");
        }

        /** {@code , input}, to follow the entity in a call; nothing without an input. */
        String argument() {
            return input.map((String name) -> ", " + name).orElse("");
        }

        /**
         * Writes the refusal of a null entity, and of a null input of a reference type, through {@code objects}, with
         * the names the facade declares; a primitive input cannot be null.
         */
        void refuseNull(String objects, SourceWriter out) {
            out.line(objects + ".requireNonNull(" + entity + ", " + quote(declaredEntity) + ");");
            if (inputType.filter((TypeMirror type) -> !type.getKind().isPrimitive()).isPresent()) {
                out.line(objects + ".requireNonNull(" + input.orElseThrow() + ", "
                        + quote(declaredInput.orElseThrow()) + ");");
            }
        }
    }

    /**
     * The methods of {@code Machine} the companion implements by handing the call to the state machine:
     * {@code definition()}, {@code reevaluate(...)}, {@code transitionsFrom(...)} and {@code async(...)}.
     */
    private static void delegations(MachineModel model, Names names, Variables variables, SourceWriter out) {
        String e = names.of(model.types().entityElement().getQualifiedName().toString());
        String s = names.of(model.types().statesElement().getQualifiedName().toString());
        String t = names.of(model.types().transitionsElement().getQualifiedName().toString());
        String generics = typeArguments(model.types(), names);
        String override = "@" + names.of(OVERRIDE);
        String machine = variables.machine();
        String entity = variables.entity();
        out.blank();
        out.line(override);
        out.line("public " + definitionType(model.types(), names) + " definition() { return " + machine
                + ".definition(); }");
        out.blank();
        out.line(override);
        out.line("public " + names.of(OUTCOME) + generics + " reevaluate(" + e + " " + entity + ") { return " + machine
                + ".reevaluate(" + entity + "); }");
        out.blank();
        out.line(override);
        out.line("public " + names.of(SET) + "<" + t + "> transitionsFrom(" + s + " " + variables.state()
                + ") { return " + machine + ".transitionsFrom(" + variables.state() + "); }");
        out.blank();
        out.line(override);
        out.line("public <R> " + names.of(COMPLETABLE_FUTURE) + "<R> async(" + names.of(SUPPLIER) + "<R> "
                + variables.call() + ") { return " + machine + ".supplyAsync(" + variables.call() + "); }");
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
        String builder = unique("graph", reserved);
        boolean catches = !caught(model, invocations).isEmpty();
        String failure = catches ? unique("failure", reserved) : "";
        String rethrow = catches ? rethrowName(model.types().facade(), invocations.elements()) : "";
        String part = partName(model.types().facade(), invocations.elements(), rethrow,
                GraphStatements.parts(GraphStatements.declarations(model)));
        Map<String, String> parameters = parameterNames(model.instanceDependencies(), reserved);
        Set<String> named = new LinkedHashSet<>(reserved);
        named.addAll(parameters.values());
        Map<String, String> definitions = new LinkedHashMap<>();
        for (MachineTypes child : model.children()) {
            definitions.put(child.facade().getQualifiedName().toString(),
                    unique(decapitalize(child.facade().getSimpleName().toString()) + "Graph", named));
        }
        Map<String, String> declared = new LinkedHashMap<>(definitions);
        declared.putAll(parameters);
        return new Variables(Set.copyOf(reserved), machine, state, call, definitions, parameters,
                entityParameter(model, declared, reserved), rethrow, failure, builder, part);
    }

    /**
     * The types the lambdas of the companion of {@code model} catch, in the order they first appear: one for each
     * call to a bound method, an exported lookup included, to a watch's peers or owner reader, or to a sub-machine's
     * child or parent reader that declares a checked exception, as {@link BoundInvocation#caught} decides; none when
     * no call does.
     */
    private static Set<String> caught(MachineModel model, BoundInvocation invocations) {
        Set<String> caught = new LinkedHashSet<>();
        for (Binding binding : bindings(model)) {
            invocations.caught(binding).ifPresent(caught::add);
        }
        for (WatchModel watch : model.watches()) {
            invocations.caught(watch.peers().accessor()).ifPresent(caught::add);
            if (watch.owner() instanceof WatchOwner.Read read) {
                invocations.caught(read.property().accessor()).ifPresent(caught::add);
            }
        }
        for (SubmachineModel submachine : model.submachines()) {
            for (Optional<Property> property : List.of(submachine.childOf(), submachine.parentOf())) {
                property.flatMap((Property read) -> invocations.caught(read.accessor())).ifPresent(caught::add);
            }
        }
        return caught;
    }

    /**
     * Every binding the companion calls: the edges' actions, guards and hooks, the watches' conditions and owner
     * lookups, and the sub-machines' parent lookups.
     */
    private static List<Binding> bindings(MachineModel model) {
        List<Binding> bindings = new ArrayList<>();
        for (TransitionModel transition : model.allTransitions()) {
            bindings.addAll(transition.bindings());
        }
        for (WatchModel watch : model.watches()) {
            bindings.addAll(watch.bindings());
        }
        for (SubmachineModel submachine : model.submachines()) {
            submachine.parentLookup().ifPresent(bindings::add);
        }
        return bindings;
    }

    /**
     * {@code <E, S, T>}: the entity, state and transition types of the machine {@code types}, as this file writes
     * them.
     */
    static String typeArguments(MachineTypes types, Names names) {
        return "<" + names.of(types.entityElement().getQualifiedName().toString()) + ", "
                + names.of(types.statesElement().getQualifiedName().toString()) + ", "
                + names.of(types.transitionsElement().getQualifiedName().toString()) + ">";
    }

    /** {@code GraphDefinition<E, S, T>} for the machine {@code types}, as this file writes it. */
    static String definitionType(MachineTypes types, Names names) {
        return names.of(GRAPH_DEFINITION) + typeArguments(types, names);
    }

    /**
     * Every type the companion writes through {@link Names}, each with the package that declares it, in the order it
     * claims them: its own first, then the fixed ones, then the types of the watched peers and of the children, then
     * {@code Duration}, {@code Optional}, {@code WatchCondition}, {@code List} and {@code Eligibility} where an
     * edge, a watch, a recovery rule that lists two throwable types or more or a check needs them, then the model's
     * other types, then, where a lambda catches what a call throws, {@code Throwable} and the types the lambdas
     * catch.
     */
    private static Map<String, String> types(MachineModel model, BoundInvocation invocations) {
        Map<String, String> types = new LinkedHashMap<>();
        Names.claim(types, model.types().facade());
        Names.claim(types, qualifiedName(model));
        for (String fixed : List.of(GRAPH_DEFINITION, MACHINE_KEY, OUTCOME, STATE_MACHINE, STATE_GRAPH, GRAPH_BUILDER,
                OBJECTS, SET, COMPLETABLE_FUTURE, SUPPLIER, GENERATED, SUPPRESS_WARNINGS, OVERRIDE)) {
            Names.claim(types, fixed);
        }
        for (WatchModel watch : model.watches()) {
            claimMachineTypes(types, watch.peer());
        }
        for (MachineTypes child : model.children()) {
            claimMachineTypes(types, child);
        }
        claimWhereNeeded(types, model);
        claimMachineTypes(types, model.types());
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

    /** Claims in {@code types} the entity, the state and the transition types of the machine {@code machine}. */
    static void claimMachineTypes(Map<String, String> types, MachineTypes machine) {
        Names.claim(types, machine.entityElement());
        Names.claim(types, machine.statesElement());
        Names.claim(types, machine.transitionsElement());
    }

    /**
     * Claims in {@code types}, in this order, {@code Duration} when an edge of {@code model} has a timeout,
     * {@code Optional} when the owner reader of a watch returns the owner itself, {@code WatchCondition} when a watch
     * has a built-in rule, {@code List} when a recovery rule lists two throwable types or more, and
     * {@code Eligibility} when the facade declares a check.
     */
    private static void claimWhereNeeded(Map<String, String> types, MachineModel model) {
        boolean anyTimeout = false;
        boolean anyPlainOwner = false;
        boolean anyBuiltIn = false;
        boolean anyTypeList = false;
        for (TransitionModel transition : model.allTransitions()) {
            anyTimeout |= transition.timeout().isPresent();
            for (RecoveryModel recovery : transition.recoveries()) {
                anyTypeList |= recovery.failsOn().size() > 1;
            }
        }
        for (WatchModel watch : model.watches()) {
            anyPlainOwner |= watch.owner() instanceof WatchOwner.Read read && !read.optional();
            for (RuleModel rule : watch.rules()) {
                anyBuiltIn |= rule.kind() != RuleKind.CONDITION;
            }
        }
        if (anyTimeout) {
            Names.claim(types, DURATION);
        }
        if (anyPlainOwner) {
            Names.claim(types, OPTIONAL);
        }
        if (anyBuiltIn) {
            Names.claim(types, WATCH_CONDITION);
        }
        if (anyTypeList) {
            Names.claim(types, LIST);
        }
        if (!model.checks().isEmpty()) {
            Names.claim(types, ELIGIBILITY);
        }
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
    static String constant(Names names, TypeElement type, String constant) {
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
        return unique("rethrow", methodNames(implemented, elements));
    }

    /**
     * The name the private methods that declare part of the graph are named by, each followed by its number from
     * {@code 1} to {@code parts}: {@code graphPart}, with as many underscores after it as it takes for none of those
     * names to be one of a method the facade declares or inherits, or {@code rethrow}. Empty when {@code parts} is
     * zero.
     */
    private static String partName(TypeElement facade, Elements elements, String rethrow, int parts) {
        if (parts == 0) {
            return "";
        }
        Set<String> taken = methodNames(facade, elements);
        taken.add(rethrow);
        String part = "graphPart";
        boolean free = false;
        while (!free) {
            free = true;
            for (int number = 1; number <= parts && free; number++) {
                free = !taken.contains(part + number);
            }
            if (!free) {
                part = part + "_";
            }
        }
        return part;
    }

    /** The names of every method {@code type} declares or inherits. */
    private static Set<String> methodNames(TypeElement type, Elements elements) {
        Set<String> methods = new LinkedHashSet<>();
        for (ExecutableElement method : ElementFilter.methodsIn(elements.getAllMembers(type))) {
            methods.add(method.getSimpleName().toString());
        }
        return methods;
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
