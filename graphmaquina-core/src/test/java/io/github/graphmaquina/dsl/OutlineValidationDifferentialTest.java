package io.github.graphmaquina.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.error.InvalidGraphException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Validation may become cheaper but not different: every outline of the corpus must still answer the very
 * list of problems {@link BaselineOutlineValidator} answers, message by message and in the same order.
 *
 * <p>The corpus enumerates the bitmask of the nine edges three states admit, crossed with the three shapes a
 * cycle permission takes, with and without a terminal state, and with no policy, one per state, or one per
 * state that also rejects identifiers. The declared fixtures that follow carry what the enumeration cannot
 * express: duplicate keys, blank reasons, recoveries that are their own key, absent or without an action, recovery
 * transitions on two edges that share a key, a watch on a terminal state, a watch on an identifier that leaves
 * nowhere, and the fixtures of {@link MachineOutlineValidatorTest}, large circulants included, except the five whose
 * names hold a slash: the frozen validator keys an edge by its printed form and answers those differently from the
 * live one.
 */
class OutlineValidationDifferentialTest {

    /** Every code the validator can report; the corpus is worthless if it does not reach all of them. */
    private static final List<String> EVERY_CODE = List.of("SG102", "SG103", "SG202", "SG203", "SG204", "SG205",
            "SG301", "SG401", "SG402", "SG507", "SG701", "SG702");

    private static final List<String> STATES = List.of("A", "B", "C");
    private static final List<String> CATALOG = catalog();
    private static final List<String> DOC_STATES = List.of("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED");
    private static final List<String> DOC_CATALOG =
            List.of("SUBMIT", "PUBLISH", "DISCARD", "SEND_BACK", "ARCHIVE", "RETOUCH");
    private static final List<Set<String>> PERMISSIONS =
            List.of(Set.<String>of(), Set.of("A", "B", "C"), Set.of("A"));
    private static final List<Set<String>> TERMINALS = List.of(Set.<String>of(), Set.of("C"));
    private static final List<PolicyOutline> PLAIN = declaredPolicies(false);
    private static final List<List<PolicyOutline>> POLICIES =
            List.of(List.<PolicyOutline>of(), PLAIN, declaredPolicies(true));
    private static final List<TransitionOutline> RING =
            List.of(move("A", "E1", "B"), move("B", "E5", "C"), move("C", "E6", "A"));
    private static final List<MachineOutline> CORPUS = corpus();

    @Test
    void theCorpusEnumeratesEveryGraphOfThreeStatesAndTheDeclaredFixtures() {
        assertEquals(512 * PERMISSIONS.size() * TERMINALS.size() * POLICIES.size() + 21, CORPUS.size());
    }

    @Test
    void theCorpusReachesEveryStructuralCode() {
        Set<String> codes = new LinkedHashSet<>();
        for (MachineOutline outline : CORPUS) {
            for (InvalidGraphException.Problem problem : MachineOutlineValidator.validate(outline)) {
                codes.add(problem.code());
            }
        }
        assertEquals(EVERY_CODE, codes.stream().sorted().toList());
    }

    @Test
    void everyOutlineOfTheCorpusReportsWhatTheFrozenValidatorReports() {
        for (MachineOutline outline : CORPUS) {
            assertEquals(BaselineOutlineValidator.validate(outline),
                         MachineOutlineValidator.validate(outline),
                         outline.name());
        }
    }

    private static List<MachineOutline> corpus() {
        List<MachineOutline> corpus = new ArrayList<>();
        for (int mask = 0; mask < 512; mask++) {
            List<TransitionOutline> transitions = transitions(mask);
            for (Set<String> allowed : PERMISSIONS) {
                for (Set<String> terminals : TERMINALS) {
                    for (List<PolicyOutline> policies : POLICIES) {
                        corpus.add(outline("mask" + mask, terminals, allowed, transitions, policies, List.of()));
                    }
                }
            }
        }
        corpus.addAll(declaredFixtures());
        corpus.addAll(fixturesOfTheValidatorTest());
        return List.copyOf(corpus);
    }

    /**
     * The nine edges three states admit, identified {@code E0..E8}: {@code Ek} runs from {@code k / 3} to
     * {@code k % 3}.
     */
    private static List<String> catalog() {
        List<String> catalog = new ArrayList<>();
        for (int edge = 0; edge < 9; edge++) {
            catalog.add("E" + edge);
        }
        return List.copyOf(catalog);
    }

    private static List<TransitionOutline> transitions(int mask) {
        List<TransitionOutline> transitions = new ArrayList<>();
        for (int edge = 0; edge < 9; edge++) {
            if ((mask & (1 << edge)) != 0) {
                transitions.add(move(STATES.get(edge / 3), CATALOG.get(edge), STATES.get(edge % 3)));
            }
        }
        return List.copyOf(transitions);
    }

