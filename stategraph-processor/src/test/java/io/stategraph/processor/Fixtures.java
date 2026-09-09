package io.stategraph.processor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Source fixtures shared by the processor tests, written against the typed graph API. Everything lives in
 * package {@code fx}, and every generated type a fixture names is one the pre-javac generator produces from
 * these very sources, so a fixture is prepared and compiled exactly as a consumer build does.
 */
final class Fixtures {

    static final String DOC_STATUS = """
            package fx;
            public enum DocStatus { DRAFT, REVIEW, PUBLISHED, ARCHIVED }
            """;

    static final String PAGE_STATUS = """
            package fx;
            public enum PageStatus { OPEN, DONE }
            """;

    static final String DOC = """
            package fx;
            import io.stategraph.annotation.GraphProperty;
            import java.util.ArrayList;
            import java.util.List;
            public final class Doc {
                private final String id;
                @GraphProperty private DocStatus status = DocStatus.DRAFT;
                @GraphProperty private final List<Page> pages = new ArrayList<>();
                public Doc(String id) { this.id = id; }
                @GraphProperty public String getId() { return id; }
                public DocStatus getStatus() { return status; }
                public void setStatus(DocStatus status) { this.status = status; }
                public List<Page> getPages() { return pages; }
                void attach(Page page) { pages.add(page); }
            }
            """;

    static final String PAGE = """
            package fx;
            import io.stategraph.annotation.GraphProperty;
            import java.util.Optional;
            public final class Page {
                private final String id;
                @GraphProperty private PageStatus status = PageStatus.OPEN;
                private final Doc doc;
                public Page(String id, Doc doc) { this.id = id; this.doc = doc; if (doc != null) { doc.attach(this); } }
                @GraphProperty public String id() { return id; }
                public PageStatus status() { return status; }
                public void status(PageStatus status) { this.status = status; }
                @GraphProperty public Optional<Doc> doc() { return Optional.ofNullable(doc); }
            }
            """;

    static final String DOC_ACTIONS = """
            package fx;
            import io.stategraph.annotation.GraphAction;
            import io.stategraph.annotation.GraphCondition;
            import io.stategraph.annotation.GraphGuard;
            import io.stategraph.annotation.GraphHook;
            import io.stategraph.api.ActionOutcome;
            import io.stategraph.api.GuardContext;
            import io.stategraph.api.GuardResult;
            import io.stategraph.api.PeerView;
            import io.stategraph.api.TransitionContext;
            import java.util.ArrayList;
            import java.util.Collection;
            import java.util.List;
            import java.util.concurrent.CompletableFuture;
            import java.util.concurrent.CompletionStage;
            public class DocActions {
                public final List<String> calls = new ArrayList<>();
                public boolean approved = true;
                @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus> ctx) { calls.add("publish"); return ActionOutcome.ok(); }
                @GraphAction public CompletionStage<ActionOutcome> archiveAsync(TransitionContext<Doc, DocStatus> ctx) {
                    calls.add("archive");
                    return CompletableFuture.completedFuture(ActionOutcome.ok());
                }
                @GraphGuard public GuardResult approved(GuardContext<Doc, DocStatus> ctx) {
                    return approved ? GuardResult.pass() : GuardResult.reject("not approved");
                }
                @GraphGuard public static GuardResult ready(GuardContext<Doc, DocStatus> ctx) { return GuardResult.pass(); }
                @GraphHook public void notify(Doc doc) { calls.add("notify:" + doc.getId()); }
                @GraphCondition public boolean allPagesDone(Doc doc, Collection<? extends Page> pages, PeerView<Page, PageStatus> view) {
                    return view.allIn(pages, PageStatus.DONE);
                }
            }
            """;

    static final String PAGES = """
            package fx;
            import io.stategraph.annotation.GenerateGraphApi;
            import io.stategraph.api.Machine;
            import io.stategraph.api.Outcome;
            import static fx.PageStatus.DONE;
            import static fx.PageStatus.OPEN;
            @GenerateGraphApi
            @PagesGraph.Definition(initial = OPEN, terminals = DONE, state = PageRefs.Status.class, id = PageRefs.Id.class)
            @PagesGraph.Row(state = OPEN, otherwise = "a page is finished")
            public interface Pages extends Machine<Page, PageStatus> {
                @PagesGraph.Move(from = OPEN, to = DONE)
                Outcome<Page, PageStatus> finish(Page page);
            }
            """;

