package consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Contract of the artifacts a consumer actually downloads. Everything else in this module is checked
 * against the classes Gradle compiled, which says nothing about what the published jar and POM contain:
 * so this test reads the files from a local Maven repository, compiles a consumer against those jars
 * alone and runs it in a separate JVM whose classpath holds nothing else — no JUnit, no project classes
 * and no test runtime. The repository and the release are passed as the {@code artifactRepository} and
 * {@code artifactVersion} system properties by the Gradle task that publishes them first.
 */
class PublishedArtifactSmokeTest {

    /** The full chain a consumer is promised, written as a consumer writes it and run from the jars. */
    private static final String CONSUMER_SOURCE = """
            import static io.github.graphmaquina.test.GraphAssertions.assertThat;
            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.GraphDefinition;
            import io.github.graphmaquina.api.MachineKey;
            import io.github.graphmaquina.api.StateMachine;
            import io.github.graphmaquina.dsl.StateGraph;
            import io.github.graphmaquina.runtime.StateMachines;
            import io.github.graphmaquina.test.GraphSnapshots;
            import io.github.graphmaquina.test.StateSnapshot;
            import io.github.graphmaquina.test.snapshot.GoldenOptions;
            import io.github.graphmaquina.test.snapshot.GraphDiff;
            import io.github.graphmaquina.test.snapshot.GraphSnapshot;
            import java.net.URL;
            import java.net.URLClassLoader;
            import java.nio.file.Path;
            import java.util.List;
            import java.util.Optional;

            public class PublishedConsumer {
                enum State { NEW, DONE }
                static final class Entity { State state = State.NEW; }
                private static final MachineKey<Entity, State> KEY = MachineKey.of(Entity.class, State.class);

                private static GraphDefinition<Entity, State> graph(String action) {
                    return StateGraph.define(KEY).initial(State.NEW).terminal(State.DONE)
                            .from(State.NEW).to(State.DONE).action(action, context -> ActionOutcome.ok())
                            .stay().forbidOthers("finish first")
                            .from(State.DONE).stay().forbidOthers("done").build();
                }

                public static void main(String[] args) throws Exception {
                    GraphDefinition<Entity, State> definition = graph("finish");
                    assertThat(definition).hasInitial(State.NEW).hasTerminalsExactly(State.DONE)
                            .edge(State.NEW, State.DONE).isAction().hasActionNamed("finish").hasNoGuards();
                    GraphSnapshot snapshot = GraphSnapshot.capture(definition);
                    GraphDiff diff = GraphDiff.compare(snapshot, GraphSnapshot.capture(graph("complete")));
                    if (!List.of(new GraphDiff.Change("/cells/NEW/DONE/action/name",
                            Optional.of("finish"), Optional.of("complete"))).equals(diff.changes())) {
                        throw new AssertionError("the action change must be detected structurally");
                    }
                    Path root = Path.of(args[0]);
                    try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
                        GraphSnapshots.assertMatches(definition, "consumer.graph",
                                GoldenOptions.updating(loader, root, path -> { }));
                        GraphSnapshots.assertMatches(definition, "consumer.graph", GoldenOptions.readOnly(loader));
                    }
                    Entity entity = new Entity();
                    StateSnapshot before = StateSnapshot.builder().track(KEY, entity, value -> value.state).capture();
                    StateMachine<Entity, State> machine = StateMachines.from(definition)
                            .state(value -> value.state, (value, state) -> value.state = state)
                            .asyncExecutor(Runnable::run).build();
                    assertThat(machine.transitionTo(entity, State.DONE)).isApplied().movedTo(State.DONE)
                            .hasEntityState(value -> value.state, State.DONE).records().hasSize(1);
                    if (before.changes().size() != 1) {
                        throw new AssertionError("the actual state write must be observed");
                    }
                    System.out.println("PUBLIC_API_OK");
                }
            }
            """;

    private static final List<String> SUFFIXES = List.of(".jar", ".pom", "-sources.jar", "-javadoc.jar");

    @Test
    void everyPublishedFileExistsAndCarriesContent() throws IOException {
        for (String module : List.of("graphmaquina-core", "graphmaquina-test")) {
            for (String suffix : SUFFIXES) {
                Path artifact = artifact(module, suffix);
                assertTrue(Files.isRegularFile(artifact), "missing published artifact: " + artifact);
                assertTrue(Files.size(artifact) > 0L, "empty published artifact: " + artifact);
            }
        }
        assertTrue(entries("graphmaquina-test", ".jar").contains("io/github/graphmaquina/test/GraphAssertions.class"),
                "the binary jar must carry the compiled facade");
        assertTrue(entries("graphmaquina-test", "-sources.jar").contains("io/github/graphmaquina/test/GraphAssertions.java"),
                "the sources jar must carry the sources, not just a manifest");
        assertTrue(entries("graphmaquina-test", "-javadoc.jar").contains("io/github/graphmaquina/test/GraphAssertions.html"),
                "the javadoc jar must carry the rendered documentation, not just a manifest");
    }

