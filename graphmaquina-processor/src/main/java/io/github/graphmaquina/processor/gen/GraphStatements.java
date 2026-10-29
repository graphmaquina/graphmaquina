package io.github.graphmaquina.processor.gen;

import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.processor.gen.MachineGenerator.Variables;
import io.github.graphmaquina.processor.model.AutomaticOrderModel;
import io.github.graphmaquina.processor.model.Binding;
import io.github.graphmaquina.processor.model.MachineModel;
import io.github.graphmaquina.processor.model.MachineTypes;
import io.github.graphmaquina.processor.model.Property;
import io.github.graphmaquina.processor.model.RecoveryModel;
import io.github.graphmaquina.processor.model.RejectionModel;
import io.github.graphmaquina.processor.model.RuleModel;
import io.github.graphmaquina.processor.model.StatePolicyModel;
import io.github.graphmaquina.processor.model.SubmachineModel;
import io.github.graphmaquina.processor.model.TransitionModel;
import io.github.graphmaquina.processor.model.WatchModel;
import io.github.graphmaquina.processor.model.WatchOwner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Writes the {@code graph(...)} of a machine's companion and, for a graph of more than
 * {@value MachineGenerator#DECLARATIONS_PER_METHOD} declarations, the private methods {@code graphPart1},
 * {@code graphPart2}, … that make those declarations in place of {@code graph(...)}, as {@link MachineGenerator}
 * describes them.
 */
final class GraphStatements {

    private GraphStatements() { }

    /** Writes {@code graph(...)}, and after it the private methods that declare part of the graph, if any. */
    static void graph(MachineModel model, Names names, Variables variables, BoundInvocation invocations,
                      SourceWriter out) {
        String objects = names.qualifier(MachineGenerator.OBJECTS);
        Map<String, String> parameters = variables.parameters();
        List<String> declarations = new ArrayList<>();
        List<String> declared = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        for (MachineTypes child : model.children()) {
            String name = variables.definitions().get(child.facade().getQualifiedName().toString());
            declarations.add(MachineGenerator.definitionType(child, names) + " " + name);
            declared.add(name);
            tags.add("@param " + name + " the definition of {@code " + child.facade().getQualifiedName()
                    + "}, which the graph binds as a sub-machine");
        }
        for (TypeElement dependency : model.instanceDependencies()) {
            String name = parameters.get(dependency.getQualifiedName().toString());
            declarations.add(names.of(dependency.getQualifiedName().toString()) + " " + name);
            declared.add(name);
            tags.add("@param " + name + " the {@code " + dependency.getQualifiedName()
                    + "} whose instance members the graph calls");
        }
        tags.add("@return the definition");
        String builder = names.of(MachineGenerator.GRAPH_BUILDER)
                + MachineGenerator.typeArguments(model.types(), names);
        List<List<String>> statements = statements(model, names, variables, invocations);
        out.javadoc("The definition, built from the annotations of {@link "
                + names.of(model.types().facade().getQualifiedName().toString()) + "}. "
                + (model.children().isEmpty() ? "One parameter"
                        : "One parameter per machine it binds as a sub-machine, which takes its definition, then one")
                + " per class with instance members.", tags);
        out.open("public static " + MachineGenerator.definitionType(model.types(), names) + " graph("
                + String.join(", ", declarations) + ")");
        for (String name : declared) {
            out.line(objects + ".requireNonNull(" + name + ", \"" + name + "\");");
        }
        out.line(builder + " " + variables.builder() + " = " + names.qualifier(MachineGenerator.STATE_GRAPH)
                + ".define(KEY);");
        declareAndBuild(statements, declared, variables, out);
        partMethods(statements, declarations, builder, variables, out);
    }

    /**
     * Writes the rest of {@code graph(...)}: its {@code statements}, or, for a graph too large for one method, a
     * call of each private method that makes part of them, which takes the builder and the parameters
     * {@code declared}, then the statement that builds the definition.
     */
    private static void declareAndBuild(List<List<String>> statements, List<String> declared, Variables variables,
                                        SourceWriter out) {
        String graph = variables.builder();
        int parts = parts(statements.size());
        if (parts == 0) {
            declare(statements, graph, out);
        }
        String arguments = declared.isEmpty() ? "" : ", " + String.join(", ", declared);
        for (int part = 1; part <= parts; part++) {
            out.line(graph + " = " + variables.part() + part + "(" + graph + arguments + ");");
        }
        out.line("return " + graph + ".build();");
        out.close();
    }

    /**
     * Writes the private methods that make {@code statements} for a graph too large for {@code graph(...)}, each
     * taking the builder, typed {@code builder}, then the parameters {@code declarations} of {@code graph(...)}, and
     * returning the builder; none for a graph {@code graph(...)} declares itself.
     */
    private static void partMethods(List<List<String>> statements, List<String> declarations, String builder,
                                    Variables variables, SourceWriter out) {
        String graph = variables.builder();
        int parts = parts(statements.size());
        String parameterList = declarations.isEmpty() ? "" : ", " + String.join(", ", declarations);
        for (int part = 1; part <= parts; part++) {
            int first = (part - 1) * MachineGenerator.DECLARATIONS_PER_METHOD;
            int last = Math.min(statements.size(), first + MachineGenerator.DECLARATIONS_PER_METHOD);
            out.blank();
            out.line("/** Declarations " + (first + 1) + " to " + last + " of the definition {@code graph(...)} builds,"
                    + " in its order. */");
            out.open("private static " + builder + " " + variables.part() + part + "(" + builder + " " + graph
                    + parameterList + ")");
            declare(statements.subList(first, last), graph, out);
            out.line("return " + graph + ";");
            out.close();
        }
    }

    /**
     * Writes each of {@code statements} as one statement that hands the builder {@code graph} holds to that
     * declaration's chain and keeps the builder the chain returns, so no chain is longer than one declaration's.
     */
    private static void declare(List<List<String>> statements, String graph, SourceWriter out) {
        for (List<String> statement : statements) {
            for (int line = 0; line < statement.size(); line++) {
                String text = (line == 0 ? graph + " = " + graph : "") + statement.get(line)
                        + (line == statement.size() - 1 ? ";" : "");
                if (line == 1) {
                    out.indent().indent();
                }
                out.line(text);
            }
            if (statement.size() > 1) {
                out.outdent().outdent();
            }
        }
    }

    /**
     * The number of private methods that declare the graph of {@code declarations} declarations: none when
     * {@code graph(...)} makes them itself, which it does up to {@link MachineGenerator#DECLARATIONS_PER_METHOD}.
     */
    static int parts(int declarations) {
        return declarations <= MachineGenerator.DECLARATIONS_PER_METHOD
                ? 0 : (declarations + MachineGenerator.DECLARATIONS_PER_METHOD - 1)
                        / MachineGenerator.DECLARATIONS_PER_METHOD;
    }

    /**
     * The declarations the companion makes on the graph's builder, in the order the DSL takes them: the initial
     * state, the terminals, the cycle allowances, the policies, the rejections, each edge from each of its origins,
     * the automatic orders, the watches and the sub-machines. Each declaration is the lines of its chain, the first
     * starting with the call that opens it and every other with a call it makes on what the previous one returned.
     */
    private static List<List<String>> statements(MachineModel model, Names names, Variables variables,
                                                 BoundInvocation invocations) {
        TypeElement states = model.types().statesElement();
        TypeElement transitions = model.types().transitionsElement();
        List<List<String>> statements = new ArrayList<>();
        statements.add(List.of(".initial(" + MachineGenerator.constant(names, states, model.initial()) + ")"));
        for (String terminal : model.terminals()) {
            statements.add(List.of(".terminal(" + MachineGenerator.constant(names, states, terminal) + ")"));
        }
        for (String state : model.cycleAllowed()) {
            statements.add(List.of(".allowsCycle(" + MachineGenerator.constant(names, states, state) + ")"));
        }
        for (StatePolicyModel policy : model.policies()) {
            statements.add(List.of(".statePolicy(" + MachineGenerator.constant(names, states, policy.state()) + ", "
                    + MachineGenerator.quote(policy.otherwise()) + ")"));
        }
        for (RejectionModel rejection : model.rejections()) {
            statements.add(List.of(".reject(" + MachineGenerator.constant(names, states, rejection.from()) + ", "
                    + MachineGenerator.constant(names, transitions, rejection.transition()) + ", "
                    + MachineGenerator.quote(rejection.reason()) + ")"));
        }
        for (TransitionModel transition : model.allTransitions()) {
            for (String from : transition.from()) {
                statements.add(List.of(transition(model, names, variables, invocations, transition, from)));
            }
        }
        for (AutomaticOrderModel order : model.automaticOrders()) {
            statements.add(List.of(".automaticOrder(" + MachineGenerator.constant(names, states, order.from()) + ", "
                    + identifiers(names, transitions, order.transitions()) + ")"));
        }
        for (WatchModel watch : model.watches()) {
            statements.add(watch(model, names, variables, invocations, watch));
        }
        for (SubmachineModel submachine : model.submachines()) {
            statements.add(submachine(model, names, variables, invocations, submachine));
        }
        return statements;
    }

    /** How many declarations {@link #statements} makes for {@code model}, counted before any of them is written. */
    static int declarations(MachineModel model) {
        int declarations = 1 + model.terminals().size() + model.cycleAllowed().size() + model.policies().size()
                + model.rejections().size() + model.automaticOrders().size() + model.watches().size()
                + model.submachines().size();
        for (TransitionModel transition : model.allTransitions()) {
            declarations += transition.from().size();
        }
        return declarations;
    }

    /** The chain of one edge from {@code from}, from the call that opens it to the one that closes it. */
    private static String transition(MachineModel model, Names names, Variables variables,
                                     BoundInvocation invocations, TransitionModel transition, String from) {
        TypeElement states = model.types().statesElement();
        TypeElement transitions = model.types().transitionsElement();
        List<String> context = List.of(MachineGenerator.unique("context", variables.taken()));
        List<String> entity = List.of(variables.entity());
        StringBuilder line = new StringBuilder(".transition("
                + MachineGenerator.constant(names, transitions, transition.transitionId()) + ", "
                + MachineGenerator.constant(names, states, from) + ", "
                + MachineGenerator.constant(names, states, transition.to()) + ")");
        if (transition.automatic()) {
            line.append(".automatic()");
        }
        if (transition.kind() == TransitionKind.ACTION) {
            Binding action = transition.action().orElseThrow();
            line.append(action.async() ? ".asyncAction(" : ".action(").append(MachineGenerator.quote(transition.name()))
                    .append(", ").append(lambda(action, context, true, variables, names, invocations)).append(')');
            transition.timeout().ifPresent((Duration timeout) -> line.append(".timeout(")
                    .append(names.qualifier(MachineGenerator.DURATION)).append(".parse(\"").append(timeout)
                    .append("\"))"));
        }
        for (Binding guard : transition.guards()) {
            line.append(guard.async() ? ".asyncGuard(" : ".guard(").append(MachineGenerator.quote(guard.methodName()))
                    .append(", ").append(lambda(guard, context, true, variables, names, invocations)).append(')');
        }
        failureRoutes(line, names, transitions, transition);
        transition.afterCommit().ifPresent((Binding hook) -> {
            boolean contextual = hook.arguments().size() == 2;
            line.append(contextual ? ".afterCommitWith(" : ".afterCommit(")
                    .append(MachineGenerator.quote(hook.methodName())).append(", ")
                    .append(lambda(hook, contextual ? hookParameters(entity.get(0), variables) : entity, false,
                            variables, names, invocations))
                    .append(')');
        });
        return line.append(".endTransition()").toString();
    }

    /**
     * The calls that name the routes a failure of {@code transition} takes: its {@code onFailure}, which continues the
     * chain of automatic attempts when the edge says so, its {@code failsOn} types and its recovery rules, each of
     * which continues the chain when it says so.
     */
    private static void failureRoutes(StringBuilder line, Names names, TypeElement transitions,
                                      TransitionModel transition) {
        transition.onFailure().ifPresent((String recovery) -> line
                .append(transition.onFailureContinues() ? ".onFailureAndContinue(" : ".onFailure(")
                .append(MachineGenerator.constant(names, transitions, recovery)).append(')'));
        for (TypeMirror type : transition.failsOn()) {
            line.append(".failsOn(").append(TypeSource.render(type)).append(".class)");
        }
        for (RecoveryModel recovery : transition.recoveries()) {
            line.append(recovery.continueChain() ? ".recoverAndContinue(" : ".recover(")
                    .append(TypeSource.render(recovery.kind())).append(".class, ")
                    .append(MachineGenerator.constant(names, transitions, recovery.transition()))
                    .append(recoveryTypes(names, recovery.failsOn())).append(')');
        }
    }

    /**
     * {@code List.of(A, B)} over the identifiers {@code ids} of {@code transitions}, in their order, with
     * {@code List} written as the file writes the type it claimed.
     */
    private static String identifiers(Names names, TypeElement transitions, List<String> ids) {
        List<String> constants = new ArrayList<>();
        for (String id : ids) {
            constants.add(MachineGenerator.constant(names, transitions, id));
        }
        return names.qualifier(MachineGenerator.LIST) + ".of(" + String.join(", ", constants) + ")";
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
                : ", " + names.qualifier(MachineGenerator.LIST) + ".of(" + String.join(", ", classes) + ")";
    }

    /**
     * The chain of one watch: the call that opens it, with the lambda that reads the peers of the entity and then the
     * one that finds the owner of a peer, one line per rule, and the call that closes it.
     */
    private static List<String> watch(MachineModel model, Names names, Variables variables,
                                      BoundInvocation invocations, WatchModel watch) {
        String e = names.of(model.types().entityElement().getQualifiedName().toString());
        String p = names.of(watch.peer().entityElement().getQualifiedName().toString());
        String q = names.of(watch.peer().statesElement().getQualifiedName().toString());
        String u = names.of(watch.peer().transitionsElement().getQualifiedName().toString());
        TypeElement peerStates = watch.peer().statesElement();
        String entity = variables.entity();
        List<String> arguments = conditionParameters(entity, variables);
        String peer = MachineGenerator.unique(MachineGenerator.decapitalize(watch.peer().entityName()),
                variables.taken());
        String peers = MachineGenerator.lambda(e + " " + entity, entity + "." + watch.peers().method() + "()",
                invocations.caught(watch.peers().accessor()).map(names::of), true, variables.rethrow(),
                variables.failure());
        String owners = switch (watch.owner()) {
            case WatchOwner.Read read -> ownerReader(model, read, watch.state(), p, peer, variables, names,
                    invocations);
            case WatchOwner.Lookup lookup -> lookup(lookup.binding(), p, peer, variables, names, invocations);
        };
        List<String> lines = new ArrayList<>();
        lines.add(".watch(" + MachineGenerator.constant(names, model.types().statesElement(), watch.state()) + ", "
                + names.qualifier(MachineGenerator.MACHINE_KEY) + ".of(" + p + ".class, " + q + ".class, " + u
                + ".class), " + peers + ", " + owners + ")");
        for (RuleModel rule : watch.rules()) {
            String condition = switch (rule.kind()) {
                case CONDITION -> MachineGenerator.quote(rule.condition().orElseThrow().methodName()) + ", "
                        + lambda(rule.condition().orElseThrow(), arguments, true, variables, names, invocations);
                case ALL_IN -> names.qualifier(MachineGenerator.WATCH_CONDITION) + ".allIn("
                        + MachineGenerator.constant(names, peerStates, rule.argument()) + ")";
                case ANY_IN -> names.qualifier(MachineGenerator.WATCH_CONDITION) + ".anyIn("
                        + MachineGenerator.constant(names, peerStates, rule.argument()) + ")";
                case NONE_IN -> names.qualifier(MachineGenerator.WATCH_CONDITION) + ".noneIn("
                        + MachineGenerator.constant(names, peerStates, rule.argument()) + ")";
            };
            lines.add(".when(" + condition + ").execute("
                    + MachineGenerator.constant(names, model.types().transitionsElement(), rule.transition()) + ")");
        }
        lines.add(".endWatch()");
        return lines;
    }

    /**
     * The lambda that reads the owner of a peer entity, typed {@code p}, through {@code read}, the owner property of
     * the watch of {@code state}. A property that returns the owner itself, rather than an {@code Optional} of it, is
     * wrapped in one after a {@code requireNonNull} whose message is the engine's for an {@code ownerOf} that returns
     * null: absence is an empty {@code Optional}, never null.
     */
    private static String ownerReader(MachineModel model, WatchOwner.Read read, String state, String p, String peer,
                                      Variables variables, Names names, BoundInvocation invocations) {
        String call = peer + "." + read.property().method() + "()";
        String refusal = "the ownerOf of " + model.types().entityName() + "/" + model.types().statesName() + "."
                + state + " returned null";
        String owner = read.optional()
                ? call
                : names.qualifier(MachineGenerator.OPTIONAL) + ".of(" + names.qualifier(MachineGenerator.OBJECTS)
                        + ".requireNonNull(" + call + ", " + MachineGenerator.quote(refusal) + "))";
        return MachineGenerator.lambda(p + " " + peer, owner,
                invocations.caught(read.property().accessor()).map(names::of), true, variables.rethrow(),
                variables.failure());
    }

    /**
     * One sub-machine: the state, the child's definition and, for a child over another entity, the way to the child
     * and the way back, then its exits in declaration order.
     */
    private static List<String> submachine(MachineModel model, Names names, Variables variables,
                                           BoundInvocation invocations, SubmachineModel submachine) {
        MachineTypes child = submachine.child();
        String definition = variables.definitions().get(child.facade().getQualifiedName().toString());
        StringBuilder line = new StringBuilder(".submachine("
                + MachineGenerator.constant(names, model.types().statesElement(), submachine.state()) + ", "
                + definition);
        if (submachine.childOf().isPresent()) {
            String e = names.of(model.types().entityElement().getQualifiedName().toString());
            String p = names.of(child.entityElement().getQualifiedName().toString());
            String entity = variables.entity();
            String peer = MachineGenerator.unique(MachineGenerator.decapitalize(child.entityName()), variables.taken());
            Property childOf = submachine.childOf().get();
            line.append(", ").append(MachineGenerator.lambda(e + " " + entity, entity + "." + childOf.method() + "()",
                    invocations.caught(childOf.accessor()).map(names::of), true, variables.rethrow(),
                    variables.failure()));
            String back = submachine.parentOf()
                    .map((Property parentOf) -> MachineGenerator.lambda(p + " " + peer,
                            peer + "." + parentOf.method() + "()",
                            invocations.caught(parentOf.accessor()).map(names::of), true, variables.rethrow(),
                            variables.failure()))
                    .orElseGet(() -> lookup(submachine.parentLookup().orElseThrow(), p, peer, variables, names,
                            invocations));
            line.append(", ").append(back);
        }
        List<String> lines = new ArrayList<>();
        lines.add(line.append(')').toString());
        for (SubmachineModel.Exit exit : submachine.exits()) {
            lines.add(".exit(" + MachineGenerator.constant(names, child.statesElement(), exit.on()) + ", "
                    + MachineGenerator.constant(names, model.types().transitionsElement(), exit.transition()) + ")");
        }
        lines.add(".endSubmachine()");
        return lines;
    }

    /**
     * The lambda that hands an entity, typed {@code p}, to {@code binding}, an exported lookup: the
     * {@code parentLookup} of a sub-machine, which finds the parent of its child, or the {@code ownerLookup} of a
     * watch, which finds the owner of its peer. The lookup is called on the host class for a static member and on the
     * injected parameter for an instance one, and its {@code Optional} is handed to the DSL as it is.
     */
    private static String lookup(Binding binding, String p, String peer, Variables variables, Names names,
                                 BoundInvocation invocations) {
        String receiver = binding.isStatic()
                ? names.qualifier(binding.owner().getQualifiedName().toString())
                : variables.parameters().get(binding.owner().getQualifiedName().toString());
        return MachineGenerator.lambda(p + " " + peer, invocations.emit(binding, receiver, List.of(peer)),
                invocations.caught(binding).map(names::of), true, variables.rethrow(), variables.failure());
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
        return MachineGenerator.lambda(String.join(", ", arguments), invocations.emit(binding, receiver, arguments),
                invocations.caught(binding).map(names::of), returns, variables.rethrow(), variables.failure());
    }

    /**
     * The three parameters of a condition lambda. They are named against one another as well as against the
     * enclosing method, because an entity type named {@code Peers} or {@code View} would otherwise give the
     * lambda two parameters of one name.
     */
    private static List<String> conditionParameters(String entity, Variables variables) {
        Set<String> taken = variables.taken();
        return List.of(MachineGenerator.unique(entity, taken), MachineGenerator.unique("peers", taken),
                MachineGenerator.unique("view", taken));
    }

    /**
     * Names of a contextual hook lambda's parameters, distinct from each other and from the enclosing method's,
     * because an entity type named {@code Commit} would otherwise give the lambda two parameters of one name.
     */
    private static List<String> hookParameters(String entity, Variables variables) {
        Set<String> taken = variables.taken();
        return List.of(MachineGenerator.unique(entity, taken), MachineGenerator.unique("commit", taken));
    }
}