    /** One policy per state, whose reason is {@code reject}; the rejecting shape also names an identifier. */
    private static List<PolicyOutline> declaredPolicies(boolean rejecting) {
        List<PolicyOutline> policies = new ArrayList<>();
        for (String state : STATES) {
            Map<String, String> rejections = rejecting ? Map.of("E0", "reject") : Map.of();
            policies.add(new PolicyOutline(state, "reject", rejections));
        }
        return List.copyOf(policies);
    }

    private static List<MachineOutline> declaredFixtures() {
        List<MachineOutline> outlines = new ArrayList<>();
        Set<String> everywhere = Set.of("A", "B", "C");
        outlines.add(outline("duplicate-key", Set.of(), everywhere,
                List.of(move("A", "E1", "B"), move("A", "E1", "C"), move("A", "E2", "C"), move("A", "E2", "B"),
                        move("B", "E5", "C"), move("C", "E6", "A")),
                PLAIN, List.of()));
        outlines.add(outline("edge-declared-twice", Set.of("C"), everywhere,
                List.of(move("A", "E1", "B"), move("B", "E5", "C"), move("C", "E6", "A"), move("C", "E6", "A")),
                PLAIN, List.of()));
        outlines.add(outline("blank-reasons", Set.of(), everywhere, RING,
                List.of(new PolicyOutline("A", " ", Map.of("E1", " ")), new PolicyOutline("A", "twice", Map.of()),
                        new PolicyOutline("B", "", Map.of()), new PolicyOutline("C", "reject", Map.of("E6", ""))),
                List.of()));
        outlines.add(outline("recoveries", Set.of(), everywhere,
                List.of(action("A", "E1", "B", "E1"), action("B", "E5", "C", "E3"),
                        new TransitionOutline("C", "E6", "A", TransitionKind.MOVE, Optional.of("E2"))),
                PLAIN, List.of()));
        outlines.add(outline("recovery-that-exists", Set.of(), everywhere,
                List.of(action("A", "E1", "B", "E2"), move("A", "E2", "C"), move("B", "E5", "C"),
                        move("C", "E6", "A")),
                PLAIN, List.of()));
        outlines.add(outline("watches", Set.of("C"), Set.of("A", "B"),
                List.of(move("A", "E1", "B"), move("B", "E3", "A"), move("B", "E5", "C")), PLAIN,
                List.of(new WatchOutline("C", List.of("E6")), new WatchOutline("B", List.of("E5", "E7")))));
        outlines.add(outline("recoveries-of-a-duplicate-key", Set.of(), everywhere,
                List.of(recovering("A", "E1", "B", TransitionKind.ACTION, "E4", "E3"),
                        recovering("A", "E1", "C", TransitionKind.ACTION, null, "E0", "E3"),
                        move("B", "E5", "C"), move("C", "E6", "A")),
                PLAIN, List.of()));
        return outlines;
    }

    /**
     * The outlines {@link MachineOutlineValidatorTest} asserts on, so the oracle covers them edge for edge, except the
     * five whose names hold a slash, which the frozen validator keys by their printed form and answers differently.
     */
    private static List<MachineOutline> fixturesOfTheValidatorTest() {
        List<MachineOutline> outlines = new ArrayList<>(declarationFixturesOfTheValidatorTest());
        outlines.addAll(recoveryFixturesOfTheValidatorTest());
        outlines.add(CirculantOutlines.of(500, 8, true));
        outlines.add(CirculantOutlines.of(500, 8, false));
        return outlines;
    }

