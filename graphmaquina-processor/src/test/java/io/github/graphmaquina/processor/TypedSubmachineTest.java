package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The processor reads every {@code Submachine} a facade declares with the vocabulary of one of its pairs: the child
 * is reached through a property of the parent's entity, and the parent through a property of the child's entity or
 * an exported lookup, each a single value of the right type; a child over the parent's own entity takes none of the
 * three. A pair that a sub-machine uses is a used pair. These tests are about reading and diagnostics only.
 */
class TypedSubmachineTest {

    /** {@code REVIEW} of {@code Docs} bound to {@code Pages}, through a property of each entity. */
    static final String BOUND = """
            @DocsPages.Submachine(state = REVIEW, child = DocRefs.Current.class, parent = PageRefs.Doc.class,
                exits = @DocsPages.Exit(on = PageStatus.DONE, transition = COMPLETE))
            """;

    /** An edge from {@code REVIEW} that no operation executes, so it can be the exit of a sub-machine there. */
    static final String COMPLETE = "@DocsGraph.Transition(id = COMPLETE, from = REVIEW, to = PUBLISHED)\n";

    @Test
    void readsABindingThroughAPropertyOfEachEntity() {
        Compilation.Result result = Compilation.compileTyped(bound(BOUND));

        assertTrue(result.success(), result.describe());
        assertEquals(List.of(), result.errors(), result.describe());
    }

    @Test
    void readsABindingWhoseParentIsFoundByALookup() {
        Compilation.Result result = Compilation.compileTyped(bound("""
                @DocsPages.Submachine(state = REVIEW, child = DocRefs.Current.class,
                    parentLookup = DocLookupsRefs.DocOf.class,
                    exits = @DocsPages.Exit(on = PageStatus.DONE, transition = COMPLETE))
                """));

        assertTrue(result.success(), result.describe());
        assertEquals(List.of(), result.errors(), result.describe());
    }

    @Test
    void readsABindingOfAMachineOverTheParentsOwnEntity() {
        Compilation.Result result = Compilation.compileTyped(reviewed("""
                @DocsReviews.Submachine(state = REVIEW,
                    exits = @DocsReviews.Exit(on = ReviewStatus.APPROVED, transition = COMPLETE))
                """));

        assertTrue(result.success(), result.describe());
        assertEquals(List.of(), result.errors(), result.describe());
    }

