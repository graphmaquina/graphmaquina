package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the processor makes of every value an edge declares, pinned whole: the companions it generates from them, byte
 * for byte, as {@link PinnedCompanions} keeps them, and the problems it reports about them, in their order and at
 * the value each one is anchored at.
 */
class TransitionValuesTest {

    /** The machine of the edge-kind fixtures, with an automatic edge, a gate, an external edge and a default hook. */
    private static final String KINDS_HEADER = """
            @DocsGraph.Definition(initial = DRAFT, terminals = RETIRED, allowsCycle = REVIEW,
                state = DocRefs.Status.class, id = DocRefs.Id.class, afterCommit = DocActionsRefs.Notified.class)
            @DocsGraph.StatePolicy(state = DRAFT, otherwise = "a draft is submitted")
            @DocsGraph.StatePolicy(state = REVIEW, otherwise = "a review is published or returned")
            @DocsGraph.StatePolicy(state = PUBLISHED, otherwise = "a publication is retired by the archive")
            @DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT)
            @DocsGraph.Transition(id = FAST, from = DRAFT, to = PUBLISHED, automatic = true)
            @DocsGraph.Transition(id = PEEK, from = REVIEW, to = REVIEW, gate = true)
            @DocsGraph.Transition(id = RETIRE, from = PUBLISHED, to = RETIRED, external = true)
            """;

    /** A plain operation, and one whose automatic edge continues the chain through its failure route. */
    private static final String KINDS_OPERATIONS = """
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED, automatic = true,
                    action = DocActionsRefs.Publish.class, guards = DocActionsRefs.Approved.class,
                    onFailure = RETURN, onFailureContinues = true, failsOn = IllegalStateException.class)
                Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
            """;

    /**
     * Edges each of whose values but its identifier and its states has something wrong, one problem after another in
     * the order an edge is read: the interface's own edge first, then each operation's.
     */
    private static final String WRONG_VALUES = """
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW,
                    action = {DocActionsRefs.Publish.class, DocActionsRefs.AutoPublish.class},
                    failsOn = IllegalStateException.class)
                Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED,
                    action = DocActionsRefs.Publish.class,
                    guards = {DocActionsRefs.Approved.class, DocActionsRefs.Approved.class},
                    timeout = @Timeout(seconds = 2),
                    afterCommit = {DocActionsRefs.Notify.class, DocActionsRefs.Notify.class})
                Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
                @DocsGraph.Transition(id = AUTO_PUBLISH, from = REVIEW, to = PUBLISHED,
                    action = DocActionsRefs.AutoPublish.class, onFailure = {RETURN, RETURN},
                    failsOn = RuntimeException.class, onFailureContinues = true)
                Outcome<Doc, DocStatus, DocTransition> autoPublish(Doc doc);
                @DocsGraph.Transition(id = ARCHIVE, from = PUBLISHED, to = ARCHIVED,
                    action = DocActionsRefs.ArchiveAsync.class, timeout = @Timeout(seconds = 0),
                    recoveries = {@DocsGraph.Recovery(kind = Busy.class, transition = RETURN),
                        @DocsGraph.Recovery(kind = Busy.class, transition = RETURN)})
                Outcome<Doc, DocStatus, DocTransition> archive(Doc doc);
            """;

    /** A watching machine, its peer and the set that holds both: guards, hooks, a timeout and a failure route. */
    @Test
    void generatesTheWatchingMachinesOfASetAsPinned() throws IOException {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY));

        PinnedCompanions.assertGenerated("watching", result);
    }

    /** The example publication: recovery rules, failsOn of the edge and of each rule, and a fallback route. */
    @Test
    void generatesTheRecoveriesOfAnEdgeAsPinned() throws IOException {
        PinnedCompanions.assertGenerated("recoveries", Compilation.compileTyped(RecoveryFixtures.example()));
    }

    /** Automatic edges, a continuing failure route, a gate, an external edge and the hook the machine defaults to. */
    @Test
    void generatesTheKindsOfEdgesAsPinned() throws IOException {
        Map<String, String> sources = TypedFixtures.graph(KINDS_HEADER, KINDS_OPERATIONS);
        sources.put("fx.DocStatus", "package fx;\npublic enum DocStatus { DRAFT, REVIEW, PUBLISHED, RETIRED }\n");
        sources.put("fx.DocTransition",
                "package fx;\npublic enum DocTransition { SUBMIT, PUBLISH, RETURN, FAST, PEEK, RETIRE }\n");

        PinnedCompanions.assertGenerated("kinds", Compilation.compileTyped(sources));
    }

    /**
     * Every wrong value is reported at the value itself, in the order the edges are read, and each edge's problems in
     * the order its values are read, its action, its guards, its timeout, its failure route, its failsOn, its
     * recoveries and its hook, then checked together.
     */
    @Test
    void reportsTheWrongValuesOfEdgesAtEachValueInTheOrderTheyAreRead() {
        Map<String, String> sources = Fixtures.withDocs(Fixtures.DOCS_HEADER
                .replace("@DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT)",
                        "@DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT,\n"
                        + "    timeout = {@Timeout(seconds = 1), @Timeout(seconds = 2)})"), WRONG_VALUES);
        sources.put("fx.Busy",
                "package fx;\npublic record Busy() implements io.github.graphmaquina.api.FailureKind { }\n");

        Compilation.Result result = Compilation.compileTyped(sources);

        assertFalse(result.success(), result.describe());
        assertEquals(List.of(
                "ERROR Docs.java:16:15 [" + DiagnosticCode.SG607 + "] timeout declares 2 values but holds one at most",
                "ERROR Docs.java:19:18 [" + DiagnosticCode.SG607 + "] action declares 2 values but holds one at most",
                "ERROR Docs.java:18:59 [" + DiagnosticCode.SG507 + "] onFailure, failsOn and timeout need an action",
                "ERROR Docs.java:24:73 [" + DiagnosticCode.SG105 + "] the guard fx.DocActionsRefs.Approved is declared"
                        + " twice",
                "ERROR Docs.java:26:23 [" + DiagnosticCode.SG607 + "] afterCommit declares 2 values but holds one at"
                        + " most",
                "ERROR Docs.java:25:19 [" + DiagnosticCode.SG507 + "] timeout is only allowed on an asynchronous"
                        + " action",
                "ERROR Docs.java:29:64 [" + DiagnosticCode.SG607 + "] onFailure declares 2 values but holds one at"
                        + " most",
                "ERROR Docs.java:30:35 [" + DiagnosticCode.SG302 + "] failsOn does not allow"
                        + " java.lang.RuntimeException; only throwable types foreign to the library, and never"
                        + " Throwable, Exception, RuntimeException, an Error or a CancellationException, which are"
                        + " never domain failures, nor CompletionException or ExecutionException, which are unwrapped"
                        + " before matching: declare the type of their cause",
                "ERROR Docs.java:30:64 [" + DiagnosticCode.SG305 + "] onFailureContinues needs an onFailure on REVIEW"
                        + " -AUTO_PUBLISH-> PUBLISHED",
                "ERROR Docs.java:33:63 [" + DiagnosticCode.SG511 + "] timeout must be a positive duration: seconds 0"
                        + " and nanos 0 must not be negative, nanos must be at most 999999999, and the two must not"
                        + " both be zero",
                "ERROR Docs.java:35:44 [" + DiagnosticCode.SG304 + "] failure kind fx.Busy is declared by more than one"
                        + " recover"),
                result.located(), result.describe());
    }
}
