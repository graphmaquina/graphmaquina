package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The second parameter an operation may declare: which shapes the processor accepts, which ones it still
 * refuses with {@code SG504}, and what the generated facade does with the value it receives.
 *
 * <p>Every case runs the two stages of a consumer build over a real fixture, so the type the facade declares
 * is the one javac resolved and the companion is the source the processor actually wrote. The payload belongs
 * to the invocation: the identifier an operation executes is the same whether it takes one or not.</p>
 */
class OperationInputProcessorTest {

    /** The host of {@code twoOperations}: its guard and its action record the payload they are handed. */
    private static final String PAYLOAD_HOST = """
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
                public boolean guardHadInput;
                public Object guardSeen;
                public Object actionSeen;
                @GraphGuard public GuardResult eligible(GuardContext<Doc, DocStatus, DocTransition> ctx) {
                    Optional<Verdict> value = ctx.input(Verdict.class);
                    guardHadInput = value.isPresent();
                    guardSeen = value.orElse(null);
                    return value.map((Verdict v) -> v.accepted() ? GuardResult.pass() : GuardResult.reject("no"))
                            .orElseGet(GuardResult::pass);
                }
                @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus, DocTransition> ctx) {
                    actionSeen = ctx.input(Verdict.class).orElse(null);
                    return ActionOutcome.ok();
                }
            }
            """;

    /** The consumer that runs both operations of {@code twoOperations} and says whether the payload arrived. */
    private static final String PAYLOAD_CONSUMER = """
            package fx;
            import external.Verdict;
            import io.github.graphmaquina.api.Outcome;
            import io.github.graphmaquina.runtime.StateMachines;
            import java.util.concurrent.CompletableFuture;
            import java.util.concurrent.ExecutorService;
            import java.util.concurrent.Executors;
            import java.util.concurrent.TimeUnit;
            import java.util.concurrent.atomic.AtomicReference;
            public final class Consumer {
                public static boolean check(boolean move) throws Exception {
                    DocActions actions = new DocActions();
                    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        Docs docs = new DocsRuntime(StateMachines.from(DocsRuntime.graph(actions))
                                .state(Doc::status, Doc::status).asyncExecutor(executor).build());
                        // An operation that declares no payload carries none.
                        Doc plain = new Doc();
                        if (!(docs.announce(plain) instanceof Outcome.Applied) || actions.guardHadInput
                                || actions.guardSeen != null || actions.actionSeen != null) { return false; }

                        // The payload reaches guard and action from a worker thread of async(...).
                        Verdict value = new Verdict(true);
                        Doc carried = new Doc();
                        Thread caller = Thread.currentThread();
                        AtomicReference<Thread> worker = new AtomicReference<>();
                        CompletableFuture<Outcome<Doc, DocStatus, DocTransition>> future = docs.async(() -> {
                            worker.set(Thread.currentThread());
                            return docs.publish(carried, value);
                        });
                        if (!(future.get(5, TimeUnit.SECONDS) instanceof Outcome.Applied)
                                || worker.get() == caller || carried.status() != DocStatus.PUBLISHED
                                || !actions.guardHadInput || actions.guardSeen != value
                                || (move ? actions.actionSeen != null : actions.actionSeen != value)) { return false; }

                        // An explicit null is refused before the call starts: no guard or action runs.
                        actions.guardHadInput = false;
                        actions.guardSeen = null;
                        actions.actionSeen = null;
                        Doc nulled = new Doc();
                        try {
                            docs.publish(nulled, null);
                            return false;
                        } catch (NullPointerException refused) {
                            if (!"value".equals(refused.getMessage()) || actions.guardHadInput
                                    || actions.guardSeen != null || actions.actionSeen != null
                                    || nulled.status() != DocStatus.DRAFT) { return false; }
                        }

                        // A guard that reads the payload can still refuse it.
                        Doc denied = new Doc();
                        return docs.publish(denied, new Verdict(false)) instanceof Outcome.Rejected
                                && denied.status() == DocStatus.DRAFT;
                    }
                }
            }
            """;

    /**
     * The minimal fixture, with two payload types to draw from, a second transition identifier and
     * {@code declaration} as its operation.
     */
    private Map<String, String> source(String declaration) {
        Map<String, String> src = TypedFixtures.minimal();
        src.put("fx.DocTransition", "package fx; public enum DocTransition { PUBLISH, AUTO_PUBLISH }");
        src.put("external.Verdict", "package external; public record Verdict(boolean accepted) {} ");
        src.put("external.Types", "package external; public final class Types { public record Nested(int n) {} }");
        src.put("fx.Docs", src.get("fx.Docs").replace("publish(Doc doc)", declaration));
        return src;
    }