    /** The fixtures of the validator test that declare policies, watches and {@code onFailure} routes. */
    private static List<MachineOutline> declarationFixturesOfTheValidatorTest() {
        List<MachineOutline> outlines = new ArrayList<>();
        outlines.add(new MachineOutline("Doc/DocStatus", DOC_STATES, DOC_CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"), action("REVIEW", "PUBLISH", "PUBLISHED", null),
                        move("REVIEW", "SEND_BACK", "DRAFT"), move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                docPolicies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of()));
        outlines.add(new MachineOutline("Doc/DocStatus", DOC_STATES, DOC_CATALOG, "DRAFT",
                Set.of("ARCHIVED", "PUBLISHED"), Set.of("ARCHIVED"),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"), action("REVIEW", "PUBLISH", "PUBLISHED", "ARCHIVE"),
                        move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                docPolicies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"),
                List.of(new WatchOutline("ARCHIVED", List.of("SUBMIT")))));
        outlines.add(new MachineOutline("Doc/DocStatus", DOC_STATES, DOC_CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of(),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"), move("REVIEW", "SEND_BACK", "DRAFT"),
                        move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                docPolicies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of()));
        outlines.add(new MachineOutline("Doc/DocStatus", DOC_STATES, DOC_CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"), move("DRAFT", "SUBMIT", "PUBLISHED"),
                        action("REVIEW", "PUBLISH", "PUBLISHED", null), move("REVIEW", "SEND_BACK", "DRAFT"),
                        move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                List.of(new PolicyOutline("DRAFT", " ", Map.of("PUBLISH", "not from a draft")),
                        new PolicyOutline("DRAFT", "twice", Map.of()),
                        new PolicyOutline("REVIEW", "in review", Map.of("PUBLISH", "already declared")),
                        new PolicyOutline("PUBLISHED", "published", Map.of()),
                        new PolicyOutline("ARCHIVED", "archived", Map.of())),
                List.of()));
        outlines.add(new MachineOutline("Doc/DocStatus", DOC_STATES, DOC_CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(new TransitionOutline("DRAFT", "SUBMIT", "REVIEW", TransitionKind.MOVE,
                                Optional.of("DISCARD")),
                        new TransitionOutline("DRAFT", "DISCARD", "ARCHIVED", TransitionKind.MOVE,
                                Optional.empty()),
                        action("REVIEW", "PUBLISH", "PUBLISHED", null), move("REVIEW", "SEND_BACK", "DRAFT"),
                        move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                docPolicies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of()));
        return outlines;
    }

    /** The fixtures of the validator test that declare {@code recover} transitions. */
    private static List<MachineOutline> recoveryFixturesOfTheValidatorTest() {
        List<MachineOutline> outlines = new ArrayList<>();
        outlines.add(new MachineOutline("Doc/DocStatus", DOC_STATES, DOC_CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"),
                        recovering("REVIEW", "PUBLISH", "PUBLISHED", TransitionKind.ACTION, "SEND_BACK", "SEND_BACK"),
                        move("REVIEW", "SEND_BACK", "DRAFT"), move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                docPolicies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of()));
        outlines.add(new MachineOutline("Doc/DocStatus", DOC_STATES, DOC_CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"),
                        recovering("REVIEW", "PUBLISH", "PUBLISHED", TransitionKind.ACTION, null, "PUBLISH"),
                        move("REVIEW", "SEND_BACK", "DRAFT"), move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                docPolicies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of()));
        outlines.add(new MachineOutline("Doc/DocStatus", DOC_STATES, DOC_CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"),
                        recovering("DRAFT", "PUBLISH", "PUBLISHED", TransitionKind.ACTION, null, "SEND_BACK"),
                        recovering("REVIEW", "PUBLISH", "PUBLISHED", TransitionKind.ACTION, null, "SEND_BACK"),
                        move("REVIEW", "SEND_BACK", "DRAFT"), move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                docPolicies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of()));
        outlines.add(new MachineOutline("Doc/DocStatus", DOC_STATES, DOC_CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(recovering("DRAFT", "SUBMIT", "REVIEW", TransitionKind.MOVE, null, "DISCARD"),
                        action("REVIEW", "PUBLISH", "PUBLISHED", null), move("REVIEW", "SEND_BACK", "DRAFT"),
                        move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                docPolicies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of()));
        outlines.add(new MachineOutline("Doc/DocStatus", DOC_STATES, DOC_CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"),
                        recovering("REVIEW", "PUBLISH", "PUBLISHED", TransitionKind.ACTION, null, "ARCHIVE", "ARCHIVE"),
                        move("REVIEW", "SEND_BACK", "DRAFT"), move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                docPolicies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of()));
        outlines.add(new MachineOutline("Doc/DocStatus", DOC_STATES, DOC_CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"),
                        recovering("REVIEW", "PUBLISH", "PUBLISHED", TransitionKind.ACTION, "ARCHIVE", "ARCHIVE"),
                        move("REVIEW", "SEND_BACK", "DRAFT"), move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                docPolicies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of()));
        outlines.add(new MachineOutline("Doc/DocStatus", DOC_STATES, DOC_CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"),
                        recovering("REVIEW", "PUBLISH", "PUBLISHED", TransitionKind.ACTION, "RETOUCH", "DISCARD",
                                "ARCHIVE"),
                        move("REVIEW", "SEND_BACK", "DRAFT"), move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                docPolicies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of()));
        return outlines;
    }

    private static MachineOutline outline(String name, Set<String> terminals, Set<String> cycleAllowed,
            List<TransitionOutline> transitions, List<PolicyOutline> policies, List<WatchOutline> watches) {
        return new MachineOutline(name, STATES, CATALOG, "A", terminals, cycleAllowed, transitions, policies,
                watches);
    }

    private static TransitionOutline move(String from, String transition, String to) {
        return new TransitionOutline(from, transition, to, TransitionKind.MOVE, Optional.empty());
    }

    private static TransitionOutline action(String from, String transition, String to, String onFailure) {
        return new TransitionOutline(from, transition, to, TransitionKind.ACTION, Optional.ofNullable(onFailure));
    }

    private static TransitionOutline recovering(String from, String transition, String to, TransitionKind kind,
            String onFailure, String... recoveries) {
        return new TransitionOutline(from, transition, to, kind, Optional.ofNullable(onFailure), List.of(recoveries));
    }

    private static List<PolicyOutline> docPolicies(String... states) {
        List<PolicyOutline> policies = new ArrayList<>();
        for (String state : states) {
            policies.add(new PolicyOutline(state, state + " rejects the rest", Map.of()));
        }
        return List.copyOf(policies);
    }
}
