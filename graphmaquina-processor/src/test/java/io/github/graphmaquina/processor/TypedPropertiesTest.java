package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.processor.TypedProcessors.Use;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A marked getter exports that reader and nothing else; a marked field exports the property its own name
 * spells, and the accessors are searched by the conventions. Both are interpreted as members of the entity, so
 * an inherited accessor of a generic base reads the entity's own state type.
 */
class TypedPropertiesTest {

    @Test
    void resolvesAMarkedGetterByItsOwnDeclaration() {
        TypedProcessors.Resolving.reset();
        TypedProcessors.Resolving.property("fx.DocRefs.URL", Use.ID, "fx.Doc/fx.DocStatus/fx.DocTransition");

        Compilation.Result result = resolve(entity("""
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Doc {
                    @GraphProperty public String getURL() { return "u"; }
                }
                """));

        assertTrue(result.success(), result.describe());
        assertEquals("getURL(): java.lang.String", answer(result, "fx.DocRefs.URL", Use.ID));
    }

    @Test
    void resolvesAMarkedFieldThroughTheConventionalGetterOfItsExactName() {
        TypedProcessors.Resolving.reset();
        TypedProcessors.Resolving.property("fx.DocRefs.URL", Use.ID, "fx.Doc/fx.DocStatus/fx.DocTransition");

        Compilation.Result result = resolve(entity("""
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Doc {
                    @GraphProperty private final String uRL = "u";
                    public String getURL() { return uRL; }
                }
                """));

        assertTrue(result.success(), result.describe());
        assertEquals("getURL(): java.lang.String", answer(result, "fx.DocRefs.URL", Use.ID));
    }

    @Test
    void keepsTheFluentGetterAMarkerNames() {
        TypedProcessors.Resolving.reset();
        TypedProcessors.Resolving.property("fx.DocRefs.Status", Use.STATE, "fx.Doc/fx.DocStatus/fx.DocTransition");

        Compilation.Result result = resolve(entity("""
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Doc {
                    private DocStatus status = DocStatus.DRAFT;
                    @GraphProperty public DocStatus status() { return status; }
                    public void setStatus(DocStatus value) { status = value; }
                }
                """));

        assertTrue(result.success(), result.describe());
        assertEquals("status(): fx.DocStatus / setStatus(fx.DocStatus): void",
                answer(result, "fx.DocRefs.Status", Use.STATE));
    }

    @Test
    void doesNotReplaceTheMarkedGetterWithAConventionalOne() {
        TypedProcessors.Resolving.reset();
        TypedProcessors.Resolving.property("fx.DocRefs.Status", Use.STATE, "fx.Doc/fx.DocStatus/fx.DocTransition");

        Compilation.Result result = resolve(entity("""
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Doc {
                    private DocStatus status = DocStatus.DRAFT;
                    @GraphProperty public DocStatus getStatus() { return status; }
                    public DocStatus status() { return status; }
                    public void setStatus(DocStatus value) { status = value; }
                }
                """));

        assertTrue(result.success(), result.describe());
        assertEquals("getStatus(): fx.DocStatus / setStatus(fx.DocStatus): void",
                answer(result, "fx.DocRefs.Status", Use.STATE));
    }

    @Test
    void readsAnInheritedAccessorInTheContextOfTheEntityAndExportsItOnce() {
        TypedProcessors.Resolving.reset();
        TypedProcessors.Resolving.property("fx.DocRefs.Status", Use.STATE, "fx.Doc/fx.DocStatus/fx.DocTransition");
        Map<String, String> sources = entity("""
                package fx;
                import io.github.graphmaquina.annotation.GraphBindings;
                @GraphBindings
                public final class Doc extends Base<DocStatus> {
                    private DocStatus status = DocStatus.DRAFT;
                    @Override public DocStatus status() { return status; }
                    @Override public void setStatus(DocStatus value) { status = value; }
                }
                """);
        sources.put("fx.Base", """
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                public abstract class Base<S extends Enum<S>> {
                    @GraphProperty public abstract S status();
                    public abstract void setStatus(S value);
                }
                """);

        Compilation.Prepared prepared = Compilation.prepare(sources);
        Compilation.Result result = resolve(prepared);

        assertTrue(result.success(), result.describe());
        assertEquals("status(): fx.DocStatus / setStatus(fx.DocStatus): void",
                answer(result, "fx.DocRefs.Status", Use.STATE));
        assertEquals(1, occurrences(exported(prepared, "fx.DocRefs"), "class Status"),
                exported(prepared, "fx.DocRefs"));
    }

    @Test
    void prefersTheSetterPrefixAndAcceptsASupertypeParameterAndAFluentReturn() {
        TypedProcessors.Resolving.reset();
        TypedProcessors.Resolving.property("fx.DocRefs.Status", Use.STATE, "fx.Doc/fx.DocStatus/fx.DocTransition");

        Compilation.Result result = resolve(entity("""
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Doc {
                    @GraphProperty private DocStatus status = DocStatus.DRAFT;
                    public DocStatus status() { return status; }
                    public Doc setStatus(Object value) { status = (DocStatus) value; return this; }
                    public void status(DocStatus value) { status = value; }
                }
                """));

        assertTrue(result.success(), result.describe());
        assertEquals("status(): fx.DocStatus / setStatus(java.lang.Object): fx.Doc",
                answer(result, "fx.DocRefs.Status", Use.STATE));
    }

