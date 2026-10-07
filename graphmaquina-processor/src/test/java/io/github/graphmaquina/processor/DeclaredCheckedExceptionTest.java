package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A bound method or a property accessor may declare a checked exception, which no functional interface the companion
 * implements declares. The companion throws it on unchanged, so the engine receives the very exception the member threw
 * and routes it as it routes one thrown without being declared, which the core's {@code CheckedExceptionTest} pins for
 * actions, guards, hooks, watch conditions, owner lookups and state writers. Here, run through the generated
 * companions: an action whose {@code failsOn} lists it takes its failure route, synchronous or not, and a synchronous
 * one aborts when its {@code failsOn} does not list it; a guard aborts, synchronous or not; a hook ends in an
 * {@code AfterCommitException}; a watch condition and a peers reader end in an aborted reaction whose abort, the
 * reevaluation's, carries it as its cause, and an owner reader in one that carries it in an
 * {@code UndeclaredThrowableException}; a state writer ends in a {@code CommitFailedException}; a state or identity
 * reader ends the call in an abort in RESOLVE that carries it. Each scenario tells the very exception the member threw
 * from any other. A member that declares only unchecked exceptions is called as before.
 */
class DeclaredCheckedExceptionTest {

    /**
     * A host whose every member throws the host's {@code failure} and declares a checked type for it: an IOException,
     * or Throwable itself for {@code recorded}.
     */
    private static final String THROWING_ACTIONS = """
            package fx;
            import io.github.graphmaquina.annotation.GraphAction;
            import io.github.graphmaquina.annotation.GraphGuard;
            import io.github.graphmaquina.annotation.GraphHook;
            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.CommitContext;
            import io.github.graphmaquina.api.GuardContext;
            import io.github.graphmaquina.api.GuardResult;
            import io.github.graphmaquina.api.TransitionContext;
            import java.io.IOException;
            import java.util.concurrent.CompletionStage;
            public final class DocActions {
                public final IOException failure = new IOException("storage down");
                @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus, DocTransition> context)
                        throws IOException {
                    throw failure;
                }
                @GraphAction public CompletionStage<ActionOutcome> archive(
                        TransitionContext<Doc, DocStatus, DocTransition> context) throws IOException {
                    throw failure;
                }
                @GraphGuard public GuardResult approved(GuardContext<Doc, DocStatus, DocTransition> context)
                        throws IOException {
                    throw failure;
                }
                @GraphGuard public CompletionStage<GuardResult> reviewed(GuardContext<Doc, DocStatus, DocTransition> context)
                        throws IOException {
                    throw failure;
                }
                @GraphHook public void notified(Doc doc) throws IOException {
                    throw failure;
                }
                @GraphHook public void recorded(Doc doc, CommitContext<DocStatus, DocTransition> commit) throws Throwable {
                    throw failure;
                }
            }
            """;

    /** What a member that declares a thrown type variable throws its exception through. */
    private static final String SNEAKY = """
            package fx;
            final class Sneaky {
                @SuppressWarnings("unchecked")
                static <X extends Throwable> X cast(Throwable thrown) { return (X) thrown; }
            }
            """;

    /** The set every scenario builds its machines from. */
    private static final String LIBRARY = """
            package fx;
            import io.github.graphmaquina.annotation.StateGraphs;
            import io.github.graphmaquina.api.Machines;
            @StateGraphs public interface Library extends Machines { Docs docs(); }
            """;

    /**
     * DRAFT -SUBMIT-> REVIEW -PUBLISH-> PUBLISHED, and REVIEW -RETURN-> DRAFT for a failure route, over
     * {@link #THROWING_ACTIONS} and held by {@link #LIBRARY}: {@code submit} and {@code publish} are what the two
     * operations' edges bind.
     */
    private static Map<String, String> docs(String submit, String publish) {
        Map<String, String> sources = TypedFixtures.graph("""
                @DocsGraph.Definition(initial = DRAFT, terminals = PUBLISHED, allowsCycle = REVIEW,
                    state = DocRefs.Status.class, id = DocRefs.Id.class)
                @DocsGraph.StatePolicy(state = DRAFT, otherwise = "review first")
                @DocsGraph.StatePolicy(state = REVIEW, otherwise = "publish or return")
                @DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT)
                """, """
                    @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW%s)
                    Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                    @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED%s)
                    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
                """.formatted(submit, publish));
        sources.put("fx.DocActions", THROWING_ACTIONS);
        sources.put("fx.Library", LIBRARY);
        return sources;
    }

