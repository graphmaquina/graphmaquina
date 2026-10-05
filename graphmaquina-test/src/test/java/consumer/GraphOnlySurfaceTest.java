package consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The retired matrix contract as a consumer can still reach it, which is to say not at all. The tests of this
 * module's {@code test} task run against the classes Gradle compiled, and a type that is absent from that
 * classpath may still be shipped: a jar carries compiled classes, a sources jar carries the sources and a
 * javadoc jar carries the rendered pages, and each of the three is part of the product. So this test reads the
 * published files from a local Maven repository, compiles a consumer against those jars alone, runs it in a
 * separate JVM whose classpath holds nothing else, and separately walks the entries of every published jar of
 * every module.
 *
 * <p>{@code Class.forName} alone would prove too little, and a scan of entry names alone would prove too
 * little; the two together cover the compiled, the source and the documented form of every retired name. The
 * repository and the release arrive as the {@code artifactRepository} and {@code artifactVersion} system
 * properties, from the Gradle task that publishes them first.
 */
class GraphOnlySurfaceTest {

    /** Every module a release publishes; each of them ships the three jars below. */
    private static final List<String> MODULES = List.of("graphmaquina-core", "graphmaquina-codegen",
            "graphmaquina-processor", "graphmaquina-test", "graphmaquina-maven-plugin", "graphmaquina-gradle-plugin");

    /** The binary, the sources and the documentation: a retired type in any one of them is still shipped. */
    private static final List<String> JARS = List.of(".jar", "-sources.jar", "-javadoc.jar");

    /**
     * The qualified names of the retired contract: the total edge model, the row builder and the cell and graph
     * outlines of the matrix DSL, and the whole string annotation vocabulary, its repeatable containers included.
     * Kept in one list because the consumer below is written from it and the jar walk looks for each of them as
     * a file, so a name added here is covered by both checks.
     */
    private static final List<String> RETIRED = List.of(
            "io.github.graphmaquina.api.EdgeSpec",
            "io.github.graphmaquina.api.EdgeKind",
            "io.github.graphmaquina.dsl.RowBuilder",
            "io.github.graphmaquina.dsl.CellOutline",
            "io.github.graphmaquina.dsl.GraphOutline",
            "io.github.graphmaquina.annotation.Row",
            "io.github.graphmaquina.annotation.Cell",
            "io.github.graphmaquina.annotation.Action",
            "io.github.graphmaquina.annotation.Move",
            "io.github.graphmaquina.annotation.Forbid",
            "io.github.graphmaquina.annotation.StateGraph",
            "io.github.graphmaquina.annotation.Watch",
            "io.github.graphmaquina.annotation.When",
            "io.github.graphmaquina.annotation.Watches",
            "io.github.graphmaquina.annotation.Rows",
            "io.github.graphmaquina.annotation.Cells",
            "io.github.graphmaquina.annotation.Forbids");

    /**
     * The simple names the generator once nested inside a machine's own vocabulary. A published artifact
     * declares a nested type of none of them, whatever outer type it belongs to.
     */
    private static final List<String> RETIRED_NESTED = List.of("Row", "Cell", "Forbid", "Action", "Move");

    /**
     * What a consumer sees of the retired contract, written as a consumer writes it and run from the jars: the
     * classloader resolves no retired name, and the two surviving entry points of the contract declare no
     * method of it. It carries its own two assertions because the jars are its entire classpath — there is no
     * JUnit in the process that runs it, which is the point.
     */
    private static final String CONSUMER_SOURCE = """
            import io.github.graphmaquina.api.GraphDefinition;
            import io.github.graphmaquina.api.StateMachine;
            import java.util.Arrays;
            import java.util.List;

            public class GraphOnlyConsumer {

                @FunctionalInterface
                interface Body { void run() throws Throwable; }

                public static void main(String[] args) {
                    for (String retired : List.of(
                        %s)) {
                        assertThrows(ClassNotFoundException.class, () -> Class.forName(retired));
                    }
                    assertTrue(Arrays.stream(StateMachine.class.getMethods()).noneMatch(m -> m.getName().equals("transitionTo")));
                    assertTrue(Arrays.stream(GraphDefinition.class.getMethods()).noneMatch(m -> m.getName().equals("render")));
                    System.out.println("GRAPH_ONLY_SURFACE_OK");
                }

                private static void assertThrows(Class<? extends Throwable> expected, Body body) {
                    try {
                        body.run();
                    } catch (Throwable thrown) {
                        if (expected.isInstance(thrown)) {
                            return;
                        }
                        throw new AssertionError("expected " + expected.getName() + " but caught " + thrown, thrown);
                    }
                    throw new AssertionError("expected " + expected.getName() + ", but nothing was thrown");
                }

                private static void assertTrue(boolean condition) {
                    if (!condition) {
                        throw new AssertionError("the published surface still declares a retired method");
                    }
                }
            }
            """.formatted(RETIRED.stream().map((String retired) -> '"' + retired + '"')
                    .collect(Collectors.joining(",\n" + " ".repeat(12))));