    /**
     * Every supported type of payload compiles and is handed on as declared. A payload of a reference type is
     * refused when null, as the entity is, with a message that names its parameter; a primitive one cannot be null.
     */
    @Test
    void preservesEachSupportedInputType() {
        for (String type : List.of("external.Verdict", "external.Types.Nested", "String",
                "java.util.List<external.Verdict>", "java.util.List<? extends external.Verdict>",
                "external.Verdict[]", "int[]", "int", "boolean")) {
            Compilation.Result result = Compilation.compileTyped(source("publish(Doc doc, " + type + " value)"));
            assertTrue(result.success(), type + "\n" + result.describe());
            String generated = result.generated("fx.DocsRuntime");
            assertTrue(generated.contains(
                    "return stateMachine.execute(doc, DocTransition.PUBLISH, value);"), generated);
            boolean primitive = type.equals("int") || type.equals("boolean");
            assertEquals(!primitive, generated.contains("Objects.requireNonNull(value, \"value\");"),
                    () -> type + "\n" + generated);
        }
    }

    /**
     * Two operations over one pair of states, one with a payload and one without. Each executes its own
     * identifier, and only the one that declares a payload passes one.
     */
    @Test
    void twoOperationsOverOnePairDifferOnlyByTheirIdentifierAndTheirPayload() {
        Compilation.Result result = Compilation.compileTyped(twoOperations(false));
        assertTrue(result.success(), result.describe());
        String generated = result.generated("fx.DocsRuntime");
        assertTrue(generated.contains("return stateMachine.execute(doc, DocTransition.PUBLISH, value);"), generated);
        assertTrue(generated.contains("return stateMachine.execute(doc, DocTransition.AUTO_PUBLISH);"), generated);
        assertTrue(generated.contains("public Outcome<Doc, DocStatus, DocTransition> announce(Doc doc)"), generated);
    }

    /**
     * The compiled consumer runs both operations through the generated facade and reads back what the guard
     * and the action received: an operation that declares no payload carries none, the payload of an
     * invocation reaches guard and action even when the call is made on a worker thread of
     * {@code async(...)}, an explicit {@code null} is refused before the call starts with a
     * {@code NullPointerException} that names the parameter, and a guard that reads the payload can still refuse it.
     * The whole matrix runs twice, once with an ACTION edge and once with a MOVE, because a payload belongs to the
     * invocation and not to the edge.
     */
    @Test
    void generatedFacadeCarriesTheInputOfTheInvocationThatDeclaresOne() throws Exception {
        for (boolean move : List.of(false, true)) {
            Compilation.Result result = Compilation.compileTyped(twoOperations(move));
            assertTrue(result.success(), move + "\n" + result.describe());
            // The move variant really is one: no edge of it runs an action at all.
            assertEquals(!move, result.generated("fx.DocsRuntime").contains(".action(\"publish\""),
                    () -> result.generated("fx.DocsRuntime"));
            assertEquals(true, result.load("fx.Consumer").getMethod("check", boolean.class).invoke(null, move));
        }
    }

    /**
     * The minimal fixture with {@code publish(Doc, Verdict)} and {@code announce(Doc)} over the same pair of
     * states. {@code move} strips the action of both edges, so the same operations run as plain moves.
     */
    private Map<String, String> twoOperations(boolean move) {
        Map<String, String> src = source("publish(Doc doc, external.Verdict value)");
        String announce = "    @DocsGraph.Transition(id = AUTO_PUBLISH, from = DRAFT, to = PUBLISHED,"
                + " action = DocActionsRefs.Publish.class, guards = DocActionsRefs.Eligible.class)\n"
                + "    Outcome<Doc, DocStatus, DocTransition> announce(Doc doc);\n";
        String docs = src.get("fx.Docs")
                .replace("action = DocActionsRefs.Publish.class)",
                        "action = DocActionsRefs.Publish.class, guards = DocActionsRefs.Eligible.class)")
                .replace("    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc, external.Verdict value);\n",
                        "    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc, external.Verdict value);\n"
                                + announce);
        src.put("fx.Docs", move
                ? docs.replace("action = DocActionsRefs.Publish.class, guards = ", "guards = ")
                : docs);
        src.put("fx.DocActions", PAYLOAD_HOST);
        src.put("fx.Consumer", PAYLOAD_CONSUMER);
        return src;
    }

