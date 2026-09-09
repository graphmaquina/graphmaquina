package io.stategraph.processor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Source fixtures written against the typed graph API, one entry per compilation unit. Everything lives in
 * package {@code fx}, and no entry names a generated type the pre-javac generator does not produce from these
 * very sources, so the fixture can be prepared and compiled exactly as a consumer build does.
 */
final class TypedFixtures {

    private TypedFixtures() { }

    /**
     * The smallest complete typed machine: a state enum, an entity exporting its state and its identity, an
     * action host and the machine that declares the graph with the vocabulary generated for it.
     */
    static Map<String, String> minimal() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", """
                package fx;
                public enum DocStatus { DRAFT, PUBLISHED }
                """);
        sources.put("fx.Doc", """
                package fx;
                import io.stategraph.annotation.GraphProperty;
                public final class Doc {
                    @GraphProperty private DocStatus status = DocStatus.DRAFT;
                    @GraphProperty public String id() { return "doc"; }
                    public DocStatus status() { return status; }
                    public void status(DocStatus value) { status = value; }
                }
                """);
        sources.put("fx.DocActions", """
                package fx;
                import io.stategraph.annotation.GraphAction;
                import io.stategraph.api.ActionOutcome;
                import io.stategraph.api.TransitionContext;
                public final class DocActions {
                    @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        sources.put("fx.Docs", """
                package fx;
                import io.stategraph.api.Machine;
                import io.stategraph.api.Outcome;
                import io.stategraph.annotation.GenerateGraphApi;
                import static fx.DocStatus.*;
                @GenerateGraphApi
                @DocsGraph.Definition(initial = DRAFT, terminals = PUBLISHED,
                    state = DocRefs.Status.class, id = DocRefs.Id.class)
                @DocsGraph.Row(state = DRAFT, otherwise = "publish before finishing")
                @DocsGraph.Row(state = PUBLISHED, otherwise = "publication is final")
                public interface Docs extends Machine<Doc, DocStatus> {
                    @DocsGraph.Action(from = DRAFT, to = PUBLISHED, action = DocActionsRefs.Publish.class)
                    Outcome<Doc, DocStatus> publish(Doc doc);
                }
                """);
        return sources;
    }

