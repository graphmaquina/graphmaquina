package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.export.GraphView;
import io.github.graphmaquina.export.GraphvizExporter;
import io.github.graphmaquina.runtime.StateMachines;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        assertTrue(source.contains("public static GraphDefinition<Doc, DocStatus> graph(DocActions docActions)"), source);
        assertTrue(source.contains(".to(DocStatus.PUBLISHED).action(\"publish\", (context) -> docActions.publish(context))"), source);
        assertTrue(source.contains(".guard(\"approved\", (context) -> docActions.approved(context))"), source);
        assertTrue(source.contains(".onFailure(DocStatus.DRAFT)"), source);
        assertTrue(source.contains(".afterCommit(\"notify\", (doc) -> docActions.notify(doc))"), source);
        assertTrue(source.contains(".asyncAction(\"archive\", (context) -> docActions.archiveAsync(context))"), source);
        assertTrue(source.contains(".timeout(Duration.parse(\"PT2S\"))"), source);
        assertTrue(source.contains(".forbidOthers(\"ARCHIVED is final\")"), source);
        assertTrue(source.contains("return stateMachine.transitionTo(doc, DocStatus.REVIEW);"), source);
        assertTrue(source.contains("@Generated(\"io.github.graphmaquina.processor.StateGraphProcessor\")"), source);
    }

    @Test
    void staticGuardsNeedNoInstanceAndForbidAndWatchAreEmitted() {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withPages(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH
                        + "@DocsGraph.Forbid(from = DRAFT, to = ARCHIVED, reason = \"never\")\n",
                Fixtures.DOCS_BODY.replace("guards = DocActionsRefs.Approved.class", "guards = DocActionsRefs.Ready.class")));
        assertTrue(result.success(), result.describe());
        String source = result.generated("fx.DocsRuntime");
        assertTrue(source.contains(".guard(\"ready\", (context) -> DocActions.ready(context))"), source);
        assertTrue(source.contains(".to(DocStatus.ARCHIVED).forbid(\"never\")"), source);
        assertTrue(source.contains(".watches(MachineKey.of(Page.class, PageStatus.class), (Doc doc) -> doc.getPages(), (Page page) -> page.doc())"), source);
        assertTrue(source.contains(".when(WatchCondition.allIn(PageStatus.DONE)).goTo(DocStatus.PUBLISHED)"), source);
        assertTrue(result.hasGenerated("fx.PagesRuntime"), result.describe());
    }

    @Test
    void namesTheParametersOfAConditionLambdaApartFromEachOther() {
        for (String entity : List.of("Peers", "View")) {
            Compilation.Result result = Compilation.compileTyped(watched(entity));

            assertTrue(result.success(), result.describe());
            String source = result.generated("fx.MainsRuntime");
            assertTrue(source.contains(".when(\"ready\", (" + parameters(entity) + ") -> rules.ready("
                    + parameters(entity) + ")).goTo(MainStatus.CLOSED)"), source);
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
        sources.put("fx.PartStatus", """
                package fx;
                public enum PartStatus { OPEN, DONE }
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
                    public boolean ready(%s entity, Collection<? extends Part> parts, PeerView<Part, PartStatus> view) {
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
                @GenerateGraphApi
                @PartsGraph.Definition(initial = OPEN, terminals = DONE, state = PartRefs.Status.class, id = PartRefs.Id.class)
                @PartsGraph.Row(state = OPEN, otherwise = "a part is finished")
                public interface Parts extends Machine<Part, PartStatus> {
                    @PartsGraph.Move(from = OPEN, to = DONE)
                    Outcome<Part, PartStatus> finish(Part part);
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
                @GenerateGraphApi
                @GraphPeers(Parts.class)
                @MainsGraph.Definition(initial = OPEN, terminals = CLOSED, state = %1$sRefs.Status.class, id = %1$sRefs.Id.class)
                @MainsGraph.Row(state = OPEN, otherwise = "the parts decide")
                @MainsParts.Watch(state = OPEN, peers = %1$sRefs.Parts.class, owner = PartRefs.Owner.class,
                        rules = @MainsParts.When(condition = RulesRefs.Ready.class, goTo = CLOSED))
                public interface Mains extends Machine<%1$s, MainStatus> {
                    @MainsGraph.Move(from = OPEN, to = CLOSED)
                    Outcome<%1$s, MainStatus> close(%1$s entity);
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
        GraphDefinition<Object, ?> definition = (GraphDefinition<Object, ?>) graph.invoke(null, actions);
        String diagram = GraphvizExporter.render(GraphView.of(definition));
        assertTrue(diagram.contains("n1 -> n2 [label=\"publish [if approved]\"];"), diagram);
        assertTrue(diagram.contains("n2 -> n3 [label=\"archive [async]\"];"), diagram);

        Class<?> docClass = result.load("fx.Doc");
        Object doc = docClass.getConstructor(String.class).newInstance("d1");
        Method getStatus = docClass.getMethod("getStatus");
        Method setStatus = docClass.getMethod("setStatus", result.load("fx.DocStatus"));
        StateMachine<Object, ?> machine = machine(definition, getStatus, setStatus);
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

    /** Builds a standalone machine over reflective accessors, with raw state types the test never names. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static StateMachine<Object, ?> machine(GraphDefinition<Object, ?> definition, Method getStatus, Method setStatus) {
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
