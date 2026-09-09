package io.stategraph.gradle;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The plugin writes the generator's request with its own encoder rather than with the generator's codec, so the
 * escaping that encoder applies is what these tests pin: a request whose paths and identity carry spaces,
 * backslashes, quotes, control characters and non-ASCII text must come back out of a strict JSON reader exactly
 * as it went in.
 */
class CodegenProcessTest {

    /** A path with a space, a quote, a backslash and a non-ASCII character in every segment. */
    private static final String AWKWARD = "/tmp/fixture project ñ/\"quoted\"\\segment";

    @TempDir
    Path tempDirectory;

    @Test
    void encodesEveryMemberOfTheRequestInTheDocumentedOrder() {
        String json = CodegenProcess.encode(request());
        JsonObject decoded = JsonParser.parseString(json).getAsJsonObject();

        assertEquals(List.of("projectDirectory", "sources", "classpath", "sourcesOutput",
                "processorSourcesOutput", "classesOutput", "workDirectory", "compilationId"),
                List.copyOf(decoded.keySet()));
    }

    @Test
    void roundTripsSpacesQuotesBackslashesAndNonAsciiText() {
        JsonObject decoded = JsonParser.parseString(CodegenProcess.encode(request())).getAsJsonObject();

        assertEquals(AWKWARD, decoded.get("projectDirectory").getAsString());
        assertEquals(AWKWARD + "/ünïcode", decoded.get("sourcesOutput").getAsString());
        assertEquals("gröup \"x\":näme\\y:main", decoded.get("compilationId").getAsString());
        JsonArray sources = decoded.getAsJsonArray("sources");
        assertEquals(AWKWARD + "/src/main/java", sources.get(0).getAsString());
        assertEquals("tab\there\nand a newline", sources.get(1).getAsString());
    }

    @Test
    void writesAStrictUtf8DocumentTheGeneratorCanRead() throws IOException {
        Path file = tempDirectory.resolve("request.json");

        CodegenProcess.write(file, request());

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                JsonReader strict = new JsonReader(reader)) {
            strict.setStrictness(Strictness.STRICT);
            JsonObject decoded = JsonParser.parseReader(strict).getAsJsonObject();
            assertEquals(AWKWARD, decoded.get("classesOutput").getAsString());
            assertEquals(List.of(), List.copyOf(decoded.getAsJsonArray("classpath").asList()));
        }
    }

    private static CodegenProcess.Request request() {
        return new CodegenProcess.Request(AWKWARD,
                List.of(AWKWARD + "/src/main/java", "tab\there\nand a newline"),
                List.of(),
                AWKWARD + "/ünïcode", AWKWARD, AWKWARD, AWKWARD,
                "gröup \"x\":näme\\y:main");
    }
}
