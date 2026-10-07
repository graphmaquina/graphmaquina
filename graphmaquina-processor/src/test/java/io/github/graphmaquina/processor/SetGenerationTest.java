package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Machines;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.Reaction;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The companion generated for a {@code @StateGraphs} set: its builder, whose signature follows the dependencies and
 * the asynchronous machines of the set and which refuses what the builders it mirrors refuse; which methods are
 * accessors; the machines it holds and runs; and the names it keeps apart. A set with a machine that failed is not
 * generated.
 */
class SetGenerationTest {

    @Test
    void builderSignatureFollowsTheDependenciesAndAsyncness() {
        Compilation.Result async = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY));
        assertTrue(async.success(), async.describe());
        String source = async.generated("fx.LibraryRuntime");
        assertTrue(source.contains("public static Builder builder(Duration asyncTimeout, DocActions docActions)"), source);
        assertTrue(source.contains("public final class LibraryRuntime implements Library"), source);
        assertTrue(source.contains(".as(Docs.class)"), source);
        assertTrue(source.contains(".as(Pages.class)"), source);

        Compilation.Result sync = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH,
                Fixtures.DOCS_BODY.replace(Fixtures.ASYNC_ARCHIVE, "action = DocActionsRefs.Publish.class"),
                Fixtures.LIBRARY));
        assertTrue(sync.success(), sync.describe());
        assertTrue(sync.generated("fx.LibraryRuntime").contains("public static Builder builder(DocActions docActions)"),
                sync.generated("fx.LibraryRuntime"));
    }

    /**
     * A set may declare a method of {@code Machines}, {@code AutoCloseable} or {@code Object} again: such a
     * redeclaration is no accessor, since the companion writes or inherits that method already. An overload of one
     * of them is no redeclaration, and stays refused.
     */
    @Test
    void redeclarationsOfWhatTheCompanionImplementsAreNoAccessors() {
        Compilation.Result redeclared = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY.replace(
                        "    Pages pages();\n", "    Pages pages();\n    @Override void close();\n"
                                + "    @Override String toString();\n    boolean equals(Object other);\n    int hashCode();\n")));
        assertTrue(redeclared.success(), redeclared.describe());
        assertTrue(redeclared.generated("fx.LibraryRuntime").contains("public void close()"),
                redeclared.generated("fx.LibraryRuntime"));

        Compilation.Result overload = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY.replace(
                        "    Pages pages();\n", "    Pages pages();\n    void close(boolean now);\n")));
        assertEquals(1, overload.countError("SG510"), overload.describe());
        assertTrue(overload.errors().get(0).contains("close must be a no-argument accessor"), overload.describe());
    }

    @Test
    void builtSetRunsCascadesWatchesHooksAndObservers() throws Exception {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY));
        assertTrue(result.success(), result.describe());
        Class<?> docActions = result.load("fx.DocActions");
        Object actions = docActions.getConstructor().newInstance();
        Class<?> companion = result.load("fx.LibraryRuntime");
        Object builder = companion.getMethod("builder", Duration.class, docActions).invoke(null, Duration.ofSeconds(5), actions);
        List<String> seen = new ArrayList<>();
        MachineObserver observer = new MachineObserver() {
            @Override
            public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                seen.add(call.key().entity().getSimpleName() + ":" + outcome.status().name());
            }
        };
        List<String> events = new ArrayList<>();
        Method register = builder.getClass().getMethod("observer", MachineObserver.class);
        register.invoke(builder, observer);
        register.invoke(builder, recording("first", events));
        register.invoke(builder, recording("second", events));
        Object library = builder.getClass().getMethod("build").invoke(builder);
        assertInstanceOf(Machines.class, library);
        try {
            Object docs = companion.getMethod("docs").invoke(library);
            Object pages = companion.getMethod("pages").invoke(library);
            assertInstanceOf(Machine.class, docs);
            Class<?> docClass = result.load("fx.Doc");
            Class<?> pageClass = result.load("fx.Page");
            Object doc = docClass.getConstructor(String.class).newInstance("d1");
            Object page = pageClass.getConstructor(String.class, docClass).newInstance("p1", doc);
            assertInstanceOf(Outcome.Applied.class, docs.getClass().getMethod("submit", docClass).invoke(docs, doc));
            Object finished = pages.getClass().getMethod("finish", pageClass).invoke(pages, page);
            Outcome.Applied<?, ?, ?> applied = assertInstanceOf(Outcome.Applied.class, finished);
            Reaction.Done reaction = assertInstanceOf(Reaction.Done.class, applied.reactions().get(0));
            assertInstanceOf(Outcome.Applied.class, reaction.outcome());
            assertEquals("PUBLISHED", docClass.getMethod("getStatus").invoke(doc).toString());
            @SuppressWarnings("unchecked")
            List<String> calls = (List<String>) docActions.getField("calls").get(actions);
            assertEquals(List.of("publish", "notify:d1"), calls);
            assertEquals(List.of("Doc:APPLIED", "Doc:APPLIED", "Page:APPLIED"), seen);
            assertEquals(12, events.size(), events.toString());
            for (int index = 0; index < events.size(); index += 2) {
                assertTrue(events.get(index).startsWith("first "), events.toString());
                assertEquals(events.get(index).replaceFirst("^first ", "second "), events.get(index + 1));
            }
            assertEquals(3, events.stream().filter((String event) -> event.startsWith("first completed")).count());
        } finally {
            ((Machines) library).close();
        }
    }

    /** An observer that appends "name event" to the shared list for every start and completion it is told of. */
    private static MachineObserver recording(String name, List<String> events) {
        return new MachineObserver() {
            @Override
            public void onStarted(MachineCall call) {
                events.add(name + " started " + call.key().entity().getSimpleName());
            }

            @Override
            public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                events.add(name + " completed " + call.key().entity().getSimpleName());
            }
        };
    }

    @Test
    void perMachineTimeoutOverrideRejectsMachinesWithoutAsyncTransitions() throws Exception {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY));
        assertTrue(result.success(), result.describe());
        Class<?> docActions = result.load("fx.DocActions");
        Object actions = docActions.getConstructor().newInstance();
        Object builder = result.load("fx.LibraryRuntime").getMethod("builder", Duration.class, docActions).invoke(null, Duration.ofSeconds(5), actions);
        Method override = builder.getClass().getMethod("asyncTimeout", Class.class, Duration.class);
        override.invoke(builder, result.load("fx.Docs"), Duration.ofSeconds(1));
        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> override.invoke(builder, result.load("fx.Pages"), Duration.ofSeconds(1)));
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
    }

    /**
     * The generated Builder mirrors MachineRegistry.Builder and StateMachines.Builder: a setting is refused when
     * it is given, observers add up instead of replacing one another, and the builder is single-use, so
     * once build() succeeded, build() itself and every setting fail loudly, each with valid arguments. The Builder
     * refuses them itself, naming itself: the builders it hands its settings to would refuse them too, naming
     * builders the author never wrote.
     */
    @Test
    void theGeneratedBuilderRefusesWhatTheBuildersItMirrorsRefuse() throws Exception {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY));
        assertTrue(result.success(), result.describe());
        Class<?> docActions = result.load("fx.DocActions");
        Object builder = result.load("fx.LibraryRuntime").getMethod("builder", Duration.class, docActions)
                .invoke(null, Duration.ofSeconds(5), docActions.getConstructor().newInstance());
        Method observer = builder.getClass().getMethod("observer", MachineObserver.class);
        Method depth = builder.getClass().getMethod("maxCascadeDepth", int.class);
        Method chain = builder.getClass().getMethod("maxWatchChain", int.class);
        Method timeout = builder.getClass().getMethod("asyncTimeout", Class.class, Duration.class);
        Method executor = builder.getClass().getMethod("asyncExecutor", Executor.class);
        Method build = builder.getClass().getMethod("build");
        MachineObserver first = new MachineObserver() { };
        MachineObserver second = new MachineObserver() { };
        Executor direct = (Runnable command) -> command.run();

        observer.invoke(builder, first);
        observer.invoke(builder, second);
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(InvocationTargetException.class, () -> depth.invoke(builder, 0)).getCause());
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(InvocationTargetException.class, () -> chain.invoke(builder, -3)).getCause());

        try (Machines library = (Machines) build.invoke(builder)) {
            assertInstanceOf(Machine.class, library.getClass().getMethod("docs").invoke(library));
            refusedAsUsed(() -> build.invoke(builder));
            refusedAsUsed(() -> observer.invoke(builder, second));
            refusedAsUsed(() -> depth.invoke(builder, 3));
            refusedAsUsed(() -> chain.invoke(builder, 3));
            refusedAsUsed(() -> timeout.invoke(builder, result.load("fx.Docs"), Duration.ofSeconds(1)));
            refusedAsUsed(() -> executor.invoke(builder, direct));
        }
    }

    /** Asserts that {@code call}, a call of the generated LibraryRuntime.Builder, is refused because it was used. */
    private static void refusedAsUsed(Executable call) {
        IllegalStateException refused = assertInstanceOf(IllegalStateException.class,
                assertThrows(InvocationTargetException.class, call).getCause());
        assertEquals("this LibraryRuntime.Builder was already used", refused.getMessage());
    }

    /**
     * The set reads and writes the state with the accessors the property selected, and only with those: a
     * static overload of the same name, which a method reference would also consider, changes nothing.
     */
    @Test
    void staticOverloadsOfTheStateAndIdentityAccessorsDoNotMakeTheSetAmbiguous() throws Exception {
        Map<String, String> sources = Fixtures.withLibrary(Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH,
                Fixtures.DOCS_BODY, Fixtures.LIBRARY);
        sources.put("fx.Page", sources.get("fx.Page").replace("    public PageStatus status() { return status; }\n",
                "    public PageStatus status() { return status; }\n"
                        + "    public static PageStatus status(Page page) { return page.status; }\n"
                        + "    public static void status(Page page, PageStatus value) { page.status = value; }\n"
                        + "    public static String id(Page page) { return page.id; }\n"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
        Class<?> docActions = result.load("fx.DocActions");
        Object builder = result.load("fx.LibraryRuntime").getMethod("builder", Duration.class, docActions)
                .invoke(null, Duration.ofSeconds(5), docActions.getConstructor().newInstance());
        try (Machines library = (Machines) builder.getClass().getMethod("build").invoke(builder)) {
            Object pages = library.getClass().getMethod("pages").invoke(library);
            Class<?> pageClass = result.load("fx.Page");
            Object page = pageClass.getConstructor(String.class, result.load("fx.Doc")).newInstance("p1", null);
            assertInstanceOf(Outcome.Applied.class, pages.getClass().getMethod("finish", pageClass).invoke(pages, page));
            assertEquals("DONE", pageClass.getMethod("status").invoke(page).toString());
        }
    }

    /**
     * A package holding Status, Step, Thing and a minimal Things machine over them: four simple names shared with
     * every twin.
     */
    private static void twin(Map<String, String> sources, String name) {
        sources.put(name + ".Status", "package " + name + ";\npublic enum Status { OPEN, DONE }\n");
        sources.put(name + ".Step", "package " + name + ";\npublic enum Step { FINISH }\n");
        sources.put(name + ".Thing", """
                package %s;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Thing {
                    private final String id;
                    @GraphProperty private Status status = Status.OPEN;
                    public Thing(String id) { this.id = id; }
                    @GraphProperty public String getId() { return id; }
                    public Status getStatus() { return status; }
                    public void setStatus(Status status) { this.status = status; }
                }
                """.formatted(name));
        sources.put(name + ".Things", """
                package %1$s;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static %1$s.Status.DONE;
                import static %1$s.Status.OPEN;
                import static %1$s.Step.FINISH;
                @GenerateGraphApi
                @ThingsGraph.Definition(initial = OPEN, terminals = DONE, state = ThingRefs.Status.class, id = ThingRefs.Id.class)
                @ThingsGraph.StatePolicy(state = OPEN, otherwise = "a thing is finished")
                public interface Things extends Machine<Thing, Status, Step> {
                    @ThingsGraph.Transition(id = FINISH, from = OPEN, to = DONE)
                    Outcome<Thing, Status, Step> finish(Thing thing);
                }
                """.formatted(name));
    }

    @Test
    void twoPackagesSharingEverySimpleNameStillCompile() {
        Map<String, String> sources = new LinkedHashMap<>();
        twin(sources, "a");
        twin(sources, "b");
        sources.put("fx.Both", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs
                public interface Both extends Machines {
                    a.Things first();
                    b.Things second();
                }
                """);
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result.describe());
        assertTrue(result.hasGenerated("a.ThingsRuntime"), result.describe());
        assertTrue(result.hasGenerated("b.ThingsRuntime"), result.describe());
        assertTrue(result.hasGenerated("fx.BothRuntime"), result.describe());
    }

    @Test
    void aDependencyNamedLikeAGeneratedFieldStillCompiles() {
        Map<String, String> sources = Fixtures.withLibrary(Fixtures.DOCS_HEADER,
                Fixtures.DOCS_BODY.replace(Fixtures.PUBLISH_BINDING, "action = ExecutorRefs.Publish.class"),
                Fixtures.LIBRARY);
        sources.put("fx.Executor", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                public class Executor {
                    @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus, DocTransition> ctx) { return ActionOutcome.ok(); }
                }
                """);
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result.describe());
    }

    @Test
    void setIsNotGeneratedWhenAMachineFailed() {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER.replace("otherwise = \"a draft is sent to review\"", "otherwise = \" \"")
                        + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY));
        assertFalse(result.success());
        assertFalse(result.hasGenerated("fx.LibraryRuntime"));
        assertFalse(result.hasGenerated("fx.DocsRuntime"));
        assertTrue(result.hasGenerated("fx.PagesRuntime"));
    }
}
