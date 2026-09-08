package io.stategraph.processor;

import java.util.LinkedHashMap;
import java.util.Map;

/** Source fixtures shared by the processor tests. Everything lives in package fx. */
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
            import java.util.ArrayList;
            import java.util.List;
            public final class Doc {
                private final String id;
                private DocStatus status = DocStatus.DRAFT;
                private final List<Page> pages = new ArrayList<>();
                public Doc(String id) { this.id = id; }
                public String getId() { return id; }
                public DocStatus getStatus() { return status; }
                public void setStatus(DocStatus status) { this.status = status; }
                public List<Page> getPages() { return pages; }
                void attach(Page page) { pages.add(page); }
            }
            """;

    static final String PAGE = """
            package fx;
            import java.util.Optional;
            public final class Page {
                private final String id;
                private PageStatus status = PageStatus.OPEN;
                private final Doc doc;
                public Page(String id, Doc doc) { this.id = id; this.doc = doc; if (doc != null) { doc.attach(this); } }
                public String id() { return id; }
                public PageStatus status() { return status; }
                public void status(PageStatus status) { this.status = status; }
                public Optional<Doc> doc() { return Optional.ofNullable(doc); }
            }
            """;

    static final String DOC_ACTIONS = """
            package fx;
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
                public ActionOutcome publish(TransitionContext<Doc, DocStatus> ctx) { calls.add("publish"); return ActionOutcome.ok(); }
                public CompletionStage<ActionOutcome> archiveAsync(TransitionContext<Doc, DocStatus> ctx) {
                    calls.add("archive");
                    return CompletableFuture.completedFuture(ActionOutcome.ok());
                }
                public GuardResult approved(GuardContext<Doc, DocStatus> ctx) {
                    return approved ? GuardResult.pass() : GuardResult.reject("not approved");
                }
                public static GuardResult ready(GuardContext<Doc, DocStatus> ctx) { return GuardResult.pass(); }
                public void notify(Doc doc) { calls.add("notify:" + doc.getId()); }
                public boolean allPagesDone(Doc doc, Collection<? extends Page> pages, PeerView<Page, PageStatus> view) {
                    return view.allIn(pages, PageStatus.DONE);
                }
                public String notAnAction(TransitionContext<Doc, DocStatus> ctx) { return ""; }
                private ActionOutcome hidden(TransitionContext<Doc, DocStatus> ctx) { return ActionOutcome.ok(); }
            }
            """;

    static final String HIDDEN_ACTIONS = """
            package fx;
            import io.stategraph.api.ActionOutcome;
            import io.stategraph.api.TransitionContext;
            class HiddenActions {
                public ActionOutcome publish(TransitionContext<Doc, DocStatus> ctx) { return ActionOutcome.ok(); }
            }
            """;

    static final String PAGES = """
            package fx;
            import io.stategraph.annotation.Move;
            import io.stategraph.annotation.Row;
            import io.stategraph.annotation.StateGraph;
            import io.stategraph.api.Machine;
            import io.stategraph.api.Outcome;
            @StateGraph(initial = "OPEN", terminals = "DONE")
            @Row(state = "OPEN", otherwise = "a page is finished")
            public interface Pages extends Machine<Page, PageStatus> {
                @Move(from = "OPEN", to = "DONE")
                Outcome<Page, PageStatus> finish(Page page);
            }
            """;

    static final String DOCS_IMPORTS = """
            package fx;
            import io.stategraph.annotation.Action;
            import io.stategraph.annotation.Cell;
            import io.stategraph.annotation.Forbid;
            import io.stategraph.annotation.Move;
            import io.stategraph.annotation.Row;
            import io.stategraph.annotation.StateGraph;
            import io.stategraph.annotation.Watch;
            import io.stategraph.annotation.When;
            import io.stategraph.api.Machine;
            import io.stategraph.api.Outcome;
            import java.io.IOException;
            """;

    static final String DOCS_HEADER = """
            @StateGraph(initial = "DRAFT", terminals = {"ARCHIVED"}, allowsCycle = "REVIEW")
            @Row(state = "DRAFT", otherwise = "a draft is sent to review")
            @Row(state = "REVIEW", otherwise = "a review is published or sent back")
            @Row(state = "PUBLISHED", otherwise = "a published doc can only be archived")
            @Cell(from = "REVIEW", to = "DRAFT")
            """;

    static final String DOCS_BODY = """
                @Move(from = "DRAFT", to = "REVIEW")
                Outcome<Doc, DocStatus> submit(Doc doc);
                @Action(from = "REVIEW", to = "PUBLISHED", by = DocActions.class, guards = "approved", onFailure = "DRAFT", afterCommit = "notify")
                Outcome<Doc, DocStatus> publish(Doc doc);
                @Action(from = "PUBLISHED", to = "ARCHIVED", by = DocActions.class, action = "archiveAsync", timeout = "PT2S")
                Outcome<Doc, DocStatus> archive(Doc doc);
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
        sources.put("fx.HiddenActions", HIDDEN_ACTIONS);
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

    /** The valid Docs machine of the spec examples. */
    static Map<String, String> validDocs() {
        return withDocs(DOCS_HEADER, DOCS_BODY);
    }
}
