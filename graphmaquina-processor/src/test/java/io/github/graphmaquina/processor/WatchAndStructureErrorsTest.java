package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a watch and the outline of a whole graph must still hold once the vocabulary types every state, every
 * identifier and every symbol. The identity of a pair and the shape of a rule are {@link TypedWatchTest}'s
 * subject; here the accessors a watch needs and the structural rules of the graph itself keep the codes they
 * had.
 */
class WatchAndStructureErrorsTest {

    private static Compilation.Result watch(String watchLines) {
        return Compilation.compileTyped(Fixtures.withPages(Fixtures.DOCS_HEADER + watchLines, Fixtures.DOCS_BODY));
    }

    @Test
    void builtInAndConditionRulesAreAccepted() {
        Compilation.Result builtIn = watch(Fixtures.DOCS_WATCH);
        assertTrue(builtIn.success(), builtIn.describe());
        Compilation.Result condition = watch("""
                @GraphPeers(Pages.class)
                @DocsPages.Watch(state = REVIEW, peers = DocRefs.Pages.class, owner = PageRefs.Doc.class,
                        rules = { @DocsPages.When(condition = DocActionsRefs.AllPagesDone.class, transition = PUBLISH),
                                  @DocsPages.When(noneIn = PageStatus.OPEN, transition = RETURN) })
                """);
        assertTrue(condition.success(), condition.describe());
    }

    @Test
    void sg503PeersAndOwnerProperties() {
        Compilation.Result peers = watch(Fixtures.DOCS_WATCH.replace("peers = DocRefs.Pages.class",
                "peers = DocRefs.Status.class"));
        assertTrue(peers.hasError("SG503"), peers.describe());
        Compilation.Result owner = watch(Fixtures.DOCS_WATCH.replace("owner = PageRefs.Doc.class",
                "owner = PageRefs.Id.class"));
        assertTrue(owner.hasError("SG503"), owner.describe());
    }

    /**
     * A raw collection is no {@code Collection<? extends Page>}: only an unchecked conversion would make it one, and
     * the companion, which reads the peers as pages, would compile against it through unchecked conversions if at
     * all. The peers reader is refused as a reader of any other type is, and so is a raw {@code Optional} owner
     * reader.
     */
    @Test
    void sg503RawPeersAndOwnerReaders() {
        Map<String, String> rawPeers = Fixtures.withPages(Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH,
                Fixtures.DOCS_BODY);
        rawPeers.put("fx.Doc", rawPeers.get("fx.Doc").replace("public List<Page> getPages()",
                "@SuppressWarnings(\"rawtypes\") public List getPages()"));
        Compilation.Result peers = Compilation.compileTyped(rawPeers);
        assertEquals(List.of("[SG503] Doc has no public reader for property 'pages' returning"
                + " Collection<? extends Page>"), sgErrors(peers), peers.describe());

        Map<String, String> rawOwner = Fixtures.withPages(Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH,
                Fixtures.DOCS_BODY);
        rawOwner.put("fx.Page", rawOwner.get("fx.Page").replace("@GraphProperty public Optional<Doc> doc()",
                "@SuppressWarnings(\"rawtypes\") @GraphProperty public Optional doc()"));
        Compilation.Result owner = Compilation.compileTyped(rawOwner);
        assertEquals(List.of("[SG503] Page has no public reader for property 'doc' returning Optional<Doc> or Doc:"
                + " the marker names fx.Page.doc()"), sgErrors(owner), owner.describe());
    }

    /** The errors of {@code result} that carry an SG code. */
    private static List<String> sgErrors(Compilation.Result result) {
        return result.errors().stream().filter((String error) -> error.startsWith("[SG")).toList();
    }

    @Test
    void structuralCodesComeOutOfJavac() {
        Compilation.Result unreachable = Compilation.compileTyped(Fixtures.withDocs(Fixtures.DOCS_HEADER,
                Fixtures.DOCS_BODY.replace("""
                            @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                            Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                        """, "")));
        assertTrue(unreachable.hasError("SG203"), unreachable.describe());
        assertEquals(Fixtures.HEADER_LINE, unreachable.lineOf("SG203"), unreachable.describe());

        Compilation.Result terminalExits = Compilation.compileTyped(Fixtures.withDocs(
                Fixtures.DOCS_HEADER.replace("terminals = ARCHIVED", "terminals = {ARCHIVED, PUBLISHED}"),
                Fixtures.DOCS_BODY));
        assertTrue(terminalExits.hasError("SG202"), terminalExits.describe());

        Compilation.Result cycle = Compilation.compileTyped(Fixtures.withDocs(
                Fixtures.DOCS_HEADER.replace(", allowsCycle = REVIEW", ""), Fixtures.DOCS_BODY));
        assertTrue(cycle.hasError("SG204"), cycle.describe());

        Compilation.Result deadCycle = Compilation.compileTyped(Fixtures.withDocs(
                Fixtures.DOCS_HEADER.replace("allowsCycle = REVIEW", "allowsCycle = {REVIEW, PUBLISHED}"),
                Fixtures.DOCS_BODY));
        assertTrue(deadCycle.hasError("SG205"), deadCycle.describe());

        Compilation.Result badRoute = Compilation.compileTyped(Fixtures.withDocs(Fixtures.DOCS_HEADER,
                Fixtures.DOCS_BODY.replace("onFailure = RETURN", "onFailure = ARCHIVE")));
        assertTrue(badRoute.hasError("SG301"), badRoute.describe());

        assertTrue(watch(Fixtures.DOCS_WATCH.replace("transition = PUBLISH", "transition = ARCHIVE")).hasError("SG401"));
        assertTrue(watch(Fixtures.DOCS_WATCH.replace("state = REVIEW", "state = ARCHIVED")).hasError("SG402"));
    }
}
