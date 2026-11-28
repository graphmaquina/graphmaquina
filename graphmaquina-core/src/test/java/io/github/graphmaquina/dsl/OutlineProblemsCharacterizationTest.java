package io.github.graphmaquina.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.api.AutomaticChain;
import io.github.graphmaquina.api.TransitionKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins, by the SHA-256 of their printed problem lists taken on v2.0.9 (e5521d52), what {@link MachineOutlineValidator}
 * reports for a fixed corpus of random outlines: a spine that reaches a terminal plus random edges, cycle
 * permissions on some states, automatic edges, failure routes through another edge of the same state that may
 * continue the chain, both automatic chains, and state names that are equal but distinct instances in the edges. The
 * corpus reaches unauthorized cycles, unused permissions, automatic cycles and both continuation rules on otherwise
 * clean outlines.
 */
class OutlineProblemsCharacterizationTest {

    @Test
    void randomOutlinesKeepTheirProblems() {
        Random random = new Random(0x0071_1E00L);
        MessageDigest digest = sha256();
        StringBuilder codes = new StringBuilder();
        for (int sample = 0; sample < 6_000; sample++) {
            String problems = MachineOutlineValidator.validate(outline(random)).toString();
            codes.append(problems);
            digest.update(problems.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        for (DiagnosticCode code : List.of(DiagnosticCode.SG204, DiagnosticCode.SG205, DiagnosticCode.SG206,
                DiagnosticCode.SG305, DiagnosticCode.SG306)) {
            assertTrue(codes.indexOf(code.name()) >= 0, code.name());
        }
        assertEquals("0be18ae19e9a180a232f62903d86df9ce2907fd044cf31374086e2e5f67a1678",
                HexFormat.of().formatHex(digest.digest()));
    }

    private static MachineOutline outline(Random random) {
        int size = 2 + random.nextInt(6);
        List<String> states = new ArrayList<>();
        List<PolicyOutline> policies = new ArrayList<>();
        Set<String> allowed = new LinkedHashSet<>();
        for (int i = 0; i < size; i++) {
            states.add("S" + i);
            policies.add(new PolicyOutline("S" + i, "no", Map.of()));
            if (random.nextInt(4) == 0) {
                allowed.add("S" + i);
            }
        }
        AutomaticChain chain = random.nextBoolean() ? AutomaticChain.AFTER_COMMIT : AutomaticChain.ON_ADVANCE;
        return new MachineOutline("m", states, List.of("T0", "T1", "T2", "T3"), "S0", Set.of("S" + (size - 1)),
                allowed, routed(random, edges(random, size)), policies, List.of(), List.of(), List.of(), chain);
    }

    /** A spine S0 → S1 → … → the terminal, then random edges; at most one edge per state and identifier. */
    private static List<String[]> edges(Random random, int size) {
        List<String[]> edges = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < size - 1; i++) {
            add(random, edges, keys, i, i + 1);
        }
        for (int extra = random.nextInt(2 * size); extra > 0; extra--) {
            add(random, edges, keys, random.nextInt(size - 1), random.nextInt(size));
        }
        return edges;
    }

    private static void add(Random random, List<String[]> edges, Set<String> keys, int from, int to) {
        String id = "T" + random.nextInt(4);
        if (keys.add(from + "/" + id)) {
            edges.add(new String[] {"S" + from, id, "S" + to, random.nextInt(3) == 0 ? "auto" : "manual"});
        }
    }

    /** Each edge as an outline; one in three routes its failure through another edge of the same state. */
    private static List<TransitionOutline> routed(Random random, List<String[]> edges) {
        List<TransitionOutline> outlines = new ArrayList<>();
        for (String[] edge : edges) {
            outlines.add(transition(random, edges, edge));
        }
        return outlines;
    }

    private static TransitionOutline transition(Random random, List<String[]> edges, String[] edge) {
        Optional<String> route = random.nextInt(3) == 0 ? sibling(edges, edge) : Optional.empty();
        if (route.isEmpty()) {
            return new TransitionOutline(new String(edge[0]), edge[1], new String(edge[2]), TransitionKind.MOVE,
                    Optional.empty(), List.of(), false, edge[3].equals("auto"), false, List.of());
        }
        boolean continues = random.nextBoolean();
        String through = route.get();
        return random.nextBoolean() ? viaRule(edge, through, continues) : viaOnFailure(edge, through, continues);
    }

    /** The route as a recovery rule, which continues the chain when {@code continues}. */
    private static TransitionOutline viaRule(String[] edge, String route, boolean continues) {
        return new TransitionOutline(new String(edge[0]), edge[1], new String(edge[2]), TransitionKind.ACTION,
                Optional.empty(), List.of(route), false, edge[3].equals("auto"), false,
                continues ? List.of(route) : List.of());
    }

    /** The route as the edge's onFailure, which continues the chain when {@code continues}. */
    private static TransitionOutline viaOnFailure(String[] edge, String route, boolean continues) {
        return new TransitionOutline(new String(edge[0]), edge[1], new String(edge[2]), TransitionKind.ACTION,
                Optional.of(route), List.of(), false, edge[3].equals("auto"), continues, List.of());
    }

    private static Optional<String> sibling(List<String[]> edges, String[] edge) {
        for (String[] other : edges) {
            if (other != edge && other[0].equals(edge[0])) {
                return Optional.of(other[1]);
            }
        }
        return Optional.empty();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException(absent);
        }
    }
}
