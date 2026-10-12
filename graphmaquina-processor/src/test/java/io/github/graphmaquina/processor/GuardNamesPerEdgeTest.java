package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A rejection names the guard that rejected, and a generated guard is named by its member's simple name, so the guards
 * one edge asks for must have members of distinct names. Two hosts that each export a guard under one member name give
 * two symbols, {@code DocActionsRefs.Approved} and {@code ReviewsRefs.Approved}: one edge that asks for both is refused
 * with {@code SG105}, which names both symbols, while two edges may each ask for one of them.
 */
class GuardNamesPerEdgeTest {

    /** A second host whose guard has the member name of {@code DocActions.approved}. */
    private static final String REVIEWS = """
            package fx;
            import io.github.graphmaquina.annotation.GraphGuard;
            import io.github.graphmaquina.api.GuardContext;
            import io.github.graphmaquina.api.GuardResult;
            public final class Reviews {
                @GraphGuard public GuardResult approved(GuardContext<Doc, DocStatus, DocTransition> context) {
                    return GuardResult.reject("the review is not done");
                }
            }
            """;

    @Test
    void oneEdgeCannotAskForTwoGuardsOfOneMemberName() {
        Map<String, String> sources = DefaultHookFixtures.sources("",
                ", guards = {DocActionsRefs.Approved.class, ReviewsRefs.Approved.class}");
        sources.put("fx.Reviews", REVIEWS);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError("SG105"), result.describe());
        List<String> errors = result.errors();
        assertTrue(errors.stream().anyMatch((String error) -> error.contains(
                "the guards fx.DocActionsRefs.Approved and fx.ReviewsRefs.Approved share the name approved")),
                result.describe());
    }

    @Test
    void twoEdgesMayEachAskForAGuardOfOneMemberName() {
        Map<String, String> sources = DefaultHookFixtures.sources("",
                ", guards = DocActionsRefs.Approved.class");
        sources.compute("fx.Docs", (String key, String text) -> text.replace(
                "@DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)",
                "@DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW, guards = ReviewsRefs.Approved.class)"));
        sources.put("fx.Reviews", REVIEWS);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
    }
}
