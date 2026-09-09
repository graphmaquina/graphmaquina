package io.stategraph.maven;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Test-only helper that materializes a minimal Maven project against the published StateGraph artifacts and
 * builds it with a real {@code mvn} process. This is test-support code, not a production API.
 *
 * <p>Nothing here instantiates a Mojo: every assertion is made about what the packaged plugin did when Maven
 * looked it up in a file repository, resolved its descriptor and ran one of its goals. The build never reads or
 * writes the developer's {@code ~/.m2}: it runs with an explicit settings file naming the fixture repository and
 * with {@code -Dmaven.repo.local} pointing at one local repository shared by every test of this module.</p>
 *
 * <p>The project directory carries a space and a non-ASCII character in its name, so every build also exercises
 * the request encoding and the process invocation over a path that is not plain ASCII.</p>
 */
public final class MavenFixture {

    /** The published index a compilation leaves beside its classes. */
    private static final String EXPORTS = "META-INF/stategraph/exports.json";

    /** The package the generator emits the carrier of one compilation into. */
    private static final String CARRIER_PACKAGE = "io/stategraph/generated/bootstrap";

    /** The annotation that carrier holds, which is what makes a generated source the carrier. */
    private static final String CARRIER_MARKER = "@GraphCompilation(";

    private final Path directory;
    private final Path settings;

    private MavenFixture(Path directory, Path settings) {
        this.directory = directory;
        this.settings = settings;
    }

    /**
     * Writes the consumer project of {@code src/test/resources/it/basic/pom.xml} with the shared adapter domain
     * — {@code fx.Status}, {@code fx.Order}, {@code fx.Actions}, {@code fx.Orders} and the unmarked
     * {@code fx.Unrelated} — and the settings file that resolves StateGraph from {@code repository}.
     *
     * @param directory the temporary directory to materialize the project under
     * @param repository the file Maven repository the StateGraph artifacts were published to
     * @return a fixture bound to the written project
     * @throws IOException if the project cannot be written
     */
    public static MavenFixture basic(Path directory, Path repository) throws IOException {
        if (!Files.isDirectory(repository)) {
            throw new IllegalStateException("the fixture repository " + repository + " does not exist; run"
                    + " ./gradlew :stategraph-maven-plugin:publishMavenPublicationToAdapterTestsRepository"
                    + " :stategraph-core:publishMavenPublicationToAdapterTestsRepository"
                    + " :stategraph-codegen:publishMavenPublicationToAdapterTestsRepository"
                    + " :stategraph-processor:publishMavenPublicationToAdapterTestsRepository first");
        }
        Path project = Files.createDirectories(directory.resolve("fixture project ñ"));
        Files.writeString(project.resolve("pom.xml"), template(), StandardCharsets.UTF_8);
        Path settings = project.resolve("settings.xml");
        Files.writeString(settings, settings(repository), StandardCharsets.UTF_8);
        MavenFixture fixture = new MavenFixture(project, settings);
        fixture.writeSources(domainSources());
        return fixture;
    }

