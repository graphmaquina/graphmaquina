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

    /** The minimal fixture, with two payload types to draw from and {@code declaration} as its operation. */
    private Map<String, String> source(String declaration) {
        Map<String, String> src = TypedFixtures.minimal();
        src.put("fx.DocTransition", "package fx; public enum DocTransition { PUBLISH, AUTO_PUBLISH }");
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
                    "return stateMachine.execute(doc, DocTransition.PUBLISH, input);"), generated);
        }
    }

    /**
     * Two operations over one pair of states, one with a payload and one without. Each executes its own
     * identifier, and only the one that declares a payload passes one.
     */
    @Test
    void twoOperationsOverOnePairDifferOnlyByTheirIdentifierAndTheirPayload() {
        Compilation.Result result = Compilation.compileTyped(twoOperations());
        assertTrue(result.success(), result.describe());
        String generated = result.generated("fx.DocsRuntime");
        assertTrue(generated.contains("return stateMachine.execute(doc, DocTransition.PUBLISH, input);"), generated);
        assertTrue(generated.contains("return stateMachine.execute(doc, DocTransition.AUTO_PUBLISH);"), generated);
        assertTrue(generated.contains("public Outcome<Doc, DocStatus, DocTransition> announce(Doc doc)"), generated);
    }

    /** The compiled consumer runs both operations and reads back what the guard and the action received. */
    @Test
    void generatedFacadeCarriesTheInputOfTheInvocationThatDeclaresOne() throws Exception {
        Compilation.Result result = Compilation.compileTyped(twoOperations());
        assertTrue(result.success(), result.describe());
        assertEquals(true, result.load("fx.Consumer").getMethod("check").invoke(null));
    }

    /** The minimal fixture with {@code publish(Doc, Verdict)} and {@code announce(Doc)} over the same pair. */
    private Map<String, String> twoOperations() {
        Map<String, String> src = source("publish(Doc doc, external.Verdict value)");
        String announce = "    @DocsGraph.Transition(id = AUTO_PUBLISH, from = DRAFT, to = PUBLISHED,"
                + " action = DocActionsRefs.Publish.class, guards = DocActionsRefs.Eligible.class)\n"
                + "    Outcome<Doc, DocStatus, DocTransition> announce(Doc doc);\n";
        src.put("fx.Docs", src.get("fx.Docs")
                .replace("action = DocActionsRefs.Publish.class)",
                        "action = DocActionsRefs.Publish.class, guards = DocActionsRefs.Eligible.class)")
                .replace("    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc, external.Verdict value);\n",
                        "    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc, external.Verdict value);\n" + announce));
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
                    @GraphGuard public GuardResult eligible(GuardContext<Doc, DocStatus, DocTransition> ctx) {
                        Optional<Verdict> value = ctx.input(Verdict.class);
                        guardSeen = value.orElse(null);
                        return value.map((Verdict v) -> v.accepted() ? GuardResult.pass() : GuardResult.reject("no"))
                                .orElseGet(GuardResult::pass);
                    }
                    @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus, DocTransition> ctx) {
                        actionSeen = ctx.input(Verdict.class).orElse(null);
                        return ActionOutcome.ok();
                    }
                }
                """);
        src.put("fx.Consumer", """
                package fx;
                import external.Verdict;
                import io.github.graphmaquina.api.Outcome;
                import io.github.graphmaquina.runtime.StateMachines;
                public final class Consumer {
                    public static boolean check() {
                        DocActions actions = new DocActions();
                        Docs docs = new DocsRuntime(StateMachines.from(DocsRuntime.graph(actions))
                                .state(Doc::status, Doc::status).build());
                        Doc plain = new Doc();
                        if (!(docs.announce(plain) instanceof Outcome.Applied)
                                || actions.guardSeen != null || actions.actionSeen != null) { return false; }
                        Verdict value = new Verdict(true);
                        Doc carried = new Doc();
                        if (!(docs.publish(carried, value) instanceof Outcome.Applied)
                                || actions.guardSeen != value || actions.actionSeen != value) { return false; }
                        Doc denied = new Doc();
                        return docs.publish(denied, new Verdict(false)) instanceof Outcome.Rejected
                                && denied.status() == DocStatus.DRAFT;
                    }
                }
                """);
        return src;
    }

    @Test
    void preservesEnumValuesInInputTypeAnnotations() throws Exception {
        Map<String, String> src = annotatedSource("@external.Mark(mode = external.Mode.YES) String");
        Compilation.Result result = Compilation.compileTyped(src);
        assertTrue(result.success(), result.describe());
        AnnotatedType parameter = result.load("fx.DocsRuntime").getMethod("publish", result.load("fx.Doc"), String.class)
                .getAnnotatedParameterTypes()[1];
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
                .replace("Outcome<Doc, DocStatus, DocTransition> publish", "<I> Outcome<Doc, DocStatus, DocTransition> publish"));
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
}
