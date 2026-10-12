package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the processor does more of as a compilation grows. Each test compiles two projects that differ only in size,
 * behind {@link CountingProcessor}, and requires the count of an operation whose cost grows with the whole compilation
 * to stay the same: the scans of a round, which visit every element of it, do not multiply with the machines.
 */
class ProcessorScaleTest {

    private static final int STATES = 10;

    /**
     * The first round is scanned for the generated vocabulary once, for every vocabulary annotation type at a time,
     * however many machines, and so annotation types, the compilation declares.
     */
    @Test
    void scansEachRoundAsManyTimesWhateverTheNumberOfMachines() {
        List<Integer> few = scans(project(4, STATES - 1));
        List<Integer> many = scans(project(16, STATES - 1));

        assertEquals(few, many, "scans per round with 4 machines, then with 16");
    }

    private static List<Integer> scans(Map<String, String> sources) {
        CountingProcessor.reset();
        Compilation.Result result = Compilation.compileWithoutLifecycle(Compilation.prepare(sources),
                List.of("-processor", CountingProcessor.class.getName()));
        assertTrue(result.success(), result.describe());
        return new ArrayList<>(CountingProcessor.SCANS_PER_ROUND);
    }

    /**
     * {@code machines} machines of package {@code scale}, each with ten states in a line, a policy for each state but
     * the terminal one, and {@code transitions} edges, at least nine, the {@code t}-th leaving state {@code t % 9}
     * for the next one, each running its own action of the machine's own host.
     */
    static Map<String, String> project(int machines, int transitions) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (int machine = 0; machine < machines; machine++) {
            machine(sources, machine, transitions);
        }
        return sources;
    }

    private static void machine(Map<String, String> sources, int i, int transitions) {
        StringBuilder states = new StringBuilder("package scale;\npublic enum M" + i + "Status { ");
        for (int s = 0; s < STATES; s++) {
            states.append(s == 0 ? "" : ", ").append('S').append(s);
        }
        sources.put("scale.M" + i + "Status", states.append(" }\n").toString());
        StringBuilder identifiers = new StringBuilder("package scale;\npublic enum M" + i + "Transition { ");
        for (int t = 0; t < transitions; t++) {
            identifiers.append(t == 0 ? "" : ", ").append('T').append(t);
        }
        sources.put("scale.M" + i + "Transition", identifiers.append(" }\n").toString());
        sources.put("scale.E" + i, """
                package scale;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class E%1$d {
                    @GraphProperty private M%1$dStatus status = M%1$dStatus.S0;
                    @GraphProperty public String id() { return "e"; }
                    public M%1$dStatus status() { return status; }
                    public void status(M%1$dStatus value) { status = value; }
                }
                """.formatted(i));
        StringBuilder host = new StringBuilder("""
                package scale;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                public final class H%1$d {
                """.formatted(i));
        for (int a = 0; a < transitions; a++) {
            host.append("    @GraphAction public ActionOutcome a").append(a).append("(TransitionContext<E").append(i)
                    .append(", M").append(i).append("Status, M").append(i)
                    .append("Transition> context) { return ActionOutcome.ok(); }\n");
        }
        sources.put("scale.H" + i, host.append("}\n").toString());
        StringBuilder facade = new StringBuilder("""
                package scale;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import static scale.M%1$dStatus.*;
                import static scale.M%1$dTransition.*;
                @GenerateGraphApi
                @M%1$dGraph.Definition(initial = S0, terminals = S%2$d, state = E%1$dRefs.Status.class,
                    id = E%1$dRefs.Id.class)
                """.formatted(i, STATES - 1));
        for (int s = 0; s < STATES - 1; s++) {
            facade.append("@M").append(i).append("Graph.StatePolicy(state = S").append(s)
                    .append(", otherwise = \"no\")\n");
        }
        for (int t = 0; t < transitions; t++) {
            int from = t % (STATES - 1);
            facade.append("@M").append(i).append("Graph.Transition(id = T").append(t).append(", from = S")
                    .append(from).append(", to = S").append(from + 1).append(", action = H").append(i)
                    .append("Refs.A").append(t).append(".class)\n");
        }
        facade.append("public interface M").append(i).append(" extends Machine<E").append(i).append(", M")
                .append(i).append("Status, M").append(i).append("Transition> {\n}\n");
        sources.put("scale.M" + i, facade.toString());
    }
}
