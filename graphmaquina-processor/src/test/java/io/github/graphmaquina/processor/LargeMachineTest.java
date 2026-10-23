package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.export.GraphView;
import io.github.graphmaquina.export.GraphvizExporter;
import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The companion of a machine of thousands of edges compiles on a thread of the stack HotSpot gives a thread by
 * default on Linux, 1 MB, and builds the very definition the DSL builds from the same declarations.
 *
 * <p>javac attributes a chain of calls recursively, once per call, and a method holds at most 64 KB of code, so the
 * companion declares the graph one statement per declaration, the private methods {@code graphPart1},
 * {@code graphPart2}, … holding at most {@value MachineGenerator#DECLARATIONS_PER_METHOD} statements each: neither
 * the depth of a chain nor the size of a method grows with the machine. The machine here, of {@value #EDGES} edges,
 * each with an action and every tenth with a guard, is beyond both limits of a companion written as one chain.</p>
 */
class LargeMachineTest {

    private static final int EDGES = 3000;
    private static final int STATES = 10;
    private static final long ONE_MEGABYTE = 1024L * 1024L;

    @Test
    void theCompanionOfThousandsOfEdgesCompilesOnAOneMegabyteStackAndBuildsTheDefinitionOfTheDsl() throws Exception {
        Map<String, String> sources = machine(EDGES, "static ");
        AtomicReference<Compilation.Result> compiled = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread compiler = new Thread(null, () -> {
            try {
                compiled.set(Compilation.compileTyped(sources));
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        }, "javac-1m", ONE_MEGABYTE);
        compiler.start();
        compiler.join();

        assertEquals(null, thrown.get(), "the compilation threw");
        Compilation.Result result = compiled.get();
        assertTrue(result.success(), result.describe());
        String companion = result.generated("big.ItemsRuntime");
        assertTrue(companion.contains("private static GraphBuilder<Item, ItemStatus, ItemTransition> graphPart31("),
                companion.substring(0, 4000));
        GraphDefinition<?, ?, ?> generated =
                (GraphDefinition<?, ?, ?>) result.load("big.ItemsRuntime").getMethod("graph").invoke(null);
        GraphDefinition<?, ?, ?> dsl = twin(result, EDGES);
        assertEquals(EDGES, generated.transitions().size());
        assertEquals(GraphView.of(dsl), GraphView.of(generated));
        assertEquals(GraphvizExporter.render(GraphView.of(dsl)), GraphvizExporter.render(GraphView.of(generated)));
    }

    /**
     * A graph of more declarations than one method makes, whose host has instance members, is split into methods
     * that each take the builder and the parameters of {@code graph(...)}, and builds the same definition as the DSL.
     */
    @Test
    void theMethodsThatDeclareAPartOfTheGraphTakeTheParametersOfTheDefinition() throws Exception {
        int edges = 150;
        Compilation.Result result = Compilation.compileTyped(machine(edges, ""));

        assertTrue(result.success(), result.describe());
        String companion = result.generated("big.ItemsRuntime");
        assertTrue(companion.contains("        graph = graphPart1(graph, itemActions);\n"
                + "        graph = graphPart2(graph, itemActions);\n        return graph.build();\n"), companion);
        assertTrue(companion.contains("private static GraphBuilder<Item, ItemStatus, ItemTransition> graphPart2("
                + "GraphBuilder<Item, ItemStatus, ItemTransition> graph, ItemActions itemActions)"), companion);
        Class<?> host = result.load("big.ItemActions");
        Constructor<?> constructor = host.getDeclaredConstructor();
        constructor.setAccessible(true);
        GraphDefinition<?, ?, ?> generated = (GraphDefinition<?, ?, ?>) result.load("big.ItemsRuntime")
                .getMethod("graph", host).invoke(null, constructor.newInstance());
        assertEquals(GraphView.of(twin(result, edges)), GraphView.of(generated));
    }

    /**
     * The machine {@code big.Items}: ten states in a line, a policy for each but the terminal one, a rejection, and
     * {@code edges} edges, the {@code t}-th leaving state {@code t % 9} for the next one through the identifier
     * {@code Tt}, each running the action {@code advance} and every tenth asking the guard {@code allowed} first,
     * both {@code modifier} members of {@code big.ItemActions}.
     */
    private static Map<String, String> machine(int edges, String modifier) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("big.ItemStatus", EnumSources.numbered("big", "ItemStatus", 'S', STATES));
        sources.put("big.ItemTransition", EnumSources.numbered("big", "ItemTransition", 'T', edges));
        sources.put("big.Item", """
                package big;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Item {
                    @GraphProperty private ItemStatus status = ItemStatus.S0;
                    @GraphProperty public String id() { return "item"; }
                    public ItemStatus status() { return status; }
                    public void status(ItemStatus value) { status = value; }
                }
                """);
        sources.put("big.ItemActions", """
                package big;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.annotation.GraphGuard;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.GuardContext;
                import io.github.graphmaquina.api.GuardResult;
                import io.github.graphmaquina.api.TransitionContext;
                public final class ItemActions {
                    private ItemActions() { }
                    @GraphAction
                    public %1$sActionOutcome advance(TransitionContext<Item, ItemStatus, ItemTransition> context) {
                        return ActionOutcome.ok();
                    }
                    @GraphGuard
                    public %1$sGuardResult allowed(GuardContext<Item, ItemStatus, ItemTransition> context) {
                        return GuardResult.pass();
                    }
                }
                """.formatted(modifier));
        sources.put("big.Items", facade(edges));
        return sources;
    }

    /**
     * The facade {@code big.Items}: the definition, its rejection, a policy per state but the last, and one
     * declaration per edge.
     */
    private static String facade(int edges) {
        StringBuilder facade = new StringBuilder("""
                package big;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import static big.ItemStatus.*;
                import static big.ItemTransition.*;
                @GenerateGraphApi
                @ItemsGraph.Definition(initial = S0, terminals = S9, state = ItemRefs.Status.class,
                    id = ItemRefs.Id.class)
                @ItemsGraph.Reject(from = S5, transition = T0, reason = "T0 leaves S0 only")
                """);
        for (int s = 0; s < STATES - 1; s++) {
            facade.append("@ItemsGraph.StatePolicy(state = S").append(s).append(", otherwise = \"no ").append(s)
                    .append("\")\n");
        }
        for (int t = 0; t < edges; t++) {
            int from = t % (STATES - 1);
            facade.append("@ItemsGraph.Transition(id = T").append(t).append(", from = S").append(from)
                    .append(", to = S").append(from + 1).append(", action = ItemActionsRefs.Advance.class")
                    .append(t % 10 == 0 ? ", guards = ItemActionsRefs.Allowed.class)\n" : ")\n");
        }
        return facade.append("public interface Items extends Machine<Item, ItemStatus, ItemTransition> {\n}\n")
                .toString();
    }

    /** The definition of {@link #machine} of {@code edges} edges written with the DSL, one declaration at a time. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static GraphDefinition<?, ?, ?> twin(Compilation.Result result, int edges)
            throws ReflectiveOperationException {
        Class states = result.load("big.ItemStatus");
        Class transitions = result.load("big.ItemTransition");
        MachineKey key = (MachineKey) result.load("big.ItemsRuntime").getField("KEY").get(null);
        GraphBuilder graph = StateGraph.define(key);
        graph = graph.initial(Enum.valueOf(states, "S0"));
        graph = graph.terminal(Enum.valueOf(states, "S9"));
        for (int s = 0; s < STATES - 1; s++) {
            graph = graph.statePolicy(Enum.valueOf(states, "S" + s), "no " + s);
        }
        graph = graph.reject(Enum.valueOf(states, "S5"), Enum.valueOf(transitions, "T0"), "T0 leaves S0 only");
        for (int t = 0; t < edges; t++) {
            int from = t % (STATES - 1);
            TransitionBuilder edge = graph.transition(Enum.valueOf(transitions, "T" + t),
                    Enum.valueOf(states, "S" + from), Enum.valueOf(states, "S" + (from + 1)));
            edge = edge.action("advance", (TransitionAction) (context) -> ActionOutcome.ok());
            if (t % 10 == 0) {
                edge = edge.guard("allowed", (TransitionGuard) (context) -> GuardResult.pass());
            }
            graph = edge.endTransition();
        }
        return graph.build();
    }
}
