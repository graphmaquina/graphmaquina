package io.github.graphmaquina.annotation.internal;

/**
 * Every diagnostic code the library reports, declared once, so that two changes cannot give one number two meanings
 * and no report prints a code that does not exist. A constant's name is the code as diagnostics print it, such as
 * {@code [SG603]}, and a public surface that carries a code, such as {@code InvalidGraphException.Problem}, carries
 * that name. The constants follow the numeric order, and {@code docs/diagnostics.md} documents each of them: a test
 * keeps the two in step and refuses the spelling of a code anywhere but here in the main and test sources of every
 * module, except on a line that is a comment.
 *
 * <p>A new code takes the next free number of its block. A number once used and then removed is never given again,
 * so that an old build log never reads as a new problem; the test of this registry lists the retired numbers.
 */
public enum DiagnosticCode {

    /** Duplicate {@code (from, id)} key, or a state that declares two policies or rejects one identifier twice. */
    SG102,

    /** Blank reason on a policy or a rejection. */
    SG103,

    /** Duplicate guard on one transition, or two guards of one transition with the same name. */
    SG105,

    /** DSL callback with no inferable name. */
    SG106,

    /** Blank DSL callback or condition name. */
    SG107,

    /** A state's automatic order is missing, repeated, incomplete or names an identifier that is not automatic. */
    SG108,

    /** In an automatic order, an edge without guards comes before another edge. */
    SG109,

    /** Missing or repeated DSL initial declaration. */
    SG201,

    /** A terminal declares an outgoing edge, including a self-loop, or a non-terminal declares none. */
    SG202,

    /** Unreachable state. */
    SG203,

    /** Unauthorised cycle. */
    SG204,

    /** Unused cycle allowance. */
    SG205,

    /** The automatic edges and the continuing failure routes form a cycle. */
    SG206,

    /** Invalid failure route. */
    SG301,

    /** Forbidden throwable type in {@code failsOn} or in a recovery rule. */
    SG302,

    /** A recovery rule names a class that is not a failure kind. */
    SG303,

    /** Ambiguous recovery rules. */
    SG304,

    /** A continuing failure route leads to a state with no automatic edge. */
    SG305,

    /** A watch rule names an identifier with no edge from the watched state. */
    SG401,

    /** A terminal state declares a watch. */
    SG402,

    /** More than one watch on the same state. */
    SG403,

    /** A watch has no rules. */
    SG404,

    /** A watch declared with annotations does not declare exactly one of {@code owner} and {@code ownerLookup}. */
    SG405,

    /** Invalid machine or set declaration. */
    SG502,

    /** Missing, incompatible or ambiguous public property accessor. */
    SG503,

    /** Invalid operation annotation or signature. */
    SG504,

    /** Exported method does not uniquely fit its role. */
    SG505,

    /** Invalid transition elements. */
    SG507,

    /** Watch rule must choose exactly one supported predicate alternative. */
    SG508,

    /** Invalid peer-machine type, or a peer or sub-machine whose types a package cannot access. */
    SG509,

    /** Invalid set accessor, duplicate or missing machine, or missing watched peer in the set. */
    SG510,

    /** Invalid timeout duration. */
    SG511,

    /** A {@code Check} names an identifier no operation of its facade executes. */
    SG512,

    /** A check does not repeat the parameters of the operation it checks. */
    SG513,

    /** Two checks name the same operation, or one method carries both a {@code Transition} and a {@code Check}. */
    SG514,

    /** An automatic transition's identifier runs an operation that takes an input, which an automatic attempt lacks. */
    SG515,

    /** The processor could not write a generated source, provisional export index or processor inventory. */
    SG599,

    /** Bootstrap declaration cannot be resolved, is ambiguous or contradicts another declaration. */
    SG601,

    /**
     * A manifest, an inventory or an export index is missing, incompatible, stale or malformed, or the export indexes
     * of two classpath entries describe one machine, or one describes a machine of this compilation.
     */
    SG602,

    /** Generated output or export collision, a name generated code cannot write, or a forbidden output path. */
    SG603,

    /** Invalid or contradictory export or role, or a host its generated references cannot access or name. */
    SG604,

    /** Symbol has no valid original member, or belongs to the wrong host or role. */
    SG605,

    /** Graph vocabulary that belongs to another machine or pair, or sits where no machine reads it. */
    SG606,

    /** Too many values in an optional singleton element. */
    SG607,

    /**
     * Invalid, duplicated, unused or undeclared peer, a peer that is no machine of this compilation or of a dependency,
     * or one its owner cannot name. A {@code @GenerateGraphApi} machine of a dependency whose export index is missing
     * or does not list it is {@link #SG602}.
     */
    SG608,

    /** A non-terminal state declares no rejection policy. */
    SG701,

    /** One {@code (from, transition)} key both declares an edge and rejects the identifier. */
    SG702,

    /** Two annotations of one method name different identifiers, or two methods claim the same identifier. */
    SG703,

    /** A sub-machine's exits do not match the terminal states of its child. */
    SG801,

    /** A sub-machine's exit names an identifier with no edge from the state the sub-machine is bound to. */
    SG802,

    /** A terminal state holds a sub-machine, or one state holds more than one. */
    SG803,

    /** An identifier a sub-machine's exit names is also named from the bound state by a route or a watch rule. */
    SG804,

    /** A machine's sub-machines contain the machine again. */
    SG805,

    /**
     * A sub-machine declared with annotations lacks the ways between its entities that it needs, or a set holds a
     * machine but not the machine bound to one of its states.
     */
    SG806,

    /** An automatic edge leaves a state that holds a sub-machine. */
    SG807
}
