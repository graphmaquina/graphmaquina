package io.stategraph.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.stategraph.api.Machine;
import io.stategraph.api.MachineKey;
import io.stategraph.api.Machines;
import io.stategraph.api.Outcome;
import io.stategraph.api.Reaction;
import io.stategraph.api.TransitionListener;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SetGenerationTest {

    @Test
    void builderSignatureFollowsTheDependenciesAndAsyncness() {
        Compilation.Result async = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY));
        assertTrue(async.success(), async.describe());
        String source = async.generated("fx.Library_");
        assertTrue(source.contains("public static Builder builder(Duration asyncTimeout, DocActions docActions)"), source);
        assertTrue(source.contains("public final class Library_ implements Library"), source);
        assertTrue(source.contains(".as(Docs.class)"), source);
        assertTrue(source.contains(".as(Pages.class)"), source);

        Compilation.Result sync = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH,
                Fixtures.DOCS_BODY.replace(Fixtures.ASYNC_ARCHIVE, "action = DocActionsRefs.Publish.class"),
                Fixtures.LIBRARY));
        assertTrue(sync.success(), sync.describe());
        assertTrue(sync.generated("fx.Library_").contains("public static Builder builder(DocActions docActions)"),
                sync.generated("fx.Library_"));
    }

    @Test
    void builtSetRunsCascadesWatchesHooksAndListener() throws Exception {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY));
        assertTrue(result.success(), result.describe());
        Class<?> docActions = result.load("fx.DocActions");
        Object actions = docActions.getConstructor().newInstance();
        Class<?> companion = result.load("fx.Library_");
        Object builder = companion.getMethod("builder", Duration.class, docActions).invoke(null, Duration.ofSeconds(5), actions);
        List<String> seen = new ArrayList<>();
        TransitionListener listener = (MachineKey<?, ?> key, Outcome<?, ?> outcome) ->
                seen.add(key.entity().getSimpleName() + ":" + outcome.getClass().getSimpleName());
        builder.getClass().getMethod("listener", TransitionListener.class).invoke(builder, listener);
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
            Outcome.Applied<?, ?> applied = assertInstanceOf(Outcome.Applied.class, finished);
            Reaction.Done reaction = assertInstanceOf(Reaction.Done.class, applied.reactions().get(0));
            assertInstanceOf(Outcome.Applied.class, reaction.outcome());
            assertEquals("PUBLISHED", docClass.getMethod("getStatus").invoke(doc).toString());
            @SuppressWarnings("unchecked")
            List<String> calls = (List<String>) docActions.getField("calls").get(actions);
            assertEquals(List.of("publish", "notify:d1"), calls);
            assertEquals(List.of("Doc:Applied", "Doc:Applied", "Page:Applied"), seen);
        } finally {
            ((Machines) library).close();
        }
    }

    @Test
    void perMachineTimeoutOverrideRejectsMachinesWithoutAsyncCells() throws Exception {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY));
        assertTrue(result.success(), result.describe());
        Class<?> docActions = result.load("fx.DocActions");
        Object actions = docActions.getConstructor().newInstance();
        Object builder = result.load("fx.Library_").getMethod("builder", Duration.class, docActions).invoke(null, Duration.ofSeconds(5), actions);
        Method override = builder.getClass().getMethod("asyncTimeout", Class.class, Duration.class);
        override.invoke(builder, result.load("fx.Docs"), Duration.ofSeconds(1));
        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> override.invoke(builder, result.load("fx.Pages"), Duration.ofSeconds(1)));
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
    }

    /** A package holding Status, Thing and a minimal Things machine over them: three simple names shared with every twin. */
    private static void twin(Map<String, String> sources, String name) {
        sources.put(name + ".Status", "package " + name + ";\npublic enum Status { OPEN, DONE }\n");
        sources.put(name + ".Thing", """
                package %s;
                import io.stategraph.annotation.GraphProperty;
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
                import io.stategraph.annotation.GenerateGraphApi;
                import io.stategraph.api.Machine;
                import io.stategraph.api.Outcome;
                import static %1$s.Status.DONE;
                import static %1$s.Status.OPEN;
                @GenerateGraphApi
                @ThingsGraph.Definition(initial = OPEN, terminals = DONE, state = ThingRefs.Status.class, id = ThingRefs.Id.class)
                @ThingsGraph.Row(state = OPEN, otherwise = "a thing is finished")
                public interface Things extends Machine<Thing, Status> {
                    @ThingsGraph.Move(from = OPEN, to = DONE)
                    Outcome<Thing, Status> finish(Thing thing);
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
                import io.stategraph.annotation.StateGraphs;
                import io.stategraph.api.Machines;
                @StateGraphs
                public interface Both extends Machines {
                    a.Things first();
                    b.Things second();
                }
                """);
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result.describe());
        assertTrue(result.hasGenerated("a.Things_"), result.describe());
        assertTrue(result.hasGenerated("b.Things_"), result.describe());
        assertTrue(result.hasGenerated("fx.Both_"), result.describe());
    }

    @Test
    void aDependencyNamedLikeAGeneratedFieldStillCompiles() {
        Map<String, String> sources = Fixtures.withLibrary(Fixtures.DOCS_HEADER,
                Fixtures.DOCS_BODY.replace(Fixtures.PUBLISH_BINDING, "action = ExecutorRefs.Publish.class"),
                Fixtures.LIBRARY);
        sources.put("fx.Executor", """
                package fx;
                import io.stategraph.annotation.GraphAction;
                import io.stategraph.api.ActionOutcome;
                import io.stategraph.api.TransitionContext;
                public class Executor {
                    @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus> ctx) { return ActionOutcome.ok(); }
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
        assertFalse(result.hasGenerated("fx.Library_"));
        assertFalse(result.hasGenerated("fx.Docs_"));
        assertTrue(result.hasGenerated("fx.Pages_"));
    }
}