    /**
     * The domain the structural fixtures vary: three states, the same entity, and one host exporting a
     * synchronous action, an asynchronous one, a guard and a hook, so a fixture only has to write its facade.
     */
    static Map<String, String> domain() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", """
                package fx;
                public enum DocStatus { DRAFT, REVIEW, PUBLISHED }
                """);
        sources.put("fx.Doc", """
                package fx;
                import io.stategraph.annotation.GraphProperty;
                public final class Doc {
                    @GraphProperty private DocStatus status = DocStatus.DRAFT;
                    @GraphProperty public String id() { return "doc"; }
                    public DocStatus status() { return status; }
                    public void status(DocStatus value) { status = value; }
                }
                """);
        sources.put("fx.DocActions", """
                package fx;
                import io.stategraph.annotation.GraphAction;
                import io.stategraph.annotation.GraphGuard;
                import io.stategraph.annotation.GraphHook;
                import io.stategraph.api.ActionOutcome;
                import io.stategraph.api.GuardContext;
                import io.stategraph.api.GuardResult;
                import io.stategraph.api.TransitionContext;
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;
                public final class DocActions {
                    @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus> context) {
                        return ActionOutcome.ok();
                    }
                    @GraphAction public CompletionStage<ActionOutcome> archive(TransitionContext<Doc, DocStatus> context) {
                        return CompletableFuture.completedFuture(ActionOutcome.ok());
                    }
                    @GraphGuard public GuardResult approved(GuardContext<Doc, DocStatus> context) {
                        return GuardResult.pass();
                    }
                    @GraphHook public void notified(Doc doc) { }
                }
                """);
        return sources;
    }

    /**
     * A machine that watches another one: {@code Docs} follows the pages of a document and publishes it,
     * {@code Pages} finishes one page, and {@code Library} holds both, so the watch runs against the peer
     * machine of its own set.
     *
     * <p>The watch declares its two rules in order, an explicit condition first and a filter over the peers'
     * states second, so the first rule that holds is the one the engine applies. The condition is declared
     * twice on its host, once marked and once not, with only the marked declaration taking an {@code Iterable}:
     * whichever of the two the generated code calls records its own name in {@code selected}, so a test can
     * tell which method really ran instead of reading the generated source.</p>
     */
    static Map<String, String> watching() {
        return watching(PEERS, WATCH);
    }

    /**
     * The same fixture with {@code peers} in place of its {@code @GraphPeers} declaration and {@code watch} in
     * place of its watch, so a test varies exactly the pair and the watch it is about.
     */
    static Map<String, String> watching(String peers, String watch) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", """
                package fx;
                public enum DocStatus { DRAFT, REVIEW, PUBLISHED }
                """);
        sources.put("fx.PageStatus", """
                package fx;
                public enum PageStatus { OPEN, DONE }
                """);
        sources.put("fx.Doc", """
                package fx;
                import io.stategraph.annotation.GraphProperty;
                import java.util.ArrayList;
                import java.util.Collection;
                import java.util.List;
                public final class Doc {
                    private final String id;
                    @GraphProperty private DocStatus status = DocStatus.DRAFT;
                    private final List<Page> pages = new ArrayList<>();
                    public Doc(String id) { this.id = id; }
                    @GraphProperty public String id() { return id; }
                    public DocStatus status() { return status; }
                    public void status(DocStatus value) { status = value; }
                    @GraphProperty public Collection<Page> pages() { return pages; }
                    void attach(Page page) { pages.add(page); }
                }
                """);
        sources.put("fx.Page", """
                package fx;
                import io.stategraph.annotation.GraphProperty;
                import java.util.Optional;
                public final class Page {
                    private final String id;
                    @GraphProperty private PageStatus status = PageStatus.OPEN;
                    private final Doc doc;
                    public Page(String id, Doc doc) { this.id = id; this.doc = doc; doc.attach(this); }
                    @GraphProperty public String id() { return id; }
                    public PageStatus status() { return status; }
                    public void status(PageStatus value) { status = value; }
                    @GraphProperty public Optional<Doc> doc() { return Optional.of(doc); }
                }
                """);
        sources.put("fx.DocActions", DOC_ACTIONS.formatted("public final class DocActions {"));
        sources.put("fx.Pages", """
                package fx;
                import io.stategraph.annotation.GenerateGraphApi;
                import io.stategraph.api.Machine;
                import io.stategraph.api.Outcome;
                import static fx.PageStatus.*;
                @GenerateGraphApi
                @PagesGraph.Definition(initial = OPEN, terminals = DONE,
                    state = PageRefs.Status.class, id = PageRefs.Id.class)
                @PagesGraph.Row(state = OPEN, otherwise = "a page is finished")
                public interface Pages extends Machine<Page, PageStatus> {
                    @PagesGraph.Move(from = OPEN, to = DONE)
                    Outcome<Page, PageStatus> finish(Page page);
                }
                """);
        sources.put("fx.Docs", watchingDocs(peers, watch));
        sources.put("fx.Library", """
                package fx;
                import io.stategraph.annotation.StateGraphs;
                import io.stategraph.api.Machines;
                @StateGraphs
                public interface Library extends Machines {
                    Docs docs();
                    Pages pages();
                }
                """);
        return sources;
    }

    /** The pair {@link #watching()} declares: the one peer its watch follows. */
    static final String PEERS = "@GraphPeers(Pages.class)\n";

    /** The watch of {@link #watching()}: a condition first, then a filter over the peers' states. */
    static final String WATCH = """
            @DocsPages.Watch(state = REVIEW, peers = DocRefs.Pages.class, owner = PageRefs.Doc.class,
                rules = {
                    @DocsPages.When(condition = DocActionsRefs.Ready.class, goTo = PUBLISHED),
                    @DocsPages.When(allIn = PageStatus.DONE, goTo = PUBLISHED)
                })
            """;

    /**
     * The host of {@link #watching()}, whose declaration line is the one element that varies: a class of its
     * own, or the base another one inherits these very members from.
     */
    static final String DOC_ACTIONS = """
            package fx;
            import io.stategraph.annotation.GraphAction;
            import io.stategraph.annotation.GraphCondition;
            import io.stategraph.api.ActionOutcome;
            import io.stategraph.api.PeerView;
            import io.stategraph.api.TransitionContext;
            import java.util.Collection;
            %s
                public String selected = "none";
                @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus> context) {
                    return ActionOutcome.ok();
                }
                @GraphCondition
                public boolean ready(Doc doc, Iterable<? extends Page> pages,
                        PeerView<Page, PageStatus> view) {
                    selected = "marked";
                    return true;
                }
                public boolean ready(Doc doc, Collection<? extends Page> pages,
                        PeerView<Page, PageStatus> view) {
                    selected = "unmarked";
                    return true;
                }
            }
            """;

    /** The watching facade, with {@code peers} declaring its pairs and {@code watch} the watches it uses them for. */
    private static String watchingDocs(String peers, String watch) {
        return """
                package fx;
                import io.stategraph.annotation.GenerateGraphApi;
                import io.stategraph.annotation.GraphPeers;
                import io.stategraph.api.Machine;
                import io.stategraph.api.Outcome;
                import static fx.DocStatus.*;
                @GenerateGraphApi
                """ + peers + """
                @DocsGraph.Definition(initial = DRAFT, terminals = PUBLISHED,
                    state = DocRefs.Status.class, id = DocRefs.Id.class)
                @DocsGraph.Row(state = DRAFT, otherwise = "a draft is sent to review")
                @DocsGraph.Row(state = REVIEW, otherwise = "the pages decide when a review is published")
                """ + watch + """
                public interface Docs extends Machine<Doc, DocStatus> {
                    @DocsGraph.Move(from = DRAFT, to = REVIEW)
                    Outcome<Doc, DocStatus> submit(Doc doc);
                    @DocsGraph.Action(from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class)
                    Outcome<Doc, DocStatus> publish(Doc doc);
                }
                """;
    }

    /** The domain with a facade carrying {@code header} over its declaration and {@code members} inside it. */
    static Map<String, String> graph(String header, String members) {
        Map<String, String> sources = domain();
        sources.put("fx.Docs", docs(header, members));
        return sources;
    }

    /**
     * One typed facade. The states are imported by name so the header reads as the declaration does, and the
     * vocabulary generated for it is always spelled {@code DocsGraph}.
     */
    static String docs(String header, String members) {
        return """
                package fx;
                import io.stategraph.annotation.GenerateGraphApi;
                import io.stategraph.annotation.Timeout;
                import io.stategraph.api.Machine;
                import io.stategraph.api.Outcome;
                import static fx.DocStatus.*;
                @GenerateGraphApi
                """ + header + "public interface Docs extends Machine<Doc, DocStatus> {\n" + members + "}\n";
    }
}
