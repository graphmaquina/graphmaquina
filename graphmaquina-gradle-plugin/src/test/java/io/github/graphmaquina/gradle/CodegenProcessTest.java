package io.github.graphmaquina.gradle;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin writes the generator's request with its own encoder rather than with the generator's codec, so these
 * tests pin what that encoder produces: the members of the request in the order the codec lists them, the release
 * as a number and the encoding as a string after them when the build knows them, and the escaping of a request
 * whose paths and identity carry spaces, backslashes, quotes, control characters and non-ASCII text, which must
 * come back out of a JSON reader exactly as it went in, and out of the generator's own codec from the UTF-8 file
 * {@code write} produces.
 */
class CodegenProcessTest {

    /** A path that carries a space, a non-ASCII character, quotes and a backslash. */
    private static final String AWKWARD = "/tmp/fixture project ñ/\"quoted\"\\segment";

    /**
     * A path that carries a space and a non-ASCII character, and nothing a file system refuses in a path, so the
     * generator's codec can turn it into one on every operating system.
     */
    private static final String SPACED = "/tmp/fixture project ñ";

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

    /**
     * The release and the encoding of the compilation follow the eight members when the build knows them: the
     * release as a number and the encoding as the name of its charset.
     */
    @Test
    void encodesTheReleaseAndTheEncodingOfTheCompilationWhenTheyAreKnown() {
        CodegenProcess.Request known = new CodegenProcess.Request(AWKWARD, List.of(AWKWARD), List.of(), AWKWARD,
                AWKWARD, AWKWARD, AWKWARD, "fx:main", 25, "ISO-8859-1");

        JsonObject decoded = JsonParser.parseString(CodegenProcess.encode(known)).getAsJsonObject();

        assertEquals(List.of("projectDirectory", "sources", "classpath", "sourcesOutput",
                "processorSourcesOutput", "classesOutput", "workDirectory", "compilationId", "release", "encoding"),
                List.copyOf(decoded.keySet()));
        assertTrue(decoded.getAsJsonPrimitive("release").isNumber(), decoded::toString);
        assertEquals(25, decoded.get("release").getAsInt());
        assertTrue(decoded.getAsJsonPrimitive("encoding").isString(), decoded::toString);
        assertEquals("ISO-8859-1", decoded.get("encoding").getAsString());
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

    /**
     * The file {@code write} produces is read back by the generator itself: {@code ManifestCodec.readRequest} of the
     * {@code graphmaquina-codegen} jar this build published, which reads strict JSON in UTF-8 and refuses a missing
     * member, a member of another JSON type and an encoding the JVM does not know. Every member it reads is the one
     * written, the identity with its quotes, backslash, tab and non-ASCII text included.
     */
    @Test
    void writesAStrictUtf8DocumentTheGeneratorCanRead() throws IOException {
        Path file = tempDirectory.resolve("request.json");
        String identity = "gröup \"x\":näme\\y:main\twith a tab";

        CodegenProcess.write(file, new CodegenProcess.Request(SPACED, List.of(SPACED + "/src/main/java"),
                List.of(SPACED + "/lib/dependency.jar"), SPACED + "/build/ünïcode", SPACED + "/build/annotations",
                SPACED + "/build/classes", SPACED + "/build/work", identity, 25, "ISO-8859-1"));

        Map<String, Object> read = readWithTheGeneratorCodec(file);
        assertEquals(Path.of(SPACED), read.get("projectDirectory"), read::toString);
        assertEquals(List.of(Path.of(SPACED + "/src/main/java")), read.get("sources"), read::toString);
        assertEquals(List.of(Path.of(SPACED + "/lib/dependency.jar")), read.get("classpath"), read::toString);
        assertEquals(Path.of(SPACED + "/build/ünïcode"), read.get("sourcesOutput"), read::toString);
        assertEquals(Path.of(SPACED + "/build/annotations"), read.get("processorSourcesOutput"), read::toString);
        assertEquals(Path.of(SPACED + "/build/classes"), read.get("classesOutput"), read::toString);
        assertEquals(Path.of(SPACED + "/build/work"), read.get("workDirectory"), read::toString);
        assertEquals(identity, read.get("compilationId"), read::toString);
        assertEquals(25, read.get("release"), read::toString);
        assertEquals(Charset.forName("ISO-8859-1"), read.get("encoding"), read::toString);
    }

    /**
     * The request {@code file} holds, as the generator's own codec reads it: every component of the
     * {@code GenerationRequest} that {@code ManifestCodec.readRequest} returns, by name. The codec is loaded from the
     * {@code graphmaquina-codegen} and {@code graphmaquina-core} jars of the fixture repository, at the version this
     * build resolved, over this class's own loader, which provides the Gson the codec reads with.
     */
    private static Map<String, Object> readWithTheGeneratorCodec(Path file) throws IOException {
        List<URL> jars = generatorJars();
        try (URLClassLoader generator = new URLClassLoader(jars.toArray(new URL[0]),
                CodegenProcessTest.class.getClassLoader())) {
            Object request = generator.loadClass("io.github.graphmaquina.codegen.manifest.ManifestCodec")
                    .getMethod("readRequest", Path.class).invoke(null, file);
            Map<String, Object> components = new LinkedHashMap<>();
            for (RecordComponent component : request.getClass().getRecordComponents()) {
                components.put(component.getName(), component.getAccessor().invoke(request));
            }
            return components;
        } catch (InvocationTargetException refused) {
            throw new AssertionError("the generator's codec refused " + file + ": " + refused.getCause(),
                    refused.getCause());
        } catch (ReflectiveOperationException absent) {
            throw new IllegalStateException("the generator's codec cannot be called", absent);
        }
    }

    /**
     * The {@code graphmaquina-codegen} and {@code graphmaquina-core} jars of the fixture repository, at the version
     * this build resolved.
     */
    private static List<URL> generatorJars() throws IOException {
        String version = System.getProperty("graphmaquina.version");
        Path artifacts = Path.of(System.getProperty("graphmaquina.fixtureRepository"), "io", "github", "graphmaquina");
        List<URL> jars = new ArrayList<>();
        for (String module : List.of("graphmaquina-codegen", "graphmaquina-core")) {
            Path jar = artifacts.resolve(module).resolve(version).resolve(module + "-" + version + ".jar");
            if (!Files.isRegularFile(jar)) {
                throw new IllegalStateException(jar + " does not exist; run ./gradlew :" + module
                        + ":publishMavenPublicationToAdapterTestsRepository first");
            }
            jars.add(jar.toUri().toURL());
        }
        return jars;
    }

    private static CodegenProcess.Request request() {
        return new CodegenProcess.Request(AWKWARD,
                List.of(AWKWARD + "/src/main/java", "tab\there\nand a newline"),
                List.of(),
                AWKWARD + "/ünïcode", AWKWARD, AWKWARD, AWKWARD,
                "gröup \"x\":näme\\y:main");
    }
}
