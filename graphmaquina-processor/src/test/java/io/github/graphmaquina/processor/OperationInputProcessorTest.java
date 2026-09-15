package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The second parameter an operation may declare: which shapes the processor accepts, which ones it still
 * refuses with {@code SG504}, and what the generated facade does with the value it receives.
 *
 * <p>Every case runs the two stages of a consumer build over a real fixture, so the type the facade declares
 * is the one javac resolved and the companion is the source the processor actually wrote.</p>
 */
class OperationInputProcessorTest {

    /** The minimal fixture, with two payload types to draw from and {@code declaration} as its operation. */
    private Map<String, String> source(String declaration) {
        Map<String, String> src = TypedFixtures.minimal();
        src.put("external.Verdict", "package external; public record Verdict(boolean accepted) {} ");
        src.put("external.Types", "package external; public final class Types { public record Nested(int n) {} }");
        src.put("fx.Docs", src.get("fx.Docs").replace("publish(Doc doc)", declaration));
        return src;
    }

    @Test
    void preservesEachSupportedInputType() {
        for (String type : List.of("external.Verdict", "external.Types.Nested", "String",
                "java.util.List<external.Verdict>", "java.util.List<? extends external.Verdict>",
                "external.Verdict[]", "int[]", "int", "boolean")) {
            Compilation.Result result = Compilation.compileTyped(source("publish(Doc doc, " + type + " value)"));
            assertTrue(result.success(), type + "\n" + result.describe());
            String generated = result.generated("fx.DocsRuntime");
            assertTrue(generated.contains(
                    "return stateMachine.transitionTo(doc, DocStatus.PUBLISHED, input);"), generated);
        }
    }

    @Test
    void retainsInvalidSignatureDiagnostics() {
        for (String declaration : List.of("publish()", "publish(String wrong)",
                "publish(Doc doc, String a, String b)", "publish(Doc doc, String... values)")) {
            Compilation.Result result = Compilation.compileTyped(source(declaration));
            assertFalse(result.success());
            assertTrue(result.hasError("SG504"), result.describe());
            assertFalse(result.hasGenerated("fx.DocsRuntime"));
        }
        Map<String, String> generic = source("publish(Doc doc, I input)");
        generic.put("fx.Docs", generic.get("fx.Docs")
                .replace("Outcome<Doc, DocStatus> publish", "<I> Outcome<Doc, DocStatus> publish"));
        Compilation.Result result = Compilation.compileTyped(generic);
        assertTrue(result.hasError("SG504"), result.describe());
    }

    @Test
    void inputNamedEntityCannotCollideWithPayloadParameter() {
        Map<String, String> original = source("publish(Doc doc, external.Verdict value)");
        Map<String, String> renamed = new LinkedHashMap<>();
        original.forEach((String name, String text) -> renamed.put(name.equals("fx.Doc") ? "fx.Input" : name,
                text.replaceAll("\\bDoc\\b", "Input").replace("DocRefs", "InputRefs")));
        Compilation.Result result = Compilation.compileTyped(renamed);
        assertTrue(result.success(), result.describe());
        assertTrue(result.generated("fx.DocsRuntime").contains("external.Verdict input2"));
    }

    @Test
    void generatedFacadeCarriesInputToGuardActionAndAsyncSupplier() throws Exception {
        for (boolean move : List.of(false, true)) {
            Map<String, String> src = source("publish(Doc doc, external.Verdict value)");
            src.put("fx.DocActions", """
                    package fx;
                    import external.Verdict;
                    import io.github.graphmaquina.annotation.GraphAction;
                    import io.github.graphmaquina.annotation.GraphGuard;
                    import io.github.graphmaquina.api.ActionOutcome;
                    import io.github.graphmaquina.api.GuardContext;
                    import io.github.graphmaquina.api.GuardResult;
                    import io.github.graphmaquina.api.TransitionContext;
                    import java.util.Optional;
                    public final class DocActions {
                        public Object guardSeen;
                        public Object actionSeen;
                        @GraphGuard public GuardResult eligible(GuardContext<Doc, DocStatus> ctx) {
                            Optional<Verdict> value = ctx.input(Verdict.class);
                            guardSeen = value.orElse(null);
                            return value.map((Verdict v) -> v.accepted() ? GuardResult.pass() : GuardResult.reject("no"))
                                    .orElseGet(() -> GuardResult.reject("absent"));
                        }
                        @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus> ctx) {
                            actionSeen = ctx.input(Verdict.class).orElseThrow();
                            return ActionOutcome.ok();
                        }
                    }
                    """);
            String annotation = move
                    ? "@DocsGraph.Move(from = DRAFT, to = PUBLISHED, guards = DocActionsRefs.Eligible.class)"
                    : "@DocsGraph.Action(from = DRAFT, to = PUBLISHED, action = DocActionsRefs.Publish.class,"
                            + " guards = DocActionsRefs.Eligible.class)";
            src.put("fx.Docs", src.get("fx.Docs").replace(
                    "@DocsGraph.Action(from = DRAFT, to = PUBLISHED, action = DocActionsRefs.Publish.class)", annotation));
            src.put("fx.Consumer", """
                    package fx;
                    import external.Verdict;
                    import io.github.graphmaquina.api.Outcome;
                    import io.github.graphmaquina.runtime.StateMachines;
                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.ExecutorService;
                    import java.util.concurrent.Executors;
                    import java.util.concurrent.TimeUnit;
                    public final class Consumer {
                        public static boolean check(boolean move) throws Exception {
                            DocActions actions = new DocActions();
                            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                                Docs docs = new DocsRuntime(StateMachines.from(DocsRuntime.graph(actions))
                                        .state(Doc::status, Doc::status).asyncExecutor(executor).build());
                                Verdict value = new Verdict(true);
                                Doc accepted = new Doc();
                                Thread caller = Thread.currentThread();
                                CompletableFuture<Outcome<Doc, DocStatus>> future = docs.async(() -> {
                                    if (Thread.currentThread() == caller) { throw new AssertionError("caller thread"); }
                                    return docs.publish(accepted, value);
                                });
                                if (!(future.get(5, TimeUnit.SECONDS) instanceof Outcome.Applied)
                                        || accepted.status() != DocStatus.PUBLISHED || actions.guardSeen != value
                                        || (move ? actions.actionSeen != null : actions.actionSeen != value)) { return false; }
                                Doc denied = new Doc();
                                if (!(docs.publish(denied, new Verdict(false)) instanceof Outcome.Rejected)
                                        || denied.status() != DocStatus.DRAFT) { return false; }
                                if (!(docs.publish(new Doc(), null) instanceof Outcome.Rejected)
                                        || actions.guardSeen != null) { return false; }
                                return move ? actions.actionSeen == null : actions.actionSeen == value;
                            }
                        }
                    }
                    """);
            Compilation.Result result = Compilation.compileTyped(src);
            assertTrue(result.success(), result.describe());
            assertEquals(true, result.load("fx.Consumer").getMethod("check", boolean.class).invoke(null, move));
        }
    }
}
