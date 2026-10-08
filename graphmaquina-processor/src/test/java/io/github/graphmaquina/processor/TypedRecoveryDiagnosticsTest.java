package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the processor checks of the recovery rules a {@code Transition} declares in its {@code recoveries}, including
 * admission and source anchors for the edge's own {@code failsOn}. Each problem is reported once, with its SG code,
 * at the value the author wrote, and names a kind or a type by its binary
 * name: SG302 for a throwable type a failsOn may not name, SG303 for a kind that is not a failure kind, SG304 at the
 * second occurrence of a kind or a type one edge declares twice, and SG507 for rules on an edge that runs no action.
 * Whether a rule's transition leaves every origin of its edge is the graph's own SG301, which the outline validator
 * the DSL runs as well reports where the {@code Definition} is, as it reports the SG301 of {@code onFailure}. Kinds
 * and types nested in a class, or held by a dependency's jar, are read as the classes they are, and a kind the
 * facade's package cannot access is javac's to refuse, never an SG303.
 */
class TypedRecoveryDiagnosticsTest {

    /** What SG302 says after the type it refuses. */
    private static final String FORBIDDEN = "; only throwable types foreign to the library, and never Throwable,"
            + " Exception, RuntimeException, an Error or a CancellationException, which are never domain failures,"
            + " nor CompletionException or ExecutionException, which are unwrapped before matching: declare the type"
            + " of their cause";

    /** What SG303 says after the class it refuses. */
    private static final String NOT_A_KIND = " is not a failure kind; a failure kind is a named final class or"
            + " record that implements FailureKind and is not an interface, an enum, a local or anonymous class, or a"
            + " Throwable";

    /** What SG304 says after a throwable type one edge declares twice. */
    private static final String REPEATED = " is declared more than once across recover and failsOn; a throwable type"
            + " selects a single route";

    @TempDir
    Path directory;

    @Test
    void theSpecsExampleCompilesWithoutADiagnostic() {
        Compilation.Result result = Compilation.compileTyped(RecoveryFixtures.example());

        assertTrue(result.success(), result.describe());
        assertEquals(List.of(), result.diagnostics(), result.describe());
    }