    @Test
    void refusesAMarkedGetterItCouldNotCallInsteadOfSubstitutingAConventionalOne() {
        TypedProcessors.Resolving.reset();
        TypedProcessors.Resolving.property("fx.DocRefs.Status", Use.STATE, "fx.Doc/fx.DocStatus/fx.DocTransition");

        Compilation.Result result = resolve(entity("""
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Doc {
                    private DocStatus status = DocStatus.DRAFT;
                    @GraphProperty private DocStatus status() { return status; }
                    public DocStatus getStatus() { return status; }
                    public void setStatus(DocStatus value) { status = value; }
                }
                """));

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError(DiagnosticCode.SG503.name()), result::describe);
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[" + DiagnosticCode.SG503 + "]")
                && error.contains("status")), result::describe);
    }

    @Test
    void refusesTwoIncomparableWritersOfTheSameAlternative() {
        TypedProcessors.Resolving.reset();
        TypedProcessors.Resolving.property("fx.DocRefs.Status", Use.STATE, "fx.Doc/fx.DocStatus/fx.DocTransition");

        Compilation.Result result = resolve(entity("""
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                import java.io.Serializable;
                public final class Doc {
                    @GraphProperty private DocStatus status = DocStatus.DRAFT;
                    public DocStatus status() { return status; }
                    public void setStatus(Serializable value) { status = (DocStatus) value; }
                    public void setStatus(Comparable<DocStatus> value) { status = (DocStatus) value; }
                }
                """));

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError(DiagnosticCode.SG503.name()), result::describe);
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[" + DiagnosticCode.SG503 + "]")
                && error.contains("setStatus")), result::describe);
    }

    @Test
    void refusesAFieldAndAGetterOfTheSamePropertyMarkedTogether() {
        Map<String, String> sources = entity("""
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Doc {
                    @GraphProperty private DocStatus status = DocStatus.DRAFT;
                    @GraphProperty public DocStatus status() { return status; }
                }
                """);

        CodegenException failure = assertThrows(CodegenException.class, () -> Compilation.prepare(sources));

        assertEquals(DiagnosticCode.SG603.name(), failure.code());
        assertTrue(failure.getMessage().contains("status"), failure::getMessage);
    }

    @Test
    void resolvesThePeersOfAnEntityAndTheOwnerOfAPeer() {
        TypedProcessors.Resolving.reset();
        TypedProcessors.Resolving.property("fx.DocRefs.Pages", Use.PEERS, "fx.Doc/fx.DocStatus/fx.DocTransition",
                "fx.Page/fx.PageStatus/fx.PageTransition");
        TypedProcessors.Resolving.property("fx.PageRefs.Doc", Use.OWNER, "fx.Doc/fx.DocStatus/fx.DocTransition",
                "fx.Page/fx.PageStatus/fx.PageTransition");
        Map<String, String> sources = entity("""
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                import java.util.ArrayList;
                import java.util.List;
                public final class Doc {
                    @GraphProperty private final List<Page> pages = new ArrayList<>();
                    public List<Page> pages() { return pages; }
                }
                """);
        sources.put("fx.PageStatus", """
                package fx;
                public enum PageStatus { OPEN, DONE }
                """);
        sources.put("fx.PageTransition", """
                package fx;
                public enum PageTransition { FINISH }
                """);
        sources.put("fx.Page", """
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                import java.util.Optional;
                public final class Page {
                    @GraphProperty private Doc doc;
                    public Optional<Doc> doc() { return Optional.ofNullable(doc); }
                }
                """);

        Compilation.Result result = resolve(sources);

        assertTrue(result.success(), result.describe());
        assertEquals("pages(): java.util.List<fx.Page>", answer(result, "fx.DocRefs.Pages", Use.PEERS));
        assertEquals("doc(): java.util.Optional<fx.Doc>", answer(result, "fx.PageRefs.Doc", Use.OWNER));
    }

    /** What the resolver answered, or an assertion failure carrying every diagnostic of the compilation. */
    private static String answer(Compilation.Result result, String symbol, Use use) {
        return TypedProcessors.Resolving.resolved(symbol, use)
                .orElseThrow(() -> new AssertionError(result.describe()));
    }

    private static Compilation.Result resolve(Map<String, String> sources) {
        return resolve(Compilation.prepare(sources));
    }

    /** Compiles a prepared fixture with the resolving processor instead of the real one. */
    private static Compilation.Result resolve(Compilation.Prepared prepared) {
        return Compilation.compileWithoutLifecycle(prepared,
                List.of("-processor", TypedProcessors.Resolving.class.getName()));
    }

    /** The state and transition enums every fixture needs, and the entity whose properties are under test. */
    private static Map<String, String> entity(String doc) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", """
                package fx;
                public enum DocStatus { DRAFT, PUBLISHED }
                """);
        sources.put("fx.DocTransition", """
                package fx;
                public enum DocTransition { PUBLISH }
                """);
        sources.put("fx.Doc", doc);
        return sources;
    }

    /** One source the generator wrote before javac ran, read from the bootstrap output of the fixture. */
    private static String exported(Compilation.Prepared prepared, String qualifiedName) {
        try {
            return Files.readString(prepared.request().sourcesOutput()
                    .resolve(qualifiedName.replace('.', File.separatorChar) + ".java"), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + token.length())) {
            count++;
        }
        return count;
    }
}