    /**
     * The negative load, run where it means something: the whole published set is the classpath, so a retired
     * type surviving in any module would resolve, and nothing else is there to hide behind.
     */
    @Test
    void noRetiredTypeResolvesForAConsumerOfThePublishedJars(@TempDir Path workDir)
            throws IOException, InterruptedException {
        String jars = MODULES.stream().map((String module) -> artifact(module, ".jar").toString())
                .collect(Collectors.joining(File.pathSeparator));
        Path classes = Files.createDirectories(workDir.resolve("classes"));
        assertEquals(List.of(), compile(workDir, jars, classes),
                "the consumer source must compile against the published jars");

        Path transcript = workDir.resolve("consumer-output.txt");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-classpath", classes + File.pathSeparator + jars, "GraphOnlyConsumer")
                .redirectErrorStream(true)
                .redirectOutput(transcript.toFile())
                .start();
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            fail("the consumer process did not terminate");
        }
        String output = Files.readString(transcript, UTF_8);
        assertEquals(0, process.exitValue(), "the consumer process failed: " + output);
        assertTrue(output.contains("GRAPH_ONLY_SURFACE_OK"), "the consumer did not reach its last line: " + output);
    }

    /**
     * What the classloader cannot answer for: a sources jar and a javadoc jar ship a retired type as text
     * rather than as a class, and an internal or nested declaration never has to be resolvable to be shipped.
     */
    @Test
    void noPublishedJarCarriesARetiredDeclaration() throws IOException {
        List<String> shipped = new ArrayList<>();
        for (String module : MODULES) {
            for (String suffix : JARS) {
                Path artifact = artifact(module, suffix);
                assertTrue(Files.isRegularFile(artifact), "missing published artifact: " + artifact);
                for (String entry : entries(artifact)) {
                    if (isRetired(entry)) {
                        shipped.add(module + suffix + "!" + entry);
                    }
                }
            }
        }
        assertEquals(List.of(), shipped, "the published artifacts still carry the retired contract");
    }

    /**
     * Whether {@code entry} declares a retired type: its own file, one of its members, or a retired nesting.
     *
     * <p>The jars spell a nesting differently — a class file joins the outer and inner names with {@code $} and a
     * javadoc page joins them with {@code .}, while a source file is named after its top-level type alone — so
     * the extension comes off first and both separators are then read the same way. Only the segments after the
     * first are nestings; a bare outer name is what the qualified list above already answers for.
     */
    private static boolean isRetired(String entry) {
        for (String retired : RETIRED) {
            String path = retired.replace('.', '/');
            if (entry.equals(path + ".class") || entry.equals(path + ".java") || entry.equals(path + ".html")
                    || entry.startsWith(path + "$") || entry.startsWith(path + ".")) {
                return true;
            }
        }
        String name = entry.substring(entry.lastIndexOf('/') + 1);
        List<String> nested = List.of(name.replaceFirst("\\.(class|java|html)$", "").split("[$.]", -1));
        for (int index = 1; index < nested.size(); index++) {
            if (RETIRED_NESTED.contains(nested.get(index))) {
                return true;
            }
        }
        return false;
    }

    /** Compiles the consumer with the published jars as its entire classpath; returns what javac reported. */
    private static List<String> compile(Path workDir, String jars, Path classes) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "this surface test needs a JDK compiler, not a JRE");
        Path source = Files.writeString(workDir.resolve("GraphOnlyConsumer.java"), CONSUMER_SOURCE);
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(collector, null, UTF_8)) {
            Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromPaths(List.of(source));
            List<String> options = List.of("--release", "21", "-proc:none", "-Xlint:all,-serial", "-Werror",
                    "-classpath", jars, "-d", classes.toString());
            boolean succeeded = compiler.getTask(null, manager, collector, options, null, units).call();
            List<String> reported = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : collector.getDiagnostics()) {
                reported.add(diagnostic.getKind() + " at line " + diagnostic.getLineNumber() + ": "
                        + diagnostic.getMessage(null));
            }
            if (reported.isEmpty() && !succeeded) {
                reported.add("javac produced no classes and no diagnostic");
            }
            return reported;
        }
    }

    private static List<String> entries(Path artifact) throws IOException {
        try (JarFile jar = new JarFile(artifact.toFile())) {
            List<String> names = new ArrayList<>();
            Enumeration<JarEntry> found = jar.entries();
            while (found.hasMoreElements()) {
                names.add(found.nextElement().getName());
            }
            return names;
        }
    }

    private static Path artifact(String module, String suffix) {
        String release = property("artifactVersion");
        return Path.of(property("artifactRepository")).resolve("io/github/graphmaquina/" + module + "/" + release
                + "/" + module + "-" + release + suffix);
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        assertNotNull(value, "this surface test reads published files and needs the " + name
                + " system property; run it through :graphmaquina-test:publishedArtifactTest");
        return value;
    }
}