    /** Compiles {@code sources} with {@code fx.Scenario}, as {@link #compile} does, runs it and answers what it returns. */
    private static Object run(Map<String, String> sources, String body) throws Exception {
        return run(compile(sources, body));
    }

    /** What {@code fx.Scenario.run()} of {@code result} returns. */
    private static Object run(Compilation.Result result) throws Exception {
        return result.load("fx.Scenario").getMethod("run").invoke(null);
    }

    /**
     * Compiles {@code sources} with {@code fx.Scenario}, whose {@code run()} has {@code body}, and requires the
     * compilation to succeed. {@code reaction(...)} describes the one reaction of a commit, and whether it carries
     * {@code failure}, as the cause of the reevaluation's abort or, from an owner lookup, wrapped in an
     * {@code UndeclaredThrowableException}; {@code aborted(...)} whether an abort carries {@code failure}.
     */
    private static Compilation.Result compile(Map<String, String> sources, String body) {
        sources.put("fx.Scenario", """
                package fx;
                import io.github.graphmaquina.api.Outcome;
                import io.github.graphmaquina.api.Reaction;
                import io.github.graphmaquina.error.AfterCommitException;
                import io.github.graphmaquina.error.CommitFailedException;
                import io.github.graphmaquina.error.TransitionAbortedException;
                import java.lang.reflect.UndeclaredThrowableException;
                import java.time.Duration;
                import java.util.ArrayList;
                import java.util.List;
                public final class Scenario {
                    public static Object run() {
                %s
                    }
                    static String reaction(Outcome<?, ?, ?> outcome, Throwable failure) {
                        if (outcome instanceof Outcome.Applied<?, ?, ?> applied && applied.reactions().size() == 1
                                && applied.reactions().get(0) instanceof Reaction.Aborted aborted) {
                            Throwable carried = aborted.cause() instanceof UndeclaredThrowableException wrapped
                                    ? wrapped.getUndeclaredThrowable() : aborted.cause().getCause();
                            return carried == failure ? "aborted by it" : "aborted by " + aborted.cause();
                        }
                        return String.valueOf(outcome);
                    }
                    static String aborted(TransitionAbortedException aborted, Throwable failure) {
                        return aborted.getCause() == failure ? "aborted by it" : "aborted by " + aborted.getCause();
                    }
                }
                """.formatted(body));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result::describe);
        return result;
    }

    @Test
    void anActionThatDeclaresACheckedExceptionTakesTheFailureRouteWhenFailsOnListsIt() throws Exception {
        Map<String, String> sources = docs("", ", action = DocActionsRefs.Publish.class, failsOn = java.io.IOException.class,"
                + " onFailure = RETURN");

        assertEquals("failed by it, DRAFT", run(sources, """
                DocActions actions = new DocActions();
                try (Library library = LibraryRuntime.builder(actions).build()) {
                    Doc doc = new Doc();
                    library.docs().submit(doc);
                    Outcome<Doc, DocStatus, DocTransition> outcome = library.docs().publish(doc);
                    return outcome instanceof Outcome.Failed<?, ?, ?> failed && failed.cause().orElseThrow() == actions.failure
                            ? "failed by it, " + doc.status() : String.valueOf(outcome);
                }
                """));
    }

    @Test
    void anActionThatDeclaresACheckedExceptionIsATechnicalAbortCarryingItWhenFailsOnDoesNotListIt() throws Exception {
        Map<String, String> sources = docs("", ", action = DocActionsRefs.Publish.class");

        assertEquals("aborted by it, REVIEW", run(sources, """
                DocActions actions = new DocActions();
                try (Library library = LibraryRuntime.builder(actions).build()) {
                    Doc doc = new Doc();
                    library.docs().submit(doc);
                    try {
                        return "returned " + library.docs().publish(doc);
                    } catch (TransitionAbortedException aborted) {
                        return aborted(aborted, actions.failure) + ", " + doc.status();
                    }
                }
                """));
    }

    /** Thrown by the method itself, before any stage exists, it is classified like a stage that failed with it. */
    @Test
    void anAsynchronousActionThatDeclaresACheckedExceptionTakesTheFailureRouteWhenFailsOnListsIt() throws Exception {
        Map<String, String> sources = docs("", ", action = DocActionsRefs.Archive.class, failsOn = java.io.IOException.class,"
                + " onFailure = RETURN");

        assertEquals("failed by it, DRAFT", run(sources, """
                DocActions actions = new DocActions();
                try (Library library = LibraryRuntime.builder(Duration.ofSeconds(5), actions).build()) {
                    Doc doc = new Doc();
                    library.docs().submit(doc);
                    Outcome<Doc, DocStatus, DocTransition> outcome = library.docs().publish(doc);
                    return outcome instanceof Outcome.Failed<?, ?, ?> failed && failed.cause().orElseThrow() == actions.failure
                            ? "failed by it, " + doc.status() : String.valueOf(outcome);
                }
                """));
    }

    /** The synchronous guard guards SUBMIT, the asynchronous one PUBLISH. */
    @Test
    void aGuardThatDeclaresACheckedExceptionIsATechnicalAbortCarryingIt() throws Exception {
        Map<String, String> sources = docs(", guards = DocActionsRefs.Approved.class",
                ", guards = DocActionsRefs.Reviewed.class");

        assertEquals(List.of("aborted by it, DRAFT", "aborted by it, REVIEW"), run(sources, """
                List<String> ends = new ArrayList<>();
                DocActions actions = new DocActions();
                try (Library library = LibraryRuntime.builder(Duration.ofSeconds(5), actions).build()) {
                    Doc draft = new Doc();
                    Doc review = new Doc();
                    review.status(DocStatus.REVIEW);
                    for (Doc doc : List.of(draft, review)) {
                        try {
                            ends.add("returned " + (doc == draft ? library.docs().submit(doc) : library.docs().publish(doc)));
                        } catch (TransitionAbortedException aborted) {
                            ends.add(aborted(aborted, actions.failure) + ", " + doc.status());
                        }
                    }
                }
                return ends;
                """));
    }

    /**
     * The hook of SUBMIT takes the entity and declares an IOException, which the companion catches as an Exception;
     * the hook of PUBLISH also takes the commit and declares Throwable itself, which it catches as a Throwable. Both
     * blocks make the call as a statement: a hook returns nothing.
     */
    @Test
    void aHookThatDeclaresACheckedExceptionIsAnAfterCommitExceptionCarryingIt() throws Exception {
        Map<String, String> sources = docs(", afterCommit = DocActionsRefs.Notified.class",
                ", afterCommit = DocActionsRefs.Recorded.class");

        Compilation.Result result = compile(sources, """
                List<String> ends = new ArrayList<>();
                DocActions actions = new DocActions();
                try (Library library = LibraryRuntime.builder(actions).build()) {
                    Doc doc = new Doc();
                    for (int call = 0; call < 2; call++) {
                        try {
                            ends.add("returned " + (call == 0 ? library.docs().submit(doc) : library.docs().publish(doc)));
                        } catch (AfterCommitException failed) {
                            ends.add(failed.hook() + (failed.getCause() == actions.failure ? " threw it, "
                                    : " threw " + failed.getCause() + ", ") + doc.status());
                        }
                    }
                }
                return ends;
                """);

        assertEquals(List.of("notified threw it, REVIEW", "recorded threw it, PUBLISHED"), run(result));
        String docs = result.generated("fx.DocsRuntime");
        assertTrue(docs.contains(".afterCommit(\"notified\", (doc) -> { try { docActions.notified(doc); }"
                + " catch (Exception failure) { throw rethrow(failure); } })"), docs);
        assertTrue(docs.contains(".afterCommitWith(\"recorded\", (doc, commit) -> { try { docActions.recorded(doc, commit); }"
                + " catch (Throwable failure) { throw rethrow(failure); } })"), docs);
    }

    /** The watch of {@link TypedFixtures#watching()}, whose condition comes first and declares an IOException. */
    @Test
    void aWatchConditionThatDeclaresACheckedExceptionIsAnAbortedReactionCarryingIt() throws Exception {
        Map<String, String> sources = TypedFixtures.watching();
        sources.put("fx.DocActions", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.annotation.GraphCondition;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.PeerView;
                import io.github.graphmaquina.api.TransitionContext;
                import java.io.IOException;
                import java.util.Collection;
                public final class DocActions {
                    public final IOException failure = new IOException("rules down");
                    @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus, DocTransition> context) {
                        return ActionOutcome.ok();
                    }
                    @GraphCondition public boolean ready(Doc doc, Collection<? extends Page> pages,
                            PeerView<Page, PageStatus, PageTransition> view) throws IOException {
                        throw failure;
                    }
                }
                """);

        assertEquals("aborted by it, REVIEW", run(sources, """
                DocActions actions = new DocActions();
                try (Library library = LibraryRuntime.builder(actions).build()) {
                    Doc doc = new Doc("d");
                    Page page = new Page("p", doc);
                    library.docs().submit(doc);
                    return reaction(library.pages().finish(page), actions.failure) + ", " + doc.status();
                }
                """));
    }

    /**
     * The peers reader runs when the reaction evaluates the watch, the owner reader before it, when the commit of the
     * page looks for its owner. In the second scenario, the owner reader returns the owner itself rather than an
     * Optional of it.
     */
    @Test
    void aPeersOrOwnerReaderThatDeclaresACheckedExceptionIsAnAbortedReactionCarryingIt() throws Exception {
        Map<String, String> peers = TypedFixtures.watching();
        peers.put("fx.Doc", peers.get("fx.Doc")
                .replace("import java.util.List;", "import java.util.List;\nimport java.io.IOException;")
                .replace("@GraphProperty public Collection<Page> pages() { return pages; }",
                        "public final IOException failure = new IOException(\"index down\");\n"
                        + "    @GraphProperty public Collection<Page> pages() throws IOException { throw failure; }"));
        Map<String, String> owner = TypedFixtures.watching();
        owner.put("fx.Page", owner.get("fx.Page")
                .replace("import java.util.Optional;", "import java.io.IOException;")
                .replace("@GraphProperty public Optional<Doc> doc() { return Optional.of(doc); }",
                        "public final IOException failure = new IOException(\"directory down\");\n"
                        + "    @GraphProperty public Doc doc() throws IOException { throw failure; }"));

        assertEquals("aborted by it, REVIEW", run(peers, """
                try (Library library = LibraryRuntime.builder(new DocActions()).build()) {
                    Doc doc = new Doc("d");
                    Page page = new Page("p", doc);
                    library.docs().submit(doc);
                    return reaction(library.pages().finish(page), doc.failure) + ", " + doc.status();
                }
                """));
        assertEquals("aborted by it, REVIEW", run(owner, """
                try (Library library = LibraryRuntime.builder(new DocActions()).build()) {
                    Doc doc = new Doc("d");
                    Page page = new Page("p", doc);
                    library.docs().submit(doc);
                    return reaction(library.pages().finish(page), page.failure) + ", " + doc.status();
                }
                """));
    }

    /** The state reader, the state writer and the identity reader of the entity, each made to throw in turn. */
    @Test
    void aStateOrIdentityAccessorThatDeclaresACheckedExceptionAbortsTheCallCarryingIt() throws Exception {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx.Doc", """
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                import java.io.IOException;
                public final class Doc {
                    public final IOException failure = new IOException("row locked");
                    public String failing = "";
                    @GraphProperty private DocStatus status = DocStatus.DRAFT;
                    @GraphProperty public String id() throws IOException {
                        if (failing.equals("identity")) throw failure;
                        return "doc";
                    }
                    public DocStatus status() throws IOException {
                        if (failing.equals("read")) throw failure;
                        return status;
                    }
                    public void status(DocStatus value) throws IOException {
                        if (failing.equals("write")) throw failure;
                        status = value;
                    }
                }
                """);
        sources.put("fx.Library", LIBRARY);

        List<String> expected = List.of("read: aborted by it", "write: commit failed by it", "identity: aborted by it");
        assertEquals(expected, run(sources, """
                List<String> ends = new ArrayList<>();
                for (String failing : List.of("read", "write", "identity")) {
                    try (Library library = LibraryRuntime.builder(new DocActions()).build()) {
                        Doc doc = new Doc();
                        doc.failing = failing;
                        try {
                            ends.add(failing + ": returned " + library.docs().publish(doc));
                        } catch (CommitFailedException failed) {
                            ends.add(failing + ": commit failed " + (failed.getCause() == doc.failure ? "by it" : "by " + failed.getCause()));
                        } catch (TransitionAbortedException aborted) {
                            ends.add(failing + ": " + aborted(aborted, doc.failure));
                        } catch (Exception thrown) {
                            ends.add(failing + ": " + (thrown == doc.failure ? "threw it" : "threw " + thrown));
                        }
                    }
                }
                return ends;
                """));
    }

    /**
     * Unchecked exceptions in a throws clause change nothing, a type variable bounded by one included: the companions
     * call the action and the accessors as they always did, and declare nothing to throw anything on.
     */
    @Test
    void aMemberThatDeclaresOnlyUncheckedExceptionsIsCalledAsBefore() {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx.DocActions", sources.get("fx.DocActions")
                .replace("@GraphAction public ActionOutcome", "@GraphAction public <X extends RuntimeException> ActionOutcome")
                .replace("TransitionContext<Doc, DocStatus, DocTransition> context) {",
                        "TransitionContext<Doc, DocStatus, DocTransition> context) throws IllegalStateException, AssertionError, X {"));
        sources.put("fx.Doc", sources.get("fx.Doc").replace("public void status(DocStatus value) {",
                "public void status(DocStatus value) throws java.io.UncheckedIOException {"));
        sources.put("fx.Library", LIBRARY);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
        String docs = result.generated("fx.DocsRuntime");
        String library = result.generated("fx.LibraryRuntime");
        assertTrue(docs.contains(".action(\"publish\", (context) -> docActions.publish(context))"), docs);
        assertTrue(library.contains("(Doc entity, DocStatus state) -> entity.status(state))"), library);
        assertFalse(docs.contains("rethrow") || docs.contains("catch ("), docs);
        assertFalse(library.contains("rethrow") || library.contains("catch ("), library);
    }

    /**
     * A thrown type variable counts as its bound: javac infers {@code X} as the bound, here IOException, for a call
     * that does not fix it, and so the companion catches what it throws as an Exception, as it catches a declared
     * IOException.
     */
    @Test
    void aThrownTypeVariableBoundedByACheckedExceptionIsThrownOnLikeTheBound() throws Exception {
        Map<String, String> sources = docs("", ", action = DocActionsRefs.Publish.class, failsOn = java.io.IOException.class,"
                + " onFailure = RETURN");
        sources.put("fx.DocActions", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                import java.io.IOException;
                public final class DocActions {
                    public static final IOException FAILURE = new IOException("storage down");
                    @GraphAction public static <X extends IOException> ActionOutcome publish(
                            TransitionContext<Doc, DocStatus, DocTransition> context) throws X {
                        throw Sneaky.<X>cast(FAILURE);
                    }
                }
                """);
        sources.put("fx.Sneaky", SNEAKY);

        Compilation.Result result = compile(sources, """
                try (Library library = LibraryRuntime.builder().build()) {
                    Doc doc = new Doc();
                    library.docs().submit(doc);
                    Outcome<Doc, DocStatus, DocTransition> outcome = library.docs().publish(doc);
                    return outcome instanceof Outcome.Failed<?, ?, ?> failed && failed.cause().orElseThrow() == DocActions.FAILURE
                            ? "failed by it, " + doc.status() : String.valueOf(outcome);
                }
                """);

        assertEquals("failed by it, DRAFT", run(result));
        String docs = result.generated("fx.DocsRuntime");
        assertTrue(docs.contains("catch (Exception failure) { throw rethrow(failure); }"), docs);
    }

    /**
     * A thrown type variable bounded by an intersection counts as the class among its bounds. Bounded by IOException
     * and Runnable, it is caught as an Exception and routes as a declared IOException does; bounded by RuntimeException
     * and Runnable, it leaves the call as it is.
     */
    @Test
    void aThrownTypeVariableBoundedByAnIntersectionCountsAsTheClassAmongItsBounds() throws Exception {
        Map<String, String> sources = docs(", action = DocActionsRefs.Submit.class", ", action = DocActionsRefs.Publish.class,"
                + " failsOn = java.io.IOException.class, onFailure = RETURN");
        sources.put("fx.DocActions", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                import java.io.IOException;
                public final class DocActions {
                    public static final Stalled FAILURE = new Stalled();
                    @GraphAction public static <X extends RuntimeException & Runnable> ActionOutcome submit(
                            TransitionContext<Doc, DocStatus, DocTransition> context) throws X {
                        return ActionOutcome.ok();
                    }
                    @GraphAction public static <X extends IOException & Runnable> ActionOutcome publish(
                            TransitionContext<Doc, DocStatus, DocTransition> context) throws X {
                        throw Sneaky.<X>cast(FAILURE);
                    }
                }
                """);
        sources.put("fx.Stalled", """
                package fx;
                public final class Stalled extends java.io.IOException implements Runnable {
                    @Override public void run() { }
                }
                """);
        sources.put("fx.Sneaky", SNEAKY);

        Compilation.Result result = compile(sources, """
                try (Library library = LibraryRuntime.builder().build()) {
                    Doc doc = new Doc();
                    Outcome<Doc, DocStatus, DocTransition> submitted = library.docs().submit(doc);
                    Outcome<Doc, DocStatus, DocTransition> outcome = library.docs().publish(doc);
                    return submitted instanceof Outcome.Applied<?, ?, ?> && outcome instanceof Outcome.Failed<?, ?, ?> failed
                            && failed.cause().orElseThrow() == DocActions.FAILURE
                            ? "failed by it, " + doc.status() : submitted + " then " + outcome;
                }
                """);

        assertEquals("failed by it, DRAFT", run(result));
        String docs = result.generated("fx.DocsRuntime");
        assertTrue(docs.contains(".action(\"submit\", (context) -> DocActions.submit(context))"), docs);
        assertTrue(docs.contains(".action(\"publish\", (context) -> { try { return DocActions.publish(context); }"
                + " catch (Exception failure) { throw rethrow(failure); } })"), docs);
    }

    /**
     * The catch parameter is a variable of the lambda, so it takes a name that no variable in scope there has: here
     * the host is named {@code Failure}, and the parameter that takes it, in the machine's {@code graph(...)} and in
     * the constructor of the set's {@code Builder}, is named after it.
     */
    @Test
    void theCatchParameterTakesANameNoOtherVariableInScopeHas() {
        Map<String, String> sources = docs("", ", action = FailureRefs.Publish.class");
        sources.remove("fx.DocActions");
        sources.put("fx.Failure", THROWING_ACTIONS.replace("public final class DocActions {", "public final class Failure {"));
        sources.put("fx.Doc", sources.get("fx.Doc").replace("public void status(DocStatus value) {",
                "public void status(DocStatus value) throws java.io.IOException {"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
    }

    /**
     * The companion throws a caught exception on through a private method of its own, which the name {@code rethrow}
     * would give the signature {@code <X extends Throwable> X rethrow(Throwable)}. The facade and the set each declare
     * a method of that name that javac would refuse beside it: the facade one that the call in a lambda of the static
     * {@code graph(...)} would select instead, an instance method it cannot call, and the set one with that very
     * signature, which a static method may not hide. Each companion names its method otherwise.
     */
    @Test
    void theMethodThatThrowsOnTakesANameTheFacadeOrTheSetLeavesFree() throws Exception {
        Map<String, String> sources = docs("", ", action = DocActionsRefs.Publish.class, failsOn = java.io.IOException.class,"
                + " onFailure = RETURN");
        sources.put("fx.Docs", sources.get("fx.Docs").replace("    Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);\n",
                "    Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);\n"
                + "    default String rethrow(Exception failure) { return \"facade\"; }\n"));
        sources.put("fx.Doc", sources.get("fx.Doc").replace("public void status(DocStatus value) {",
                "public void status(DocStatus value) throws java.io.IOException {"));
        sources.put("fx.Library", LIBRARY.replace("Docs docs(); }", "Docs docs();\n"
                + "    @SuppressWarnings(\"unchecked\")\n"
                + "    default <X extends Throwable> X rethrow(Throwable failure) throws X { throw (X) failure; }\n}"));

        assertEquals("failed by it, DRAFT", run(sources, """
                DocActions actions = new DocActions();
                try (Library library = LibraryRuntime.builder(actions).build()) {
                    Doc doc = new Doc();
                    library.docs().submit(doc);
                    Outcome<Doc, DocStatus, DocTransition> outcome = library.docs().publish(doc);
                    return outcome instanceof Outcome.Failed<?, ?, ?> failed && failed.cause().orElseThrow() == actions.failure
                            ? "failed by it, " + doc.status() : String.valueOf(outcome);
                }
                """));
    }
}
