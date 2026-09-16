package io.github.graphmaquina.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.error.InvalidGraphException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The outline is the shared model: the DSL and the processor both normalise to it before validating. */
class MachineOutlineValidatorTest {

    static final List<String> STATES = List.of("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED");
    static final List<String> CATALOG = List.of("SUBMIT", "PUBLISH", "DISCARD", "SEND_BACK", "ARCHIVE", "RETOUCH");

    private static TransitionOutline move(String from, String transition, String to) {
        return new TransitionOutline(from, transition, to, TransitionKind.MOVE, Optional.empty());
    }

    private static TransitionOutline action(String from, String transition, String to, String onFailure) {
        return new TransitionOutline(from, transition, to, TransitionKind.ACTION, Optional.ofNullable(onFailure));
    }

    private static List<PolicyOutline> policies(String... states) {
        List<PolicyOutline> policies = new ArrayList<>();
        for (String state : states) {
            policies.add(new PolicyOutline(state, state + " rejects the rest", Map.of()));
        }
        return policies;
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
        MachineOutline outline = new MachineOutline("Doc/DocStatus", STATES, CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"), action("REVIEW", "PUBLISH", "PUBLISHED", null),
                        move("REVIEW", "SEND_BACK", "DRAFT"), move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                policies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of());
        assertEquals(List.of(), MachineOutlineValidator.validate(outline));
    }

    @Test
    void reportsStructuralCodesSortedByCodeAndState() {
        MachineOutline outline = new MachineOutline("Doc/DocStatus", STATES, CATALOG, "DRAFT",
                Set.of("ARCHIVED", "PUBLISHED"), Set.of("ARCHIVED"),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"), action("REVIEW", "PUBLISH", "PUBLISHED", "ARCHIVE"),
                        move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                policies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"),
                List.of(new WatchOutline("ARCHIVED", List.of("SUBMIT"))));
        List<InvalidGraphException.Problem> problems = MachineOutlineValidator.validate(outline);
        // SG202: PUBLISHED is terminal but declares ARCHIVE; SG205: ARCHIVED is in no cycle;
        // SG301: no ARCHIVE leaves REVIEW; SG401: no SUBMIT leaves ARCHIVED; SG402: ARCHIVED is terminal and watches.
        assertEquals(List.of("SG202", "SG205", "SG301", "SG401", "SG402"), codes(problems));
        assertTrue(problems.get(0).message().contains("PUBLISHED is terminal but declares [ARCHIVE]"));
    }

    @Test
    void unreachableAndUnallowedCycle() {
        MachineOutline outline = new MachineOutline("Doc/DocStatus", STATES, CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of(),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"), move("REVIEW", "SEND_BACK", "DRAFT"),
                        move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                policies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of());
        List<InvalidGraphException.Problem> problems = MachineOutlineValidator.validate(outline);
        assertEquals(List.of("SG203", "SG203", "SG204"), codes(problems));
        assertTrue(problems.get(0).message().startsWith("PUBLISHED is unreachable"));
        assertTrue(problems.get(2).message().contains("[DRAFT, REVIEW, DRAFT]"));
    }

    @Test
    void duplicateKeysAndBlankReasonsAreReportedOnTheOutlineToo() {
        MachineOutline outline = new MachineOutline("Doc/DocStatus", STATES, CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"), move("DRAFT", "SUBMIT", "PUBLISHED"),
                        action("REVIEW", "PUBLISH", "PUBLISHED", null), move("REVIEW", "SEND_BACK", "DRAFT"),
                        move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                List.of(new PolicyOutline("DRAFT", " ", Map.of("PUBLISH", "not from a draft")),
                        new PolicyOutline("DRAFT", "twice", Map.of()),
                        new PolicyOutline("REVIEW", "in review", Map.of("PUBLISH", "already declared")),
                        new PolicyOutline("PUBLISHED", "published", Map.of()),
                        new PolicyOutline("ARCHIVED", "archived", Map.of())),
                List.of());
        assertEquals(List.of("SG102", "SG102", "SG103", "SG702"), codes(MachineOutlineValidator.validate(outline)));
    }

    @Test
    void outlineMustUseKnownStatesAndIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> new MachineOutline("X", STATES, CATALOG, "NOPE", Set.of(),
                Set.of(), List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MachineOutline("X", STATES, CATALOG, "DRAFT", Set.of(),
                Set.of(), List.of(move("DRAFT", "NOPE", "REVIEW")), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MachineOutline("X", STATES, CATALOG, "DRAFT", Set.of(),
                Set.of(), List.of(action("REVIEW", "PUBLISH", "PUBLISHED", "NOPE")), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MachineOutline("X", STATES, CATALOG, "DRAFT", Set.of(),
                Set.of(), List.of(), List.of(), List.of(new WatchOutline("DRAFT", List.of("NOPE")))));
        assertThrows(IllegalArgumentException.class,
                () -> new TransitionOutline("DRAFT", "SUBMIT", "REVIEW", TransitionKind.MOVE, Optional.of("PUBLISH")));
    }
}
