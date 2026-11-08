package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A consumer on Java 25 may reach the JDK types of its machines through a single-module-import declaration instead of
 * single-type imports: here the entity's identifier is a {@code UUID}, the guard and the action return a
 * {@code CompletionStage} and the action's {@code failsOn} names {@code IOException}, all through
 * {@code import module java.base;}. The generator and the processor read those names as javac does, so the machine
 * compiles at release 25 under the project's strict options and the failure route runs. A JDK below 25 compiles no
 * module import, so the test runs on JDK 25 or later only.
 */
class ModuleImportCompilationTest {

    /** The entity, whose identifier is a {@code UUID} reached through the module import. */
    private static final String DOC = """
            package fx;
            import module java.base;
            import io.github.graphmaquina.annotation.GraphProperty;
            public final class Doc {
                @GraphProperty private DocStatus status = DocStatus.DRAFT;
                @GraphProperty public UUID id() { return UUID.nameUUIDFromBytes(new byte[] {1}); }
                public DocStatus status() { return status; }
                public void status(DocStatus value) { status = value; }
            }
            """;

    /** An asynchronous guard that passes and an asynchronous action whose stage fails with an IOException. */
    private static final String ACTIONS = """
            package fx;
            import module java.base;
            import io.github.graphmaquina.annotation.GraphAction;
            import io.github.graphmaquina.annotation.GraphGuard;
            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.GuardContext;
            import io.github.graphmaquina.api.GuardResult;
            import io.github.graphmaquina.api.TransitionContext;
            public final class DocActions {
                @GraphGuard public CompletionStage<GuardResult> reviewed(
                        GuardContext<Doc, DocStatus, DocTransition> context) {
                    return CompletableFuture.completedFuture(GuardResult.pass());
                }
                @GraphAction public CompletionStage<ActionOutcome> archive(
                        TransitionContext<Doc, DocStatus, DocTransition> context) {
                    return CompletableFuture.failedFuture(new IOException("storage down"));
                }
            }
            """;

    /** DRAFT -SUBMIT-> REVIEW -PUBLISH-> PUBLISHED, and REVIEW -RETURN-> DRAFT, the failure route of PUBLISH. */
    private static final String DOCS = """
            package fx;
            import module java.base;
            import io.github.graphmaquina.annotation.GenerateGraphApi;
            import io.github.graphmaquina.api.Machine;
            import io.github.graphmaquina.api.Outcome;
            import static fx.DocStatus.*;
            import static fx.DocTransition.*;
            @GenerateGraphApi
            @DocsGraph.Definition(initial = DRAFT, terminals = PUBLISHED, allowsCycle = REVIEW,
                state = DocRefs.Status.class, id = DocRefs.Id.class)
            @DocsGraph.StatePolicy(state = DRAFT, otherwise = "review first")
            @DocsGraph.StatePolicy(state = REVIEW, otherwise = "publish or return")
            @DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT)
            public interface Docs extends Machine<Doc, DocStatus, DocTransition> {
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW,
                    guards = DocActionsRefs.Reviewed.class)
                Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED,
                    action = DocActionsRefs.Archive.class, failsOn = IOException.class, onFailure = RETURN)
                Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
            }
            """;

    /** The set the scenario builds the machine from. */
    private static final String LIBRARY = """
            package fx;
            import io.github.graphmaquina.annotation.StateGraphs;
            import io.github.graphmaquina.api.Machines;
            @StateGraphs public interface Library extends Machines { Docs docs(); }
            """;

    /** Submits a draft and publishes it, and tells how the publication ended. */
    private static final String SCENARIO = """
            package fx;
            import module java.base;
            import io.github.graphmaquina.api.Outcome;
            public final class Scenario {
                public static Object run() {
                    DocActions actions = new DocActions();
                    try (Library library = LibraryRuntime.builder(Duration.ofSeconds(5), actions).build()) {
                        Doc doc = new Doc();
                        library.docs().submit(doc);
                        Outcome<Doc, DocStatus, DocTransition> outcome = library.docs().publish(doc);
                        return outcome instanceof Outcome.Failed<?, ?, ?> failed
                                && failed.cause().orElseThrow() instanceof IOException
                                ? "failed by the IOException, " + doc.status() : String.valueOf(outcome);
                    }
                }
            }
            """;

    @Test
    void compilesAndRunsAMachineWhoseHostsReachJdkTypesThroughAModuleImport() throws Exception {
        int feature = Runtime.version().feature();
        assumeTrue(feature >= 25, "module imports are Java 25 syntax; this test runs on Java " + feature);

        Compilation.Result result = Compilation.compilePrepared(Compilation.prepare(sources(), 25),
                List.of("--release", "25"));

        assertTrue(result.success(), result::describe);
        assertEquals("failed by the IOException, DRAFT", result.load("fx.Scenario").getMethod("run").invoke(null));
    }

    /** The enums, then the constants above, each under its qualified name. */
    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", "package fx;\npublic enum DocStatus { DRAFT, REVIEW, PUBLISHED }\n");
        sources.put("fx.DocTransition", "package fx;\npublic enum DocTransition { SUBMIT, PUBLISH, RETURN }\n");
        sources.put("fx.Doc", DOC);
        sources.put("fx.DocActions", ACTIONS);
        sources.put("fx.Docs", DOCS);
        sources.put("fx.Library", LIBRARY);
        sources.put("fx.Scenario", SCENARIO);
        return sources;
    }
}
