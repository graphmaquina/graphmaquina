package io.stategraph.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.stategraph.api.GraphDefinition;
import io.stategraph.api.Machine;
import io.stategraph.api.Outcome;
import io.stategraph.api.StateMachine;
import io.stategraph.export.GraphView;
import io.stategraph.export.MermaidExporter;
import io.stategraph.runtime.StateMachines;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class MachineGenerationTest {

    @Test
    void generatesTheCompanionWithGraphFacadeAndKey() {
        Compilation.Result result = Compilation.compile(Fixtures.validDocs());
        assertTrue(result.success(), result.describe());
        assertTrue(result.hasGenerated("fx.Docs_"), result.describe());
        String source = result.generated("fx.Docs_");
        assertTrue(source.contains("public final class Docs_ implements Docs"), source);
        assertTrue(source.contains("public static GraphDefinition<Doc, DocStatus> graph(DocActions docActions)"), source);
        assertTrue(source.contains(".to(DocStatus.PUBLISHED).action(\"publish\", docActions::publish)"), source);
        assertTrue(source.contains(".guard(\"approved\", docActions::approved)"), source);
        assertTrue(source.contains(".onFailure(DocStatus.DRAFT)"), source);
        assertTrue(source.contains(".afterCommit(\"notify\", docActions::notify)"), source);
        assertTrue(source.contains(".asyncAction(\"archive\", docActions::archiveAsync)"), source);
        assertTrue(source.contains(".timeout(Duration.parse(\"PT2S\"))"), source);
        assertTrue(source.contains(".forbidOthers(\"ARCHIVED is final\")"), source);
        assertTrue(source.contains("return machine.transitionTo(doc, DocStatus.REVIEW);"), source);
        assertTrue(source.contains("@Generated(\"io.stategraph.processor.StateGraphProcessor\")"), source);
    }

    @Test
    void staticGuardsNeedNoInstanceAndForbidAndWatchAreEmitted() {
        Compilation.Result result = Compilation.compile(Fixtures.withPages(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH + "@Forbid(from = \"DRAFT\", to = \"ARCHIVED\", reason = \"never\")\n",
                Fixtures.DOCS_BODY.replace("guards = \"approved\"", "guards = \"ready\"")));
        assertTrue(result.success(), result.describe());
        String source = result.generated("fx.Docs_");
        assertTrue(source.contains(".guard(\"ready\", DocActions::ready)"), source);
        assertTrue(source.contains(".to(DocStatus.ARCHIVED).forbid(\"never\")"), source);
        assertTrue(source.contains(".watches(MachineKey.of(Page.class, PageStatus.class), (Doc doc) -> doc.getPages(), (Page page) -> page.doc())"), source);
        assertTrue(source.contains(".when(WatchCondition.allIn(PageStatus.DONE)).goTo(DocStatus.PUBLISHED)"), source);
        assertTrue(result.hasGenerated("fx.Pages_"), result.describe());
    }

    @Test
    void generatedGraphBuildsAndTheFacadeRuns() throws Exception {
        Compilation.Result result = Compilation.compile(Fixtures.validDocs());
        assertTrue(result.success(), result.describe());
        Class<?> docActions = result.load("fx.DocActions");
        Object actions = docActions.getConstructor().newInstance();
        Class<?> companion = result.load("fx.Docs_");
        Method graph = companion.getMethod("graph", docActions);
        @SuppressWarnings("unchecked")
        GraphDefinition<Object, ?> definition = (GraphDefinition<Object, ?>) graph.invoke(null, actions);
        String diagram = MermaidExporter.render(GraphView.of(definition));
        assertTrue(diagram.contains("REVIEW --> PUBLISHED : publish [if approved]"), diagram);
        assertTrue(diagram.contains("PUBLISHED --> ARCHIVED : archive [async]"), diagram);

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
