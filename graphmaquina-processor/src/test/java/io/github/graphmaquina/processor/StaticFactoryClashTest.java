package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * A machine's companion declares a static {@code graph(...)}, which takes the definition of each machine it binds as a
 * sub-machine, then one parameter per class with instance members the machine binds, and a set's companion a static
 * {@code builder(...)}, which takes the timeout of the set's asynchronous machines, if it has any, and then every
 * class with instance members its machines use. Both are public API. javac refuses a static method that has the
 * signature of an instance method the class declares or inherits, abstract or default, which it would hide, or be
 * declared beside; it compares parameter types as members of the class, once erased. So a facade or a set that
 * declares or inherits such a method is refused, as {@code SG504} for a facade and {@code SG510} for a set: where it
 * declares the method, or where it inherits it, naming the interface that declares it. This extends the refusal of a
 * set accessor named {@code builder} when the companion's {@code builder()} takes nothing. A method of that name with
 * other parameter types is an overload, and is accepted, as is a static or private method of the interface, which a
 * class does not inherit.
 */
class StaticFactoryClashTest {

    /** {@code Pages}, which binds nothing, with {@code members} added to its body. */
    private static Map<String, String> pages(String members) {
        Map<String, String> sources = Fixtures.withDocs(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY);
        sources.put("fx.Pages", Fixtures.PAGES.replace(
                "    Outcome<Page, PageStatus, PageTransition> finish(Page page);\n",
                "    Outcome<Page, PageStatus, PageTransition> finish(Page page);\n" + members));
        return sources;
    }