    @Test
    void everyJarDeclaresItsAutomaticModuleName() throws IOException {
        assertEquals("io.github.graphmaquina.core", automaticModuleName("graphmaquina-core"));
        assertEquals("io.github.graphmaquina.test", automaticModuleName("graphmaquina-test"));
    }

    @Test
    void thePomPullsCoreAloneAtTheSameVersion() throws IOException, ParserConfigurationException, SAXException {
        assertEquals(List.of("io.github.graphmaquina:graphmaquina-core:" + version() + ":compile"),
                dependenciesOf(artifact("graphmaquina-test", ".pom")),
                "a consumer must inherit core and nothing else: no JUnit, AssertJ, Gson, codegen or processor");
    }

    @Test
    void aConsumerCompilesAndRunsAgainstThePublishedJarsAlone(@TempDir Path workDir)
            throws IOException, InterruptedException {
        String jars = artifact("graphmaquina-core", ".jar") + File.pathSeparator + artifact("graphmaquina-test", ".jar");
        runConsumer(workDir, jars);
    }

    @Test
    void semanticAssertionsRunWithoutAnyGraphicExporter(@TempDir Path workDir)
            throws IOException, InterruptedException {
        Path core = workDir.resolve("core-without-exporters.jar");
        try (JarFile original = new JarFile(artifact("graphmaquina-core", ".jar").toFile());
                JarOutputStream filtered = new JarOutputStream(Files.newOutputStream(core))) {
            Enumeration<JarEntry> entries = original.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith("io/github/graphmaquina/export/")) {
                    continue;
                }
                filtered.putNextEntry(new JarEntry(entry.getName()));
                try (InputStream content = original.getInputStream(entry)) {
                    content.transferTo(filtered);
                }
                filtered.closeEntry();
            }
        }
        runConsumer(workDir, core + File.pathSeparator + artifact("graphmaquina-test", ".jar"));
    }

    private static void runConsumer(Path workDir, String jars) throws IOException, InterruptedException {
        Path classes = Files.createDirectories(workDir.resolve("classes"));
        List<String> diagnostics = compile(workDir, jars, classes);
        assertEquals(List.of(), diagnostics, "the consumer source must compile against the published jars");

        Path transcript = workDir.resolve("consumer-output.txt");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-classpath", classes + File.pathSeparator + jars, "PublishedConsumer", workDir.toString())
                .redirectErrorStream(true)
                .redirectOutput(transcript.toFile())
                .start();
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            fail("the consumer process did not terminate");
        }
        String output = Files.readString(transcript, UTF_8);
        assertEquals(0, process.exitValue(), "the consumer process failed: " + output);
        assertTrue(output.contains("PUBLIC_API_OK"), "the consumer did not reach its last line: " + output);
    }

    /** Compiles the consumer with the published jars as its entire classpath; returns what javac reported. */
    private static List<String> compile(Path workDir, String jars, Path classes) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "this smoke test needs a JDK compiler, not a JRE");
        Path source = Files.writeString(workDir.resolve("PublishedConsumer.java"), CONSUMER_SOURCE);
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

    /** Each dependency of the POM as {@code group:artifact:version:scope}, in declaration order. */
    private static List<String> dependenciesOf(Path pom) throws IOException, ParserConfigurationException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        NodeList declared = builder.parse(pom.toFile()).getDocumentElement().getElementsByTagName("dependency");
        List<String> dependencies = new ArrayList<>();
        for (int index = 0; index < declared.getLength(); index++) {
            Element dependency = (Element) declared.item(index);
            dependencies.add(text(dependency, "groupId") + ":" + text(dependency, "artifactId") + ":"
                    + text(dependency, "version") + ":" + text(dependency, "scope"));
        }
        return dependencies;
    }

    private static String text(Element dependency, String field) {
        NodeList matches = dependency.getElementsByTagName(field);
        Node first = matches.getLength() == 0 ? null : matches.item(0);
        return first == null ? "" : first.getTextContent().trim();
    }

    private static String automaticModuleName(String module) throws IOException {
        try (JarFile jar = new JarFile(artifact(module, ".jar").toFile())) {
            return jar.getManifest().getMainAttributes().getValue("Automatic-Module-Name");
        }
    }

    private static List<String> entries(String module, String suffix) throws IOException {
        try (JarFile jar = new JarFile(artifact(module, suffix).toFile())) {
            List<String> names = new ArrayList<>();
            Enumeration<JarEntry> found = jar.entries();
            while (found.hasMoreElements()) {
                names.add(found.nextElement().getName());
            }
            return names;
        }
    }

    private static Path artifact(String module, String suffix) {
        String release = version();
        return repository().resolve("io/github/graphmaquina/" + module + "/" + release + "/"
                + module + "-" + release + suffix);
    }

    private static Path repository() {
        return Path.of(property("artifactRepository"));
    }

    private static String version() {
        return property("artifactVersion");
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        assertNotNull(value, "this smoke test reads published files and needs the " + name
                + " system property; run it through :graphmaquina-test:publishedArtifactTest");
        return value;
    }
}
