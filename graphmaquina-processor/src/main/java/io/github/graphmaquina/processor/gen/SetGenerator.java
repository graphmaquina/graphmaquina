package io.github.graphmaquina.processor.gen;

import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.codegen.emit.SymbolNames;
import io.github.graphmaquina.processor.model.Binding;
import io.github.graphmaquina.processor.model.MachineAccessor;
import io.github.graphmaquina.processor.model.MachineModel;
import io.github.graphmaquina.processor.model.MachineTypes;
import io.github.graphmaquina.processor.model.Property;
import io.github.graphmaquina.processor.model.SetModel;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
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
 * and {@code removal} warnings of the members it calls and the {@code rawtypes} warnings of the types it writes,
 * it writes its types and suffixes its variables as {@link MachineGenerator} describes, its own {@code Builder}
 * counted among the member types of its scope, and it throws on a checked exception that a state or identity
 * accessor declares as that class describes, naming its method after the methods the set declares or inherits; a
 * variable is named after its role, after the accessor it serves, or after the class a parameter of
 * {@code builder(...)} takes. {@link #emit} answers, beside the source, what the processor checks against that
 * scope before writing it.</p>
 *
 * <p>Its {@code Builder} mirrors {@code MachineRegistry.Builder} and {@code StateMachines.Builder}: it creates them
 * when it is created and hands every setting to them as soon as it is given, so a setting they refuse is refused where
 * it is given, observers add up in the order they are given, and the builder is single-use. The state and the identity
 * of each machine are read and written by lambdas that call the very accessors the property selected, never by a method
 * reference, which would also consider a static overload of the same name.</p>
 *
 * <p>The definition of a machine that another machine of the set binds as a sub-machine is built once, before the
 * definition of any machine that binds it, into a local variable of the {@code Builder}'s constructor named after its
 * accessor and followed by {@code Graph}. That one instance is what {@code StateMachines.from(...)} takes for the
 * child and what the {@code graph(...)} of each of its parents takes, as the registry requires. The shared timeout
 * goes to the machines {@link SetModel#asynchronous} names, and {@code asyncTimeout(Machine.class, Duration)}
 * accepts those only.</p>
 *
 * <p>Every public member it declares, but the methods it overrides, whose comments {@code javadoc} inherits, carries
 * a documentation comment with the tags its signature calls for, as {@link SourceWriter#javadoc} writes it.</p>
 */
public final class SetGenerator {

    private static final String MACHINE = Machine.class.getName();
    private static final String MACHINE_OBSERVER = MachineObserver.class.getName();
    private static final String MACHINE_REGISTRY = MachineRegistry.class.getName();
    private static final String STATE_MACHINES = StateMachines.class.getName();
    private static final String EXECUTOR = "java.util.concurrent.Executor";
    private static final String INSTANT_SOURCE = "java.time.InstantSource";
    private static final String CLASS = "java.lang.Class";
    private static final String ILLEGAL_ARGUMENT = "java.lang.IllegalArgumentException";
    private static final String ILLEGAL_STATE = "java.lang.IllegalStateException";

    /** The member types the set companion declares itself: its public {@code Builder}. */
    public static final Set<String> MEMBER_TYPES = Set.of("Builder");

    /**
     * The parameters of the {@code Builder}'s methods and the local variable of its {@code build()}, by the role
     * they play, which also is their usual name.
     */
    private static final List<String> SETTER_PARAMETERS = List.of("machine", "timeout", "executor", "clock", "depth",
            "chain", "observer", "built");

    /**
     * The doc text of the {@code Builder}'s {@code recordTrail(InstantSource)}: what
     * {@code StateMachines.Builder.recordTrail(InstantSource)} says of one machine, said of the set. It never names the
     * parameter: when another variable of the companion holds {@code clock}, the parameter takes another name.
     */
    private static final String RECORD_TRAIL = "Records the trail of every call through the set on the given clock, by"
            + " handing it to the StateMachines.Builder of every machine of the set. The root machine of a call decides"
            + " for the whole call, so every root call made through the set records its trail, the peers it cascades to"
            + " and the exits of sub-machines its steps run included, and so does each watch reaction, a call of its"
            + " own that its own machine, one of the set, decides. The flow such a call hands out, in its outcome or in"
            + " its failure, lists in trail() each hop and each sub-machine start the call took, each at the instant"
            + " that clock read right after the call took it, then the rejection or the failure that ended the call,"
            + " at the instant it ended. A call that records its trail creates its flow when its step starts, so it"
            + " hands out its flow, with the root entity and the trail, even when none of its steps or hooks wrote to"
            + " it; an ignored outcome hands out the empty flow: a reevaluation that selects no edge and an advance"
            + " whose state has no automatic edge run no step, and an advance whose automatic edges were all refused"
            + " wrote nothing."
            + " The clock is read on the thread running the call, once per entry the call appends, an entry a rollback"
            + " later drops included, so a clock that advances on each read leaves a gap for each dropped entry; a"
            + " test can pass a clock it controls, such as a java.time.Clock. Anything but an Error that the clock"
            + " throws, a checked exception included,"
            + " fails the call as what an identity function throws does: the entry it could not mark is left out of"
            + " the trail. When it throws while the"
            + " call reads the instant of the Failed entry that ends a failed call, whatever failed the call, the"
            + " call's CallFailedException carries what it threw as suppressed, unless it threw that very exception,"
            + " and its trail ends without that entry; an InterruptedException suppressed there interrupts the"
            + " thread again, and an Error propagates. A clock that answers null counts as one that throws a"
            + " NullPointerException. A set built without recordTrail reads no clock and keeps no mark.";

    /** The {@code @return} tag of every {@code Builder} method that answers the builder itself. */
    private static final String THIS_BUILDER = "@return this builder";

    private SetGenerator() { }

    /**
     * The names the set companion gives its variables, each one free of every identifier the file writes at the
     * head of a type reference and of every other variable in its scope.
     *
     * <p>{@code fields} names, by accessor, the private field that keeps each facade, and {@code registry} the one
     * that keeps the registry; {@code builders} names, by accessor, the field of the {@code Builder} that holds each
     * machine's own builder, beside {@code registryBuilder} and {@code used}. {@code parameters} maps the qualified
     * name of each parameter type of {@code builder(...)} to the parameter's name, the shared timeout first when
     * the set has one, whose name {@code timeout} holds as well; {@code definitions} names, by accessor, the local
     * variable of the {@code Builder}'s constructor that holds the definition of each machine another machine binds as
     * a sub-machine; {@code entity} and {@code state} name the parameters of the accessor lambdas, and
     * {@code setters}, by role, the parameters of the {@code Builder}'s methods and the local variable of its
     * {@code build()}. {@code rethrow} names the private method that throws on what an accessor lambda caught, and
     * {@code failure} the parameter of its catch clause; both are empty when no accessor lambda catches anything.</p>
     */
    private record Variables(Map<String, String> fields, String registry, Map<String, String> builders,
                             String registryBuilder, String used, Map<String, String> parameters, String timeout,
                             Map<String, String> definitions, String entity, String state,
                             Map<String, String> setters, String rethrow, String failure) { }

    public static String qualifiedName(SetModel model) {
        return SymbolNames.qualified(model.packageName(), SymbolNames.runtimeName(model.setName()));
    }

    /**
     * What {@link TypeSource} writes in the companion of {@code model}: the types that pin a state writer's
     * argument.
     */
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
        Names names = names(model, invocations);
        Written rendered = rendered(model, invocations);
        Variables variables = variables(model, names.written().and(rendered), invocations);
        String set = names.of(model.type().getQualifiedName().toString());
        SourceWriter out = new SourceWriter();
        MachineGenerator.openCompanion(out, names, model.packageName(), "Generated set of {@link " + set
                + "}: builds the registry once and hands out the facades.", companion, set);
        fieldsAndConstructor(model, names, variables, companion, out);
        List<String> parameters = parameters(names, variables);
        builderFactory(model, variables, parameters, out);
        builder(model, names, invocations, variables, parameters, out);
        overrides(model, names, variables, out);
        if (!variables.rethrow().isEmpty()) {
            MachineGenerator.rethrower(out, names, variables.rethrow());
        }
        out.close();
        return new Emitted(out.toString(), names.written().and(rendered));
    }

    /**
     * The fields that keep the registry and the facade of each machine, and the private constructor that creates
     * each facade, the machine's companion, over the registry's machine of the companion's {@code KEY}.
     */
    private static void fieldsAndConstructor(SetModel model, Names names, Variables variables, String companion,
                                             SourceWriter out) {
        String registry = variables.registry();
        out.line("private final " + names.of(MACHINE_REGISTRY) + " " + registry + ";");
        for (MachineAccessor accessor : model.machines()) {
            out.line("private final " + facadeName(accessor, names) + " " + variables.fields().get(accessor.method())
                    + ";");
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
    }

    /**
     * The parameters of {@code builder(...)}, which the {@code Builder}'s constructor declares as well, each with its
     * type: the shared timeout first when the set has one, then every class with instance members.
     */
    private static List<String> parameters(Names names, Variables variables) {
        List<String> parameters = new ArrayList<>();
        for (Map.Entry<String, String> parameter : variables.parameters().entrySet()) {
            parameters.add(names.of(parameter.getKey()) + " " + parameter.getValue());
        }
        return parameters;
    }

    /** The static {@code builder(...)}, which declares {@code parameters} and starts a {@code Builder} over them. */
    private static void builderFactory(SetModel model, Variables variables, List<String> parameters,
                                       SourceWriter out) {
        List<String> tags = new ArrayList<>();
        for (Map.Entry<String, String> parameter : variables.parameters().entrySet()) {
            tags.add("@param " + parameter.getValue() + (parameter.getValue().equals(variables.timeout())
                    ? " the timeout shared by the asynchronous machines"
                    : " the {@code " + parameter.getKey() + "} whose instance members the machines call"));
        }
        tags.add("@return a new builder of the set");
        out.javadoc("Starts a builder of the set over every class with instance members the machines use"
                + (model.hasAsync() ? ", after the timeout shared by the asynchronous machines" : "") + ".", tags);
        out.open("public static Builder builder(" + String.join(", ", parameters) + ")");
        out.line("return new Builder(" + String.join(", ", variables.parameters().values()) + ");");
        out.close();
        out.blank();
    }

    /**
     * The methods of the set that the companion implements: each accessor, which hands out its machine's facade, and
     * {@code close()}, which closes the registry.
     */
    private static void overrides(SetModel model, Names names, Variables variables, SourceWriter out) {
        for (MachineAccessor accessor : model.machines()) {
            out.blank();
            out.line("@" + names.of(MachineGenerator.OVERRIDE));
            out.line("public " + facadeName(accessor, names) + " " + accessor.method() + "() { return "
                    + variables.fields().get(accessor.method()) + "; }");
        }
        out.blank();
        out.line("@" + names.of(MachineGenerator.OVERRIDE));
        out.line("public void close() { " + variables.registry() + ".close(); }");
    }

    /**
     * The public {@code Builder}: its fields, its constructor, which takes {@code parameters}, and its methods, in
     * the order the companion declares them.
     */
    private static void builder(SetModel model, Names names, BoundInvocation invocations, Variables variables,
                                List<String> parameters, SourceWriter out) {
        String objects = names.qualifier(MachineGenerator.OBJECTS);
        out.line("/**");
        out.line(" * Mirrors MachineRegistry.Builder and StateMachines.Builder, which it creates with itself and "
                + "hands");
        out.line(" * every setting to as soon as it is given: what they refuse is refused where it is given, and");
        out.line(" * observers add up in the order they are given. Single-use and not thread-safe: once build()");
        out.line(" * succeeded, nothing is accepted.");
        out.line(" */");
        out.open("public static final class Builder");
        out.blank();
        builderFields(model, names, variables, out);
        builderConstructor(model, names, invocations, variables, objects, parameters, out);
        if (model.hasAsync()) {
            asyncTimeout(model, names, variables, objects, out);
        }
        asyncExecutor(model, names, variables, objects, out);
        recordTrail(model, names, variables, objects, out);
        registrySettings(names, variables, out);
        buildAndCheckUnused(model, names, variables, out);
        out.close();
    }

    /**
     * The fields of the {@code Builder}: the registry's builder, the builder of each machine, and whether
     * {@code build()} has succeeded.
     */
    private static void builderFields(SetModel model, Names names, Variables variables, SourceWriter out) {
        out.line("private final " + names.of(MACHINE_REGISTRY) + ".Builder " + variables.registryBuilder() + " = "
                + names.qualifier(MACHINE_REGISTRY) + ".builder();");
        for (MachineAccessor accessor : model.machines()) {
            out.line("private final " + machineBuilder(accessor.machine(), names) + " "
                    + variables.builders().get(accessor.method()) + ";");
        }
        out.line("private boolean " + variables.used() + ";");
        out.blank();
    }

    /**
     * The constructor of the {@code Builder}, which declares {@code parameters} and refuses a null one through
     * {@code objects}, builds the definition of each machine another machine binds, children first, and then the
     * builder of each machine.
     */
    private static void builderConstructor(SetModel model, Names names, BoundInvocation invocations,
                                           Variables variables, String objects, List<String> parameters,
                                           SourceWriter out) {
        out.open("private Builder(" + String.join(", ", parameters) + ")");
        for (String name : variables.parameters().values()) {
            out.line(objects + ".requireNonNull(" + name + ", " + MachineGenerator.quote(name) + ");");
        }
        for (MachineAccessor child : children(model)) {
            out.line(MachineGenerator.definitionType(child.machine().types(), names) + " "
                    + variables.definitions().get(child.method()) + " = " + graph(model, child, names, variables)
                    + ";");
        }
        for (MachineAccessor accessor : model.machines()) {
            machineBuilderStatements(model, accessor, names, invocations, variables, out);
        }
        out.close();
        out.blank();
    }

    /**
     * The statements of the {@code Builder}'s constructor that create the builder of the machine of
     * {@code accessor} over its definition and its state and identity lambdas, hand it the shared timeout when the
     * machine is asynchronous, and add it to the registry's builder.
     */
    private static void machineBuilderStatements(SetModel model, MachineAccessor accessor, Names names,
                                                 BoundInvocation invocations, Variables variables, SourceWriter out) {
        MachineModel machine = accessor.machine();
        String builder = variables.builders().get(accessor.method());
        String definition = variables.definitions().containsKey(accessor.method())
                ? variables.definitions().get(accessor.method()) : graph(model, accessor, names, variables);
        out.line("this." + builder + " = " + names.qualifier(STATE_MACHINES) + ".from(" + definition + ")");
        out.indent().indent();
        out.line(".state(" + accessors(machine, names, invocations, variables) + ")");
        out.line(".identity(" + reader(machine, machine.idGetter(), names, invocations, variables) + ")");
        out.line(".as(" + facadeName(accessor, names) + ".class);");
        out.outdent().outdent();
        if (model.asynchronous(machine)) {
            out.line(builder + ".asyncTimeout(" + variables.timeout() + ");");
        }
        out.line(variables.registryBuilder() + ".add(" + builder + ");");
    }

    /**
     * The {@code Builder}'s {@code asyncTimeout(Class, Duration)}, which overrides the shared timeout for the
     * asynchronous machine it names and refuses, through {@code objects}, a null argument.
     */
    private static void asyncTimeout(SetModel model, Names names, Variables variables, String objects,
                                     SourceWriter out) {
        String machine = variables.setters().get("machine");
        String timeout = variables.setters().get("timeout");
        out.javadoc("Overrides the shared timeout for one asynchronous machine: one whose own graph has an"
                + " asynchronous edge, or whose calls can run one implicitly, such as the exit of a sub-machine.",
                List.of("@param " + machine + " the facade class of an asynchronous machine of the set",
                        "@param " + timeout + " the timeout of that machine", THIS_BUILDER));
        out.open("public Builder asyncTimeout(" + names.of(CLASS) + "<? extends " + names.of(MACHINE)
                + "<?, ?, ?>> " + machine + ", " + names.of(MachineGenerator.DURATION) + " " + timeout + ")");
        out.line("checkUnused();");
        out.line(objects + ".requireNonNull(" + machine + ", \"machine\");");
        out.line(objects + ".requireNonNull(" + timeout + ", \"timeout\");");
        for (MachineAccessor accessor : model.machines()) {
            if (model.asynchronous(accessor.machine())) {
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

    /**
     * The {@code Builder}'s {@code asyncExecutor(Executor)}, which hands the executor to the builder of every machine
     * and refuses, through {@code objects}, a null one.
     */
    private static void asyncExecutor(SetModel model, Names names, Variables variables, String objects,
                                      SourceWriter out) {
        String executor = variables.setters().get("executor");
        out.javadoc("Hands the given executor to the StateMachines.Builder of every machine of the set, whose calls"
                + " submitted through async(...) then run on it, as StateMachines.Builder.asyncExecutor(Executor)"
                + " describes.",
                List.of("@param " + executor + " the executor of those calls", THIS_BUILDER));
        out.open("public Builder asyncExecutor(" + names.of(EXECUTOR) + " " + executor + ")");
        out.line("checkUnused();");
        out.line(objects + ".requireNonNull(" + executor + ", \"executor\");");
        for (MachineAccessor accessor : model.machines()) {
            out.line(variables.builders().get(accessor.method()) + ".asyncExecutor(" + executor + ");");
        }
        out.line("return this;");
        out.close();
        out.blank();
    }

    /**
     * The {@code Builder}'s {@code recordTrail()} and {@code recordTrail(InstantSource)}, which hand the recording of
     * the trail to the builder of every machine, on the system clock or on the given one, which the second refuses,
     * through {@code objects}, when null.
     */
    private static void recordTrail(SetModel model, Names names, Variables variables, String objects,
                                    SourceWriter out) {
        out.javadoc("Records the trail of every call through the set on the system clock, InstantSource.system(), by"
                + " calling recordTrail() on the StateMachines.Builder of every machine of the set, as"
                + " recordTrail(InstantSource) describes.", List.of(THIS_BUILDER));
        out.open("public Builder recordTrail()");
        out.line("checkUnused();");
        for (MachineAccessor accessor : model.machines()) {
            out.line(variables.builders().get(accessor.method()) + ".recordTrail();");
        }
        out.line("return this;");
        out.close();
        out.blank();
        String clock = variables.setters().get("clock");
        out.javadoc(RECORD_TRAIL, List.of("@param " + clock + " the clock the trail is read on", THIS_BUILDER));
        out.open("public Builder recordTrail(" + names.of(INSTANT_SOURCE) + " " + clock + ")");
        out.line("checkUnused();");
        out.line(objects + ".requireNonNull(" + clock + ", \"clock\");");
        for (MachineAccessor accessor : model.machines()) {
            out.line(variables.builders().get(accessor.method()) + ".recordTrail(" + clock + ");");
        }
        out.line("return this;");
        out.close();
        out.blank();
    }

    /**
     * The settings the {@code Builder} hands to the registry's builder: the deepest cascade, the longest chain of
     * watch reactions, the longest chain of automatic attempts, and the observers.
     */
    private static void registrySettings(Names names, Variables variables, SourceWriter out) {
        String registry = variables.registryBuilder();
        Map<String, String> setters = variables.setters();
        forward(out, "Bounds how deep a cascade may go, the root being at depth 0. The exit of a sub-machine counts at"
                + " its own depth: one level below the step that ended the child or, when the child stood in a terminal"
                + " state while its parent was still running the step that entered the bound state, one level below"
                + " that step. The default is 10, and a value below 1 is refused.",
                "maxCascadeDepth", "int " + setters.get("depth"),
                "@param " + setters.get("depth") + " the deepest level a cascade may reach, at least 1",
                registry + ".maxCascadeDepth(" + setters.get("depth") + ");");
        forward(out, "Bounds how many watch reactions may follow one another; the default is 10, and a value below 1"
                + " is refused. A call that an after-commit hook starts is a new root, which this bound never counts:"
                + " the watch reactions it triggers begin a chain of their own, so hooks that keep starting calls recur"
                + " until the stack of the thread overflows, as MachineRegistry says.", "maxWatchChain",
                "int " + setters.get("chain"),
                "@param " + setters.get("chain") + " how many watch reactions may follow one another, at least 1",
                registry + ".maxWatchChain(" + setters.get("chain") + ");");
        forward(out, "Bounds how many automatic attempts that commit something may follow one another on one path of"
                + " continuations from a call of the application; the default is 10, and a value below 1 is refused."
                + " The attempt past the bound that admits a candidate aborts before its action, writing nothing, with"
                + " an AutomaticChainExceededException. A call that an after-commit hook starts is a new root, which"
                + " this bound never counts.", "maxAutomaticChain", "int " + setters.get("chain"),
                "@param " + setters.get("chain") + " how many automatic attempts may follow one another, at least 1",
                registry + ".maxAutomaticChain(" + setters.get("chain") + ");");
        forward(out, "Adds an observer of every call of the set; observers add up and are notified in the order they"
                + " are given.", "observer", names.of(MACHINE_OBSERVER) + " " + setters.get("observer"),
                "@param " + setters.get("observer") + " the observer to add",
                registry + ".observer(" + setters.get("observer") + ");");
    }

    /**
     * The {@code Builder}'s {@code build()}, which builds the registry and the set over it, and its private
     * {@code checkUnused()}, which every other method calls first and which refuses any use once {@code build()} has
     * succeeded.
     */
    private static void buildAndCheckUnused(SetModel model, Names names, Variables variables, SourceWriter out) {
        String built = variables.setters().get("built");
        String used = variables.used();
        String companion = names.of(qualifiedName(model));
        out.javadoc("Builds the registry of the machines and the set over it, which hands out their facades. Once it"
                + " succeeds, the builder accepts nothing more.", List.of("@return the set"));
        out.open("public " + names.of(model.type().getQualifiedName().toString()) + " build()");
        out.line("checkUnused();");
        out.line(companion + " " + built + " = new " + companion + "(" + variables.registryBuilder() + ".build());");
        out.line(used + " = true;");
        out.line("return " + built + ";");
        out.close();
        out.blank();
        out.line("/** Refuses any use once build() has succeeded: a built set is neither rebuilt nor reconfigured. */");
        out.open("private void checkUnused()");
        out.open("if (" + used + ")");
        out.line("throw new " + names.of(ILLEGAL_STATE) + "("
                + MachineGenerator.quote("this " + SymbolNames.runtimeName(model.setName())
                        + ".Builder was already used") + ");");
        out.close();
        out.close();
    }

    /**
     * One setting the {@code Builder} hands to the registry's builder as soon as it is given, documented by
     * {@code doc} and by {@code tag}, the {@code @param} tag of its one parameter.
     */
    private static void forward(SourceWriter out, String doc, String method, String parameter, String tag,
                                String statement) {
        out.javadoc(doc, List.of(tag, THIS_BUILDER));
        out.open("public Builder " + method + "(" + parameter + ")");
        out.line("checkUnused();");
        out.line(statement);
        out.line("return this;");
        out.close();
        out.blank();
    }

    /**
     * The machines of {@code model} that another machine of the set binds as a sub-machine, children before parents:
     * the order in which the {@code Builder} builds their definitions. Each machine comes after every machine it binds,
     * and otherwise in accessor order.
     */
    private static List<MachineAccessor> children(SetModel model) {
        Set<String> bound = new LinkedHashSet<>();
        for (MachineAccessor accessor : model.machines()) {
            for (MachineTypes child : accessor.machine().children()) {
                bound.add(child.facade().getQualifiedName().toString());
            }
        }
        List<MachineAccessor> ordered = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        for (MachineAccessor accessor : model.machines()) {
            visit(model, accessor, bound, visited, ordered);
        }
        return ordered;
    }

    /** Adds the bound machines {@code accessor} reaches to {@code ordered}, each after the machines it binds. */
    private static void visit(SetModel model, MachineAccessor accessor, Set<String> bound, Set<String> visited,
                              List<MachineAccessor> ordered) {
        String facade = accessor.machine().types().facade().getQualifiedName().toString();
        if (!visited.add(facade)) {
            return;
        }
        for (MachineTypes child : accessor.machine().children()) {
            model.accessorOf(child.facade()).ifPresent((MachineAccessor held) ->
                    visit(model, held, bound, visited, ordered));
        }
        if (bound.contains(facade)) {
            ordered.add(accessor);
        }
    }

    /**
     * {@code <Machine>Runtime.graph(...)} for {@code accessor}: the definitions of the machines it binds, from the
     * local variables the {@code Builder}'s constructor holds them in, then its classes with instance members.
     */
    private static String graph(SetModel model, MachineAccessor accessor, Names names, Variables variables) {
        List<String> arguments = new ArrayList<>();
        for (MachineTypes child : accessor.machine().children()) {
            arguments.add(variables.definitions().get(model.accessorOf(child.facade()).orElseThrow().method()));
        }
        for (TypeElement dependency : accessor.machine().instanceDependencies()) {
            arguments.add(variables.parameters().get(dependency.getQualifiedName().toString()));
        }
        return companionQualifier(accessor, names) + ".graph(" + String.join(", ", arguments) + ")";
    }

    /**
     * The state reader and writer of {@code machine} as two lambdas, each calling the accessor the property
     * selected; the writer's argument is pinned, as {@link BoundInvocation} decides, to the parameter type that
     * keeps that writer selected.
     */
    private static String accessors(MachineModel machine, Names names, BoundInvocation invocations,
                                    Variables variables) {
        String e = names.of(machine.types().entityElement().getQualifiedName().toString());
        String s = names.of(machine.types().statesElement().getQualifiedName().toString());
        String entity = variables.entity();
        String state = variables.state();
        Binding writer = writer(machine);
        return reader(machine, machine.stateGetter(), names, invocations, variables) + ", "
                + MachineGenerator.lambda(e + " " + entity + ", " + s + " " + state,
                        invocations.emit(writer, entity, List.of(state)), invocations.caught(writer).map(names::of),
                        false, variables.rethrow(), variables.failure());
    }

    /** A lambda that reads {@code property} from the entity of {@code machine} by calling its selected accessor. */
    private static String reader(MachineModel machine, Property property, Names names, BoundInvocation invocations,
                                 Variables variables) {
        String e = names.of(machine.types().entityElement().getQualifiedName().toString());
        return MachineGenerator.lambda(e + " " + variables.entity(),
                invocations.emit(property.accessor(), variables.entity(), List.of()),
                invocations.caught(property.accessor()).map(names::of), true, variables.rethrow(), variables.failure());
    }

    /**
     * The types the accessor lambdas of the companion of {@code model} catch, in the order they first appear: one
     * for each state reader, state writer or identity reader that declares a checked exception, as
     * {@link BoundInvocation#caught} decides; none when no accessor does.
     */
    private static Set<String> caught(SetModel model, BoundInvocation invocations) {
        Set<String> caught = new LinkedHashSet<>();
        for (MachineAccessor accessor : model.machines()) {
            MachineModel machine = accessor.machine();
            for (Binding binding : List.of(machine.stateGetter().accessor(), writer(machine),
                    machine.idGetter().accessor())) {
                invocations.caught(binding).ifPresent(caught::add);
            }
        }
        return caught;
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
        return names.of(STATE_MACHINES) + ".Builder" + MachineGenerator.typeArguments(machine.types(), names);
    }

    /**
     * Allocates every variable of the companion. A field takes its name before anything its class declares
     * inside a method, a parameter before the lambdas of its method, and nothing takes an identifier the file
     * writes at the head of a type reference: so nothing a nested scope declares hides what that scope reads,
     * and no field named after an accessor collides with a field the companion needs for itself.
     */
    private static Variables variables(SetModel model, Written written, BoundInvocation invocations) {
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
            parameters.put(MachineGenerator.DURATION, timeout);
        }
        Map<String, String> dependencies = MachineGenerator.parameterNames(model.dependencies(), taken);
        parameters.putAll(dependencies);
        taken.addAll(dependencies.values());
        Map<String, String> definitions = new LinkedHashMap<>();
        for (MachineAccessor child : children(model)) {
            definitions.put(child.method(), MachineGenerator.unique(child.method() + "Graph", taken));
        }
        String entity = MachineGenerator.unique("entity", taken);
        String state = MachineGenerator.unique("state", taken);
        boolean catches = !caught(model, invocations).isEmpty();
        String failure = catches ? MachineGenerator.unique("failure", taken) : "";
        String rethrow = catches ? MachineGenerator.rethrowName(model.type(), invocations.elements()) : "";
        return new Variables(fields, registry, builders, registryBuilder, used, parameters, timeout, definitions,
                entity, state, setterNames(taken), rethrow, failure);
    }

    /**
     * The names of the parameters of the {@code Builder}'s methods and of the local variable of its {@code build()},
     * by role: each is its role, suffixed as it takes to be free of what {@code taken} holds, which none of them
     * joins.
     */
    private static Map<String, String> setterNames(Set<String> taken) {
        Map<String, String> setters = new LinkedHashMap<>();
        for (String role : SETTER_PARAMETERS) {
            setters.put(role, MachineGenerator.unique(role, new LinkedHashSet<>(taken)));
        }
        return setters;
    }

    /**
     * How the set companion writes its types, from what it claims and what its scope holds already, its own Builder
     * included.
     */
    private static Names names(SetModel model, BoundInvocation invocations) {
        return new Names(model.packageName(), types(model, invocations), Names.Scope.of(invocations.elements(),
                Names.packageOf(model.type()), model.type(), Map.of("Builder", qualifiedName(model) + ".Builder")));
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
     * {@code GraphDefinition} when a machine binds another one, then the fixed ones, then the machines', then, where
     * an accessor lambda catches what an accessor throws, {@code Throwable} and the types the lambdas catch.
     */
    private static Map<String, String> types(SetModel model, BoundInvocation invocations) {
        Map<String, String> types = new LinkedHashMap<>();
        Names.claim(types, model.type());
        Names.claim(types, qualifiedName(model));
        if (!children(model).isEmpty()) {
            Names.claim(types, MachineGenerator.GRAPH_DEFINITION);
        }
        if (model.hasAsync()) {
            for (String fixed : List.of(MACHINE, MachineGenerator.DURATION, CLASS, ILLEGAL_ARGUMENT)) {
                Names.claim(types, fixed);
            }
        }
        for (String fixed : List.of(MACHINE_OBSERVER, MACHINE_REGISTRY, STATE_MACHINES, MachineGenerator.OBJECTS,
                EXECUTOR, INSTANT_SOURCE, MachineGenerator.GENERATED, MachineGenerator.SUPPRESS_WARNINGS,
                MachineGenerator.OVERRIDE, ILLEGAL_STATE)) {
            Names.claim(types, fixed);
        }
        for (MachineAccessor accessor : model.machines()) {
            MachineModel machine = accessor.machine();
            Names.claim(types, machine.types().facade());
            Names.claim(types, MachineGenerator.qualifiedName(machine));
            MachineGenerator.claimMachineTypes(types, machine.types());
        }
        for (TypeElement dependency : model.dependencies()) {
            Names.claim(types, dependency);
        }
        MachineGenerator.claimCaught(types, caught(model, invocations));
        return types;
    }
}