    @Test
    void sg806AChildOverAnotherEntityNeedsChildAndOneWayBack() {
        Compilation.Result result = Compilation.compileTyped(bound("""
                @DocsPages.Submachine(state = REVIEW, parent = PageRefs.Doc.class,
                    exits = @DocsPages.Exit(on = PageStatus.DONE, transition = COMPLETE))
                @DocsPages.Submachine(state = REVIEW, child = DocRefs.Current.class,
                    exits = @DocsPages.Exit(on = PageStatus.DONE, transition = COMPLETE))
                @DocsPages.Submachine(state = REVIEW, child = DocRefs.Current.class, parent = PageRefs.Doc.class,
                    parentLookup = DocLookupsRefs.DocOf.class,
                    exits = @DocsPages.Exit(on = PageStatus.DONE, transition = COMPLETE))
                """));

        String needs = "[SG806] Docs.REVIEW: Pages governs Page, so the sub-machine needs child and one of parent or"
                + " parentLookup";
        assertEquals(List.of(needs, needs, needs), result.errors(), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
    }

    @Test
    void sg806AChildOverTheParentsOwnEntityTakesNoWayToIt() {
        Compilation.Result result = Compilation.compileTyped(reviewed("""
                @DocsReviews.Submachine(state = REVIEW, child = DocRefs.Current.class,
                    exits = @DocsReviews.Exit(on = ReviewStatus.APPROVED, transition = COMPLETE))
                """));

        assertEquals(List.of("[SG806] Docs.REVIEW: Reviews governs Doc itself, so child, parent and parentLookup must be"
                + " empty"), result.errors(), result.describe());
    }

    @Test
    void sg607EachWayHoldsOneValueAtMost() {
        Compilation.Result result = Compilation.compileTyped(bound("""
                @DocsPages.Submachine(state = REVIEW, child = {DocRefs.Current.class, DocRefs.Current.class},
                    parent = {PageRefs.Doc.class, PageRefs.Doc.class},
                    parentLookup = {DocLookupsRefs.DocOf.class, DocLookupsRefs.DocOf.class},
                    exits = @DocsPages.Exit(on = PageStatus.DONE, transition = COMPLETE))
                """));

        assertEquals(List.of("[SG607] child declares 2 values but holds one at most",
                "[SG607] parent declares 2 values but holds one at most",
                "[SG607] parentLookup declares 2 values but holds one at most"), result.errors(), result.describe());
    }

    @Test
    void sg503EachPropertyReturnsAnOptionalOfTheOtherEntity() {
        Compilation.Result result = Compilation.compileTyped(bound("""
                @DocsPages.Submachine(state = REVIEW, child = DocRefs.Pages.class, parent = PageRefs.Id.class,
                    exits = @DocsPages.Exit(on = PageStatus.DONE, transition = COMPLETE))
                """));

        assertEquals(List.of("[SG503] Doc has no public reader for property 'pages' returning Optional<Page>: the"
                        + " marker names fx.Doc.pages()",
                "[SG503] Page has no public reader for property 'id' returning Optional<Doc>: the marker names"
                        + " fx.Page.id()"), result.errors(), result.describe());
    }

    @Test
    void sg605EachWayNamesASymbolOfItsOwnSide() {
        Map<String, String> sources = bound("""
                @DocsPages.Submachine(state = REVIEW, child = PageRefs.Doc.class, parent = DocRefs.Current.class,
                    exits = @DocsPages.Exit(on = PageStatus.DONE, transition = COMPLETE))
                @DocsPages.Submachine(state = REVIEW, child = DocRefs.Current.class, parentLookup = FakeLookup.class,
                    exits = @DocsPages.Exit(on = PageStatus.DONE, transition = COMPLETE))
                """);
        sources.put("fx.FakeLookup", """
                package fx;
                import io.github.graphmaquina.symbol.LookupSymbol;
                /** A lookup symbol without an exported member. */
                public final class FakeLookup implements LookupSymbol { }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of("[SG605] fx.PageRefs.Doc is a property of fx.Page, not of Doc",
                "[SG605] fx.DocRefs.Current is a property of fx.Doc, not of Page",
                "[SG605] fx.FakeLookup is not a symbol this compilation exports or depends on; use one of the"
                        + " generated <Host>Refs types"), result.errors(), result.describe());
    }

    @Test
    void sg505ALookupReturnsAnOptionalOfTheParentFromTheChild() {
        Compilation.Result result = Compilation.compileTyped(bound("""
                @DocsPages.Submachine(state = REVIEW, child = DocRefs.Current.class,
                    parentLookup = DocLookupsRefs.TitleOf.class,
                    exits = @DocsPages.Exit(on = PageStatus.DONE, transition = COMPLETE))
                """));

        assertEquals(List.of("[SG505] no exported lookup named titleOf on fx.DocLookups has the signature this"
                + " machine needs; expected Optional<Doc> titleOf(Page)"), result.errors(), result.describe());
    }

    /**
     * A child over a parameterized entity is refused at its own header, and its binding is refused for that reason
     * too, rather than with a comparison of the raw host of a property symbol with the child's entity.
     */
    @Test
    void sg509AChildOverAParameterizedEntity() {
        Map<String, String> sources = bound(BOUND);
        sources.put("fx.Page", sources.get("fx.Page").replace("public final class Page {", "/** The parameterized page entity fixture. */ public final class Page<T> {"));
        sources.put("fx.Doc", sources.get("fx.Doc").replace("List<Page>", "List<Page<String>>")
                .replace("Collection<Page>", "Collection<Page<String>>")
                .replace("Optional<Page>", "Optional<Page<String>>")
                .replace("void attach(Page page)", "void attach(Page<String> page)"));
        sources.put("fx.Pages", sources.get("fx.Pages").replace("@GenerateGraphApi", "/** The parameterized page machine fixture. */ @GenerateGraphApi").replace("Machine<Page, PageStatus, PageTransition>",
                "Machine<Page<String>, PageStatus, PageTransition>").replace(
                "Outcome<Page, PageStatus, PageTransition> finish(Page page)",
                "Outcome<Page<String>, PageStatus, PageTransition> finish(Page<String> page)"));
        sources.put("fx.DocActions", sources.get("fx.DocActions").replace("public final class DocActions", "/** Actions of the parameterized page fixture. */ public final class DocActions").replace("Iterable<? extends Page>",
                        "Iterable<? extends Page<String>>").replace("Collection<? extends Page>",
                        "Collection<? extends Page<String>>")
                .replace("PeerView<Page, PageStatus, PageTransition>", "PeerView<Page<String>, PageStatus, PageTransition>"));
        sources.put("fx.DocLookups", sources.get("fx.DocLookups").replace("(Page page)", "(Page<String> page)"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError("SG601"), result.describe());
        assertEquals(1, result.countError("SG509"), result.describe());
        assertTrue(result.errors().contains("[SG509] fx.Pages declares its entity as fx.Page<java.lang.String>, a"
                + " parameterized type, which a parent cannot bind as its sub-machine"), result.describe());
        assertEquals(0, result.countError("SG605"), result.describe());
    }

    @Test
    void sg608APairNeitherAWatchNorASubmachineUses() {
        Compilation.Result result = Compilation.compileTyped(bound(""));

        assertEquals(List.of("[SG608] fx.Pages is declared as a peer of Docs but no watch or sub-machine of it uses that"
                + " pair; declare the peers this machine watches or binds, and only those"), result.errors(),
                result.describe());
    }

    /** The pair vocabulary was generated for a peer the facade no longer declares when the processor reads it. */
    @Test
    void sg608ASubmachineOfAPeerTheFacadeDoesNotDeclare() {
        Map<String, String> sources = bound(BOUND);
        Compilation.Prepared prepared = Compilation.prepare(sources);
        rewrite(prepared, "fx.Docs", sources.get("fx.Docs").replace("@GraphPeers(Pages.class)\n", ""));

        Compilation.Result result = Compilation.compilePrepared(prepared);

        assertEquals(List.of("[SG608] Docs declares a sub-machine of fx.Pages, which it does not declare with"
                + " @GraphPeers"), result.errors(), result.describe());
    }

    /**
     * The peer stopped being a generated machine after the generator wrote the vocabulary of the pair; its own
     * vocabulary and the set that holds it are refused too.
     */
    @Test
    void sg608APeerThatIsNoGeneratedMachine() {
        Map<String, String> sources = bound(BOUND);
        Compilation.Prepared prepared = Compilation.prepare(sources);
        rewrite(prepared, "fx.Pages", sources.get("fx.Pages").replace("@GenerateGraphApi\n", ""));

        Compilation.Result result = Compilation.compilePrepared(prepared);

        assertEquals(1, result.countError("SG608"), result.describe());
        assertTrue(result.errors().contains("[SG608] fx.Pages is not a @GenerateGraphApi machine: the vocabulary of a"
                + " pair is typed with the peer's own states, which only a generated API declares"), result.describe());
    }

    /**
     * The watching fixture with {@code header} in place of its watch, beside {@link #COMPLETE}: {@code DocTransition}
     * gains {@code COMPLETE}, {@code Doc} the property {@code current} of type {@code Optional<Page>}, and
     * {@code DocLookups} exports two lookups from a page, of its document and of a title.
     */
    static Map<String, String> bound(String header) {
        Map<String, String> sources = TypedFixtures.watching(TypedFixtures.PEERS, header + COMPLETE);
        sources.put("fx.Docs", sources.get("fx.Docs").replace("@GenerateGraphApi", "/** The parent document machine fixture. */ @GenerateGraphApi"));
        sources.put("fx.DocTransition", """
                package fx;
                /** Identifiers of the document fixture. */
                public enum DocTransition { SUBMIT, PUBLISH, COMPLETE }
                """);
        sources.put("fx.Doc", sources.get("fx.Doc").replace("public final class Doc", "/** The document entity fixture. */ public final class Doc").replace("import java.util.List;\n",
                "import java.util.List;\nimport java.util.Optional;\n").replace("    void attach(Page page)",
                "    @GraphProperty public Optional<Page> current() { return pages.stream().findFirst(); }\n"
                        + "    void attach(Page page)"));
        sources.put("fx.DocLookups", """
                package fx;
                import io.github.graphmaquina.annotation.GraphLookup;
                import java.util.Optional;
                /** Exported parent lookups of the document fixture. */
                public final class DocLookups {
                    @GraphLookup public static Optional<Doc> docOf(Page page) { return page.doc(); }
                    @GraphLookup public static Optional<String> titleOf(Page page) { return Optional.empty(); }
                }
                """);
        return sources;
    }

    /**
     * {@link #bound(String)} whose only peer is {@code Reviews}, a machine over the documents themselves: {@code Doc}
     * gains the state {@code review}, and the set holds the new machine too.
     */
    private static Map<String, String> reviewed(String header) {
        Map<String, String> sources = bound(header);
        sources.put("fx.Docs", sources.get("fx.Docs").replace("@GraphPeers(Pages.class)", "@GraphPeers(Reviews.class)"));
        sources.put("fx.ReviewStatus", """
                package fx;
                /** States of the review fixture. */
                public enum ReviewStatus { PENDING, APPROVED }
                """);
        sources.put("fx.ReviewTransition", """
                package fx;
                /** Identifiers of the review fixture. */
                public enum ReviewTransition { APPROVE }
                """);
        sources.put("fx.Doc", sources.get("fx.Doc").replace("    void attach(Page page)",
                "    @GraphProperty private ReviewStatus review = ReviewStatus.PENDING;\n"
                        + "    public ReviewStatus review() { return review; }\n"
                        + "    public void review(ReviewStatus value) { review = value; }\n"
                        + "    void attach(Page page)"));
        sources.put("fx.Reviews", """
                package fx;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static fx.ReviewStatus.*;
                import static fx.ReviewTransition.*;
                /** The same-entity review machine fixture. */
                @GenerateGraphApi
                @ReviewsGraph.Definition(initial = PENDING, terminals = APPROVED,
                    state = DocRefs.Review.class, id = DocRefs.Id.class)
                @ReviewsGraph.StatePolicy(state = PENDING, otherwise = "a review is approved")
                public interface Reviews extends Machine<Doc, ReviewStatus, ReviewTransition> {
                    @ReviewsGraph.Transition(id = APPROVE, from = PENDING, to = APPROVED)
                    Outcome<Doc, ReviewStatus, ReviewTransition> approve(Doc doc);
                }
                """);
        sources.put("fx.Library", sources.get("fx.Library").replace("@StateGraphs", "/** The machine set of the review fixture. */ @StateGraphs").replace("    Pages pages();\n",
                "    Pages pages();\n    Reviews reviews();\n"));
        return sources;
    }

    /** Replaces one original after the generator has already read it, as an edit between the two stages does. */
    private static void rewrite(Compilation.Prepared prepared, String qualifiedName, String source) {
        Path file = prepared.request().sources().get(0)
                .resolve(qualifiedName.replace('.', File.separatorChar) + ".java");
        try {
            Files.writeString(file, source, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