    /** Nine lines, so the first line of a header is line 10 of the facade it opens. */
    static final String DOCS_IMPORTS = """
            package fx;
            import io.stategraph.annotation.GenerateGraphApi;
            import io.stategraph.annotation.GraphPeers;
            import io.stategraph.annotation.Timeout;
            import io.stategraph.api.Machine;
            import io.stategraph.api.Outcome;
            import java.io.IOException;
            import static fx.DocStatus.*;
            @GenerateGraphApi
            """;

    /** The line the diagnostics of a header are anchored at, counted from the first line of the facade. */
    static final int HEADER_LINE = 10;

    static final String DOCS_HEADER = """
            @DocsGraph.Definition(initial = DRAFT, terminals = ARCHIVED, allowsCycle = REVIEW, state = DocRefs.Status.class, id = DocRefs.Id.class)
            @DocsGraph.Row(state = DRAFT, otherwise = "a draft is sent to review")
            @DocsGraph.Row(state = REVIEW, otherwise = "a review is published or sent back")
            @DocsGraph.Row(state = PUBLISHED, otherwise = "a published doc can only be archived")
            @DocsGraph.Cell(from = REVIEW, to = DRAFT)
            """;

    static final String DOCS_BODY = """
                @DocsGraph.Move(from = DRAFT, to = REVIEW)
                Outcome<Doc, DocStatus> submit(Doc doc);
                @DocsGraph.Action(from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class,
                        guards = DocActionsRefs.Approved.class, onFailure = DRAFT, afterCommit = DocActionsRefs.Notify.class)
                Outcome<Doc, DocStatus> publish(Doc doc);
                @DocsGraph.Action(from = PUBLISHED, to = ARCHIVED, action = DocActionsRefs.ArchiveAsync.class, timeout = @Timeout(seconds = 2))
                Outcome<Doc, DocStatus> archive(Doc doc);
            """;

    /** What {@link #DOCS_BODY} spells to run the archive asynchronously, so a test can make it synchronous. */
    static final String ASYNC_ARCHIVE = "action = DocActionsRefs.ArchiveAsync.class, timeout = @Timeout(seconds = 2)";

    /** What {@link #DOCS_BODY} spells to publish, so a test can bind the operation to another host. */
    static final String PUBLISH_BINDING = "action = DocActionsRefs.Publish.class,\n"
            + "            guards = DocActionsRefs.Approved.class, onFailure = DRAFT,"
            + " afterCommit = DocActionsRefs.Notify.class";

    /** The pair Docs watches and the watch that uses it; both halves of a pair are declared together. */
    static final String DOCS_WATCH = """
            @GraphPeers(Pages.class)
            @DocsPages.Watch(state = REVIEW, peers = DocRefs.Pages.class, owner = PageRefs.Doc.class,
                    rules = @DocsPages.When(allIn = PageStatus.DONE, goTo = PUBLISHED))
            """;

    static final String LIBRARY = """
            package fx;
            import io.stategraph.annotation.StateGraphs;
            import io.stategraph.api.Machines;
            @StateGraphs
            public interface Library extends Machines {
                Docs docs();
                Pages pages();
            }
            """;

    private Fixtures() { }

    /** The fx domain: entities, enums and DocActions. */
    static Map<String, String> domain() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", DOC_STATUS);
        sources.put("fx.PageStatus", PAGE_STATUS);
        sources.put("fx.Doc", DOC);
        sources.put("fx.Page", PAGE);
        sources.put("fx.DocActions", DOC_ACTIONS);
        return sources;
    }

    /** A Docs interface with the given type-level annotations and member declarations. */
    static String docs(String typeAnnotations, String members) {
        return DOCS_IMPORTS + typeAnnotations + "public interface Docs extends Machine<Doc, DocStatus> {\n" + members + "}\n";
    }

    /** The domain plus a Docs interface. */
    static Map<String, String> withDocs(String typeAnnotations, String members) {
        Map<String, String> sources = domain();
        sources.put("fx.Docs", docs(typeAnnotations, members));
        return sources;
    }

    /** The domain, Pages, and a Docs interface. */
    static Map<String, String> withPages(String typeAnnotations, String members) {
        Map<String, String> sources = withDocs(typeAnnotations, members);
        sources.put("fx.Pages", PAGES);
        return sources;
    }

    /** Domain, Pages, Docs and a set interface. */
    static Map<String, String> withLibrary(String typeAnnotations, String members, String library) {
        Map<String, String> sources = withPages(typeAnnotations, members);
        sources.put("fx.Library", library);
        return sources;
    }

    /** The valid Docs machine of the spec examples. */
    static Map<String, String> validDocs() {
        return withDocs(DOCS_HEADER, DOCS_BODY);
    }
}