    @Test
    void preservesEnumValuesInInputTypeAnnotations() throws Exception {
        Map<String, String> src = annotatedSource("@external.Mark(mode = external.Mode.YES) String");
        Compilation.Result result = Compilation.compileTyped(src);
        assertTrue(result.success(), result.describe());
        AnnotatedType parameter = result.load("fx.DocsRuntime")
                .getMethod("publish", result.load("fx.Doc"), String.class).getAnnotatedParameterTypes()[1];
        Object annotation = parameter.getAnnotations()[0];
        assertEquals("YES", result.load("external.Mark").getMethod("mode").invoke(annotation).toString());
    }

    @Test
    void preservesAnnotationsThroughoutNestedInputTypes() {
        for (String type : List.of("java.util.List<@external.Mark(mode=external.Mode.YES) String>",
                "java.util.List<@external.Mark(mode=external.Mode.YES) ? extends String>",
                "java.util.List<? super @external.Mark(mode=external.Mode.YES) String>",
                "external.Types.@external.Mark(mode=external.Mode.YES) Nested",
                "external.Box<@external.Mark(mode=external.Mode.YES) String>.Inner<Integer>",
                "@external.Mark(mode=external.Mode.YES) int",
                "@external.Mark(mode=external.Mode.YES) String @external.Mark(mode=external.Mode.NO) []")) {
            Compilation.Result result = Compilation.compileTyped(annotatedSource(type));
            assertTrue(result.success(), type + "\n" + result.describe());
        }
    }

    @Test
    void preservesArrayAnnotationOrderAndNestedAnnotationValues() throws Exception {
        Map<String, String> src = annotatedSource("String @external.Mark(mode=external.Mode.YES,"
                + " modes={external.Mode.NO, external.Mode.YES}, nested=@external.Nested(external.Mode.NO),"
                + " type=int[].class, text=\"mode=YES\") [] @external.Mark(mode=external.Mode.NO) []");
        Compilation.Result result = Compilation.compileTyped(src);
        assertTrue(result.success(), result.describe());
        AnnotatedArrayType array = (AnnotatedArrayType) result.load("fx.DocsRuntime")
                .getMethod("publish", result.load("fx.Doc"), String[][].class).getAnnotatedParameterTypes()[1];
        Class<?> mark = result.load("external.Mark");
        Object outer = array.getAnnotations()[0];
        Object inner = array.getAnnotatedGenericComponentType().getAnnotations()[0];
        assertEquals("YES", mark.getMethod("mode").invoke(outer).toString());
        assertEquals("NO", mark.getMethod("mode").invoke(inner).toString());
        Object[] modes = (Object[]) mark.getMethod("modes").invoke(outer);
        assertEquals(List.of("NO", "YES"), List.of(modes[0].toString(), modes[1].toString()));
        Object nested = mark.getMethod("nested").invoke(outer);
        assertEquals("NO", result.load("external.Nested").getMethod("value").invoke(nested).toString());
        assertEquals(int[].class, mark.getMethod("type").invoke(outer));
        assertEquals("mode=YES", mark.getMethod("text").invoke(outer));
    }

    private Map<String, String> annotatedSource(String type) {
        Map<String, String> src = source("publish(Doc doc, " + type + " value)");
        src.put("external.Mode", "package external; public enum Mode { YES, NO }");
        src.put("external.Nested", """
                package external;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Nested { Mode value(); }
                """);
        src.put("external.Mark", """
                package external;
                @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Mark {
                    Mode mode();
                    Mode[] modes() default {};
                    Nested nested() default @Nested(Mode.YES);
                    Class<?> type() default Object.class;
                    String text() default "";
                }
                """);
        src.put("external.Box", "package external; public class Box<T> { public class Inner<U> {} }");
        return src;
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
                .replace("Outcome<Doc, DocStatus, DocTransition> publish",
                        "<I> Outcome<Doc, DocStatus, DocTransition> publish"));
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
        assertTrue(result.generated("fx.DocsRuntime").contains("publish(Input doc, external.Verdict value)"),
                result.generated("fx.DocsRuntime"));
    }
}