    /** A record or a final class, top-level or a member of another class, is a failure kind. */
    @Test
    void everyNamedFinalClassOrRecordIsAFailureKind() {
        Map<String, String> sources = RecoveryFixtures.sources("""
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = {
                            @TicketsGraph.Recovery(kind = Paused.class, transition = SCHEDULE_RETRY),
                            @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                                transition = SCHEDULE_RETRY),
                            @TicketsGraph.Recovery(kind = Throttled.class, transition = SCHEDULE_RETRY),
                            @TicketsGraph.Recovery(kind = Limits.Exceeded.class, transition = SCHEDULE_RETRY)})
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """);
        sources.put("fx.Paused", """
                package fx;
                /** Fixture declaration for recovery diagnostics. */ public record Paused() implements io.github.graphmaquina.api.FailureKind { }
                """);
        sources.put("fx.Throttled", """
                package fx;
                /** Fixture declaration for recovery diagnostics. */ public final class Throttled implements io.github.graphmaquina.api.FailureKind { }
                """);
        sources.put("fx.Limits", """
                package fx;
                /** Fixture declaration for recovery diagnostics. */ public final class Limits {
                    private Limits() { }
                    /** Fixture declaration for recovery diagnostics. */ public static final class Exceeded implements io.github.graphmaquina.api.FailureKind { }
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
        assertEquals(List.of(), result.diagnostics(), result.describe());
    }

    /**
     * A rule's failsOn may name what the edge's own failsOn may name, and nothing else: a catch-all is refused, and so
     * is a cancellation, here a subtype nested in a class.
     */
    @Test
    void sg302ForAThrowableTypeARuleMayNotName() {
        Compilation.Result catchAll = Compilation.compileTyped(RecoveryFixtures.sources("""
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                            transition = SCHEDULE_RETRY, failsOn = {ServiceUnavailableException.class,
                                RuntimeException.class}))
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """));
        Map<String, String> sources = RecoveryFixtures.sources("""
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = @TicketsGraph.Recovery(kind = PublicationFailures.Rejected.class,
                            transition = RECORD_REJECTION, failsOn = Adapter.Halted.class))
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """);
        sources.put("fx.Adapter", """
                package fx;
                /** Fixture declaration for recovery diagnostics. */ public final class Adapter {
                    private Adapter() { }
                    /** Fixture declaration for recovery diagnostics. */ public static final class Halted extends java.util.concurrent.CancellationException { }
                }
                """);
        Compilation.Result cancellation = Compilation.compileTyped(sources);

        assertEquals(List.of("[SG302] recover(fx.PublicationFailures$Unavailable, SCHEDULE_RETRY) does not allow"
                + " java.lang.RuntimeException" + FORBIDDEN), catchAll.errors(), catchAll.describe());
        assertEquals(RecoveryFixtures.PUBLISH_LINE + 4, catchAll.lineOf("SG302"), catchAll.describe());
        assertEquals(List.of("[SG302] recover(fx.PublicationFailures$Rejected, RECORD_REJECTION) does not allow"
                + " fx.Adapter$Halted" + FORBIDDEN), cancellation.errors(), cancellation.describe());
        assertEquals(RecoveryFixtures.PUBLISH_LINE + 3, cancellation.lineOf("SG302"), cancellation.describe());
    }

    /**
     * javac accepts as a kind any class that implements {@code FailureKind}; the processor refuses, at the kind, every
     * one that is not a failure kind: a final class that is a {@code Throwable}, a class that is not final, an
     * abstract one, an interface and an enum.
     */
    @Test
    void sg303ForAKindThatIsNotAFailureKind() {
        Map<String, String> kinds = new LinkedHashMap<>();
        kinds.put("fx.Broken", "/** Deliberately invalid failure-kind declaration. */ public final class Broken extends RuntimeException implements FailureKind { }");
        kinds.put("fx.Open", "/** Deliberately invalid failure-kind declaration. */ public class Open implements FailureKind { }");
        kinds.put("fx.Partial", "/** Deliberately invalid failure-kind declaration. */ public abstract class Partial implements FailureKind { }");
        kinds.put("fx.Outage", "/** Deliberately invalid failure-kind declaration. */ public interface Outage extends FailureKind { }");
        kinds.put("fx.Severity", "/** Deliberately invalid failure-kind declaration. */ public enum Severity implements FailureKind { HIGH }");
        for (Map.Entry<String, String> kind : kinds.entrySet()) {
            Map<String, String> sources = RecoveryFixtures.sources("""
                        @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                            action = TicketActionsRefs.Publish.class,
                            recoveries = @TicketsGraph.Recovery(kind = %s.class,
                                transition = SCHEDULE_RETRY))
                        Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                    """.formatted(kind.getKey()));
            sources.put(kind.getKey(), "package fx;\nimport io.github.graphmaquina.api.FailureKind;\n"
                    + kind.getValue() + "\n");

            Compilation.Result result = Compilation.compileTyped(sources);

            assertEquals(List.of("[SG303] " + kind.getKey() + NOT_A_KIND), result.errors(), result.describe());
            assertEquals(RecoveryFixtures.PUBLISH_LINE + 2, result.lineOf("SG303"), result.describe());
        }
    }

    /** Two rules of one edge never select the same kind; the second rule is refused at its kind. */
    @Test
    void sg304ForAKindTwoRulesDeclare() {
        Compilation.Result result = Compilation.compileTyped(RecoveryFixtures.sources("""
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = {
                            @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                                transition = SCHEDULE_RETRY),
                            @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                                transition = RECORD_REJECTION)})
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """));

        assertEquals(List.of("[SG304] failure kind fx.PublicationFailures$Unavailable is declared by more than one"
                + " recover"), result.errors(), result.describe());
        assertEquals(RecoveryFixtures.PUBLISH_LINE + 5, result.lineOf("SG304"), result.describe());
    }

    /** A kind that is not a failure kind and that two rules name is refused once, where it first appears. */
    @Test
    void sg303AndSg304ForAnInvalidKindTwoRulesDeclare() {
        Map<String, String> sources = RecoveryFixtures.sources("""
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = {
                            @TicketsGraph.Recovery(kind = Open.class,
                                transition = SCHEDULE_RETRY),
                            @TicketsGraph.Recovery(kind = Open.class,
                                transition = RECORD_REJECTION)})
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """);
        sources.put("fx.Open", """
                package fx;
                /** Fixture declaration for recovery diagnostics. */ public class Open implements io.github.graphmaquina.api.FailureKind { }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of("[SG303] fx.Open" + NOT_A_KIND,
                "[SG304] failure kind fx.Open is declared by more than one recover"),
                result.errors(), result.describe());
        assertEquals(RecoveryFixtures.PUBLISH_LINE + 3, result.lineOf("SG303"), result.describe());
        assertEquals(RecoveryFixtures.PUBLISH_LINE + 5, result.lineOf("SG304"), result.describe());
    }

    /**
     * A throwable type selects a single route, so one edge declares it once: twice in one rule, in two rules, or in a
     * rule and in the edge's own failsOn is refused at the second occurrence. Occurrences follow source order, with deterministic rule-then-fallback
     * order only when source is unavailable.
     */
    @Test
    void sg304ForAThrowableTypeOneEdgeDeclaresTwice() {
        Compilation.Result withinARule = Compilation.compileTyped(RecoveryFixtures.sources("""
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                            transition = SCHEDULE_RETRY, failsOn = {ServiceUnavailableException.class,
                                ServiceUnavailableException.class}))
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """));
        Compilation.Result acrossRules = Compilation.compileTyped(RecoveryFixtures.sources("""
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = {
                            @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                                transition = SCHEDULE_RETRY, failsOn = ServiceUnavailableException.class),
                            @TicketsGraph.Recovery(kind = PublicationFailures.Rejected.class,
                                transition = RECORD_REJECTION, failsOn = ServiceUnavailableException.class)})
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """));
        Compilation.Result ruleAndFailsOn = Compilation.compileTyped(RecoveryFixtures.sources("""
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                            transition = SCHEDULE_RETRY, failsOn = ServiceUnavailableException.class),
                        failsOn = {InvalidResponseException.class,
                            ServiceUnavailableException.class},
                        onFailure = MARK_INTEGRATION_FAILED)
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """));

        List<String> refused = List.of("[SG304] fx.ServiceUnavailableException" + REPEATED);
        assertEquals(refused, withinARule.errors(), withinARule.describe());
        assertEquals(RecoveryFixtures.PUBLISH_LINE + 4, withinARule.lineOf("SG304"), withinARule.describe());
        assertEquals(refused, acrossRules.errors(), acrossRules.describe());
        assertEquals(RecoveryFixtures.PUBLISH_LINE + 6, acrossRules.lineOf("SG304"), acrossRules.describe());
        assertEquals(refused, ruleAndFailsOn.errors(), ruleAndFailsOn.describe());
        assertEquals(RecoveryFixtures.PUBLISH_LINE + 5, ruleAndFailsOn.lineOf("SG304"), ruleAndFailsOn.describe());
    }

    /**
     * A type the edge's own failsOn lists twice selects one fallback, so it is still accepted beside rules, and a
     * subtype is distinct from its supertype, so both rule declarations are accepted.
     */
    @Test
    void aRepeatInsideTheEdgesFailsOnAndASubtypeInAnotherRuleAreAccepted() {
        Compilation.Result result = Compilation.compileTyped(RecoveryFixtures.sources("""
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = {
                            @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                                transition = SCHEDULE_RETRY, failsOn = ServiceUnavailableException.class),
                            @TicketsGraph.Recovery(kind = PublicationFailures.Rejected.class,
                                transition = RECORD_REJECTION, failsOn = RateLimitedException.class)},
                        failsOn = {InvalidResponseException.class, InvalidResponseException.class},
                        onFailure = MARK_INTEGRATION_FAILED)
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """));

        assertTrue(result.success(), result.describe());
        assertEquals(List.of(), result.diagnostics(), result.describe());
    }

    /** Only a failure of an action selects a rule, so a move that declares rules is refused, apart from onFailure. */
    @Test
    void sg507ForRulesOnAMove() {
        Map<String, String> sources = RecoveryFixtures.example();
        sources.put("fx.Tickets", sources.get("fx.Tickets").replace(
                "@TicketsGraph.Transition(id = RETRY, from = RETRY_PENDING, to = READY)",
                "@TicketsGraph.Transition(id = RETRY, from = RETRY_PENDING, to = READY,\n"
                        + "        recoveries = @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,"
                        + " transition = CLOSE))"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of("[SG507] recoveries need an action"), result.errors(), result.describe());
        assertEquals(RecoveryFixtures.RETRY_LINE, result.lineOf("SG507"), result.describe());
    }

    @Test
    void sg301ForARuleThatRecoversThroughItsOwnKey() {
        Compilation.Result result = Compilation.compileTyped(RecoveryFixtures.sources("""
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                            transition = PUBLISH))
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """));

        assertEquals(List.of("[SG301] READY -PUBLISH-> PUBLISHED declares recovery PUBLISH, which is its own key"),
                result.errors(), result.describe());
        assertEquals(RecoveryFixtures.DEFINITION_LINE, result.lineOf("SG301"), result.describe());
    }

    /**
     * A rule's transition is resolved from the origin the entity is in, so it must leave every origin of the edge:
     * the edge leaves READY and RETRY_PENDING, and RECORD_REJECTION leaves READY only. Two rules that recover through
     * it give one SG301, for the one origin it does not leave.
     */
    @Test
    void sg301ForTheOneOriginARulesTransitionDoesNotLeave() {
        Compilation.Result result = Compilation.compileTyped(RecoveryFixtures.sources("""
                    @TicketsGraph.Transition(id = PUBLISH, from = {READY, RETRY_PENDING}, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = {
                            @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                                transition = RECORD_REJECTION),
                            @TicketsGraph.Recovery(kind = PublicationFailures.Rejected.class,
                                transition = RECORD_REJECTION)})
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """));

        assertEquals(List.of("[SG301] RETRY_PENDING -PUBLISH-> PUBLISHED declares recovery RECORD_REJECTION, but no"
                + " transition RECORD_REJECTION leaves RETRY_PENDING"), result.errors(), result.describe());
        assertEquals(RecoveryFixtures.DEFINITION_LINE, result.lineOf("SG301"), result.describe());
    }

    /**
     * The kinds and the types a dependency's jar holds, nested in a class of its own, are read from its class files
     * as from sources: valid ones compile, and a refused one is named by its binary name.
     */
    @Test
    void readsTheKindsAndTypesOfADependencysJar() throws IOException {
        List<Path> library = List.of(jar(Map.of("lib.Outages", """
                package lib;
                import io.github.graphmaquina.api.FailureKind;
                /** Fixture declaration for recovery diagnostics. */ public final class Outages {
                    private Outages() { }
                    /** Fixture declaration for recovery diagnostics. */ public record Unavailable() implements FailureKind { }
                    /** Fixture declaration for recovery diagnostics. */ public static class Open implements FailureKind { }
                    /** Fixture declaration for recovery diagnostics. */ public static final class DownException extends RuntimeException {
                        public DownException(String message) {
                            super(message);
                        }
                    }
                }
                """)));

        Compilation.Result valid = Compilation.compileTyped(RecoveryFixtures.sources("""
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = @TicketsGraph.Recovery(kind = lib.Outages.Unavailable.class,
                            transition = SCHEDULE_RETRY, failsOn = lib.Outages.DownException.class))
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """), library);
        Compilation.Result open = Compilation.compileTyped(RecoveryFixtures.sources("""
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = @TicketsGraph.Recovery(kind = lib.Outages.Open.class,
                            transition = SCHEDULE_RETRY))
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """), library);
        Compilation.Result repeated = Compilation.compileTyped(RecoveryFixtures.sources("""
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = @TicketsGraph.Recovery(kind = lib.Outages.Unavailable.class,
                            transition = SCHEDULE_RETRY, failsOn = lib.Outages.DownException.class),
                        failsOn = lib.Outages.DownException.class,
                        onFailure = MARK_INTEGRATION_FAILED)
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """), library);

        assertTrue(valid.success(), valid.describe());
        assertEquals(List.of(), valid.diagnostics(), valid.describe());
        assertEquals(List.of("[SG303] lib.Outages$Open" + NOT_A_KIND), open.errors(), open.describe());
        assertEquals(RecoveryFixtures.PUBLISH_LINE + 2, open.lineOf("SG303"), open.describe());
        assertEquals(List.of("[SG304] lib.Outages$DownException" + REPEATED), repeated.errors(), repeated.describe());
        assertEquals(RecoveryFixtures.PUBLISH_LINE + 4, repeated.lineOf("SG304"), repeated.describe());
    }

    /**
     * The facade writes the class literal of a kind itself, so whether its package can access the kind is javac's to
     * say: a package-private kind of another package is refused with javac's own access error, and the processor
     * adds no SG303, nor any diagnostic of its own.
     */
    @Test
    void javacAloneRefusesAKindTheFacadesPackageCannotAccess() {
        Map<String, String> sources = RecoveryFixtures.sources("""
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = @TicketsGraph.Recovery(kind = billing.Hidden.class,
                            transition = SCHEDULE_RETRY))
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """);
        sources.put("billing.Hidden", """
                package billing;
                /** Fixture declaration for recovery diagnostics. */ record Hidden() implements io.github.graphmaquina.api.FailureKind { }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertFalse(result.success(), result.describe());
        assertTrue(result.diagnostics().stream().anyMatch((Diagnostic<? extends JavaFileObject> diagnostic) ->
                diagnostic.getKind() == Diagnostic.Kind.ERROR
                        && "compiler.err.not.def.public.cant.access".equals(diagnostic.getCode())), result.describe());
        assertEquals(0, result.countError("SG303"), result.describe());
        assertFalse(result.errors().stream().anyMatch((String error) -> error.startsWith("[SG")), result.describe());
    }

    /** Every supported declaration/container form points at each exact offending nested literal. */
    @Test
    void nestedDiagnosticsRetainExactSourceSpansAcrossContainers() {
        String edge = """
                @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                    action = TicketActionsRefs.Publish.class,
                    recoveries = {
                        @TicketsGraph.Recovery(kind = Open.class, transition = SCHEDULE_RETRY,
                            failsOn = {RuntimeException.class, RuntimeException.class}),
                        @TicketsGraph.Recovery(kind = Open.class, transition = RECORD_REJECTION)})
                """;
        for (String form : List.of("method", "repeated-type", "container-type", "container-method", "repeated-method")) {
            Map<String, String> sources = RecoveryFixtures.sources(edge
                    + "Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);\n");
            sources.put("fx.Open", "package fx; /** Nonfinal candidate rejected by SG303. */ "
                    + "public class Open implements io.github.graphmaquina.api.FailureKind {}\n");
            String source = sources.get("fx.Tickets");
            if (form.equals("repeated-type")) {
                source = source.replace("public interface Tickets", edge + "public interface Tickets")
                        .replace(edge + "Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);\n", "");
            } else if (form.equals("container-type")) {
                List<String> transitions = source.lines()
                        .filter((String line) -> line.startsWith("@TicketsGraph.Transition(") && !line.contains("id = PUBLISH"))
                        .toList();
                for (String transition : transitions) {
                    source = source.replace(transition + "\n", "");
                }
                source = source.replace("public interface Tickets", "@TicketsGraph.Transitions({"
                        + String.join(",\n", transitions) + ",\n" + edge.strip()
                        + "})\npublic interface Tickets")
                        .replace(edge + "Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);\n", "");
            } else if (form.equals("container-method")) {
                source = source.replace(edge, "@TicketsGraph.Transitions(" + edge.strip() + ")\n");
            } else if (form.equals("repeated-method")) {
                source = source.replace(edge, "@TicketsGraph.Transition(id = PUBLISH, from = RETRY_PENDING, to = READY)\n" + edge);
            }
            sources.put("fx.Tickets", source);
            Compilation.Result result = Compilation.compileTyped(sources);
            assertEquals(5, result.errors().size(), form + "\n" + result.describe());
            assertLocation(result, "SG303", 0, source, "Open.class", 0);
            assertLocation(result, "SG304", 0, source, "Open.class", 1);
            assertLocation(result, "SG302", 0, source, "RuntimeException.class", 0);
            assertLocation(result, "SG302", 1, source, "RuntimeException.class", 1);
            assertLocation(result, "SG304", 1, source, "RuntimeException.class", 1);
        }
    }

    /** The second meaningful occurrence follows written member order; fallback-only repeats count once. */
    @Test
    void duplicateThrowableAnchorsFollowBothSourceOrders() {
        for (boolean fallbackFirst : List.of(false, true)) {
            for (boolean duplicateFallback : List.of(false, true)) {
                String fallback = "failsOn = {ServiceUnavailableException.class"
                        + (duplicateFallback ? ", ServiceUnavailableException.class" : "") + "},\n";
                String rule = "recoveries = @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,"
                        + " transition = SCHEDULE_RETRY, failsOn = ServiceUnavailableException.class),\n";
                Map<String, String> sources = RecoveryFixtures.sources(
                        "@TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,\n"
                        + "action = TicketActionsRefs.Publish.class,\n"
                        + (fallbackFirst ? fallback + rule : rule + fallback)
                        + "onFailure = MARK_INTEGRATION_FAILED)\n"
                        + "Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);\n");
                Compilation.Result result = Compilation.compileTyped(sources);
                assertEquals(List.of("[SG304] fx.ServiceUnavailableException" + REPEATED), result.errors(), result.describe());
                int occurrence = fallbackFirst && duplicateFallback ? 2 : 1;
                assertLocation(result, "SG304", 0, sources.get("fx.Tickets"), "ServiceUnavailableException.class", occurrence);
            }
        }
    }

    @Test
    void threeRepeatedTypesReportOnlyTheSecondAndFallbackOnlyDuplicatesRemainLegal() {
        Map<String, String> sources = RecoveryFixtures.sources("""
                @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                    action = TicketActionsRefs.Publish.class,
                    recoveries = @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                        transition = SCHEDULE_RETRY, failsOn = {ServiceUnavailableException.class,
                            ServiceUnavailableException.class, ServiceUnavailableException.class}))
                Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """);
        Compilation.Result result = Compilation.compileTyped(sources);
        assertEquals(List.of("[SG304] fx.ServiceUnavailableException" + REPEATED), result.errors(), result.describe());
        assertLocation(result, "SG304", 0, sources.get("fx.Tickets"), "ServiceUnavailableException.class", 1);
        Compilation.Result fallback = Compilation.compileTyped(RecoveryFixtures.sources("""
                @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                    action = TicketActionsRefs.Publish.class,
                    failsOn = {ServiceUnavailableException.class, ServiceUnavailableException.class},
                    onFailure = MARK_INTEGRATION_FAILED)
                Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """));
        assertTrue(fallback.success(), fallback.describe());
        assertEquals(List.of(), fallback.diagnostics(), fallback.describe());
    }

    @Test
    void threeForbiddenTypesHaveThreeAdmissionErrorsAndOneDuplicateError() {
        Map<String, String> sources = RecoveryFixtures.sources("""
                @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                    action = TicketActionsRefs.Publish.class,
                    recoveries = @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                        transition = SCHEDULE_RETRY, failsOn = {RuntimeException.class,
                            RuntimeException.class, RuntimeException.class}))
                Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """);
        Compilation.Result result = Compilation.compileTyped(sources);
        String forbidden = "[SG302] recover(fx.PublicationFailures$Unavailable, SCHEDULE_RETRY) does not allow"
                + " java.lang.RuntimeException" + FORBIDDEN;
        assertEquals(List.of(forbidden, forbidden, forbidden, "[SG304] java.lang.RuntimeException" + REPEATED),
                result.errors(), result.describe());
        for (int occurrence = 0; occurrence < 3; occurrence++) {
            assertLocation(result, "SG302", occurrence, sources.get("fx.Tickets"), "RuntimeException.class", occurrence);
        }
        assertLocation(result, "SG304", 0, sources.get("fx.Tickets"), "RuntimeException.class", 1);
    }

    /** An unreadable sibling leaves SG302 on the full forbidden edge-level literal, in either array order. */
    @Test
    void sg302AnchorsTheForbiddenEdgeTypeBesideAnUnreadableSibling() {
        for (String literals : List.of("RuntimeException.class, Missing.class", "Missing.class, RuntimeException.class")) {
            Map<String, String> sources = RecoveryFixtures.sources("""
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        failsOn = {%s}, onFailure = MARK_INTEGRATION_FAILED)
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                    """.formatted(literals));
            Compilation.Result result = Compilation.compileTyped(sources);

            assertFalse(result.success(), result.describe());
            assertEquals(2, result.errors().size(), result.describe());
            assertEquals(1, result.diagnostics().stream()
                    .filter((Diagnostic<? extends JavaFileObject> diagnostic) -> diagnostic.getKind() == Diagnostic.Kind.ERROR
                            && diagnostic.getCode().startsWith("compiler.err.cant.resolve")
                            && diagnostic.getMessage(null).contains("class Missing"))
                    .count(), result.describe());
            assertEquals(List.of("[SG302] failsOn does not allow java.lang.RuntimeException" + FORBIDDEN),
                    result.errors().stream().filter((String message) -> message.startsWith("[SG")).toList(),
                    result.describe());
            assertLocation(result, "SG302", 0, sources.get("fx.Tickets"), "RuntimeException.class", 0);
        }
    }

    /**
     * javac refuses the unknown constant and the unknown kind itself; the processor adds no SG error for the rules
     * it cannot read.
     */
    @Test
    void anUnknownTransitionConstantIsJavacsErrorAloneAndTheProcessorAddsNone() {
        Compilation.Result result = Compilation.compileTyped(RecoveryFixtures.sources("""
                @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                    action = TicketActionsRefs.Publish.class,
                    recoveries = {
                        @TicketsGraph.Recovery(kind = MissingKind.class, transition = SCHEDULE_RETRY),
                        @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class, transition = UNKNOWN)})
                Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """));
        assertFalse(result.success());
        assertEquals(1, result.diagnostics().stream().filter((Diagnostic<? extends JavaFileObject> diagnostic) ->
                diagnostic.getKind() == Diagnostic.Kind.ERROR
                        && diagnostic.getCode().startsWith("compiler.err.cant.resolve")
                        && diagnostic.getMessage(null).contains("UNKNOWN")).count(), result.describe());
        assertEquals(2, result.errors().size(), result.describe());
        assertEquals(List.of(), result.errors().stream()
                .filter((String message) -> message.startsWith("[SG")).toList(), result.describe());
    }

    /** An unreadable sibling leaves SG302 on the full forbidden literal of a rule, in either array order. */
    @Test
    void sg302AnchorsTheForbiddenRuleTypeBesideAnUnreadableSibling() {
        for (String literals : List.of("RuntimeException.class, Missing.class",
                "Missing.class, RuntimeException.class")) {
            Map<String, String> sources = RecoveryFixtures.sources("""
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                            transition = SCHEDULE_RETRY, failsOn = {%s}))
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                    """.formatted(literals));
            Compilation.Result result = Compilation.compileTyped(sources);

            assertFalse(result.success(), result.describe());
            assertEquals(1, result.diagnostics().stream()
                    .filter((Diagnostic<? extends JavaFileObject> diagnostic) ->
                            diagnostic.getKind() == Diagnostic.Kind.ERROR
                            && diagnostic.getCode().startsWith("compiler.err.cant.resolve")
                            && diagnostic.getMessage(null).contains("class Missing"))
                    .count(), result.describe());
            assertEquals(List.of("[SG302] recover(fx.PublicationFailures$Unavailable, SCHEDULE_RETRY) does not allow"
                    + " java.lang.RuntimeException" + FORBIDDEN),
                    result.errors().stream().filter((String message) -> message.startsWith("[SG")).toList(),
                    result.describe());
            assertLocation(result, "SG302", 0, sources.get("fx.Tickets"), "RuntimeException.class", 0);
        }
    }

    /** Pins the full expression span and javac's preferred class-literal position, including repeated text. */
    private static void assertLocation(Compilation.Result result, String code, int diagnosticIndex,
            String source, String literal, int occurrence) {
        List<Diagnostic<? extends JavaFileObject>> matching = result.diagnostics().stream()
                .filter((Diagnostic<? extends JavaFileObject> diagnostic) -> diagnostic.getKind() == Diagnostic.Kind.ERROR
                        && diagnostic.getMessage(null).startsWith("[" + code + "]"))
                .sorted(java.util.Comparator.comparing((Diagnostic<? extends JavaFileObject> diagnostic) -> diagnostic.getMessage(null)))
                .toList();
        assertTrue(matching.size() > diagnosticIndex, result.describe());
        Diagnostic<? extends JavaFileObject> diagnostic = matching.get(diagnosticIndex);
        int start = -1;
        for (int at = 0; at <= occurrence; at++) {
            start = source.indexOf(literal, start + 1);
            assertTrue(start >= 0, literal + " occurrence " + occurrence);
        }
        int preferred = start + literal.lastIndexOf('.');
        long line = source.substring(0, preferred).chars().filter((int character) -> character == '\n').count() + 1;
        long column = preferred - source.lastIndexOf('\n', preferred);
        assertEquals(line, diagnostic.getLineNumber(), result.describe());
        assertEquals(column, diagnostic.getColumnNumber(), result.describe());
        assertEquals(start, diagnostic.getStartPosition(), result.describe());
        assertEquals(start + literal.length(), diagnostic.getEndPosition(), result.describe());
        assertEquals(literal, source.substring((int) diagnostic.getStartPosition(), (int) diagnostic.getEndPosition()));
    }

    /** {@code sources} compiled on their own, without annotation processing, and packaged as a jar. */
    private Path jar(Map<String, String> sources) throws IOException {
        Path root = directory.resolve("library-sources");
        Path classes = Files.createDirectories(directory.resolve("library-classes"));
        List<Path> files = new ArrayList<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = root.resolve(source.getKey().replace('.', File.separatorChar) + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
            files.add(file);
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            List<String> options = List.of("-proc:none", "-classpath", System.getProperty("java.class.path"),
                    "-d", classes.toString());
            assertTrue(compiler.getTask(null, manager, null, options, null,
                    manager.getJavaFileObjectsFromPaths(files)).call(), "the library does not compile");
        }
        Path jar = directory.resolve("library.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
                Stream<Path> walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace(File.separatorChar, '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        return jar;
    }
}
