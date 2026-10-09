package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The sub-machine annotations of a pair are vocabulary like any other: written anywhere but a facade they are
 * {@code SG606}, and on the facade of another machine that facade's builder refuses them as {@code SG606}, so the
 * machine they belong to is never left out of the generation without a word.
 */
class MisplacedSubmachineVocabularyTest {

    /** One sub-machine of {@code Docs} over {@code Pages}, typed with the states and identifiers of both. */
    private static final String SUBMACHINE = """
            @DocsPages.Submachine(state = DocStatus.REVIEW,
                exits = @DocsPages.Exit(on = PageStatus.DONE, transition = DocTransition.PUBLISH))
            """;

    @Test
    void refusesSubmachineVocabularyOnTypesThatAreNoFacade() {
        Map<String, String> sources = TypedFixtures.watching();
        sources.put("fx.DocService", "package fx;\n/** A service that is not a machine facade. */\n"
                + SUBMACHINE + "public final class DocService { }\n");
        sources.put("fx.Docs", sources.get("fx.Docs").replace("    @DocsGraph.Transition(id = SUBMIT",
                "    /** A nested type that is not a machine facade. */\n"
                        + SUBMACHINE.indent(4) + SUBMACHINE.indent(4) + "    interface Nested { }\n"
                        + "    @DocsGraph.Transition(id = SUBMIT"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(2, result.countError("SG606"), result.describe());
        assertEquals(2, result.errors().size(), result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.contains("fx.DocsPages.Submachine is read")
                && error.contains("never read on fx.DocService")), result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.contains("fx.DocsPages.Submachines is read")
                && error.contains("never read on fx.Docs.Nested")), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
    }

    @Test
    void refusesSubmachineVocabularyOfAnotherMachineOnAFacade() {
        Map<String, String> sources = TypedFixtures.watching();
        sources.put("fx.Pages", sources.get("fx.Pages").replace("@GenerateGraphApi\n",
                "@GenerateGraphApi\n" + SUBMACHINE + SUBMACHINE));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of("[SG606] fx.DocsPages.Submachine is the vocabulary of a pair of fx.Docs, not of fx.Pages",
                "[SG606] fx.DocsPages.Submachine is the vocabulary of a pair of fx.Docs, not of fx.Pages"),
                result.errors(), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
        assertFalse(result.hasGenerated("fx.PagesRuntime"), result.describe());
    }
}