    /**
     * A set holding Pages alone, which is synchronous and binds nothing, so its companion's {@code builder()} takes
     * nothing: {@code header} follows {@code Machines} among its supertypes, and {@code members} its accessor.
     */
    private static Map<String, String> pagesLibrary(String header, String members) {
        Map<String, String> sources = Fixtures.withPages(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY);
        sources.put("fx.Library", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs
                public interface Library extends Machines%s {
                    Pages pages();
                %s}
                """.formatted(header, members));
        return sources;
    }

    /** {@code file:line} of the first error of {@code code}, the simple name of its source file. */
    private static String at(Compilation.Result result, String code) {
        for (Diagnostic<? extends JavaFileObject> diagnostic : result.diagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR
                    && diagnostic.getMessage(null).contains("[" + code + "]")) {
                String name = diagnostic.getSource() == null ? "-" : diagnostic.getSource().getName();
                return name.substring(name.lastIndexOf('/') + 1) + ":" + diagnostic.getLineNumber();
            }
        }
        return "none";
    }

    /** {@code file:line} of the first line that contains {@code text} in the source of the type {@code name}. */
    private static String line(Map<String, String> sources, String name, String text) {
        List<String> lines = sources.get(name).lines().toList();
        for (int at = 0; at < lines.size(); at++) {
            if (lines.get(at).contains(text)) {
                return name.substring(name.lastIndexOf('.') + 1) + ".java:" + (at + 1);
            }
        }
        throw new IllegalArgumentException(text + " is not in " + name);
    }

    /**
     * A default {@code graph()} of Pages, and a generic default method of Docs whose type variable erases to
     * DocActions, the class the {@code graph(...)} of its companion takes.
     */
    @Test
    void sg504AGraphMethodTheFacadeDeclares() {
        Map<String, String> sources = pages("    default String graph() { return \"pages\"; }\n");

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of("[SG504] graph() is the static graph() the generated PagesRuntime declares, which takes no"
                + " parameter for a machine without sub-machines and without instance dependencies: give the method"
                + " another name"),
                result.errors(), result.describe());
        assertEquals(line(sources, "fx.Pages", "default String graph()"), at(result, "SG504"), result.describe());
        assertFalse(result.hasGenerated("fx.PagesRuntime"), result.describe());

        Map<String, String> generic = Fixtures.withDocs(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY
                + "    default <T extends DocActions> String graph(T actions) { return \"docs\"; }\n");
        Compilation.Result erased = Compilation.compileTyped(generic);
        assertEquals(List.of("[SG504] graph(DocActions) is the static graph(DocActions) the generated DocsRuntime"
                + " declares, which takes every class with instance members the machine binds: give the method"
                + " another name"),
                erased.errors(), erased.describe());
        assertEquals(line(generic, "fx.Docs", "String graph(T actions)"), at(erased, "SG504"), erased.describe());
    }

    /** The entity hosts the instance action of Docs, so {@code graph(Doc)} is what the companion declares. */
    @Test
    void sg504AnOperationNamedGraphThatTakesWhatTheCompanionsGraphTakes() {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.remove("fx.DocActions");
        sources.put("fx.Doc", sources.get("fx.Doc").replace("public final class Doc {", """
                public final class Doc {
                    @io.github.graphmaquina.annotation.GraphAction
                    public io.github.graphmaquina.api.ActionOutcome publish(
                            io.github.graphmaquina.api.TransitionContext<Doc, DocStatus, DocTransition> context) {
                        return io.github.graphmaquina.api.ActionOutcome.ok();
                    }"""));
        sources.put("fx.Docs", sources.get("fx.Docs").replace("DocActionsRefs.Publish.class", "DocRefs.Publish.class")
                .replace("publish(Doc doc);", "graph(Doc doc);"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of("[SG504] graph(Doc) is the static graph(Doc) the generated DocsRuntime declares, which"
                + " takes every class with instance members the machine binds: give the operation another name"),
                result.errors(), result.describe());
        assertEquals(line(sources, "fx.Docs", "graph(Doc doc);"), at(result, "SG504"), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
    }

    /**
     * Inherited, from a base the facade extends, or from a generic base whose type argument makes the method's
     * parameter, as a member of the facade, the class {@code graph(...)} takes.
     */
    @Test
    void sg504AGraphMethodTheFacadeInherits() {
        Map<String, String> named = pages("");
        named.put("fx.Named",
                "package fx;\npublic interface Named {\n    default String graph() { return \"named\"; }\n}\n");
        named.put("fx.Pages", named.get("fx.Pages").replace("extends Machine<Page, PageStatus, PageTransition>",
                "extends Machine<Page, PageStatus, PageTransition>, Named"));
        Compilation.Result inherited = Compilation.compileTyped(named);
        assertEquals(List.of("[SG504] Pages inherits graph() from fx.Named, but the generated PagesRuntime declares a"
                + " static graph(), which takes no parameter for a machine without sub-machines and without instance"
                + " dependencies: give that method another name"), inherited.errors(), inherited.describe());
        assertEquals(line(named, "fx.Pages", "public interface Pages"), at(inherited, "SG504"), inherited.describe());
        assertFalse(inherited.hasGenerated("fx.PagesRuntime"), inherited.describe());

        Map<String, String> generic = Fixtures.withDocs(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY);
        generic.put("fx.Graphed", "package fx;\npublic interface Graphed<T> {\n"
                + "    default String graph(T host) { return \"graphed\"; }\n}\n");
        generic.put("fx.Docs", generic.get("fx.Docs").replace("extends Machine<Doc, DocStatus, DocTransition>",
                "extends Machine<Doc, DocStatus, DocTransition>, Graphed<DocActions>"));
        Compilation.Result erased = Compilation.compileTyped(generic);
        assertEquals(1, erased.countError("SG504"), erased.describe());
        assertTrue(erased.errors().get(0).contains("Docs inherits graph(DocActions) from fx.Graphed, but the generated"
                + " DocsRuntime declares a static graph(DocActions), which takes every class with instance members the"
                + " machine binds"), erased.describe());
        assertFalse(erased.hasGenerated("fx.DocsRuntime"), erased.describe());
    }

    /**
     * Other parameter types make an overload: a {@code graph(String)} of Pages, whose companion's {@code graph()} takes
     * nothing, and a generic base of Docs whose argument is not DocActions, the class its companion's
     * {@code graph(...)} takes. A private {@code graph()} of Pages and a static {@code graph(DocActions)} of Docs are
     * no members the companion inherits.
     */
    @Test
    void aGraphMethodThatOnlyOverloadsTheCompanionsIsAccepted() {
        Map<String, String> sources = pages("""
                    default String graph(String name) { return name + graph(); }
                    private String graph() { return "private"; }
                """);
        sources.put("fx.Graphed", "package fx;\npublic interface Graphed<T> {\n"
                + "    default String graph(T host) { return \"graphed\"; }\n}\n");
        sources.put("fx.Docs", sources.get("fx.Docs").replace("extends Machine<Doc, DocStatus, DocTransition>",
                "extends Machine<Doc, DocStatus, DocTransition>, Graphed<String>")
                .replace("Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);",
                        "Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);\n"
                        + "    static String graph(DocActions actions) { return \"static\"; }"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
    }

    @Test
    void sg510ABuilderMethodTheSetDeclares() {
        Map<String, String> sources = pagesLibrary("", "    default String builder() { return \"library\"; }\n");

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of("[SG510] builder() is the static builder() the generated LibraryRuntime declares, which"
                + " takes no parameter for a set without asynchronous machines and without instance dependencies: give"
                + " the method another name"), result.errors(), result.describe());
        assertEquals(line(sources, "fx.Library", "default String builder()"), at(result, "SG510"), result.describe());
        assertFalse(result.hasGenerated("fx.LibraryRuntime"), result.describe());
    }

    /**
     * Docs is asynchronous and binds DocActions, so {@code builder(Duration, DocActions)} is what the companion
     * declares.
     */
    @Test
    void sg510ABuilderMethodWithTheParametersOfTheCompanions() {
        Map<String, String> sources = Fixtures.withLibrary(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY,
                Fixtures.LIBRARY.replace("Pages pages();",
                        "Pages pages();\n    default String builder(java.time.Duration timeout, DocActions actions) {"
                        + " return \"library\"; }"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of("[SG510] builder(Duration, DocActions) is the static builder(Duration, DocActions) the"
                + " generated LibraryRuntime declares, which takes the timeout shared by the set's asynchronous"
                + " machines, then every class with instance members its machines use: give the method another name"),
                result.errors(), result.describe());
        assertEquals(line(sources, "fx.Library", "default String builder("), at(result, "SG510"), result.describe());
        assertFalse(result.hasGenerated("fx.LibraryRuntime"), result.describe());
    }

    /**
     * A default method the set inherits is reported where the set is declared, at its {@code @StateGraphs}, naming the
     * interface that declares it. An abstract one is an accessor the companion would not implement, which is reported
     * as such, and only as such.
     */
    @Test
    void sg510ABuilderMethodTheSetInherits() {
        Map<String, String> sources = pagesLibrary(", Named", "");
        sources.put("fx.Named",
                "package fx;\npublic interface Named {\n    default String builder() { return \"named\"; }\n}\n");
        Compilation.Result inherited = Compilation.compileTyped(sources);
        assertEquals(List.of("[SG510] Library inherits builder() from fx.Named, but the generated LibraryRuntime"
                + " declares a static builder(), which takes no parameter for a set without asynchronous machines and"
                + " without instance dependencies: give that method another name"), inherited.errors(),
                inherited.describe());
        assertEquals(line(sources, "fx.Library", "@StateGraphs"), at(inherited, "SG510"), inherited.describe());
        assertFalse(inherited.hasGenerated("fx.LibraryRuntime"), inherited.describe());

        sources.put("fx.Named", "package fx;\npublic interface Named {\n    Pages builder();\n}\n");
        sources.put("fx.Library", sources.get("fx.Library").replace("    Pages pages();\n", ""));
        Compilation.Result abstractOne = Compilation.compileTyped(sources);
        assertEquals(1, abstractOne.errors().size(), abstractOne.describe());
        assertTrue(abstractOne.errors().get(0).contains(
                "[SG510] Library inherits the abstract method builder from fx.Named"),
                abstractOne.describe());
    }

    /**
     * A {@code builder(String)} is an overload of the companion's {@code builder()}, and a static one is not inherited.
     */
    @Test
    void aBuilderMethodThatOnlyOverloadsTheCompanionsIsAccepted() {
        Compilation.Result result = Compilation.compileTyped(pagesLibrary("", """
                    default String builder(String name) { return name; }
                    static String builder() { return "static"; }
                """));

        assertTrue(result.success(), result.describe());
    }
}
