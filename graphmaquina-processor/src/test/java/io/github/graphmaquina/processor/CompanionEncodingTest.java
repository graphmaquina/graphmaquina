package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.TransitionSpec;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A companion keeps every character of the names and the texts it writes, whatever encoding the compilation reads
 * its sources in. javac's {@code Filer} writes a generated source in that encoding and replaces a character the
 * encoding cannot represent with {@code ?}, and no encoding represents an unpaired surrogate, so the processor
 * writes every character outside printable ASCII as a Unicode escape, which javac reads back in any encoding. Every
 * fixture spells such a character as a Unicode escape too, which an ISO-8859-1 source allows.
 */
class CompanionEncodingTest {

    private static final Charset LATIN_1 = StandardCharsets.ISO_8859_1;

    /** A policy text beyond Latin-1 reaches the graph unchanged from an ISO-8859-1 build. */
    @Test
    void keepsATextTheSourceEncodingCannotRepresent() throws Exception {
        Compilation.Result result = Compilation.compilePrepared(Compilation.prepare(
                withOtherwise("\"price in \\u20ac\""), LATIN_1));

        assertTrue(result.success(), result.describe());
        assertEquals("price in \u20ac", otherwise(result), result.generated("fx.DocsRuntime"));
    }

    /**
     * A transition constant beyond Latin-1 is written into the companion as the identifier it is, so the
     * companion of an ISO-8859-1 build compiles and its graph declares the edge under that constant.
     */
    @Test
    void keepsAnIdentifierTheSourceEncodingCannotRepresent() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        for (Map.Entry<String, String> source : TypedFixtures.minimal().entrySet()) {
            sources.put(source.getKey(), source.getValue().replace("PUBLISH", "PUBLISH_\\u03a9"));
        }

        Compilation.Result result = Compilation.compilePrepared(Compilation.prepare(sources, LATIN_1));

        assertTrue(result.success(), result.describe());
        List<String> identifiers = new ArrayList<>();
        for (TransitionSpec<Object, ?, ?> transition : graph(result).transitions()) {
            identifiers.add(transition.transition().name());
        }
        assertEquals(List.of("PUBLISH_\u03a9"), identifiers);
    }

    /** No encoding represents an unpaired surrogate, not even UTF-8, so it is escaped in every build. */
    @Test
    void keepsAnUnpairedSurrogateInAUtf8Build() throws Exception {
        Compilation.Result result = Compilation.compilePrepared(Compilation.prepare(
                withOtherwise("\"half \\uD800 pair\""), StandardCharsets.UTF_8));

        assertTrue(result.success(), result.describe());
        assertEquals("half \uD800 pair", otherwise(result), result.generated("fx.DocsRuntime"));
    }

    /**
     * A backslash starts a Unicode escape only after an even run of backslashes. The companion doubles every
     * backslash of a text, so the escape of a character that follows one still reads as that character, and the
     * text of an escape that follows one still reads as text: a backslash before the euro sign, and a backslash
     * before the five characters {@code u0041}, come back as written.
     */
    @Test
    void keepsTheBackslashesBeforeAnEscapeAndTheTextOfAnEscape() throws Exception {
        Compilation.Result result = Compilation.compilePrepared(Compilation.prepare(
                withOtherwise("\"\\\\\\u20ac and \\\\u0041\""), LATIN_1));

        assertTrue(result.success(), result.describe());
        assertEquals("\\" + "\u20ac" + " and " + "\\" + "u0041", otherwise(result),
                result.generated("fx.DocsRuntime"));
    }

    /** The smallest machine, its draft's {@code otherwise} replaced by {@code literal}, written verbatim. */
    private static Map<String, String> withOtherwise(String literal) {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx.Docs", sources.get("fx.Docs")
                .replace("otherwise = \"publish before finishing\"", "otherwise = " + literal));
        return sources;
    }

    /** The {@code otherwise} of the draft's policy in the graph the companion builds. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String otherwise(Compilation.Result result) throws Exception {
        Enum draft = (Enum) result.load("fx.DocStatus").getEnumConstants()[0];
        return ((GraphDefinition) graph(result)).policy(draft).otherwise();
    }

    /** The graph the companion builds, over a fresh instance of the action host. */
    @SuppressWarnings("unchecked")
    private static GraphDefinition<Object, ?, ?> graph(Compilation.Result result) throws Exception {
        Class<?> actions = result.load("fx.DocActions");
        return (GraphDefinition<Object, ?, ?>) result.load("fx.DocsRuntime").getMethod("graph", actions)
                .invoke(null, actions.getConstructor().newInstance());
    }
}
