package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.runtime.StateMachines;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class MachineGenerationTest {

    @Test
    void generatesTheCompanionWithGraphFacadeAndKey() {
        Compilation.Result result = Compilation.compileTyped(Fixtures.validDocs());
        assertTrue(result.success(), result.describe());
        assertTrue(result.hasGenerated("fx.DocsRuntime"), result.describe());
        String source = result.generated("fx.DocsRuntime");
        assertTrue(source.contains("public final class DocsRuntime implements Docs"), source);
        assertTrue(source.contains("MachineKey<Doc, DocStatus, DocTransition> KEY = MachineKey.of(Doc.class,"
                + " DocStatus.class, DocTransition.class)"), source);
        assertTrue(source.contains("public static GraphDefinition<Doc, DocStatus, DocTransition> graph(DocActions docActions)"), source);
        assertTrue(source.contains(".statePolicy(DocStatus.DRAFT, \"a draft is sent to review\")"), source);
        assertTrue(source.contains(".transition(DocTransition.PUBLISH, DocStatus.REVIEW, DocStatus.PUBLISHED)"
                + ".action(\"publish\", (context) -> docActions.publish(context))"), source);
        assertTrue(source.contains(".guard(\"approved\", (context) -> docActions.approved(context))"), source);
        assertTrue(source.contains(".onFailure(DocTransition.RETURN)"), source);
        assertTrue(source.contains(".afterCommit(\"notify\", (doc) -> docActions.notify(doc))"), source);
        assertTrue(source.contains(".asyncAction(\"archive\", (context) -> docActions.archiveAsync(context))"), source);
        assertTrue(source.contains(".timeout(Duration.parse(\"PT2S\"))"), source);
        assertTrue(source.contains(".endTransition()"), source);
        assertTrue(source.contains("return stateMachine.execute(doc, DocTransition.SUBMIT);"), source);
        assertTrue(source.contains("@Generated(\"io.github.graphmaquina.processor.StateGraphProcessor\")"), source);
    }

    /**
     * Two operations reaching the same destination from the same origin. Each one executes its own identifier
     * and neither is expressed as a destination, so nothing in the companion can substitute one for the other.
     */
    @Test
    void twoOperationsReachingOneDestinationEachExecuteTheirOwnIdentifier() {
        Compilation.Result result = Compilation.compileTyped(
                Fixtures.withDocs(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY + Fixtures.AUTO_PUBLISH));
        assertTrue(result.success(), result.describe());
        String source = result.generated("fx.DocsRuntime");

        assertTrue(source.contains("stateMachine.execute(doc, DocTransition.PUBLISH)"));
        assertTrue(source.contains("stateMachine.execute(doc, DocTransition.AUTO_PUBLISH)"));
        assertFalse(source.contains("transitionTo("));
        assertFalse(source.contains(".forbidOthers("));
        assertFalse(source.contains(".stay()"));

        assertTrue(source.contains(".transition(DocTransition.AUTO_PUBLISH, DocStatus.REVIEW, DocStatus.PUBLISHED)"
                + ".action(\"autoPublish\", (context) -> docActions.autoPublish(context)).endTransition()"), source);
    }

    /**
     * The generated facade dispatches by identity for real: the fixture is loaded through the compilation's own
     * ClassLoader, and calling {@code autoPublish} runs the action that operation names and not the one the
     * parallel edge to the same destination names.
     */
    @Test
    void autoPublishRunsItsOwnActionThroughTheGeneratedFacade() throws Exception {
        Compilation.Result result = Compilation.compileTyped(
                Fixtures.withDocs(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY + Fixtures.AUTO_PUBLISH));
        assertTrue(result.success(), result.describe());
        Class<?> docActions = result.load("fx.DocActions");
        Object actions = docActions.getConstructor().newInstance();
        Class<?> companion = result.load("fx.DocsRuntime");
        Method graph = companion.getMethod("graph", docActions);
        @SuppressWarnings("unchecked")
        GraphDefinition<Object, ?, ?> definition = (GraphDefinition<Object, ?, ?>) graph.invoke(null, actions);

        Class<?> docClass = result.load("fx.Doc");
        Object doc = docClass.getConstructor(String.class).newInstance("d1");
        Method getStatus = docClass.getMethod("getStatus");
        Method setStatus = docClass.getMethod("setStatus", result.load("fx.DocStatus"));
        Object facade = companion.getConstructor(StateMachine.class)
                .newInstance(machine(definition, getStatus, setStatus));
        assertInstanceOf(Outcome.Applied.class, companion.getMethod("submit", docClass).invoke(facade, doc));
        assertInstanceOf(Outcome.Applied.class, companion.getMethod("autoPublish", docClass).invoke(facade, doc));

        assertEquals("PUBLISHED", getStatus.invoke(doc).toString());
        @SuppressWarnings("unchecked")
        List<String> calls = (List<String>) docActions.getField("calls").get(actions);
        assertEquals(List.of("autoPublish"), calls);
    }

    @Test
    void staticGuardsNeedNoInstanceAndRejectAndWatchAreEmitted() {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withPages(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH
                        + "@DocsGraph.Reject(from = DRAFT, transition = ARCHIVE, reason = \"never\")\n",
                Fixtures.DOCS_BODY.replace("guards = DocActionsRefs.Approved.class", "guards = DocActionsRefs.Ready.class")));
        assertTrue(result.success(), result.describe());
        String source = result.generated("fx.DocsRuntime");
        assertTrue(source.contains(".guard(\"ready\", (context) -> DocActions.ready(context))"), source);
        assertTrue(source.contains(".reject(DocStatus.DRAFT, DocTransition.ARCHIVE, \"never\")"), source);
        assertTrue(source.contains(".watch(DocStatus.REVIEW, MachineKey.of(Page.class, PageStatus.class,"
                + " PageTransition.class), (Doc doc) -> doc.getPages(), (Page page) -> page.doc())"), source);
        assertTrue(source.contains(".when(WatchCondition.allIn(PageStatus.DONE)).execute(DocTransition.PUBLISH)"), source);
        assertTrue(source.contains(".endWatch()"), source);
        assertTrue(result.hasGenerated("fx.PagesRuntime"), result.describe());
    }

    @Test
    void namesTheParametersOfAConditionLambdaApartFromEachOther() {
        for (String entity : List.of("Peers", "View")) {
            Compilation.Result result = Compilation.compileTyped(watched(entity));

            assertTrue(result.success(), result.describe());
            String source = result.generated("fx.MainsRuntime");
            assertTrue(source.contains(".when(\"ready\", (" + parameters(entity) + ") -> rules.ready("
                    + parameters(entity) + ")).execute(MainTransition.CLOSE)"), source);
        }
    }

    /** The three names the condition lambda must declare when the entity is named {@code entity}. */
    private static String parameters(String entity) {
        return switch (entity) {
            case "Peers" -> "peers, peers2, view";
            case "View" -> "view, peers, view2";
            default -> throw new IllegalArgumentException(entity);
        };
    }

    /**
     * A watched machine whose entity type is named {@code entity}, so the entity's own lambda parameter takes
     * the name one of the other two parameters of a condition would otherwise claim.
     */
    private static Map<String, String> watched(String entity) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.MainStatus", """
                package fx;
                public enum MainStatus { OPEN, CLOSED }
                """);
        sources.put("fx.MainTransition", """
                package fx;
                public enum MainTransition { CLOSE }
                """);
        sources.put("fx.PartStatus", """
                package fx;
                public enum PartStatus { OPEN, DONE }
                """);
        sources.put("fx.PartTransition", """
                package fx;
                public enum PartTransition { FINISH }
                """);
        sources.put("fx." + entity, """
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                import java.util.ArrayList;
                import java.util.List;
                public final class %s {
                    @GraphProperty private MainStatus status = MainStatus.OPEN;
                    private final List<Part> parts = new ArrayList<>();
                    @GraphProperty public String id() { return "main"; }
                    public MainStatus status() { return status; }
                    public void status(MainStatus value) { status = value; }
                    @GraphProperty public List<Part> parts() { return parts; }
                }
                """.formatted(entity));
        sources.put("fx.Part", """
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                import java.util.Optional;
                public final class Part {
                    @GraphProperty private PartStatus status = PartStatus.OPEN;
                    @GraphProperty public String id() { return "part"; }
                    public PartStatus status() { return status; }
                    public void status(PartStatus value) { status = value; }
                    @GraphProperty public Optional<%s> owner() { return Optional.empty(); }
                }
                """.formatted(entity));
        sources.put("fx.Rules", """
                package fx;
                import io.github.graphmaquina.annotation.GraphCondition;
                import io.github.graphmaquina.api.PeerView;
                import java.util.Collection;
                public final class Rules {
                    @GraphCondition
                    public boolean ready(%s entity, Collection<? extends Part> parts,
                            PeerView<Part, PartStatus, PartTransition> view) {
                        return view.allIn(parts, PartStatus.DONE);
                    }
                }
                """.formatted(entity));
        sources.put("fx.Parts", """
                package fx;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static fx.PartStatus.DONE;
                import static fx.PartStatus.OPEN;
                import static fx.PartTransition.FINISH;
                @GenerateGraphApi
                @PartsGraph.Definition(initial = OPEN, terminals = DONE, state = PartRefs.Status.class, id = PartRefs.Id.class)
                @PartsGraph.StatePolicy(state = OPEN, otherwise = "a part is finished")
                public interface Parts extends Machine<Part, PartStatus, PartTransition> {
                    @PartsGraph.Transition(id = FINISH, from = OPEN, to = DONE)
                    Outcome<Part, PartStatus, PartTransition> finish(Part part);
                }
                """);
        sources.put("fx.Mains", """
                package fx;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.annotation.GraphPeers;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static fx.MainStatus.CLOSED;
                import static fx.MainStatus.OPEN;
                import static fx.MainTransition.CLOSE;
                @GenerateGraphApi
                @GraphPeers(Parts.class)
                @MainsGraph.Definition(initial = OPEN, terminals = CLOSED, state = %1$sRefs.Status.class, id = %1$sRefs.Id.class)
                @MainsGraph.StatePolicy(state = OPEN, otherwise = "the parts decide")
                @MainsParts.Watch(state = OPEN, peers = %1$sRefs.Parts.class, owner = PartRefs.Owner.class,
                        rules = @MainsParts.When(condition = RulesRefs.Ready.class, transition = CLOSE))
                public interface Mains extends Machine<%1$s, MainStatus, MainTransition> {
                    @MainsGraph.Transition(id = CLOSE, from = OPEN, to = CLOSED)
                    Outcome<%1$s, MainStatus, MainTransition> close(%1$s entity);
                }
                """.formatted(entity));
        return sources;
    }

    @Test
    void generatedGraphBuildsAndTheFacadeRuns() throws Exception {
        Compilation.Result result = Compilation.compileTyped(Fixtures.validDocs());
        assertTrue(result.success(), result.describe());
        Class<?> docActions = result.load("fx.DocActions");
        Object actions = docActions.getConstructor().newInstance();
        Class<?> companion = result.load("fx.DocsRuntime");
        Method graph = companion.getMethod("graph", docActions);
        @SuppressWarnings("unchecked")
        GraphDefinition<Object, ?, ?> definition = (GraphDefinition<Object, ?, ?>) graph.invoke(null, actions);
        assertGeneratedTransitions(definition);

        Class<?> docClass = result.load("fx.Doc");
        Object doc = docClass.getConstructor(String.class).newInstance("d1");
        Method getStatus = docClass.getMethod("getStatus");
        Method setStatus = docClass.getMethod("setStatus", result.load("fx.DocStatus"));
        StateMachine<Object, ?, ?> machine = machine(definition, getStatus, setStatus);
        Object facade = companion.getConstructor(StateMachine.class).newInstance(machine);
        assertInstanceOf(Machine.class, facade);
        Object submitted = companion.getMethod("submit", docClass).invoke(facade, doc);
        assertInstanceOf(Outcome.Applied.class, submitted);
        assertEquals("REVIEW", getStatus.invoke(doc).toString());
        Object published = companion.getMethod("publish", docClass).invoke(facade, doc);
        assertInstanceOf(Outcome.Applied.class, published);
        @SuppressWarnings("unchecked")
        List<String> calls = (List<String>) docActions.getField("calls").get(actions);
        assertEquals(List.of("publish", "notify:d1"), calls);
        Object archived = companion.getMethod("archive", docClass).invoke(facade, doc);
        assertInstanceOf(Outcome.Applied.class, archived);
        assertEquals("ARCHIVED", getStatus.invoke(doc).toString());
    }

    /** Narrows the generated enums through their declared types, without consulting a diagram. */
    private static <S extends Enum<S>, T extends Enum<T>> void assertGeneratedTransitions(
            GraphDefinition<Object, S, T> definition) {
        Class<S> states = definition.key().states();
        Class<T> catalog = definition.key().transitions();
        S review = Enum.valueOf(states, "REVIEW");
        S published = Enum.valueOf(states, "PUBLISHED");
        T publish = Enum.valueOf(catalog, "PUBLISH");
        T returned = Enum.valueOf(catalog, "RETURN");
        T archive = Enum.valueOf(catalog, "ARCHIVE");
        TransitionSpec<Object, S, T> publishing = definition.findTransition(review, publish).orElseThrow();
        assertEquals(TransitionKind.ACTION, publishing.kind());
        assertEquals("publish", publishing.action().orElseThrow().name());
        assertFalse(publishing.isAsync());
        assertEquals(List.of("approved"), publishing.guards().stream().map(guard -> guard.name()).toList());
        assertEquals(Optional.of(returned), publishing.onFailure());
        assertEquals("notify", publishing.afterCommit().orElseThrow().name());
        TransitionSpec<Object, S, T> archiving = definition.findTransition(published, archive).orElseThrow();
        assertEquals(TransitionKind.ACTION, archiving.kind());
        assertEquals("archive", archiving.action().orElseThrow().name());
        assertTrue(archiving.action().orElseThrow().isAsync());
        assertEquals(Optional.of(Duration.ofSeconds(2)), archiving.timeout());
    }

    /** Builds a standalone machine over reflective accessors, with raw state types the test never names. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static StateMachine<Object, ?, ?> machine(GraphDefinition<Object, ?, ?> definition, Method getStatus,
            Method setStatus) {
        Function<Object, Enum> getter = (Object entity) -> {
            try {
                return (Enum) getStatus.invoke(entity);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        };
        BiConsumer<Object, Enum> setter = (Object entity, Enum state) -> {
            try {
                setStatus.invoke(entity, state);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        };
        StateMachines.Builder builder = StateMachines.from((GraphDefinition) definition);
        return builder.state(getter, setter).asyncTimeout(Duration.ofSeconds(5)).build();
    }
}
