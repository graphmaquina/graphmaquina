package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Compiles the generated facade of a default after-commit hook and runs it, asserting its observable runtime behavior. */
class DefaultAfterCommitRuntimeTest {
    private static Map<String, String> sources() {
        Map<String, String> sources = DefaultHookFixtures.sources(
            ", afterCommit = RecorderRefs.Persist.class",
            ", afterCommit = DocActionsRefs.Notified.class, onFailure = DRAFT");
        sources.put("fx.Recorder", """
            package fx;
            import io.github.graphmaquina.annotation.GraphHook;
            import java.util.ArrayList;
            import java.util.List;
            public final class Recorder {
                public final List<String> events = new ArrayList<>();
                public Thread thread;
                public boolean fail;
                @GraphHook public void persist(Doc doc) {
                    thread = Thread.currentThread();
                    events.add("persist:" + doc.status());
                    if (fail) throw new IllegalStateException("storage unavailable");
                }
            }
            """);
        sources.compute("fx.DocActions", (key, text) -> text
            .replace("public final class DocActions {",
                "public final class DocActions { public boolean fail; public int notified;")
            .replace("return ActionOutcome.ok();", "return fail ? ActionOutcome.failed(\"declined\") : ActionOutcome.ok();")
            .replace("public void notified(Doc doc) { }", "public void notified(Doc doc) { notified++; }"));
        return sources;
    }

    private static Object execute(Map<String, String> sources, String body) throws Exception {
        sources.put("fx.Scenario", """
            package fx;
            import io.github.graphmaquina.api.Outcome;
            import io.github.graphmaquina.error.AfterCommitException;
            import java.util.List;
            public final class Scenario {
                public static Object run() {
                    Recorder recorder = new Recorder();
                    DocActions actions = new DocActions();
                    try (Library library = LibraryRuntime.builder(recorder, actions)
                            .listener((key, outcome) -> recorder.events.add("listener"))
                            .build()) {
                        Doc doc = new Doc();
                        %s
                    }
                }
            }
            """.formatted(body));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result::describe);
        return result.load("fx.Scenario").getMethod("run").invoke(null);
    }

    @Test void hookSeesCommittedStateOnCallingThreadBeforeListener() throws Exception {
        assertEquals(Boolean.TRUE, execute(sources(), """
            Thread caller = Thread.currentThread();
            Outcome<Doc, DocStatus> outcome = library.docs().submit(doc);
            return outcome instanceof Outcome.Applied<?, ?>
                && doc.status() == DocStatus.REVIEW && recorder.thread == caller
                && recorder.events.equals(List.of("persist:REVIEW", "listener"));
            """));
    }

    @Test void explicitHookReplacesDefaultAtRuntime() throws Exception {
        assertEquals(Boolean.TRUE, execute(sources(), """
            library.docs().submit(doc);
            recorder.events.clear();
            library.docs().publish(doc);
            return doc.status() == DocStatus.PUBLISHED && actions.notified == 1
                && recorder.events.equals(List.of("listener"));
            """));
    }

    @Test void actionWithoutOverrideRunsDefault() throws Exception {
        Map<String, String> fixture = sources();
        fixture.compute("fx.Docs", (key, text) -> text.replace(
            ", afterCommit = DocActionsRefs.Notified.class", ""));
        assertEquals(Boolean.TRUE, execute(fixture, """
            library.docs().submit(doc);
            recorder.events.clear();
            library.docs().publish(doc);
            return doc.status() == DocStatus.PUBLISHED && actions.notified == 0
                && recorder.events.equals(List.of("persist:PUBLISHED", "listener"));
            """));
    }

    @Test void asyncWrapperRunsDefaultOnTheOperationThread() throws Exception {
        assertEquals(Boolean.TRUE, execute(sources(), """
            Thread caller = Thread.currentThread();
            java.util.concurrent.atomic.AtomicReference<Thread> worker = new java.util.concurrent.atomic.AtomicReference<>();
            Outcome<Doc, DocStatus> outcome = library.docs().async(() -> {
                worker.set(Thread.currentThread());
                return library.docs().submit(doc);
            }).join();
            return outcome instanceof Outcome.Applied<?, ?>
                && recorder.thread == worker.get() && recorder.thread != caller
                && recorder.events.equals(List.of("persist:REVIEW", "listener"));
            """));
    }

    @Test void rejectedAndIgnoredDoNotRunDefault() throws Exception {
        assertEquals(Boolean.TRUE, execute(sources(), """
            Outcome<Doc, DocStatus> rejected = library.docs().publish(doc);
            Outcome<Doc, DocStatus> ignored = library.docs().reevaluate(doc);
            return rejected instanceof Outcome.Rejected<?, ?>
                && ignored instanceof Outcome.Ignored<?, ?>
                && recorder.events.equals(List.of("listener", "listener"));
            """));
    }

    @Test void failureRouteUsesInternalCellDefaultInsteadOfOriginalOverride() throws Exception {
        assertEquals(Boolean.TRUE, execute(sources(), """
            library.docs().submit(doc);
            recorder.events.clear();
            actions.fail = true;
            Outcome<Doc, DocStatus> outcome = library.docs().publish(doc);
            return outcome instanceof Outcome.Failed<?, ?> && doc.status() == DocStatus.DRAFT
                && actions.notified == 0
                && recorder.events.equals(List.of("persist:DRAFT", "listener"));
            """));
    }

    @Test void throwingDefaultKeepsStateAndStopsListener() throws Exception {
        assertEquals(Boolean.TRUE, execute(sources(), """
            recorder.fail = true;
            try {
                library.docs().submit(doc);
                return false;
            } catch (AfterCommitException failure) {
                return failure.step().equals("persist")
                    && failure.getCause() instanceof IllegalStateException
                    && doc.status() == DocStatus.REVIEW
                    && recorder.events.equals(List.of("persist:REVIEW"));
            }
            """));
    }

    @Test void defaultIsAppliedWhenAWatchTriggersTheOwnerAction() throws Exception {
        Map<String, String> sources = TypedFixtures.watching();
        sources.compute("fx.Docs", (key, text) -> text.replace("id = DocRefs.Id.class)",
            "id = DocRefs.Id.class, afterCommit = DocActionsRefs.Persist.class)"));
        sources.compute("fx.DocActions", (key, text) -> text.replace("public String selected",
            "public int commits; @io.github.graphmaquina.annotation.GraphHook "
            + "public void persist(Doc doc) { commits++; } public String selected"));
        sources.put("fx.WatchScenario", """
            package fx;
            public final class WatchScenario {
                public static int run() {
                    DocActions actions = new DocActions();
                    try (Library library = LibraryRuntime.builder(actions).build()) {
                        Doc doc = new Doc("d");
                        Page page = new Page("p", doc);
                        library.docs().submit(doc);
                        library.pages().finish(page);
                        return doc.status() == DocStatus.PUBLISHED ? actions.commits : -1;
                    }
                }
            }
            """);
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result::describe);
        assertEquals(2, result.load("fx.WatchScenario").getMethod("run").invoke(null));
    }
}
