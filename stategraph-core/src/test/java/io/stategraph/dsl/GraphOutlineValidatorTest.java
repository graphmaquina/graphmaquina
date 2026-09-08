package io.stategraph.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.stategraph.api.EdgeKind;
import io.stategraph.error.InvalidGraphException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GraphOutlineValidatorTest {

    static final List<String> STATES = List.of("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED");

    /** Full matrix: the given cells, STAY on the diagonal, FORBID elsewhere. */
    private static List<CellOutline> matrix(Map<String, EdgeKind> moving, Map<String, String> onFailure) {
        List<CellOutline> cells = new ArrayList<>();
        for (String from : STATES) {
            for (String to : STATES) {
                String pair = from + ">" + to;
                EdgeKind kind = from.equals(to) ? EdgeKind.STAY : moving.getOrDefault(pair, EdgeKind.FORBID);
                cells.add(new CellOutline(from, to, kind, Optional.ofNullable(onFailure.get(pair))));
            }
        }
        return cells;
    }

    private static List<String> codes(List<InvalidGraphException.Problem> problems) {
        List<String> codes = new ArrayList<>();
        for (InvalidGraphException.Problem problem : problems) {
            codes.add(problem.code());
        }
        return codes;
    }

    @Test
    void validOutlineHasNoProblems() {
        GraphOutline outline = new GraphOutline("Doc/DocStatus", STATES, "DRAFT", Set.of("ARCHIVED"), Set.of("REVIEW"),
                matrix(Map.of("DRAFT>REVIEW", EdgeKind.MOVE, "REVIEW>PUBLISHED", EdgeKind.ACTION, "REVIEW>DRAFT", EdgeKind.MOVE,
                        "PUBLISHED>ARCHIVED", EdgeKind.MOVE), Map.of()),
                List.of());
        assertEquals(List.of(), GraphOutlineValidator.validate(outline));
    }

    @Test
    void reportsStructuralCodesSortedByCodeAndState() {
        GraphOutline outline = new GraphOutline("Doc/DocStatus", STATES, "DRAFT", Set.of("ARCHIVED", "PUBLISHED"), Set.of("ARCHIVED"),
                matrix(Map.of("DRAFT>REVIEW", EdgeKind.MOVE, "REVIEW>PUBLISHED", EdgeKind.ACTION, "PUBLISHED>ARCHIVED", EdgeKind.MOVE),
                        Map.of("REVIEW>PUBLISHED", "ARCHIVED")),
                List.of(new WatchOutline("ARCHIVED", List.of("DRAFT"))));
        List<InvalidGraphException.Problem> problems = GraphOutlineValidator.validate(outline);
        // SG202: PUBLISHED is terminal but exits; SG205: ARCHIVED not in any cycle; SG301: onFailure ARCHIVED is FORBID from REVIEW;
        // SG401: watch of ARCHIVED leads to DRAFT which is FORBID; SG402: ARCHIVED is terminal and watches.
        assertEquals(List.of("SG202", "SG205", "SG301", "SG401", "SG402"), codes(problems));
        assertTrue(problems.get(0).message().contains("PUBLISHED is terminal but exits to [ARCHIVED]"));
    }

    @Test
    void unreachableAndUnallowedCycle() {
        GraphOutline outline = new GraphOutline("Doc/DocStatus", STATES, "DRAFT", Set.of("ARCHIVED"), Set.of(),
                matrix(Map.of("DRAFT>REVIEW", EdgeKind.MOVE, "REVIEW>DRAFT", EdgeKind.MOVE, "PUBLISHED>ARCHIVED", EdgeKind.MOVE), Map.of()),
                List.of());
        List<InvalidGraphException.Problem> problems = GraphOutlineValidator.validate(outline);
        assertEquals(List.of("SG203", "SG203", "SG204"), codes(problems));
        assertTrue(problems.get(0).message().startsWith("PUBLISHED is unreachable"));
        assertTrue(problems.get(2).message().contains("DRAFT → REVIEW → DRAFT"));
    }

    @Test
    void outlineMustBeAFullMatrixOverKnownStates() {
        assertThrows(IllegalArgumentException.class, () -> new GraphOutline("X", STATES, "DRAFT", Set.of(), Set.of(),
                List.of(new CellOutline("DRAFT", "DRAFT", EdgeKind.STAY, Optional.empty())), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new GraphOutline("X", STATES, "NOPE", Set.of(), Set.of(),
                matrix(Map.of(), Map.of()), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new GraphOutline("X", STATES, "DRAFT", Set.of(), Set.of(),
                matrix(Map.of("REVIEW>PUBLISHED", EdgeKind.ACTION), Map.of("REVIEW>PUBLISHED", "NOPE")), List.of()));
    }
}