    /** The POM of the consumer project, read from the test resources rather than duplicated here. */
    private static String template() throws IOException {
        try (InputStream stream = MavenFixture.class.getResourceAsStream("/it/basic/pom.xml")) {
            if (stream == null) {
                throw new IllegalStateException("the fixture POM /it/basic/pom.xml is not on the test classpath");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The settings file of every fixture build: one active profile whose dependency and plugin repositories are
     * the file repository this build published to, with snapshots enabled and always re-read, followed by Maven
     * Central for everything else the consumer project needs.
     */
    private static String settings(Path repository) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 \
                https://maven.apache.org/xsd/settings-1.0.0.xsd">
                  <profiles>
                    <profile>
                      <id>stategraph-fixture</id>
                      <repositories>
                        <repository>
                          <id>stategraph-adapter-tests</id>
                          <url>%1$s</url>
                          <releases><enabled>true</enabled></releases>
                          <snapshots><enabled>true</enabled><updatePolicy>always</updatePolicy></snapshots>
                        </repository>
                        <repository>
                          <id>central</id>
                          <url>https://repo.maven.apache.org/maven2</url>
                          <releases><enabled>true</enabled></releases>
                          <snapshots><enabled>false</enabled></snapshots>
                        </repository>
                      </repositories>
                      <pluginRepositories>
                        <pluginRepository>
                          <id>stategraph-adapter-tests</id>
                          <url>%1$s</url>
                          <releases><enabled>true</enabled></releases>
                          <snapshots><enabled>true</enabled><updatePolicy>always</updatePolicy></snapshots>
                        </pluginRepository>
                        <pluginRepository>
                          <id>central</id>
                          <url>https://repo.maven.apache.org/maven2</url>
                          <releases><enabled>true</enabled></releases>
                          <snapshots><enabled>false</enabled></snapshots>
                        </pluginRepository>
                      </pluginRepositories>
                    </profile>
                  </profiles>
                  <activeProfiles>
                    <activeProfile>stategraph-fixture</activeProfile>
                  </activeProfiles>
                </settings>
                """.formatted(repository.toUri().toString());
    }

    /**
     * The shared adapter domain, one entry per compilation unit, keyed by qualified name, so a test can vary one
     * declaration and rewrite the whole source tree with it.
     *
     * @return a mutable copy of the domain
     */
    public static Map<String, String> domainSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.Status", """
                package fx;

                /** The states an order moves through. */
                public enum Status { NEW, DONE }
                """);
        sources.put("fx.Order", """
                package fx;

                import io.stategraph.annotation.GraphProperty;
                import lombok.Getter;
                import lombok.Setter;

                /** The entity of the graph; Lombok writes the accessors the processor resolves. */
                @Getter
                @Setter
                public final class Order {

                    @GraphProperty
                    private String id = "order";

                    @GraphProperty
                    private Status status = Status.NEW;
                }
                """);
        sources.put("fx.Actions", """
                package fx;

                import io.stategraph.annotation.GraphAction;
                import io.stategraph.api.ActionOutcome;
                import io.stategraph.api.TransitionContext;

                /** The action host of the graph. */
                public final class Actions {

                    @GraphAction
                    public ActionOutcome approve(TransitionContext<Order, Status> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        sources.put("fx.Orders", """
                package fx;

                import io.stategraph.annotation.GenerateGraphApi;
                import io.stategraph.api.Machine;
                import io.stategraph.api.Outcome;

                import static fx.Status.DONE;
                import static fx.Status.NEW;

                /** The machine of the fixture. */
                @GenerateGraphApi
                @OrdersGraph.Definition(initial = NEW, terminals = DONE,
                    state = OrderRefs.Status.class, id = OrderRefs.Id.class)
                @OrdersGraph.Row(state = NEW, otherwise = "a new order is approved")
                @OrdersGraph.Row(state = DONE, otherwise = "a finished order does not move")
                public interface Orders extends Machine<Order, Status> {

                    @OrdersGraph.Action(from = NEW, to = DONE, action = ActionsRefs.Approve.class)
                    Outcome<Order, Status> approve(Order order);
                }
                """);
        sources.put("fx.Unrelated", """
                package fx;

                /** A type no marker mentions, kept to prove that cleanup is selective. */
                public final class Unrelated {

                    private Unrelated() {
                    }
                }
                """);
        return sources;
    }

    /**
     * Writes {@code sources} under {@code src/main/java}, one file per qualified name, replacing whatever the
     * main source root held before, which is what editing a source tree does.
     *
     * @param sources the compilation units, keyed by qualified name
     * @throws IOException if the sources cannot be written
     */
    public void writeSources(Map<String, String> sources) throws IOException {
        Path root = directory.resolve("src/main/java");
        deleteRecursively(root);
        Files.createDirectories(root);
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = root.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Replaces the sources with the one type that carries no StateGraph marker, so the next build generates and
     * publishes nothing while still compiling the project.
     *
     * @throws IOException if the sources cannot be rewritten
     */
    public void removeAllStateGraphDeclarations() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.Unrelated", domainSources().get("fx.Unrelated"));
        writeSources(sources);
    }

    /**
     * Runs {@code goals} with the fixture's settings and local repository, in the project directory, merging
     * both streams of the process in the order Maven wrote them.
     *
     * @param goals the Maven goals and phases to run
     * @return the status and output of the build
     * @throws IOException if the process cannot be started or its output cannot be read
     */
    public MavenResult run(String... goals) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("stategraph.mavenExecutable", "mvn"));
        command.add("-B");
        command.add("-s");
        command.add(settings.toString());
        command.add("-Dmaven.repo.local=" + localRepository());
        command.addAll(List.of(goals));
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        String output;
        try (InputStream stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            return new MavenResult(process.waitFor(), output);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroy();
            throw new IllegalStateException("interrupted while running " + String.join(" ", command),
                    interrupted);
        }
    }

    /** The one local repository every fixture build of this module writes to; never the developer's {@code ~/.m2}. */
    private static String localRepository() {
        String configured = System.getProperty("stategraph.mavenLocalRepository");
        if (configured == null) {
            throw new IllegalStateException("stategraph.mavenLocalRepository is not set; these tests must run"
                    + " through the Gradle test task, which points it at this module's build directory");
        }
        return configured;
    }

    /**
     * The fixture's project directory.
     *
     * @return the project directory
     */
    public Path projectDirectory() {
        return directory;
    }

    /**
     * Whether {@code relativePath} exists under the project directory.
     *
     * @param relativePath a path relative to the project directory
     * @return {@code true} if the file or directory exists
     */
    public boolean exists(String relativePath) {
        return Files.exists(directory.resolve(relativePath));
    }

    /**
     * Whether the generator wrote the carrier of this compilation into the generated source directory: the
     * tool-internal type that holds {@code @GraphCompilation} and that the annotation processor reads.
     *
     * @return {@code true} if a generated source carries the compilation marker
     */
    public boolean generatedSourcesContainGraphCompilation() {
        Path carriers = directory.resolve("target/generated-sources/stategraph").resolve(CARRIER_PACKAGE);
        if (!Files.isDirectory(carriers)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(carriers)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                if (Files.readString(file, StandardCharsets.UTF_8).contains(CARRIER_MARKER)) {
                    return true;
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return false;
    }

    /**
     * Whether the project's jar carries {@code entry}.
     *
     * @param entry the jar entry name
     * @return {@code true} if the jar declares that entry
     */
    public boolean jarContains(String entry) {
        return jarEntries().contains(entry);
    }

    /**
     * Whether the jar carries the compilation carrier, which belongs to the build and must never be published.
     *
     * @return {@code true} if a carrier class reached the jar
     */
    public boolean jarContainsGraphCompilationCarrier() {
        for (String entry : jarEntries()) {
            if (entry.startsWith(CARRIER_PACKAGE + "/") && entry.endsWith(".class")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The names the index published inside the jar exports: every machine facade and every symbol.
     *
     * @return the exported names, in the order the index declares them
     */
    public List<String> localExports() {
        JsonObject model = readJarJson(EXPORTS).getAsJsonObject("model");
        List<String> exports = new ArrayList<>();
        for (JsonElement graph : model.getAsJsonArray("graphs")) {
            exports.add(graph.getAsJsonObject().get("facade").getAsString());
        }
        for (JsonElement symbol : model.getAsJsonArray("symbols")) {
            exports.add(symbol.getAsJsonObject().get("qualifiedName").getAsString());
        }
        return List.copyOf(exports);
    }

    /**
     * Whether the compilation of {@code sourceSet} left a sealed inventory: the final one is there and no
     * pending one is.
     *
     * @param sourceSet the name of the source set, {@code main} or {@code test}
     * @return {@code true} if the inventory is sealed
     */
    public boolean inventoryIsSealed(String sourceSet) {
        Path work = directory.resolve("target/stategraph/" + sourceSet);
        return Files.isRegularFile(work.resolve("owned-outputs.json"))
                && !Files.exists(work.resolve("owned-outputs.pending.json"));
    }

    /** The names of every entry of the project's jar. */
    private List<String> jarEntries() {
        List<String> entries = new ArrayList<>();
        try (ZipFile jar = new ZipFile(jar().toFile(), StandardCharsets.UTF_8)) {
            for (ZipEntry entry : jar.stream().toList()) {
                entries.add(entry.getName());
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return entries;
    }

    /** Reads one jar entry as a JSON object. */
    private JsonObject readJarJson(String entry) {
        try (ZipFile jar = new ZipFile(jar().toFile(), StandardCharsets.UTF_8)) {
            ZipEntry found = jar.getEntry(entry);
            if (found == null) {
                throw new IllegalStateException("the jar " + jar().getFileName() + " carries no " + entry);
            }
            try (InputStream stream = jar.getInputStream(found);
                    Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The single jar the fixture's {@code package} phase produced. */
    private Path jar() {
        return directory.resolve("target/fixture-1.0.0.jar");
    }

    /** Removes {@code root} and everything below it, deepest entry first; an absent root is nothing to do. */
    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
