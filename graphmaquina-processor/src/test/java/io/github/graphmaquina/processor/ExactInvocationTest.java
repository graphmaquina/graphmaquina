package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.Machines;
import io.github.graphmaquina.api.Outcome;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The overload the processor selected is the one the running program calls.
 *
 * <p>The host declares two methods of one name and one arity that both accept the arguments a watch condition
 * is given, and only one of them is marked. Reading the generated source would only show that a parameter type
 * was written somewhere; what has to hold is that javac then resolved the call to the marked declaration, so
 * the watch is run for real, through the facade and against the peer machine of its own set, and the method
 * that ran is the one that says which it was.</p>
 */
class ExactInvocationTest {

    @Test
    void runsTheMarkedOverloadOfAWatchCondition() throws Exception {
        assertEquals("marked", publishThroughTheWatch(TypedFixtures.watching()));
    }

    @Test
    void runsTheMarkedOverloadInsteadOfAPackagePrivateOverload() throws Exception {
        Map<String, String> sources = TypedFixtures.watching();
        sources.put("fx.DocActions", sources.get("fx.DocActions").replace(
                "public boolean ready(Doc doc, Collection", "boolean ready(Doc doc, Collection"));
        assertEquals("marked", publishThroughTheWatch(sources));
    }

    @Test
    void runsTheMarkedOverloadInsteadOfAProtectedOverload() throws Exception {
        Map<String, String> sources = TypedFixtures.watching();
        sources.put("fx.DocActions", sources.get("fx.DocActions").replace(
                "public boolean ready(Doc doc, Collection", "protected boolean ready(Doc doc, Collection"));
        assertEquals("marked", publishThroughTheWatch(sources));
    }

    /**
     * The type that pins the marked overload is written as source: a type annotation on it, whose value names an
     * enum constant, is written with that constant qualified, and not as javac prints the type.
     */
    @Test
    void pinsTheMarkedOverloadWithATypeWhoseAnnotationsNameEnumConstants() throws Exception {
        Map<String, String> sources = TypedFixtures.watching();
        sources.put("fx.Tag", """
                package fx;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                @Target(ElementType.TYPE_USE)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface Tag {
                    String value() default "";
                    java.util.concurrent.TimeUnit unit() default java.util.concurrent.TimeUnit.SECONDS;
                }
                """);
        sources.put("fx.DocActions", sources.get("fx.DocActions").replace("Iterable<? extends Page> pages",
                "@Tag(value = \"packed\", unit = java.util.concurrent.TimeUnit.DAYS) "
                        + "Iterable<? extends @Tag(\"page\") Page> pages"));

        assertEquals("marked", publishThroughTheWatch(sources));
    }

    @Test
    void runsTheMarkedOverloadInheritedFromABaseHost() throws Exception {
        assertEquals("marked", publishThroughTheWatch(inheritedHost()));
    }

    /**
     * Compiles {@code sources}, builds the set with one instance of the host, submits a document and lets the
     * watch of its review decide. The one page of the document stays open, so only the condition can hold; the
     * document reaching {@code PUBLISHED} means the rule ran, and the host says which method it ran.
     */
    private static String publishThroughTheWatch(Map<String, String> sources) throws Exception {
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result.describe());
        Class<?> hostClass = result.load("fx.DocActions");
        Object host = hostClass.getConstructor().newInstance();
        Class<?> companion = result.load("fx.LibraryRuntime");
        List<Method> builders = builders(companion);
        assertEquals(1, builders.size(), () -> builders.toString());
        assertEquals(List.of(hostClass), Arrays.asList(builders.get(0).getParameterTypes()));

        Object builder = builders.get(0).invoke(null, host);
        Object library = builder.getClass().getMethod("build").invoke(builder);
        try {
            Object docs = companion.getMethod("docs").invoke(library);
            Class<?> docClass = result.load("fx.Doc");
            Class<?> pageClass = result.load("fx.Page");
            Object doc = docClass.getConstructor(String.class).newInstance("d1");
            pageClass.getConstructor(String.class, docClass).newInstance("p1", doc);
            assertInstanceOf(Outcome.Applied.class,
                    docs.getClass().getMethod("submit", docClass).invoke(docs, doc));
            assertEquals("REVIEW", docClass.getMethod("status").invoke(doc).toString());

            assertInstanceOf(Outcome.Applied.class,
                    docs.getClass().getMethod("reevaluate", docClass).invoke(docs, doc));
            assertEquals("PUBLISHED", docClass.getMethod("status").invoke(doc).toString());
            return (String) hostClass.getField("selected").get(host);
        } finally {
            ((Machines) library).close();
        }
    }

    /** The same fixture with the marked condition inherited: the concrete host adds nothing of its own. */
    private static Map<String, String> inheritedHost() {
        Map<String, String> sources = TypedFixtures.watching();
        sources.put("fx.DocWatches", TypedFixtures.DOC_ACTIONS.formatted("public class DocWatches {"));
        sources.put("fx.DocActions", """
                package fx;
                import io.github.graphmaquina.annotation.GraphBindings;
                @GraphBindings
                public final class DocActions extends DocWatches { }
                """);
        return sources;
    }

    /** The factory methods of a generated set companion, which is where its dependencies are counted. */
    private static List<Method> builders(Class<?> companion) {
        List<Method> found = new ArrayList<>();
        for (Method method : companion.getMethods()) {
            if (method.getName().equals("builder")) {
                found.add(method);
            }
        }
        return found;
    }
}
