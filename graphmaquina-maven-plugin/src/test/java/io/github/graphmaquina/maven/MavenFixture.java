package io.github.graphmaquina.maven;

import com.google.gson.JsonArray;
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
    private static final String EXPORTS = "META-INF/graphmaquina/exports.json";

    /** The package the generator emits the carrier of one compilation into. */
    private static final String CARRIER_PACKAGE = "io/github/graphmaquina/generated/bootstrap";

    /** The annotation that carrier holds, which is what makes a generated source the carrier. */
    private static final String CARRIER_MARKER = "@GraphCompilation(";

    /** The nested types of the retired matrix vocabulary; no 2.0 artefact may carry one. */
    private static final List<String> RETIRED_VOCABULARY =
            List.of("$Row", "$Cell", "$Forbid", "$Action", "$Move");

    private final Path directory;
    private final Path settings;
    private final String artifactId;

    private MavenFixture(Path directory, Path settings, String artifactId) {
        this.directory = directory;
        this.settings = settings;
        this.artifactId = artifactId;
    }

    /**
     * Writes the consumer project of {@code src/test/resources/it/basic/pom.xml} with the shared adapter domain
     * — {@code fx.Status}, {@code fx.Transition}, {@code fx.Order}, {@code fx.Actions}, {@code fx.Orders} and
     * the unmarked {@code fx.Unrelated} — and the settings file that resolves StateGraph from {@code repository}.
     *
     * @param directory the temporary directory to materialize the project under
     * @param repository the file Maven repository the StateGraph artifacts were published to
     * @return a fixture bound to the written project
     * @throws IOException if the project cannot be written
     */
    public static MavenFixture basic(Path directory, Path repository) throws IOException {
        MavenFixture fixture = write(directory, "fixture project ñ", "/it/basic/pom.xml", "fixture",
                repository);
        fixture.writeSources(domainSources());
        return fixture;
    }

    /**
     * Writes a second project that depends on the installed jar of {@link #basic} and on nothing else of it:
     * no source of the producer is on its source path, so everything its generation resolves comes from the
     * index the producer published inside that jar.
     *
     * @param directory the temporary directory to materialize the project under
     * @param repository the file Maven repository the StateGraph artifacts were published to
     * @return a fixture bound to the written project
     * @throws IOException if the project cannot be written
     */
    public static MavenFixture consumer(Path directory, Path repository) throws IOException {
        return write(directory, "consumer project ñ", "/it/consumer/pom.xml", "consumer", repository);
    }

    /**
     * Turns this project into the {@code pom} parent of one {@code jar} module, {@code app}, which declares nothing
     * but its parent: the compiler configuration and the six StateGraph executions reach it by inheritance, the
     * way a multi-module build configures them once. The shared domain moves into the module, so the parent
     * itself has nothing to compile.
     *
     * @return a fixture bound to the module; {@link #run} on this fixture builds the parent and the module
     * @throws IOException if the projects cannot be rewritten
     */
    public MavenFixture module() throws IOException {
        editPom("<packaging>jar</packaging>", """
                <packaging>pom</packaging>

                  <modules>
                    <module>app</module>
                  </modules>""");
        deleteRecursively(directory.resolve("src"));
        Path module = Files.createDirectories(directory.resolve("app"));
        Files.writeString(module.resolve("pom.xml"), template("/it/module/pom.xml"), StandardCharsets.UTF_8);
        MavenFixture fixture = new MavenFixture(module, settings, "app");
        fixture.writeSources(domainSources());
        return fixture;
    }

    /** Materializes one project: its POM from the test resources and the settings file of every build. */
    private static MavenFixture write(Path directory, String name, String pom, String artifactId,
            Path repository) throws IOException {
        if (!Files.isDirectory(repository)) {
            throw new IllegalStateException("the fixture repository " + repository + " does not exist; run"
                    + " ./gradlew :graphmaquina-maven-plugin:publishMavenPublicationToAdapterTestsRepository"
                    + " :graphmaquina-core:publishMavenPublicationToAdapterTestsRepository"
                    + " :graphmaquina-codegen:publishMavenPublicationToAdapterTestsRepository"
                    + " :graphmaquina-processor:publishMavenPublicationToAdapterTestsRepository first");
        }
        Path project = Files.createDirectories(directory.resolve(name));
        Files.writeString(project.resolve("pom.xml"), template(pom), StandardCharsets.UTF_8);
        Path settings = project.resolve("settings.xml");
        Files.writeString(settings, settings(repository), StandardCharsets.UTF_8);
        return new MavenFixture(project, settings, artifactId);
    }

    /**
     * One POM of the consumer projects, read from the test resources rather than duplicated here.
     * {@code @GRAPHMAQUINA_VERSION@} is the only token replaced, with the version this build resolved.
     */
    private static String template(String resource) throws IOException {
        try (InputStream stream = MavenFixture.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("the fixture POM " + resource + " is not on the test classpath");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("@GRAPHMAQUINA_VERSION@", graphmaquinaVersion());
        }
    }

    /** The version every StateGraph artifact of the fixture repository is published under. */
    private static String graphmaquinaVersion() {
        String configured = System.getProperty("graphmaquina.version");
        if (configured == null) {
            throw new IllegalStateException("graphmaquina.version is not set; these tests must run through the"
                    + " Gradle test task, which points it at the version this build resolved");
        }
        return configured;
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
                      <id>graphmaquina-fixture</id>
                      <repositories>
                        <repository>
                          <id>graphmaquina-adapter-tests</id>
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
                          <id>graphmaquina-adapter-tests</id>
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
                    <activeProfile>graphmaquina-fixture</activeProfile>
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
        sources.put("fx.Transition", """
                package fx;

                /** The identified transitions of the order graph. */
                public enum Transition { APPROVE }
                """);
        sources.put("fx.Order", """
                package fx;

                import io.github.graphmaquina.annotation.GraphProperty;
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

                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;

                /** The action host of the graph. */
                public final class Actions {

                    @GraphAction
                    public ActionOutcome approve(TransitionContext<Order, Status, Transition> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        sources.put("fx.Orders", orders("Orders", "Approve", "approve"));
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
     * One facade declaration of the shared domain, so a test can rename the machine or the action it binds.
     *
     * @param facade the simple name of the machine interface
     * @param symbol the simple name of the generated action symbol the operation binds
     * @param operation the name of the facade's operation
     * @return the source of the facade
     */
    public static String orders(String facade, String symbol, String operation) {
        return """
                package fx;

                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;

                import static fx.Status.DONE;
                import static fx.Status.NEW;
                import static fx.Transition.APPROVE;

                /** The machine of the fixture. */
                @GenerateGraphApi
                @%1$sGraph.Definition(initial = NEW, terminals = DONE,
                    state = OrderRefs.Status.class, id = OrderRefs.Id.class)
                @%1$sGraph.StatePolicy(state = NEW, otherwise = "a new order is approved")
                public interface %1$s extends Machine<Order, Status, Transition> {

                    @%1$sGraph.Transition(id = APPROVE, from = NEW, to = DONE, action = ActionsRefs.%2$s.class)
                    Outcome<Order, Status, Transition> %3$s(Order order);
                }
                """.formatted(facade, symbol, operation);
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
            write(root, source.getKey(), source.getValue());
        }
    }

    /**
     * Writes one compilation unit under {@code src/test/java}.
     *
     * @param qualifiedName the qualified name of the type
     * @param content the source text
     * @throws IOException if the source cannot be written
     */
    public void writeTestSource(String qualifiedName, String content) throws IOException {
        write(Files.createDirectories(directory.resolve("src/test/java")), qualifiedName, content);
    }

    private static void write(Path root, String qualifiedName, String content) throws IOException {
        Path file = root.resolve(qualifiedName.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /**
     * Replaces one literal of the project's POM, so a test can build the same project with a compiler that is
     * configured differently.
     *
     * @param literal the text to replace, which must occur exactly once
     * @param replacement the text to write in its place
     * @throws IOException if the POM cannot be rewritten
     */
    public void editPom(String literal, String replacement) throws IOException {
        Path pom = directory.resolve("pom.xml");
        String current = Files.readString(pom, StandardCharsets.UTF_8);
        int first = current.indexOf(literal);
        if (first < 0 || current.indexOf(literal, first + literal.length()) >= 0) {
            throw new IllegalStateException("the fixture POM does not carry '" + literal + "' exactly once");
        }
        Files.writeString(pom, current.replace(literal, replacement), StandardCharsets.UTF_8);
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
     * <p>Maven runs on the JDK running these tests, whatever {@code JAVA_HOME} the shell that started them
     * exported: javac compiles inside that JVM unless a build forks it, so this is the JDK every test reasons
     * about when it compares the compiler with the generator.</p>
     *
     * @param goals the Maven goals and phases to run
     * @return the status and output of the build
     * @throws IOException if the process cannot be started or its output cannot be read
     */
    public MavenResult run(String... goals) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("graphmaquina.mavenExecutable", "mvn"));
        command.add("-B");
        command.add("-s");
        command.add(settings.toString());
        command.add("-Dmaven.repo.local=" + localRepository());
        command.addAll(List.of(goals));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true);
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process process = builder.start();
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
        String configured = System.getProperty("graphmaquina.mavenLocalRepository");
        if (configured == null) {
            throw new IllegalStateException("graphmaquina.mavenLocalRepository is not set; these tests must run"
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
        Path carriers = directory.resolve("target/generated-sources/graphmaquina").resolve(CARRIER_PACKAGE);
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
     * The single carrier source of the main generation: the tool-internal type every generation emits, whose
     * timestamp the preparation dates past the outputs it retired.
     *
     * @return the carrier source of the main compilation
     */
    public Path carrierSource() {
        Path carriers = directory.resolve("target/generated-sources/graphmaquina").resolve(CARRIER_PACKAGE);
        try (Stream<Path> walk = Files.walk(carriers)) {
            List<Path> sources = walk.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".java"))
                    .toList();
            if (sources.size() != 1) {
                throw new IllegalStateException("the generation left " + sources.size() + " carrier sources"
                        + " under " + carriers + ", not one");
            }
            return sources.get(0);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
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
     * Whether the jar carries a binary the sealed inventory of the main compilation excluded from publication,
     * which is the compilation carrier every generation produces.
     *
     * @return {@code true} if one of the excluded binaries reached the jar
     */
    public boolean jarContainsGraphCompilationCarrier() {
        JsonArray excluded = readJson(directory.resolve("target/graphmaquina/main/owned-outputs.json"))
                .getAsJsonArray("excludedBinaries");
        if (excluded.isEmpty()) {
            throw new IllegalStateException("the sealed inventory excluded no binary, so this fixture cannot"
                    + " tell whether the carrier reached the jar");
        }
        List<String> entries = jarEntries();
        for (JsonElement binary : excluded) {
            if (entries.contains(binary.getAsString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The entries of the project's jar that declare a nested type of the retired matrix vocabulary, which a
     * 2.0 artefact never publishes and a downstream project therefore never inherits.
     *
     * @return the offending entry names, empty when the jar carries only the graph vocabulary
     */
    public List<String> retiredVocabularyEntries() {
        List<String> found = new ArrayList<>();
        for (String entry : jarEntries()) {
            for (String retired : RETIRED_VOCABULARY) {
                if (entry.contains(retired)) {
                    found.add(entry);
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * The identifiers the index published inside the jar records for {@code facade}, each qualified by the
     * transition enum that types it, so a consumer's peer identifiers can be read back as the producer typed
     * them.
     *
     * @param facade the canonical name of the machine
     * @return {@code <transitionType>.<id>} for every identifier of that machine, in declaration order
     */
    public List<String> transitionsOf(String facade) {
        for (JsonElement graph : readJarJson(EXPORTS).getAsJsonObject("model").getAsJsonArray("graphs")) {
            JsonObject object = graph.getAsJsonObject();
            if (object.get("facade").getAsString().equals(facade)) {
                List<String> transitions = new ArrayList<>();
                String type = object.get("transitionType").getAsString();
                for (JsonElement id : object.getAsJsonArray("transitions")) {
                    transitions.add(type + '.' + id.getAsString());
                }
                return List.copyOf(transitions);
            }
        }
        throw new IllegalStateException("the published index declares no graph for " + facade);
    }

    /**
     * The names the index published inside the jar exports: every machine facade and every symbol.
     *
     * @return the exported names, in the order the index declares them
     */
    public List<String> localExports() {
        return exports(readJarJson(EXPORTS));
    }

    /**
     * The names the index at {@code relativePath} exports, for the compilations that publish no jar.
     *
     * @param relativePath a path relative to the project directory
     * @return the exported names, in the order the index declares them
     */
    public List<String> localExportsOf(String relativePath) {
        return exports(readJson(directory.resolve(relativePath)));
    }

    /** The facades and symbols of one published index, in the order it declares them. */
    private static List<String> exports(JsonObject index) {
        JsonObject model = index.getAsJsonObject("model");
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
     * The value of one string member of the request the goals of {@code sourceSet} wrote.
     *
     * @param sourceSet the name of the source set, {@code main} or {@code test}
     * @param member the name of the member
     * @return the value the plugin encoded
     */
    public String requestValue(String sourceSet, String member) {
        return readJson(directory.resolve("target/graphmaquina/" + sourceSet + "/request.json"))
                .get(member).getAsString();
    }

    /**
     * Whether the compilation of {@code sourceSet} left a sealed inventory: the final one is there and no
     * pending one is.
     *
     * @param sourceSet the name of the source set, {@code main} or {@code test}
     * @return {@code true} if the inventory is sealed
     */
    public boolean inventoryIsSealed(String sourceSet) {
        Path work = directory.resolve("target/graphmaquina/" + sourceSet);
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

    /** Reads one file of the project as a JSON object. */
    private static JsonObject readJson(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The single jar the fixture's {@code package} phase produced. */
    private Path jar() {
        return directory.resolve("target/" + artifactId + "-1.0.0.jar");
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
