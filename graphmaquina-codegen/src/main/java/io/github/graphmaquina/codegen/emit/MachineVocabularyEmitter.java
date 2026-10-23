package io.github.graphmaquina.codegen.emit;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.model.GeneratedSource;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Emits the typed vocabulary of one machine: the top-level {@code <Machine>Graph} holding {@code Definition},
 * {@code StatePolicy}, {@code Transition} and {@code Reject} with their repeatable containers, and
 * {@code Recovery}, which is written only inside a {@code Transition}.
 *
 * <p>Every element that names a state is declared with the machine's own state enum and every element that
 * names an edge is declared with its own transition enum, so a constant of another machine, or a state written
 * where an identifier belongs, is a javac error before any processor runs.</p>
 *
 * <p>Every annotation and every element it declares carries a documentation comment. An element's says what the
 * element holds: in an inline {@code {@return}} tag, or, for {@code Definition.afterCommit} and
 * {@code Transition.recoveries}, in a {@code @return} block tag after a description of the element. A {@code javadoc}
 * run over the generated sources, under its default doclint or {@code -Xdoclint:all}, therefore has nothing to warn
 * about in a file the author cannot edit.</p>
 *
 * <p>Nothing is imported: the enums, the machine, the StateGraph types and even {@code java.lang} types are written
 * with their qualified names, so a type of the machine's own package named {@code Class} or {@code String} cannot
 * shadow a name this file uses, and no name this file claims can collide with an import. The vocabulary suppresses
 * the deprecation and removal warnings of the machine and the enums it names, which the author may deprecate and
 * cannot suppress here, as {@link JavaSource#SUPPRESS_DEPRECATION} explains.</p>
 *
 * <p>Each name is still read through its first segment, and any type in scope inside this file under that simple
 * name takes the name over. An annotation nested here shadows a type of that name (JLS 6.4.1) and obscures a package
 * of that name. A type of the machine's package, a top-level type generated in that package, this file included, or
 * a public type of {@code java.lang}, which every compilation unit imports on demand, obscures the package the name
 * starts with (JLS 6.4.2). The bootstrap refuses a declaration whose name a type of the package or of
 * {@code java.lang} would take, and {@link #emit} one whose name a nested annotation or a generated type would take,
 * all with {@code SG603} and before anything is written.</p>
 */
final class MachineVocabularyEmitter {

    private static final List<String> ANNOTATIONS = List.of("Definition", "StatePolicy", "StatePolicies",
        "Transition", "Transitions", "Recovery", "Reject", "Rejects");
    private static final String TYPE_TARGET = "java.lang.annotation.ElementType.TYPE";
    private static final String EDGE_TARGETS =
        "{java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.METHOD}";

    private MachineVocabularyEmitter() {
    }

    /** The canonical name of the vocabulary class generated for {@code graph}, in the facade's own package. */
    static String vocabularyName(GraphDescriptor graph) {
        return SymbolNames.qualified(graph.packageName(), hostName(graph) + "Graph");
    }

    /** The normalized host name, with invalid spellings anchored to their machine declaration. */
    static String hostName(GraphDescriptor graph) {
        try {
            return SymbolNames.hostName(SymbolNames.nameWithin(graph.packageName(), graph.facade()));
        } catch (IllegalArgumentException invalid) {
            throw new CodegenException("SG603", "the machine " + graph.facade() + ": "
                + invalid.getMessage(), graph.origin());
        }
    }

    /** The canonical names of every annotation the vocabulary of {@code graph} declares, in declaration order. */
    static List<String> annotationTypes(GraphDescriptor graph) {
        String vocabulary = vocabularyName(graph);
        return ANNOTATIONS.stream().map((String name) -> vocabulary + '.' + name).toList();
    }

    /**
     * The compilation unit carrying the vocabulary of {@code graph}.
     *
     * @param generated the simple names of the top-level types generated in each package
     * @throws CodegenException {@code SG603} if a nested annotation of the vocabulary, or a top-level type generated
     *     in its package, takes over the first segment of the machine or of one of its enums
     */
    static GeneratedSource emit(GraphDescriptor graph, Map<String, Set<String>> generated) {
        String vocabulary = vocabularyName(graph);
        String simpleName = SymbolNames.nameWithin(graph.packageName(), vocabulary);
        String machine = graph.facade();
        String state = graph.stateType();
        String transition = graph.transitionType();
        requireMachineTypesNameable(graph, simpleName, generated);

        JavaSource source = new JavaSource();
        source.header(graph.packageName(), List.of());
        source.javadoc("Typed graph vocabulary generated for {@link " + machine + "}.",
            "",
            "<p>Generated by the StateGraph code generator. Do not edit.</p>");
        source.line(JavaSource.SUPPRESS_DEPRECATION);
        source.line("public final class " + simpleName + " {");
        source.indent();
        source.line("");
        source.line("private " + simpleName + "() {");
        source.line("}");

        definition(source, machine, state);
        statePolicy(source, simpleName, machine, state);
        statePolicies(source, simpleName, machine);
        transition(source, simpleName, machine, state, transition);
        transitions(source, simpleName, machine);
        recovery(source, simpleName, machine, transition);
        reject(source, simpleName, machine, state, transition);
        rejects(source, simpleName, machine);

        source.outdent();
        source.line("}");
        return new GeneratedSource(vocabulary, source.toString());
    }

    /**
     * Applies {@link ApiEmitter#requireNameable} to the three types the vocabulary {@code simpleName} of
     * {@code graph} names: the machine, its state enum and its transition enum. It refuses one whose first segment a
     * nested annotation of the vocabulary, or a top-level type generated in its package, takes over.
     *
     * @param generated the simple names of the top-level types generated in each package
     */
    private static void requireMachineTypesNameable(GraphDescriptor graph, String simpleName,
            Map<String, Set<String>> generated) {
        String machine = graph.facade();
        String state = graph.stateType();
        String transition = graph.transitionType();
        String unit = "the vocabulary " + simpleName;
        Map<String, String> meanings = ApiEmitter.meanings(simpleName, graph.packageName(), ANNOTATIONS, generated);
        ApiEmitter.requireNameable(unit, meanings, "the machine " + machine, machine, graph.origin());
        ApiEmitter.requireNameable(unit, meanings, "the state type " + state + " of " + machine, state,
            graph.origin());
        ApiEmitter.requireNameable(unit, meanings, "the transition type " + transition + " of " + machine,
            transition, graph.origin());
    }

    private static void definition(JavaSource source, String machine, String state) {
        describe(source, "Declares the graph of {@link " + machine + "}: its initial state, its terminal states,",
            "the states a cycle may return to, the properties reading the entity's identity and state,",
            "and the default hook edges inherit unless they declare their own.");
        annotation(source, machine, "DEFINITION", TYPE_TARGET, null, "Definition");
        element(source, state + " initial();", "the initial state, from which every state must be reachable");
        element(source, state + "[] terminals() default {};",
            "the terminal states, which no edge leaves; none by default");
        element(source, state + "[] allowsCycle() default {};",
            "the states that allow a cycle, one of which every cycle must pass through; none by default");
        element(source, symbolClass("PropertySymbol") + " state();",
            "the property that reads and writes the state of the entity");
        element(source, symbolClass("PropertySymbol") + " id();", "the property that reads the identity of the entity");
        source.javadoc("Default hook for ACTION/MOVE edges; an edge hook replaces it. Empty inherits on an edge.",
            "",
            "@return the default hook, at most one; none by default");
        source.line(symbolClass("HookSymbol") + "[] afterCommit() default {};");
        close(source);
    }

    private static void statePolicy(JavaSource source, String vocabulary, String machine, String state) {
        describe(source, "Gives one state of {@link " + machine + "} the reason every identifier it declares",
            "no edge for is refused with.");
        annotation(source, machine, "STATE_POLICY", TYPE_TARGET, vocabulary + ".StatePolicies",
            "StatePolicy");
        element(source, state + " state();", "the state whose refusals the policy words");
        element(source, "java.lang.String otherwise();",
            "the reason an identifier with no edge from that state is refused with");
        close(source);
    }

    private static void statePolicies(JavaSource source, String vocabulary, String machine) {
        describe(source, "Holds the repeated {@link " + vocabulary + ".StatePolicy} declarations of {@link "
            + machine + "}.");
        annotation(source, machine, "STATE_POLICIES", TYPE_TARGET, null, "StatePolicies");
        element(source, vocabulary + ".StatePolicy[] value();", "the repeated declarations");
        close(source);
    }

    private static void transition(JavaSource source, String vocabulary, String machine, String state,
            String transition) {
        describe(source, "Declares one edge of {@link " + machine + "}, identified by {@code id} from each of",
            "its origins. An absent {@code action} makes the edge a move; a single action makes it an action.");
        annotation(source, machine, "TRANSITION", EDGE_TARGETS, vocabulary + ".Transitions", "Transition");
        element(source, transition + " id();", "the identifier of the edge");
        element(source, state + "[] from();", "the states the edge leaves, at least one");
        element(source, state + " to();", "the state the edge enters");
        element(source, symbolClass("ActionSymbol") + "[] action() default {};",
            "the action of the edge, at most one; none makes the edge a move");
        element(source, symbolClass("GuardSymbol") + "[] guards() default {};",
            "the guards of the edge, run in declaration order; none by default");
        element(source, transition + "[] onFailure() default {};",
            "the fallback transition of a failure no recovery rule takes, at most one; none by default");
        element(source, "java.lang.Class<? extends java.lang.Throwable>[] failsOn() default {};",
            "the throwable types of the action admitted as failures, for {@code onFailure}; none by default");
        source.javadoc("The recovery rules of this edge, each a {@link " + vocabulary + ".Recovery}, which need"
                + " an action.",
            "A failed outcome or a throwable of the action takes the transition of the rule that selects",
            "it, the most specific declared type winning across the rules and {@code failsOn}, and",
            "{@code onFailure} stays the fallback. Each transition is resolved from the edge's origin and",
            "entered without its guards or any recovery of its own.",
            "",
            "@return the recovery rules, none by default");
        source.line(vocabulary + ".Recovery[] recoveries() default {};");
        element(source, "io.github.graphmaquina.annotation.Timeout[] timeout() default {};",
            "the timeout of the asynchronous action, at most one; none by default");
        element(source, symbolClass("HookSymbol") + "[] afterCommit() default {};",
            "the hook run after the edge commits, at most one; none inherits the default hook of the"
                + " {@code Definition}");
        close(source);
    }

    private static void transitions(JavaSource source, String vocabulary, String machine) {
        describe(source, "Holds the repeated {@link " + vocabulary + ".Transition} declarations of {@link "
            + machine + "}.");
        annotation(source, machine, "TRANSITIONS", EDGE_TARGETS, null, "Transitions");
        element(source, vocabulary + ".Transition[] value();", "the repeated declarations");
        close(source);
    }

    private static void recovery(JavaSource source, String vocabulary, String machine, String transition) {
        describe(source, "One recovery rule in the {@code recoveries} of a {@link " + vocabulary + ".Transition} of",
            "{@link " + machine + "}. A failed outcome the edge's action returns with {@code kind}, or a",
            "throwable the action fails with whose most specific declared type is one of {@code failsOn},",
            "none by default, takes {@code transition}, and the call's failed outcome reports",
            "{@code kind} as its failure kind.",
            "",
            "<p>The most specific declared type wins across the whole edge, whether a rule or the edge's",
            "own {@code failsOn} declares it, and declaration order never matters. A type of the edge's",
            "own {@code failsOn}, and a failed outcome that carries no kind or one no rule declares, take",
            "the edge's {@code onFailure}, the fallback, when it declares one. {@code transition} is",
            "resolved from the origin the entity is in, so an edge with that identifier must leave every",
            "origin of the declaring edge. Entered as a recovery, that edge runs without its guards and",
            "applies no rule or fallback of its own: a recovery never chains.</p>");
        annotation(source, machine, "RECOVERY", "{}", null, "Recovery");
        element(source, "java.lang.Class<? extends io.github.graphmaquina.api.FailureKind> kind();",
            "the failure kind the rule takes");
        element(source, transition + " transition();",
            "the transition the rule takes, resolved from the origin the entity is in");
        element(source, "java.lang.Class<? extends java.lang.Throwable>[] failsOn() default {};",
            "the throwable types of the action the rule takes; none by default");
        close(source);
    }

    private static void reject(JavaSource source, String vocabulary, String machine, String state,
            String transition) {
        describe(source, "Refuses one identifier of {@link " + machine + "} from one state, with an explicit",
            "reason that overrides that state's policy.");
        annotation(source, machine, "REJECT", TYPE_TARGET, vocabulary + ".Rejects", "Reject");
        element(source, state + " from();", "the state the identifier is refused from");
        element(source, transition + " transition();", "the identifier refused");
        element(source, "java.lang.String reason();", "the reason the identifier is refused with");
        close(source);
    }

    private static void rejects(JavaSource source, String vocabulary, String machine) {
        describe(source, "Holds the repeated {@link " + vocabulary + ".Reject} declarations of {@link " + machine
            + "}.");
        annotation(source, machine, "REJECTS", TYPE_TARGET, null, "Rejects");
        element(source, vocabulary + ".Reject[] value();", "the repeated declarations");
        close(source);
    }

    /** Writes the blank line and the documentation comment preceding one nested annotation. */
    private static void describe(JavaSource source, String... javadoc) {
        source.line("");
        source.javadoc(javadoc);
    }

    /** Writes the metadata, target, retention and repeatability of one nested annotation and opens its body. */
    private static void annotation(JavaSource source, String machine, String kind, String target,
            String container, String name) {
        source.line("@io.github.graphmaquina.annotation.internal.GeneratedVocabulary(machine = " + machine
            + ".class, value = io.github.graphmaquina.annotation.internal.VocabularyKind." + kind + ")");
        source.line("@java.lang.annotation.Target(" + target + ")");
        source.line("@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)");
        if (container != null) {
            source.line("@java.lang.annotation.Repeatable(" + container + ".class)");
        }
        source.line("public @interface " + name + " {");
        source.indent();
    }

    /**
     * Writes one element of a nested annotation, {@code declaration}, after the documentation comment that says what
     * it holds, {@code returns}, which every public member of a generated file carries: the author cannot comment a
     * generated file, and a {@code javadoc} run warns about an element without one.
     */
    static void element(JavaSource source, String declaration, String returns) {
        source.line("/** {@return " + returns + "} */");
        source.line(declaration);
    }

    /** The type of an element naming a generated symbol of {@code category}, a StateGraph symbol interface. */
    static String symbolClass(String category) {
        return "java.lang.Class<? extends io.github.graphmaquina.symbol." + category + ">";
    }

    private static void close(JavaSource source) {
        source.outdent();
        source.line("}");
    }
}
